package com.fawads.ai.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import android.provider.ContactsContract
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.fawads.ai.R
import com.fawads.ai.ui.main.MainActivity

/**
 * Watches the phone state. On an incoming call it asks Fawad's AI to announce
 * the caller and gets a voice decision to accept/reject.
 */
class CallMonitorService : Service() {

    companion object {
        const val CHANNEL_ID = "fawads_call_channel"
        const val ACTION_CALL_ENDED = "com.fawads.CALL_ENDED"
    }

    private var telephonyManager: TelephonyManager? = null
    private var listener: PhoneStateListener? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        try {
            startForeground(101, buildNotification())
        } catch (e: Exception) {
            Log.e("CallMonitor", "startForeground failed", e)
            stopSelf()
            return
        }

        // Call monitoring needs READ_PHONE_STATE. If it isn't granted (yet, or
        // ever), just skip this feature instead of crashing the whole app.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w("CallMonitor", "READ_PHONE_STATE not granted, call announcing disabled")
            return
        }
        try {
            telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            listener = object : PhoneStateListener() {
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    handleState(state, phoneNumber ?: "")
                }
            }
            telephonyManager?.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
        } catch (e: SecurityException) {
            Log.e("CallMonitor", "listen() denied", e)
        }
    }

    private fun handleState(state: Int, number: String) {
        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                val name = resolveCallerName(number)
                val intent = Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(MainActivity.EXTRA_INCOMING_CALL, true)
                    .putExtra(MainActivity.EXTRA_CALLER_NAME, name)
                startActivity(intent)
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                sendBroadcast(Intent(ACTION_CALL_ENDED))
            }
            else -> {}
        }
    }

    private fun resolveCallerName(number: String): String {
        if (number.isBlank()) return "Unknown"
        try {
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            contentResolver.query(uri, projection, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val saved = (c.getString(1) ?: "").replace(" ", "")
                    val incoming = number.replace(" ", "")
                    if (saved.length >= 7 && incoming.contains(saved.takeLast(7)) && saved == incoming) {
                        return c.getString(0) ?: number
                    }
                    if (saved.length >= 7 && incoming.takeLast(7) == saved.takeLast(7)) {
                        return c.getString(0) ?: number
                    }
                }
            }
        } catch (_: Exception) {
        }
        return number
    }

    override fun onDestroy() {
        telephonyManager?.listen(listener, PhoneStateListener.LISTEN_NONE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Fawad's AI — Call Monitor",
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "Monitors incoming calls so Fawad's AI can announce them."
            (getSystemService(NotificationManager::class.java)).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Fawad's AI")
            .setContentText("Listening for incoming calls…")
            .setSmallIcon(R.drawable.ic_fawads_notif)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
