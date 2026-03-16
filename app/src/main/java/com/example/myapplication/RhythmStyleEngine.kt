package com.example.myapplication

/**
 * 保持舞蹈风格切换的稳定性，使风格变化更跟随节奏趋势，
 * 而不是单次噪声节拍的突变。
 */
class RhythmStyleEngine {

    private var smoothedBpm = 0f
    private var smoothedStrength = 0f
    private var pendingStyle: DanceStyle? = null
    private var pendingCount = 0
    private var lastSwitchTimestampMs = 0L

    fun resolveStyle(
        event: BeatEvent,
        currentStyle: DanceStyle,
        preferredSongStyle: DanceStyle? = null,
    ): DanceStyle {
        if (event.bpm > 0f) {
            smoothedBpm = if (smoothedBpm <= 0f) event.bpm else (smoothedBpm * 0.78f + event.bpm * 0.22f)
        }
        smoothedStrength = if (smoothedStrength <= 0f) {
            event.strength
        } else {
            smoothedStrength * 0.72f + event.strength * 0.28f
        }

        val rhythmTarget = selectTargetStyle(smoothedBpm, smoothedStrength, currentStyle)
        val resolvedTarget = biasWithSongStyle(rhythmTarget, preferredSongStyle)
        val target = limitDowngradeStep(currentStyle, resolvedTarget)
        if (target == currentStyle) {
            pendingStyle = null
            pendingCount = 0
            return currentStyle
        }

        if (pendingStyle == target) {
            pendingCount += 1
        } else {
            pendingStyle = target
            pendingCount = 1
        }

        val direction = switchDirection(currentStyle, target)
        val cooldownPassed = (event.timestampMs - lastSwitchTimestampMs) >= cooldownMsFor(direction)
        val confirmed = pendingCount >= confirmationCountFor(direction)
        if (!cooldownPassed || !confirmed) {
            return currentStyle
        }

        pendingStyle = null
        pendingCount = 0
        lastSwitchTimestampMs = event.timestampMs
        return target
    }

    private fun biasWithSongStyle(target: DanceStyle, preferredSongStyle: DanceStyle?): DanceStyle {
        val preferred = preferredSongStyle ?: return target
        if (preferred == target) return target

        val strength = smoothedStrength.coerceIn(0f, 1f)
        val targetRank = styleRank(target)
        val preferredRank = styleRank(preferred)

        return when {
            // 当节拍信号非常强时，保持即时的节拍反应
            strength >= 0.78f -> target
            // 当节奏信号较弱时，遵循歌曲偏好风格
            strength <= 0.50f -> preferred
            // 中等强度信号时，混合成一个中间风格
            else -> styleForRank((targetRank + preferredRank) / 2)
        }
    }

    private fun selectTargetStyle(bpm: Float, strength: Float, currentStyle: DanceStyle): DanceStyle {
        return when {
            qualifiesPower(bpm, strength, currentStyle) -> DanceStyle.POWER
            qualifiesGroove(bpm, strength, currentStyle) -> DanceStyle.GROOVE
            else -> DanceStyle.CHILL
        }
    }

    private fun qualifiesPower(bpm: Float, strength: Float, currentStyle: DanceStyle): Boolean {
        val fromLowerStyle = currentStyle != DanceStyle.POWER
        val bpmThreshold = if (fromLowerStyle) POWER_ENTER_BPM else POWER_HOLD_BPM
        val strengthThreshold = if (fromLowerStyle) POWER_ENTER_STRENGTH else POWER_HOLD_STRENGTH
        return bpm >= bpmThreshold || strength >= strengthThreshold
    }

    private fun qualifiesGroove(bpm: Float, strength: Float, currentStyle: DanceStyle): Boolean {
        val fromLowerStyle = currentStyle == DanceStyle.CHILL
        val bpmThreshold = if (fromLowerStyle) GROOVE_ENTER_BPM else GROOVE_HOLD_BPM
        val strengthThreshold = if (fromLowerStyle) GROOVE_ENTER_STRENGTH else GROOVE_HOLD_STRENGTH
        return bpm >= bpmThreshold || strength >= strengthThreshold
    }

    private fun switchDirection(current: DanceStyle, target: DanceStyle): Direction {
        val currentRank = styleRank(current)
        val targetRank = styleRank(target)
        return if (targetRank > currentRank) Direction.UP else Direction.DOWN
    }

    private fun styleRank(style: DanceStyle): Int {
        return when (style) {
            DanceStyle.CHILL -> 0
            DanceStyle.GROOVE -> 1
            DanceStyle.POWER -> 2
        }
    }

    private fun styleForRank(rank: Int): DanceStyle {
        return when (rank.coerceIn(0, 2)) {
            0 -> DanceStyle.CHILL
            1 -> DanceStyle.GROOVE
            else -> DanceStyle.POWER
        }
    }

    private fun limitDowngradeStep(current: DanceStyle, target: DanceStyle): DanceStyle {
        val currentRank = styleRank(current)
        val targetRank = styleRank(target)
        return if (targetRank < currentRank - 1) {
            styleForRank(currentRank - 1)
        } else {
            target
        }
    }

    private fun confirmationCountFor(direction: Direction): Int {
        return when (direction) {
            Direction.UP -> UPGRADE_CONFIRMATION_COUNT
            Direction.DOWN -> DOWNGRADE_CONFIRMATION_COUNT
        }
    }

    private fun cooldownMsFor(direction: Direction): Long {
        return when (direction) {
            Direction.UP -> UPGRADE_COOLDOWN_MS
            Direction.DOWN -> DOWNGRADE_COOLDOWN_MS
        }
    }

    private enum class Direction {
        UP,
        DOWN,
    }

    companion object {
        private const val POWER_ENTER_BPM = 142f
        private const val POWER_HOLD_BPM = 132f
        private const val POWER_ENTER_STRENGTH = 0.90f
        private const val POWER_HOLD_STRENGTH = 0.78f

        private const val GROOVE_ENTER_BPM = 108f
        private const val GROOVE_HOLD_BPM = 98f
        private const val GROOVE_ENTER_STRENGTH = 0.56f
        private const val GROOVE_HOLD_STRENGTH = 0.42f

        private const val UPGRADE_CONFIRMATION_COUNT = 3
        private const val DOWNGRADE_CONFIRMATION_COUNT = 2
        private const val UPGRADE_COOLDOWN_MS = 1_800L
        private const val DOWNGRADE_COOLDOWN_MS = 1_200L
    }
}

