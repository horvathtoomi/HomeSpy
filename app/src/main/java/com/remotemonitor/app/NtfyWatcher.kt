package com.remotemonitor.app

import android.content.Context
import android.util.Log
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.min

class NtfyWatcher(
    private val ctx: Context,
    private val client: OkHttpClient,
    private val onPayload: (ControlPayload) -> Unit
) {
    companion object {
        private const val TAG = "RM-Ntfy"
        private const val KEY_LAST_EVENT_ID = "ntfy_last_event_id"
    }

    @Volatile private var running = false
    @Volatile private var source: EventSource? = null
    @Volatile private var connected = false
    private var backoffMs = 1000L
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    fun start() {
        running = true
        connect()
    }

    fun stop() {
        running = false
        connected = false
        source?.cancel()
        source = null
        handler.removeCallbacksAndMessages(null)
    }

    /** True while the SSE connection is currently open; used by the watchdog alarm to detect a silently-dead connection and force a reload. */
    fun isConnected(): Boolean = connected

    fun reload() {
        stop()
        backoffMs = 1000L
        start()
    }

    /** Publishes a plain-text message to the configured ntfy topic (e.g. a STATUS reply). */
    fun publish(message: String) {
        val topic = AppConfig.ntfyTopic(ctx)
        if (topic.isBlank()) return
        val url = "https://ntfy.sh/$topic"
        val body = message.toRequestBody("text/plain; charset=utf-8".toMediaType())
        val req = Request.Builder().url(url).post(body).build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.w(TAG, "Status publish failed: ${e.message}")
            }
            override fun onResponse(call: Call, response: Response) {
                response.close()
            }
        })
    }

    /** Publishes JSON status to https://ntfy.sh/${topic}-status */
    fun publishStatus(json: String) {
        val topic = AppConfig.ntfyTopic(ctx).trim()
        if (topic.isBlank()) return
        val url = "https://ntfy.sh/${topic}-status"
        val body = json.toRequestBody("application/json; charset=utf-8".toMediaType())
        val req = Request.Builder().url(url).post(body).build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.w(TAG, "Status publish to $url failed: ${e.message}")
            }
            override fun onResponse(call: Call, response: Response) {
                response.close()
            }
        })
    }

    /** Uploads a JPEG snapshot as an ntfy attachment to https://ntfy.sh/${topic}-status (PUT with raw bytes). */
    fun publishSnapshot(jpeg: ByteArray) {
        val topic = AppConfig.ntfyTopic(ctx).trim()
        if (topic.isBlank()) return
        val url = "https://ntfy.sh/${topic}-status"
        val body = jpeg.toRequestBody("image/jpeg".toMediaType())
        val req = Request.Builder()
            .url(url)
            .put(body)
            .header("Filename", "snapshot.jpg")
            .header("Title", "Snapshot")
            .build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.w(TAG, "Snapshot publish failed: ${e.message}")
            }
            override fun onResponse(call: Call, response: Response) {
                response.close()
            }
        })
    }

    private fun lastEventId(): String? = AppConfig.prefs(ctx).getString(KEY_LAST_EVENT_ID, null)

    private fun saveLastEventId(id: String) {
        AppConfig.prefs(ctx).edit().putString(KEY_LAST_EVENT_ID, id).apply()
    }

    private fun connect() {
        if (!running) return
        val topic = AppConfig.ntfyTopic(ctx)
        if (topic.isBlank()) {
            handler.postDelayed({ connect() }, 5000)
            return
        }
        // Resume from the last message we actually processed instead of always "now": if the
        // phone was asleep or the service got restarted, a START sent in the gap would
        // otherwise be lost forever. Falling back to "2m" bounds the catch-up window on a
        // completely fresh install (no saved id yet).
        val since = lastEventId()?.takeIf { it.isNotBlank() } ?: "2m"
        val url = "https://ntfy.sh/${topic}/sse?since=$since"
        Log.i(TAG, "Connecting SSE: $url")
        val req = Request.Builder().url(url).header("Accept", "text/event-stream").build()
        val factory = EventSources.createFactory(client)
        source = factory.newEventSource(req, object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                Log.i(TAG, "SSE open")
                connected = true
                backoffMs = 1000L
            }
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (data.isBlank()) return
                try {
                    val obj = JSONObject(data)
                    val msgId = obj.optString("id", "")
                    if (msgId.isNotBlank()) saveLastEventId(msgId)
                    val event = obj.optString("event", "message")
                    if (event != "message") return
                    val message = obj.optString("message", "")
                    val payloadStr = if (message.trim().startsWith("{")) message else obj.optString("payload", message)
                    val payload = ControlPayload.parse(payloadStr)
                    if (payload != null) {
                        Log.i(TAG, "Ntfy payload action=${payload.action}")
                        onPayload(payload)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "SSE parse error: ${e.message}")
                }
            }
            override fun onClosed(eventSource: EventSource) {
                connected = false
                scheduleReconnect("closed")
            }
            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                connected = false
                Log.w(TAG, "SSE failure: ${t?.message} code=${response?.code}")
                scheduleReconnect("failure")
            }
        })
    }

    private fun scheduleReconnect(reason: String) {
        if (!running) return
        // onClosed() and onFailure() can both fire for the same EventSource (e.g. a failure
        // that immediately triggers a close); without clearing pending callbacks first, that
        // piles up duplicate reconnect timers which would eventually open multiple concurrent
        // SSE connections to the same topic.
        handler.removeCallbacksAndMessages(null)
        Log.i(TAG, "Reconnect in ${backoffMs}ms ($reason)")
        handler.postDelayed({
            backoffMs = min(backoffMs * 2, 60_000L)
            connect()
        }, backoffMs)
    }
}
