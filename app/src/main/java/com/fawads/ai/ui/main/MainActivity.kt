package com.fawads.ai.ui.main

import android.Manifest
import android.app.ActivityManager
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
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
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.View
import android.view.ViewGroup
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
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
import com.fawads.ai.ui.notes.NotesActivity
import com.fawads.ai.util.Prefs
import com.fawads.ai.util.TaskItem
import com.fawads.ai.util.TaskManager
import com.fawads.ai.util.checkForUpdates
import com.fawads.ai.viewmodel.MainViewModel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_INCOMING_CALL = "incoming_call"
        const val EXTRA_CALLER_NAME = "caller_name"
        const val EXTRA_UNLOCKED = "unlocked"
        private const val REQ_PERMS = 100
        private const val REQ_NOTIFICATIONS = 201
    }

    private enum class Page { HOME, TASKS, VOICE, CHAT }

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
    private var assistantStarted = false
    private var permissionRequestInProgress = false
    private var audioPlaybackStarted = false
    private var pendingIncomingCall: String? = null
    private var currentPage = Page.HOME
    private var chatSessionMode = false
    private var liveConnectionStarted = false
    private var chatTurnInFlight = false
    private var lastApiKey: String? = null
    private var lastSettingsSignature = ""
    private val pendingChatPrompts = mutableListOf<String>()
    private lateinit var taskAdapter: TaskAdapter
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
        lastApiKey = prefs.apiKey
        lastSettingsSignature = settingsSignature()
        chatMessages.addAll(ChatHistoryStore.load(prefs))
        if (chatMessages.isEmpty()) chatMessages.add(ChatMessage("Sure, ${prefs.userName}. How can I help?", false))
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
        // Keep first launch lightweight; voice permissions and audio start
        // only after the user explicitly chooses the assistant.
        handleIncomingCall(intent)

        // Keep network work off the first-render path.
        if (!isCall) handler.postDelayed({
            if (!isFinishing) runCatching { checkForUpdates(manual = false) }
        }, 5_000)
    }

    private fun proceedAfterPermissions() {
        permissionRequestInProgress = false
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            assistantStarted = false
            status("Microphone permission is needed for voice")
            toast("Allow microphone access to start voice assistant")
            showHomeScreen()
            return
        }
        assistantStarted = true
        runCatching { startSystemServices() }
        handler.postDelayed({
            try {
                initGeminiLive()
            } catch (e: Exception) {
                geminiLive?.disconnect()
                audioEngine?.release()
                geminiLive = null
                audioEngine = null
                liveConnectionStarted = false
                audioPlaybackStarted = false
                assistantStarted = false
                status("Voice could not start")
                addChat(ChatMessage("Voice startup failed: ${e.message ?: "Audio device unavailable"}", false))
            }
        }, 300)
    }

    // ---------------------------- INIT ----------------------------
    private fun initViews() {
        chatAdapter = ChatAdapter(chatMessages)
        binding.greetingText.text = personalizedGreeting()
        binding.topSubtitle.text = SimpleDateFormat("EEEE", Locale.getDefault()).format(Date())
        binding.chatRecycler.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.chatRecycler.adapter = chatAdapter
        taskAdapter = TaskAdapter(
            onCompletionChanged = { task, completed ->
                TaskManager.setTaskCompleted(this, task.id, completed)
                refreshTasks()
            },
            onDelete = { task ->
                TaskManager.deleteTask(this, task.id)
                refreshTasks()
            }
        )
        binding.tasksRecycler.layoutManager = LinearLayoutManager(this)
        binding.tasksRecycler.adapter = taskAdapter

        binding.settingsBtn.setOnClickListener {
            startActivity(Intent(this, com.fawads.ai.ui.settings.SettingsActivity::class.java))
        }
        binding.voiceSettingsButton.setOnClickListener {
            startActivity(Intent(this, com.fawads.ai.ui.settings.SettingsActivity::class.java))
        }
        binding.micButton.setOnClickListener {
            if (assistantStarted) toggleMute() else startAssistant()
        }
        binding.startTalkingButton.setOnClickListener { startAssistant() }
        binding.talkActionButton.setOnClickListener { startAssistant() }
        binding.callsToolButton.setOnClickListener { showCommunicationActions() }
        binding.liveInfoButton.setOnClickListener { openChatWithPrompt("Please share the latest useful weather and news information available to you.") }
        binding.homeSeeAllButton.setOnClickListener { showTasksScreen() }
        binding.homeNewTaskButton.setOnClickListener {
            showTasksScreen()
        }
        binding.homeTasksSummary.setOnClickListener { showTasksScreen() }
        binding.actionAlarm.setOnClickListener { showTasksScreen(); showAddTaskDialog() }
        binding.actionWork.setOnClickListener { showTasksScreen(); showAddTaskDialog() }
        binding.actionWeather.setOnClickListener { openChatWithPrompt("What's the weather today?") }
        binding.actionNews.setOnClickListener { openChatWithPrompt("Give me a concise summary of today's news.") }
        binding.homeNav.setOnClickListener { showHomeScreen() }
        binding.tasksNav.setOnClickListener { showTasksScreen() }
        binding.voiceNav.setOnClickListener { startAssistant() }
        binding.chatNav.setOnClickListener { openChatScreen() }
        binding.backHomeButton.setOnClickListener { showHomeScreen() }
        binding.tasksAddButton.setOnClickListener { showAddTaskDialog() }
        binding.tasksNotesButton.setOnClickListener {
            startActivity(Intent(this, NotesActivity::class.java))
        }
        binding.chatSendButton.setOnClickListener { sendChatMessage() }
        binding.voicePauseButton.setOnClickListener { if (assistantStarted) toggleMute() else startAssistant() }
        binding.voiceEndButton.setOnClickListener { showHomeScreen() }
        binding.chatInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendChatMessage()
                true
            } else false
        }
        binding.chatClearButton.setOnClickListener { confirmClearChat() }
        binding.micButton.setOnLongClickListener {
            audioEngine?.interruptPlayback()
            geminiLive?.interrupt()
            addChat(ChatMessage("⏹️ Stopped", false))
            true
        }

        viewModel.commandResult.observe(this) { result ->
            if (result != null) {
                addChat(ChatMessage(result, false))
                if (currentPage != Page.CHAT) geminiLive?.sendText(result)
                refreshTasks()
            }
        }
        viewModel.callDecision.observe(this) { decision ->
            if (decision != null) {
                addChat(ChatMessage(decision, false))
                geminiLive?.sendText(decision)
            }
        }

        applyMuteUi()
        refreshTasks()
        updateNavigation(Page.HOME)
        installPressFeedback(binding.root)
    }

    private fun installPressFeedback(view: View) {
        if (view.isClickable || view is android.widget.Button) {
            view.setOnTouchListener { target, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        target.animate().scaleX(0.97f).scaleY(0.97f).setDuration(75).start()
                        target.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        target.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                }
                false
            }
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) installPressFeedback(view.getChildAt(i))
    }

    private fun showAssistantScreen() {
        updateNavigation(Page.VOICE)
    }

    private fun startAssistant() {
        chatSessionMode = false
        showAssistantScreen()
        if (prefs.apiKey.isBlank()) {
            status("Add your Gemini API key in Menu → Settings")
            toast("Set up your Gemini API key in Settings first")
            return
        }
        if (assistantStarted) {
            initGeminiLive(startVoice = true)
            audioEngine?.setMuted(muted)
            if (geminiLive?.isConnected == true) {
                status("Connected")
                setActive(true)
            }
            return
        }
        checkPermissions()
    }

    private fun openChatScreen() {
        chatSessionMode = true
        updateNavigation(Page.CHAT)
        connectChat()
    }

    private fun openChatWithPrompt(prompt: String) {
        chatSessionMode = true
        updateNavigation(Page.CHAT)
        binding.chatInput.setText(prompt)
        sendChatMessage()
    }

    private fun showTasksScreen() {
        updateNavigation(Page.TASKS)
        refreshTasks()
    }

    private fun showHomeScreen() {
        updateNavigation(Page.HOME)
    }

    private fun updateNavigation(page: Page) {
        if (page != Page.VOICE) {
            stopHandsFree()
            audioEngine?.stopRecording()
            audioEngine?.setMuted(true)
            setActive(false)
        }
        currentPage = page
        val voicePage = page == Page.VOICE
        binding.topBar.visibility = if (voicePage) View.GONE else View.VISIBLE
        binding.bottomBar.visibility = if (voicePage) View.GONE else View.VISIBLE
        binding.homeContent.visibility = if (page == Page.HOME) View.VISIBLE else View.GONE
        binding.tasksContent.visibility = if (page == Page.TASKS) View.VISIBLE else View.GONE
        binding.assistantContent.visibility = if (page == Page.VOICE) View.VISIBLE else View.GONE
        binding.chatContent.visibility = if (page == Page.CHAT) View.VISIBLE else View.GONE
        val darkPage = voicePage
        window.statusBarColor = getColor(if (darkPage) R.color.voice_bg else R.color.bg_dark)
        window.navigationBarColor = getColor(if (darkPage) R.color.voice_bg else R.color.bg_dark)
        @Suppress("DEPRECATION")
        run {
            window.decorView.systemUiVisibility = if (darkPage) 0 else
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        val active = getColor(R.color.nav_active_text)
        val inactive = getColor(R.color.nav_inactive)
        listOf(
            binding.homeNav to Page.HOME,
            binding.tasksNav to Page.TASKS,
            binding.voiceNav to Page.VOICE,
            binding.chatNav to Page.CHAT
        ).forEach { (tab, tabPage) ->
            val selected = tabPage == page
            tab.setTextColor(if (selected) active else inactive)
            tab.setBackgroundResource(if (selected) R.drawable.bg_nav_active else android.R.color.transparent)
        }
        if (page == Page.CHAT && chatMessages.isNotEmpty()) {
            binding.chatRecycler.post { binding.chatRecycler.scrollToPosition(chatMessages.size - 1) }
        }
    }

    private fun refreshTasks() {
        if (!this::taskAdapter.isInitialized) return
        val tasks = TaskManager.getTasks(this)
        taskAdapter.submitTasks(tasks)
        val active = tasks.count { !it.completed }
        val completed = tasks.size - active
        binding.tasksSummaryText.text = "$active active · $completed completed"
        binding.tasksEmptyText.visibility = if (tasks.isEmpty()) View.VISIBLE else View.GONE
        binding.homeTasksSummary.text = if (active == 0) "To-dos & reminders · all clear" else "$active active · $completed done"
    }

    private fun showAddTaskDialog() {
        val titleInput = EditText(this).apply {
            hint = "What needs to get done?"
            setSingleLine(true)
            setPadding(20, 18, 20, 18)
        }
        val dueAt = longArrayOf(0L)
        val dueButton = TextView(this).apply {
            text = "Add a reminder (optional)"
            setTextColor(getColor(R.color.accent_red))
            setPadding(20, 18, 20, 18)
            setBackgroundResource(R.drawable.bg_input_field)
        }
        dueButton.setOnClickListener {
            val now = Calendar.getInstance()
            DatePickerDialog(this, { _, year, month, day ->
                val selected = Calendar.getInstance().apply {
                    set(Calendar.YEAR, year)
                    set(Calendar.MONTH, month)
                    set(Calendar.DAY_OF_MONTH, day)
                }
                TimePickerDialog(this, { _, hour, minute ->
                    selected.set(Calendar.HOUR_OF_DAY, hour)
                    selected.set(Calendar.MINUTE, minute)
                    selected.set(Calendar.SECOND, 0)
                    selected.set(Calendar.MILLISECOND, 0)
                    if (selected.timeInMillis <= System.currentTimeMillis()) {
                        dueAt[0] = 0L
                        dueButton.text = "Reminder must be in the future"
                    } else {
                        dueAt[0] = selected.timeInMillis
                        dueButton.text = "Remind me · ${SimpleDateFormat("EEE, d MMM · h:mm a", Locale.getDefault()).format(selected.time)}"
                    }
                }, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), false).show()
            }, now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH)).show()
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 4)
            addView(titleInput, LinearLayout.LayoutParams(-1, -2))
            val params = LinearLayout.LayoutParams(-1, -2).apply { topMargin = 12 }
            addView(dueButton, params)
        }
        AlertDialog.Builder(this)
            .setTitle("Add a task")
            .setView(content)
            .setPositiveButton("Save") { _, _ ->
                val title = titleInput.text.toString().trim()
                if (title.isBlank()) {
                    toast("Write a task first")
                } else {
                    val reminderAt = dueAt[0].takeIf { it > 0L }
                    TaskManager.addTask(this, title, reminderAt)
                    if (reminderAt != null) requestNotificationPermissionIfNeeded()
                    refreshTasks()
                    toast("Task saved")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showCommunicationActions() {
        val actions = arrayOf("Open phone dialer", "Compose text message", "Open WhatsApp chat")
        AlertDialog.Builder(this)
            .setTitle("Calls & messages")
            .setItems(actions) { _, which ->
                val numberInput = EditText(this).apply {
                    hint = "Phone number (include country code for WhatsApp)"
                    inputType = android.text.InputType.TYPE_CLASS_PHONE
                    setSingleLine(true)
                    setPadding(24, 18, 24, 18)
                }
                AlertDialog.Builder(this)
                    .setTitle(actions[which])
                    .setView(numberInput)
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Continue") { _, _ ->
                        val number = numberInput.text.toString().trim()
                        if (number.isBlank()) {
                            toast("Enter a phone number first")
                            return@setPositiveButton
                        }
                        val intent = when (which) {
                            0 -> Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(number)}"))
                            1 -> Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(number)}"))
                            else -> Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/${number.filter { it.isDigit() }}"))
                        }
                        try {
                            startActivity(intent)
                        } catch (_: Exception) {
                            toast(if (which == 2) "WhatsApp or a browser is not available" else "No compatible phone app is available")
                        }
                    }
                    .show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun connectChat() {
        if (prefs.apiKey.isBlank()) {
            binding.chatStatusText.text = "Set your Gemini API key in Menu → Settings to chat."
            return
        }
        try {
            initGeminiLive(startVoice = false)
            binding.chatStatusText.text = if (geminiLive?.isConnected == true) "Connected · ready for your message" else "Connecting to Gemini…"
        } catch (e: Exception) {
            binding.chatStatusText.text = "Could not connect: ${e.message ?: "try again"}"
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQ_NOTIFICATIONS
            )
        }
    }

    private fun sendChatMessage() {
        val message = binding.chatInput.text?.toString()?.trim().orEmpty()
        if (message.isBlank()) return
        binding.chatInput.setText("")
        chatSessionMode = true
        addChat(ChatMessage(message, true))
        if (executeChatCommand(message)) return
        if (prefs.apiKey.isBlank()) {
            addChat(ChatMessage("Add your Gemini API key in Menu → Settings to get AI replies.", false))
            binding.chatStatusText.text = "API key required"
            return
        }
        pendingChatPrompts.add(message)
        connectChat()
        sendNextChatPrompt()
    }

    private fun executeChatCommand(text: String): Boolean {
        val command = CommandParser.parse(text) ?: return false
        when (command.type) {
            CommandType.MUTE, CommandType.UNMUTE, CommandType.STOP -> {
                handleCommand(text)
                val reply = when (command.type) {
                    CommandType.MUTE -> "Microphone muted."
                    CommandType.UNMUTE -> "Microphone unmuted."
                    else -> "Stopped."
                }
                addChat(ChatMessage(reply, false))
            }
            else -> viewModel.execute(command)
        }
        return true
    }

    private fun sendNextChatPrompt() {
        if (!chatSessionMode || chatTurnInFlight || pendingChatPrompts.isEmpty()) return
        val live = geminiLive ?: return
        if (!live.isConnected) return
        val prompt = pendingChatPrompts.removeAt(0)
        chatTurnInFlight = true
        binding.chatStatusText.text = "Fawad AI is thinking…"
        live.sendText(prompt)
    }

    private fun flushChatResponse() {
        val response = outputBuffer.toString().trim()
        outputBuffer.setLength(0)
        inputBuffer.setLength(0)
        if (response.isNotBlank()) addChat(ChatMessage(response, false))
        chatTurnInFlight = false
        if (currentPage == Page.CHAT) binding.chatStatusText.text = "Connected · ready for your message"
        sendNextChatPrompt()
    }

    private fun confirmClearChat() {
        AlertDialog.Builder(this)
            .setTitle("Clear conversation?")
            .setMessage("This removes the saved chat on this device and starts a fresh AI session.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Clear") { _, _ ->
                chatMessages.clear()
                chatAdapter.notifyDataSetChanged()
                ChatHistoryStore.save(prefs, chatMessages)
                pendingChatPrompts.clear()
                chatTurnInFlight = false
                outputBuffer.setLength(0)
                inputBuffer.setLength(0)
                geminiLive?.disconnect()
                geminiLive = null
                liveConnectionStarted = false
                assistantStarted = false
                audioEngine?.release()
                audioEngine = null
                if (currentPage == Page.CHAT) connectChat()
            }
            .show()
    }

    private fun buildChatContext(): String {
        val history = chatMessages.toMutableList()
        repeat(pendingChatPrompts.size) {
            if (history.lastOrNull()?.isUser == true) history.removeAt(history.lastIndex)
        }
        return history.takeLast(16).joinToString("\n") {
            "${if (it.isUser) "User" else "Fawad AI"}: ${it.text}"
        }.takeLast(6000)
    }

    private fun startSystemServices() {
        // Start optional services only when their special permissions are
        // already enabled; never leave the app during first use.
        if (Settings.canDrawOverlays(this)) {
            try { startService(Intent(this, FawadsOverlayService::class.java)) } catch (_: Exception) {}
        }
        // Call monitor
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
            try { ContextCompat.startForegroundService(this, Intent(this, CallMonitorService::class.java)) } catch (_: Exception) {}
        }
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
        binding.batteryText.text = "Battery $battery%"

        // RAM
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        val totalMb = info.totalMem / (1024 * 1024)
        val availMb = info.availMem / (1024 * 1024)
        binding.ramText.text = String.format(Locale.getDefault(), "RAM %.1f GB free", availMb / 1024f)

        // Time
        binding.timeText.text = SimpleDateFormat("h:mm", Locale.getDefault()).format(Date())
    }

    private fun checkPermissions() {
        if (assistantStarted || permissionRequestInProgress) return
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
            permissionRequestInProgress = true
            ActivityCompat.requestPermissions(this, toRequest.toTypedArray(), REQ_PERMS)
        } else {
            proceedAfterPermissions()
        }
    }

    // ---------------------------- GEMINI + AUDIO ----------------------------
    private fun initGeminiLive(startVoice: Boolean = true) {
        if (prefs.apiKey.isBlank()) return
        if (geminiLive == null) {
            geminiLive = GeminiLiveClient(prefs, object : GeminiLiveClient.Listener {
                override fun onConnected() {
                    runOnUiThread {
                        liveConnectionStarted = true
                        if (currentPage == Page.VOICE && !chatSessionMode) {
                            setActive(true)
                            status("Connected")
                        }
                        if (chatSessionMode) {
                            binding.chatStatusText.text = "Connected · ready for your message"
                            sendNextChatPrompt()
                        } else {
                            val caller = pendingIncomingCall
                            if (caller != null) {
                                pendingIncomingCall = null
                                announceCall(caller)
                            } else if (currentPage == Page.VOICE) {
                                sendGreeting()
                            }
                        }
                    }
                }

                override fun onDisconnected() {
                    runOnUiThread {
                        if (currentPage == Page.CHAT) binding.chatStatusText.text = "Connection lost · reconnecting…"
                        else if (currentPage == Page.VOICE) status("Reconnecting…")
                    }
                }

                override fun onAudioReceived(base64Pcm: String) {
                    if (currentPage == Page.VOICE && !chatSessionMode) audioEngine?.queueAudio(base64Pcm)
                }

                override fun onInputTranscript(text: String) {
                    if (!chatSessionMode && currentPage == Page.VOICE) {
                        runOnUiThread { inputBuffer.append(text).append(' ') }
                    }
                }

                override fun onOutputTranscript(text: String) {
                    runOnUiThread { outputBuffer.append(text) }
                }

                override fun onTurnComplete() {
                    runOnUiThread {
                        if (chatSessionMode || currentPage != Page.VOICE) flushChatResponse()
                        else flushTranscription()
                    }
                }

                override fun onError(error: Throwable) {
                    runOnUiThread {
                        val message = error.message ?: "Connection error"
                        if (chatSessionMode || currentPage == Page.CHAT) {
                            binding.chatStatusText.text = message
                        } else if (currentPage == Page.VOICE) {
                            status(message)
                        }
                    }
                }
            }, priorChatContext = if (chatSessionMode) buildChatContext() else "")
        }

        if (startVoice) initAudioEngine()
        if (!liveConnectionStarted) {
            liveConnectionStarted = true
            geminiLive?.connect()
        }
    }

    private fun initAudioEngine() {
        if (audioEngine == null) {
            audioPlaybackStarted = false
            audioEngine = AudioEngine().apply {
                onAudioChunk = { chunk ->
                    if (currentPage == Page.VOICE && !chatSessionMode && !handsFreeActive) geminiLive?.sendAudio(chunk)
                }
                onSpeakingStarted = {
                    runOnUiThread {
                        if (currentPage == Page.VOICE) {
                            setActive(true)
                            status("Speaking…")
                            binding.orbView.setState(OrbAnimationView.State.SPEAKING)
                            binding.waveformView.setAmplitude(0.58f)
                            pulseVoiceHaptic()
                        }
                    }
                }
                onSpeakingStopped = {
                    runOnUiThread {
                        if (currentPage == Page.VOICE) {
                            status("Listening…")
                            binding.orbView.setState(OrbAnimationView.State.LISTENING)
                            binding.waveformView.setAmplitude(0.08f)
                        }
                    }
                }
                onAmplitudeChanged = { rms ->
                    runOnUiThread {
                        val level = if (audioEngine?.isSpeaking == true) maxOf(0.42f, rms * 5f) else (rms * 8f).coerceIn(0f, 1f)
                        binding.waveformView.setAmplitude(level)
                        binding.orbView.setAmplitude(level)
                    }
                }
            }
        }
        audioEngine?.setMuted(muted)
        audioEngine?.startRecording()
        if (!audioPlaybackStarted) {
            audioEngine?.startPlayback()
            audioPlaybackStarted = true
        }
        applyMuteUi()
    }

    private fun pulseVoiceHaptic() {
        if (!prefs.voiceHaptics || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            val vibrator = getSystemService(Vibrator::class.java)
            if (vibrator?.hasVibrator() == true) vibrator.vibrate(VibrationEffect.createOneShot(18, 48))
        }
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
        binding.voicePauseButton.text = if (muted) "▶" else "Ⅱ"
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
            val caller = intent.getStringExtra(EXTRA_CALLER_NAME) ?: "Unknown"
            inCallMode = true
            if (assistantStarted) {
                announceCall(caller)
            } else {
                pendingIncomingCall = caller
                startAssistant()
            }
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
        ChatHistoryStore.save(prefs, chatMessages)
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
        audioEngine?.stopRecording()
    }

    override fun onResume() {
        super.onResume()
        binding.greetingText.text = personalizedGreeting()
        val currentKey = prefs.apiKey
        val currentSettings = settingsSignature()
        if (lastApiKey != currentKey || lastSettingsSignature != currentSettings) {
            if (chatTurnInFlight) {
                chatMessages.lastOrNull { it.isUser }?.let { pendingChatPrompts.add(0, it.text) }
            }
            chatTurnInFlight = false
            outputBuffer.setLength(0)
            geminiLive?.disconnect()
            geminiLive = null
            liveConnectionStarted = false
            audioEngine?.release()
            audioEngine = null
            audioPlaybackStarted = false
            assistantStarted = false
            lastApiKey = currentKey
            lastSettingsSignature = currentSettings
        }
        if (currentPage == Page.VOICE && currentKey.isNotBlank()) {
            if (!assistantStarted) {
                startAssistant()
            } else {
                initGeminiLive(startVoice = true)
                if (prefs.handsFree) startHandsFree() else audioEngine?.setMuted(muted)
            }
        } else {
            audioEngine?.setMuted(true)
        }
        if (currentPage == Page.CHAT) {
            if (currentKey.isNotBlank()) connectChat()
            else binding.chatStatusText.text = "Set your Gemini API key in Menu → Settings to chat."
        }
        if (this::taskAdapter.isInitialized) refreshTasks()
    }

    private fun settingsSignature(): String = listOf(
        prefs.userName, prefs.apiKey, prefs.geminiModel, prefs.geminiVoice,
        prefs.personalityMode, prefs.handsFree, prefs.wakeWord
    ).joinToString("|")

    private fun personalizedGreeting(): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val greeting = when {
            hour < 12 -> "Good morning"
            hour < 17 -> "Good afternoon"
            else -> "Good evening"
        }
        return "$greeting, ${prefs.userName} 👋"
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
            permissionRequestInProgress = false
            if (grantResults.any { it != PackageManager.PERMISSION_GRANTED }) {
                toast("Some permissions were denied — features may be limited")
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                proceedAfterPermissions()
            } else {
                status("Microphone permission is needed for voice")
                showHomeScreen()
            }
        } else if (requestCode == REQ_NOTIFICATIONS &&
            grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED
        ) {
            toast("Allow notifications if you want reminder alerts")
        }
    }
}
