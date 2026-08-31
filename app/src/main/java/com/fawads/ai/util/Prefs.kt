package com.fawads.ai.util

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

/**
 * Central preferences holder. The API key + sensitive data are stored with
 * AndroidX EncryptedSharedPreferences (AES-256) when available; it falls back
 * to a plain SharedPreferences on devices where the crypto provider is missing.
 */
class Prefs(context: Context) {

    private val prefs: SharedPreferences = run {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                "fawads_ai_secure_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            context.getSharedPreferences("fawads_ai_prefs", Context.MODE_PRIVATE)
        }
    }

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_API_KEY, value).apply()

    var userName: String
        get() = prefs.getString(KEY_USER_NAME, "Fawad") ?: "Fawad"
        set(value) = prefs.edit().putString(KEY_USER_NAME, value).apply()

    var personalityMode: String
        get() = prefs.getString(KEY_PERSONALITY_PREF, "gf") ?: "gf"
        set(value) = prefs.edit().putString(KEY_PERSONALITY_PREF, value).apply()

    var geminiModel: String
        get() = prefs.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(value) = prefs.edit().putString(KEY_MODEL, value).apply()

    var geminiVoice: String
        get() = prefs.getString(KEY_VOICE, DEFAULT_VOICE) ?: DEFAULT_VOICE
        set(value) = prefs.edit().putString(KEY_VOICE, value).apply()

    var isMuted: Boolean
        get() = prefs.getBoolean(KEY_MUTED, false)
        set(value) = prefs.edit().putBoolean(KEY_MUTED, value).apply()

    var primeContactsJson: String
        get() = prefs.getString(KEY_PRIME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PRIME, value).apply()

    // ----- Security (generic lock — set by whoever configure it) -----
    var securityEnabled: Boolean
        get() = prefs.getBoolean(KEY_SEC_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_SEC_ENABLED, value).apply()

    var pinHash: String
        get() = prefs.getString(KEY_PIN_HASH, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PIN_HASH, value).apply()

    var pinSalt: String
        get() = prefs.getString(KEY_PIN_SALT, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PIN_SALT, value).apply()

    var useFace: Boolean
        get() = prefs.getBoolean(KEY_USE_FACE, true)
        set(value) = prefs.edit().putBoolean(KEY_USE_FACE, value).apply()

    var faceDescriptor: String
        get() = prefs.getString(KEY_FACE_DESC, "") ?: ""
        set(value) = prefs.edit().putString(KEY_FACE_DESC, value).apply()

    var hasFaceEnrolled: Boolean
        get() = prefs.getBoolean(KEY_FACE_ENROLLED, false)
        set(value) = prefs.edit().putBoolean(KEY_FACE_ENROLLED, value).apply()

    // ----- Hands-free / continuous mode -----
    var handsFree: Boolean
        get() = prefs.getBoolean(KEY_HANDS_FREE, false)
        set(value) = prefs.edit().putBoolean(KEY_HANDS_FREE, value).apply()

    var wakeWord: String
        get() = prefs.getString(KEY_WAKE, "fawad") ?: "fawad"
        set(value) = prefs.edit().putString(KEY_WAKE, value).apply()

    // ----- Prime contacts (migrates legacy prime_name / prime_number keys) -----
    fun getPrimeContacts(): MutableList<PrimeContact> {
        val raw = primeContactsJson
        if (raw.isNotBlank()) {
            val list = mutableListOf<PrimeContact>()
            try {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    list.add(PrimeContact(obj.optString("name"), obj.optString("number")))
                }
            } catch (_: Exception) {
            }
            return list
        }
        // Legacy migration
        val legacyName = prefs.getString("prime_name", "") ?: ""
        val legacyNumber = prefs.getString("prime_number", "") ?: ""
        return if (legacyName.isNotBlank()) mutableListOf(PrimeContact(legacyName, legacyNumber)) else mutableListOf()
    }

    fun savePrimeContacts(list: List<PrimeContact>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("name", it.name).put("number", it.number)) }
        primeContactsJson = arr.toString()
    }

    companion object {
        private const val KEY_API_KEY = "api_key"
        private const val KEY_USER_NAME = "user_name"
        private const val KEY_PERSONALITY_PREF = "personality_mode"
        private const val KEY_MODEL = "gemini_model"
        private const val KEY_VOICE = "gemini_voice"
        private const val KEY_PRIME = "prime_contacts_json"
        private const val KEY_MUTED = "muted"
        private const val KEY_SEC_ENABLED = "sec_enabled"
        private const val KEY_PIN_HASH = "pin_hash"
        private const val KEY_PIN_SALT = "pin_salt"
        private const val KEY_USE_FACE = "use_face"
        private const val KEY_FACE_DESC = "face_desc"
        private const val KEY_FACE_ENROLLED = "face_enrolled"
        private const val KEY_HANDS_FREE = "hands_free"
        private const val KEY_WAKE = "wake_word"

        // Personality mode ids
        const val PERSONALITY_GF = "gf"
        const val PERSONALITY_PRO = "professional"
        const val PERSONALITY_ASSISTANT = "assistant"

        val PERSONALITY_LABELS = mapOf(
            PERSONALITY_GF to "GF Mode (Hinglish, caring)",
            PERSONALITY_PRO to "Professional Mode (formal English)",
            PERSONALITY_ASSISTANT to "Assistant Mode (balanced)"
        )

        // Gemini Live native-audio models
        val MODELS = arrayOf(
            "models/gemini-2.5-flash-native-audio-preview-12-2025",
            "models/gemini-2.0-flash-live-001",
            "models/gemini-2.5-flash-preview-native-audio-dialog"
        )
        const val DEFAULT_MODEL = "models/gemini-2.5-flash-native-audio-preview-12-2025"

        val VOICES = arrayOf("Aoede", "Charon", "Kore", "Fenrir", "Puck", "Leda", "Orus", "Zephyr")
        const val DEFAULT_VOICE = "Aoede"
    }
}

data class PrimeContact(val name: String, val number: String)
