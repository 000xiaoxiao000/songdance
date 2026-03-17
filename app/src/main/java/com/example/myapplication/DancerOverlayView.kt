package com.example.myapplication

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import kotlinx.coroutines.*

class DancerOverlayView(context: Context) : FrameLayout(context), BeatReactiveAvatar {
    private enum class DeferredSpriteType {
        BEGIN,
        END,
    }

    private data class DeferredSpriteRequest(
        val type: DeferredSpriteType,
        val durationMs: Long = 1200L,
    )

    private data class AvatarSourceConfig(
        val useAvatarVariant1: Boolean,
        val avatarDir: String,
        val avatarVariantDir: String,
    ) {
        val targetDir: String
            get() = if (useAvatarVariant1) avatarVariantDir else avatarDir
    }

    // 移除 avatarImageView，统一使用 OpenGL ES 渲染
    private val openGlView = OpenGLESView(context)
    private val modeText = TextView(context)
    private val bpmText = TextView(context)
    // 目标基准尺寸（dp），用于统一缩放上传到 GL 的位图
    private var baseSizeDp = 256f
    private var currentDanceStyle = DanceStyle.CHILL
    private var currentPlaybackState = PlaybackDanceState.STOPPED
    private var latestBpm = 0f
    // 缓存的音频电平（0..1），由 setAudioLevel 更新供渲染使用
    private var overlayAudioLevel = 0f
    private var currentAvatarSource: AvatarSourceConfig? = null
    // 图片帧序列（统一由 AvatarLoader 加载）
    private var avatarFrames: List<AvatarLoader.LoadedSprite> = emptyList()
    private var currentFrameIndex = 0
    // begin/end 特殊帧
    private var beginBitmap: android.graphics.Bitmap? = null
    private var endBitmap: android.graphics.Bitmap? = null
    // 用于在没有明显节拍事件时也能让悬浮小人按一定速率切帧，模拟跳舞
    private val frameHandler = Handler(Looper.getMainLooper())
    private var frameTickerRunning = false
    // 默认帧间隔（毫秒），会根据节拍事件调整
    private var frameIntervalMs = 350L
    private val frameTickerRunnable = object : Runnable {
        override fun run() {
            // 跳舞前清理 begin/end 图
            clearBeginEndState()
            // 仅当处于播放态且检测到节拍（latestBpm>0）时才循环自动切帧
            if (currentPlaybackState == PlaybackDanceState.PLAYING && avatarFrames.isNotEmpty() && latestBpm > 0f) {
                currentFrameIndex = (currentFrameIndex + 1) % avatarFrames.size
                loadAvatarFrame(currentFrameIndex)
                frameHandler.postDelayed(this, frameIntervalMs)
            } else {
                // 停止定时器并退出，避免在无节拍时继续动画
                frameTickerRunning = false
                frameHandler.removeCallbacks(this)
            }
        }
    }
    // Bitmap 缓存（最近若干帧），避免每次解码/缩放
    private val avatarBitmapCache = object : android.util.LruCache<String, android.graphics.Bitmap>(12) {
        // 当条目被移除或驱逐时，将位图放回复用池而不是直接回收，以便 inBitmap 或 Canvas 重用
        override fun entryRemoved(evicted: Boolean, key: String?, oldValue: android.graphics.Bitmap?, newValue: android.graphics.Bitmap?) {
            oldValue?.let { bmp ->
                if (!bmp.isRecycled) {
                    // 放回复用池或回收：使用外部方法
                    releaseReusableBitmap(bmp)
                }
            }
        }
    }
    // 可重用位图池，用于在解码/缩放后重复利用内存（可选）
    private val reusableBitmaps = ArrayDeque<android.graphics.Bitmap>(4)
    // 当前用户设置的缩放比例（由 applySettings 更新），用于计算目标像素尺寸
    private var currentScale = 1.0f
    // 标志：当前是否显示 begin 或 end 图
    private var isShowingBeginOrEnd = false
    // 可取消的 begin 恢复 Runnable 引用，防止重复/并发恢复任务
    private var beginRestoreRunnable: Runnable? = null
    // begin 活动保护时间戳（毫秒），在此时间内外部状态更新不应覆盖 begin 显示
    private var beginActiveUntilMs: Long = 0L
    // 协程作用域，用于异步加载资源
    private val viewScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    // 资源尚未完成加载时，暂存 begin/end 显示请求，避免启动瞬间丢失首帧。
    private var deferredSpriteRequest: DeferredSpriteRequest? = null
    // 资源异步加载版本号，避免旧请求回写覆盖最新配置。
    private var avatarLoadVersion = 0L

    companion object {
        private const val MAX_AVATAR_FRAMES = 80
        private const val PREVIEW_FRAME_COUNT = 3
    }

    init {
        setBackgroundColor(Color.TRANSPARENT)
        setPadding(8, 24, 8, 24)
        minimumWidth = 248
        minimumHeight = 320

        modeText.apply {
            setTextColor(Color.rgb(140, 240, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            text = context.getString(R.string.overlay_mode_template, context.getString(R.string.capture_mode_playback))
        }

        bpmText.apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            text = context.getString(
                R.string.overlay_style_only_template,
                currentDanceStyle.displayName
            )
        }

        // 仅添加 openGlView
        addView(
            openGlView,
            LayoutParams(dpToPx(baseSizeDp), dpToPx(baseSizeDp)).apply {
                gravity = Gravity.CENTER
            }
        )
        addView(
            modeText,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = dpToPx(2f)
            }
        )
        addView(
            bpmText,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = dpToPx(2f)
            }
        )

        // 使悬浮窗可点击和可聚焦
        isClickable = true
        isFocusable = true

        // 点击时仅走统一 begin 流程
        val clickHandler = OnClickListener {
            if (currentPlaybackState == PlaybackDanceState.PLAYING && latestBpm > 0f) {
                showBeginSprite()
            }
        }
        setOnClickListener(clickHandler)

        // 仅为 openGlView 设置点击监听
        openGlView.setOnClickListener { performClick() }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // 释放 GL 资源
        try {
            openGlView.onPause()
            openGlView.releaseGLResources()
        } catch (_: Exception) {
        }
        // 取消协程
        viewScope.cancel()
        // 清理缓存位图：evictAll 会触发 entryRemoved 回收到 reusableBitmaps
        avatarBitmapCache.evictAll()
        // 回收复用池中的位图
        reusableBitmaps.forEach { bmp -> if (!bmp.isRecycled) bmp.recycle() }
        reusableBitmaps.clear()
        // 停止可能运行的帧定时器，避免 Handler 引用导致内存泄漏
        stopFrameTicker()
    }

    fun applySettings(settings: OverlaySettings) {
        openGlView.setAlphaValue(settings.avatarAlpha)
        // 强制显示 openGlView
        openGlView.visibility = VISIBLE

        // 更新当前缩放比例并调整视图尺寸
        val scaleChanged = currentScale != settings.avatarScale
        currentScale = settings.avatarScale
        val size = dpToPx(baseSizeDp * currentScale)
        openGlView.setSize(size)

        openGlView.layoutParams = (openGlView.layoutParams as? LayoutParams)?.apply {
            width = size
            height = size
        }
        openGlView.requestLayout()

        if (scaleChanged) {
            // 缩放改变后，旧尺寸缓存已经不再适用，清理后按当前状态重新渲染。
            avatarBitmapCache.evictAll()
        }

        val newSource = AvatarSourceConfig(
            useAvatarVariant1 = settings.useAvatarVariant1,
            avatarDir = settings.avatarDir,
            avatarVariantDir = settings.avatarVariantDir,
        )
        val needReload = currentAvatarSource != newSource || avatarFrames.isEmpty()
        currentAvatarSource = newSource

        if (needReload) {
            reloadAvatarResources(newSource)
        } else {
            renderAfterAvatarResourcesReady()
        }
    }

    private fun reloadAvatarResources(source: AvatarSourceConfig) {
        val targetDir = source.targetDir
        val loadVersion = ++avatarLoadVersion

        AvatarLoader.getCachedSingleSpriteSet(targetDir, MAX_AVATAR_FRAMES)?.let { cachedFullSet ->
            // 命中完整热缓存时直接切换，避免再次经历“预览 -> 完整”的等待。
            applyLoadedSpriteSet(loadVersion, targetDir, cachedFullSet, isPreview = false)
            return
        }

        val cachedPreviewSet = AvatarLoader.getCachedSingleSpriteSet(targetDir, PREVIEW_FRAME_COUNT)
        if (cachedPreviewSet != null) {
            applyLoadedSpriteSet(loadVersion, targetDir, cachedPreviewSet, isPreview = true)
        }

        viewScope.launch {
            if (cachedPreviewSet == null) {
                // 第一步：先加载少量预览帧，优先缩短切换形象后的首图等待时间。
                val previewSet = withContext(Dispatchers.IO) {
                    AvatarLoader.loadOrAwaitSingleSpriteSet(
                        context = context,
                        preferredDir = targetDir,
                        maxFrames = PREVIEW_FRAME_COUNT,
                    )
                }
                if (!applyLoadedSpriteSet(loadVersion, targetDir, previewSet, isPreview = true)) {
                    return@launch
                }
            }

            // 第二步：后台补齐完整序列，让动画在首图出现后继续变得完整流畅。
            val fullSet = withContext(Dispatchers.IO) {
                AvatarLoader.loadOrAwaitSingleSpriteSet(
                    context = context,
                    preferredDir = targetDir,
                    maxFrames = MAX_AVATAR_FRAMES,
                )
            }
            applyLoadedSpriteSet(loadVersion, targetDir, fullSet, isPreview = false)
        }
    }

    private fun applyLoadedSpriteSet(
        loadVersion: Long,
        targetDir: String,
        spriteSet: AvatarLoader.SingleSpriteSet,
        isPreview: Boolean,
    ): Boolean {
        if (loadVersion != avatarLoadVersion) {
            android.util.Log.d("DancerOverlayView", "忽略过期小人资源结果 dir=$targetDir, version=$loadVersion")
            return false
        }

        val hasAnySprite = spriteSet.frames.isNotEmpty() || spriteSet.begin != null || spriteSet.end != null
        if (!hasAnySprite && isPreview) {
            android.util.Log.w("DancerOverlayView", "预览资源为空，继续等待完整资源 dir=$targetDir")
            return true
        }
        if (!hasAnySprite) {
            // 预览已经可用时，完整资源若异常为空，不要把当前画面清空。
            android.util.Log.w("DancerOverlayView", "完整资源为空，保留当前已显示的小人资源 dir=$targetDir")
            return true
        }

        avatarFrames = spriteSet.frames
        beginBitmap = spriteSet.begin?.bitmap
        endBitmap = spriteSet.end?.bitmap
        avatarBitmapCache.evictAll()
        currentFrameIndex = when {
            avatarFrames.isEmpty() -> 0
            else -> currentFrameIndex.mod(avatarFrames.size)
        }

        val loadPhase = if (isPreview) "预览" else "完整"
        android.util.Log.d(
            "DancerOverlayView",
            "小人资源${loadPhase}加载完成 dir=$targetDir, frames=${avatarFrames.size}, begin=${beginBitmap != null}, end=${endBitmap != null}"
        )
        if (!hasAnySprite) {
            android.util.Log.w("DancerOverlayView", "小人资源为空，悬浮窗无法显示。请检查目录: $targetDir")
        }

        renderAfterAvatarResourcesReady()
        return true
    }

    fun setCaptureMode(value: String) {
        modeText.text = context.getString(R.string.overlay_mode_template, value)
    }

    override fun setAudioLevel(level: Float) {
        // 更新本地缓存（保留范围 0..1），未来可用于基于音频调整渲染
        overlayAudioLevel = level.coerceIn(0f, 1f)
    }

    override fun setDanceStyle(style: DanceStyle) {
        if (style == currentDanceStyle) return
        currentDanceStyle = style
        updateBpmText()
    }

    override fun setPlaybackState(state: PlaybackDanceState) {
        if (currentPlaybackState == state) return
        currentPlaybackState = state
        updateBpmText()

        when (state) {
            PlaybackDanceState.PLAYING -> {
                openGlView.setRenderModeContinuous()
                val now = System.currentTimeMillis()
                if (!isShowingBeginOrEnd && now > beginActiveUntilMs) {
                    if (avatarFrames.isNotEmpty()) {
                        loadAvatarFrame(currentFrameIndex)
                        if (latestBpm > 0f) {
                            val bpm = latestBpm.coerceAtLeast(30f)
                            frameIntervalMs = (60000f / bpm).toLong().coerceIn(120L, 1200L)
                            startFrameTicker()
                        } else {
                            // 即使没节拍，既然是 PLAYING 态，也尝试给个默认频率跳动
                            frameIntervalMs = 400L
                            startFrameTicker()
                        }
                    }
                } else {
                    stopFrameTicker()
                }
            }
            PlaybackDanceState.PAUSED, PlaybackDanceState.STOPPED, PlaybackDanceState.IDLE -> {
                openGlView.setRenderModeDirty()
                val now2 = System.currentTimeMillis()
                if (now2 > beginActiveUntilMs) showEndSprite()
                stopFrameTicker()
            }
        }
    }

    // 简化渲染逻辑，仅支持 OpenGL
    private fun renderBitmapOnActiveSurface(bitmap: android.graphics.Bitmap, tag: String) {
        openGlView.uploadBitmapWithMesh(tag, bitmap, 20, 20)
        openGlView.visibility = VISIBLE
    }

    /**
     * 加载特定索引的跳舞帧并渲染
     */
    private fun loadAvatarFrame(index: Int) {
        if (avatarFrames.isEmpty()) {
            // 如果 avatarFrames 为空，尝试显示 endBitmap 或 beginBitmap 作为兜底
            val fallback = endBitmap ?: beginBitmap
            if (fallback != null) {
                renderBitmapOnActiveSurface(prepareOverlayBitmapForDisplay(fallback, "fallback"), "fallback")
            } else {
                openGlView.visibility = GONE
            }
            return
        }
        val frame = avatarFrames[index % avatarFrames.size]
        try {
            val src = frame.bitmap
            val displayBitmap = prepareOverlayBitmapForDisplay(src, "frame_${index % avatarFrames.size}")
            renderBitmapOnActiveSurface(displayBitmap, "frame")
        } catch (e: Exception) {
            android.util.Log.e("DancerOverlayView", "加载图片失败: frameIndex=$index", e)
        }
    }

    // 不使用淡入淡出：直接显示目标视图并隐藏另一个视图以避免重叠

    private fun ensureArgb(bmp: android.graphics.Bitmap): android.graphics.Bitmap {
        if (bmp.config == android.graphics.Bitmap.Config.ARGB_8888 && bmp.isMutable) return bmp
        return bmp.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
    }

    /**
     * 若图片非正方形，则自动加透明边框补齐为正方形，保证等比缩放时视觉尺寸一致。
     */
    private fun padBitmapToSquare(bmp: android.graphics.Bitmap): android.graphics.Bitmap {
        val w = bmp.width
        val h = bmp.height
        if (w == h) return bmp
        val size = maxOf(w, h)
        val output = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(output)
        val left = ((size - w) / 2f)
        val top = ((size - h) / 2f)
        canvas.drawARGB(0, 0, 0, 0) // 全透明底
        canvas.drawBitmap(bmp, left, top, null)
        return output
    }

    override fun onBeat(event: BeatEvent) {
        latestBpm = event.bpm
        updateBpmText()
        if (System.currentTimeMillis() > beginActiveUntilMs) {
            clearBeginEndState()
        }
        if (avatarFrames.isNotEmpty() && latestBpm > 0f) {
            frameHandler.removeCallbacks(frameTickerRunnable)
            frameTickerRunning = false

            currentFrameIndex = (currentFrameIndex + 1) % avatarFrames.size
            loadAvatarFrame(currentFrameIndex)
            openGlView.onBeat(event)
        }
        // 收到节拍时根据 bpm 调整自动帧切换间隔（如果 bpm 可用）并确保定时器运行
        if (event.bpm > 0f) {
            val bpm = event.bpm.coerceAtLeast(30f)
            frameIntervalMs = (60000f / bpm).toLong().coerceIn(120L, 1200L)
        }
        // 只有在处于播放态且有节拍时才运行自动帧切换
        if (currentPlaybackState == PlaybackDanceState.PLAYING && latestBpm > 0f) {
            frameTickerRunning = true
            frameHandler.postDelayed(frameTickerRunnable, frameIntervalMs)
        }
    }

    /**
     * 在悬浮窗内短暂显示 begin 图，表示小人开始进入动作。
     */
    fun showBeginSprite(durationMs: Long = 1200L) {
        if (!canRenderBeginSprite()) {
            deferredSpriteRequest = DeferredSpriteRequest(
                type = DeferredSpriteType.BEGIN,
                durationMs = durationMs,
            )
            android.util.Log.d("DancerOverlayView", "begin 精灵请求已排队，等待资源加载完成")
            return
        }

        deferredSpriteRequest = null
        // 只在点击时显示 begin 图，显示期间不跳舞
        // 取消任何已有的 begin 恢复任务，防止并发
        beginRestoreRunnable?.let { frameHandler.removeCallbacks(it) }
        beginRestoreRunnable = null
        isShowingBeginOrEnd = true
        stopFrameTicker()
        beginBitmap?.let { bmp ->
            val out = prepareOverlayBitmapForDisplay(bmp, "begin")
            renderBitmapOnActiveSurface(out, "begin")
        } ?: run {
            // 若没有专门的 begin 精灵，回退到首帧（并确保可见）
            if (avatarFrames.isNotEmpty()) {
                currentFrameIndex = 0
                loadAvatarFrame(currentFrameIndex)
            }
        }
        // duration 后恢复为当前帧或待机：使用可取消的 Runnable 避免并发/重入
        val runnable = Runnable {
            // 清理 begin 活动保护时间
            beginActiveUntilMs = 0L

            // 恢复逻辑：只有在正在播放且有节拍时恢复为自动帧切换；否则显示待机 end 精灵。
            if (currentPlaybackState == PlaybackDanceState.PLAYING && latestBpm > 0f) {
                // 清除 begin/end 标志并恢复帧动画
                if (isShowingBeginOrEnd) {
                    // 直接恢复为当前帧（不先切为其他帧）
                    isShowingBeginOrEnd = false
                    loadAvatarFrame(currentFrameIndex)
                }
                startFrameTicker()
            } else {
                // 没有音乐或未处于播放态：保持/显示待机 end 精灵
                showEndSprite()
            }
            // 清理引用
            beginRestoreRunnable = null
        }
        beginRestoreRunnable = runnable
        // 记录保护截止时间，略微多留出一些缓冲，防止并发覆盖
        beginActiveUntilMs = System.currentTimeMillis() + durationMs + 80L
        frameHandler.postDelayed(runnable, durationMs)
    }

    /**
     * 显示 end/待机图，替代跳舞序列帧。
     */
    fun showEndSprite() {
        if (!canRenderEndSprite()) {
            deferredSpriteRequest = DeferredSpriteRequest(type = DeferredSpriteType.END)
            android.util.Log.d("DancerOverlayView", "end 精灵请求已排队，等待资源加载完成")
            return
        }

        deferredSpriteRequest = null
        // 只在暂停/停止时显示 end 图
        isShowingBeginOrEnd = true
        stopFrameTicker()
        endBitmap?.let { bmp ->
            val out = prepareOverlayBitmapForDisplay(bmp, "end")
            renderBitmapOnActiveSurface(out, "end")
        } ?: run {
            // 如果没有独立的 end 图，则回退到当前帧/首帧。
            loadAvatarFrame(currentFrameIndex)
        }
    }

    /**
     * 跳舞时清理 begin/end 图，恢复为当前帧
     */
    private fun clearBeginEndState() {
        if (isShowingBeginOrEnd) {
            // 取消任何待执行的 begin 恢复任务
            beginRestoreRunnable?.let { frameHandler.removeCallbacks(it) }
            beginRestoreRunnable = null
            loadAvatarFrame(currentFrameIndex)
            isShowingBeginOrEnd = false
        }
    }

    // 将 begin/end 等特殊位图 pad/scale 为 overlay 目标尺寸，且使用缓存避免重复缩放
    private fun prepareOverlayBitmapForDisplay(
        src: android.graphics.Bitmap,
        spriteKey: String,
    ): android.graphics.Bitmap {
        val targetPx = getTargetDisplaySizePx()
        val cacheKey = "__display__${spriteKey}@${targetPx}x${targetPx}_${src.width}x${src.height}"
        avatarBitmapCache.get(cacheKey)?.takeIf { !it.isRecycled }?.let { return it }

        val scaledBitmap = createDisplayBitmap(src, targetPx)
        avatarBitmapCache.put(cacheKey, scaledBitmap)
        return scaledBitmap
    }

    private fun getTargetDisplaySizePx(): Int {
        val density = resources.displayMetrics.density
        return (baseSizeDp * currentScale * density).toInt().coerceAtLeast(1)
    }

    private fun createDisplayBitmap(src: android.graphics.Bitmap, targetPx: Int): android.graphics.Bitmap {
        val argbBitmap = ensureArgb(src)
        val paddedBitmap = padBitmapToSquare(argbBitmap)

        return try {
            val reusable = getReusableBitmap(targetPx, targetPx)
            if (reusable != null) {
                val canvas = android.graphics.Canvas(reusable)
                canvas.drawColor(android.graphics.Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
                val srcRect = android.graphics.Rect(0, 0, paddedBitmap.width, paddedBitmap.height)
                val dstRect = android.graphics.Rect(0, 0, targetPx, targetPx)
                canvas.drawBitmap(paddedBitmap, srcRect, dstRect, null)
                reusable
            } else {
                android.graphics.Bitmap.createScaledBitmap(paddedBitmap, targetPx, targetPx, true)
            }
        } finally {
            recycleIntermediateBitmap(paddedBitmap, src)
            if (argbBitmap !== paddedBitmap) {
                recycleIntermediateBitmap(argbBitmap, src)
            }
        }
    }

    private fun recycleIntermediateBitmap(
        candidate: android.graphics.Bitmap,
        original: android.graphics.Bitmap,
    ) {
        if (candidate !== original && !candidate.isRecycled) {
            candidate.recycle()
        }
    }

    private fun renderAfterAvatarResourcesReady() {
        val deferred = deferredSpriteRequest
        if (deferred != null) {
            deferredSpriteRequest = null
            when (deferred.type) {
                DeferredSpriteType.BEGIN -> showBeginSprite(deferred.durationMs)
                DeferredSpriteType.END -> showEndSprite()
            }
            return
        }

        when (currentPlaybackState) {
            PlaybackDanceState.PLAYING -> loadAvatarFrame(currentFrameIndex)
            PlaybackDanceState.PAUSED, PlaybackDanceState.STOPPED, PlaybackDanceState.IDLE -> showEndSprite()
        }
    }

    private fun canRenderBeginSprite(): Boolean {
        return beginBitmap != null || avatarFrames.isNotEmpty()
    }

    private fun canRenderEndSprite(): Boolean {
        return endBitmap != null || avatarFrames.isNotEmpty() || beginBitmap != null
    }

    private fun startFrameTicker() {
        if (frameTickerRunning) return
        frameTickerRunning = true
        frameHandler.removeCallbacks(frameTickerRunnable)
        frameHandler.postDelayed(frameTickerRunnable, frameIntervalMs)
    }

    private fun stopFrameTicker() {
        frameTickerRunning = false
        frameHandler.removeCallbacks(frameTickerRunnable)
    }

    private fun updateBpmText() {
        val styleName = currentDanceStyle.displayName
        val playbackStateLabel = when (currentPlaybackState) {
            PlaybackDanceState.PLAYING -> context.getString(R.string.overlay_state_playing)
            PlaybackDanceState.PAUSED -> context.getString(R.string.overlay_state_paused)
            PlaybackDanceState.STOPPED, PlaybackDanceState.IDLE -> context.getString(R.string.overlay_state_stopped)
        }

        bpmText.text = if (latestBpm > 0f && currentPlaybackState == PlaybackDanceState.PLAYING) {
            context.getString(R.string.overlay_bpm_style_state_template, latestBpm, styleName, playbackStateLabel)
        } else {
            context.getString(R.string.overlay_style_state_template, styleName, playbackStateLabel)
        }
    }

    private fun dpToPx(value: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value,
            resources.displayMetrics
        ).toInt()
    }

    // 从可重用位图池中获取符合尺寸的位图（会返回一个可绘制的 ARGB_8888 可变位图）
    private fun getReusableBitmap(w: Int, h: Int): android.graphics.Bitmap? {
        val it = reusableBitmaps.iterator()
        while (it.hasNext()) {
            val bmp = it.next()
            if (!bmp.isRecycled && bmp.width == w && bmp.height == h && bmp.config == android.graphics.Bitmap.Config.ARGB_8888) {
                it.remove()
                return bmp
            }
        }
        return null
    }

    // 将位图放回复用池（若池已满则回收）
    @Suppress("unused")
    private fun releaseReusableBitmap(bmp: android.graphics.Bitmap) {
        if (bmp.isRecycled) return
        if (reusableBitmaps.size >= 4) {
            bmp.recycle()
        } else {
            reusableBitmaps.addLast(bmp)
        }
    }

    // 允许外部按钮直接复用悬浮小人的 begin 展示逻辑。
    fun setupFloatingCharacterButton(button: android.view.View) {
        button.setOnClickListener {
            // 统一走现有 begin 展示流程，保持时序和表现一致。
            showBeginSprite()
        }
    }

    override fun onSongChanged() {
        // 切歌时重置到首帧并短暂展示 begin，保证 OpenGL 链路下状态可预期。
        currentFrameIndex = 0
        if (currentPlaybackState == PlaybackDanceState.PLAYING) {
            showBeginSprite(900L)
        } else {
            showEndSprite()
        }
    }

    fun reloadAvatarVariant() {
        // 统一走当前配置重载，避免多入口导致的资源状态不一致。
        val settings = OverlaySettingsRepository(context).get()
        applySettings(settings)
    }
}
