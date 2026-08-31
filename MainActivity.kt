package com.fawads.ai.ui.main

import android.Manifest
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.fawads.ai.R
import com.fawads.ai.ai.AudioEngine
import com.fawads.ai.ai.CommandParser
import com.fawads.ai.ai.GeminiLiveClient
import com.fawads.ai.databinding.ActivityMainBinding
import com.fawads.ai.model.AppCommand
import com.fawads.ai.model.CommandType
import com.fawads.ai.service.CallMonitorService
import com.fawads.ai.service.FawadsOverlayService
import com.fawads.ai.ui.security.LockActivity
import com.fawads.ai.util.Prefs
import com.fawads.ai.viewmodel.MainViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_INCOMING_CALL = "incoming_call"
        const val EXTRA_CALLER_NAME = "caller_name"
        const val EXTRA_UNLOCKED = "unlocked"
        private const val REQ_PERMS = 100
        private const val REQ_OVERLAY = 200
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: MainViewModel
    private lateinit var prefs: Prefs

    private var geminiLive: GeminiLiveClient? = null
    private var audioEngine: AudioEngine? = null

    private val chatMessages = mutableListOf<ChatMessage>()
    private lateinit var chatAdapter: ChatAdapter

    private val handler = Handler(Looper.getMainLooper())
    private val inputBuffer = StringBuilder()
    private val outputBuffer = StringBuilder()

    private var muted = false
    private var inCallMode = false
    private var callSpeechRecognizer: SpeechRecognizer? = null
    private var handsFreeRecognizer: SpeechRecognizer? = null
    @Volatile private var handsFreeActive = false

    private val callEndedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            inCallMode = false
            audioEngine?.setMuted(false)
            status("Sun rahi hoon…")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = Prefs(this)
        muted = prefs.isMuted
        viewModel = ViewModelProvider(this)[MainViewModel::class.java]

        // Security gate: route to lock screen unless already unlocked, and skip
        // when handling an incoming call (so call announcements still work).
        val unlocked = intent?.getBooleanExtra(EXTRA_UNLOCKED, false) ?: false
        val isCall = intent?.getBooleanExtra(EXTRA_INCOMING_CALL, false) ?: false
        if (prefs.securityEnabled && !unlocked && !isCall) {
            startActivity(Intent(this, LockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
            return
        }

        initViews()
        startStatusUpdates()
        ContextCompat.registerReceiver(
            this, callEndedReceiver,
            IntentFilter(CallMonitorService.ACTION_CALL_ENDED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        // Only start the mic / services / Gemini connection once the
        // permission dialog flow has actually finished — starting them
        // immediately raced the async permission request and crashed the
        // app on first launch.
        checkPermissions()
        handleIncomingCall(intent)
    }

    private fun proceedAfterPermissions() {
        startSystemServices()
        handler.postDelayed({ initGeminiLive() }, 300)
    }

    // ---------------------------- INIT ----------------------------
    private fun initViews() {
        chatAdapter = ChatAdapter(chatMessages)
        binding.chatRecycler.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.chatRecycler.adapter = chatAdapter

        binding.settingsBtn.setOnClickListener {
            startActivity(Intent(this, com.fawads.ai.ui.settings.SettingsActivity::class.java))
        }

        binding.micButton.setOnClickListener { toggleMute() }
        binding.micButton.setOnLongClickListener {
            audioEngine?.interruptPlayback()
            geminiLive?.interrupt()
            addChat(ChatMessage("⏹️ Stopped", false))
            true
        }

        viewModel.commandResult.observe(this) { result ->
            if (result != null) {
                addChat(ChatMessage(result, false))
                geminiLive?.sendText(result)
            }
        }
        viewModel.callDecision.observe(this) { decision ->
            if (decision != null) {
                addChat(ChatMessage(decision, false))
                geminiLive?.sendText(decision)
            }
        }

        applyMuteUi()
    }

    private fun startSystemServices() {
        // Floating orb (needs SYSTEM_ALERT_WINDOW)
        if (Settings.canDrawOverlays(this)) {
            try { startService(Intent(this, FawadsOverlayService::class.java)) } catch (_: Exception) {}
        } else {
            toast("Overlay permission needed for the floating orb")
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
        // Call monitor
        try {
            ContextCompat.startForegroundService(this, Intent(this, CallMonitorService::class.java))
        } catch (_: Exception) {}
    }

    private fun startStatusUpdates() {
        updateStatusBar()
        handler.postDelayed(object : Runnable {
            override fun run() {
                updateStatusBar()
                handler.postDelayed(this, 30_000)
            }
        }, 30_000)
    }

    private fun updateStatusBar() {
        // Battery
        val bm = getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
        val battery = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        binding.batteryText.text = "$battery%"

        // RAM
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        val totalMb = info.totalMem / (1024 * 1024)
        val availMb = info.availMem / (1024 * 1024)
        binding.ramText.text = "RAM ${availMb / 1000f}G / $totalMb MB"

        // Time
        binding.timeText.text = SimpleDateFormat("h:mm", Locale.getDefault()).format(Date())
    }

    private fun checkPermissions() {
        val perms = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.CAMERA
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        val toRequest = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (toRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, toRequest.toTypedArray(), REQ_PERMS)
        } else {
            proceedAfterPermissions()
        }
    }

    // ---------------------------- GEMINI + AUDIO ----------------------------
    private fun initGeminiLive() {
        if (geminiLive != null) return
        geminiLive = GeminiLiveClient(prefs, object : GeminiLiveClient.Listener {
            override fun onConnected() {
                runOnUiThread {
                    setActive(true)
                    status("Connected ✓")
                    sendGreeting()
                }
            }
            override fun onDisconnected() {
                runOnUiThread { status("Reconnecting…") }
            }
            override fun onAudioReceived(base64Pcm: String) {
                audioEngine?.queueAudio(base64Pcm)
            }
            override fun onInputTranscript(text: String) {
                runOnUiThread { inputBuffer.append(text).append(' ') }
            }
            override fun onOutputTranscript(text: String) {
                runOnUiThread { outputBuffer.append(text) }
            }
            override fun onTurnComplete() {
                runOnUiThread { flushTranscription() }
            }
            override fun onError(error: Throwable) {
                runOnUiThread {
                    status(error.message ?: "Error")
                    addChat(ChatMessage("⚠️ ${error.message ?: "Connection error"}", false))
                }
            }
        })

        audioEngine = AudioEngine().apply {
            onAudioChunk = { chunk -> if (!handsFreeActive) geminiLive?.sendAudio(chunk) }
            onSpeakingStarted = {
                runOnUiThread {
                    setActive(true)
                    status("Bol rahi hoon…")
                    binding.orbView.setState(OrbAnimationView.State.SPEAKING)
                }
            }
            onSpeakingStopped = {
                runOnUiThread {
                    status("Sun rahi hoon…")
                    binding.orbView.setState(OrbAnimationView.State.LISTENING)
                }
            }
            onAmplitudeChanged = { rms ->
                runOnUiThread { binding.waveformView.setAmplitude(rms) }
            }
        }

        geminiLive?.connect()
        audioEngine?.startRecording()
        audioEngine?.startPlayback()
        applyMuteUi()
    }

    private fun flushTranscription() {
        val userText = inputBuffer.toString().trim()
        val myraText = outputBuffer.toString().trim()
        inputBuffer.setLength(0)
        outputBuffer.setLength(0)

        if (userText.isNotBlank()) {
            addChat(ChatMessage(userText, true))
            handleCommand(userText)
        }
        val last = chatAdapter.lastMyraText()
        if (myraText.isNotBlank() && myraText != last) {
            addChat(ChatMessage(myraText, false))
        }
    }

    private fun handleCommand(text: String) {
        val cmd = CommandParser.parse(text) ?: return
        when (cmd.type) {
            CommandType.MUTE -> { muted = true; prefs.isMuted = true; audioEngine?.setMuted(true); applyMuteUi() }
            CommandType.UNMUTE -> { muted = false; prefs.isMuted = false; audioEngine?.setMuted(false); applyMuteUi() }
            CommandType.STOP -> { audioEngine?.interruptPlayback(); geminiLive?.interrupt() }
            else -> viewModel.execute(cmd)
        }
    }

    private fun sendGreeting() {
        val name = prefs.userName
        val greeting = when (prefs.personalityMode) {
            Prefs.PERSONALITY_PRO -> "Good day $name. I'm online and ready to assist you."
            Prefs.PERSONALITY_ASSISTANT -> "Hello $name! Main Fawad's AI hoon. Kaise help karun aapki?"
            else -> "Hey $name! Main aa gayi hoon. Kya help chahiye tumhe?"
        }
        geminiLive?.sendText(greeting)
    }

    // ---------------------------- HANDS-FREE (wake word) ----------------------------
    private fun startHandsFree() {
        if (handsFreeActive) return
        handsFreeActive = true
        prefs.isMuted = false
        try {
            handsFreeRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onResults(results: android.os.Bundle?) {
                        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.joinToString(" ")?.trim() ?: ""
                        onHandsFreeResult(text)
                    }
                    override fun onError(error: Int) {
                        if (handsFreeActive) handler.postDelayed({ listenHandsFree() }, 1200)
                    }
                    override fun onReadyForSpeech(params: android.os.Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onPartialResults(partialResults: android.os.Bundle?) {}
                    override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
                })
                listenHandsFree()
            }
        } catch (_: Exception) {}
    }

    private fun listenHandsFree() {
        handsFreeRecognizer?.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            }
        )
    }

    private fun onHandsFreeResult(text: String) {
        status("Sun rahi hoon…")
        if (text.isBlank()) { if (handsFreeActive) handler.postDelayed({ listenHandsFree() }, 1000); return }
        val wake = prefs.wakeWord.lowercase()
        val lower = text.lowercase()
        var command = text
        if (lower.contains(wake)) {
            command = text.substring(
                text.lowercase().indexOf(wake) + wake.length
            ).trim().trimStart(',', ' ', '-')
        }
        if (command.isBlank()) { if (handsFreeActive) handler.postDelayed({ listenHandsFree() }, 1000); return }
        addChat(ChatMessage(text, true))
        val cmd = CommandParser.parse(command)
        if (cmd != null) {
            handleCommand(command)
        } else {
            geminiLive?.sendText(command)
        }
        if (handsFreeActive) handler.postDelayed({ listenHandsFree() }, 800)
    }

    private fun stopHandsFree() {
        handsFreeActive = false
        handsFreeRecognizer?.destroy()
        handsFreeRecognizer = null
    }

    // ---------------------------- MUTE / ACTIVE ----------------------------
    private fun toggleMute() {
        muted = !muted
        prefs.isMuted = muted
        audioEngine?.setMuted(muted)
        applyMuteUi()
    }

    private fun applyMuteUi() {
        binding.micButton.setImageResource(if (muted) R.drawable.ic_mic_off else R.drawable.ic_mic_on)
        status(if (prefs.handsFree) "Hands-free: '$w' bolo 🎤" else if (muted) "Muted 🔇" else "Tap karke bolo 💬")
    }

    private val w get() = prefs.wakeWord

    private fun setActive(active: Boolean) {
        val target = if (active) 0.08f else 0f
        binding.redOverlay.animate().alpha(target)
            .setDuration(if (active) 300 else 500).start()
        if (active) binding.orbView.setState(OrbAnimationView.State.ACTIVE)
        else binding.orbView.setState(OrbAnimationView.State.IDLE)
    }

    private fun status(text: String) {
        binding.statusText.text = text
    }

    // ---------------------------- INCOMING CALL ----------------------------
    private fun handleIncomingCall(intent: Intent?) {
        if (intent != null && intent.getBooleanExtra(EXTRA_INCOMING_CALL, false)) {
            inCallMode = true
            val caller = intent.getStringExtra(EXTRA_CALLER_NAME) ?: "Unknown"
            announceCall(caller)
        }
    }

    private fun announceCall(caller: String) {
        audioEngine?.setMuted(true)
        addChat(ChatMessage("📞 Incoming call from $caller", true))
        geminiLive?.sendText("Sir, $caller ka call aa raha hai. Uthau ya reject karu?")
        handler.postDelayed({ startCallDecisionStt() }, 4500)
    }

    private fun startCallDecisionStt() {
        try {
            if (!SpeechRecognizer.isRecognitionAvailable(this)) return
            callSpeechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onResults(results: android.os.Bundle?) {
                        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.joinToString(" ")?.lowercase() ?: ""
                        decideCall(text)
                    }
                    override fun onError(error: Int) {}
                    override fun onReadyForSpeech(params: android.os.Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onPartialResults(partialResults: android.os.Bundle?) {}
                    override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
                })
                startListening(
                    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                    }
                )
            }
        } catch (_: Exception) {}
    }

    private fun decideCall(text: String) {
        val accept = listOf("uthao", "utha", "accept", "haan", "han", "yes", "lo", "answer", "utha lo")
        val reject = listOf("reject", "nahi", "mat", "no", "band", "not", "na", "reject karo")
        inCallMode = false
        audioEngine?.setMuted(false)
        if (accept.any { text.contains(it) }) viewModel.acceptCall()
        else if (reject.any { text.contains(it) }) viewModel.rejectCall()
        else status("Call decision not received")
    }

    // ---------------------------- HELPERS ----------------------------
    private fun addChat(message: ChatMessage) {
        chatAdapter.addMessage(message)
        binding.chatRecycler.scrollToPosition(chatMessages.size - 1)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingCall(intent)
    }

    override fun onPause() {
        super.onPause()
        stopHandsFree()
        audioEngine?.setMuted(true)
    }

    override fun onResume() {
        super.onResume()
        if (prefs.handsFree) {
            startHandsFree()
        } else {
            if (!muted) audioEngine?.setMuted(false)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        try { unregisterReceiver(callEndedReceiver) } catch (_: Exception) {}
        geminiLive?.disconnect()
        audioEngine?.release()
        callSpeechRecognizer?.destroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMS) {
            if (grantResults.any { it != PackageManager.PERMISSION_GRANTED }) {
                toast("Some permissions were denied — features may be limited")
            }
            proceedAfterPermissions()
        }
    }
}
