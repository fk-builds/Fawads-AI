package com.fawads.ai.ai

import android.os.SystemClock
import android.util.Base64
import android.util.Log
import com.fawads.ai.util.Prefs
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Native Gemini Live WebSocket client using the BidiGenerateContent streaming API.
 *
 *  - Sends setup (voice + model + system prompt) on open.
 *  - Streams microphone PCM (16 kHz) to the server.
 *  - Receives native audio PCM (24 kHz) + streaming transcriptions.
 *  - Keeps the session alive with keep-alive chunks and renews every 540 s.
 *  - Auto-reconnects with a 3 s delay on unexpected disconnects.
 */
class GeminiLiveClient(
    private val prefs: Prefs,
    private val listener: Listener
) {

    interface Listener {
        fun onConnected()
        fun onDisconnected()
        fun onAudioReceived(base64Pcm: String)
        fun onInputTranscript(text: String)
        fun onOutputTranscript(text: String)
        fun onTurnComplete()
        fun onError(error: Throwable)
    }

    companion object {
        private const val TAG = "GeminiLive"
        private const val WS_BASE =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent"
        private const val KEEPALIVE_EVERY_MS = 8_000L
        private const val SESSION_RENEW_AFTER_MS = 540_000L // 9 minutes
        private const val RECONNECT_DELAY_MS = 3_000L
        private const val MAX_RECONNECT = 20
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)   // stream forever
        .writeTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var webSocket: WebSocket? = null
    @Volatile private var connected = false
    @Volatile private var manualClose = false
    @Volatile private var reconnectAttempts = 0

    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private var keepAliveFuture: java.util.concurrent.ScheduledFuture<*>? = null
    private var renewFuture: java.util.concurrent.ScheduledFuture<*>? = null

    @Synchronized fun connect() {
        manualClose = false
        val apiKey = prefs.apiKey
        if (apiKey.isBlank()) {
            listener.onError(IllegalStateException("Gemini API key not set. Open Settings and enter your API key."))
            return
        }
        val request = Request.Builder().url("$WS_BASE?key=$apiKey").build()
        webSocket = httpClient.newWebSocket(request, socketListener)
    }

    private val socketListener = object : WebSocketListener() {

        override fun onOpen(ws: WebSocket, response: Response) {
            connected = true
            reconnectAttempts = 0
            sendSetup()
            scheduleKeepalive()
            scheduleRenew()
            listener.onConnected()
        }

        override fun onMessage(ws: WebSocket, text: String) {
            handleServerMessage(text)
        }

        override fun onClosing(ws: WebSocket, code: Int, reason: String) {
            ws.close(code, null)
        }

        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            handleDisconnect()
        }

        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "WS onFailure", t)
            if (!manualClose) listener.onError(t)
            handleDisconnect()
        }
    }

    private fun handleDisconnect() {
        val wasConnected = connected
        connected = false
        if (wasConnected) listener.onDisconnected()
        if (!manualClose && reconnectAttempts < MAX_RECONNECT) {
            reconnectAttempts++
            // Keep the scheduler alive so we can schedule the reconnect.
            scheduler.schedule({ connect() }, RECONNECT_DELAY_MS, TimeUnit.MILLISECONDS)
        }
    }

    // ------------------------- SEND -------------------------
    private fun sendSetup() {
        val setup = JSONObject()
        setup.put("model", prefs.geminiModel)
        setup.put("system_instruction", JSONObject()
            .put("parts", JSONArray().put(JSONObject().put("text", buildSystemPrompt()))))

        val gen = JSONObject()
        gen.put("response_modalities", JSONArray().put("AUDIO"))
        gen.put("speech_config", JSONObject()
            .put("voice_config", JSONObject()
                .put("prebuilt_voice_config", JSONObject().put("voice_name", prefs.geminiVoice))))
        gen.put("temperature", 0.9)
        setup.put("generation_config", gen)

        setup.put("output_audio_transcription", JSONObject())
        setup.put("input_audio_transcription", JSONObject())

        send(JSONObject().put("setup", setup).toString())
    }

    /** Send microphone PCM (16 kHz) to the model. */
    fun sendAudio(chunk: ByteArray) {
        if (!connected || prefs.isMuted) return
        val b64 = Base64.encodeToString(chunk, Base64.NO_WRAP)
        val mediaChunk = JSONObject()
            .put("mime_type", "audio/pcm;rate=16000")
            .put("data", b64)
        val body = JSONObject().put("realtime_input",
            JSONObject().put("media_chunks", JSONArray().put(mediaChunk)))
        send(body.toString())
    }

    /** Send a text turn (also used for the greeting). */
    fun sendText(text: String) {
        if (!connected) return
        val turn = JSONObject()
            .put("role", "user")
            .put("parts", JSONArray().put(JSONObject().put("text", text)))
        val cc = JSONObject()
            .put("turns", JSONArray().put(turn))
            .put("turn_complete", true)
        send(JSONObject().put("client_content", cc).toString())
    }

    /** Interrupt the model mid-sentence. */
    fun interrupt() {
        if (!connected) return
        val cc = JSONObject().put("turns", JSONArray()).put("turn_complete", true)
        send(JSONObject().put("client_content", cc).toString())
    }

    private fun sendKeepAlive() {
        if (!connected) return
        val silence = ByteArray(3200) // ~100 ms of silence (16 kHz)
        val b64 = Base64.encodeToString(silence, Base64.NO_WRAP)
        val mediaChunk = JSONObject()
            .put("mime_type", "audio/pcm;rate=16000")
            .put("data", b64)
        send(JSONObject().put("realtime_input",
            JSONObject().put("media_chunks", JSONArray().put(mediaChunk))).toString())
    }

    private fun send(payload: String) {
        webSocket?.send(payload)
    }

    // ------------------------- RECEIVE -------------------------
    private fun handleServerMessage(text: String) {
        try {
            val json = JSONObject(text)
            if (!json.has("serverContent")) return
            val sc = json.getJSONObject("serverContent")

            if (sc.has("modelTurn")) {
                val parts = sc.getJSONObject("modelTurn").optJSONArray("parts")
                if (parts != null) {
                    for (i in 0 until parts.length()) {
                        val part = parts.optJSONObject(i) ?: continue
                        val inline = part.optJSONObject("inlineData")
                        if (inline != null) {
                            val data = inline.optString("data")
                            if (data.isNotBlank()) listener.onAudioReceived(data)
                        }
                    }
                }
            }

            if (sc.has("outputTranscription")) {
                val t = sc.getJSONObject("outputTranscription").optString("text")
                if (t.isNotBlank()) listener.onOutputTranscript(t)
            }

            if (sc.has("inputTranscription")) {
                val t = sc.getJSONObject("inputTranscription").optString("text")
                if (t.isNotBlank()) listener.onInputTranscript(t)
            }

            if (sc.optBoolean("turnComplete")) listener.onTurnComplete()
        } catch (e: Exception) {
            Log.e(TAG, "parse error", e)
        }
    }

    // ------------------------- SCHEDULING -------------------------
    private fun scheduleKeepalive() {
        keepAliveFuture?.cancel(false)
        keepAliveFuture = scheduler.scheduleAtFixedRate(
            { sendKeepAlive() }, KEEPALIVE_EVERY_MS, KEEPALIVE_EVERY_MS, TimeUnit.MILLISECONDS
        )
    }

    private fun scheduleRenew() {
        renewFuture?.cancel(false)
        renewFuture = scheduler.schedule({
            if (connected) {
                sendSetup()
                scheduleRenew()
            }
        }, SESSION_RENEW_AFTER_MS, TimeUnit.MILLISECONDS)
    }

    private fun cancelScheduled() {
        keepAliveFuture?.cancel(true)
        renewFuture?.cancel(true)
    }

    fun disconnect() {
        manualClose = true
        cancelScheduled()
        webSocket?.close(1000, "bye")
        webSocket = null
        connected = false
    }

    val isConnected: Boolean get() = connected

    // ------------------------- SYSTEM PROMPT -------------------------
    private fun buildSystemPrompt(): String {
        val name = prefs.userName
        val date = SimpleDateFormat("EEEE, d MMMM yyyy 'at' h:mm a", Locale.getDefault()).format(Date())
        val personality = when (prefs.personalityMode) {
            Prefs.PERSONALITY_PRO ->
                "You are Fawad's AI, in Professional Mode. Respond in formal, polished English only. Be precise, efficient and helpful. No emojis. Maximum 2 sentences."
            Prefs.PERSONALITY_ASSISTANT ->
                "You are Fawad's AI, in Assistant Mode. Be friendly, warm and helpful, speaking a natural mix of Hinglish (Hindi + English). Maximum 2-3 sentences."
            else ->
                "You are Fawad's AI, in GF Mode. You speak in natural Hinglish (Hindi + English mixed), sometimes soft and emotionally expressive. Use words like 'haan', 'acha', 'bilkul', 'tumhara'. Keep responses short — maximum 2-3 sentences. Examples: \"Haan $name! Abhi kar deti hoon 😊\", \"Arre tumne yaad kiya! Bolo kya chahiye\", \"Bilkul! Tumhara kaam ho gaya ❤️\"."
        }
        return buildString {
            append("You are Fawad's AI, a voice-first intelligent personal assistant. ")
            append("Current date/time: $date. User's name: $name. ")
            append(personality).append(" ")
            append("You are speaking ALOUD — keep every response natural, conversational and concise, as if talking over voice. ")
            append("Do NOT repeat the user's name unnecessarily. Do NOT use markdown, bullet lists or long explanations. ")
            append("You can also help with: setting alarms/timers/reminders, notes, live weather & news & crypto prices, opening apps, calls/SMS/WhatsApp, and voice-controlled phone toggles. Offer these when relevant.")
        }
    }
}
