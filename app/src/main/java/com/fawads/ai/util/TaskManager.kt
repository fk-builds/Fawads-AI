package com.fawads.ai.util

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.fawads.ai.service.AlarmReceiver
import org.json.JSONArray
import org.json.JSONObject

/** Persistent notes, tasks and system-scheduled reminders. */
object TaskManager {

    private const val PREF = "fawads_tasks"
    private const val KEY_NOTES = "notes"
    private const val KEY_TASKS = "task_items"

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
        } catch (_: Exception) { emptyList() }
    }

    fun addNote(context: Context, text: String) {
        val clean = text.trim()
        if (clean.isBlank()) return
        val notes = getNotes(context).toMutableList()
        notes.add(0, Note(clean, System.currentTimeMillis()))
        val arr = JSONArray()
        notes.forEach { arr.put(JSONObject().put("text", it.text).put("time", it.time)) }
        prefs(context).edit().putString(KEY_NOTES, arr.toString()).apply()
    }

    fun deleteNote(context: Context, time: Long) {
        val arr = JSONArray()
        getNotes(context).filterNot { it.time == time }.forEach {
            arr.put(JSONObject().put("text", it.text).put("time", it.time))
        }
        prefs(context).edit().putString(KEY_NOTES, arr.toString()).apply()
    }

    // ------------------------- TASKS -------------------------
    fun getTasks(context: Context): List<TaskItem> {
        val raw = prefs(context).getString(KEY_TASKS, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { index ->
                val item = arr.optJSONObject(index) ?: return@mapNotNull null
                TaskItem(
                    id = item.optLong("id"),
                    title = item.optString("title").trim(),
                    createdAt = item.optLong("createdAt"),
                    dueAt = item.optLong("dueAt").takeIf { it > 0L },
                    completed = item.optBoolean("completed", false)
                ).takeIf { it.title.isNotBlank() }
            }.sortedWith(compareBy<TaskItem> { it.completed }.thenBy { it.dueAt ?: Long.MAX_VALUE }.thenByDescending { it.createdAt })
        } catch (_: Exception) { emptyList() }
    }

    /** Create a task and, when a future due time is supplied, schedule its notification. */
    fun addTask(context: Context, title: String, dueAt: Long? = null): TaskItem? {
        val clean = title.trim()
        if (clean.isBlank()) return null
        val tasks = getTasks(context).toMutableList()
        val id = maxOf(System.currentTimeMillis(), (tasks.maxOfOrNull { it.id } ?: 0L) + 1L)
        val task = TaskItem(id, clean, System.currentTimeMillis(), dueAt?.takeIf { it > 0L }, false)
        tasks.add(task)
        saveTasks(context, tasks)
        task.dueAt?.takeIf { it > System.currentTimeMillis() }?.let {
            scheduleReminder(context, it, "Task reminder: ${task.title}", alarmId(task.id))
        }
        return task
    }

    fun setTaskCompleted(context: Context, taskId: Long, completed: Boolean) {
        val tasks = getTasks(context).map { item ->
            if (item.id == taskId) item.copy(completed = completed) else item
        }
        saveTasks(context, tasks)
        val task = tasks.firstOrNull { it.id == taskId } ?: return
        if (completed) {
            cancelReminder(context, alarmId(taskId))
        } else {
            task.dueAt?.takeIf { it > System.currentTimeMillis() }?.let {
                scheduleReminder(context, it, "Task reminder: ${task.title}", alarmId(task.id))
            }
        }
    }

    fun deleteTask(context: Context, taskId: Long) {
        cancelReminder(context, alarmId(taskId))
        saveTasks(context, getTasks(context).filterNot { it.id == taskId })
    }

    private fun saveTasks(context: Context, tasks: List<TaskItem>) {
        val arr = JSONArray()
        tasks.forEach { task ->
            arr.put(JSONObject()
                .put("id", task.id)
                .put("title", task.title)
                .put("createdAt", task.createdAt)
                .put("dueAt", task.dueAt ?: 0L)
                .put("completed", task.completed))
        }
        prefs(context).edit().putString(KEY_TASKS, arr.toString()).apply()
    }

    private fun alarmId(taskId: Long): Int = (taskId xor (taskId ushr 32)).toInt()

    private fun cancelReminder(context: Context, id: Int) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, AlarmReceiver::class.java)
        val pending = PendingIntent.getBroadcast(
            context, id, intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        ) ?: return
        am.cancel(pending)
        pending.cancel()
    }

    // ------------------------- REMINDER / ALARM -------------------------
    /** Schedule a reminder at the given time; fires AlarmReceiver and speaks it. */
    fun scheduleReminder(context: Context, triggerAt: Long, message: String, id: Int) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, AlarmReceiver::class.java)
            .putExtra("message", message)
            .putExtra("id", id)
        val pending = PendingIntent.getBroadcast(
            context, id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            }
        } catch (_: Exception) {
            am.set(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        }
    }

    /** Schedule a countdown timer (relative to now). */
    fun scheduleTimer(context: Context, delayMs: Long, message: String, id: Int) {
        scheduleReminder(context, System.currentTimeMillis() + delayMs, message, id)
    }
}

data class Note(val text: String, val time: Long)

data class TaskItem(
    val id: Long,
    val title: String,
    val createdAt: Long,
    val dueAt: Long? = null,
    val completed: Boolean = false
)
