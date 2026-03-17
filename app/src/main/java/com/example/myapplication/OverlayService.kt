package com.example.myapplication

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import androidx.core.app.NotificationCompat

class OverlayService : Service(), AudioCaptureManager.Listener {

    private lateinit var windowManager: WindowManager
    private var overlayView: DancerOverlayView? = null
    private lateinit var windowParams: WindowManager.LayoutParams
    private lateinit var captureManager: AudioCaptureManager
    private lateinit var settingsRepository: OverlaySettingsRepository
    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentSettings = OverlaySettings(
        sensitivity = 1f, // 灵敏度
        avatarScale = 1f, // 头像缩放
        avatarAlpha = 0.92f, // 头像透明度
        showModeText = true, // 显示模式文本
        lockPosition = false, // 锁定位置
        autoStartOnBoot = false, // 启动时自动开始
        useAvatarVariant1 = false, // useAvatarVariant1 (默认 false)
        avatarDir = AvatarAssets.DIR_AVATAR,
        avatarVariantDir = AvatarAssets.DIR_AVATAR1,
        avatarAnchorOffsetPercent = 0f, // 头像锚点偏移百分比
        audioActivityThreshold = 0.05f, // 音频活动阈值
        audioInactivityTimeoutMs = 1500, // 音频无活动超时（毫秒）
    )
    private var currentDanceStyle = DanceStyle.CHILL
    private var currentPlaybackState = PlaybackDanceState.STOPPED
    private var lastAudioActivityMs: Long = 0L
    // 音频无活动超时后停止播放，显示结束精灵
    private val stopPlaybackRunnable = Runnable {
        // 使用 currentSettings.audioInactivityTimeoutMs 进行信息性日志记录
        currentPlaybackState = PlaybackDanceState.STOPPED
        // 应用停止状态，由悬浮窗视图决定何时显示结束/待机精灵（以免覆盖正在显示的 begin 精灵）
        overlayView?.setPlaybackState(currentPlaybackState)
    }
    // 已移除歌曲识别；不跟踪歌曲签名
    private val rhythmStyleEngine = RhythmStyleEngine()
    private var isForegroundActive = false
    // 定时刷新媒体上下文的任务
    private val mediaStateRefreshRunnable = object : Runnable {
        override fun run() {
            refreshMediaContext()
            mainHandler.postDelayed(this, MEDIA_STATE_REFRESH_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannelIfNeeded() // 创建通知渠道（仅 Android 8.0+）
        isRunning = true
        // 初始化窗口管理器、设置仓库、音频捕获管理器
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        settingsRepository = OverlaySettingsRepository(this)
        currentSettings = settingsRepository.get()
        captureManager = AudioCaptureManager(this, this)
        preloadAvatarResources(currentSettings)

        // 启动定时刷新媒体上下文
        mainHandler.post(mediaStateRefreshRunnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 处理服务启动命令
        when (intent?.action) {
            ACTION_STOP -> {
                // 停止服务
                stopSelf()
            }
            ACTION_HIDE -> {
                // 隐藏悬浮窗
                setOverlayVisibility(false)
            }
            ACTION_SHOW -> {
                // 显示悬浮窗
                ensureOverlay()
                setOverlayVisibility(true)
            }
            ACTION_START, null -> {
                // 启动悬浮窗，显示“开始”精灵
                val resultCode = if (intent?.hasExtra(EXTRA_CAPTURE_RESULT_CODE) == true) {
                    intent.getIntExtra(EXTRA_CAPTURE_RESULT_CODE, 0)
                } else {
                    null
                }
                val captureData = intent?.getParcelableExtraCompat(EXTRA_CAPTURE_DATA)

                if (resultCode == null || captureData == null) {
                    // Android 14+ 上，mediaProjection FGS 必须有用户授权的数据，否则无法启动
                    stopSelf()
                    return START_NOT_STICKY
                }

                if (!startForegroundSafely()) {
                    stopSelf()
                    return START_NOT_STICKY
                }

                captureManager.start(resultCode, captureData)
                ensureOverlay()
                setOverlayVisibility(true)
                // 启动悬浮窗时在悬浮窗内显示“开始”精灵（dancer_single_begin.png）
                // 注意：不要在启动时强制进入播放态（PLAYING），如果没有音频活动或节拍，
                // 悬浮小人应保持待机（STOPPED）。因此仅显示 begin 精灵，状态保持为 STOPPED，
                // 后续由音频/节拍回调切换到 PLAYING。
                // 将播放状态设置为 STOPPED（待机），但先在悬浮窗内显示 begin 精灵（覆盖当前待机显示），
                // showBeginSprite 会在超时后根据是否有节拍决定恢复动画或保持待机。
                currentPlaybackState = PlaybackDanceState.STOPPED
                overlayView?.showBeginSprite()
                // 然后应用播放状态；DancerOverlayView 会在 begin 的保护期内避免立即覆盖 begin 显示
                overlayView?.setPlaybackState(currentPlaybackState)
                refreshMediaContext()
            }
            ACTION_APPLY_SETTINGS -> {
                // 设置界面请求重新应用设置
                reloadSettings()
                // 重新应用播放状态，确保动画和 UI 一致
                overlayView?.setPlaybackState(currentPlaybackState)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        // 服务销毁，清理资源
        isRunning = false
        mainHandler.removeCallbacks(mediaStateRefreshRunnable)
        captureManager.stop()
        // 停止前将悬浮窗状态设为已停止，并显示结束精灵
        currentPlaybackState = PlaybackDanceState.STOPPED
        overlayView?.setPlaybackState(currentPlaybackState)
        overlayView?.showEndSprite()
        // 移除悬浮窗
        removeOverlay()
        super.onDestroy()
    }

    // 不支持绑定
    override fun onBind(intent: Intent?): IBinder? = null

    // 音频捕获模式变更回调
    override fun onCaptureModeChanged(mode: AudioCaptureManager.CaptureMode) {
        mainHandler.post {
            overlayView?.setCaptureMode(mode.displayName)
            refreshMediaContext()
            val text = getString(R.string.notif_active_mode, mode.displayName)
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, buildNotification(text))
        }
    }

    // 音频电平回调
    override fun onAudioLevel(level: Float) {
        mainHandler.post {
            overlayView?.setAudioLevel(level)
            // 持续音频视为播放活动
            val threshold = currentSettings.audioActivityThreshold.coerceIn(0f, 1f)
            if (level > threshold) {
                // 记录最近一次音频活动，用于 inactivity 超时判断
                lastAudioActivityMs = System.currentTimeMillis()
                // 不直接切换为 PLAYING，避免在没有节拍（例如噪声）时误判为播放。
                // 节拍回调 onBeat() 将在检测到有效节拍时切换为 PLAYING。
                mainHandler.removeCallbacks(stopPlaybackRunnable)
                mainHandler.postDelayed(stopPlaybackRunnable, currentSettings.audioInactivityTimeoutMs.toLong())
            }
        }
    }

    // 节拍事件回调
    override fun onBeat(event: BeatEvent) {
        mainHandler.post {
            refreshMediaContext()
            // 解析节奏风格
            val targetStyle = rhythmStyleEngine.resolveStyle(event, currentDanceStyle, null)
            if (targetStyle != currentDanceStyle) {
                currentDanceStyle = targetStyle
                overlayView?.setDanceStyle(targetStyle)
            }
            // 节拍到达时标记为播放活动
            lastAudioActivityMs = System.currentTimeMillis()
            if (currentPlaybackState != PlaybackDanceState.PLAYING) {
                // 切换为播放状态
                currentPlaybackState = PlaybackDanceState.PLAYING
                overlayView?.setPlaybackState(currentPlaybackState)
            }
            mainHandler.removeCallbacks(stopPlaybackRunnable)
            mainHandler.postDelayed(stopPlaybackRunnable, currentSettings.audioInactivityTimeoutMs.toLong())
            overlayView?.onBeat(event)
        }
    }

    // 刷新媒体上下文（已禁用歌曲识别，仅刷新播放状态）
    private fun refreshMediaContext() {
        overlayView?.setPlaybackState(currentPlaybackState)
    }


    // 确保悬浮窗已创建
    private fun ensureOverlay() {
        if (overlayView != null) return

        windowParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 120
            y = 240
        }

        overlayView = DancerOverlayView(this)
        overlayView?.applySettings(currentSettings)
        overlayView?.setDanceStyle(currentDanceStyle)
        overlayView?.setPlaybackState(currentPlaybackState)
        installDragBehavior(overlayView!!)
        windowManager.addView(overlayView, windowParams)
    }

    // 重新加载设置并应用到悬浮窗
    private fun reloadSettings() {
        currentSettings = settingsRepository.get()
        preloadAvatarResources(currentSettings)
        overlayView?.applySettings(currentSettings)
    }

    private fun preloadAvatarResources(settings: OverlaySettings) {
        // 预热两套形象的预览资源，让用户在 avatar / avatar1 之间来回切换时首图更快出现。
        AvatarLoader.preloadSingleSpriteSet(
            context = applicationContext,
            preferredDir = settings.avatarDir,
            maxFrames = AVATAR_PRELOAD_FRAME_COUNT,
        )

        if (settings.avatarVariantDir != settings.avatarDir) {
            AvatarLoader.preloadSingleSpriteSet(
                context = applicationContext,
                preferredDir = settings.avatarVariantDir,
                maxFrames = AVATAR_PRELOAD_FRAME_COUNT,
            )
        }
    }

    // 移除悬浮窗
    private fun removeOverlay() {
        overlayView?.let { view ->
            kotlin.runCatching { windowManager.removeView(view) }
        }
        overlayView = null
    }

    // 设置悬浮窗可见性
    private fun setOverlayVisibility(visible: Boolean) {
        overlayView?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    // 启动前台服务，显示通知
    private fun startForegroundSafely(): Boolean {
        if (isForegroundActive) return true
        val notification = buildNotification(getString(R.string.notif_idle))
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            isForegroundActive = true
        }.isSuccess
    }

    // 安装悬浮窗拖动行为（支持锁定位置）
    private fun installDragBehavior(view: View) {
        // 当 lockPosition 为 true 时禁止拖动
        // 触摸监听器绑定到 view 及其所有子视图，实现整体拖动
        var startX = 0
        var startY = 0
        var touchStartX = 0f
        var touchStartY = 0f
        var moved = false
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop

        val touchListener = View.OnTouchListener { touchedView, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (currentSettings.lockPosition) {
                        // 锁定位置时不消费 ACTION_DOWN，让点击事件继续传递
                        return@OnTouchListener false
                    }
                    startX = windowParams.x
                    startY = windowParams.y
                    touchStartX = event.rawX
                    touchStartY = event.rawY
                    moved = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchStartX).toInt()
                    val dy = (event.rawY - touchStartY).toInt()
                    if (!moved && (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop)) {
                        moved = true
                    }
                    windowParams.x = startX + dx
                    windowParams.y = startY + dy
                    windowManager.updateViewLayout(view, windowParams)
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        touchedView.performClick()
                    }
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    moved = false
                    false
                }

                else -> false
            }
        }

        // 绑定到容器本身
        view.setOnTouchListener(touchListener)

        // 遍历所有子视图，设置相同的触摸监听器
        if (view is android.view.ViewGroup) {
            val stack = ArrayDeque<View>()
            for (i in 0 until view.childCount) stack.add(view.getChildAt(i))
            while (stack.isNotEmpty()) {
                val v = stack.removeLast()
                try {
                    v.setOnTouchListener(touchListener)
                } catch (_: Exception) {
                    // 忽略无法设置的视图
                }
                if (v is android.view.ViewGroup) {
                    for (i in 0 until v.childCount) stack.add(v.getChildAt(i))
                }
            }
        }
    }

    // 创建通知渠道（仅 Android 8.0+）
    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    // 构建前台服务通知
    private fun buildNotification(content: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(content)
            .setOngoing(true)
            .build()
    }

    // 兼容不同 Android 版本的 getParcelableExtra
    private fun Intent.getParcelableExtraCompat(key: String): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(key, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(key)
        }
    }

    companion object {
        const val ACTION_START = "com.example.myapplication.action.START"
        const val ACTION_STOP = "com.example.myapplication.action.STOP"
        const val ACTION_HIDE = "com.example.myapplication.action.HIDE"
        const val ACTION_SHOW = "com.example.myapplication.action.SHOW"
        const val EXTRA_CAPTURE_RESULT_CODE = "extra.capture.result.code"
        const val EXTRA_CAPTURE_DATA = "extra.capture.data"
        @Volatile
        var isRunning: Boolean = false
            private set

        private const val MEDIA_STATE_REFRESH_INTERVAL_MS = 1_000L
        private const val CHANNEL_ID = "dancer_overlay_channel"
        private const val NOTIFICATION_ID = 1001
        private const val AVATAR_PRELOAD_FRAME_COUNT = 3
        const val ACTION_APPLY_SETTINGS = "com.example.myapplication.action.APPLY_SETTINGS"
    }
}


