package com.remotemonitor.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
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
        private const val NOTIF_ID = 1001
        const val ACTION_APPLY = "com.remotemonitor.app.APPLY"
        const val ACTION_RELOAD_CONFIG = "com.remotemonitor.app.RELOAD_CONFIG"
        const val EXTRA_PAYLOAD = "payload"

        @Volatile var instance: MonitorService? = null
    }

    val webStreamer: DirectWebStreamer? get() = directWebStreamer

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutex = Mutex()
    private var watchdogJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

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

    fun applyPayload(payload: ControlPayload) {
        scope.launch { applyPayloadSync(payload) }
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

        try {
            val ok = startStreaming(payload)
            if (ok) {
                acquireStreamingLocks()
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
                releaseStreamingLocks()
                MonitorBus.update(MonitorState.IDLE)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Start failed", e)
            MonitorBus.lastError = "Start failed: ${e.message}"
            cleanupStreamers()
            releaseStreamingLocks()
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
        releaseStreamingLocks()
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
        val target = payload.streamTarget.trim()
        val useServer = target.isEmpty()

        val (w, h) = resolveVideoResolution(payload.videoResolution)

        val bitrateBps = payload.videoBitrateKbps * 1024
        val fps = payload.fps

        return if (useServer) {
            val server = RtspServerCamera2(this, this, 8554)
            rtspServer = server
            val token = AppConfig.token(this)
            server.getStreamClient().setAuthorization("admin", token)

            var audioOk = true
            if (!isVideoOnly) {
                audioOk = server.prepareAudio(64 * 1024, 32000, true, false, false)
            }
            // RootEncoder's startStream()/startEncoders() unconditionally starts the video
            // encoder regardless of mode; setOnlyAudio() only suppresses it on the wire, not
            // at prepare/start time. Leaving it unprepared in AUDIO_ONLY mode throws
            // "VideoEncoder not prepared yet" from startStream() below, so it must always be
            // prepared (the camera itself still stays untouched since startPreview() below
            // remains gated on !isAudioOnly).
            val videoOk = server.prepareVideo(w, h, fps, bitrateBps, 0, 90)

            if (!audioOk || !videoOk) return false

            server.getStreamClient().setOnlyAudio(isAudioOnly)
            server.getStreamClient().setOnlyVideo(isVideoOnly)

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

    private fun acquireStreamingLocks() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "remotemonitor:streaming_wakelock").apply {
                    setReferenceCounted(false)
                }
            }
            if (wakeLock?.isHeld == false) wakeLock?.acquire(10 * 3600 * 1000L)

            if (wifiLock == null) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "remotemonitor:streaming_wifilock").apply {
                    setReferenceCounted(false)
                }
            }
            if (wifiLock?.isHeld == false) wifiLock?.acquire()
        } catch (e: Exception) {
            Log.w(TAG, "acquireStreamingLocks error: ${e.message}")
        }
    }

    private fun releaseStreamingLocks() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {}
        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (_: Exception) {}
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Monitor Service", NotificationManager.IMPORTANCE_LOW)
        )
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
        releaseStreamingLocks()
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
