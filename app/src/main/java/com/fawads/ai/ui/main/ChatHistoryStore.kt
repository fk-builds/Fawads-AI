package com.fawads.ai.ui.main

import com.fawads.ai.util.Prefs
import org.json.JSONArray
import org.json.JSONObject

object ChatHistoryStore {
    private const val MAX_MESSAGES = 100

    fun load(prefs: Prefs): List<ChatMessage> {
        return try {
            val array = JSONArray(prefs.chatHistoryJson)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val text = item.optString("text").trim()
                if (text.isBlank()) return@mapNotNull null
                ChatMessage(
                    text = text,
                    isUser = item.optBoolean("isUser"),
                    timestamp = item.optLong("timestamp", System.currentTimeMillis())
                )
            }.takeLast(MAX_MESSAGES)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(prefs: Prefs, messages: List<ChatMessage>) {
        val array = JSONArray()
        messages.takeLast(MAX_MESSAGES).forEach { message ->
            array.put(JSONObject()
                .put("text", message.text)
                .put("isUser", message.isUser)
                .put("timestamp", message.timestamp))
        }
        prefs.chatHistoryJson = array.toString()
    }
}
