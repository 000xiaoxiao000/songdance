package com.example.myapplication

import android.Manifest
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
// ...existing imports...
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private var pendingProjectionRequest = false

    private val overlayPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            updateStatus()
        }

    private val recordAudioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                toast(getString(R.string.msg_need_audio_permission))
                updateStatus()
                return@registerForActivityResult
            }
            if (pendingProjectionRequest) {
                pendingProjectionRequest = false
                requestNotificationThenStart()
            }
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            requestCaptureAndStart()
        }

    private val captureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK || result.data == null) {
                toast(getString(R.string.msg_capture_required_playback_only))
                updateStatus()
                return@registerForActivityResult
            }
            startOverlayService(result.resultCode, result.data)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupUi()
        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun setupUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(48, 64, 48, 48)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        statusText = TextView(this).apply {
            textSize = 16f
            setLineSpacing(8f, 1f)
        }

        val btnGrantOverlay = Button(this).apply {
            text = getString(R.string.btn_grant_overlay)
            setOnClickListener { openOverlayPermissionPage() }
        }

        val btnStart = Button(this).apply {
            text = getString(R.string.btn_start_dancer)
            setOnClickListener { startFlow() }
        }

        val btnStop = Button(this).apply {
            text = getString(R.string.btn_stop_dancer)
            setOnClickListener {
                val stopIntent = Intent(this@MainActivity, OverlayService::class.java).apply {
                    action = OverlayService.ACTION_STOP
                }
                startService(stopIntent)
                updateStatus()
            }
        }

        val btnSettings = Button(this).apply {
            text = getString(R.string.btn_open_settings)
            setOnClickListener {
                startActivity(SettingsActivity.createIntent(this@MainActivity))
            }
        }

        val btnBattery = Button(this).apply {
            text = getString(R.string.btn_battery_optimization)
            setOnClickListener {
                val intent = PowerOptimizationHelper.buildOptimizationIntent(this@MainActivity)
                if (intent == null) {
                    toast(getString(R.string.msg_battery_no_need))
                } else {
                    startActivity(intent)
                }
            }
        }

        root.addView(statusText)
        root.addView(btnGrantOverlay)
        root.addView(btnStart)
        root.addView(btnStop)
        root.addView(btnSettings)
        root.addView(btnBattery)
        setContentView(root)
    }

    private fun startFlow() {
        if (!Settings.canDrawOverlays(this)) {
            toast(getString(R.string.msg_need_overlay_permission))
            openOverlayPermissionPage()
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            pendingProjectionRequest = true
            recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        requestNotificationThenStart()
    }

    private fun requestNotificationThenStart() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }

        requestCaptureAndStart()
    }

    private fun requestCaptureAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val projectionManager =
                getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            captureLauncher.launch(projectionManager.createScreenCaptureIntent())
        } else {
            toast(getString(R.string.msg_playback_capture_not_supported))
        }
    }

    private fun startOverlayService(resultCode: Int, data: Intent?) {
        // 启动悬浮窗服务（不再在主界面弹出独立的全屏 begin 图片）
        // 悬浮窗服务自身会在启动时调用 overlayView?.showBeginSprite() 来在悬浮窗内显示 dancer_single_begin.png
        val intent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_START
            putExtra(OverlayService.EXTRA_CAPTURE_RESULT_CODE, resultCode)
            data?.let { putExtra(OverlayService.EXTRA_CAPTURE_DATA, it) }
        }
        ContextCompat.startForegroundService(this, intent)
        updateStatus()
    }

    private fun openOverlayPermissionPage() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            "package:$packageName".toUri()
        )
        overlayPermissionLauncher.launch(intent)
    }

    private fun updateStatus() {
        val overlayReady = Settings.canDrawOverlays(this)
        val audioReady = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        val batteryReady = PowerOptimizationHelper.isIgnoringBatteryOptimizations(this)

        statusText.text = getString(
            R.string.status_template,
            if (overlayReady) getString(R.string.status_granted) else getString(R.string.status_missing),
            if (audioReady) getString(R.string.status_granted) else getString(R.string.status_missing),
            if (batteryReady) getString(R.string.status_granted) else getString(R.string.status_missing)
        )
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    // 已移除：在主界面弹出全屏 begin 图片的实现。该逻辑会导致行为不一致（点击主界面按钮弹出独立视图），
    // 现在统一由 `OverlayService` 在悬浮窗内展示 begin 精灵（dancer_single_begin.png）。
}


