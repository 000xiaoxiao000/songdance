package com.example.myapplication

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.ImageView
import android.graphics.BitmapFactory
// 使用框架提供的 Bitmap.createBitmap / createScaledBitmap，避免对 KTX 扩展的依赖

class DancerOverlayView(context: Context) : FrameLayout(context) {
    // 只用图片型小人
    private val avatarImageView = ImageView(context)
    // OpenGL ES 视图，用于渲染上传的位图纹理（当启用 GL 分支时使用）
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
    private var lastUseAvatar1: Boolean? = null
    private var lastAvatarDir: String? = null
    private var lastAvatarVariantDir: String? = null
    // 图片帧资源id数组或asset路径数组
    private var avatarFrameAssets: List<String> = emptyList()
    private var currentFrameIndex = 0
    // begin/end special assets (optional)
    private var beginAssetPath: String? = null
    private var endAssetPath: String? = null
    private var beginBitmap: android.graphics.Bitmap? = null
    private var endBitmap: android.graphics.Bitmap? = null
    // 用于在没有明显节拍事件时也能让悬浮小人按一定速率切换帧，模拟跳舞
    private val frameHandler = Handler(Looper.getMainLooper())
    private var frameTickerRunning = false
    // 默认帧间隔（毫秒），会根据节拍事件调整
    private var frameIntervalMs = 350L
    private val frameTickerRunnable = object : Runnable {
        override fun run() {
            // 跳舞前清理 begin/end 图
            clearBeginEndState()
            // 仅当处于播放态且检测到节拍（latestBpm>0）时才循环自动切帧
            if (currentPlaybackState == PlaybackDanceState.PLAYING && avatarFrameAssets.isNotEmpty() && latestBpm > 0f) {
                currentFrameIndex = (currentFrameIndex + 1) % avatarFrameAssets.size
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
    // 是否使用 GL 渲染分支（默认关闭，优先使用 ImageView 回退以保证兼容性）
    private var useGl = false
    // 无淡入淡出：直接切换渲染视图，确保在显示前隐藏另一者以避免重叠
    // 当前用户设置的缩放比例（由 applySettings 更新），用于计算目标像素尺寸
    private var currentScale = 1.0f
    // 标志：当前是否显示 begin 或 end 图
    private var isShowingBeginOrEnd = false
    // 可取消的 begin 恢复 Runnable 引用，防止重复/并发恢复任务
    private var beginRestoreRunnable: Runnable? = null
    // begin 活动保护时间戳（毫秒），在此时间内外部状态更新不应覆盖 begin 显示
    private var beginActiveUntilMs: Long = 0L

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

        avatarImageView.apply {
            // 使用 CENTER_INSIDE，确保图片等比缩放到 ImageView 区域内，不拉伸、不压缩，仅按比例缩放，居中显示
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setBackgroundColor(Color.TRANSPARENT)
            visibility = VISIBLE
        }
        // 将 ImageView 与 GL 视图叠放，GL 在上层（可见时覆盖 ImageView）
        addView(
            avatarImageView,
            LayoutParams(dpToPx(baseSizeDp), dpToPx(baseSizeDp)).apply {
                gravity = Gravity.CENTER
            }
        )
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

        // Modify click handler to show the begin sprite when appropriate.
        // We avoid referencing missing drawable resources and prefer the asset-based beginBitmap
        val clickHandler = OnClickListener {
            if (currentPlaybackState == PlaybackDanceState.PLAYING && latestBpm > 0f) {
                // If we already decoded a begin bitmap from assets, use it; otherwise fall back to the
                // normal showBeginSprite() which handles asset fallback and timing.
                if (beginBitmap != null) {
                    // Display the prepared begin bitmap immediately (scaled) and schedule restore
                    val out = prepareOverlayBitmapForDisplay(beginBitmap!!)
                    avatarImageView.setImageBitmap(out)
                    avatarImageView.visibility = VISIBLE
                    openGlView.visibility = GONE
                    // Mark that we're showing a special sprite so other logic won't immediately override
                    isShowingBeginOrEnd = true
                    // Schedule restore using the existing logic
                    showBeginSprite()
                } else {
                    // showBeginSprite will attempt to use assets and handle timing
                    showBeginSprite()
                }
            }
            // No operation for non-playing states
        }
        setOnClickListener(clickHandler)

        // Ensure child views trigger the same click logic
        avatarImageView.setOnClickListener { performClick() }
        openGlView.setOnClickListener { performClick() }
    }

    fun applySettings(settings: OverlaySettings) {
        avatarImageView.alpha = settings.avatarAlpha
        openGlView.setAlphaValue(settings.avatarAlpha)
        // When GL rendering is enabled we must not leave the ImageView visible
        // because it would draw the same frame underneath/over the GL view and
        // produce overlapping/ghosting. Only one rendering surface should be
        // visible at a time.
        avatarImageView.visibility = if (useGl) GONE else VISIBLE
        openGlView.visibility = if (useGl) VISIBLE else GONE
        // 更新当前缩放比例并调整视图尺寸
        currentScale = settings.avatarScale
        val size = dpToPx(baseSizeDp * currentScale)
        avatarImageView.layoutParams = (avatarImageView.layoutParams as? LayoutParams)?.apply {
            width = size
            height = size
        }
        openGlView.layoutParams = (openGlView.layoutParams as? LayoutParams)?.apply {
            width = size
            height = size
        }
        avatarImageView.requestLayout()
        openGlView.requestLayout()

        var needReload = false
        if (lastUseAvatar1 == null) lastUseAvatar1 = settings.useAvatarVariant1
        if (lastAvatarDir == null) lastAvatarDir = settings.avatarDir
        if (lastAvatarVariantDir == null) lastAvatarVariantDir = settings.avatarVariantDir

        if (lastUseAvatar1 != settings.useAvatarVariant1) {
            lastUseAvatar1 = settings.useAvatarVariant1
            needReload = true
        }
        if (lastAvatarDir != settings.avatarDir) {
            lastAvatarDir = settings.avatarDir
            needReload = true
        }
        if (lastAvatarVariantDir != settings.avatarVariantDir) {
            lastAvatarVariantDir = settings.avatarVariantDir
            needReload = true
        }
        if (needReload || avatarFrameAssets.isEmpty()) {
            // 仅使用用户所选的 avatar 目录（avatar 或 avatar1），不将两个目录的帧混合显示
            val preferredDir = if (settings.useAvatarVariant1) settings.avatarVariantDir else settings.avatarDir
            val otherDir = if (preferredDir == settings.avatarDir) settings.avatarVariantDir else settings.avatarDir

            fun listFromDir(d: String?): List<String> {
                if (d == null) return emptyList()
                return try {
                    context.assets.list(d)
                        ?.filter { it.startsWith("dancer_single") && it.endsWith(".png") }
                        ?.sortedBy { it.substringAfter("dancer_single").substringBefore(".png").toIntOrNull() ?: Int.MAX_VALUE }
                        ?.map { "$d/$it" } ?: emptyList()
                } catch (_: Exception) { emptyList() }
            }

            val chosenList: List<String> = try {
                val preferredList = listFromDir(preferredDir)
                preferredList.ifEmpty {
                    val otherList = listFromDir(otherDir)
                    otherList.ifEmpty {
                        try {
                            context.assets.list("")?.filter { it.startsWith("dancer_single") && it.endsWith(".png") }
                                ?.sortedBy {
                                    it.substringAfter("dancer_single").substringBefore(".png").toIntOrNull() ?: Int.MAX_VALUE
                                }
                                ?: emptyList()
                        } catch (_: Exception) {
                            emptyList<String>()
                        }
                    }
                }
            } catch (_: Exception) {
                emptyList()
            }

            // 过滤掉 begin/end 精灵帧，不参与动画
            avatarFrameAssets = chosenList.distinct().filterNot {
                it.endsWith("dancer_single_begin.png") || it.endsWith("dancer_single_end.png")
            }

            // 仅在首选目录查找 begin/end 精灵，若首选目录没有再尝试根目录（避免混合 avatar 与 avatar1）
            fun resolveSpecial(name: String): String? {
                val candidates = buildList {
                    val preferred = if (settings.useAvatarVariant1) settings.avatarVariantDir else settings.avatarDir
                    if (preferred.isNotEmpty()) add("$preferred/$name")
                    add(name)
                }
                for (p in candidates) {
                    if (p.isEmpty()) continue
                    val exists = kotlin.runCatching {
                        context.assets.open(p).close()
                        true
                    }.getOrDefault(false)
                    if (exists) return p
                }
                return null
            }

            beginAssetPath = resolveSpecial("dancer_single_begin.png")
            endAssetPath = resolveSpecial("dancer_single_end.png")

            val decodeOpts = BitmapFactory.Options().apply {
                inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
                inMutable = true
            }
            beginBitmap = beginAssetPath?.let { path ->
                kotlin.runCatching { context.assets.open(path).use { BitmapFactory.decodeStream(it, null, decodeOpts) } }.getOrNull()?.let { ensureArgb(it) }
            }
            endBitmap = endAssetPath?.let { path ->
                kotlin.runCatching { context.assets.open(path).use { BitmapFactory.decodeStream(it, null, decodeOpts) } }.getOrNull()?.let { ensureArgb(it) }
            }
            currentFrameIndex = 0
            loadAvatarFrame(currentFrameIndex)
        } else {
            loadAvatarFrame(currentFrameIndex)
        }
    }

    fun setCaptureMode(value: String) {
        modeText.text = context.getString(R.string.overlay_mode_template, value)
    }

    fun setAudioLevel(value: Float) {
        // 更新本地缓存（保留范围 0..1），未来可用于基于音频调整渲染
        overlayAudioLevel = value.coerceIn(0f, 1f)
    }

    fun setDanceStyle(style: DanceStyle) {
        if (style == currentDanceStyle) return
        currentDanceStyle = style
        updateBpmText()
    }

    fun setPlaybackState(state: PlaybackDanceState) {
        if (currentPlaybackState == state) return
        currentPlaybackState = state
        updateBpmText()

        when (state) {
            PlaybackDanceState.PLAYING -> {
                openGlView.setRenderModeContinuous()
                // 如果当前正在展示 begin/end（例如服务刚刚启动显示 begin），不要立即覆盖显示。
                // 只有当没有 begin/end 展示时才恢复/启动帧动画或显示当前帧。
                // 如果 begin 仍处于保护期，也不要覆盖
                val now = System.currentTimeMillis()
                if (!isShowingBeginOrEnd && now > beginActiveUntilMs) {
                    loadAvatarFrame(currentFrameIndex)
                    if (latestBpm > 0f) {
                        val bpm = latestBpm.coerceAtLeast(30f)
                        frameIntervalMs = (60000f / bpm).toLong().coerceIn(120L, 1200L)
                        startFrameTicker()
                    } else {
                        stopFrameTicker()
                    }
                } else {
                    // 保持 begin/end 状态直到其自动恢复或节拍事件触发 clearBeginEndState()
                    stopFrameTicker()
                }
            }
            PlaybackDanceState.PAUSED, PlaybackDanceState.STOPPED, PlaybackDanceState.IDLE -> {
                openGlView.setRenderModeDirty()
                // Respect begin protection window: if begin sprite is still intended to show, skip immediate end
                val now2 = System.currentTimeMillis()
                if (now2 > beginActiveUntilMs) showEndSprite() else {
                    // 保持当前 begin/end 可见性（不覆盖）
                }
                stopFrameTicker()
            }
        }
    }

    private fun loadAvatarFrame(index: Int) {
        // 切换帧时立即切换显示目标渲染视图（ImageView 或 GL），避免动画重叠
            if (avatarFrameAssets.isEmpty()) {
            avatarImageView.setImageDrawable(null)
            avatarImageView.setBackgroundColor(Color.TRANSPARENT)
            return
        }
        val assetPath = avatarFrameAssets[index % avatarFrameAssets.size]
        try {
            // 优先从缓存获取已经缩放好的目标尺寸位图，使用当前缩放比例保证尺寸一致
            val targetPx = (baseSizeDp * currentScale * resources.displayMetrics.density).toInt()
            val cacheKey = "$assetPath@$targetPx"
            val cached = avatarBitmapCache.get(cacheKey)
            if (cached != null) {
                if (useGl) {
                    // 立即在 GL 分支显示，隐藏 ImageView
                    openGlView.uploadBitmapWithMesh("frame", cached, 20, 20)
                    openGlView.visibility = VISIBLE
                    avatarImageView.visibility = GONE
                } else {
                    // 立即在 ImageView 显示，隐藏 GL 视图
                    avatarImageView.setImageBitmap(cached)
                    avatarImageView.visibility = VISIBLE
                    openGlView.visibility = GONE
                }
            } else {
                context.assets.open(assetPath).use { input ->
                    val opts = BitmapFactory.Options().apply { inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888; inMutable = true }
                    val src = BitmapFactory.decodeStream(input, null, opts)
                        ?: throw Exception("Failed to decode asset: $assetPath")
                    val padded = padBitmapToSquare(ensureArgb(src))
                    val reusable = getReusableBitmap(targetPx, targetPx)
                    val finalBitmap: android.graphics.Bitmap = if (reusable != null) {
                        val canvas = android.graphics.Canvas(reusable)
                        canvas.drawARGB(0, 0, 0, 0)
                        val srcRect = android.graphics.Rect(0, 0, padded.width, padded.height)
                        val dstRect = android.graphics.Rect(0, 0, targetPx, targetPx)
                        canvas.drawBitmap(padded, srcRect, dstRect, null)
                        reusable
                    } else {
                        android.graphics.Bitmap.createScaledBitmap(padded, targetPx, targetPx, true)
                    }
                    avatarBitmapCache.put(cacheKey, finalBitmap)
                    if (useGl) {
                        openGlView.uploadBitmapWithMesh("frame", finalBitmap, 20, 20)
                        openGlView.visibility = VISIBLE
                        avatarImageView.visibility = GONE
                    } else {
                        avatarImageView.setImageBitmap(finalBitmap)
                        avatarImageView.visibility = VISIBLE
                        openGlView.visibility = GONE
                    }
                }
            }
        } catch (e: Exception) {
            avatarImageView.setImageDrawable(null)
            avatarImageView.setBackgroundColor(Color.TRANSPARENT)
            android.util.Log.e("DancerOverlayView", "加载图片失败: $assetPath", e)
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

    fun onBeat(event: BeatEvent) {
        latestBpm = event.bpm
        updateBpmText()
        // Only clear begin/end state if begin protection window expired; otherwise let begin finish
        if (System.currentTimeMillis() > beginActiveUntilMs) {
            clearBeginEndState()
        }
        // 只有在有节拍时才切帧
        if (avatarFrameAssets.isNotEmpty() && latestBpm > 0f) {
            frameHandler.removeCallbacks(frameTickerRunnable)
            frameTickerRunning = false

            currentFrameIndex = (currentFrameIndex + 1) % avatarFrameAssets.size
            loadAvatarFrame(currentFrameIndex)
            if (useGl) {
                openGlView.onBeat(event)
            }
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
     * Show the special begin sprite inside the overlay for a short time.
     * This is used to indicate the dancer is starting.
     */
    fun showBeginSprite(durationMs: Long = 1200L) {
        // 只在点击时显示 begin 图，显示期间不跳舞
        // 取消任何已有的 begin 恢复任务，防止并发
        beginRestoreRunnable?.let { frameHandler.removeCallbacks(it) }
        beginRestoreRunnable = null
        isShowingBeginOrEnd = true
        stopFrameTicker()
        beginBitmap?.let { bmp ->
            if (useGl) {
                val out = prepareOverlayBitmapForDisplay(bmp)
                openGlView.uploadBitmapWithMesh("begin", out, 20, 20)
                openGlView.visibility = VISIBLE
                avatarImageView.visibility = GONE
            } else {
                val out = prepareOverlayBitmapForDisplay(bmp)
                avatarImageView.setImageBitmap(out)
                avatarImageView.visibility = VISIBLE
                openGlView.visibility = GONE
            }
        } ?: run {
            // 若没有专门的 begin 精灵，回退到首帧（并确保可见）
            if (avatarFrameAssets.isNotEmpty()) {
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
     * Display the special end/standby sprite in place of the animated frames.
     */
    fun showEndSprite() {
        // 只在暂停/停止时显示 end 图
        isShowingBeginOrEnd = true
        stopFrameTicker()
        endBitmap?.let { bmp ->
            if (useGl) {
                val out = prepareOverlayBitmapForDisplay(bmp)
                openGlView.uploadBitmapWithMesh("end", out, 20, 20)
                openGlView.visibility = VISIBLE
                avatarImageView.visibility = GONE
            } else {
                val out = prepareOverlayBitmapForDisplay(bmp)
                avatarImageView.setImageBitmap(out)
                avatarImageView.visibility = VISIBLE
                openGlView.visibility = GONE
            }
        } ?: run {
            // Fallback: if no explicit end asset, show first frame
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
    private fun prepareOverlayBitmapForDisplay(src: android.graphics.Bitmap): android.graphics.Bitmap {
        val targetPx = (baseSizeDp * currentScale * resources.displayMetrics.density).toInt()
        val cacheKey = "__special__@${targetPx}x${targetPx}_${src.width}x${src.height}"
        avatarBitmapCache.get(cacheKey)?.let { return it }

        val padded = padBitmapToSquare(ensureArgb(src))
        val finalBitmap = android.graphics.Bitmap.createScaledBitmap(padded, targetPx, targetPx, true)
        avatarBitmapCache.put(cacheKey, finalBitmap)
        return finalBitmap
    }

    private fun startFrameTicker() {
        if (frameTickerRunning) return
        frameTickerRunning = true
        frameHandler.postDelayed(frameTickerRunnable, frameIntervalMs)
    }

    private fun stopFrameTicker() {
        frameHandler.removeCallbacks(frameTickerRunnable)
        frameTickerRunning = false
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

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // 释放 GL 资源
        try {
            openGlView.onPause()
            openGlView.releaseGLResources()
        } catch (_: Exception) {
        }
        // 清理缓存位图：evictAll 会触发 entryRemoved 回收到 reusableBitmaps
        avatarBitmapCache.evictAll()
        // 回收复用池中的位图
        reusableBitmaps.forEach { bmp -> if (!bmp.isRecycled) bmp.recycle() }
        reusableBitmaps.clear()
        // 停止可能运行的帧定时器，避免 Handler 引用导致内存泄漏
        stopFrameTicker()
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

    // Allow external wiring of a UI button to the overlay's begin behavior.
    // Callers (e.g., an Activity) can pass the actual button/view instance here.
    fun setupFloatingCharacterButton(button: android.view.View) {
        button.setOnClickListener {
            // Reuse the existing begin-display logic which prefers asset bitmaps and timing
            showBeginSprite()
        }
    }
}
