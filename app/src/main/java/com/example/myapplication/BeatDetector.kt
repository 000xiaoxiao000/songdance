package com.example.myapplication

import kotlin.math.roundToInt
import kotlin.math.sqrt

data class BeatEvent(
    val strength: Float,
    val bpm: Float,
    val timestampMs: Long,
)

class BeatDetector {

    private val fftAnalyzer = FftAnalyzer()
    private var previousSpectrum = FloatArray(0)
    private val fluxHistory = ArrayDeque<Float>()
    private var emaLevel = 0f
    private var lastBeatTimestampMs = 0L
    private val intervalsMs = ArrayDeque<Long>()

    fun pushSamples(
        samples: ShortArray,
        length: Int,
        timestampMs: Long,
        sensitivity: Float,
    ): BeatEvent? {
        val safeSensitivity = sensitivity.coerceIn(0.6f, 2.0f)
        val rms = calculateRms(samples, length)
        if (rms < MIN_RMS_LEVEL) {
            return null
        }

        val spectrum = fftAnalyzer.analyze(samples, length)
        val broadFlux = calculateFlux(spectrum)
        val bassFlux = calculateBandFlux(spectrum, 3, 26)
        val flux = broadFlux * 0.56f + bassFlux * 0.44f
        previousSpectrum = spectrum

        fluxHistory.addLast(flux)
        if (fluxHistory.size > 43) {
            fluxHistory.removeFirst()
        }

        emaLevel = if (emaLevel == 0f) flux else (emaLevel * 0.88f + flux * 0.12f)
        val avgFlux = fluxHistory.average().toFloat()
        val threshold = (avgFlux * 1.34f + emaLevel * 0.22f) / safeSensitivity
        val triggerMargin = threshold * MIN_TRIGGER_MARGIN_RATIO
        val enoughGap = (timestampMs - lastBeatTimestampMs) >= 220L

        if (flux <= threshold + triggerMargin || !enoughGap) {
            return null
        }

        if (lastBeatTimestampMs > 0L) {
            val interval = timestampMs - lastBeatTimestampMs
            if (interval in 250L..2_000L && isReasonableInterval(interval)) {
                intervalsMs.addLast(interval)
                if (intervalsMs.size > 12) {
                    intervalsMs.removeFirst()
                }
            }
        }
        lastBeatTimestampMs = timestampMs

        val stableInterval = robustIntervalMs()
        val bpm = if (stableInterval > 0f) (60_000f / stableInterval).roundToInt().toFloat() else 0f

        val normalizedStrength = ((flux - threshold) / (threshold + 0.001f)).coerceIn(0f, 1f)
        return BeatEvent(normalizedStrength, bpm, timestampMs)
    }

    private fun isReasonableInterval(candidate: Long): Boolean {
        val stable = robustIntervalMs()
        if (stable <= 0f) return true
        val deltaRatio = kotlin.math.abs(candidate - stable) / stable
        return deltaRatio <= MAX_INTERVAL_DEVIATION_RATIO
    }

    private fun robustIntervalMs(): Float {
        if (intervalsMs.isEmpty()) return 0f
        val sorted = intervalsMs.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) / 2f
        } else {
            sorted[middle].toFloat()
        }
    }

    private fun calculateRms(samples: ShortArray, length: Int): Float {
        var sum = 0.0
        for (i in 0 until length) {
            val normalized = samples[i] / 32768.0
            sum += normalized * normalized
        }
        return sqrt(sum / length).toFloat()
    }

    private fun calculateFlux(spectrum: FloatArray): Float {
        if (previousSpectrum.isEmpty()) {
            return 0f
        }

        var flux = 0f
        val maxBin = minOf(spectrum.size, 120)
        for (i in 2 until maxBin) {
            val diff = spectrum[i] - previousSpectrum.getOrElse(i) { 0f }
            if (diff > 0f) {
                flux += diff
            }
        }
        return flux
    }

    private fun calculateBandFlux(spectrum: FloatArray, fromBin: Int, toBin: Int): Float {
        if (previousSpectrum.isEmpty()) return 0f
        val start = fromBin.coerceAtLeast(0)
        val endExclusive = toBin.coerceAtMost(spectrum.size)
        var flux = 0f
        for (i in start until endExclusive) {
            val diff = spectrum[i] - previousSpectrum.getOrElse(i) { 0f }
            if (diff > 0f) {
                flux += diff
            }
        }
        return flux
    }

    companion object {
        private const val MIN_RMS_LEVEL = 0.001f
        private const val MIN_TRIGGER_MARGIN_RATIO = 0.06f
        private const val MAX_INTERVAL_DEVIATION_RATIO = 0.42f
    }
}

