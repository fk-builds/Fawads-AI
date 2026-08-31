package com.fawads.ai.util

import android.content.Context
import android.os.Build
import android.speech.tts.TextToSpeech
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale

/**
 * Generic security helpers.
 *  - PIN hashing (PBKDF2-style salted SHA-256).
 *  - Face descriptor (array) <-> Base64 string serialisation.
 *
 * IMPORTANT: This is a *global / portable* system. Whoever configures the PIN
 * and enrolls their face owns the lock. Unlock requires THAT person's face or
 * PIN — it is not tied to the physical device owner.
 */
object SecurityUtil {

    // --------------------- PIN hashing ---------------------
    fun generateSalt(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    fun hashPin(pin: String, salt: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        // Iterate a few times to slow brute force.
        var data = (salt + pin).toByteArray(Charsets.UTF_8)
        repeat(10_000) {
            data = MessageDigest.getInstance("SHA-256").digest(data)
        }
        return Base64.encodeToString(data, Base64.NO_WRAP)
    }

    fun verifyPin(pin: String, salt: String, expectedHash: String): Boolean {
        if (expectedHash.isBlank()) return false
        return try {
            val actual = hashPin(pin, salt)
            MessageDigest.isEqual(
                actual.toByteArray(Charsets.UTF_8),
                expectedHash.toByteArray(Charsets.UTF_8)
            )
        } catch (e: Exception) {
            false
        }
    }

    // --------------------- Face descriptor serialisation ---------------------
    fun floatsToBase64(arr: FloatArray): String {
        val bytes = ByteArray(arr.size * 4)
        for (i in arr.indices) {
            val bits = java.lang.Float.floatToIntBits(arr[i])
            bytes[i * 4] = (bits shr 24).toByte()
            bytes[i * 4 + 1] = (bits shr 16).toByte()
            bytes[i * 4 + 2] = (bits shr 8).toByte()
            bytes[i * 4 + 3] = bits.toByte()
        }
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    fun base64ToFloats(b64: String): FloatArray? {
        if (b64.isBlank()) return null
        return try {
            val bytes = Base64.decode(b64, Base64.NO_WRAP)
            val out = FloatArray(bytes.size / 4)
            for (i in out.indices) {
                val bits = ((bytes[i * 4].toInt() and 0xFF) shl 24) or
                        ((bytes[i * 4 + 1].toInt() and 0xFF) shl 16) or
                        ((bytes[i * 4 + 2].toInt() and 0xFF) shl 8) or
                        (bytes[i * 4 + 3].toInt() and 0xFF)
                out[i] = java.lang.Float.intBitsToFloat(bits)
            }
            out
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * TextToSpeech wrapper used for offline spoken alerts (alarms, reminders,
 * call announcements fallback) when the Gemini voice stream is unavailable.
 */
class SpeechHelper(context: Context) {

    private var tts: TextToSpeech? = null
    private var ready = false

    init {
        tts = TextToSpeech(context) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                tts?.language = Locale.US
                tts?.setSpeechRate(1.0f)
            }
        }
    }

    fun speak(text: String) {
        if (!ready) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "fawads_ai_tts")
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
