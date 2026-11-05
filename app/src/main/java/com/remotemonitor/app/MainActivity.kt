package com.remotemonitor.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private lateinit var tvStatus: TextView
    private lateinit var tvDetails: TextView
    private lateinit var etToken: EditText
    private lateinit var etNtfy: EditText
    private lateinit var etMqtt: EditText
    private lateinit var root: ScrollView
    private val handler = Handler(Looper.getMainLooper())
    private var dimmed = false
    // null = automatic (dim only while STREAMING); non-null = explicit user override
    private var manualDim: Boolean? = null

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { ensureOverlay() }

    private val ticker = object : Runnable {
        override fun run() {
            refreshUi()
            handler.postDelayed(this, 1500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        root = findViewById(R.id.rootScroll)
        tvStatus = findViewById(R.id.tvStatus)
        tvDetails = findViewById(R.id.tvDetails)
        etToken = findViewById(R.id.etToken)
        etNtfy = findViewById(R.id.etNtfy)
        etMqtt = findViewById(R.id.etMqtt)

        etToken.setText(AppConfig.token(this))
        etNtfy.setText(AppConfig.ntfyTopic(this))
        etMqtt.setText(AppConfig.mqttUrl(this))

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            AppConfig.save(this, etToken.text.toString(), etNtfy.text.toString(), etMqtt.text.toString())
            restartService()
            reloadServiceConfig()
            refreshUi()
        }
        findViewById<Button>(R.id.btnDim).setOnClickListener {
            manualDim = !dimmed
            applyDim(manualDim!!)
        }
        findViewById<Button>(R.id.btnPerms).setOnClickListener { requestAllPerms() }
        findViewById<Button>(R.id.btnService).setOnClickListener { restartService() }

        root.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN && dimmed) {
                manualDim = false
                applyDim(false)
            }
            false
        }

        MonitorBus.onStateChanged = { runOnUiThread { refreshUi() } }
        requestAllPerms()
        restartService()
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
        refreshUi()
    }
    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onDestroy() {
        MonitorBus.onStateChanged = null
        super.onDestroy()
    }

    private fun restartService() {
        val i = Intent(this, MonitorService::class.java)
        try { ContextCompat.startForegroundService(this, i) } catch (_: Exception) {}
    }

    private fun reloadServiceConfig() {
        val i = Intent(this, MonitorService::class.java).apply {
            action = MonitorService.ACTION_RELOAD_CONFIG
        }
        try { ContextCompat.startForegroundService(this, i) } catch (_: Exception) {}
    }

    private fun requestAllPerms() {
        val perms = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        permLauncher.launch(perms.toTypedArray())
    }

    private fun ensureOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } catch (_: Exception) {}
        }
    }

    private fun refreshUi() {
        val svc = MonitorService.instance
        val state = MonitorBus.state
        tvStatus.text = state.name
        val details = buildString {
            if (svc != null) {
                appendLine(svc.statusJson().toString(2))
            } else {
                appendLine(MonitorBus.snapshot().toString(2))
                appendLine("service: not running")
            }
            append("overlay: ${Settings.canDrawOverlays(this@MainActivity)}")
        }
        tvDetails.text = details

        val shouldDim = manualDim ?: (state == MonitorState.STREAMING)
        if (shouldDim != dimmed) applyDim(shouldDim)
    }

    private fun applyDim(enable: Boolean) {
        dimmed = enable
        val lp = window.attributes
        lp.screenBrightness = if (enable) 0.01f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = lp
        root.alpha = if (enable) 0.15f else 1.0f
    }
}
