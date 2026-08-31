package com.fawads.ai.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.fawads.ai.R
import com.fawads.ai.util.SpeechHelper

/**
 * Fires when an alarm, timer or reminder goes off. Shows a notification and
 * speaks the message aloud using TextToSpeech (works even offline).
 */
class AlarmReceiver : BroadcastReceiver() {

    companion object {
        const val CHANNEL_ID = "fawads_alarm_channel"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val message = intent.getStringExtra("message") ?: "Time's up!"
        createChannel(context)

        val nm = context.getSystemService(NotificationManager::class.java)
        val notification: Notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Fawad's AI")
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_fawads_notif)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        nm.notify(intent.getIntExtra("id", 1001), notification)

        // Speak aloud.
        SpeechHelper(context).speak(message)
    }

    private fun createChannel(context: Context) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Fawad's AI — Alarms",
                NotificationManager.IMPORTANCE_HIGH
            )
            channel.description = "Alarm, timer and reminder alerts."
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
