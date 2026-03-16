package com.example.myapplication

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.Context
import androidx.preference.PreferenceManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import android.view.MotionEvent
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random
import androidx.core.graphics.withTranslation
import androidx.core.graphics.withRotation
import androidx.core.view.isVisible

// 悬浮小人使用独立的 sprite 帧图片
// 运行时的 PoseFrame（用于微动作/节拍驱动的动态效果）。

class DancingAvatarView(context: Context) : View(context), BeatReactiveAvatar {
    // Indicates if the position is locked
    private var isLocked = false
    private var lastX = 0f
    private var lastY = 0f

    // 上一帧pose缓存及插值参数
    // 贝塞尔插值器（与运动控制器参数一致，ease-in-out）
    private val bezierInterpolator = CubicBezierInterpolator(0.42f, 0f, 0.58f, 1f)

    private data class LayerSprite(
        val bitmap: Bitmap,
        val pivotXRatio: Float,
        val pivotYRatio: Float,
    )

    private var lastPoseFrame: PoseFrame? = null
    private var lastPoseTimestamp: Long = 0L

    private data class DepthProfile(
        val avatarScale: Float,
        val forwardOffsetY: Float,
        val glowBoost: Float,
        val particleBoost: Float,
        val eqBoost: Float,
        val apertureAlpha: Float,
    )

    data class PoseFrame(
        val bodyAngle: Float,
        val leftArmAngle: Float,
        val rightArmAngle: Float,
        val fanAngle: Float,
        val skirtAngle: Float,
        val headAngle: Float,
        val leftLegLift: Float,
        val rightLegLift: Float,
    )

    private val stageGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(70, 120, 180, 255)
    }
    private val stagePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(110, 72, 90, 168)
    }
    private val floorShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(78, 14, 20, 38)
    }

    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(255, 188, 212)
    }
    private val bodyShadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(132, 190, 255)
    }
    private val trimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(232, 255, 255, 255)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val skinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(255, 236, 224)
    }
    private val hairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(90, 150, 238)
    }
    private val hairShadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(72, 112, 196)
    }
    private val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(30, 38, 62)
    }
    private val eyeHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val blushPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(104, 255, 132, 168)
    }
    private val browPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.rgb(84, 56, 96)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val mouthFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(164, 210, 84, 128)
    }
    private val skinShadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(250, 214, 198)
    }
    private val irisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(86, 170, 255)
    }
    private val ribbonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(255, 104, 186)
    }
    private val stockingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(245, 248, 255)
    }
    private val sparklePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    private val fanPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(255, 228, 226)
    }
    private val fanAccentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.rgb(252, 112, 176)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val ornamentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(244, 198, 116)
    }
    private val shoePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(58, 80, 150)
    }

    private val skirtPath = Path()
    private val cloudPath = Path()
    private val ruyiPath = Path()
    private val hairPath = Path()
    private val peonyPath = Path()

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = true
        isDither = true
    }
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)

    private fun avatarDir(): String {
        return prefs.getString(OverlaySettingsRepository.KEY_AVATAR_DIR, AvatarAssets.DIR_AVATAR).orEmpty()
    }

    private fun avatarVariantDir(): String {
        return prefs.getString(OverlaySettingsRepository.KEY_AVATAR_VARIANT_DIR, AvatarAssets.DIR_AVATAR1).orEmpty()
    }

    private fun prefersAvatar1(): Boolean {
        return prefs.getBoolean(OverlaySettingsRepository.KEY_USE_AVATAR_VARIANT_1, false)
    }
    private var layeredSprites: Map<String, LayerSprite> = loadLayeredSprites()
    private var avatarSpriteFrames: List<LayerSprite> = loadAvatarSpriteFrames()
    private val maxAvatarSpriteFrames = 18
    private val backgroundColorTolerance = 20
    private val spriteChoreographyEngine = AvatarSpriteChoreographyEngine(maxAvatarSpriteFrames)

    // 单精灵变形的网格变量
    private val meshWidth = 20
    private val meshHeight = 20
    private val meshVerts = FloatArray((meshWidth + 1) * (meshHeight + 1) * 2)
    private val meshVertsAlt = FloatArray((meshWidth + 1) * (meshHeight + 1) * 2)
    // midVerts 用于在混合两帧时保存合并后的顶点，复用以避免每帧分配
    private val midVerts = FloatArray((meshWidth + 1) * (meshHeight + 1) * 2)
    private var useMeshForSprites = false

    // 运行时可配置的锚点偏移（百分比），用于垂直微调头像位置。
    private var avatarAnchorOffsetPercent: Float = 0f

    fun setAvatarAnchorOffsetPercent(percent: Float) {
        avatarAnchorOffsetPercent = percent
        invalidate()
    }

    // 在渲染重叠帧时使用的离屏合成缓冲区，以避免变形不匹配
    private var tmpBitmap: Bitmap? = null
    private var tmpCanvas: Canvas? = null

    private var phase = 0f
    private var beatBoost = 0f
    private var recentBeatStrength = 0f
    private var expressionEnergy = 0f
    private var blinkAmount = 0f
    private var winkLeftEye = true
    private var audioLevel = 0f
    private var danceStyle = DanceStyle.CHILL
    private var playbackState = PlaybackDanceState.STOPPED
    private var songChangeBoost = 0f
    private var spriteFrameProgress = 0f
    private var beatCounter = 0
    // 回退渲染状态已迁移为 OpenGL ES 兼容类型（OpenGLESFallbackRenderState）
    private var fallbackRenderState = OpenGLESFallbackRenderState.Neutral
    // 标志：表示我们应在下一次节拍时显示开始精灵
    private var awaitingFirstBeat = false
    // 用于将开始/结束精灵对齐到最近节拍的计划 runnable
    private var pendingBeginRunnable: Runnable? = null
    private var pendingEndRunnable: Runnable? = null

    private var lastBeatTimestampMs = 0L
    // 略慢的默认节拍间隔，使头像对音乐的跟随显得更从容/滞后一些
    private var beatIntervalMs = 800L

    // Pending beat event recorded from onBeat(); consumed once per animator frame to debounce
    private var pendingBeatEvent: BeatEvent? = null
    // 最后被 animator 消耗的节拍时间戳，用于去重
    private var lastProcessedBeatTimestampMs: Long = 0L

    private var lastSpriteSwitchTimestampMs = 0L
    private val minSpriteSwitchIntervalMs = 120L

    // 微动作变量，用于添加偶发的小幅手势变化
    private var microMotionEnabled = true
    private var microHeadTiltDeg = 0f
    private var microLeftArmOffset = 0f
    private var microRightArmOffset = 0f
    private val microMotionRunnable = object : Runnable {
        override fun run() {
            scheduleMicroMotionOnce()
            // 在 700..2600 毫秒内安排下一次微动作
            postDelayed(this, (700L + Random.nextLong(0L, 1900L)))
        }
    }

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = styleDurationMs(danceStyle)
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            val motionScale = playbackMotionScale()
            val fullFrameFactor = if (avatarSpriteFrames.size >= maxAvatarSpriteFrames) 1.14f else 1f
            beatBoost *= if (playbackState == PlaybackDanceState.PLAYING) 0.92f else 0.82f
            recentBeatStrength *= if (playbackState == PlaybackDanceState.PLAYING) 0.9f else 0.8f
            expressionEnergy *= if (playbackState == PlaybackDanceState.PLAYING) 0.93f else 0.88f
            blinkAmount *= 0.86f
            songChangeBoost *= if (playbackState == PlaybackDanceState.PLAYING) 0.93f else 0.89f

            // 在每帧平滑衰减微动作偏移
            microHeadTiltDeg *= 0.88f
            microLeftArmOffset *= 0.88f
            microRightArmOffset *= 0.88f

            val poseSpeed = when (playbackState) {
                PlaybackDanceState.PLAYING -> 0.022f + styleAmplitude(danceStyle) * 0.018f + audioLevel * 0.02f + beatBoost * 0.02f + songChangeBoost * 0.024f
                PlaybackDanceState.PAUSED -> 0.0048f + songChangeBoost * 0.014f
                PlaybackDanceState.STOPPED, PlaybackDanceState.IDLE -> 0.0022f + songChangeBoost * 0.01f
            }
            // 如果我们正在等待首个节拍，则保持静止（仅执行微动作与阻尼），
            // 这里已移除基于预置 Pose 的序列逻辑，保留精灵帧推进（sprite animation）
            if (!awaitingFirstBeat) {

                if (avatarSpriteFrames.size > 1 && spriteStepSequence().size > 1) {
                    val spriteSpeed = when (playbackState) {
                        PlaybackDanceState.PLAYING -> (0.014f + styleAmplitude(danceStyle) * 0.024f + audioLevel * 0.032f + beatBoost * 0.024f + songChangeBoost * 0.05f) * fullFrameFactor
                        PlaybackDanceState.PAUSED -> 0.004f + songChangeBoost * 0.015f
                        PlaybackDanceState.STOPPED, PlaybackDanceState.IDLE -> 0.0018f + songChangeBoost * 0.01f
                    }
                    spriteFrameProgress += spriteSpeed * motionScale
                    while (spriteFrameProgress >= 1f) {
                        spriteFrameProgress -= 1f
                        advanceSpriteChoreography()
                    }
                } else {
                    spriteFrameProgress = 0f
                }

                    // 每帧消费一次挂起的节拍事件以去抖，保证在渲染循环中统一处理节拍引起的重状态变更
                    pendingBeatEvent?.let { ev ->
                        if (ev.timestampMs > lastProcessedBeatTimestampMs) {
                            consumePendingBeat(ev)
                            lastProcessedBeatTimestampMs = ev.timestampMs
                        }
                        pendingBeatEvent = null
                    }
            }

            invalidate()
        }
    }

    // --- 开始/结束 精灵支持 ---
    private var beginSprite: LayerSprite? = null
    private var endSprite: LayerSprite? = null
    private var isAtBegin = false
    private var isAtEnd = false

    private fun loadBeginEndSprites() {
        // 先尝试从 assets 加载，如果失败则回退到 drawable 资源。
        val beginCandidates = if (prefersAvatar1()) listOf("${avatarVariantDir()}/dancer_single_begin.png", "${avatarDir()}/dancer_single_begin.png", "dancer_single_begin.png") else listOf("${avatarDir()}/dancer_single_begin.png", "dancer_single_begin.png")
        beginSprite = loadSpriteFromCandidates(beginCandidates)
            ?: run {
                val resId = findDrawableResourceIdByName("dancer_single_begin")
                if (resId != 0) {
                    BitmapFactory.decodeResource(resources, resId)?.let { prepareAvatarBitmap(it) }
                } else null
            }?.let { LayerSprite(it, 0.5f, 0.5f) }
        val endCandidates = if (prefersAvatar1()) listOf("${avatarVariantDir()}/dancer_single_end.png", "${avatarDir()}/dancer_single_end.png", "dancer_single_end.png") else listOf("${avatarDir()}/dancer_single_end.png", "dancer_single_end.png")
        endSprite = loadSpriteFromCandidates(endCandidates)
            ?: run {
                val resId = findDrawableResourceIdByName("dancer_single_end")
                if (resId != 0) {
                    BitmapFactory.decodeResource(resources, resId)?.let { prepareAvatarBitmap(it) }
                } else null
            }?.let { LayerSprite(it, 0.5f, 0.5f) }
    }

    init {
        loadBeginEndSprites()

        // Built-in poses are defined in `builtInPoses` above; external registration removed.

        // Initialize the "锁定位置" preference
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        isLocked = sharedPreferences.getBoolean(context.getString(R.string.lock_position), false)

        // Set up touch listener for drag functionality
        setOnTouchListener { _, event ->
            if (isLocked) return@setOnTouchListener false

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.rawX
                    lastY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - lastX
                    val dy = event.rawY - lastY
                    translationX += dx
                    translationY += dy
                    lastX = event.rawX
                    lastY = event.rawY
                    true
                }
                else -> false
            }
        }
    }

    /**
     * Reload avatar assets (called when the user changes the avatar variant preference).
     */
    fun reloadAvatarVariant() {
        // Clear any temporary buffers
        tmpBitmap?.recycle()
        tmpBitmap = null
        tmpCanvas = null

        layeredSprites = loadLayeredSprites()
        avatarSpriteFrames = loadAvatarSpriteFrames()
        loadBeginEndSprites()
        invalidate()
        if (isAttachedToWindow && isVisible) {
            if (!animator.isStarted) {
                animator.start()
            }
        }
    }
    // --- 结束 精灵支持 ---

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startAnimatorIfNeeded()
        // 启动微动作循环
        if (microMotionEnabled) postDelayed(microMotionRunnable, 900L)
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE && isShown) {
            startAnimatorIfNeeded()
        } else {
            stopAnimatorIfNeeded()
        }
    }

    private fun startAnimatorIfNeeded() {
        if (!animator.isStarted) {
            animator.start()
        }
    }

    private fun stopAnimatorIfNeeded() {
        if (animator.isStarted) {
            animator.cancel()
        }
    }

    override fun setAudioLevel(level: Float) {
        audioLevel = level.coerceIn(0f, 1f)
        spriteChoreographyEngine.refresh(spriteChoreographyContext())
        invalidate()
    }

    override fun setDanceStyle(style: DanceStyle) {
        if (style == danceStyle) return
        danceStyle = style
        animator.duration = styleDurationMs(style)
        resetPoseChoreography()
        resetSpriteChoreography()
    }

    // Consolidated setPlaybackState method
    override fun setPlaybackState(state: PlaybackDanceState) {
        if (playbackState == state) return
        playbackState = state

        when (state) {
            PlaybackDanceState.PLAYING -> {
                isDancing = true
                animator.start()
            }
                PlaybackDanceState.PAUSED, PlaybackDanceState.STOPPED, PlaybackDanceState.IDLE -> {
                isDancing = false
                if (state == PlaybackDanceState.STOPPED) {
                    // 显示结束/待机精灵，避免在无音乐时继续显示动画帧
                    showEndSprite()
                }
            }
        }
    }

    // 在无音乐或停止状态时显示 end 精灵（若资源存在）
    private fun showEndSprite() {
        endSprite?.let {
            isAtEnd = true
            isAtBegin = false
            // 确保重绘以显示 end 精灵
            invalidate()
        } ?: run {
            // 如果没有 end 精灵资源，则简单地将标志置为 end 并重绘，画面会使用普通帧体系回退
            isAtEnd = true
            isAtBegin = false
            invalidate()
        }
    }

    override fun onSongChanged() {
        songChangeBoost = 1f
        beatBoost = max(beatBoost, 0.74f)
        recentBeatStrength = max(recentBeatStrength, 0.88f)
        expressionEnergy = max(expressionEnergy, 0.82f)
        blinkAmount = max(blinkAmount, 0.9f)
        beatCounter = 0
        resetPoseChoreography()
        resetSpriteChoreography()
        spriteFrameProgress = 0.16f
        pendingBeginRunnable?.let { removeCallbacks(it); pendingBeginRunnable = null }
        awaitingFirstBeat = true
        isAtEnd = true
        invalidate()
    }

    override fun onBeat(event: BeatEvent) {
        // Lightweight timing/energy updates; heavy state transitions will be
        // consumed once per animator frame (debounced) via pendingBeatEvent.
        if (lastBeatTimestampMs > 0L) {
            val interval = event.timestampMs - lastBeatTimestampMs
            if (interval in 240L..1_050L) {
                beatIntervalMs = (beatIntervalMs * 0.76f + interval * 0.24f).toLong()
            }
        }
        lastBeatTimestampMs = event.timestampMs

        recentBeatStrength = max(recentBeatStrength, event.strength.coerceIn(0f, 1f))
        beatBoost = max(beatBoost, 0.22f + event.strength.coerceIn(0f, 1f) * 0.78f)
        expressionEnergy = max(expressionEnergy, 0.24f + event.strength * 0.86f)

        awaitingFirstBeat = false
        pendingBeginRunnable?.let { removeCallbacks(it); pendingBeginRunnable = null }
        pendingEndRunnable?.let { removeCallbacks(it); pendingEndRunnable = null }
        isAtBegin = false
        isAtEnd = false

        // Record pending beat; animator will consume it once per frame. If multiple
        // beats arrive quickly, only the newest pending beat will be kept for processing.
        if (pendingBeatEvent == null || event.timestampMs > (pendingBeatEvent?.timestampMs ?: 0L)) {
            pendingBeatEvent = event
        }
    }

    private fun consumePendingBeat(event: BeatEvent) {
        val section = currentSection()
        val now = System.currentTimeMillis()

        // sprite帧切换冷却
        if (avatarSpriteFrames.size > 1 && spriteStepSequence().size > 1 && (now - lastSpriteSwitchTimestampMs > minSpriteSwitchIntervalMs)) {
            val denseFrameBonus = if (avatarSpriteFrames.size >= maxAvatarSpriteFrames) 1 else 0
            val spriteAdvance = when {
                songChangeBoost > 0.28f -> 4 + denseFrameBonus
                section == DanceSection.CHORUS && event.strength >= 0.9f -> 4 + denseFrameBonus
                section == DanceSection.PRE_CHORUS && event.strength >= 0.8f -> 3 + denseFrameBonus
                event.strength >= 0.84f -> 2 + denseFrameBonus
                event.strength >= 0.62f -> 2
                else -> 1
            }
            advanceSpriteChoreography(spriteAdvance)
            spriteFrameProgress = when {
                songChangeBoost > 0.28f || event.strength >= 0.9f -> 0.24f + audioLevel * 0.18f
                event.strength >= 0.78f -> 0.16f + audioLevel * 0.14f
                else -> 0.08f + event.strength.coerceAtLeast(0f).coerceAtMost(1f) * 0.2f + audioLevel * 0.08f
            }.coerceAtMost(0.94f)
            lastSpriteSwitchTimestampMs = now
        }
        if (event.strength >= 0.58f) {
            blinkAmount = max(blinkAmount, 0.82f)
            winkLeftEye = !winkLeftEye
        }

        val desiredDuration = (beatIntervalMs * styleBeatsPerCycle(danceStyle)).toLong().coerceIn(330L, 1_150L)
        animator.duration = ((animator.duration * 0.72f) + desiredDuration * 0.28f).toLong()

        beatCounter += 1
        spriteChoreographyEngine.refresh(spriteChoreographyContext(), force = true)

        // 如果在等待首个节拍，则到达节拍应取消等待并清理 begin/end 状态
        if (awaitingFirstBeat) {
            awaitingFirstBeat = false
            pendingBeginRunnable?.let { removeCallbacks(it); pendingBeginRunnable = null }
            pendingEndRunnable?.let { removeCallbacks(it); pendingEndRunnable = null }
            isAtEnd = false
            isAtBegin = false
            invalidate()
        }
    }

    override fun onDetachedFromWindow() {
        stopAnimatorIfNeeded()
        // stop micro-motion loop
        removeCallbacks(microMotionRunnable)
        pendingBeginRunnable?.let { removeCallbacks(it); pendingBeginRunnable = null }
        pendingEndRunnable?.let { removeCallbacks(it); pendingEndRunnable = null }
        tmpBitmap?.recycle()
        tmpBitmap = null
        tmpCanvas = null
        super.onDetachedFromWindow()
    }

    fun isAwaitingFirstBeat(): Boolean = awaitingFirstBeat

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val unit = min(width, height) / 220f
        val cx = width / 2f
        val cy = height / 2f

        val rawBeatWave = sin(min(1f, (System.currentTimeMillis() - lastBeatTimestampMs).coerceAtLeast(0L) / max(beatIntervalMs, 1L).toFloat()) * PI)
            .toFloat()
            .coerceAtLeast(0f)
        val idleWave = ((sin(phase * (2f * PI).toFloat() * 0.36f) + 1f) * 0.5f).coerceIn(0f, 1f)
        val beatWave = when (playbackState) {
            PlaybackDanceState.PLAYING -> rawBeatWave.coerceAtLeast(songChangeBoost * 0.28f)
            PlaybackDanceState.PAUSED -> (idleWave * 0.42f + songChangeBoost * 0.3f).coerceIn(0f, 1f)
            PlaybackDanceState.STOPPED, PlaybackDanceState.IDLE -> (idleWave * 0.22f + songChangeBoost * 0.24f).coerceIn(0f, 1f)
        }
        val playbackEnergyScale = when (playbackState) {
            PlaybackDanceState.PLAYING -> 1f
            PlaybackDanceState.PAUSED -> 0.34f
            PlaybackDanceState.STOPPED, PlaybackDanceState.IDLE -> 0.18f
        }
        val energy = ((audioLevel * 0.56f + beatBoost * 0.44f) * playbackEnergyScale + songChangeBoost * 0.52f).coerceIn(0f, 1.25f)
        val depthProfile = sectionDepthProfile()

        applyPalette(energy)
        drawStage(canvas, cx, cy, unit, beatWave, energy, depthProfile)

        // 直接计算基于节拍/微动作的动态 PoseFrame
        val frame = computeDynamicPose(phase, energy, danceStyle)
        val unifiedScale = fallbackRenderState.breathScale * fallbackRenderState.spritePulseScale
        drawCharacter(canvas, cx, cy, unit, frame, beatWave, energy, depthProfile, unifiedScale)

        // 调试覆盖层：显示关键状态，便于复现与定位重影问题
        if (debugOverlayEnabled) {
            val selection = spriteChoreographyEngine.selection(spriteChoreographyContext())
            val pair = resolveSpritePair()
            val currentIdx = selection?.currentFrameIndex ?: -1
            val nextIdx = selection?.nextFrameIndex ?: -1
            val info = listOf(
                "playback=$playbackState",
                "current=$currentIdx",
                "next=$nextIdx",
                "spriteProgress=${"%.2f".format(spriteFrameProgress)}",
                "transitionRecommended=${selection?.transitionRecommended ?: false}",
            ).joinToString(" | ")
            canvas.drawText(info, 8f * resources.displayMetrics.density, 16f * resources.displayMetrics.density, debugTextPaint)
        }
    }

    private fun drawStage(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        unit: Float,
        beatWave: Float,
        energy: Float,
        depthProfile: DepthProfile,
    ) {
        val glowRadius = (72f + beatBoost * 22f + audioLevel * 10f) * unit * depthProfile.glowBoost
        canvas.drawCircle(cx, cy + 18f * unit, glowRadius, stageGlowPaint)

        sparklePaint.color = Color.argb(
            ((38 + energy * 52f) * depthProfile.apertureAlpha).toInt().coerceIn(32, 152),
            172,
            216,
            255
        )
        canvas.drawOval(
            cx - 94f * unit * depthProfile.glowBoost,
            cy - 86f * unit * depthProfile.glowBoost,
            cx - 34f * unit,
            cy + 24f * unit * depthProfile.glowBoost,
            sparklePaint
        )
        sparklePaint.color = Color.argb(
            ((34 + energy * 48f) * depthProfile.apertureAlpha).toInt().coerceIn(30, 148),
            255,
            176,
            228
        )
        canvas.drawOval(
            cx + 34f * unit,
            cy - 92f * unit * depthProfile.glowBoost,
            cx + 96f * unit * depthProfile.glowBoost,
            cy + 18f * unit * depthProfile.glowBoost,
            sparklePaint
        )

        canvas.drawOval(
            cx - 82f * unit * depthProfile.glowBoost,
            cy + 46f * unit,
            cx + 82f * unit * depthProfile.glowBoost,
            cy + 88f * unit,
            stagePaint
        )
        canvas.drawOval(
            cx - 52f * unit * (0.94f + depthProfile.eqBoost * 0.08f),
            cy + 70f * unit,
            cx + 52f * unit * (0.94f + depthProfile.eqBoost * 0.08f),
            cy + 82f * unit,
            floorShadowPaint
        )

        val angle = phase * (2f * PI).toFloat()
        val barCount = when {
            depthProfile.eqBoost >= 1.2f -> 11
            depthProfile.eqBoost >= 1.04f -> 10
            else -> 9
        }
        val barWidth = 3.8f * unit
        val barGap = 2.4f * unit
        val startX = cx - (barCount * barWidth + (barCount - 1) * barGap) / 2f
        val baseY = cy + 85f * unit
        for (i in 0 until barCount) {
            val wave = ((12f + sin(angle + i * 0.62f) * 9f + audioLevel * 12f + beatWave * 8f) * depthProfile.eqBoost)
                .coerceAtLeast(4f)
            val left = startX + i * (barWidth + barGap)
            val alpha = ((110 + i * 10 + energy * 24f) * depthProfile.apertureAlpha).toInt().coerceIn(96, 236)
            wavePaint.color = Color.argb(alpha, 124 + i * 5, 178 + i * 2, 255)
            canvas.drawRoundRect(
                left,
                baseY - wave * unit,
                left + barWidth,
                baseY,
                1.9f * unit,
                1.9f * unit,
                wavePaint
            )
        }

        val sparkleCount = when {
            depthProfile.particleBoost >= 1.24f -> 10
            depthProfile.particleBoost >= 1.08f -> 8
            else -> 7
        }
        for (i in 0 until sparkleCount) {
            val orbit = angle + i * 0.9f
            val px = cx + cos(orbit.toDouble()).toFloat() * (44f + i * 7f) * unit * depthProfile.particleBoost
            val py = cy - 34f * unit + sin((orbit * 1.3f).toDouble()).toFloat() * (14f + energy * 8f) * unit * depthProfile.particleBoost
            val size = (1.7f + (i % 3) * 0.7f + beatWave * 1.2f) * unit * (0.96f + depthProfile.particleBoost * 0.16f)
            sparklePaint.color = if (i % 2 == 0) {
                Color.argb(((120 + energy * 40f) * depthProfile.apertureAlpha).toInt().coerceIn(110, 210), 255, 255, 255)
            } else {
                Color.argb(((104 + energy * 52f) * depthProfile.apertureAlpha).toInt().coerceIn(96, 216), 255, 186, 228)
            }
            canvas.drawCircle(px, py, size, sparklePaint)
        }
    }

    @SuppressLint("UseKtx")
    private fun drawCharacter(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        unit: Float,
        frame: PoseFrame,
        beatWave: Float,
        energy: Float,
        depthProfile: DepthProfile,
        unifiedScale: Float,
    ) {
        // pose帧平滑插值
        val now = System.currentTimeMillis()
        val prev = lastPoseFrame
        val prevTs = lastPoseTimestamp
        val transitionMs = 220L // 可根据实际动作切换时长调整
        val smoothFrame = if (prev != null && (now - prevTs) < transitionMs) {
            val t = ((now - prevTs).toFloat() / transitionMs).coerceIn(0f, 1f)
            PoseFrame(
                bodyAngle = MotionSmoother.bezier(prev.bodyAngle, frame.bodyAngle, t, bezierInterpolator),
                leftArmAngle = MotionSmoother.bezier(prev.leftArmAngle, frame.leftArmAngle, t, bezierInterpolator),
                rightArmAngle = MotionSmoother.bezier(prev.rightArmAngle, frame.rightArmAngle, t, bezierInterpolator),
                fanAngle = MotionSmoother.bezier(prev.fanAngle, frame.fanAngle, t, bezierInterpolator),
                skirtAngle = MotionSmoother.bezier(prev.skirtAngle, frame.skirtAngle, t, bezierInterpolator),
                headAngle = MotionSmoother.bezier(prev.headAngle, frame.headAngle, t, bezierInterpolator),
                leftLegLift = MotionSmoother.bezier(prev.leftLegLift, frame.leftLegLift, t, bezierInterpolator),
                rightLegLift = MotionSmoother.bezier(prev.rightLegLift, frame.rightLegLift, t, bezierInterpolator),
            )
        } else {
            frame
        }
        lastPoseFrame = smoothFrame
        lastPoseTimestamp = now
        val grooveAmp = styleAmplitude(danceStyle)
        val sectionAmp = sectionMotionMultiplier()
        val phraseAccent = sectionPhraseAccent()
        val swayX = sin(phase * (2f * PI).toFloat() * 1.15f + beatBoost * 0.4f) * (5f + energy * 9f) * unit * grooveAmp * sectionAmp
        val bounceY = (-(beatWave * 7f + energy * 3.6f + beatBoost * 3.4f) * unit * sectionAmp * phraseAccent) + depthProfile.forwardOffsetY * unit
        val centerX = cx + swayX + fallbackRenderState.headOffsetXUnits * unit * 0.35f
        val centerY = cy + bounceY - fallbackRenderState.breathLiftUnits * unit
        val bodyPivotY = centerY + 8f * unit
        val torsoTop = centerY - 30f * unit
        val torsoBottom = torsoTop + 98f * unit

        drawBeatParticles(canvas, centerX, centerY, unit, beatWave, energy, depthProfile)

        canvas.save()
        // Ensure consistent scaling for the floating avatar
        canvas.scale(unifiedScale, unifiedScale, centerX, centerY)

        if (drawSingleSpriteCharacter(canvas, centerX, centerY, unit, smoothFrame, beatWave, energy, unifiedScale)) {
            canvas.restore()
            return
        }
        if (drawLayeredSpriteCharacter(canvas, centerX, centerY, unit, smoothFrame)) {
            canvas.restore()
            return
        }

        drawLeg(canvas, centerX - 18f * unit, centerY + 70f * unit + smoothFrame.leftLegLift * unit, unit, true)
        drawLeg(canvas, centerX + 6f * unit, centerY + 70f * unit + smoothFrame.rightLegLift * unit, unit, false)

        canvas.save()
        canvas.rotate(smoothFrame.skirtAngle + smoothFrame.bodyAngle * 0.4f, centerX, bodyPivotY)
        drawSkirt(canvas, centerX, centerY, unit, energy)
        canvas.restore()

        canvas.withRotation(smoothFrame.bodyAngle, centerX, bodyPivotY) {
            drawTorso(this, centerX, torsoTop, torsoBottom, unit, energy)

            drawArm(
                canvas = this,
                pivotX = centerX - 31f * unit,
                pivotY = torsoTop + 10f * unit,
                angle = smoothFrame.leftArmAngle,
                length = 60f * unit,
                width = 18f * unit,
                isLeft = true,
            )
            drawArm(
                canvas = this,
                pivotX = centerX + 31f * unit,
                pivotY = torsoTop + 10f * unit,
                angle = smoothFrame.rightArmAngle,
                length = 60f * unit,
                width = 18f * unit,
                isLeft = false,
            )

            drawFan(this, centerX + 58f * unit, torsoTop - 8f * unit, unit, smoothFrame)
            drawHead(
                this,
                centerX + swayX * 0.08f + fallbackRenderState.headOffsetXUnits * unit * 0.4f,
                torsoTop - 52f * unit - fallbackRenderState.headOffsetYUnits * unit * 0.3f,
                unit * (1f + energy * 0.03f) * fallbackRenderState.breathScale,
                smoothFrame,
                beatWave,
                energy
            )
        }
        canvas.restore()
    }

    private fun drawBeatParticles(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        unit: Float,
        beatWave: Float,
        energy: Float,
        depthProfile: DepthProfile,
    ) {
        val baseAngle = phase * (2f * PI).toFloat()
        val orbitScale = (1f + beatWave * 0.45f + energy * 0.35f) * depthProfile.particleBoost
        val particleCount = when {
            depthProfile.particleBoost >= 1.24f -> 8
            depthProfile.particleBoost >= 1.08f -> 7
            else -> 6
        }
        for (i in 0 until particleCount) {
            val angle = baseAngle * (0.8f + i * 0.08f) + i * 1.04f
            val radiusX = (34f + i * 7f) * unit * orbitScale
            val radiusY = (18f + (i % 3) * 8f) * unit * orbitScale
            val px = cx + cos(angle.toDouble()).toFloat() * radiusX
            val py = cy - 30f * unit + sin(angle.toDouble()).toFloat() * radiusY
            val alpha = ((88 + beatBoost * 96f + energy * 46f) * depthProfile.apertureAlpha).toInt().coerceIn(72, 224)
            sparklePaint.color = when (i % 3) {
                0 -> Color.argb(alpha, 255, 255, 255)
                1 -> Color.argb(alpha, 255, 170, 230)
                else -> Color.argb(alpha, 160, 224, 255)
            }
            canvas.drawCircle(px, py, (2.2f + beatWave * 1.8f + (i % 2) * 0.8f) * unit * (0.94f + depthProfile.particleBoost * 0.14f), sparklePaint)
        }
    }

    private fun drawSingleSpriteCharacter(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        unit: Float,
        frame: PoseFrame,
        beatWave: Float,
        energy: Float,
        unifiedScale: Float,
    ): Boolean {
        // compute anchor early so begin/end sprites align consistently with regular sprite placement
        val anchorX = cx + fallbackRenderState.headOffsetXUnits * unit * 0.6f
        // lift character a bit by reducing base offset so feet are visible
        // adjusted so feet are within the view bounds
        val anchorYOffsetFactor = 1f + (avatarAnchorOffsetPercent / 100f)
        val anchorY = cy + 28f * unit * anchorYOffsetFactor - fallbackRenderState.breathLiftUnits * unit

        // Show begin or end sprite if at the very start or end
        if (isAtBegin && beginSprite != null) {
            drawDeformedSprite(
                canvas = canvas,
                sprite = beginSprite!!,
                anchorX = anchorX,
                anchorY = anchorY,
                targetWidth = width * 0.85f,
                targetHeight = height * 0.7f,
                frame = frame,
                beatWave = beatWave,
                energy = energy,
                alphaFraction = 1f,
                outVerts = meshVerts,
            )
            return true
        }
        if (isAtEnd && endSprite != null) {
            drawDeformedSprite(
                canvas = canvas,
                sprite = endSprite!!,
                anchorX = anchorX,
                anchorY = anchorY,
                targetWidth = width * 0.85f,
                targetHeight = height * 0.7f,
                frame = frame,
                beatWave = beatWave,
                energy = energy,
                alphaFraction = 1f,
                outVerts = meshVerts,
            )
            return true
        }
        // Respect choreography engine recommendation to avoid ghosting when a transition
        // isn't recommended. resolveSpritePair() returns the frames but we ask the engine
        // whether a transition is recommended via selection().transitionRecommended.
        val selection = spriteChoreographyEngine.selection(spriteChoreographyContext())
        val pair = resolveSpritePair() ?: return false
        val (currentSprite, nextSprite) = pair

        val targetHeight = height * 0.7f * fallbackRenderState.breathScale * fallbackRenderState.spritePulseScale
        val targetWidth = width * 0.85f * fallbackRenderState.spritePulseScale
        val pulseScale = 1f + beatWave * 0.05f + energy * 0.03f

        // anchorX/anchorY already computed above; reuse

        canvas.drawOval(
            anchorX - 30f * unit * pulseScale,
            anchorY + 4f * unit,
            anchorX + 30f * unit * pulseScale,
            anchorY + 17f * unit,
            floorShadowPaint
        )

        // 计算过渡进度。
        // 1. 仅在过渡区间（0 < rawTransition < 1）内做alpha混合，且current+next alpha总和始终为1。
        // 2. 过渡区间外（rawTransition<=0或>=1）只绘制单一帧，绝不出现两帧全alpha重叠。
        val hasDistinctNextSprite = nextSprite != null && nextSprite.bitmap !== currentSprite.bitmap
        val rawTransition = if (hasDistinctNextSprite) smoothStep(spriteFrameProgress) else 0f
        val allowed = (selection?.transitionRecommended == true)

        if (hasDistinctNextSprite && allowed && rawTransition > 0f && rawTransition < 1f) {
            // 过渡期内，current/next alpha严格互补，mesh与离屏混合参数完全一致，避免重影
            val currentAlpha = (1f - rawTransition).coerceIn(0f, 1f)
            val nextAlpha = rawTransition.coerceIn(0f, 1f)
            // 此处currentAlpha+nextAlpha恒为1，且两帧mesh参数一致，视觉无重叠
            if (useMeshForSprites) {
                drawBothWithSharedMesh(
                    canvas = canvas,
                    currentSprite = currentSprite,
                    nextSprite = nextSprite,
                    anchorX = anchorX,
                    anchorY = anchorY,
                    targetWidth = targetWidth,
                    targetHeight = targetHeight,
                    frame = frame,
                    beatWave = beatWave,
                    energy = energy,
                    currentAlpha = currentAlpha,
                    nextAlpha = nextAlpha,
                )
            } else {
                drawBothToOffscreen(
                    canvas = canvas,
                    currentSprite = currentSprite,
                    nextSprite = nextSprite,
                    anchorX = anchorX,
                    anchorY = anchorY,
                    targetWidth = targetWidth,
                    targetHeight = targetHeight,
                    frame = frame,
                    beatWave = beatWave,
                    energy = energy,
                    currentAlpha = currentAlpha,
                    nextAlpha = nextAlpha,
                    viewScale = unifiedScale,
                )
            }
        } else {
            // 非过渡区间或不推荐过渡时，只绘制单一帧，绝无重叠
            val drawSprite = if (allowed && rawTransition >= 1f && nextSprite != null) nextSprite else currentSprite
            drawDeformedSprite(
                canvas = canvas,
                sprite = drawSprite,
                anchorX = anchorX,
                anchorY = anchorY,
                targetWidth = targetWidth,
                targetHeight = targetHeight,
                frame = frame,
                beatWave = beatWave,
                energy = energy,
                alphaFraction = 1f,
                outVerts = if (drawSprite === nextSprite) meshVertsAlt else meshVerts,
            )
        }

        return true
    }

    private fun drawDeformedSprite(
        canvas: Canvas,
        sprite: LayerSprite,
        anchorX: Float,
        anchorY: Float,
        targetWidth: Float,
        targetHeight: Float,
        frame: PoseFrame,
        beatWave: Float,
        energy: Float,
        alphaFraction: Float,
        outVerts: FloatArray,
    ) {
        if (alphaFraction <= 0f) return

        // If mesh usage is disabled or alpha < 1 (during blending), use simple scaled draw
        if (!useMeshForSprites || alphaFraction < 1f) {
            val left = anchorX - targetWidth * 0.5f
            val top = anchorY - targetHeight * 0.5f
            val rect = android.graphics.RectF(left, top, left + targetWidth, top + targetHeight)
            val prevAlpha = bitmapPaint.alpha
            bitmapPaint.alpha = (alphaFraction * 255f).toInt().coerceIn(0, 255)
            canvas.drawBitmap(sprite.bitmap, null, rect, bitmapPaint)
            bitmapPaint.alpha = prevAlpha
            return
        }

        // Otherwise use mesh deformation path
        // mesh draw
        computeDeformedVerts(sprite, anchorX, anchorY, targetWidth, targetHeight, frame, beatWave, energy, alphaFraction, outVerts)
        val previousAlpha = bitmapPaint.alpha
        bitmapPaint.alpha = (alphaFraction * 255f).toInt().coerceIn(0, 255)
        canvas.drawBitmapMesh(
            sprite.bitmap,
            meshWidth,
            meshHeight,
            outVerts,
            0,
            null,
            0,
            bitmapPaint
        )
        bitmapPaint.alpha = previousAlpha
    }

    private fun computeDeformedVerts(
        sprite: LayerSprite,
        anchorX: Float,
        anchorY: Float,
        targetWidth: Float,
        targetHeight: Float,
        frame: PoseFrame,
        beatWave: Float,
        energy: Float,
        alphaFraction: Float,
        outVerts: FloatArray,
    ) {
        val allFrames = avatarSpriteFrames
        val referenceWidth = (allFrames.maxOfOrNull { it.bitmap.width } ?: sprite.bitmap.width).toFloat()
        val referenceHeight = (allFrames.maxOfOrNull { it.bitmap.height } ?: sprite.bitmap.height).toFloat()
        val baseScale = min(targetHeight / referenceHeight, targetWidth / referenceWidth)
        val pulseScale = (1f + beatWave * 0.05f + energy * 0.03f) * fallbackRenderState.spritePulseScale
        val finalScale = baseScale * pulseScale

        val bitmapW = sprite.bitmap.width.toFloat()
        val bitmapH = sprite.bitmap.height.toFloat()
        val bodyWave = sin(phase * (2f * PI).toFloat() * 1.18f + 0.32f)
        val armWave = sin(phase * (2f * PI).toFloat() * 2.06f + 0.52f)
        val shoulderBandTop = 0.22f
        val shoulderBandBottom = 0.56f
        val legBandTop = 0.56f
        val waistPivotY = -bitmapH * 0.42f

        var index = 0
        for (y in 0..meshHeight) {
            val v = y.toFloat() / meshHeight
            for (x in 0..meshWidth) {
                val u = x.toFloat() / meshWidth

                val localX = (u - 0.5f) * bitmapW
                val localY = (v - 1.0f) * bitmapH

                val heightFactor = 1.0f - v
                val spineAngle = Math.toRadians(frame.bodyAngle.toDouble() * heightFactor).toFloat()

                val cosSpine = cos(spineAngle)
                val sinSpine = sin(spineAngle)

                var tx = localX * cosSpine - localY * sinSpine
                var ty = localX * sinSpine + localY * cosSpine

                if (v < 0.25f) {
                    val headFactor = (0.25f - v) / 0.25f
                    val headRad = Math.toRadians(frame.headAngle.toDouble() * headFactor).toFloat()
                    val neckY = -0.75f * bitmapH
                    val hx = tx
                    val hy = ty - neckY
                    val cosHead = cos(headRad)
                    val sinHead = sin(headRad)

                    tx = hx * cosHead - hy * sinHead
                    ty = hx * sinHead + hy * cosHead + neckY
                }

                ty *= (1f + beatWave * 0.05f * (1f - v))
                val deformBlend = 0.5f + 0.5f * alphaFraction // ranges 0.5..1.0
                tx *= (1f + energy * 0.02f * sin(v * PI.toFloat()) * deformBlend)

                val side = (u - 0.5f) * 2f
                val leftInfluence = ((0.5f - u) / 0.5f).coerceIn(0f, 1f)
                val rightInfluence = ((u - 0.5f) / 0.5f).coerceIn(0f, 1f)
                val shoulderBand = ((v - shoulderBandTop) / (shoulderBandBottom - shoulderBandTop)).coerceIn(0f, 1f)
                val shoulderEnvelope = sin(shoulderBand * PI.toFloat())
                val legBand = ((v - legBandTop) / (1f - legBandTop)).coerceIn(0f, 1f)

                val dynBlend = 0.6f + 0.4f * alphaFraction // 0.6..1.0
                tx += bodyWave * heightFactor * bitmapW * 0.032f * dynBlend
                tx += shoulderEnvelope * leftInfluence * frame.leftArmAngle * bitmapW * 0.0019f * dynBlend
                tx += shoulderEnvelope * rightInfluence * frame.rightArmAngle * bitmapW * 0.0019f * dynBlend
                tx += shoulderEnvelope * armWave * side * bitmapW * (0.01f + energy * 0.006f) * dynBlend
                tx += fallbackRenderState.bodyLeanDeg * heightFactor * bitmapW * 0.0009f * dynBlend

                val legSwing = (frame.leftLegLift * leftInfluence + frame.rightLegLift * rightInfluence) * legBand
                ty += legSwing * bitmapH * 0.0028f
                tx += legBand * side * bitmapW * 0.012f * beatWave

                if (v > 0.48f) {
                    val skirtBand = ((v - 0.48f) / 0.52f).coerceIn(0f, 1f)
                    tx += side * skirtBand * bitmapW * (0.018f + beatBoost * 0.018f)
                }

                if (ty < waistPivotY) {
                    val upperFactor = ((waistPivotY - ty) / bitmapH).coerceIn(0f, 0.4f)
                    tx += sin(frame.headAngle.toDouble() * PI / 180.0).toFloat() * bitmapW * upperFactor * 0.34f
                }

                if (v < 0.4f) {
                    val faceDepth = ((0.4f - v) / 0.4f).coerceIn(0f, 1f)
                    tx += fallbackRenderState.faceOffsetXUnits * bitmapW * 0.012f * faceDepth
                    ty += fallbackRenderState.faceOffsetYUnits * bitmapH * 0.01f * faceDepth
                }
                if (v in 0.56f..0.78f) {
                    val mouthBand = 1f - abs(v - 0.67f) / 0.11f
                    val centerInfluence = (1f - abs(side)).coerceIn(0f, 1f)
                    ty += mouthBand.coerceAtMost(1f) * centerInfluence * bitmapH * 0.022f * fallbackRenderState.mouthOpen
                }

                outVerts[index++] = anchorX + tx * finalScale
                outVerts[index++] = anchorY + ty * finalScale
            }
        }
    }

    private fun drawBothWithSharedMesh(
        canvas: Canvas,
        currentSprite: LayerSprite,
        nextSprite: LayerSprite,
        anchorX: Float,
        anchorY: Float,
        targetWidth: Float,
        targetHeight: Float,
        frame: PoseFrame,
        beatWave: Float,
        energy: Float,
        currentAlpha: Float,
        nextAlpha: Float,
    ) {
        // 为避免在过渡时出现重影/错位，必须保证 current 与 next 使用完全一致的顶点网格。
        // 问题根源：之前分别使用 currentAlpha/nextAlpha 计算网格（computeDeformedVerts），
        // 导致两帧对应的顶点位置不同，随后将它们按权重混合得到 midVerts 再去绘制，会产生微小位移，造成重影。
        // 解决方案：采用统一的参考网格（使用 alphaFraction = 1f 计算），使两张图在顶点映射上一致，
        // 然后只改变 bitmapPaint.alpha 来做透明度混合。这样可避免因为顶点差异导致的视觉重影。

        // 计算统一 mesh（alphaFraction 固定为 1f，保证变形参数一致）
        computeDeformedVerts(currentSprite, anchorX, anchorY, targetWidth, targetHeight, frame, beatWave, energy, 1f, meshVerts)
        // 直接复用 meshVerts 到 midVerts，避免额外计算
        if (midVerts.size == meshVerts.size) {
            System.arraycopy(meshVerts, 0, midVerts, 0, midVerts.size)
        } else {
            for (i in meshVerts.indices) midVerts[i] = meshVerts[i]
        }

        val prevAlpha = bitmapPaint.alpha
        // 先绘制 current，再绘制 next，两者共用 midVerts，仅通过 alpha 做混合，避免顶点不一致
        bitmapPaint.alpha = (currentAlpha.coerceIn(0f, 1f) * 255f).toInt().coerceIn(0, 255)
        canvas.drawBitmapMesh(currentSprite.bitmap, meshWidth, meshHeight, midVerts, 0, null, 0, bitmapPaint)
        bitmapPaint.alpha = (nextAlpha.coerceIn(0f, 1f) * 255f).toInt().coerceIn(0, 255)
        canvas.drawBitmapMesh(nextSprite.bitmap, meshWidth, meshHeight, midVerts, 0, null, 0, bitmapPaint)
        bitmapPaint.alpha = prevAlpha
    }

    private fun ensureTmpBitmap(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        if (tmpBitmap?.width == w && tmpBitmap?.height == h) return
        tmpBitmap?.recycle()
        tmpBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { setHasAlpha(true) }
        tmpCanvas = Canvas(tmpBitmap!!)
    }

    private fun drawBothToOffscreen(
        canvas: Canvas,
        currentSprite: LayerSprite,
        nextSprite: LayerSprite,
        anchorX: Float,
        anchorY: Float,
        targetWidth: Float,
        targetHeight: Float,
        frame: PoseFrame,
        beatWave: Float,
        energy: Float,
        currentAlpha: Float,
        nextAlpha: Float,
        viewScale: Float,
    ) {

        // 计算基于当前（已被可能 scale）画布坐标的目标矩形
        val halfW = targetWidth * 0.5f
        val halfH = targetHeight * 0.5f
        val rectView = RectF(
            anchorX - halfW,
            anchorY - halfH,
            anchorX + halfW,
            anchorY + halfH,
        )

        val rectW = rectView.width().coerceAtLeast(1f)
        val rectH = rectView.height().coerceAtLeast(1f)

        // 临时位图尺寸使用目标矩形的像素大小，避免使用整个视图大小导致内存/性能问题
        val tmpW = rectW.toInt().coerceAtLeast(1)
        val tmpH = rectH.toInt().coerceAtLeast(1)
        ensureTmpBitmap(tmpW, tmpH)
        val bc = tmpCanvas ?: return

        // 清空 tmpCanvas（透明）
        tmpBitmap?.eraseColor(Color.TRANSPARENT)

        val prevAlpha = bitmapPaint.alpha

        // 在 tmpCanvas 的坐标系里，把两帧绘制为覆盖整个 tmpBitmap
        val rectDst = RectF(0f, 0f, tmpW.toFloat(), tmpH.toFloat())

        // 绘制 current
        bitmapPaint.alpha = (currentAlpha * 255f).toInt().coerceIn(0, 255)
        bc.save()
        bc.setMatrix(null)
        bc.drawBitmap(currentSprite.bitmap, null, rectDst, bitmapPaint)

        // 绘制 next
        bitmapPaint.alpha = (nextAlpha * 255f).toInt().coerceIn(0, 255)
        bc.drawBitmap(nextSprite.bitmap, null, rectDst, bitmapPaint)
        bc.restore()

        bitmapPaint.alpha = prevAlpha

        // 把合成后的 tmpBitmap 绘制回主画布，目标矩形使用 rectView（主画布当前变换会被正确应用）
        canvas.drawBitmap(tmpBitmap!!, null, rectView, null)
    }

    private fun drawLayeredSpriteCharacter(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        unit: Float,
        frame: PoseFrame,
    ): Boolean {
        if (layeredSprites.isEmpty()) return false

        val body = layeredSprites["body"] ?: return false
        val head = layeredSprites["head"] ?: return false

        val bodyPivotX = cx
        val baseScale = unit / 3.6f

        val skirt = layeredSprites["skirt"]
        val leftLeg = layeredSprites["leg_left"]
        val rightLeg = layeredSprites["leg_right"]
        val leftArm = layeredSprites["arm_left"]
        val rightArm = layeredSprites["arm_right"]
        val fan = layeredSprites["fan"]

        leftLeg?.let {
            drawSpriteLayer(
                canvas = canvas,
                sprite = it,
                anchorX = bodyPivotX - 19f * unit,
                anchorY = cy + 74f * unit + frame.leftLegLift * unit,
                rotationDeg = 0f,
                scale = baseScale,
            )
        }
        rightLeg?.let {
            drawSpriteLayer(
                canvas = canvas,
                sprite = it,
                anchorX = bodyPivotX + 9f * unit,
                anchorY = cy + 74f * unit + frame.rightLegLift * unit,
                rotationDeg = 0f,
                scale = baseScale,
            )
        }

        skirt?.let {
            drawSpriteLayer(
                canvas = canvas,
                sprite = it,
                anchorX = bodyPivotX,
                anchorY = cy + 20f * unit,
                rotationDeg = frame.skirtAngle + frame.bodyAngle * 0.35f,
                scale = baseScale,
            )
        }

        drawSpriteLayer(
            canvas = canvas,
            sprite = body,
            anchorX = bodyPivotX,
            anchorY = cy + 16f * unit,
            rotationDeg = frame.bodyAngle,
            scale = baseScale,
        )

        leftArm?.let {
            drawSpriteLayer(
                canvas = canvas,
                sprite = it,
                anchorX = bodyPivotX - 31f * unit,
                anchorY = cy - 18f * unit,
                rotationDeg = frame.leftArmAngle + frame.bodyAngle * 0.5f,
                scale = baseScale,
            )
        }

        rightArm?.let {
            drawSpriteLayer(
                canvas = canvas,
                sprite = it,
                anchorX = bodyPivotX + 31f * unit,
                anchorY = cy - 18f * unit,
                rotationDeg = frame.rightArmAngle + frame.bodyAngle * 0.5f,
                scale = baseScale,
            )
        }

        fan?.let {
            drawSpriteLayer(
                canvas = canvas,
                sprite = it,
                anchorX = bodyPivotX + 60f * unit,
                anchorY = cy - 30f * unit,
                rotationDeg = frame.fanAngle + frame.rightArmAngle * 0.45f + frame.bodyAngle * 0.35f,
                scale = baseScale,
            )
        }

        drawSpriteLayer(
            canvas = canvas,
            sprite = head,
            anchorX = bodyPivotX,
            anchorY = cy - 82f * unit,
            rotationDeg = frame.headAngle + frame.bodyAngle * 0.25f,
            scale = baseScale,
        )
        return true
    }

    @SuppressLint("UseKtx")
    private fun drawSpriteLayer(
        canvas: Canvas,
        sprite: LayerSprite,
        anchorX: Float,
        anchorY: Float,
        rotationDeg: Float,
        scale: Float,
    ) {
        val bitmap = sprite.bitmap
        val pivotX = bitmap.width * sprite.pivotXRatio
        val pivotY = bitmap.height * sprite.pivotYRatio
        canvas.withTranslation(anchorX, anchorY) {
            rotate(rotationDeg)
            scale(scale, scale)
            drawBitmap(bitmap, -pivotX, -pivotY, bitmapPaint)
        }
    }

    private fun loadLayeredSprites(): Map<String, LayerSprite> {
        // Delegate asset discovery + preprocessing to AvatarLoader.
        val specs = mapOf(
            "body" to Pair("avatar_body", Pair(0.5f, 0.2f)),
            "head" to Pair("avatar_head", Pair(0.5f, 0.8f)),
            "arm_left" to Pair("avatar_arm_left", Pair(0.9f, 0.08f)),
            "arm_right" to Pair("avatar_arm_right", Pair(0.1f, 0.08f)),
            "fan" to Pair("avatar_fan", Pair(0.2f, 0.85f)),
            "skirt" to Pair("avatar_skirt", Pair(0.5f, 0.1f)),
            "leg_left" to Pair("avatar_leg_left", Pair(0.5f, 0.08f)),
            "leg_right" to Pair("avatar_leg_right", Pair(0.5f, 0.08f)),
        )

        val keys = specs.values.map { it.first }.toList()
        val loaded = mutableMapOf<String, LayerSprite>()

        val loadedMap = AvatarLoader.loadLayeredSprites(
            context = context,
            keys = keys,
            prefersVariant = prefersAvatar1(),
            avatarDir = avatarDir(),
            avatarVariantDir = avatarVariantDir(),
        )

        // Map loaded results into LayerSprite and preserve pivot info from specs when available
        for ((key, spec) in specs) {
            val name = spec.first
            val piv = spec.second
            loadedMap[name]?.let { ls ->
                loaded[key] = LayerSprite(ls.bitmap, piv.first, piv.second)
            }
        }

        // Fallback to legacy drawable resources if nothing found for a part
        for ((key, spec) in specs) {
            if (loaded.containsKey(key)) continue
            val (name, piv) = spec
            val resId = findDrawableResourceIdByName(name)
            if (resId != 0) {
                BitmapFactory.decodeResource(resources, resId)?.let { bmp ->
                    loaded[key] = LayerSprite(prepareAvatarBitmap(bmp), piv.first, piv.second)
                }
            }
        }

        return loaded
    }

    @SuppressLint("DiscouragedApi")
    private fun findDrawableResourceIdByName(name: String): Int {
        return resources.getIdentifier(name, "drawable", context.packageName)
    }

    private fun loadAvatarSpriteFrames(): List<LayerSprite> {
        val preferredDir = avatarDir()
        val variantDir = avatarVariantDir()
        val loaded = AvatarLoader.loadSingleSpriteFrames(
            context = context,
            preferredDir = if (prefersAvatar1()) variantDir else preferredDir,
            otherDir = if (prefersAvatar1()) preferredDir else variantDir,
            maxFrames = maxAvatarSpriteFrames,
        )

        if (loaded.isNotEmpty()) {
            return loaded.map { LayerSprite(it.bitmap, it.pivotX, it.pivotY) }
        }

        // Fallback to drawable resources (legacy)
        val drawableFrames = (1..maxAvatarSpriteFrames).mapNotNull { index ->
            val resId = findDrawableResourceIdByName("dancer_single$index")
            if (resId != 0) {
                val bmp = BitmapFactory.decodeResource(resources, resId) ?: return@mapNotNull null
                return@mapNotNull LayerSprite(
                    bitmap = prepareAvatarBitmap(bmp),
                    pivotXRatio = 0.5f,
                    pivotYRatio = 0.5f,
                )
            } else null
        }
        if (drawableFrames.isNotEmpty()) return drawableFrames

        val legacy = loadLegacySingleAvatarSprite()?.let(::listOf).orEmpty()
        return legacy
    }

    private fun singleSpriteAssetCandidates(index: Int): List<String> {
        val baseName = "dancer_single$index"
        val extensions = listOf("png", "jpg", "jpeg", "webp")
        return buildList {
            // Prefer user-selected variant subfolder first (avatar1 or avatar), then fall back to root assets
                    val preferredDir = if (prefersAvatar1()) avatarVariantDir() else avatarDir()
                    val otherDir = if (preferredDir == avatarDir()) avatarVariantDir() else avatarDir()
            for (extension in extensions) {
                add("$preferredDir/$baseName.$extension")
            }
            for (extension in extensions) {
                add("$otherDir/$baseName.$extension")
            }
            for (extension in extensions) {
                add("$baseName.$extension")
            }
        }
    }

    private fun loadLegacySingleAvatarSprite(): LayerSprite? {
        val base = listOf(
            "dancer_single_begin",
            "reference",
            "avatar",
            "avatar1",
        )

        val exts = listOf("png", "jpg", "jpeg", "webp")
        val candidates = mutableListOf<String>()
        val dirs = if (prefersAvatar1()) listOf(AvatarAssets.DIR_AVATAR1, AvatarAssets.DIR_AVATAR, "") else listOf(AvatarAssets.DIR_AVATAR, AvatarAssets.DIR_AVATAR1, "")
        for (dir in dirs) {
            for (name in base) {
                for (ext in exts) {
                    if (dir.isEmpty()) candidates.add("$name.$ext") else candidates.add("$dir/$name.$ext")
                }
            }
        }
        return loadSpriteFromCandidates(candidates)
    }

    private fun loadSpriteFromCandidates(candidates: List<String>): LayerSprite? {
        for (path in candidates) {
            val bitmap = kotlin.runCatching {
                context.assets.open(path).use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
                    inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
                    inMutable = true
                }) }
            }.getOrNull() ?: continue

            return LayerSprite(
                bitmap = prepareAvatarBitmap(bitmap),
                pivotXRatio = 0.5f,
                pivotYRatio = 0.5f,
            )
        }
        return null
    }

    private fun prepareAvatarBitmap(bitmap: Bitmap): Bitmap {
        var source = bitmap
        if (source.width > source.height * 2) {
            val oneWidth = source.width / 5
            source = Bitmap.createBitmap(source, 0, 0, oneWidth, source.height)
        }
        val transparent = autoRemoveBackground(source)
        val cropped = cropBitmapTransparency(transparent)
        // 统一输出尺寸（比如 500x400，宽高比 5:4）
        val targetWidth = 500
        val targetHeight = 400
        val result = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888).apply {
            setHasAlpha(true)
            setPremultiplied(true)
        }
        val canvas = Canvas(result)
        canvas.drawColor(Color.TRANSPARENT)
        // 保持内容最大化且底部对齐，避免不同帧在垂直方向跳动造成重影
        val scale = minOf(targetWidth.toFloat() / cropped.width, targetHeight.toFloat() / cropped.height)
        val drawWidth = (cropped.width * scale).toInt()
        val drawHeight = (cropped.height * scale).toInt()
        val left = (targetWidth - drawWidth) / 2
        // bottom-align: place the image so its bottom matches the target bottom
        val top = targetHeight - drawHeight
        val rect = android.graphics.Rect(left, top, left + drawWidth, top + drawHeight)
        canvas.drawBitmap(cropped, null, rect, null)
        return result
    }

    private fun autoRemoveBackground(source: Bitmap): Bitmap {
        // Sample corners to detect background color
        val width = source.width
        val height = source.height

        if (source.hasAlpha()) return source

        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)

        val bgColor = pixels[0]
        val queue = java.util.ArrayDeque<Int>()
        val visited = BooleanArray(width * height)

        val corners = listOf(0, width - 1, (height - 1) * width, (height - 1) * width + width - 1)
        var seedsFound = false
        for (seed in corners) {
            if (isSimilarColor(pixels[seed], bgColor)) {
                queue.add(seed)
                visited[seed] = true
                seedsFound = true
            }
        }

        if (!seedsFound) return source

        while (!queue.isEmpty()) {
            val index = queue.removeFirst()
            pixels[index] = 0 // Make transparent

            val x = index % width
            val y = index / width

            // Check neighbors (Up, Down, Left, Right)
            // Up
            if (y > 0) {
                val nIndex = index - width
                if (!visited[nIndex]) {
                    if (isSimilarColor(pixels[nIndex], bgColor)) {
                        visited[nIndex] = true
                        queue.add(nIndex)
                    } else {
                        visited[nIndex] = true // Border detected
                    }
                }
            }
            // Down
            if (y < height - 1) {
                val nIndex = index + width
                if (!visited[nIndex]) {
                    if (isSimilarColor(pixels[nIndex], bgColor)) {
                        visited[nIndex] = true
                        queue.add(nIndex)
                    } else {
                        visited[nIndex] = true
                    }
                }
            }
            // Left
            if (x > 0) {
                val nIndex = index - 1
                if (!visited[nIndex]) {
                    if (isSimilarColor(pixels[nIndex], bgColor)) {
                        visited[nIndex] = true
                        queue.add(nIndex)
                    } else {
                        visited[nIndex] = true
                    }
                }
            }
            // Right
            if (x < width - 1) {
                val nIndex = index + 1
                if (!visited[nIndex]) {
                    if (isSimilarColor(pixels[nIndex], bgColor)) {
                        visited[nIndex] = true
                        queue.add(nIndex)
                    } else {
                        visited[nIndex] = true
                    }
                }
            }
        }

        val result = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888).apply {
            setHasAlpha(true)
            setPremultiplied(true)
        }
        return result
    }

    private fun isSimilarColor(c1: Int, c2: Int): Boolean {
        val r = abs(((c1 shr 16) and 0xFF) - ((c2 shr 16) and 0xFF))
        val g = abs(((c1 shr 8) and 0xFF) - ((c2 shr 8) and 0xFF))
        val b = abs((c1 and 0xFF) - (c2 and 0xFF))
        return r <= backgroundColorTolerance && g <= backgroundColorTolerance && b <= backgroundColorTolerance
    }

    private fun cropBitmapTransparency(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)

        var minX = width
        var minY = height
        var maxX = -1
        var maxY = -1

        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = pixels[y * width + x]
                val alpha = (pixel shr 24) and 0xFF
                if (alpha > 0) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }

        if (maxX < minX || maxY < minY) return source

        // Expand bounds slightly to preserve anti-aliased edges and avoid halo artifacts.
        val pad = 2
        val outMinX = (minX - pad).coerceAtLeast(0)
        val outMinY = (minY - pad).coerceAtLeast(0)
        val outMaxX = (maxX + pad).coerceAtMost(width - 1)
        val outMaxY = (maxY + pad).coerceAtMost(height - 1)

        val w = (outMaxX - outMinX) + 1
        val h = (outMaxY - outMinY) + 1
        val result = Bitmap.createBitmap(source, outMinX, outMinY, w, h).apply {
            setHasAlpha(true)
            setPremultiplied(true)
        }
        return result
    }

    private fun drawLeg(canvas: Canvas, x: Float, y: Float, unit: Float, isLeft: Boolean) {
        val legWidth = 14f * unit
        val legHeight = 38f * unit
        val skinTop = y + 1.5f * unit
        val kneeY = y + legHeight * 0.48f
        val sockTop = y + legHeight * 0.54f
        trimPaint.strokeWidth = 1.1f * unit

        canvas.drawRoundRect(x + 2f * unit, skinTop, x + legWidth - 1.5f * unit, kneeY, 5.2f * unit, 5.2f * unit, skinPaint)
        canvas.drawRoundRect(x + 2f * unit, skinTop, x + legWidth - 1.5f * unit, kneeY, 5.2f * unit, 5.2f * unit, Paint(trimPaint).apply { alpha = 120 })

        canvas.drawRoundRect(x, sockTop, x + legWidth, y + legHeight, 6f * unit, 6f * unit, stockingPaint)
        canvas.drawRoundRect(x, sockTop, x + legWidth, y + legHeight, 6f * unit, 6f * unit, Paint(trimPaint).apply { alpha = 150 })
        canvas.drawRect(x + 1.5f * unit, sockTop + 4f * unit, x + legWidth - 1.5f * unit, sockTop + 6.3f * unit, ribbonPaint)

        val shoeLeft = if (isLeft) x - 0.7f * unit else x + 0.1f * unit
        val shoeRight = if (isLeft) x + legWidth + 2.8f * unit else x + legWidth + 3.6f * unit
        canvas.drawRoundRect(
            shoeLeft,
            y + legHeight - 0.4f * unit,
            shoeRight,
            y + legHeight + 6f * unit,
            3.6f * unit,
            3.6f * unit,
            shoePaint
        )
        canvas.drawCircle(if (isLeft) shoeLeft + 1.8f * unit else shoeRight - 1.8f * unit, y + legHeight + 2.6f * unit, 1.15f * unit, ribbonPaint)
    }

    private fun drawSkirt(canvas: Canvas, cx: Float, cy: Float, unit: Float, energy: Float) {
        val top = cy + 8f * unit
        val bottom = cy + 68f * unit
        val spread = 1f + energy * 0.06f + beatBoost * 0.04f

        skirtPath.reset()
        skirtPath.moveTo(cx - 58f * unit * spread, top)
        skirtPath.quadTo(cx - 76f * unit * spread, cy + 38f * unit, cx - 48f * unit * spread, bottom)
        skirtPath.quadTo(cx, cy + 96f * unit, cx + 48f * unit * spread, bottom)
        skirtPath.quadTo(cx + 76f * unit * spread, cy + 38f * unit, cx + 58f * unit * spread, top)
        skirtPath.close()
        canvas.drawPath(skirtPath, bodyPaint)
        canvas.drawPath(skirtPath, trimPaint)

        skirtPath.reset()
        skirtPath.moveTo(cx - 50f * unit, cy + 18f * unit)
        skirtPath.quadTo(cx - 56f * unit, cy + 46f * unit, cx - 38f * unit, cy + 60f * unit)
        skirtPath.quadTo(cx, cy + 84f * unit, cx + 38f * unit, cy + 60f * unit)
        skirtPath.quadTo(cx + 56f * unit, cy + 46f * unit, cx + 50f * unit, cy + 18f * unit)
        skirtPath.close()
        canvas.drawPath(skirtPath, bodyShadePaint)

        trimPaint.strokeWidth = 1.3f * unit
        for (i in -3..3) {
            val x = cx + i * 12f * unit
            canvas.drawLine(x, top + 2f * unit, x, bottom + 10f * unit, trimPaint)
        }

        canvas.drawOval(cx - 16f * unit, cy + 10f * unit, cx + 16f * unit, cy + 24f * unit, ribbonPaint)
        canvas.drawCircle(cx - 20f * unit, cy + 16f * unit, 5.5f * unit, ribbonPaint)
        canvas.drawCircle(cx + 20f * unit, cy + 16f * unit, 5.5f * unit, ribbonPaint)
        canvas.drawCircle(cx, cy + 17f * unit, 4.6f * unit, ornamentPaint)

        cloudPath.reset()
        cloudPath.moveTo(cx - 42f * unit, cy + 46f * unit)
        cloudPath.addArc(RectF(cx - 48f * unit, cy + 40f * unit, cx - 30f * unit, cy + 56f * unit), 180f, 180f)
        cloudPath.addArc(RectF(cx - 34f * unit, cy + 38f * unit, cx - 16f * unit, cy + 54f * unit), 180f, 180f)
        trimPaint.strokeWidth = 1.05f * unit
        canvas.drawPath(cloudPath, trimPaint)

        ruyiPath.reset()
        ruyiPath.moveTo(cx + 26f * unit, cy + 50f * unit)
        ruyiPath.quadTo(cx + 32f * unit, cy + 40f * unit, cx + 40f * unit, cy + 50f * unit)
        ruyiPath.quadTo(cx + 46f * unit, cy + 58f * unit, cx + 30f * unit, cy + 62f * unit)
        fanAccentPaint.strokeWidth = 1.35f * unit
        canvas.drawPath(ruyiPath, fanAccentPaint)
    }

    private fun drawTorso(canvas: Canvas, cx: Float, top: Float, bottom: Float, unit: Float, energy: Float) {
        canvas.drawRoundRect(cx - 32f * unit, top, cx + 32f * unit, bottom, 14f * unit, 14f * unit, bodyPaint)
        canvas.drawRoundRect(cx - 32f * unit, top + 28f * unit, cx + 32f * unit, bottom, 10f * unit, 10f * unit, bodyShadePaint)
        canvas.drawOval(cx - 14f * unit, top + 8f * unit, cx + 14f * unit, top + 28f * unit, skinPaint)
        canvas.drawOval(cx - 12f * unit, top + 8f * unit, cx + 12f * unit, top + 22f * unit, skinShadePaint)
        canvas.drawRoundRect(cx - 24f * unit, top + 22f * unit, cx + 24f * unit, top + 32f * unit, 5f * unit, 5f * unit, ribbonPaint)
        canvas.drawCircle(cx, top + 27f * unit, 4.8f * unit, ornamentPaint)

        trimPaint.strokeWidth = 1.9f * unit
        canvas.drawRoundRect(cx - 32f * unit, top, cx + 32f * unit, bottom, 14f * unit, 14f * unit, trimPaint)

        for (i in 0 until 4) {
            canvas.drawCircle(
                cx + 21f * unit,
                top + 9f * unit + i * 9.5f * unit,
                2.4f * unit,
                ornamentPaint
            )
        }

        canvas.drawCircle(cx - 19f * unit, top + 35f * unit, (2.4f + energy * 0.6f) * unit, ribbonPaint)
        canvas.drawCircle(cx + 19f * unit, top + 35f * unit, (2.4f + energy * 0.6f) * unit, ribbonPaint)
    }

    @SuppressLint("UseKtx")
    private fun drawArm(
        canvas: Canvas,
        pivotX: Float,
        pivotY: Float,
        angle: Float,
        length: Float,
        width: Float,
        isLeft: Boolean,
    ) {
        canvas.withRotation(angle, pivotX, pivotY) {
            val left = if (isLeft) pivotX - width else pivotX
            val right = if (isLeft) pivotX else pivotX + width
            val round = 9f * (width / 20f)
            val sleeveBottom = pivotY + length * 0.28f
            drawRoundRect(left, pivotY, right, sleeveBottom, round, round, bodyPaint)
            drawRoundRect(left + width * 0.1f, sleeveBottom - 1.5f * (width / 18f), right - width * 0.1f, pivotY + length, round, round, skinPaint)
            drawCircle((left + right) / 2f, sleeveBottom + 1.3f * (width / 18f), 2.8f * (width / 18f), ribbonPaint)
            drawCircle((left + right) / 2f, pivotY + length + 1.4f * (width / 18f), 2.1f * (width / 18f), skinPaint)
            trimPaint.strokeWidth = 1.35f * (width / 18f)
            drawRoundRect(left, pivotY, right, pivotY + length, round, round, trimPaint)
        }
    }

    @SuppressLint("UseKtx")
    private fun drawFan(canvas: Canvas, cx: Float, cy: Float, unit: Float, frame: PoseFrame) {
        canvas.withRotation(frame.rightArmAngle * 0.5f + frame.fanAngle, cx, cy) {
            val fanRect = RectF(cx - 19f * unit, cy - 19f * unit, cx + 19f * unit, cy + 19f * unit)
            drawOval(fanRect, fanPaint)
            fanAccentPaint.strokeWidth = 1.5f * unit
            drawOval(fanRect, fanAccentPaint)

            drawLine(
                cx,
                cy,
                cx - 6.8f * unit,
                cy + 21f * unit,
                Paint(fanAccentPaint).apply {
                    style = Paint.Style.STROKE
                    color = Color.rgb(230, 182, 92)
                    strokeWidth = 2.2f * unit
                }
            )

            peonyPath.reset()
            peonyPath.moveTo(cx - 2f * unit, cy - 12f * unit)
            peonyPath.quadTo(cx + 10f * unit, cy - 16f * unit, cx + 14f * unit, cy - 6f * unit)
            peonyPath.quadTo(cx + 16f * unit, cy + 6f * unit, cx + 6f * unit, cy + 2f * unit)
            peonyPath.quadTo(cx - 2f * unit, cy + 6f * unit, cx - 8f * unit, cy - 2f * unit)
            peonyPath.quadTo(cx - 10f * unit, cy - 14f * unit, cx - 2f * unit, cy - 10f * unit)
            drawPath(peonyPath, fanAccentPaint)

        }
    }

    @SuppressLint("UseKtx")
    private fun drawHead(canvas: Canvas, cx: Float, cy: Float, unit: Float, frame: PoseFrame, beatWave: Float, energy: Float) {
        val headCx = cx + fallbackRenderState.headOffsetXUnits * unit
        val headCy = cy + fallbackRenderState.headOffsetYUnits * unit - fallbackRenderState.breathLiftUnits * unit * 0.2f
        val featureOffsetX = fallbackRenderState.faceOffsetXUnits * unit
        val featureOffsetY = fallbackRenderState.faceOffsetYUnits * unit
        canvas.withRotation(frame.headAngle + fallbackRenderState.headTiltDeg, headCx, headCy) {
            val headRadius = 21.5f * unit
            drawCircle(headCx, headCy, headRadius, skinPaint)
            drawOval(headCx - 11f * unit, headCy + 8f * unit, headCx + 11f * unit, headCy + 18f * unit, skinShadePaint)

            drawCircle(headCx - 17f * unit, headCy - 4f * unit, 8.2f * unit, hairPaint)
            drawCircle(headCx + 17f * unit, headCy - 4f * unit, 8.2f * unit, hairPaint)
            drawRoundRect(
                headCx - 16f * unit,
                headCy - 17.5f * unit,
                headCx + 16f * unit,
                headCy - 2f * unit,
                5f * unit,
                5f * unit,
                hairPaint
            )
            drawRoundRect(
                headCx - 14f * unit,
                headCy - 11f * unit,
                headCx + 14f * unit,
                headCy - 2f * unit,
                4f * unit,
                4f * unit,
                hairShadePaint
            )

            drawCircle(headCx - 20f * unit, headCy - 8f * unit, 2.8f * unit, ColorPaint.pink)
            drawRect(headCx + 14f * unit, headCy - 13f * unit, headCx + 24.5f * unit, headCy - 10f * unit, ornamentPaint)
            drawCircle(headCx + 24.5f * unit, headCy - 13f * unit, 1.5f * unit, ornamentPaint)

            val tailSwing = sin(phase * (2f * PI).toFloat() * 1.2f + energy) * (3.6f + energy * 3.2f)
            hairPath.reset()
            hairPath.moveTo(headCx - 15f * unit, headCy + 2f * unit)
            hairPath.quadTo(headCx - 32f * unit, headCy + 20f * unit, headCx - 24f * unit, headCy + 46f * unit + tailSwing * unit)
            hairPath.quadTo(headCx - 6f * unit, headCy + 36f * unit, headCx - 8f * unit, headCy + 10f * unit)
            hairPath.close()
            drawPath(hairPath, hairPaint)

            hairPath.reset()
            hairPath.moveTo(headCx + 15f * unit, headCy + 2f * unit)
            hairPath.quadTo(headCx + 32f * unit, headCy + 20f * unit, headCx + 24f * unit, headCy + 46f * unit - tailSwing * unit)
            hairPath.quadTo(headCx + 6f * unit, headCy + 36f * unit, headCx + 8f * unit, headCy + 10f * unit)
            hairPath.close()
            drawPath(hairPath, hairPaint)

            canvas.drawCircle(headCx - 17f * unit, headCy - 4f * unit, 3.1f * unit, ribbonPaint)
            canvas.drawCircle(headCx + 17f * unit, headCy - 4f * unit, 3.1f * unit, ribbonPaint)

            hairPath.reset()
            hairPath.moveTo(headCx - 20f * unit, headCy)
            hairPath.quadTo(headCx - 30f * unit, headCy + 20f * unit, headCx - 12f * unit, headCy + 36f * unit)
            hairPath.quadTo(headCx, headCy + 42f * unit, headCx + 12f * unit, headCy + 36f * unit)
            hairPath.quadTo(headCx + 30f * unit, headCy + 20f, headCx + 20f * unit, headCy)
            hairPath.close()
            drawPath(hairPath, Paint(hairShadePaint).apply { alpha = 72 })

            val blink = max(
                blinkAmount,
                if (abs(sin((System.currentTimeMillis() % 2200L) / 2200f * (2f * PI)).toFloat()) > 0.995f) 0.95f else 0f
            )
            val eyeScale = (1f - blink).coerceIn(0.18f, 1f)
            val smileLift = expressionEnergy * 0.8f + beatWave * 0.5f
            browPaint.strokeWidth = 1.2f * unit
            drawLine(
                headCx - 10.6f * unit + featureOffsetX * 0.35f,
                headCy - 7.6f * unit - fallbackRenderState.browLift * 1.6f * unit + featureOffsetY * 0.2f,
                headCx - 3.2f * unit + featureOffsetX * 0.45f,
                headCy - 8.8f * unit - fallbackRenderState.browLift * 1.05f * unit + featureOffsetY * 0.2f,
                browPaint,
            )
            drawLine(
                headCx + 3.2f * unit + featureOffsetX * 0.45f,
                headCy - 8.8f * unit - fallbackRenderState.browLift * 1.05f * unit + featureOffsetY * 0.2f,
                headCx + 10.6f * unit + featureOffsetX * 0.35f,
                headCy - 7.6f * unit - fallbackRenderState.browLift * 1.6f * unit + featureOffsetY * 0.2f,
                browPaint,
            )

            drawEye(this, headCx - 6.8f * unit + featureOffsetX, headCy - 2.2f * unit + featureOffsetY * 0.35f, unit, if (winkLeftEye) eyeScale else eyeScale * 0.6f)
            drawEye(this, headCx + 6.8f * unit + featureOffsetX, headCy - 2.2f * unit + featureOffsetY * 0.35f, unit, if (winkLeftEye) eyeScale * 0.6f else eyeScale)

            if (eyeScale > 0.35f) {
                canvas.drawCircle(headCx - 6.2f * unit + featureOffsetX, headCy - 1.4f * unit + featureOffsetY * 0.28f, 1.45f * unit, irisPaint)
                canvas.drawCircle(headCx + 6.2f * unit + featureOffsetX, headCy - 1.4f * unit + featureOffsetY * 0.28f, 1.45f * unit, irisPaint)
                canvas.drawCircle(headCx - 5.7f * unit + featureOffsetX, headCy - 2.4f * unit + featureOffsetY * 0.18f, 0.55f * unit, eyeHighlightPaint)
                canvas.drawCircle(headCx + 5.7f * unit + featureOffsetX, headCy - 2.4f * unit + featureOffsetY * 0.18f, 0.55f * unit, eyeHighlightPaint)
            }

            val previousBlushAlpha = blushPaint.alpha
            blushPaint.alpha = (previousBlushAlpha * fallbackRenderState.blushAlphaMultiplier).toInt().coerceIn(56, 220)
            drawOval(headCx - 14f * unit, headCy + 2f * unit, headCx - 7f * unit, headCy + 6f * unit, blushPaint)
            drawOval(headCx + 7f * unit, headCy + 2f * unit, headCx + 14f * unit, headCy + 6f * unit, blushPaint)
            blushPaint.alpha = previousBlushAlpha

            trimPaint.strokeWidth = 1.2f * unit
            val mouthHeight = (3.8f + fallbackRenderState.mouthOpen * 4.8f).coerceAtLeast(3.6f)
            val mouthTop = headCy + 5.8f * unit - smileLift * unit + featureOffsetY * 0.35f
            val mouthRect = RectF(
                headCx - (3.4f + fallbackRenderState.mouthOpen * 0.9f) * unit + featureOffsetX * 0.16f,
                mouthTop,
                headCx + (3.4f + fallbackRenderState.mouthOpen * 0.9f) * unit + featureOffsetX * 0.16f,
                mouthTop + mouthHeight * unit,
            )
            if (fallbackRenderState.mouthOpen > 0.18f) {
                drawOval(mouthRect, mouthFillPaint)
            }
            drawArc(
                mouthRect,
                14f,
                136f,
                false,
                trimPaint
            )

        }
    }

    private fun drawEye(canvas: Canvas, cx: Float, cy: Float, unit: Float, open: Float) {
        val eyeW = 2.5f * unit
        val eyeH = (3f * unit * open).coerceAtLeast(0.68f * unit)
        canvas.drawOval(cx - eyeW, cy - eyeH, cx + eyeW, cy + eyeH, eyePaint)
        if (open > 0.35f) {
            canvas.drawCircle(cx + 0.6f * unit, cy - 0.7f * unit, 0.85f * unit, eyeHighlightPaint)
        }
    }

    private fun playbackMotionScale(): Float {
        return when (playbackState) {
            PlaybackDanceState.PLAYING -> 1f
            PlaybackDanceState.PAUSED -> 0.55f
            PlaybackDanceState.STOPPED, PlaybackDanceState.IDLE -> 0.35f
        }
    }

    private fun currentSection(): DanceSection {
        if (playbackState != PlaybackDanceState.PLAYING) return DanceSection.VERSE
        if (songChangeBoost > 0.28f) return DanceSection.CHORUS

        return when (beatCounter.mod(24)) {
            in 0..7 -> DanceSection.VERSE
            in 8..15 -> DanceSection.PRE_CHORUS
            else -> DanceSection.CHORUS
        }
    }

    private fun currentPhraseBeat(): Int = beatCounter.mod(8)

    private fun sectionMotionMultiplier(): Float {
        val base = when (currentSection()) {
            DanceSection.VERSE -> 0.9f
            DanceSection.PRE_CHORUS -> 1.06f
            DanceSection.CHORUS -> 1.2f
        }
        return if (playbackState == PlaybackDanceState.PLAYING) base else base * 0.82f
    }

    private fun sectionPhraseAccent(): Float {
        val accent = when (currentPhraseBeat()) {
            0, 4 -> 1.16f
            2, 6 -> 1.06f
            else -> 0.96f
        }
        return when (currentSection()) {
            DanceSection.VERSE -> accent * 0.94f
            DanceSection.PRE_CHORUS -> accent
            DanceSection.CHORUS -> accent * 1.08f
        }
    }

    private fun sectionDepthProfile(): DepthProfile {
        val base = when (currentSection()) {
            DanceSection.VERSE -> DepthProfile(
                avatarScale = 0.95f,
                forwardOffsetY = -4f,
                glowBoost = 0.9f,
                particleBoost = 0.92f,
                eqBoost = 0.94f,
                apertureAlpha = 0.92f,
            )

            DanceSection.PRE_CHORUS -> DepthProfile(
                avatarScale = 1.01f,
                forwardOffsetY = 2f,
                glowBoost = 1f,
                particleBoost = 1.04f,
                eqBoost = 1.04f,
                apertureAlpha = 1f,
            )

            DanceSection.CHORUS -> DepthProfile(
                avatarScale = 1.12f,
                forwardOffsetY = 10f,
                glowBoost = 1.24f,
                particleBoost = 1.28f,
                eqBoost = 1.18f,
                apertureAlpha = 1.18f,
            )
        }

        val playbackScale = when (playbackState) {
            PlaybackDanceState.PLAYING -> 1f
            PlaybackDanceState.PAUSED -> 0.72f
            PlaybackDanceState.STOPPED, PlaybackDanceState.IDLE -> 0.58f
        }
        val songBoost = if (songChangeBoost > 0.28f) 0.08f else 0f

        return base.copy(
            avatarScale = base.avatarScale + songBoost,
            forwardOffsetY = base.forwardOffsetY + if (songBoost > 0f) 2f else 0f,
            glowBoost = 0.82f + (base.glowBoost - 0.82f) * playbackScale + songBoost * 0.8f,
            particleBoost = 0.86f + (base.particleBoost - 0.86f) * playbackScale + songBoost,
            eqBoost = 0.9f + (base.eqBoost - 0.9f) * playbackScale + songBoost * 0.7f,
            apertureAlpha = 0.88f + (base.apertureAlpha - 0.88f) * playbackScale + songBoost * 0.8f,
        )
    }

    private fun spriteStepSequence(): List<Int> {
        val context = spriteChoreographyContext()
        return if (context.frameCount <= 0) {
            emptyList()
        } else {
            List(spriteChoreographyEngine.stepCount(context)) { it }
        }
    }

    private fun spriteChoreographyContext(): SpriteChoreographyContext {
        return SpriteChoreographyContext(
            frameCount = avatarSpriteFrames.size,
            danceStyle = danceStyle,
            playbackState = playbackState,
            section = currentSection(),
            phraseBeat = currentPhraseBeat(),
            audioLevel = audioLevel,
            beatBoost = beatBoost,
            recentBeatStrength = recentBeatStrength,
            songChangeBoost = songChangeBoost,
            maxFrameCount = maxAvatarSpriteFrames,
        )
    }

    private fun resetPoseChoreography() {
        // Pose choreography deprecated: no-op to preserve legacy calls
    }

    private fun resetSpriteChoreography() {
        spriteChoreographyEngine.reset(spriteChoreographyContext())
        spriteFrameProgress = 0f
    }

    private fun advanceSpriteChoreography(steps: Int = 1) {
        spriteChoreographyEngine.advance(spriteChoreographyContext(), steps)
        spriteFrameProgress = 0f // 保证每次推进后进度归零，防止帧错位
    }

    private fun resolveSpritePair(): Pair<LayerSprite, LayerSprite?>? {
        val frames = avatarSpriteFrames
        if (frames.isEmpty()) return null

        val selection = spriteChoreographyEngine.selection(spriteChoreographyContext())
            ?: return frames.first() to null

        val currentFrameIndex = selection.currentFrameIndex.coerceIn(0, frames.lastIndex)
        val nextFrameIndex = selection.nextFrameIndex?.coerceIn(0, frames.lastIndex)
        return frames[currentFrameIndex] to nextFrameIndex?.let(frames::get)
    }

    private fun scheduleMicroMotionOnce() {
        if (!microMotionEnabled) return
        microHeadTiltDeg += (Random.nextFloat() - 0.5f) * 4f // ±2°
        microLeftArmOffset += (Random.nextFloat() - 0.5f) * 12f // ±6°
        microRightArmOffset += (Random.nextFloat() - 0.5f) * 12f // ±6°
    }

    // 运行时动态 PoseFrame（基于节拍、能量与微动作）
    private fun computeDynamicPose(timePhase: Float, energy: Float, style: DanceStyle): PoseFrame {
        val angle = timePhase * (2f * PI).toFloat()
        val amp = styleAmplitude(style) * playbackMotionScale() * sectionMotionMultiplier()
        val accent = 1f + beatBoost * 0.42f + songChangeBoost * 0.56f + (sectionPhraseAccent() - 1f) * 0.9f
        fun clamp(v: Float, minV: Float, maxV: Float) = v.coerceIn(minV, maxV)

        val bodyDelta = sin(angle * 1.15f + 0.12f) * (3.2f + energy * 3.8f) * amp * accent
        val leftArmDelta = sin(angle * 2.05f + 0.8f) * (7.4f + energy * 5.4f) * amp * accent + microLeftArmOffset * 0.6f
        val rightArmDelta = -sin(angle * 2.1f + 0.1f) * (7.8f + energy * 5.8f) * amp * accent + microRightArmOffset * 0.6f
        val fanDelta = sin(angle * 2.75f + 0.24f) * (6.2f + energy * 4.4f) * amp
        val skirtDelta = sin(angle * 2.35f + 0.6f) * (4.6f + energy * 4.2f) * amp
        val headDelta = sin(angle * 1.45f + 0.16f) * (2.4f + expressionEnergy * 2.8f) + microHeadTiltDeg
        val leftLegDelta = sin(angle * 1.85f + 0.34f) * (4.6f + beatBoost * 4.2f) * amp
        val rightLegDelta = -sin(angle * 1.85f + 0.34f) * (4.6f + beatBoost * 4.2f) * amp

        // 基线为0的 PoseFrame（没有预置 pose），仅使用动态偏移
        return PoseFrame(
            bodyAngle = clamp(bodyDelta, -8f, 8f),
            leftArmAngle = clamp(leftArmDelta, -20f, 20f),
            rightArmAngle = clamp(rightArmDelta, -20f, 20f),
            fanAngle = clamp(fanDelta, -10f, 10f),
            skirtAngle = clamp(skirtDelta, -8f, 8f),
            headAngle = clamp(headDelta, -6f, 6f),
            leftLegLift = clamp(leftLegDelta, -6f, 6f),
            rightLegLift = clamp(rightLegDelta, -6f, 6f),
        )
    }

    // Overlay mapping removed.

    private fun applyPalette(energy: Float) {
        when (danceStyle) {
            DanceStyle.CHILL -> {
                hairPaint.color = Color.rgb(96, 154, 238)
                hairShadePaint.color = Color.rgb(74, 116, 194)
                bodyPaint.color = Color.rgb(255, 194, 218)
                bodyShadePaint.color = Color.rgb(138, 196, 255)
                irisPaint.color = Color.rgb(88, 172, 255)
                ribbonPaint.color = Color.rgb(255, 116, 196)
            }

            DanceStyle.GROOVE -> {
                hairPaint.color = Color.rgb(120, 132, 240)
                hairShadePaint.color = Color.rgb(86, 96, 206)
                bodyPaint.color = Color.rgb(255, 180, 208)
                bodyShadePaint.color = Color.rgb(128, 176, 255)
                irisPaint.color = Color.rgb(124, 210, 255)
                ribbonPaint.color = Color.rgb(255, 96, 174)
            }

            DanceStyle.POWER -> {
                hairPaint.color = Color.rgb(146, 118, 248)
                hairShadePaint.color = Color.rgb(108, 70, 220)
                bodyPaint.color = Color.rgb(252, 166, 200)
                bodyShadePaint.color = Color.rgb(150, 154, 255)
                irisPaint.color = Color.rgb(154, 216, 255)
                ribbonPaint.color = Color.rgb(255, 86, 154)
            }
        }

        stagePaint.color = Color.argb((90 + energy * 36f).toInt().coerceIn(80, 168), 74, 90, 168)
        stageGlowPaint.color = Color.argb((48 + energy * 42f).toInt().coerceIn(42, 124), 120, 188, 255)
        fanPaint.color = Color.rgb((248 - energy * 10f).toInt().coerceIn(224, 248), 232, 240)
        fanAccentPaint.color = ribbonPaint.color
    }

    private fun smoothStep(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    private fun styleAmplitude(style: DanceStyle): Float {
        return when (style) {
            DanceStyle.CHILL -> 0.78f
            DanceStyle.GROOVE -> 1f
            DanceStyle.POWER -> 1.24f
        }
    }

    private fun styleBeatsPerCycle(style: DanceStyle): Float {
        return when (style) {
            DanceStyle.CHILL -> 2f
            DanceStyle.GROOVE -> 1.4f
            DanceStyle.POWER -> 1f
        }
    }

    private fun styleDurationMs(style: DanceStyle): Long {
        return when (style) {
            DanceStyle.CHILL -> 920L
            DanceStyle.GROOVE -> 690L
            DanceStyle.POWER -> 500L
        }
    }

    private object ColorPaint {
        val pink: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.rgb(255, 118, 186)
        }
    }

    private var isDancing = false
    // 调试开关：启用后会在画布左上角显示当前帧调试信息，便于重现/定位重影问题
    private var debugOverlayEnabled = false
    private val debugTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 12f * resources.displayMetrics.density
    }

}
