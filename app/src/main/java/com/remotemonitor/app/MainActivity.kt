package com.remotemonitor.app

import android.Manifest
import android.app.KeyguardManager
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
    companion object {
        /** Set on the launch Intent by MonitorService.wakeScreenAndShowActivity() when it needs to surface the UI over a locked/sleeping screen to get camera/mic access. */
        const val EXTRA_STEALTH_WAKE = "stealth_wake"
    }

    private lateinit var tvStatus: TextView
    private lateinit var tvDetails: TextView
    private lateinit var etNtfy: EditText
    private lateinit var etMqtt: EditText
    private lateinit var root: ScrollView
    private val handler = Handler(Looper.getMainLooper())
    private var dimmed = false
    // null = automatic (dim only while STREAMING); non-null = explicit user override
    private var manualDim: Boolean? = null
    // true when manualDim was set by the user touching the screen to wake it during a
    // STREAMING session (as opposed to an explicit "Dim mode" button press); cleared once
    // that session ends so the NEXT stream still auto-dims instead of staying stuck awake.
    private var wokeByTouch = false
    private var lastState: MonitorState = MonitorState.IDLE
    // True only while this Activity instance is on-screen *because* MonitorService stealth-woke
    // it for a remote request, not because the user actually opened the app. In that case the
    // activity must get out of the way again once the stream ends, or its own
    // FLAG_KEEP_SCREEN_ON would keep the screen lit forever and defeat the whole point of a
    // "nothing visibly happened" remote wake.
    private var stealthWakeActive = false

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
        etNtfy = findViewById(R.id.etNtfy)
        etMqtt = findViewById(R.id.etMqtt)

        etNtfy.setText(AppConfig.ntfyTopic(this))
        etMqtt.setText(AppConfig.mqttUrl(this))

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            AppConfig.save(this, AppConfig.token(this), etNtfy.text.toString(), etMqtt.text.toString())
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
                wokeByTouch = true
                applyDim(false)
            }
            false
        }

        MonitorBus.onStateChanged = { runOnUiThread { refreshUi() } }
        requestAllPerms()
        restartService()
        MonitorService.scheduleWatchdogAlarm(this)
        applyStealthWakeIfRequested(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyStealthWakeIfRequested(intent)
    }

    /**
     * When launched by MonitorService to wake a sleeping/locked phone for a remote stream
     * request, surface over the keyguard at near-zero brightness instead of the normal UI
     * flash — the whole point is that a glance at the phone shouldn't reveal it's recording.
     */
    private fun applyStealthWakeIfRequested(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_STEALTH_WAKE, false) != true) return
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        val km = getSystemService(KeyguardManager::class.java)
        km?.requestDismissKeyguard(this, null)
        manualDim = true
        stealthWakeActive = true
        applyDim(true)
    }

    override fun onResume() {
        super.onResume()
        MonitorBus.activityResumed = true
        handler.post(ticker)
        refreshUi()
    }
    override fun onPause() {
        MonitorBus.activityResumed = false
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

        // A touch-wake during STREAMING is meant to light up the screen for the current
        // session only; once that session ends, restore automatic dimming so the next
        // stream still goes dark instead of staying awake forever.
        if (wokeByTouch && lastState == MonitorState.STREAMING && state != MonitorState.STREAMING) {
            manualDim = null
            wokeByTouch = false
        }
        // A stealth-woken activity must retreat once the stream it was woken for is actually
        // over, so FLAG_KEEP_SCREEN_ON stops holding the screen on and the normal OS timeout
        // can put it back to sleep — otherwise a remote START would leave the screen lit
        // indefinitely after STOP.
        if (stealthWakeActive && state == MonitorState.IDLE && lastState != MonitorState.IDLE) {
            stealthWakeActive = false
            moveTaskToBack(true)
        }
        lastState = state

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
