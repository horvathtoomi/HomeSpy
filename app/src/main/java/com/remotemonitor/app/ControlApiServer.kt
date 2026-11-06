package com.remotemonitor.app

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.ByteArrayInputStream

class ControlApiServer(private val ctx: Context, port: Int) : NanoHTTPD("0.0.0.0", port) {
    companion object { private const val TAG = "RM-API" }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri ?: ""
        val method = session.method
        return try {
            when {
                method == Method.GET && (uri == "/" || uri == "/index.html") ->
                    newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", DashboardHtml.CONTENT)
                method == Method.POST && uri == "/api/control" -> handleControl(session)
                method == Method.GET && uri == "/api/status" -> handleStatus(session)
                method == Method.GET && uri == "/api/snapshot.jpg" -> handleSnapshot(session)
                method == Method.GET && uri == "/api/audio.wav" -> handleAudio(session)
                method == Method.GET && uri == "/api/mjpeg" -> handleMjpeg(session)
                method == Method.GET && uri == "/health" -> newFixedLengthResponse(Response.Status.OK, "application/json", """{"ok":true}""")
                else -> json(Response.Status.NOT_FOUND, """{"ok":false,"error":"not found"}""")
            }
        } catch (e: Exception) {
            Log.e(TAG, "serve error", e)
            json(Response.Status.INTERNAL_ERROR, """{"ok":false,"error":"${e.message?.replace("\"", "'")}"}""")
        }
    }

    private fun handleControl(session: IHTTPSession): Response {
        val files = HashMap<String, String>()
        session.parseBody(files)
        val body = files["postData"] ?: ""
        val payload = ControlPayload.parse(body)
            ?: return json(Response.Status.BAD_REQUEST, """{"ok":false,"error":"invalid json"}""")
        if (!AppConfig.tokenMatches(ctx, payload.authToken)) {
            MonitorBus.failedAuthCount++
            Log.w(TAG, "401 invalid token on /api/control")
            return json(Response.Status.UNAUTHORIZED, """{"ok":false,"error":"unauthorized"}""")
        }
        val svc = MonitorService.instance
            ?: return json(Response.Status.SERVICE_UNAVAILABLE, """{"ok":false,"error":"service not running"}""")
        runBlocking {
            withTimeoutOrNull(4000) { svc.applyPayloadSync(payload) }
        }
        val accepted = JSONObject().apply {
            put("ok", true)
            put("accepted", payload.action)
            put("state", MonitorBus.state.name)
            put("torch_active", MonitorBus.torchActive)
            put("camera", MonitorBus.activePayload?.camera ?: "")
        }
        return json(Response.Status.OK, accepted.toString())
    }

    private fun handleStatus(session: IHTTPSession): Response {
        if (!isAuthorized(session)) {
            MonitorBus.failedAuthCount++
            return json(Response.Status.UNAUTHORIZED, """{"ok":false,"error":"unauthorized"}""")
        }
        val svc = MonitorService.instance
        val payload = svc?.statusJson() ?: MonitorBus.snapshot().apply { put("ok", true); put("service", "not_running") }
        return json(Response.Status.OK, payload.toString())
    }

    /** Latest JPEG snapshot pulled from the live camera session (no external RTSP player needed). */
    private fun handleSnapshot(session: IHTTPSession): Response {
        if (!isAuthorized(session)) {
            MonitorBus.failedAuthCount++
            return json(Response.Status.UNAUTHORIZED, """{"ok":false,"error":"unauthorized"}""")
        }
        val jpeg = MonitorService.instance?.webStreamer?.snapshotBytes()
            ?: return json(Response.Status.NOT_FOUND, """{"ok":false,"error":"no snapshot available"}""")
        val resp = newFixedLengthResponse(
            Response.Status.OK, "image/jpeg", ByteArrayInputStream(jpeg), jpeg.size.toLong()
        )
        resp.addHeader("Cache-Control", "no-store")
        return resp
    }

    /** Continuous raw-PCM-as-WAV stream of the mic, playable directly in a browser <audio> tag. */
    private fun handleAudio(session: IHTTPSession): Response {
        if (!isAuthorized(session)) {
            MonitorBus.failedAuthCount++
            return json(Response.Status.UNAUTHORIZED, """{"ok":false,"error":"unauthorized"}""")
        }
        val streamer = MonitorService.instance?.webStreamer
            ?: return json(Response.Status.SERVICE_UNAVAILABLE, """{"ok":false,"error":"service not running"}""")
        val resp = newChunkedResponse(Response.Status.OK, "audio/wav", streamer.newAudioStream())
        resp.addHeader("Cache-Control", "no-store")
        resp.addHeader("Connection", "keep-alive")
        return resp
    }

    /** multipart/x-mixed-replace MJPEG stream for clients that want continuous video instead of polled snapshots. */
    private fun handleMjpeg(session: IHTTPSession): Response {
        if (!isAuthorized(session)) {
            MonitorBus.failedAuthCount++
            return json(Response.Status.UNAUTHORIZED, """{"ok":false,"error":"unauthorized"}""")
        }
        val streamer = MonitorService.instance?.webStreamer
            ?: return json(Response.Status.SERVICE_UNAVAILABLE, """{"ok":false,"error":"service not running"}""")
        val resp = newChunkedResponse(Response.Status.OK, "multipart/x-mixed-replace; boundary=frame", streamer.newMjpegStream())
        resp.addHeader("Cache-Control", "no-store")
        resp.addHeader("Connection", "keep-alive")
        return resp
    }

    private fun isAuthorized(session: IHTTPSession): Boolean {
        val auth = session.headers["authorization"] ?: session.headers["Authorization"] ?: ""
        val token = auth.removePrefix("Bearer ").removePrefix("bearer ").trim()
        val qpToken = session.parameters["auth_token"]?.firstOrNull()
        return AppConfig.tokenMatches(ctx, token) || AppConfig.tokenMatches(ctx, qpToken)
    }

    private fun json(status: Response.Status, body: String): Response =
        newFixedLengthResponse(status, "application/json; charset=utf-8", body)
}
