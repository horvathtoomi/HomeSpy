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

    fun ntfyTopic(ctx: Context): String = prefs(ctx).getString(KEY_NTFY, "") ?: ""
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
    val streamTarget: String = ""
) {
    companion object {
        fun fromJson(obj: JSONObject): ControlPayload = ControlPayload(
            authToken = obj.optString("auth_token", ""),
            action = obj.optString("action", "STATUS").uppercase(),
            mode = obj.optString("mode", "BOTH").uppercase(),
            camera = obj.optString("camera", "BACK").uppercase(),
            videoResolution = obj.optString("video_Resolution", obj.optString("video_resolution", "720p")),
            durationSeconds = obj.optLong("duration_seconds", 0),
            streamTarget = obj.optString("stream_target", "")
        )
        fun parse(raw: String): ControlPayload? = try {
            fromJson(JSONObject(raw))
        } catch (_: Exception) { null }
    }
}

object MonitorBus {
    @Volatile var state: MonitorState = MonitorState.IDLE
    @Volatile var activePayload: ControlPayload? = null
    @Volatile var startedAtMs: Long = 0L
    @Volatile var endsAtMs: Long = 0L
    @Volatile var lastError: String = ""
    @Volatile var streamUrl: String = ""
    @Volatile var failedAuthCount: Int = 0
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
