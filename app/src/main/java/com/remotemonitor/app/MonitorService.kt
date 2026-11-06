package com.remotemonitor.app

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.video.CameraHelper
import com.pedro.library.base.Camera2Base
import com.pedro.library.rtmp.RtmpCamera2
import com.pedro.library.rtsp.RtspCamera2
import com.pedro.library.util.streamclient.StreamBaseClient
import com.pedro.rtspserver.RtspServerCamera2
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit

class MonitorService : Service(), ConnectChecker {
    companion object {
        private const val TAG = "RemoteMonitor"
        private const val CHANNEL_ID = "monitor_channel"
        private const val WAKE_CHANNEL_ID = "monitor_wake_channel"
        private const val NOTIF_ID = 1001
        private const val NOTIF_WAKE_ID = 1002
        private const val WATCHDOG_REQUEST_CODE = 997
        private const val WATCHDOG_INTERVAL_MS = 60_000L
        const val ACTION_APPLY = "com.remotemonitor.app.APPLY"
        const val ACTION_RELOAD_CONFIG = "com.remotemonitor.app.RELOAD_CONFIG"
        const val ACTION_RESTART_SELF = "com.remotemonitor.app.RESTART_SELF"
        const val EXTRA_PAYLOAD = "payload"

        @Volatile var instance: MonitorService? = null

        /**
         * Self-chaining 60s heartbeat: AlarmManager has no native "repeat exactly while idle"
         * primitive (setRepeating isn't exact and drifts under Doze), so each firing of
         * ACTION_RESTART_SELF reschedules the next one. Call this from MainActivity.onCreate,
         * BOOT_COMPLETED, MonitorService.onCreate, and every ACTION_RESTART_SELF so the chain
         * restarts itself from any of those entry points.
         */
        fun scheduleWatchdogAlarm(context: Context) {
            try {
                val intent = Intent(context, ControlReceiver::class.java).apply {
                    action = ACTION_RESTART_SELF
                }
                val pi = PendingIntent.getBroadcast(
                    context, WATCHDOG_REQUEST_CODE, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + WATCHDOG_INTERVAL_MS, pi)
            } catch (e: Exception) {
                Log.e(TAG, "scheduleWatchdogAlarm failed: ${e.message}")
            }
        }
    }

    val webStreamer: DirectWebStreamer? get() = directWebStreamer

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutex = Mutex()
    private var watchdogJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var screenWakeLock: PowerManager.WakeLock? = null
    private var overlayView: View? = null
    private var overlayWindowManager: WindowManager? = null

    private var rtspServer: RtspServerCamera2? = null
    private var rtspClient: RtspCamera2? = null
    private var rtmpClient: RtmpCamera2? = null

    private var apiServer: ControlApiServer? = null
    private var ntfyWatcher: NtfyWatcher? = null
    private var mqttWatcher: MqttWatcher? = null

    // Ntfy sends a keepalive roughly every ~45-55s; a bounded read timeout (instead of
    // infinite) lets OkHttp detect a silently-dropped mobile/NAT connection quickly via a
    // read timeout + onFailure/reconnect, rather than hanging for hours on a half-open socket.
    private val sseClient = OkHttpClient.Builder()
        .readTimeout(90, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var directWebStreamer: DirectWebStreamer? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannel()
        startForeground(NOTIF_ID, buildNotification("Service starting"))

        // Must be attached before any camera/mic access attempt: Android's CameraService and
        // AudioPolicyService on API 30 refuse hardware access to a backgrounded process unless
        // it owns a visible, attached TYPE_APPLICATION_OVERLAY window, even with
        // SYSTEM_ALERT_WINDOW granted. A 1x1 transparent window satisfies that check invisibly.
        ensureOverlayWindow()

        // Held for the service's entire lifetime (including IDLE), not just while streaming,
        // so a screen-off, app-closed phone still answers a remote START within <1s instead of
        // the CPU/Wi-Fi going to sleep and missing the signal for hours.
        acquireStandbyLocks()

        // Keeps the self-restart chain alive from the service side too, independent of
        // MainActivity ever having been opened — see scheduleWatchdogAlarm's doc.
        scheduleWatchdogAlarm(this)

        try {
            apiServer = ControlApiServer(this, 5000)
            apiServer?.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            Log.i(TAG, "API listening on 0.0.0.0:5000")
        } catch (e: Exception) {
            MonitorBus.lastError = "API start failed: ${e.message}"
            Log.e(TAG, "API start failed", e)
        }

        ntfyWatcher = NtfyWatcher(this, sseClient) { payload -> applyPayload(payload) }
        ntfyWatcher?.start()

        mqttWatcher = MqttWatcher(this) { payload -> applyPayload(payload) }
        mqttWatcher?.start()

        directWebStreamer = DirectWebStreamer(this, sseClient, ntfyWatcher)

        updateNotification("IDLE")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_APPLY -> {
                val raw = intent.getStringExtra(EXTRA_PAYLOAD)
                val payload = raw?.let { ControlPayload.parse(it) }
                if (payload != null) applyPayload(payload)
            }
            ACTION_RELOAD_CONFIG -> {
                Log.i(TAG, "Reloading Ntfy/MQTT config")
                ntfyWatcher?.reload()
                mqttWatcher?.reload()
            }
        }
        return START_STICKY
    }

    /**
     * Fires when the user swipes the app away from the recents list. Without this, Android
     * treats task removal as a signal to tear the service down shortly after, even though it's
     * a foreground service — so a remote-controlled phone that gets "closed" would silently stop
     * answering. Schedule an exact alarm that restarts the service a moment later; by the time it
     * fires the OS has finished its own teardown, so a plain re-entrant start here is unreliable.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.w(TAG, "onTaskRemoved: app closed from recents, scheduling service restart")
        try {
            val restartIntent = Intent(this, ControlReceiver::class.java).apply {
                action = ACTION_RESTART_SELF
            }
            val pi = PendingIntent.getBroadcast(
                this, 998, restartIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 500, pi)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule restart alarm", e)
        }
    }

    fun applyPayload(payload: ControlPayload) {
        scope.launch { applyPayloadSync(payload) }
    }

    /** Called by the watchdog alarm (ControlReceiver.ACTION_RESTART_SELF) when the service is already alive, to catch a silently-dead ntfy SSE connection that would otherwise never self-heal. */
    fun checkNtfyHealth() {
        val watcher = ntfyWatcher ?: return
        if (!watcher.isConnected()) {
            Log.w(TAG, "Watchdog: Ntfy SSE not connected, reloading")
            watcher.reload()
        }
    }

    suspend fun applyPayloadSync(payload: ControlPayload) {
        if (!AppConfig.tokenMatches(this, payload.authToken)) {
            MonitorBus.failedAuthCount++
            Log.w(TAG, "Rejected payload: invalid auth token (count=${MonitorBus.failedAuthCount})")
            return
        }
        withContext(Dispatchers.Main.immediate) {
            mutex.withLock {
                when (payload.action) {
                    "START" -> handleStart(payload)
                    "STOP" -> handleStop("remote STOP")
                    "STATUS" -> publishStatusReply()
                    "SWITCH_CAMERA" -> handleSwitchCamera()
                    "TORCH_ON" -> handleTorch(true)
                    "TORCH_OFF" -> handleTorch(false)
                    else -> MonitorBus.lastError = "Unknown action: ${payload.action}"
                }
            }
            updateNotification(MonitorBus.state.name)
        }
    }

    private fun activeStreamer(): Camera2Base? = rtspServer ?: rtspClient ?: rtmpClient

    private fun handleSwitchCamera() {
        if (MonitorBus.state != MonitorState.STREAMING) {
            MonitorBus.lastError = "Cannot switch camera: not streaming"
            Log.w(TAG, MonitorBus.lastError)
            return
        }
        if (MonitorBus.activePayload?.mode == "AUDIO_ONLY") {
            MonitorBus.lastError = "Cannot switch camera: audio-only mode has no active camera"
            Log.w(TAG, MonitorBus.lastError)
            return
        }
        val streamer = activeStreamer() ?: run {
            MonitorBus.lastError = "No active streamer"
            Log.w(TAG, MonitorBus.lastError)
            return
        }
        try {
            streamer.switchCamera()
            val newFacing = if (streamer.cameraFacing == CameraHelper.Facing.FRONT) "FRONT" else "BACK"
            MonitorBus.activePayload = MonitorBus.activePayload?.copy(camera = newFacing)
            if (newFacing == "FRONT" && MonitorBus.torchActive) {
                try { streamer.disableLantern() } catch (_: Exception) {}
                MonitorBus.torchActive = false
            }
            Log.i(TAG, "Switched camera to $newFacing")
            publishStatusReply()
        } catch (e: Exception) {
            Log.e(TAG, "switchCamera error", e)
            MonitorBus.lastError = "switchCamera error: ${e.message}"
        }
    }

    private fun handleTorch(enable: Boolean) {
        if (MonitorBus.state != MonitorState.STREAMING) {
            MonitorBus.lastError = "Cannot toggle torch: not streaming"
            Log.w(TAG, MonitorBus.lastError)
            return
        }
        if (MonitorBus.activePayload?.mode == "AUDIO_ONLY") {
            MonitorBus.lastError = "Cannot toggle torch: audio-only mode has no active camera"
            Log.w(TAG, MonitorBus.lastError)
            return
        }
        val streamer = activeStreamer() ?: run {
            MonitorBus.lastError = "No active streamer"
            Log.w(TAG, MonitorBus.lastError)
            return
        }
        try {
            if (enable) {
                streamer.enableLantern()
                MonitorBus.torchActive = true
                Log.i(TAG, "Torch enabled")
            } else {
                streamer.disableLantern()
                MonitorBus.torchActive = false
                Log.i(TAG, "Torch disabled")
            }
            publishStatusReply()
        } catch (e: Exception) {
            Log.e(TAG, "Torch toggle error (enable=$enable)", e)
            MonitorBus.lastError = "Torch toggle error: ${e.message}"
        }
    }

    private suspend fun handleStart(payload: ControlPayload) {
        if (MonitorBus.state == MonitorState.STREAMING || MonitorBus.state == MonitorState.STARTING) {
            Log.i(TAG, "Restarting stream with new parameters")
            handleStop("restart for new parameters")
            delay(600)
        }
        MonitorBus.update(MonitorState.STARTING)
        MonitorBus.lastError = ""
        MonitorBus.activePayload = payload
        MonitorBus.startedAtMs = System.currentTimeMillis()
        MonitorBus.endsAtMs = if (payload.durationSeconds > 0)
            MonitorBus.startedAtMs + payload.durationSeconds * 1000L else 0L

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isInteractive && payload.mode != "AUDIO_ONLY") {
            // AUDIO_ONLY never touches the camera/preview, so there's nothing that needs a
            // foregrounded activity to unblock — waking the screen for it would just be a
            // pointless, visible flash with no benefit.
            Log.i(TAG, "Screen is off; waking it before touching camera/mic")
            wakeScreenAndShowActivity(payload.durationSeconds)
            // Wait for MainActivity to actually reach onResume() (not just for
            // startActivity() to have been called) before opening the camera — a fixed 300ms
            // isn't reliably enough on a cold wake from deep sleep on this hardware, but
            // waiting indefinitely risks wedging a START forever if the activity never comes
            // up for some reason, so cap it.
            withTimeoutOrNull(2500) {
                while (!MonitorBus.activityResumed) delay(100)
            }
            // Small additional settle time even after onResume() fires, before touching the
            // camera — same reasoning as the old fixed delay, just on top of the dynamic wait
            // rather than in place of it.
            delay(200)
        }

        try {
            val ok = startStreaming(payload)
            if (ok) {
                MonitorBus.update(MonitorState.STREAMING)
                if (payload.mode != "AUDIO_ONLY" && payload.torch && payload.camera != "FRONT") {
                    try {
                        activeStreamer()?.enableLantern()
                        MonitorBus.torchActive = true
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to enable torch on start: ${e.message}")
                    }
                }
                scheduleWatchdog(payload.durationSeconds)
                Log.i(TAG, "Streaming started: mode=${payload.mode} target=${MonitorBus.streamUrl} bitrate=${payload.videoBitrateKbps}k fps=${payload.fps}")
                try {
                    directWebStreamer?.start(activeStreamer(), payload)
                } catch (e: Exception) {
                    Log.w(TAG, "DirectWebStreamer start failed: ${e.message}")
                }
                publishStatusReply()
            } else {
                MonitorBus.lastError = "Failed to prepare/start stream"
                cleanupStreamers()
                MonitorBus.update(MonitorState.IDLE)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Start failed", e)
            MonitorBus.lastError = "Start failed: ${e.message}"
            cleanupStreamers()
            MonitorBus.update(MonitorState.IDLE)
        }
    }

    private suspend fun handleStop(reason: String) {
        MonitorBus.update(MonitorState.STOPPING)
        watchdogJob?.cancel()
        watchdogJob = null
        try {
            if (MonitorBus.torchActive) {
                activeStreamer()?.disableLantern()
            }
        } catch (_: Exception) {}
        MonitorBus.torchActive = false
        cleanupStreamers()
        releaseScreenWakeLock()
        getSystemService(NotificationManager::class.java).cancel(NOTIF_WAKE_ID)
        MonitorBus.activePayload = null
        MonitorBus.streamUrl = ""
        MonitorBus.startedAtMs = 0L
        MonitorBus.endsAtMs = 0L
        MonitorBus.lastError = reason
        MonitorBus.update(MonitorState.IDLE)
        Log.i(TAG, "Stopped: $reason")
        publishStatusReply()
    }

    fun publishStatusReply() {
        scope.launch(Dispatchers.IO) {
            val json = statusJson().toString()
            Log.i(TAG, "Publishing status to remote channels")
            ntfyWatcher?.publishStatus(json)
            mqttWatcher?.publishStatus(json)
        }
    }

    private fun scheduleWatchdog(durationSeconds: Long) {
        watchdogJob?.cancel()
        if (durationSeconds <= 0) return
        watchdogJob = scope.launch {
            delay(durationSeconds * 1000L)
            mutex.withLock { handleStop("duration expired") }
            updateNotification(MonitorBus.state.name)
        }
    }

    private fun startStreaming(payload: ControlPayload): Boolean {
        val isAudioOnly = payload.mode == "AUDIO_ONLY"
        val isVideoOnly = payload.mode == "VIDEO_ONLY"
        val isBack = payload.camera != "FRONT"
        val rawTarget = payload.streamTarget.trim()
        // Empty target, or an explicit "DIRECT", both mean: no external RTSP/RTMP push wanted,
        // just the built-in local RTSP server (if anyone cares to connect to it) plus the
        // phone's own HTTP endpoints. In that case the mic must stay exclusively with
        // DirectWebStreamer's AudioRecord (see below) rather than RootEncoder.
        val isDirect = rawTarget.isEmpty() || rawTarget.equals("DIRECT", ignoreCase = true)
        val target = if (isDirect) "" else rawTarget
        val useServer = isDirect

        if (isDirect && isAudioOnly) {
            // Direct + AUDIO_ONLY needs no RootEncoder instance at all: there is no video to
            // serve over the built-in RTSP server, so spinning one up (and opening the camera
            // preview that goes with it) only burns CPU and needlessly wakes the screen.
            // DirectWebStreamer's own AudioRecord (started right after this returns) is the
            // only thing that actually needs to run.
            MonitorBus.streamUrl = ""
            return true
        }

        val (w, h) = resolveVideoResolution(payload.videoResolution)

        val bitrateBps = payload.videoBitrateKbps * 1024
        val fps = payload.fps

        return if (useServer) {
            val server = RtspServerCamera2(this, this, 8554)
            rtspServer = server
            val token = AppConfig.token(this)
            server.getStreamClient().setAuthorization("admin", token)

            // Deliberately never call server.prepareAudio() here: this RootEncoder instance is
            // only a secondary local RTSP viewer for this mode, and the mic must be free for
            // DirectWebStreamer's own AudioRecord (started by MonitorService right after this
            // function returns) to open without contention. The RTSP server therefore only ever
            // carries video.
            // RootEncoder's startStream()/startEncoders() unconditionally starts the video
            // encoder regardless of mode; setOnlyVideo() only suppresses audio on the wire, not
            // at prepare/start time. Leaving it unprepared in AUDIO_ONLY mode throws
            // "VideoEncoder not prepared yet" from startStream() below, so it must always be
            // prepared (the camera itself still stays untouched since startPreview() below
            // remains gated on !isAudioOnly).
            val videoOk = server.prepareVideo(w, h, fps, bitrateBps, 0, 90)

            if (!videoOk) return false

            server.getStreamClient().setOnlyAudio(false)
            server.getStreamClient().setOnlyVideo(true)

            if (!isAudioOnly) {
                try {
                    val facing = if (isBack) CameraHelper.Facing.BACK else CameraHelper.Facing.FRONT
                    server.startPreview(facing, w, h)
                } catch (e: Exception) {
                    Log.e(TAG, "startPreview server error", e)
                }
            }
            server.startStream()
            ensureCameraFacing(server, isBack, isAudioOnly)
            val ip = bestIp()
            MonitorBus.streamUrl = "rtsp://$ip:8554/live"
            true
        } else if (target.startsWith("rtmp", true)) {
            val client = RtmpCamera2(this, this)
            rtmpClient = client
            startClient(client, client.getStreamClient(), target, isAudioOnly, isVideoOnly, isBack, w, h, fps, bitrateBps)
        } else {
            val client = RtspCamera2(this, this)
            rtspClient = client
            startClient(client, client.getStreamClient(), target, isAudioOnly, isVideoOnly, isBack, w, h, fps, bitrateBps)
        }
    }

    private fun startClient(
        client: Camera2Base,
        streamClient: StreamBaseClient,
        target: String,
        isAudioOnly: Boolean,
        isVideoOnly: Boolean,
        isBack: Boolean,
        w: Int,
        h: Int,
        fps: Int,
        bitrateBps: Int
    ): Boolean {
        var audioOk = true
        if (!isVideoOnly) {
            audioOk = client.prepareAudio(64 * 1024, 32000, true, false, false)
        }
        // Same RootEncoder constraint as the built-in server path: the video encoder must
        // always be prepared even in AUDIO_ONLY mode, or startStream() throws.
        val videoOk = client.prepareVideo(w, h, fps, bitrateBps, 0, 90)

        if (!audioOk || !videoOk) return false

        streamClient.setOnlyAudio(isAudioOnly)
        streamClient.setOnlyVideo(isVideoOnly)

        if (!isAudioOnly) {
            try {
                val facing = if (isBack) CameraHelper.Facing.BACK else CameraHelper.Facing.FRONT
                client.startPreview(facing, w, h)
            } catch (e: Exception) {
                Log.e(TAG, "startPreview client error", e)
            }
        }
        client.startStream(target)
        ensureCameraFacing(client, isBack, isAudioOnly)
        MonitorBus.streamUrl = target
        return true
    }

    private fun ensureCameraFacing(client: Camera2Base, isBack: Boolean, isAudioOnly: Boolean) {
        if (isAudioOnly) return
        try {
            val wanted = if (isBack) CameraHelper.Facing.BACK else CameraHelper.Facing.FRONT
            if (client.cameraFacing != wanted) {
                Log.i(TAG, "Active camera facing mismatch, switching camera")
                client.switchCamera()
            }
        } catch (e: Exception) {
            Log.e(TAG, "switchCamera error", e)
        }
    }

    private fun cleanupStreamers() {
        try { directWebStreamer?.stop() } catch (_: Exception) {}
        try { rtspServer?.let { it.stopStream(); it.stopPreview() } } catch (_: Exception) {}
        try { rtspClient?.let { it.stopStream(); it.stopPreview() } } catch (_: Exception) {}
        try { rtmpClient?.let { it.stopStream(); it.stopPreview() } } catch (_: Exception) {}
        rtspServer = null
        rtspClient = null
        rtmpClient = null
    }

    fun statusJson(): JSONObject = JSONObject().apply {
        val ips = allIps()
        put("ok", true)
        put("state", MonitorBus.state.name)
        put("mode", MonitorBus.activePayload?.mode ?: "NONE")
        put("camera", MonitorBus.activePayload?.camera ?: "")
        put("stream_url", MonitorBus.streamUrl)
        put("battery_percent", batteryPercent())
        put("charging", isCharging())
        put("remaining_seconds", MonitorBus.remainingSeconds())
        put("started_at_ms", MonitorBus.startedAtMs)
        put("ends_at_ms", MonitorBus.endsAtMs)
        put("last_error", MonitorBus.lastError)
        put("failed_auth_count", MonitorBus.failedAuthCount)
        put("torch_active", MonitorBus.torchActive)
        put("video_bitrate_kbps", MonitorBus.activePayload?.videoBitrateKbps ?: 1500)
        put("fps", MonitorBus.activePayload?.fps ?: 30)
        put("overlay_allowed", Settings.canDrawOverlays(this@MonitorService))
        put("ntfy_topic_configured", AppConfig.ntfyTopic(this@MonitorService).isNotBlank())
        put("mqtt_configured", AppConfig.mqttUrl(this@MonitorService).isNotBlank())
        put("ips", JSONObject().apply {
            put("wifi", getInterfaceIp("wlan0"))
            put("vpn", getInterfaceIp("tun0"))
            put("best", bestIp(ips))
            put("all", ips.joinToString(","))
        })
    }

    private fun batteryPercent(): Int {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    private fun isCharging(): Boolean {
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
        val s = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        return s == BatteryManager.BATTERY_STATUS_CHARGING || s == BatteryManager.BATTERY_STATUS_FULL
    }

    private fun getInterfaceIp(name: String): String = try {
        val nif = NetworkInterface.getByName(name)
        nif?.inetAddresses?.toList()
            ?.filterIsInstance<Inet4Address>()
            ?.firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress ?: ""
    } catch (_: Exception) { "" }

    private fun bestIp(all: List<String> = allIps()): String {
        val vpn = getInterfaceIp("tun0")
        if (vpn.isNotBlank()) return vpn
        val wifi = getInterfaceIp("wlan0")
        if (wifi.isNotBlank()) return wifi
        return all.firstOrNull { it.startsWith("100.") }
            ?: all.firstOrNull { it.startsWith("192.168.") }
            ?: all.firstOrNull { it.startsWith("10.") }
            ?: all.firstOrNull() ?: "0.0.0.0"
    }

    private fun allIps(): List<String> = try {
        NetworkInterface.getNetworkInterfaces().toList().flatMap { ni ->
            ni.inetAddresses.toList()
                .filterIsInstance<Inet4Address>()
                .filter { !it.isLoopbackAddress }
                .map { it.hostAddress ?: "" }
        }.filter { it.isNotBlank() }
    } catch (_: Exception) { emptyList() }

    private fun acquireStandbyLocks() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "remotemonitor:standby_wakelock").apply {
                    setReferenceCounted(false)
                }
            }
            if (wakeLock?.isHeld == false) wakeLock?.acquire()

            if (wifiLock == null) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "remotemonitor:standby_wifilock").apply {
                    setReferenceCounted(false)
                }
            }
            if (wifiLock?.isHeld == false) wifiLock?.acquire()
            Log.i(TAG, "Standby wake/wifi locks acquired for service lifetime")
        } catch (e: Exception) {
            Log.w(TAG, "acquireStandbyLocks error: ${e.message}")
        }
    }

    private fun releaseStandbyLocks() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {}
        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (_: Exception) {}
    }

    private fun ensureOverlayWindow() {
        if (overlayView != null) return
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "SYSTEM_ALERT_WINDOW not granted; camera/mic may fail while backgrounded with screen off")
            return
        }
        try {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val view = View(this)
            val params = WindowManager.LayoutParams(
                1, 1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 0
            }
            wm.addView(view, params)
            overlayView = view
            overlayWindowManager = wm
            Log.i(TAG, "Attached invisible 1x1 overlay window")
        } catch (e: Exception) {
            Log.w(TAG, "ensureOverlayWindow failed: ${e.message}")
        }
    }

    private fun removeOverlayWindow() {
        try {
            overlayView?.let { overlayWindowManager?.removeView(it) }
        } catch (_: Exception) {}
        overlayView = null
        overlayWindowManager = null
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Monitor Service", NotificationManager.IMPORTANCE_LOW)
        )
        // Separate, high-importance channel: the main channel is IMPORTANCE_LOW (silent,
        // ongoing status notification) which cannot carry a heads-up/full-screen notification
        // on Android — needed to reliably bring the activity up over a locked keyguard.
        nm.createNotificationChannel(
            NotificationChannel(WAKE_CHANNEL_ID, "Monitor Wake", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    /**
     * Wakes a sleeping/locked screen and brings MainActivity to the front in "stealth" mode
     * (minimum brightness, see MainActivity.EXTRA_STEALTH_WAKE) so Android's CameraService /
     * AudioPolicyService treat this as a foregrounded request instead of blocking it. Acquires
     * a SCREEN_DIM_WAKE_LOCK with ACQUIRE_CAUSES_WAKEUP, released again in releaseScreenWakeLock()
     * once the stream stops.
     */
    private fun wakeScreenAndShowActivity(durationSeconds: Long) {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (screenWakeLock == null) {
                @Suppress("DEPRECATION")
                screenWakeLock = pm.newWakeLock(
                    PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "remotemonitor:screen_wakelock"
                )
            }
            // Safety-net timeout in case a STOP/duration-expiry somehow never arrives to release
            // it explicitly — sized to the actual requested session length (plus a 30s margin)
            // instead of a flat 10 minutes, so a long-running continuous watch session doesn't
            // have the screen wake lock quietly expire out from under it mid-stream.
            val timeoutMs = if (durationSeconds > 0) (durationSeconds + 30) * 1000L else 4 * 3600 * 1000L
            if (screenWakeLock?.isHeld == false) screenWakeLock?.acquire(timeoutMs)

            val activityIntent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(MainActivity.EXTRA_STEALTH_WAKE, true)
            }
            try {
                startActivity(activityIntent)
            } catch (e: Exception) {
                Log.w(TAG, "Direct startActivity failed, relying on full-screen-intent notification: ${e.message}")
            }
            showWakeNotification(activityIntent)
        } catch (e: Exception) {
            Log.w(TAG, "wakeScreenAndShowActivity failed: ${e.message}")
        }
    }

    private fun releaseScreenWakeLock() {
        try {
            if (screenWakeLock?.isHeld == true) screenWakeLock?.release()
        } catch (_: Exception) {}
    }

    private fun showWakeNotification(activityIntent: Intent) {
        val pi = PendingIntent.getActivity(
            this, 2, activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = Notification.Builder(this, WAKE_CHANNEL_ID)
            .setContentTitle("RemoteMonitor")
            .setContentText("Waking for remote stream")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setFullScreenIntent(pi, true)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_WAKE_ID, notif)
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("RemoteMonitor")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    override fun onDestroy() {
        watchdogJob?.cancel()
        cleanupStreamers()
        releaseStandbyLocks()
        releaseScreenWakeLock()
        removeOverlayWindow()
        MonitorBus.activePayload = null
        MonitorBus.streamUrl = ""
        MonitorBus.startedAtMs = 0L
        MonitorBus.endsAtMs = 0L
        MonitorBus.torchActive = false
        MonitorBus.update(MonitorState.IDLE)
        try { apiServer?.stop() } catch (_: Exception) {}
        ntfyWatcher?.stop()
        mqttWatcher?.stop()
        scope.cancel()
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ConnectChecker
    override fun onConnectionStarted(url: String) { Log.i(TAG, "Connect started: $url") }
    override fun onConnectionSuccess() { Log.i(TAG, "Connection success") }
    override fun onConnectionFailed(reason: String) {
        Log.e(TAG, "Connection failed: $reason")
        MonitorBus.lastError = "Connection failed: $reason"
        if (rtspServer != null) {
            Log.w(TAG, "Ignoring viewer connection failure in server mode: $reason")
            return
        }
        scope.launch { mutex.withLock { if (MonitorBus.state == MonitorState.STREAMING) handleStop("connection failed") } }
    }
    override fun onDisconnect() { Log.i(TAG, "Disconnected") }
    override fun onAuthError() { MonitorBus.lastError = "Auth error"; onConnectionFailed("auth error") }
    override fun onAuthSuccess() { Log.i(TAG, "Auth success") }
}
