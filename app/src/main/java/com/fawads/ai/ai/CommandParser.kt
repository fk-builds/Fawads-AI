package com.fawads.ai.ai

import com.fawads.ai.model.AppCommand
import com.fawads.ai.model.CommandType

/**
 * Parses transcribed speech (Hinglish + English) into a structured [AppCommand].
 * Returns null when the utterance is conversational and should be handled by the LLM.
 */
object CommandParser {

    private val appTokens = listOf(
        "youtube", "whatsapp", "instagram", "facebook", "chrome", "gmail", "maps",
        "spotify", "netflix", "twitter", "x", "telegram", "snapchat", "settings",
        "calculator", "calendar", "clock", "phone", "contacts", "play store",
        "amazon", "flipkart", "paytm", "phonepe", "gpay", "google pay", "zoom",
        "meet", "teams", "tiktok", "discord", "linkedin", "camera", "gallery",
        "photos", "messages", "sms", "files", "chrome", "browser", "music"
    )

    fun parse(text: String): AppCommand? {
        val t = text.trim().lowercase()
        if (t.isBlank()) return null

        openApp(t)?.let { return it }
        closeApp(t)?.let { return it }
        primeCall(t)?.let { return it }
        primeMsg(t)?.let { return it }
        call(t)?.let { return it }
        sms(t)?.let { return it }
        whatsapp(t)?.let { return it }

        if (hasAny(t, "volume up", "volume badhao", "awaz badhao", "aawaz badhao", "volume barhao", "aawaz barhao", "sound barhao", "sound up", "louder", "tez")) return AppCommand(CommandType.VOLUME_UP)
        if (hasAny(t, "volume down", "volume kam", "awaz kam", "aawaz kam", "sound kam", "thoda sa kam", "quiet", "slow the volume")) return AppCommand(CommandType.VOLUME_DOWN)

        if (flashlight(t, on = true)) return AppCommand(CommandType.FLASHLIGHT_ON)
        if (flashlight(t, on = false)) return AppCommand(CommandType.FLASHLIGHT_OFF)

        if (wifi(t, on = true)) return AppCommand(CommandType.WIFI_ON)
        if (wifi(t, on = false)) return AppCommand(CommandType.WIFI_OFF)

        if (bluetooth(t, on = true)) return AppCommand(CommandType.BLUETOOTH_ON)
        if (bluetooth(t, on = false)) return AppCommand(CommandType.BLUETOOTH_OFF)

        if (hasAny(t, "mute", "chup", "mute karo", "mute mode", "stop mic", "mute kar do")) return AppCommand(CommandType.MUTE)
        if (hasAny(t, "unmute", "mute hat", "mute hata", "awaz wapas")) return AppCommand(CommandType.UNMUTE)
        if (hasAny(t, "stop", "ruko", "band karo", "chup ho jao", "silence", "halt")) return AppCommand(CommandType.STOP)

        extended(t)?.let { return it }

        return null
    }

    // ---------------- EXTENDED (alarm/timer/notes/info/music/search) ----------------
    private fun extended(t: String): AppCommand? {
        // Weather
        if (hasAny(t, "weather", "mausam", "mausum", "temperature batao", "baarish kaisa")) return AppCommand(CommandType.WEATHER)
        // News
        if (hasAny(t, "news", "khabar", "khabrein", "headlines", "taaza khabar")) {
            val topic = extractAfter(t, listOf("news", "khabar", "khabrein", "about", "ka", "ke"))
            return AppCommand(CommandType.NEWS, mapOf("topic" to (topic ?: "top stories")))
        }
        // Crypto / currency
        if (hasAny(t, "bitcoin", "btc", "ethereum", "doge", "crypto", "litecoin")) {
            val coin = extractCoin(t)
            return AppCommand(CommandType.CRYPTO, mapOf("coin" to coin))
        }
        // Timer
        if (hasAny(t, "timer", "countdown", "maaloom time", "kitni der")) {
            val mins = extractMin(t) ?: 1
            return AppCommand(CommandType.TIMER, mapOf("minutes" to mins))
        }
        // Alarm
        if (hasAny(t, "alarm", "alarm lagao", "alarm set", "par jagao", "per jagao")) {
            val time = extractTime(t)
            return AppCommand(CommandType.ALARM, mapOf("time" to (time ?: "")))
        }
        // Reminder
        if (hasAny(t, "remind", "reminder", "yaad dilana", "yaad dilao", "remember to", "yaad rakhna", "yar dilana")) {
            val msg = extractReminderMsg(t)
            val time = extractTime(t)
            return AppCommand(CommandType.REMINDER, mapOf("message" to (msg ?: "Reminder"), "time" to (time ?: "")))
        }
        // Notes
        if (hasAny(t, "open notes", "notes dikhao", "meri notes", "my notes", "notes kholo")) return AppCommand(CommandType.OPEN_NOTES)
        if (hasAny(t, "note down", "add a note", "note karo", "likh lo", "note likh", "note banao", "yaad rakhna note")) {
            val msg = extractNote(t)
            return AppCommand(CommandType.ADD_NOTE, mapOf("note" to (msg ?: "")))
        }
        // Play music (avoid "play store")
        if (hasAny(t, "chalao", "play song", "gaana chalao", "song chalao", "music chalao", "play music", "sunao gaana", "song play")) {
            if (hasAny(t, "play store", "playstore")) return null
            val song = extractAfter(t, listOf("play", "chalao", "song", "gaana", "music", "sunao"))
            return AppCommand(CommandType.PLAY_MUSIC, mapOf("query" to (song ?: "")))
        }
        // YouTube search
        if (hasAny(t, "youtube pe", "youtube search", "youtube pe dikhao", "chhodo youtube")) {
            val q = extractAfter(t, listOf("youtube", "search", "dikhao", "chhodo"))
            return AppCommand(CommandType.SEARCH_YOUTUBE, mapOf("query" to (q ?: "")))
        }
        // Web search / Google
        if (hasAny(t, "google karo", "search karo", "google search", "search the web", "google pe dhoondo", "dhundo")) {
            val q = extractAfter(t, listOf("google", "search", "dhoondo", "dhundo", "karo"))
            return AppCommand(CommandType.SEARCH_WEB, mapOf("query" to (q ?: "")))
        }
        return null
    }

    private fun extractTime(t: String): String? {
        val m = Regex("""(\d{1,2})[:\s](\d{2})""").find(t) ?: return null
        return "${m.groupValues[1]}:${m.groupValues[2]}"
    }

    private fun extractMin(t: String): String {
        val m = Regex("""(\d+)\s*(?:min|minute|minutes|second|second|sec|seconds)""").find(t)
        return m?.groupValues?.get(1) ?: "1"
    }

    private fun extractCoin(t: String): String = when {
        t.contains("btc") || t.contains("bitcoin") -> "bitcoin"
        t.contains("eth") || t.contains("ethereum") -> "ethereum"
        t.contains("doge") || t.contains("dogecoin") -> "dogecoin"
        t.contains("ltc") || t.contains("litecoin") -> "litecoin"
        else -> "bitcoin"
    }

    private fun extractReminderMsg(t: String): String {
        val after = t
            .replace(Regex("""\b(remind|reminder|remember|yaad dilana|yaad dilao|yaad rakhna|me|to|ki|ko|mujhe|sorry|dilana)\b"""), " ")
            .trim()
        return after.take(60)
    }

    private fun extractNote(t: String): String {
        return t
            .replace(Regex("""\b(note down|add a note|note karo|note likh|note banao|likh lo|yaad rakhna|note)\b"""), " ")
            .trim()
            .trimStart(',', ':', '-')
            .take(120)
    }

    private fun extractAfter(t: String, keys: List<String>): String {
        var cleaned = t
        for (k in keys) cleaned = cleaned.replace(k, " ")
        return cleaned.replace(Regex("""\s+"""), " ")
            .trim()
            .trimStart(',', ':', '-', ' ')
            .trim()
            .replace(Regex("""\b(karo|karna|chalao|dikhao|kholo|hai|plz|please|para|par)\b"""), "")
            .take(80)
    }

    // ---------------- OPEN / CLOSE APP ----------------
    private fun openApp(t: String): AppCommand? {
        val openHint = hasAny(t, "kholo", "khol", "open", "launch", "kholo na", "shuru karo", "open karo", "ong karo", "ongo")
        if (!openHint) return null
        val app = findAppName(t) ?: return null
        return AppCommand(CommandType.OPEN_APP, mapOf("app_name" to app))
    }

    private fun closeApp(t: String): AppCommand? {
        val closeHint = hasAny(t, "band karo", "close", "band kar", "bnd karo", "exit", "kholo band", "band kro")
        if (!closeHint) return null
        val app = findAppName(t)
        return AppCommand(CommandType.CLOSE_APP, app?.let { mapOf("app_name" to it) } ?: emptyMap())
    }

    private fun findAppName(t: String): String? {
        for (token in appTokens) {
            if (t.contains(token)) return token
        }
        return null
    }

    // ---------------- PRIME CONTACTS ----------------
    private fun primeCall(t: String): AppCommand? {
        if (!hasAny(t, "close friend", "close one", "messenger friend", "mera close", "best friend", "mona friend", "mere close", "my close", "dushman")) return null
        if (hasAny(t, "message", "msg", "whatsapp", "sms", "bhejo")) return null
        return AppCommand(CommandType.PRIME_CALL, mapOf("index" to primeIndex(t)))
    }

    private fun primeMsg(t: String): AppCommand? {
        if (!hasAny(t, "close friend", "close one", "best friend", "my love", "meri jaan", "jaan", "mere close", "my close")) return null
        if (!hasAny(t, "message", "msg", "whatsapp", "sms", "bhejo", "send")) return null
        return AppCommand(CommandType.PRIME_MSG, mapOf("index" to primeIndex(t)))
    }

    private fun primeIndex(t: String): String {
        return when {
            containsAny(t, "second", "dusra", "doosra", "2") -> "1"
            containsAny(t, "third", "teesra", "3") -> "2"
            else -> "0"
        }
    }

    // ---------------- CALL / SMS / WHATSAPP ----------------
    private fun call(t: String): AppCommand? {
        if (!hasAny(t, "call", "call karo", "ko phone karo", "phone karo", "ko call", "phone lagao", "call karna")) return null
        if (hasAny(t, "message", "msg", "whatsapp", "sms", "bhejo")) return null
        val target = extractContact(t) ?: return null
        if (target.isBlank()) return null
        return AppCommand(CommandType.CALL, mapOf("name" to target))
    }

    private fun sms(t: String): AppCommand? {
        if (!hasAny(t, "sms", "message", "msg bhejo", "text", "message bhejo", "sms bhejo", "msg karo")) return null
        if (hasAny(t, "whatsapp")) return null
        val target = extractContact(t) ?: return null
        if (target.isBlank()) return null
        val message = extractMessage(t) ?: ""
        return AppCommand(CommandType.SMS, mapOf("name" to target, "message" to message))
    }

    private fun whatsapp(t: String): AppCommand? {
        val isWhatsapp = hasAny(t, "whatsapp", "wa ", "whatsup", "whats app")
        if (!isWhatsapp) return null
        val target = extractContact(t) ?: return null
        if (target.isBlank()) return null
        if (hasAny(t, "call", "video call", "voice call")) {
            return AppCommand(CommandType.WHATSAPP_CALL, mapOf("name" to target))
        }
        val message = extractMessage(t) ?: ""
        return AppCommand(CommandType.WHATSAPP_MSG, mapOf("name" to target, "message" to message))
    }

    // Extract a contact name from the utterance, e.g. "call mom" -> "mom".
    private fun extractContact(t: String): String? {
        // "call" + optional "ko"/"to" + name
        val patterns = listOf(
            Regex("""(?:call|phone|message|msg|whatsapp|text|ko phn|ko phone)\s+(?:ko|to|karte|kar do|karo)?\s*([a-zA-Z][a-zA-Z0-9 ._-]{1,30})"""),
            Regex("""(?:ko|to)\s+(?:ko|to)?\s*([a-zA-Z][a-zA-Z0-9 ._-]{1,30})""")
        )
        for (p in patterns) {
            val m = p.find(t) ?: continue
            val name = m.groupValues[1].trim().trimEnd('.', '!', '?')
            if (name.isBlank()) continue
            // ignore common filler words
            if (name.lowercase() in fillers) continue
            return name
        }
        return null
    }

    private fun extractMessage(t: String): String? {
        // "message bhejo <name> <message>" or "...that <message>"
        val idxSays = listOf("says", "sayo", " keh ", " bolo ", " bolo ki", "ka bolo", " bol ki", "ki", "that")
        var cleaned = t
        for (key in listOf("message bhejo", "msg bhejo", "sms bhejo", "whatsapp", "message", "whatsapp karo", "send", "bhejo", "go", "ko")) {
            cleaned = cleaned.replace(key, " ")
        }
        // Take everything after the name if a "name <message>" structure
        val name = extractContact(t)
        if (name != null) {
            val nIdx = cleaned.indexOf(name)
            if (nIdx >= 0) {
                val after = cleaned.substring(nIdx + name.length).trim().trimStart(',', ':', '-').trim()
                if (after.isNotBlank()) return after
            }
        }
        // fallback: strip leading command words
        return cleaned.replace(Regex("""\b(call|phone|message|msg|sms|whatsapp|karo|ko|to|bhej|bhejo|send|text|that|ki)\b"""), " ")
            .trim()
            .trimStart(',', ':', '-')
            .trim()
            .ifBlank { null }
    }

    // ---------------- FLASHLIGHT ----------------
    private fun flashlight(t: String, on: Boolean): Boolean {
        val app = hasAny(t, "torch", "flash", "flashlight", "light", "torchlight")
        if (!app) return false
        if (on) return hasAny(t, "on", "jalo", "jalwa", "chalao", "on karo", "open", "band na", "kar") 
            && !hasAny(t, "off", "band", "buha")
        return hasAny(t, "off", "band", "buha", "stop", "bnd")
    }

    // ---------------- WiFi ----------------
    private fun wifi(t: String, on: Boolean): Boolean {
        if (!hasAny(t, "wifi", "wi-fi", "wi fi", "internet")) return false
        return if (on) hasAny(t, "on", "chalao", "jalo", "open", "on karo") && !hasAny(t, "off", "band", "bnd")
        else hasAny(t, "off", "band", "bnd", "stop")
    }

    // ---------------- Bluetooth ----------------
    private fun bluetooth(t: String, on: Boolean): Boolean {
        if (!hasAny(t, "bluetooth", "blue tooth")) return false
        return if (on) hasAny(t, "on", "chalao", "on karo", "jalo") && !hasAny(t, "off", "band", "bnd")
        else hasAny(t, "off", "band", "bnd", "stop")
    }

    // ---------------- Helpers ----------------
    private fun hasAny(text: String, vararg words: String): Boolean = words.any { text.contains(it) }

    private fun containsAny(text: String, vararg words: String): Boolean = words.any { text.contains(it) }

    private val fillers = setOf(
        "kholo", "khol", "open", "close", "band", "call", "karo", "kar", "ko", "to",
        "the", "a", "an", "please", "plz", "ko", "then", "on", "off", "jaldi", "abhi"
    )
}
