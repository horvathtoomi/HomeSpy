package com.remotemonitor.app

import android.content.Context
import android.util.Log
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.net.URI
import java.util.UUID

class MqttWatcher(
    private val ctx: Context,
    private val onPayload: (ControlPayload) -> Unit
) {
    companion object { private const val TAG = "RM-Mqtt" }

    @Volatile private var running = false
    private var client: MqttAsyncClient? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    fun start() {
        running = true
        connect()
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
        try {
            client?.let {
                if (it.isConnected) it.disconnect()
                it.close()
            }
        } catch (_: Exception) {}
        client = null
    }

    fun reload() {
        stop()
        start()
    }

    /** Publishes a message to the given MQTT topic (e.g. a STATUS reply), if currently connected. */
    fun publish(topic: String, message: String) {
        try {
            val c = client
            if (c != null && c.isConnected) {
                c.publish(topic, MqttMessage(message.toByteArray(Charsets.UTF_8)).apply { qos = 1 })
            }
        } catch (e: Exception) {
            Log.w(TAG, "Status publish failed: ${e.message}")
        }
    }

    /** Publishes status JSON to remotemonitor/status */
    fun publishStatus(json: String) {
        publish("remotemonitor/status", json)
    }

    private fun connect() {
        if (!running) return
        var rawUrl = AppConfig.mqttUrl(ctx).trim()
        if (rawUrl.isBlank()) {
            handler.postDelayed({ connect() }, 10_000)
            return
        }
        rawUrl = when {
            rawUrl.startsWith("mqtts://", ignoreCase = true) -> "ssl://" + rawUrl.substring("mqtts://".length)
            rawUrl.startsWith("mqtt://", ignoreCase = true) -> "tcp://" + rawUrl.substring("mqtt://".length)
            else -> rawUrl
        }
        try {
            var username: String? = null
            var password: String? = null
            try {
                val uri = URI(rawUrl)
                val userInfo = uri.userInfo
                if (!userInfo.isNullOrBlank()) {
                    val parts = userInfo.split(":", limit = 2)
                    username = parts.getOrNull(0)
                    password = parts.getOrNull(1)
                    // uri.port is -1 when the URL omitted an explicit port (e.g. "ssl://user:pass@host"),
                    // which would otherwise produce an invalid "...:-1" address; fall back to the
                    // scheme's conventional MQTT port in that case.
                    val port = if (uri.port > 0) uri.port else if (uri.scheme.equals("ssl", ignoreCase = true)) 8883 else 1883
                    rawUrl = "${uri.scheme}://${uri.host}:${port}${uri.path ?: ""}"
                }
            } catch (e: Exception) {
                Log.w(TAG, "MQTT URL parse warning: ${e.message}")
            }

            val clientId = "RemoteMonitor_" + UUID.randomUUID().toString().substring(0, 8)
            val c = MqttAsyncClient(rawUrl, clientId, MemoryPersistence())
            client = c
            val opts = MqttConnectOptions().apply {
                isAutomaticReconnect = true
                isCleanSession = true
                connectionTimeout = 10
                keepAliveInterval = 30
                if (!username.isNullOrBlank()) userName = username
                if (password != null) this.password = password.toCharArray()
            }
            c.setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                    Log.i(TAG, "MQTT connected to $serverURI (reconnect=$reconnect)")
                    val topic = "remotemonitor/control"
                    try {
                        c.subscribe(topic, 1) { _, message ->
                            val body = String(message.payload, Charsets.UTF_8)
                            Log.i(TAG, "MQTT message received on $topic")
                            ControlPayload.parse(body)?.let { onPayload(it) }
                        }
                        Log.i(TAG, "Subscribed to $topic")
                    } catch (e: Exception) {
                        Log.e(TAG, "Subscribe error", e)
                    }
                }

                override fun connectionLost(cause: Throwable?) {
                    Log.w(TAG, "MQTT connection lost: ${cause?.message}")
                }

                override fun messageArrived(topic: String?, message: MqttMessage?) {}
                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            })
            c.connect(opts, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    Log.i(TAG, "MQTT connect success")
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    Log.w(TAG, "MQTT connect failed: ${exception?.message}")
                    if (running) handler.postDelayed({ connect() }, 10_000)
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "MQTT init error: ${e.message}")
            if (running) handler.postDelayed({ connect() }, 10_000)
        }
    }
}

