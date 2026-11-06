package com.remotemonitor.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.security.MessageDigest

object AppConfig {
    const val PREFS = "rm_prefs"
    const val KEY_TOKEN = "auth_token"
    const val KEY_NTFY = "ntfy_topic"
    const val KEY_MQTT = "mqtt_url"
    const val DEFAULT_TOKEN = "changeme-secret-token"

    fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun token(ctx: Context): String =
        prefs(ctx).getString(KEY_TOKEN, DEFAULT_TOKEN)?.ifBlank { DEFAULT_TOKEN } ?: DEFAULT_TOKEN

    /**
     * Returns the configured ntfy topic, or generates and persists a deterministic per-device
     * default (derived from ANDROID_ID) the first time it's read blank. Remote control must
     * work immediately after install without the user having to open the app and type a topic
     * in first — a device sitting headless behind NAT has no other way to receive a start signal.
     */
    fun ntfyTopic(ctx: Context): String {
        val stored = prefs(ctx).getString(KEY_NTFY, null)
        if (!stored.isNullOrBlank()) return stored
        val androidId = try {
            android.provider.Settings.Secure.getString(ctx.contentResolver, android.provider.Settings.Secure.ANDROID_ID)
        } catch (_: Exception) { null }
        val suffix = androidId?.takeLast(6)?.ifBlank { null } ?: "a40home"
        val generated = "rm-a40-$suffix"
        prefs(ctx).edit().putString(KEY_NTFY, generated).apply()
        return generated
    }
    fun mqttUrl(ctx: Context): String = prefs(ctx).getString(KEY_MQTT, "") ?: ""

    fun save(ctx: Context, token: String, ntfy: String, mqtt: String) {
        prefs(ctx).edit()
            .putString(KEY_TOKEN, token.ifBlank { DEFAULT_TOKEN })
            .putString(KEY_NTFY, ntfy.trim())
            .putString(KEY_MQTT, mqtt.trim())
            .apply()
    }

    fun sha256(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    fun tokenMatches(ctx: Context, provided: String?): Boolean {
        if (provided.isNullOrBlank()) return false
        val configured = token(ctx)
        if (provided == configured) return true
        return provided.equals(sha256(configured), ignoreCase = true)
    }
}

enum class MonitorState { IDLE, STARTING, STREAMING, STOPPING }

data class ControlPayload(
    val authToken: String = "",
    val action: String = "STATUS",
    val mode: String = "BOTH",
    val camera: String = "BACK",
    val videoResolution: String = "720p",
    val durationSeconds: Long = 0,
    val streamTarget: String = "",
    val torch: Boolean = false,
    val videoBitrateKbps: Int = 1500,
    val fps: Int = 30,
    val callbackUrl: String = "",
    val ntfyPushSnapshots: Boolean = false,
    val snapshotIntervalMs: Long = 1000
) {
    companion object {
        fun fromJson(obj: JSONObject): ControlPayload {
            val rawBitrate = if (obj.has("video_bitrate_kbps")) {
                obj.optInt("video_bitrate_kbps", 1500)
            } else {
                obj.optInt("videoBitrateKbps", 1500)
            }
            val bitrateClamped = rawBitrate.coerceIn(200, 8000)

            val rawFps = obj.optInt("fps", 30)
            val fpsClamped = rawFps.coerceIn(10, 60)

            val rawSnapshotInterval = obj.optLong("snapshot_interval_ms", 1000)
            val snapshotIntervalClamped = rawSnapshotInterval.coerceIn(200, 60_000)

            return ControlPayload(
                authToken = obj.optString("auth_token", ""),
                action = obj.optString("action", "STATUS").uppercase(),
                mode = obj.optString("mode", "BOTH").uppercase(),
                camera = obj.optString("camera", "BACK").uppercase(),
                videoResolution = obj.optString("video_Resolution", obj.optString("video_resolution", "720p")),
                durationSeconds = obj.optLong("duration_seconds", 0),
                streamTarget = obj.optString("stream_target", ""),
                torch = obj.optBoolean("torch", false),
                videoBitrateKbps = bitrateClamped,
                fps = fpsClamped,
                callbackUrl = obj.optString("callback_url", ""),
                ntfyPushSnapshots = obj.optBoolean("ntfy_push_snapshots", false),
                snapshotIntervalMs = snapshotIntervalClamped
            )
        }

        fun parse(raw: String): ControlPayload? = try {
            fromJson(JSONObject(raw))
        } catch (_: Exception) { null }
    }
}

/** Shared video resolution mapping, used both for the RootEncoder stream and the DirectWebStreamer snapshot capture. */
fun resolveVideoResolution(res: String): Pair<Int, Int> = when (res.lowercase()) {
    "1080p", "1920x1080" -> 1920 to 1080
    "480p", "854x480" -> 854 to 480
    else -> 1280 to 720
}

object MonitorBus {
    @Volatile var state: MonitorState = MonitorState.IDLE
    @Volatile var activePayload: ControlPayload? = null
    @Volatile var startedAtMs: Long = 0L
    @Volatile var endsAtMs: Long = 0L
    @Volatile var lastError: String = ""
    @Volatile var streamUrl: String = ""
    @Volatile var failedAuthCount: Int = 0
    @Volatile var torchActive: Boolean = false
    // Tracks MainActivity's actual onResume()/onPause() lifecycle, so a service-triggered
    // stealth wake can wait for the activity to genuinely be in the foreground (not just
    // "startActivity() was called") before opening the camera on a cold wake from deep sleep.
    @Volatile var activityResumed: Boolean = false
    var onStateChanged: ((MonitorState) -> Unit)? = null

    fun snapshot(): JSONObject = JSONObject().apply {
        put("state", state.name)
        put("active_mode", activePayload?.mode ?: "")
        put("active_camera", activePayload?.camera ?: "")
        put("stream_url", streamUrl)
        put("started_at_ms", startedAtMs)
        put("ends_at_ms", endsAtMs)
        put("remaining_seconds", remainingSeconds())
        put("last_error", lastError)
        put("failed_auth_count", failedAuthCount)
        put("torch_active", torchActive)
        put("video_bitrate_kbps", activePayload?.videoBitrateKbps ?: 1500)
        put("fps", activePayload?.fps ?: 30)
    }

    fun remainingSeconds(): Long {
        if (state != MonitorState.STREAMING || endsAtMs <= 0L) return 0L
        return ((endsAtMs - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L)
    }

    fun update(newState: MonitorState) {
        state = newState
        onStateChanged?.invoke(newState)
    }
}
