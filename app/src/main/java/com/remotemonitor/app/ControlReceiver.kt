package com.remotemonitor.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

class ControlReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION = "com.remotemonitor.ACTION_CONTROL"
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
