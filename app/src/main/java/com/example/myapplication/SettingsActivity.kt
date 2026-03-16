package com.example.myapplication

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SeekBarPreference
import androidx.preference.SwitchPreferenceCompat

class SettingsActivity : AppCompatActivity() {

    override fun onStart() {
        super.onStart()
        sendOverlayAction(OverlayService.ACTION_HIDE)
    }

    override fun onStop() {
        sendOverlayAction(OverlayService.ACTION_SHOW)
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportFragmentManager
            .beginTransaction()
            .replace(android.R.id.content, SettingsFragment())
            .commit()
    }

    class SettingsFragment : PreferenceFragmentCompat() {

        private lateinit var repo: OverlaySettingsRepository

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            view.setPadding(0, 220, 0, 0)
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.root_preferences, rootKey)
            repo = OverlaySettingsRepository(requireContext())

            val current = repo.get()
            bindCurrentValues(current)
            adjustBootPreferenceIfNeeded()
            bindSavers()
        }

        private fun adjustBootPreferenceIfNeeded() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
            findPreference<SwitchPreferenceCompat>(KEY_BOOT)?.apply {
                isEnabled = false
                summary = getString(R.string.pref_auto_start_boot_summary_unsupported)
                isChecked = false
            }
        }

        private fun bindCurrentValues(current: OverlaySettings) {
            findPreference<SeekBarPreference>(KEY_SENSITIVITY)?.value =
                (current.sensitivity * 100f).toInt()
            findPreference<SeekBarPreference>(KEY_SIZE)?.value =
                (current.avatarScale * 100f).toInt()
            findPreference<SeekBarPreference>(KEY_ALPHA)?.value =
                (current.avatarAlpha * 100f).toInt()
            findPreference<SwitchPreferenceCompat>(KEY_SHOW_MODE)?.isChecked = current.showModeText
            findPreference<SwitchPreferenceCompat>(KEY_LOCK)?.isChecked = current.lockPosition
            findPreference<SwitchPreferenceCompat>(KEY_BOOT)?.isChecked = current.autoStartOnBoot
            // 偏好设置
            findPreference<SeekBarPreference>(KEY_AVATAR_ANCHOR)?.value = current.avatarAnchorOffsetPercent.toInt()
            findPreference<SeekBarPreference>(KEY_AUDIO_THRESHOLD)?.value = (current.audioActivityThreshold * 100f).toInt()
            findPreference<SeekBarPreference>(KEY_AUDIO_INACTIVITY)?.value = current.audioInactivityTimeoutMs
            findPreference<SwitchPreferenceCompat>(KEY_USE_AVATAR1)?.isChecked = current.useAvatarVariant1
            findPreference<androidx.preference.EditTextPreference>(KEY_AVATAR_DIR)?.text = current.avatarDir
            findPreference<androidx.preference.EditTextPreference>(KEY_AVATAR_VARIANT_DIR)?.text = current.avatarVariantDir
        }

        private fun bindSavers() {
            val listener = Preference.OnPreferenceChangeListener { _, _ ->
                view?.post { notifySettingsChanged() }
                true
            }

            findPreference<SeekBarPreference>(KEY_SENSITIVITY)?.onPreferenceChangeListener = listener
            findPreference<SeekBarPreference>(KEY_SIZE)?.onPreferenceChangeListener = listener
            findPreference<SeekBarPreference>(KEY_ALPHA)?.onPreferenceChangeListener = listener
            findPreference<SwitchPreferenceCompat>(KEY_SHOW_MODE)?.onPreferenceChangeListener = listener
            findPreference<SwitchPreferenceCompat>(KEY_LOCK)?.onPreferenceChangeListener = listener
            findPreference<SwitchPreferenceCompat>(KEY_BOOT)?.onPreferenceChangeListener = listener
            // 新增偏好设置的变更监听器
            findPreference<SeekBarPreference>(KEY_AVATAR_ANCHOR)?.onPreferenceChangeListener = listener
            findPreference<SeekBarPreference>(KEY_AUDIO_THRESHOLD)?.onPreferenceChangeListener = listener
            findPreference<SeekBarPreference>(KEY_AUDIO_INACTIVITY)?.onPreferenceChangeListener = listener
            findPreference<SwitchPreferenceCompat>(KEY_USE_AVATAR1)?.onPreferenceChangeListener = listener
            findPreference<androidx.preference.EditTextPreference>(KEY_AVATAR_DIR)?.onPreferenceChangeListener = listener
            findPreference<androidx.preference.EditTextPreference>(KEY_AVATAR_VARIANT_DIR)?.onPreferenceChangeListener = listener

        }

        private fun notifySettingsChanged() {
            // Send a broadcast for backwards compatibility and also notify the service directly
            val ctx = requireContext()
            ctx.sendBroadcast(Intent(ACTION_SETTINGS_CHANGED).setPackage(ctx.packageName))
            if (OverlayService.isRunning) {
                val intent = Intent(ctx, OverlayService::class.java).apply {
                    action = OverlayService.ACTION_APPLY_SETTINGS
                }
                ctx.startService(intent)
            }
        }

        companion object {
            private const val KEY_SENSITIVITY = OverlaySettingsRepository.KEY_SENSITIVITY
            private const val KEY_SIZE = OverlaySettingsRepository.KEY_SCALE
            private const val KEY_ALPHA = OverlaySettingsRepository.KEY_ALPHA
            private const val KEY_SHOW_MODE = OverlaySettingsRepository.KEY_SHOW_MODE_TEXT
            private const val KEY_LOCK = OverlaySettingsRepository.KEY_LOCK_POSITION
            private const val KEY_BOOT = OverlaySettingsRepository.KEY_AUTO_START_ON_BOOT
            private const val KEY_AVATAR_ANCHOR = OverlaySettingsRepository.KEY_AVATAR_ANCHOR_OFFSET
            private const val KEY_AUDIO_THRESHOLD = OverlaySettingsRepository.KEY_AUDIO_THRESHOLD
            private const val KEY_AUDIO_INACTIVITY = OverlaySettingsRepository.KEY_AUDIO_INACTIVITY_TIMEOUT
            private const val KEY_USE_AVATAR1 = OverlaySettingsRepository.KEY_USE_AVATAR_VARIANT_1
            private const val KEY_AVATAR_DIR = OverlaySettingsRepository.KEY_AVATAR_DIR
            private const val KEY_AVATAR_VARIANT_DIR = OverlaySettingsRepository.KEY_AVATAR_VARIANT_DIR
        }
    }

    companion object {
        const val ACTION_SETTINGS_CHANGED = "com.example.myapplication.action.SETTINGS_CHANGED"

        fun createIntent(context: Context): Intent {
            return Intent(context, SettingsActivity::class.java)
        }
    }

    private fun sendOverlayAction(action: String) {
        if (!OverlayService.isRunning) return
        val intent = Intent(this, OverlayService::class.java).apply {
            this.action = action
        }
        startService(intent)
    }
}
