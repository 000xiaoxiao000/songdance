package com.example.myapplication

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Process
import androidx.core.content.ContextCompat
import kotlin.math.sqrt

class AudioCaptureManager(
    private val context: Context,
    private val listener: Listener,
) {

    enum class CaptureMode(val displayName: String) {
        PLAYBACK("系统音频"),
        NONE("未检测到音频"),
    }

    interface Listener {
        fun onCaptureModeChanged(mode: CaptureMode)
        fun onAudioLevel(level: Float)
        fun onBeat(event: BeatEvent)
    }

    private val detector = BeatDetector()
    private val settingsRepository = OverlaySettingsRepository(context)

    @Volatile
    private var running = false
    private var readThread: Thread? = null
    private var audioRecord: AudioRecord? = null
    private var mediaProjection: MediaProjection? = null
    private var levelFloor = 0.001f
    private var levelPeak = 0.08f
    private var smoothedLevel = 0f

    fun start(resultCode: Int?, projectionData: Intent?) {
        stop()

        val playbackRecord = buildPlaybackRecord(resultCode, projectionData)
        val record = playbackRecord?.takeIf {
            it.state == AudioRecord.STATE_INITIALIZED
        }

        if (record == null) {
            playbackRecord?.release()
            listener.onCaptureModeChanged(CaptureMode.NONE)
            return
        }

        audioRecord = record
        listener.onCaptureModeChanged(CaptureMode.PLAYBACK)
        resetLevelState()

        running = true
        readThread = Thread({ captureLoop(record) }, "audio-capture-loop").apply { start() }
    }

    fun stop() {
        running = false
        readThread?.interrupt()
        readThread = null

        audioRecord?.let {
            kotlin.runCatching { it.stop() }
            kotlin.runCatching { it.release() }
        }
        audioRecord = null

        mediaProjection?.stop()
        mediaProjection = null
    }

    private fun captureLoop(record: AudioRecord) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val shortBuffer = ShortArray(2_048)

        kotlin.runCatching { record.startRecording() }
            .onFailure {
                listener.onCaptureModeChanged(CaptureMode.NONE)
                return
            }

        while (running && !Thread.currentThread().isInterrupted) {
            val read = record.read(shortBuffer, 0, shortBuffer.size, AudioRecord.READ_BLOCKING)
            if (read <= 0) continue

            val rms = calculateRms(shortBuffer, read)
            listener.onAudioLevel(normalizeLevel(rms))
            detector.pushSamples(
                samples = shortBuffer,
                length = read,
                timestampMs = System.currentTimeMillis(),
                sensitivity = settingsRepository.get().sensitivity
            )?.let(listener::onBeat)
        }
    }

    private fun buildPlaybackRecord(resultCode: Int?, projectionData: Intent?): AudioRecord? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || resultCode == null || projectionData == null) {
            return null
        }
        if (
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }

        return kotlin.runCatching {
            val projectionManager =
                context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, projectionData)

            val config = android.media.AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            val format = AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()

            val minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            try {
                AudioRecord.Builder()
                    .setAudioFormat(format)
                    .setBufferSizeInBytes((minBuffer * 2).coerceAtLeast(4_096))
                    .setAudioPlaybackCaptureConfig(config)
                    .build()
            } catch (_: SecurityException) {
                null
            }
        }.getOrNull()
    }

    private fun calculateRms(buffer: ShortArray, length: Int): Float {
        var sum = 0.0
        for (i in 0 until length) {
            val sample = buffer[i] / 32768.0
            sum += sample * sample
        }
        return sqrt(sum / length).toFloat().coerceIn(0f, 1f)
    }

    private fun resetLevelState() {
        levelFloor = 0.001f
        levelPeak = 0.08f
        smoothedLevel = 0f
    }

    private fun normalizeLevel(rms: Float): Float {
        val clamped = rms.coerceIn(0f, 1f)
        levelFloor = levelFloor * 0.992f + clamped * 0.008f
        if (clamped > levelPeak) {
            levelPeak = clamped
        } else {
            levelPeak *= 0.998f
        }
        if (levelPeak < levelFloor + 0.02f) {
            levelPeak = levelFloor + 0.02f
        }

        val normalized = ((clamped - levelFloor) / (levelPeak - levelFloor + 0.0001f))
            .coerceIn(0f, 1f)
        smoothedLevel = smoothedLevel * 0.84f + normalized * 0.16f
        return smoothedLevel
    }


    companion object {
        private const val SAMPLE_RATE = 44_100
    }
}


