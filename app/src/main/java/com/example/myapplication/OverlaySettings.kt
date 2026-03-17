package com.example.myapplication

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import androidx.core.content.edit
import kotlin.math.roundToInt

data class OverlaySettings(
    val sensitivity: Float,
    val avatarScale: Float,
    val avatarAlpha: Float,
    val showModeText: Boolean,
    val lockPosition: Boolean,
    val autoStartOnBoot: Boolean,
    val useAvatarVariant1: Boolean = false,
    val avatarDir: String = AvatarAssets.DIR_AVATAR,
    val avatarVariantDir: String = AvatarAssets.DIR_AVATAR1,
    val avatarAnchorOffsetPercent: Float = 0f,
    val audioActivityThreshold: Float = 0.05f,
    val audioInactivityTimeoutMs: Int = 1500,
)

class OverlaySettingsRepository(context: Context) {

    private val prefs: SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(context)
    private val legacyPrefs: SharedPreferences =
        context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)

    fun get(): OverlaySettings {
        return OverlaySettings(
            sensitivity = readFloat(KEY_SENSITIVITY, LEGACY_KEY_SENSITIVITY, DEFAULT_SENSITIVITY, SENSITIVITY_PERCENT_RANGE)
                .coerceIn(0.6f, 2.0f),
            avatarScale = readFloat(KEY_SCALE, LEGACY_KEY_SCALE, DEFAULT_SCALE, SCALE_PERCENT_RANGE)
                .coerceIn(0.7f, 1.8f),
            avatarAlpha = readFloat(KEY_ALPHA, LEGACY_KEY_ALPHA, DEFAULT_ALPHA, ALPHA_PERCENT_RANGE)
                .coerceIn(0.35f, 1.0f),
            showModeText = readBoolean(KEY_SHOW_MODE_TEXT, LEGACY_KEY_SHOW_MODE_TEXT, true),
            lockPosition = readBoolean(KEY_LOCK_POSITION, LEGACY_KEY_LOCK_POSITION, false),
            autoStartOnBoot = readBoolean(KEY_AUTO_START_ON_BOOT, LEGACY_KEY_AUTO_START_ON_BOOT, false),
            avatarAnchorOffsetPercent = readFloat(KEY_AVATAR_ANCHOR_OFFSET, LEGACY_KEY_AVATAR_ANCHOR_OFFSET, 0f, AVATAR_ANCHOR_PERCENT_RANGE)
                .coerceIn(-50f, 50f),
            audioActivityThreshold = readFloat(KEY_AUDIO_THRESHOLD, LEGACY_KEY_AUDIO_THRESHOLD, DEFAULT_AUDIO_THRESHOLD, AUDIO_THRESHOLD_PERCENT_RANGE)
                .coerceIn(0.01f, 1.0f),
            audioInactivityTimeoutMs = readInt(KEY_AUDIO_INACTIVITY_TIMEOUT, LEGACY_KEY_AUDIO_INACTIVITY_TIMEOUT, DEFAULT_AUDIO_INACTIVITY_TIMEOUT_MS),
            useAvatarVariant1 = prefs.getBoolean(KEY_USE_AVATAR_VARIANT_1, false),
            avatarDir = readString(KEY_AVATAR_DIR, "", DEFAULT_AVATAR_DIR),
            avatarVariantDir = readString(KEY_AVATAR_VARIANT_DIR, "", DEFAULT_AVATAR_VARIANT_DIR),
        )
    }

    fun save(settings: OverlaySettings) {
        prefs.edit {
            putInt(KEY_SENSITIVITY, toPercentValue(settings.sensitivity, SENSITIVITY_PERCENT_RANGE))
            putInt(KEY_SCALE, toPercentValue(settings.avatarScale, SCALE_PERCENT_RANGE))
            putInt(KEY_ALPHA, toPercentValue(settings.avatarAlpha, ALPHA_PERCENT_RANGE))
            putBoolean(KEY_SHOW_MODE_TEXT, settings.showModeText)
            putBoolean(KEY_LOCK_POSITION, settings.lockPosition)
            putBoolean(KEY_AUTO_START_ON_BOOT, settings.autoStartOnBoot)
            putInt(KEY_AVATAR_ANCHOR_OFFSET, toPercentValue(settings.avatarAnchorOffsetPercent, AVATAR_ANCHOR_PERCENT_RANGE))
            putInt(KEY_AUDIO_THRESHOLD, toPercentValue(settings.audioActivityThreshold, AUDIO_THRESHOLD_PERCENT_RANGE))
            putInt(KEY_AUDIO_INACTIVITY_TIMEOUT, settings.audioInactivityTimeoutMs)
            putBoolean(KEY_USE_AVATAR_VARIANT_1, settings.useAvatarVariant1)
            putString(KEY_AVATAR_DIR, settings.avatarDir)
            putString(KEY_AVATAR_VARIANT_DIR, settings.avatarVariantDir)
            // OpenGL ES 选项已移除
        }
    }

    private fun readFloat(
        key: String,
        legacyKey: String,
        defaultValue: Float,
        percentRange: IntRange,
    ): Float {
        // 先尝试以 Int 方式读取，因为 SeekBarPreference 通常存储 Int
        if (prefs.contains(key)) {
            val raw = try {
                prefs.getInt(key, Int.MIN_VALUE)
            } catch (e: ClassCastException) {
                Int.MIN_VALUE
            }

            if (raw != Int.MIN_VALUE) {
                // 如果能读到 Int，说明存储的是百分比整数
                return normalizeFromCurrentNumber(raw.toFloat(), defaultValue, percentRange)
            }
            
            // 如果读不到 Int (可能是旧版本存了 Float)，则尝试读取所有并手动解析
            val currentRaw = prefs.all[key]
            if (currentRaw != null) {
                val decoded = decodeStoredFloat(currentRaw, defaultValue, percentRange)
                rewriteCurrentFloatIfNeeded(key, currentRaw, decoded, percentRange)
                return decoded
            }
        }

        val legacyRaw = legacyPrefs.all[legacyKey]
        if (legacyRaw != null) {
            val decoded = decodeLegacyFloat(legacyRaw, defaultValue, percentRange)
            prefs.edit { putInt(key, toPercentValue(decoded, percentRange)) }
            return decoded
        }

        return defaultValue
    }

    private fun readBoolean(key: String, legacyKey: String, defaultValue: Boolean): Boolean {
        return when {
            prefs.contains(key) -> prefs.getBoolean(key, defaultValue)
            legacyPrefs.contains(legacyKey) -> legacyPrefs.getBoolean(legacyKey, defaultValue)
            else -> defaultValue
        }
    }

    private fun readString(key: String, legacyKey: String, defaultValue: String): String {
        return when {
            prefs.contains(key) -> prefs.getString(key, defaultValue).orEmpty()
            legacyPrefs.contains(legacyKey) -> legacyPrefs.getString(legacyKey, defaultValue).orEmpty()
            else -> defaultValue
        }
    }

    private fun decodeStoredFloat(raw: Any, defaultValue: Float, percentRange: IntRange): Float {
        return when (raw) {
            is Int -> normalizeFromCurrentNumber(raw.toFloat(), defaultValue, percentRange)
            is Long -> normalizeFromCurrentNumber(raw.toFloat(), defaultValue, percentRange)
            is Float -> normalizeFromCurrentNumber(raw, defaultValue, percentRange)
            is Double -> normalizeFromCurrentNumber(raw.toFloat(), defaultValue, percentRange)
            is String -> normalizeFromCurrentNumber(raw.toFloatOrNull(), defaultValue, percentRange)
            else -> defaultValue
        }
    }

    private fun readInt(key: String, legacyKey: String, defaultValue: Int): Int {
        return when {
            prefs.contains(key) -> prefs.getInt(key, defaultValue)
            legacyPrefs.contains(legacyKey) -> legacyPrefs.getInt(legacyKey, defaultValue)
            else -> defaultValue
        }
    }

    private fun decodeLegacyFloat(raw: Any, defaultValue: Float, percentRange: IntRange): Float {
        return when (raw) {
            is Float -> normalizeLegacyNumber(raw, defaultValue, percentRange)
            is Double -> normalizeLegacyNumber(raw.toFloat(), defaultValue, percentRange)
            is Int -> normalizeLegacyNumber(raw.toFloat(), defaultValue, percentRange)
            is Long -> normalizeLegacyNumber(raw.toFloat(), defaultValue, percentRange)
            is String -> normalizeLegacyNumber(raw.toFloatOrNull(), defaultValue, percentRange)
            else -> defaultValue
        }
    }

    private fun normalizeFromCurrentNumber(value: Float?, defaultValue: Float, percentRange: IntRange): Float {
        val numeric = value ?: return defaultValue
        val normalizedRange = percentRange.first / 100f..percentRange.last / 100f
        return when {
            numeric in normalizedRange -> numeric
            numeric in percentRange.first.toFloat()..percentRange.last.toFloat() -> numeric / 100f
            numeric > 0f && numeric <= 3f -> numeric
            else -> defaultValue
        }
    }

    private fun normalizeLegacyNumber(value: Float?, defaultValue: Float, percentRange: IntRange): Float {
        val numeric = value ?: return defaultValue
        val normalizedRange = percentRange.first / 100f..percentRange.last / 100f
        return when {
            numeric in normalizedRange -> numeric
            numeric in percentRange.first.toFloat()..percentRange.last.toFloat() -> numeric / 100f
            numeric > 0f && numeric <= 3f -> numeric
            else -> defaultValue
        }
    }

    private fun rewriteCurrentFloatIfNeeded(
        key: String,
        raw: Any,
        decoded: Float,
        percentRange: IntRange,
    ) {
        val canonicalPercent = toPercentValue(decoded, percentRange)
        val alreadyCanonical = raw is Int && raw == canonicalPercent
        if (alreadyCanonical) return

        prefs.edit { putInt(key, canonicalPercent) }
    }

    private fun toPercentValue(value: Float, percentRange: IntRange): Int {
        return (value * 100f).roundToInt().coerceIn(percentRange.first, percentRange.last)
    }

    companion object {
        private const val LEGACY_PREFS_NAME = "overlay_settings"
        const val KEY_SENSITIVITY = "pref_sensitivity"
        const val KEY_SCALE = "pref_avatar_size"
        const val KEY_ALPHA = "pref_avatar_alpha"
        const val KEY_SHOW_MODE_TEXT = "pref_show_mode_text"
        const val KEY_LOCK_POSITION = "pref_lock_position"
        const val KEY_AUTO_START_ON_BOOT = "pref_auto_start_boot"
        const val KEY_USE_AVATAR_VARIANT_1 = "pref_use_avatar1"
        const val KEY_AVATAR_DIR = "pref_avatar_dir"
        const val KEY_AVATAR_VARIANT_DIR = "pref_avatar_variant_dir"
        // OpenGL ES 首选项键已移除

        private const val LEGACY_KEY_SENSITIVITY = "sensitivity"
        private const val LEGACY_KEY_SCALE = "avatar_scale"
        private const val LEGACY_KEY_ALPHA = "avatar_alpha"
        private const val LEGACY_KEY_SHOW_MODE_TEXT = "show_mode_text"
        private const val LEGACY_KEY_LOCK_POSITION = "lock_position"
        private const val LEGACY_KEY_AUTO_START_ON_BOOT = "auto_start_on_boot"
        const val KEY_AVATAR_ANCHOR_OFFSET = "pref_avatar_anchor_offset"
        const val KEY_AUDIO_THRESHOLD = "pref_audio_threshold"
        const val KEY_AUDIO_INACTIVITY_TIMEOUT = "pref_audio_inactivity_timeout_ms"
        private const val LEGACY_KEY_AVATAR_ANCHOR_OFFSET = "avatar_anchor_offset"
        private const val LEGACY_KEY_AUDIO_THRESHOLD = "audio_threshold"
        private const val LEGACY_KEY_AUDIO_INACTIVITY_TIMEOUT = "audio_inactivity_timeout_ms"

        private const val DEFAULT_SENSITIVITY = 1.0f
        private const val DEFAULT_SCALE = 1.0f
        private const val DEFAULT_ALPHA = 0.92f
        private const val DEFAULT_AUDIO_THRESHOLD = 0.05f
        private const val DEFAULT_AUDIO_INACTIVITY_TIMEOUT_MS = 1500
        private const val DEFAULT_AVATAR_DIR = AvatarAssets.DIR_AVATAR
        private const val DEFAULT_AVATAR_VARIANT_DIR = AvatarAssets.DIR_AVATAR1

        private val SENSITIVITY_PERCENT_RANGE = 60..200
        private val SCALE_PERCENT_RANGE = 70..180
        private val ALPHA_PERCENT_RANGE = 35..100
        private val AVATAR_ANCHOR_PERCENT_RANGE = -50..50
        private val AUDIO_THRESHOLD_PERCENT_RANGE = 1..100
    }
}
