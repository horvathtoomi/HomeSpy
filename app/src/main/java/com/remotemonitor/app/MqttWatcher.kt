package com.remotemonitor.app

import android.content.Context
import android.util.Log
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
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

    private fun connect() {
        if (!running) return
        val rawUrl = AppConfig.mqttUrl(ctx).trim()
        if (rawUrl.isBlank()) {
            handler.postDelayed({ connect() }, 10_000)
            return
        }
        try {
            val clientId = "RemoteMonitor_" + UUID.randomUUID().toString().substring(0, 8)
            val c = MqttAsyncClient(rawUrl, clientId, MemoryPersistence())
            client = c
            val opts = MqttConnectOptions().apply {
                isAutomaticReconnect = true
                isCleanSession = true
                connectionTimeout = 10
                keepAliveInterval = 30
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

