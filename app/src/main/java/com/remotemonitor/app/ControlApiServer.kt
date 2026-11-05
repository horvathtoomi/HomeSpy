package com.remotemonitor.app

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject

class ControlApiServer(private val ctx: Context, port: Int) : NanoHTTPD("0.0.0.0", port) {
    companion object { private const val TAG = "RM-API" }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri ?: ""
        val method = session.method
        return try {
            when {
                method == Method.POST && uri == "/api/control" -> handleControl(session)
                method == Method.GET && uri == "/api/status" -> handleStatus(session)
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
        MonitorService.instance?.applyPayload(payload)
            ?: return json(Response.Status.SERVICE_UNAVAILABLE, """{"ok":false,"error":"service not running"}""")
        val accepted = JSONObject().apply {
            put("ok", true)
            put("accepted", payload.action)
            put("state", MonitorBus.state.name)
        }
        return json(Response.Status.OK, accepted.toString())
    }

    private fun handleStatus(session: IHTTPSession): Response {
        val auth = session.headers["authorization"] ?: session.headers["Authorization"] ?: ""
        val token = auth.removePrefix("Bearer ").removePrefix("bearer ").trim()
        val qpToken = session.parameters["auth_token"]?.firstOrNull()
        if (!AppConfig.tokenMatches(ctx, token) && !AppConfig.tokenMatches(ctx, qpToken)) {
            MonitorBus.failedAuthCount++
            return json(Response.Status.UNAUTHORIZED, """{"ok":false,"error":"unauthorized"}""")
        }
        val svc = MonitorService.instance
        val payload = svc?.statusJson() ?: MonitorBus.snapshot().apply { put("ok", true); put("service", "not_running") }
        return json(Response.Status.OK, payload.toString())
    }

    private fun json(status: Response.Status, body: String): Response =
        newFixedLengthResponse(status, "application/json; charset=utf-8", body)
}
