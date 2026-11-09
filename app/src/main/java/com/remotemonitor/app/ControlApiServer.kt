package com.remotemonitor.app

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit

class ControlApiServer(private val ctx: Context, port: Int) : NanoHTTPD("0.0.0.0", port) {
    companion object { private const val TAG = "RM-API" }

    private val recordingManager by lazy { RecordingManager(ctx) }

    // Short-timeout client dedicated to the self /api/lan_scan subnet probe — distinct from
    // MonitorService's sseClient, which is tuned for a long-lived relay connection instead.
    private val lanScanClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(500, TimeUnit.MILLISECONDS)
            .readTimeout(500, TimeUnit.MILLISECONDS)
            .build()
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri ?: ""
        val method = session.method
        val resp = try {
            when {
                // The standalone remote_controller.html is opened from a different origin (a
                // local file, or a different host entirely) than the phone's own IP:5000, so
                // browsers preflight non-trivial cross-origin requests with OPTIONS first.
                method == Method.OPTIONS -> newFixedLengthResponse(Response.Status.OK, "text/plain", "")
                method == Method.GET && (uri == "/" || uri == "/index.html") ->
                    newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", DashboardHtml.CONTENT)
                method == Method.POST && uri == "/api/control" -> handleControl(session)
                method == Method.GET && uri == "/api/status" -> handleStatus(session)
                method == Method.GET && uri == "/api/snapshot.jpg" -> handleSnapshot(session)
                method == Method.GET && uri == "/api/audio.wav" -> handleAudio(session)
                method == Method.GET && uri == "/api/mjpeg" -> handleMjpeg(session)
                method == Method.GET && uri == "/api/live_pack" -> handleLivePack(session)
                method == Method.POST && uri == "/api/talk" -> handleTalk(session)
                method == Method.GET && uri == "/api/recordings" -> handleRecordingsList(session)
                method == Method.GET && uri.startsWith("/api/recordings/") -> handleRecordingDownload(session, uri.removePrefix("/api/recordings/"))
                method == Method.DELETE && uri.startsWith("/api/recordings/") -> handleRecordingDelete(session, uri.removePrefix("/api/recordings/"))
                method == Method.GET && uri == "/health" -> handleHealth()
                method == Method.GET && uri == "/api/lan_scan" -> handleLanScan()
                else -> json(Response.Status.NOT_FOUND, """{"ok":false,"error":"not found"}""")
            }
        } catch (e: Exception) {
            Log.e(TAG, "serve error", e)
            json(Response.Status.INTERNAL_ERROR, """{"ok":false,"error":"${e.message?.replace("\"", "'")}"}""")
        }
        resp.addHeader("Access-Control-Allow-Origin", "*")
        resp.addHeader("Access-Control-Allow-Headers", "Authorization, Content-Type")
        resp.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        return resp
    }

    private fun handleControl(session: IHTTPSession): Response {
        val files = HashMap<String, String>()
        session.parseBody(files)
        val body = files["postData"] ?: ""
        val payload = ControlPayload.parse(body)
            ?: return json(Response.Status.BAD_REQUEST, """{"ok":false,"error":"invalid json"}""")
        if (!AppConfig.checkAuth(ctx, payload.authToken)) {
            Log.w(TAG, "401 invalid token (or cooldown active) on /api/control")
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
            return json(Response.Status.UNAUTHORIZED, """{"ok":false,"error":"unauthorized"}""")
        }
        val svc = MonitorService.instance
        val payload = svc?.statusJson() ?: MonitorBus.snapshot().apply { put("ok", true); put("service", "not_running") }
        return json(Response.Status.OK, payload.toString())
    }

    /** Latest JPEG snapshot pulled from the live camera session (no external RTSP player needed). */
    private fun handleSnapshot(session: IHTTPSession): Response {
        if (!isAuthorized(session)) {
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
            return json(Response.Status.UNAUTHORIZED, """{"ok":false,"error":"unauthorized"}""")
        }
        val streamer = MonitorService.instance?.webStreamer
            ?: return json(Response.Status.SERVICE_UNAVAILABLE, """{"ok":false,"error":"service not running"}""")
        val resp = newChunkedResponse(Response.Status.OK, "multipart/x-mixed-replace; boundary=frame", streamer.newMjpegStream())
        resp.addHeader("Cache-Control", "no-store")
        resp.addHeader("Connection", "keep-alive")
        return resp
    }

    /** Single-request JSON pack (snapshot + recent audio, both base64) for NAT-relay polling. */
    private fun handleLivePack(session: IHTTPSession): Response {
        if (!isAuthorized(session)) {
            return json(Response.Status.UNAUTHORIZED, """{"ok":false,"error":"unauthorized"}""")
        }
        val streamer = MonitorService.instance?.webStreamer
            ?: return json(Response.Status.SERVICE_UNAVAILABLE, """{"ok":false,"error":"service not running"}""")
        return json(Response.Status.OK, streamer.livePackJson().toString())
    }

    /** Zero-auth build: every endpoint is open to anyone who can reach the phone on the network. */
    private fun isAuthorized(session: IHTTPSession): Boolean = true

    /** Device identification for network scanners: anyone probing the subnet can tell RemoteMonitor phones apart. */
    private fun handleHealth(): Response {
        val svc = MonitorService.instance
        val body = (svc?.deviceIdentityJson() ?: JSONObject()).apply {
            put("ok", true)
            put("state", MonitorBus.state.name)
        }
        return json(Response.Status.OK, body.toString())
    }

    /**
     * Server-side subnet scan for sibling RemoteMonitor devices: probes 1..254 on the phone's
     * own /24 over port 5000/health in parallel. Bypasses browser Mixed-Content/Private Network
     * Access restrictions that would otherwise block a page served over HTTPS (or a different
     * origin) from reaching plain-HTTP LAN devices directly.
     */
    private fun handleLanScan(): Response {
        val svc = MonitorService.instance
            ?: return json(Response.Status.SERVICE_UNAVAILABLE, """{"ok":false,"error":"service not running"}""")
        val selfIp = svc.statusJson().optString("ip", "")
        val prefix = selfIp.substringBeforeLast('.', "")
        if (prefix.isBlank()) {
            return json(Response.Status.OK, """{"ok":true,"devices":[]}""")
        }
        val devices = runBlocking {
            (1..254).map { host ->
                async(Dispatchers.IO) { probeLanHost("$prefix.$host") }
            }.awaitAll().filterNotNull()
        }
        val arr = JSONArray()
        devices.forEach { arr.put(it) }
        return json(Response.Status.OK, JSONObject().apply { put("ok", true); put("devices", arr) }.toString())
    }

    private fun probeLanHost(ip: String): JSONObject? = try {
        val req = Request.Builder().url("http://$ip:5000/health").get().build()
        lanScanClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val obj = JSONObject(resp.body?.string() ?: return null)
            if (!obj.optBoolean("ok", false)) return null
            JSONObject().apply {
                put("ip", ip)
                put("device_model", obj.optString("device_model", ""))
                put("state", obj.optString("state", ""))
            }
        }
    } catch (_: Exception) { null }

    /** POST raw WAV/PCM bytes, played immediately through the speaker. */
    private fun handleTalk(session: IHTTPSession): Response {
        if (!isAuthorized(session)) {
            return json(Response.Status.UNAUTHORIZED, """{"ok":false,"error":"unauthorized"}""")
        }
        val svc = MonitorService.instance
            ?: return json(Response.Status.SERVICE_UNAVAILABLE, """{"ok":false,"error":"service not running"}""")
        val len = (session.headers["content-length"] ?: session.headers["Content-Length"])?.toIntOrNull() ?: 0
        if (len <= 0 || len > 5_000_000) {
            return json(Response.Status.BAD_REQUEST, """{"ok":false,"error":"invalid or oversized content length"}""")
        }
        val buf = ByteArray(len)
        var read = 0
        val input = session.inputStream
        while (read < len) {
            val n = input.read(buf, read, len - read)
            if (n <= 0) break
            read += n
        }
        svc.playTalkAudio(if (read == len) buf else buf.copyOf(read))
        return json(Response.Status.OK, """{"ok":true}""")
    }

    private fun handleRecordingsList(session: IHTTPSession): Response {
        if (!isAuthorized(session)) {
            return json(Response.Status.UNAUTHORIZED, """{"ok":false,"error":"unauthorized"}""")
        }
        val arr = JSONArray()
        recordingManager.listFiles().forEach { f ->
            arr.put(JSONObject().apply {
                put("name", f.name)
                put("size_bytes", f.length())
                put("modified_ms", f.lastModified())
            })
        }
        return json(Response.Status.OK, JSONObject().apply { put("ok", true); put("files", arr) }.toString())
    }

    private fun handleRecordingDownload(session: IHTTPSession, filename: String): Response {
        if (!isAuthorized(session)) {
            return json(Response.Status.UNAUTHORIZED, """{"ok":false,"error":"unauthorized"}""")
        }
        val f = recordingManager.safeFile(filename)
            ?: return json(Response.Status.NOT_FOUND, """{"ok":false,"error":"not found"}""")
        val resp = newFixedLengthResponse(Response.Status.OK, "video/mp4", f.inputStream(), f.length())
        resp.addHeader("Content-Disposition", "attachment; filename=\"${f.name}\"")
        resp.addHeader("Cache-Control", "no-store")
        return resp
    }

    private fun handleRecordingDelete(session: IHTTPSession, filename: String): Response {
        if (!isAuthorized(session)) {
            return json(Response.Status.UNAUTHORIZED, """{"ok":false,"error":"unauthorized"}""")
        }
        val ok = recordingManager.delete(filename)
        return json(if (ok) Response.Status.OK else Response.Status.NOT_FOUND, """{"ok":$ok}""")
    }

    private fun json(status: Response.Status, body: String): Response =
        newFixedLengthResponse(status, "application/json; charset=utf-8", body)
}
