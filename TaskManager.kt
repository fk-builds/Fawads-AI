package com.fawads.ai.util

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * Stores notes, alarms and reminders (JSON in SharedPreferences) and schedules
 * them with the system AlarmManager. Fired alarms are handled by [AlarmReceiver].
 */
object TaskManager {

    private const val PREF = "fawads_tasks"
    private const val KEY_NOTES = "notes"
    private const val KEY_ALARMS = "alarms"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    // ------------------------- NOTES -------------------------
    fun getNotes(context: Context): List<Note> {
        val raw = prefs(context).getString(KEY_NOTES, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Note(o.optString("text"), o.optLong("time"))
            }
        } catch (e: Exception) { emptyList() }
    }

    fun addNote(context: Context, text: String) {
        val notes = getNotes(context).toMutableList()
        notes.add(0, Note(text, System.currentTimeMillis()))
        prefs(context).edit().putString(KEY_NOTES, JSONArray().apply {
            notes.forEach {
                put(JSONObject().put("text", it.text).put("time", it.time))
            }
        }.toString()).apply()
    }

    fun deleteNote(context: Context, time: Long) {
        val notes = getNotes(context)
            .filterNot { it.time == time }
            .map { JSONObject().put("text", it.text).put("time", it.time) }
        prefs(context).edit().putString(KEY_NOTES, JSONArray(notes).toString()).apply()
    }

    // ------------------------- REMINDER / ALARM -------------------------
    /** Schedule a reminder at the given time; fires [AlarmReceiver] and speaks it. */
    fun scheduleReminder(context: Context, triggerAt: Long, message: String, id: Int) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, com.fawads.ai.service.AlarmReceiver::class.java)
            .putExtra("message", message)
            .putExtra("id", id)
        val pi = PendingIntent.getBroadcast(
            context, id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        } catch (e: Exception) {
            am.set(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }

    /** Schedule a countdown timer (relative to now). */
    fun scheduleTimer(context: Context, delayMs: Long, message: String, id: Int) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, com.fawads.ai.service.AlarmReceiver::class.java)
            .putExtra("message", message)
            .putExtra("id", id)
        val pi = PendingIntent.getBroadcast(
            context, id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val triggerAt = System.currentTimeMillis() + delayMs
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        } catch (e: Exception) {
            am.set(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }
}

data class Note(val text: String, val time: Long)
