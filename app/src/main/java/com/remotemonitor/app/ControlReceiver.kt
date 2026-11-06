package com.remotemonitor.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

class ControlReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION = "com.remotemonitor.ACTION_CONTROL"
        const val ACTION_CONFIG = "com.remotemonitor.ACTION_CONFIG"
        private const val TAG = "RM-Receiver"
    }
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.i(TAG, "Boot/update received, starting MonitorService")
                try {
                    ContextCompat.startForegroundService(context, Intent(context, MonitorService::class.java))
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start service on boot/replace", e)
                }
                MonitorService.scheduleWatchdogAlarm(context)
                return
            }
            MonitorService.ACTION_RESTART_SELF -> {
                // Doubles as both "the app got swiped away, bring the service back" (one-shot,
                // scheduled by MonitorService.onTaskRemoved) and the repeating 60s watchdog
                // heartbeat (scheduleWatchdogAlarm) — same action, same receiver, so either
                // trigger is handled uniformly here.
                val svc = MonitorService.instance
                if (svc == null) {
                    Log.i(TAG, "Watchdog: service not running, restarting it")
                    try {
                        ContextCompat.startForegroundService(context, Intent(context, MonitorService::class.java))
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to restart service", e)
                    }
                } else {
                    Log.i(TAG, "Watchdog: service alive, checking Ntfy SSE health")
                    svc.checkNtfyHealth()
                }
                MonitorService.scheduleWatchdogAlarm(context)
                return
            }
            ACTION_CONFIG -> {
                // Lets an ADB shell (or any trusted local caller) configure the app without
                // needing to open the UI, e.g.:
                // adb shell am broadcast -a com.remotemonitor.ACTION_CONFIG --es token "..." --es ntfy "..." --es mqtt "..."
                val token = intent.getStringExtra("token")
                val ntfy = intent.getStringExtra("ntfy")
                val mqtt = intent.getStringExtra("mqtt")
                Log.i(TAG, "ACTION_CONFIG received (token=${token != null}, ntfy=${ntfy != null}, mqtt=${mqtt != null})")
                AppConfig.save(
                    context,
                    token ?: AppConfig.token(context),
                    ntfy ?: AppConfig.ntfyTopic(context),
                    mqtt ?: AppConfig.mqttUrl(context)
                )
                val reloadIntent = Intent(context, MonitorService::class.java).apply {
                    action = MonitorService.ACTION_RELOAD_CONFIG
                }
                try {
                    ContextCompat.startForegroundService(context, reloadIntent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to apply ACTION_CONFIG", e)
                }
                return
            }
        }
        if (intent?.action != ACTION) return
        val raw = intent.getStringExtra("payload") ?: intent.getStringExtra("json") ?: return
        Log.i(TAG, "Broadcast payload received (${raw.length} chars)")
        val payload = ControlPayload.parse(raw) ?: run {
            Log.w(TAG, "Invalid broadcast payload")
            return
        }
        val svc = MonitorService.instance
        if (svc != null) {
            svc.applyPayload(payload)
        } else {
            val sIntent = Intent(context, MonitorService::class.java).apply {
                action = MonitorService.ACTION_APPLY
                putExtra(MonitorService.EXTRA_PAYLOAD, raw)
            }
            try { context.startForegroundService(sIntent) } catch (e: Exception) {
                Log.e(TAG, "Failed to start service for broadcast", e)
            }
        }
    }
}
