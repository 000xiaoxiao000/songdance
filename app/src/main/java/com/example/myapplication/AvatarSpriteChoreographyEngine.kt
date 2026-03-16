package com.example.myapplication

import kotlin.math.abs
import kotlin.math.roundToInt

// 镜头类型：远景、近景、侧面、俯视等
enum class CameraShotType {
    WIDE,
    CLOSE_UP,
    SIDE,
    TOP,
}

data class CameraShot(
    val type: CameraShotType,
    val startStep: Int,
    val endStep: Int,
)

enum class DanceSection {
    VERSE,
    PRE_CHORUS,
    CHORUS,
}

data class SpriteChoreographyContext(
    val frameCount: Int,
    val danceStyle: DanceStyle,
    val playbackState: PlaybackDanceState,
    val section: DanceSection,
    val phraseBeat: Int,
    val audioLevel: Float,
    val beatBoost: Float,
    val recentBeatStrength: Float,
    val songChangeBoost: Float,
    val maxFrameCount: Int = 9,
    val cameraShot: CameraShotType = CameraShotType.WIDE, // 当前镜头类型，默认远景
)

data class SpriteFrameSelection(
    val currentFrameIndex: Int,
    val nextFrameIndex: Int?,
    val transitionRecommended: Boolean,
)

class AvatarSpriteChoreographyEngine(
    private val maxFrameCount: Int,
) {

    // 动作语义支持变奏参数（如方向、手势、节奏变体）
    private enum class SpritePoseSemantic {
        IDLE,
        GROOVE_LEFT,
        GROOVE_RIGHT,
        TRAVEL_LEFT,
        TRAVEL_RIGHT,
        ACCENT_OPEN,
        ACCENT_CROSS,
        PEAK,
        RECOVERY,
        // 预留：变奏类型，如GROOVE_LEFT_VARIANT1, ...
    }

    private enum class SpriteFrameRole {
        ANCHOR,
        GROOVE,
        TRAVEL,
        ACCENT,
        PEAK,
        RECOVERY,
    }

    private data class SemanticChoreoProfile(
        val anchorSemantics: List<SpritePoseSemantic>,
        val grooveSemantics: List<SpritePoseSemantic>,
        val travelSemantics: List<SpritePoseSemantic>,
        val accentSemantics: List<SpritePoseSemantic>,
        val peakSemantics: List<SpritePoseSemantic>,
        val recoverySemantics: List<SpritePoseSemantic>,
        val maxJump: Int,
        val phraseLength: Int,
        // 预留：动作变奏参数，如方向、手势、节奏变体等
        val variationHints: List<String> = emptyList(),
    )

    private data class SpriteSelectionSnapshot(
        val frameCount: Int,
        val playbackState: PlaybackDanceState,
        val section: DanceSection,
        val phraseBeat: Int,
        val energyBand: Int,
        val accentBand: Int,
        val songTransition: Boolean,
    )

    private var cachedSequence: List<Int> = emptyList()
    private var cachedSignature = ""
    private var currentStepIndex = 0
    private var nextStepIndex = 0
    // 镜头切换点缓存
    private var cachedCameraShots: List<CameraShot> = emptyList()

    fun reset(context: SpriteChoreographyContext) {
        refresh(context, force = true)
        currentStepIndex = 0
        nextStepIndex = if (cachedSequence.size > 1) 1 else 0
    }

    fun refresh(context: SpriteChoreographyContext, force: Boolean = false) {
        val signature = sequenceSignature(context)
        if (!force && signature == cachedSignature && cachedSequence.isNotEmpty()) {
            return
        }

        val previousSequence = cachedSequence
        val previousCurrentFrame = previousSequence.getOrNull(currentStepIndex.coerceIn(0, previousSequence.lastIndex.coerceAtLeast(0)))
        val previousNextFrame = previousSequence.getOrNull(nextStepIndex.coerceIn(0, previousSequence.lastIndex.coerceAtLeast(0)))

        cachedSequence = buildSequence(context)
        // cachedCameraShots = buildCameraShots(context, cachedSequence.size) // Temporarily comment out to resolve error
        cachedSignature = signature

        if (cachedSequence.isEmpty()) {
            currentStepIndex = 0
            nextStepIndex = 0
            return
        }

        // Try to preserve continuity by restoring to the same frame index if possible.
        // If exact frames aren't present in the new sequence, pick the closest available frames
        // to avoid abrupt jumps when sequences change.
        currentStepIndex = cachedSequence.indexOf(previousCurrentFrame).takeIf { it >= 0 }
            ?: findClosestIndex(cachedSequence, previousCurrentFrame) ?: 0

        nextStepIndex = cachedSequence.indexOf(previousNextFrame).takeIf { it >= 0 && it != currentStepIndex }
            ?: findClosestIndex(cachedSequence, previousNextFrame)
            ?: if (cachedSequence.size > 1) {
                (currentStepIndex + 1) % cachedSequence.size
            } else {
                currentStepIndex
            }
    }

    fun stepCount(context: SpriteChoreographyContext): Int {
        refresh(context)
        return cachedSequence.size
    }

    fun advance(context: SpriteChoreographyContext, steps: Int = 1) {
        refresh(context)
        if (cachedSequence.isEmpty()) return

        repeat(steps.coerceAtLeast(1)) {
            currentStepIndex = nextStepIndex.coerceIn(0, cachedSequence.lastIndex)
            nextStepIndex = if (cachedSequence.size > 1) {
                (currentStepIndex + 1) % cachedSequence.size
            } else {
                currentStepIndex
            }
        }
    }

    fun selection(context: SpriteChoreographyContext): SpriteFrameSelection? {
        refresh(context)
        if (context.frameCount <= 0 || cachedSequence.isEmpty()) return null

        val current = cachedSequence[currentStepIndex.coerceIn(0, cachedSequence.lastIndex)]
            .coerceIn(0, context.frameCount - 1)
        val next = cachedSequence.getOrNull(nextStepIndex.coerceIn(0, cachedSequence.lastIndex))
            ?.coerceIn(0, context.frameCount - 1)
        return SpriteFrameSelection(
            currentFrameIndex = current,
            nextFrameIndex = next,
            transitionRecommended = next != null && next != current,
        )
    }

    private fun sequenceSignature(context: SpriteChoreographyContext): String {
        if (context.frameCount <= 1) return "static|${context.frameCount}"
        val snapshot = selectionSnapshot(context)
        return listOf(
            context.frameCount,
            context.danceStyle.name,
            snapshot.playbackState.name,
            snapshot.section.name,
            snapshot.phraseBeat,
            snapshot.energyBand,
            snapshot.accentBand,
            if (snapshot.songTransition) 1 else 0,
        ).joinToString("|")
    }

    private fun buildSequence(context: SpriteChoreographyContext): List<Int> {
        if (context.frameCount <= 0) return emptyList()
        if (context.frameCount == 1) return listOf(0)

        val snapshot = selectionSnapshot(context)
        val profile = spriteChoreoProfile(context.danceStyle, snapshot.section)

        return when (snapshot.playbackState) {
            PlaybackDanceState.PAUSED -> compactResolvedSequence(
                resolveSemanticPool(profile.anchorSemantics, context.frameCount) +
                        resolveSemanticPool(profile.grooveSemantics, context.frameCount).take(1) +
                        resolveSemanticPool(profile.anchorSemantics, context.frameCount).take(1),
                context.frameCount,
            )
            PlaybackDanceState.STOPPED -> compactResolvedSequence(
                resolveSemanticPool(profile.anchorSemantics, context.frameCount).take(1) +
                        resolveSemanticPool(profile.recoverySemantics, context.frameCount).take(1) +
                        resolveSemanticPool(profile.anchorSemantics, context.frameCount).take(1),
                context.frameCount,
            )
            PlaybackDanceState.IDLE -> compactResolvedSequence(
                resolveSemanticPool(profile.anchorSemantics, context.frameCount).take(1),
                context.frameCount,
            )
            PlaybackDanceState.PLAYING -> buildWeightedSequenceWithCamera(profile, snapshot, context.frameCount, context)
        }
    }

    // 扩展：生成动作序列的同时生成镜头切换点
    private fun buildWeightedSequenceWithCamera(
        profile: SemanticChoreoProfile,
        snapshot: SpriteSelectionSnapshot,
        frameCount: Int,
        context: SpriteChoreographyContext,
    ): List<Int> {
        val totalSteps = profile.phraseLength + snapshot.energyBand + if (snapshot.songTransition) 1 else 0
        val sequence = mutableListOf<Int>()
        var lastFrame = selectFrame(
            pool = if (snapshot.songTransition) resolveSemanticPool(profile.accentSemantics, frameCount) else resolveSemanticPool(profile.anchorSemantics, frameCount),
            fallbackPool = resolveSemanticPool(profile.anchorSemantics, frameCount),
            snapshot = snapshot,
            step = 0,
            lastFrame = null,
            frameCount = frameCount,
        )
        sequence += lastFrame
        for (step in 0 until totalSteps) {
            val beatSlot = (snapshot.phraseBeat + step) % 8
            val role = spriteRoleForBeat(beatSlot, step, totalSteps, snapshot)
            val rolePool = resolveRolePool(profile, role, frameCount)
            val selectedFrame = selectFrame(
                pool = rolePool,
                fallbackPool = resolveSemanticPool(profile.grooveSemantics + profile.anchorSemantics, frameCount),
                snapshot = snapshot,
                step = step,
                lastFrame = lastFrame,
                frameCount = frameCount,
            )
            appendFrameWithBridge(sequence, lastFrame, selectedFrame, profile, snapshot, frameCount)
            lastFrame = selectedFrame
        }
        appendFrameWithBridge(
            sequence = sequence,
            previousFrame = lastFrame,
            targetFrame = selectFrame(
                pool = resolveSemanticPool(profile.recoverySemantics, frameCount),
                fallbackPool = resolveSemanticPool(profile.anchorSemantics, frameCount),
                snapshot = snapshot,
                step = totalSteps + 1,
                lastFrame = lastFrame,
                frameCount = frameCount,
            ),
            profile = profile,
            snapshot = snapshot,
            frameCount = frameCount,
        )
        return compactResolvedSequence(sequence, frameCount)
    }

    private fun selectionSnapshot(context: SpriteChoreographyContext): SpriteSelectionSnapshot {
        return SpriteSelectionSnapshot(
            frameCount = context.frameCount,
            playbackState = context.playbackState,
            section = context.section,
            phraseBeat = context.phraseBeat,
            energyBand = energyBand(context),
            accentBand = accentBand(context),
            songTransition = context.songChangeBoost > 0.28f,
        )
    }

    private fun energyBand(context: SpriteChoreographyContext): Int {
        val combinedEnergy = context.audioLevel * 0.52f + context.beatBoost * 0.3f + context.recentBeatStrength * 0.18f + context.songChangeBoost * 0.38f
        return when {
            combinedEnergy >= 0.84f -> 2
            combinedEnergy >= 0.48f -> 1
            else -> 0
        }
    }

    private fun accentBand(context: SpriteChoreographyContext): Int {
        val phraseAccent = when (context.phraseBeat) {
            0, 4 -> 1.16f
            2, 6 -> 1.06f
            else -> 0.96f
        }
        val sectionAccent = when (context.section) {
            DanceSection.VERSE -> phraseAccent * 0.94f
            DanceSection.PRE_CHORUS -> phraseAccent
            DanceSection.CHORUS -> phraseAccent * 1.08f
        }
        val accent = context.recentBeatStrength * 0.8f + context.songChangeBoost * 0.35f + (sectionAccent - 1f) * 0.45f
        return when {
            accent >= 0.9f -> 2
            accent >= 0.58f -> 1
            else -> 0
        }
    }

    private fun spriteChoreoProfile(style: DanceStyle, section: DanceSection): SemanticChoreoProfile {
        return when (style) {
            DanceStyle.CHILL -> when (section) {
                DanceSection.VERSE -> SemanticChoreoProfile(
                    anchorSemantics = listOf(SpritePoseSemantic.IDLE, SpritePoseSemantic.GROOVE_LEFT),
                    grooveSemantics = listOf(SpritePoseSemantic.GROOVE_LEFT, SpritePoseSemantic.GROOVE_RIGHT),
                    travelSemantics = listOf(SpritePoseSemantic.TRAVEL_LEFT, SpritePoseSemantic.TRAVEL_RIGHT),
                    accentSemantics = listOf(SpritePoseSemantic.ACCENT_OPEN, SpritePoseSemantic.ACCENT_CROSS),
                    peakSemantics = listOf(SpritePoseSemantic.ACCENT_CROSS, SpritePoseSemantic.PEAK),
                    recoverySemantics = listOf(SpritePoseSemantic.TRAVEL_LEFT, SpritePoseSemantic.GROOVE_RIGHT, SpritePoseSemantic.GROOVE_LEFT, SpritePoseSemantic.IDLE),
                    maxJump = 2,
                    phraseLength = 8,
                )
                DanceSection.PRE_CHORUS -> SemanticChoreoProfile(
                    anchorSemantics = listOf(SpritePoseSemantic.IDLE, SpritePoseSemantic.GROOVE_LEFT, SpritePoseSemantic.GROOVE_RIGHT),
                    grooveSemantics = listOf(SpritePoseSemantic.GROOVE_LEFT, SpritePoseSemantic.GROOVE_RIGHT, SpritePoseSemantic.TRAVEL_LEFT),
                    travelSemantics = listOf(SpritePoseSemantic.TRAVEL_LEFT, SpritePoseSemantic.TRAVEL_RIGHT),
                    accentSemantics = listOf(SpritePoseSemantic.ACCENT_OPEN, SpritePoseSemantic.ACCENT_CROSS),
                    peakSemantics = listOf(SpritePoseSemantic.PEAK, SpritePoseSemantic.ACCENT_CROSS),
                    recoverySemantics = listOf(SpritePoseSemantic.TRAVEL_LEFT, SpritePoseSemantic.GROOVE_RIGHT, SpritePoseSemantic.GROOVE_LEFT),
                    maxJump = 3,
                    phraseLength = 10,
                )
                DanceSection.CHORUS -> SemanticChoreoProfile(
                    anchorSemantics = listOf(SpritePoseSemantic.GROOVE_LEFT, SpritePoseSemantic.GROOVE_RIGHT),
                    grooveSemantics = listOf(SpritePoseSemantic.TRAVEL_LEFT, SpritePoseSemantic.TRAVEL_RIGHT, SpritePoseSemantic.ACCENT_OPEN),
                    travelSemantics = listOf(SpritePoseSemantic.TRAVEL_LEFT, SpritePoseSemantic.TRAVEL_RIGHT, SpritePoseSemantic.ACCENT_OPEN),
                    accentSemantics = listOf(SpritePoseSemantic.ACCENT_OPEN, SpritePoseSemantic.ACCENT_CROSS),
                    peakSemantics = listOf(SpritePoseSemantic.PEAK, SpritePoseSemantic.ACCENT_CROSS),
                    recoverySemantics = listOf(SpritePoseSemantic.ACCENT_OPEN, SpritePoseSemantic.TRAVEL_LEFT, SpritePoseSemantic.GROOVE_RIGHT),
                    maxJump = 3,
                    phraseLength = 12,
                )
            }

            DanceStyle.GROOVE -> when (section) {
                DanceSection.VERSE -> SemanticChoreoProfile(
                    anchorSemantics = listOf(SpritePoseSemantic.GROOVE_LEFT, SpritePoseSemantic.GROOVE_RIGHT),
                    grooveSemantics = listOf(SpritePoseSemantic.GROOVE_RIGHT, SpritePoseSemantic.TRAVEL_LEFT, SpritePoseSemantic.TRAVEL_RIGHT),
                    travelSemantics = listOf(SpritePoseSemantic.TRAVEL_LEFT, SpritePoseSemantic.TRAVEL_RIGHT),
                    accentSemantics = listOf(SpritePoseSemantic.ACCENT_OPEN, SpritePoseSemantic.ACCENT_CROSS),
                    peakSemantics = listOf(SpritePoseSemantic.ACCENT_CROSS, SpritePoseSemantic.PEAK),
                    recoverySemantics = listOf(SpritePoseSemantic.TRAVEL_LEFT, SpritePoseSemantic.GROOVE_RIGHT, SpritePoseSemantic.GROOVE_LEFT),
                    maxJump = 3,
                    phraseLength = 10,
                )
                DanceSection.PRE_CHORUS -> SemanticChoreoProfile(
                    anchorSemantics = listOf(SpritePoseSemantic.GROOVE_LEFT, SpritePoseSemantic.GROOVE_RIGHT, SpritePoseSemantic.TRAVEL_LEFT),
                    grooveSemantics = listOf(SpritePoseSemantic.TRAVEL_LEFT, SpritePoseSemantic.TRAVEL_RIGHT, SpritePoseSemantic.ACCENT_OPEN),
                    travelSemantics = listOf(SpritePoseSemantic.TRAVEL_RIGHT, SpritePoseSemantic.ACCENT_OPEN),
                    accentSemantics = listOf(SpritePoseSemantic.ACCENT_OPEN, SpritePoseSemantic.ACCENT_CROSS),
                    peakSemantics = listOf(SpritePoseSemantic.PEAK, SpritePoseSemantic.ACCENT_CROSS),
                    recoverySemantics = listOf(SpritePoseSemantic.ACCENT_OPEN, SpritePoseSemantic.TRAVEL_LEFT, SpritePoseSemantic.GROOVE_RIGHT),
                    maxJump = 3,
                    phraseLength = 11,
                )
                DanceSection.CHORUS -> SemanticChoreoProfile(
                    anchorSemantics = listOf(SpritePoseSemantic.GROOVE_RIGHT, SpritePoseSemantic.TRAVEL_LEFT),
                    grooveSemantics = listOf(SpritePoseSemantic.TRAVEL_LEFT, SpritePoseSemantic.TRAVEL_RIGHT, SpritePoseSemantic.ACCENT_OPEN),
                    travelSemantics = listOf(SpritePoseSemantic.TRAVEL_RIGHT, SpritePoseSemantic.ACCENT_OPEN, SpritePoseSemantic.ACCENT_CROSS),
                    accentSemantics = listOf(SpritePoseSemantic.ACCENT_OPEN, SpritePoseSemantic.ACCENT_CROSS, SpritePoseSemantic.PEAK),
                    peakSemantics = listOf(SpritePoseSemantic.PEAK, SpritePoseSemantic.ACCENT_CROSS),
                    recoverySemantics = listOf(SpritePoseSemantic.ACCENT_OPEN, SpritePoseSemantic.TRAVEL_LEFT, SpritePoseSemantic.GROOVE_RIGHT),
                    maxJump = 4,
                    phraseLength = 12,
                )
            }

            DanceStyle.POWER -> when (section) {
                DanceSection.VERSE -> SemanticChoreoProfile(
                    anchorSemantics = listOf(SpritePoseSemantic.GROOVE_RIGHT, SpritePoseSemantic.TRAVEL_LEFT),
                    grooveSemantics = listOf(SpritePoseSemantic.TRAVEL_LEFT, SpritePoseSemantic.TRAVEL_RIGHT),
                    travelSemantics = listOf(SpritePoseSemantic.TRAVEL_RIGHT, SpritePoseSemantic.ACCENT_OPEN),
                    accentSemantics = listOf(SpritePoseSemantic.ACCENT_OPEN, SpritePoseSemantic.ACCENT_CROSS),
                    peakSemantics = listOf(SpritePoseSemantic.ACCENT_CROSS, SpritePoseSemantic.PEAK),
                    recoverySemantics = listOf(SpritePoseSemantic.ACCENT_OPEN, SpritePoseSemantic.TRAVEL_LEFT, SpritePoseSemantic.GROOVE_RIGHT),
                    maxJump = 4,
                    phraseLength = 10,
                )
                DanceSection.PRE_CHORUS -> SemanticChoreoProfile(
                    anchorSemantics = listOf(SpritePoseSemantic.TRAVEL_LEFT, SpritePoseSemantic.TRAVEL_RIGHT),
                    grooveSemantics = listOf(SpritePoseSemantic.TRAVEL_RIGHT, SpritePoseSemantic.ACCENT_OPEN, SpritePoseSemantic.ACCENT_CROSS),
                    travelSemantics = listOf(SpritePoseSemantic.ACCENT_OPEN, SpritePoseSemantic.ACCENT_CROSS),
                    accentSemantics = listOf(SpritePoseSemantic.ACCENT_CROSS, SpritePoseSemantic.PEAK),
                    peakSemantics = listOf(SpritePoseSemantic.PEAK, SpritePoseSemantic.ACCENT_CROSS),
                    recoverySemantics = listOf(SpritePoseSemantic.ACCENT_OPEN, SpritePoseSemantic.TRAVEL_RIGHT, SpritePoseSemantic.TRAVEL_LEFT),
                    maxJump = 4,
                    phraseLength = 11,
                )
                DanceSection.CHORUS -> SemanticChoreoProfile(
                    anchorSemantics = listOf(SpritePoseSemantic.TRAVEL_LEFT, SpritePoseSemantic.TRAVEL_RIGHT),
                    grooveSemantics = listOf(SpritePoseSemantic.ACCENT_OPEN, SpritePoseSemantic.ACCENT_CROSS),
                    travelSemantics = listOf(SpritePoseSemantic.ACCENT_OPEN, SpritePoseSemantic.ACCENT_CROSS),
                    accentSemantics = listOf(SpritePoseSemantic.ACCENT_CROSS, SpritePoseSemantic.PEAK),
                    peakSemantics = listOf(SpritePoseSemantic.PEAK, SpritePoseSemantic.ACCENT_CROSS),
                    recoverySemantics = listOf(SpritePoseSemantic.ACCENT_OPEN, SpritePoseSemantic.TRAVEL_RIGHT, SpritePoseSemantic.TRAVEL_LEFT),
                    maxJump = 5,
                    phraseLength = 12,
                )
            }
        }
    }

    private fun spriteRoleForBeat(
        beatSlot: Int,
        step: Int,
        totalSteps: Int,
        snapshot: SpriteSelectionSnapshot,
    ): SpriteFrameRole {
        if (step == totalSteps - 1) return SpriteFrameRole.RECOVERY
        if (snapshot.songTransition && step <= 1) return SpriteFrameRole.PEAK

        return when (beatSlot) {
            0 -> if (snapshot.energyBand >= 1 || snapshot.accentBand >= 1) SpriteFrameRole.ACCENT else SpriteFrameRole.GROOVE
            1 -> if (snapshot.section == DanceSection.CHORUS) SpriteFrameRole.TRAVEL else SpriteFrameRole.GROOVE
            2 -> if (snapshot.energyBand >= 1) SpriteFrameRole.TRAVEL else SpriteFrameRole.GROOVE
            3 -> if (snapshot.accentBand >= 1) SpriteFrameRole.ACCENT else SpriteFrameRole.ANCHOR
            4 -> if (snapshot.energyBand >= 2 || snapshot.accentBand >= 2) SpriteFrameRole.PEAK else SpriteFrameRole.ACCENT
            5 -> if (snapshot.section == DanceSection.CHORUS || snapshot.energyBand >= 1) SpriteFrameRole.TRAVEL else SpriteFrameRole.GROOVE
            6 -> if (snapshot.accentBand >= 1) SpriteFrameRole.ACCENT else SpriteFrameRole.RECOVERY
            else -> SpriteFrameRole.ANCHOR
        }
    }

    private fun resolveRolePool(
        profile: SemanticChoreoProfile,
        role: SpriteFrameRole,
        frameCount: Int,
    ): List<Int> {
        return when (role) {
            SpriteFrameRole.ANCHOR -> resolveSemanticPool(profile.anchorSemantics, frameCount)
                .ifEmpty { resolveSemanticPool(listOf(SpritePoseSemantic.IDLE), frameCount) }
            SpriteFrameRole.GROOVE -> resolveSemanticPool(profile.grooveSemantics, frameCount)
                .ifEmpty { resolveRolePool(profile, SpriteFrameRole.ANCHOR, frameCount) }
            SpriteFrameRole.TRAVEL -> resolveSemanticPool(profile.travelSemantics, frameCount)
                .ifEmpty { resolveRolePool(profile, SpriteFrameRole.GROOVE, frameCount) }
            SpriteFrameRole.ACCENT -> resolveSemanticPool(profile.accentSemantics, frameCount)
                .ifEmpty { resolveRolePool(profile, SpriteFrameRole.TRAVEL, frameCount) }
            SpriteFrameRole.PEAK -> resolveSemanticPool(profile.peakSemantics, frameCount)
                .ifEmpty { resolveRolePool(profile, SpriteFrameRole.ACCENT, frameCount) }
            SpriteFrameRole.RECOVERY -> resolveSemanticPool(profile.recoverySemantics, frameCount)
                .ifEmpty { resolveRolePool(profile, SpriteFrameRole.GROOVE, frameCount) }
        }
    }

    // Return index of element in list that is closest (by absolute difference) to target.
    // If list is empty or target is null, return null.
    private fun findClosestIndex(list: List<Int>, target: Int?): Int? {
        if (target == null || list.isEmpty()) return null
        var bestIndex = 0
        var bestDist = Int.MAX_VALUE
        for ((i, v) in list.withIndex()) {
            val d = abs(v - target)
            if (d < bestDist) {
                bestDist = d
                bestIndex = i
            }
            if (bestDist == 0) break
        }
        return bestIndex
    }

    private fun resolveSemanticPool(
        semantics: List<SpritePoseSemantic>,
        frameCount: Int,
    ): List<Int> {
        return semantics
            .flatMap { semantic -> semanticFrameSlots(semantic) }
            .map { normalizeRawFrameIndex(it, frameCount) }
            .distinct()
    }

    private fun semanticFrameSlots(semantic: SpritePoseSemantic): List<Int> {
        // Map each high-level semantic to one or more raw slot indices within
        // 0..(maxFrameCount-1). These raw slots are later scaled to the
        // actual `frameCount` passed in the runtime context, so these values
        // represent semantic positions across the 26-frame sprite sheet.
        return when (semantic) {
            SpritePoseSemantic.IDLE -> listOf(0, 1, 2)
            SpritePoseSemantic.GROOVE_LEFT -> listOf(3, 4, 5)
            SpritePoseSemantic.GROOVE_RIGHT -> listOf(6, 7, 8)
            SpritePoseSemantic.TRAVEL_LEFT -> listOf(9, 10, 11)
            SpritePoseSemantic.TRAVEL_RIGHT -> listOf(12, 13, 14)
            SpritePoseSemantic.ACCENT_OPEN -> listOf(15, 16, 17)
            SpritePoseSemantic.ACCENT_CROSS -> listOf(18, 19, 20)
            SpritePoseSemantic.PEAK -> listOf(21, 22, 23)
            SpritePoseSemantic.RECOVERY -> listOf(24, 25, 23, 22, 21)
        }
    }

    private fun selectFrame(
        pool: List<Int>,
        fallbackPool: List<Int>,
        snapshot: SpriteSelectionSnapshot,
        step: Int,
        lastFrame: Int?,
        frameCount: Int,
    ): Int {
        // Build initial normalized pool (distinct) and expand when too narrow.
        val basePool = pool.distinct().ifEmpty { fallbackPool.distinct() }.ifEmpty { listOf(0) }
        val normalizedPool = basePool.toMutableList()

        // If the semantic pool collapses to a single candidate but we have multiple
        // frames in the sprite sheet, expand to neighboring frames so the avatar
        // can vary instead of getting stuck on one image.
        val expandedPool = if (normalizedPool.size == 1 && frameCount > 1) {
            val center = normalizedPool.first()
            val candidates = mutableListOf<Int>()
            candidates += center
            // add up to two neighbors on each side (bounded by frameCount)
            for (offset in 1 until minOf(3, frameCount)) {
                candidates += (center - offset).coerceIn(0, frameCount - 1)
                candidates += (center + offset).coerceIn(0, frameCount - 1)
            }
            candidates.distinct()
        } else {
            normalizedPool.distinct()
        }

        val poolSize = expandedPool.size
        val baseIndex = if (poolSize > 0) (snapshot.phraseBeat + step + snapshot.energyBand + snapshot.accentBand) % poolSize else 0
        var selected = if (poolSize > 0) expandedPool[baseIndex] else 0

        // If we would pick the same as the last frame, prefer a candidate that
        // is farther away to avoid rapid toggling between very close frames.
        if (lastFrame != null && poolSize > 1 && selected == lastFrame) {
            var bestCandidate = selected
            var bestDist = -1
            for (candidate in expandedPool) {
                if (candidate == lastFrame) continue
                val d = abs(candidate - lastFrame)
                if (d > bestDist) {
                    bestDist = d
                    bestCandidate = candidate
                }
            }
            selected = bestCandidate
        }

        return selected
    }

    private fun appendFrameWithBridge(
        sequence: MutableList<Int>,
        previousFrame: Int,
        targetFrame: Int,
        profile: SemanticChoreoProfile,
        snapshot: SpriteSelectionSnapshot,
        frameCount: Int,
    ) {
        val delta = targetFrame - previousFrame
        val allowWideJump = snapshot.songTransition || snapshot.accentBand >= 2 || snapshot.section == DanceSection.CHORUS

        // If jump is large and wide jumps aren't allowed, insert stepwise bridge frames
        // between previousFrame and targetFrame up to profile.maxJump per hop to smooth movement.
        if (!allowWideJump && abs(delta) > 1) {
            var cursor = previousFrame
            while (cursor != targetFrame) {
                val remaining = targetFrame - cursor
                val magnitude = minOf(profile.maxJump, abs(remaining))
                val hop = if (remaining >= 0) magnitude else -magnitude
                val next = (cursor + hop).coerceIn(0, frameCount - 1)
                if (sequence.lastOrNull() != next) sequence += next
                cursor = next
                if (hop == 0) break
            }
        } else if (abs(delta) >= 2 && snapshot.energyBand == 0) {
            // If low energy but moderate jump, add a single bridge step to soften the transition
            val bridge = previousFrame + delta.signStep()
            if (bridge != targetFrame && sequence.lastOrNull() != bridge) {
                sequence += bridge.coerceIn(0, frameCount - 1)
            }
            if (sequence.lastOrNull() != targetFrame) {
                sequence += targetFrame
            }
        } else {
            if (sequence.lastOrNull() != targetFrame) {
                sequence += targetFrame
            }
        }
    }


    private fun normalizeRawFrameIndex(rawFrame: Int, frameCount: Int): Int {
        if (frameCount <= 1) return 0
        val clamped = rawFrame.coerceIn(0, maxFrameCount - 1)
        val scaled = (clamped / (maxFrameCount - 1).toFloat()) * (frameCount - 1)
        return scaled.roundToInt().coerceIn(0, frameCount - 1)
    }

    private fun compactResolvedSequence(
        sequence: List<Int>,
        frameCount: Int,
    ): List<Int> {
        if (frameCount <= 0) return emptyList()

        val compact = sequence
            .map { it.coerceIn(0, frameCount - 1) }
            .fold(mutableListOf<Int>()) { acc, value ->
                if (acc.lastOrNull() != value) {
                    acc += value
                }
                acc
            }

        if (compact.isEmpty()) return listOf(0)
        // 如果最终序列长度小于 frameCount 的一半，直接 fallback 为顺序遍历所有帧，保证动画连贯
        if (compact.size < (frameCount / 2)) {
            return List(frameCount) { it }
        }
        return if (compact.size == 1 && frameCount > 1) {
            val first = compact.first()
            val span = maxOf(1, (frameCount - 1) / 3)
            val a = first
            val b = (first + span) % frameCount
            val c = (first + 2 * span) % frameCount
            val list = listOf(a, b, c).distinct()
            if (list.size == 1) listOf(a, (a + 1) % frameCount) else list
        } else {
            compact
        }
    }

    private fun Int.signStep(): Int {
        return when {
            this > 0 -> 1
            this < 0 -> -1
            else -> 0
        }
    }
}
