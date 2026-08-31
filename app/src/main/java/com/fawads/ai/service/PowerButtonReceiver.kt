package com.fawads.ai.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Detects a double-press of the power button (SCREEN_OFF + SCREEN_ON within
 * 600 ms) and shows the floating orb overlay.
 */
class PowerButtonReceiver : BroadcastReceiver() {

    companion object {
        private const val DOUBLE_PRESS_MS = 600L
        @Volatile private var lastPress = 0L
    }

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SCREEN_OFF, Intent.ACTION_SCREEN_ON -> {
                val now = System.currentTimeMillis()
                val delta = now - lastPress
                lastPress = now
                if (delta in 1..DOUBLE_PRESS_MS) showOverlay(context)
            }
        }
    }

    private fun showOverlay(context: Context) {
        try {
            val intent = Intent(context, FawadsOverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        } catch (e: Exception) {
            Log.e("PowerButton", "overlay start failed", e)
        }
    }
}
