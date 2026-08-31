package com.fawads.ai.viewmodel

import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.ContactsContract
import android.telecom.TelecomManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.fawads.ai.model.AppCommand
import com.fawads.ai.model.CommandType
import com.fawads.ai.service.AccessibilityHelperService
import com.fawads.ai.ui.notes.NotesActivity
import com.fawads.ai.util.InfoProviders
import com.fawads.ai.util.Prefs
import com.fawads.ai.util.TaskManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application
    private val prefs = Prefs(application)

    private val _commandResult = MutableLiveData<String?>()
    val commandResult: LiveData<String?> = _commandResult

    private val _callDecision = MutableLiveData<String?>()
    val callDecision: LiveData<String?> = _callDecision

    fun execute(command: AppCommand) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCommand(command) }
            _commandResult.postValue(result)
        }
    }

    private suspend fun runCommand(command: AppCommand): String {
        return when (command.type) {
            CommandType.OPEN_APP -> openApp(command.params["app_name"] ?: "")
            CommandType.CLOSE_APP -> closeApp()
            CommandType.CALL -> call(command.params["name"] ?: "")
            CommandType.SMS -> sendSms(command.params["name"] ?: "", command.params["message"] ?: "")
            CommandType.WHATSAPP_MSG -> whatsappMessage(command.params["name"] ?: "", command.params["message"] ?: "")
            CommandType.WHATSAPP_CALL -> whatsappCall(command.params["name"] ?: "")
            CommandType.PRIME_CALL -> {
                val idx = command.params["index"]?.toIntOrNull() ?: 0
                val p = prefs.getPrimeContacts().getOrNull(idx) ?: return "I couldn't find that prime contact"
                call(p.number)
            }
            CommandType.PRIME_MSG -> {
                val idx = command.params["index"]?.toIntOrNull() ?: 0
                val p = prefs.getPrimeContacts().getOrNull(idx) ?: return "I couldn't find that prime contact"
                whatsappMessage(p.name, command.params["message"] ?: "")
            }
            CommandType.VOLUME_UP -> volume(true)
            CommandType.VOLUME_DOWN -> volume(false)
            CommandType.FLASHLIGHT_ON -> flashlight(true)
            CommandType.FLASHLIGHT_OFF -> flashlight(false)
            CommandType.WIFI_ON -> wifi(true)
            CommandType.WIFI_OFF -> wifi(false)
            CommandType.BLUETOOTH_ON -> bluetooth(on = true)
            CommandType.BLUETOOTH_OFF -> bluetooth(on = false)
            CommandType.MUTE -> { prefs.isMuted = true; "Muted" }
            CommandType.UNMUTE -> { prefs.isMuted = false; "Unmuted" }
            CommandType.STOP -> "Stopped"
            // ---- Extended features ----
            CommandType.ALARM -> setAlarm(command.params["time"] ?: "")
            CommandType.TIMER -> setTimer(command.params["minutes"] ?: "1")
            CommandType.REMINDER -> setReminder(command.params["message"] ?: "Reminder", command.params["time"] ?: "")
            CommandType.ADD_NOTE -> addNote(command.params["note"] ?: "")
            CommandType.OPEN_NOTES -> openNotes()
            CommandType.WEATHER -> weather(command.params["city"] ?: "")
            CommandType.NEWS -> news(command.params["topic"] ?: "top stories")
            CommandType.CRYPTO -> crypto(command.params["coin"] ?: "bitcoin")
            CommandType.PLAY_MUSIC -> playMusic(command.params["query"] ?: "")
            CommandType.SEARCH_YOUTUBE -> searchYouTube(command.params["query"] ?: "")
            CommandType.SEARCH_WEB -> searchWeb(command.params["query"] ?: "")
            else -> "I didn't quite get that"
        }
    }

    // --------------------------- APPS ---------------------------
    private fun openApp(name: String): String {
        val nm = name.trim().lowercase()
        var pkg = appPackages[nm]
        if (pkg == null) pkg = findAppByLabel(nm)
        if (pkg != null) {
            try {
                val intent = app.packageManager.getLaunchIntentForPackage(pkg)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    app.startActivity(intent)
                    return "Opening $name"
                }
            } catch (_: Exception) {}
        }
        return "I couldn't find the app \"$name\""
    }

    private fun findAppByLabel(label: String): String? {
        val pm = app.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(intent, 0)
        return apps.firstOrNull {
            it.loadLabel(pm).toString().lowercase().contains(label)
        }?.activityInfo?.packageName
    }

    private fun closeApp(): String {
        return if (!AccessibilityHelperService.isEnabled(app)) {
            "Please enable Accessibility first, then I can close apps."
        } else {
            AccessibilityHelperService.instance?.closeCurrentApp()
            "Closed the current app"
        }
    }

    // --------------------------- CALL / SMS / WHATSAPP ---------------------------
    private fun call(name: String): String {
        val number = resolveNumber(name) ?: return "I couldn't find contact \"$name\""
        return try {
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            app.startActivity(intent)
            "Calling $name"
        } catch (e: SecurityException) {
            "I need call permission to do that"
        }
    }

    private fun sendSms(name: String, message: String): String {
        val number = resolveNumber(name) ?: return "I couldn't find contact \"$name\""
        return try {
            val uri = Uri.parse("smsto:$number")
            val intent = Intent(Intent.ACTION_SENDTO, uri)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("sms_body", message)
            app.startActivity(intent)
            "Opened SMS to $name"
        } catch (e: Exception) {
            "Couldn't send SMS"
        }
    }

    private fun whatsappMessage(name: String, message: String): String {
        val number = resolveNumber(name) ?: return "I couldn't find contact \"$name\""
        return try {
            val clean = number.replace("+", "").replace(" ", "").replace("-", "")
            val uri = Uri.parse("https://wa.me/$clean?text=${Uri.encode(message)}")
            val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            app.startActivity(intent)
            "Opening WhatsApp for $name"
        } catch (e: Exception) {
            "Couldn't open WhatsApp"
        }
    }

    private fun whatsappCall(name: String): String {
        val number = resolveNumber(name) ?: return "I couldn't find contact \"$name\""
        val clean = number.replace("+", "").replace(" ", "").replace("-", "")
        return try {
            val uri = Uri.parse("https://wa.me/$clean")
            app.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "Opening WhatsApp for $name"
        } catch (e: Exception) {
            "Couldn't open WhatsApp"
        }
    }

    private fun resolveNumber(name: String): String? {
        val trimmed = name.trim()
        if (trimmed.length >= 7 &&
            (trimmed.all { it.isDigit() } || trimmed.contains("+") ||
                trimmed.contains("-") || trimmed.contains(" "))
        ) return trimmed
        try {
            val cr: ContentResolver = app.contentResolver
            cr.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                CONTACT_PROJECTION, null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val display = c.getString(0) ?: ""
                    if (display.equals(trimmed, ignoreCase = true)) return c.getString(1)
                }
            }
        } catch (_: Exception) {}
        return null
    }

    // --------------------------- SYSTEM ---------------------------
    private fun volume(up: Boolean): String {
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val dir = if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
        val level = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        return "Volume: $level"
    }

    private fun flashlight(on: Boolean): String {
        return try {
            val cm = app.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull() ?: return "No flashlight found"
            cm.setTorchMode(id, on)
            if (on) "Torch on" else "Torch off"
        } catch (e: Exception) {
            "Couldn't toggle flashlight"
        }
    }

    private fun wifi(on: Boolean): String {
        return try {
            val wm = app.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wm.setWifiEnabled(on)
            if (on) "WiFi turned on" else "WiFi turned off"
        } catch (e: Exception) {
            "Couldn't toggle WiFi"
        }
    }

    private fun bluetooth(on: Boolean): String {
        return try {
            val bm = app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val adapter = bm.adapter ?: return "No Bluetooth found"
            if (on) adapter.enable() else adapter.disable()
            if (on) "Bluetooth on" else "Bluetooth off"
        } catch (e: Exception) {
            "Couldn't toggle Bluetooth"
        }
    }

    // --------------------------- EXTENDED FEATURES ---------------------------
    private fun setAlarm(time: String): String {
        val parts = time.split(":").mapNotNull { it.trim().toIntOrNull() }
        if (parts.size != 2) return "Kis waqt alarm lagana hai? Jaise '7:30' bolo."
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, parts[0])
            set(Calendar.MINUTE, parts[1])
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        TaskManager.scheduleReminder(app, cal.timeInMillis, "Alarm — aapka waqt ho gaya! ⏰", 5001)
        return "Alarm set ho gaya ${parts[0]}:${parts[1].toString().padStart(2, '0')} ⏰"
    }

    private fun setTimer(minutes: String): String {
        val mins = minutes.toIntOrNull() ?: 1
        TaskManager.scheduleTimer(app, mins * 60_000L, "Timer khatam! $mins minute ho gaye ⏳", 5002)
        return "$mins minute ka timer laga diya ⏳"
    }

    private fun setReminder(message: String, time: String): String {
        if (time.isNotBlank()) {
            val parts = time.split(":").mapNotNull { it.trim().toIntOrNull() }
            if (parts.size == 2) {
                val cal = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, parts[0])
                    set(Calendar.MINUTE, parts[1])
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                    if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
                }
                TaskManager.scheduleReminder(app, cal.timeInMillis, message, 5003)
                return "Yaad dilaya jaayega: \"$message\""
            }
        }
        return "Batao kab yaad dilana hai (time ke saath)."
    }

    private fun addNote(note: String): String {
        if (note.isBlank()) return "Kya note karna hai?"
        TaskManager.addNote(app, note)
        return "Note save ho gayi: \"$note\" 📝"
    }

    private fun openNotes(): String {
        try {
            val intent = Intent(app, NotesActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            app.startActivity(intent)
            return "Notes khul gayi"
        } catch (e: Exception) {
            return "Notes khol nahi sakta"
        }
    }

    private suspend fun weather(city: String): String {
        val (lat, lon) = InfoProviders.geocode(city)
        return try {
            InfoProviders.weather(lat, lon)
        } catch (e: Exception) {
            "Weather fetch nahi hua: ${e.message}"
        }
    }

    private suspend fun news(topic: String): String {
        return try {
            InfoProviders.news(topic)
        } catch (e: Exception) {
            "Khabar fetch nahi hui: ${e.message}"
        }
    }

    private suspend fun crypto(coin: String): String {
        return try {
            InfoProviders.crypto(coin)
        } catch (e: Exception) {
            "Crypto rate nahi mila: ${e.message}"
        }
    }

    private fun playMusic(query: String): String {
        if (query.isBlank()) return "Konsa gaana chalaun?"
        try {
            val url = "https://www.youtube.com/results?search_query=" + Uri.encode(query)
            app.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return "Gaana chala rahi hoon: \"$query\" 🎵"
        } catch (e: Exception) { return "Gaana nahi chala paayi" }
    }

    private fun searchYouTube(query: String): String {
        if (query.isBlank()) return "Kya search karna hai YouTube pe?"
        try {
            val url = "https://www.youtube.com/results?search_query=" + Uri.encode(query)
            app.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return "YouTube pe \"$query\" search kar rahi hoon"
        } catch (e: Exception) { return "Search nahi ho sakti" }
    }

    private fun searchWeb(query: String): String {
        if (query.isBlank()) return "Kya search karna hai?"
        try {
            val url = "https://www.google.com/search?q=" + Uri.encode(query)
            app.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return "Google pe \"$query\" search kar rahi hoon"
        } catch (e: Exception) { return "Search nahi ho sakti" }
    }

    // --------------------------- CALL DECISION ---------------------------
    fun acceptCall() {
        try {
            val tm = app.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) tm.acceptRingingCall()
            _callDecision.postValue("Call accepted")
        } catch (e: Exception) {
            _callDecision.postValue("Couldn't accept the call")
        }
    }

    fun rejectCall() {
        try {
            val tm = app.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) tm.endCall()
            _callDecision.postValue("Call rejected")
        } catch (e: Exception) {
            _callDecision.postValue("Couldn't end the call")
        }
    }

    companion object {
        private val CONTACT_PROJECTION = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )

        private val appPackages = mapOf(
            "youtube" to "com.google.android.youtube",
            "whatsapp" to "com.whatsapp",
            "instagram" to "com.instagram.android",
            "facebook" to "com.facebook.katana",
            "chrome" to "com.android.chrome",
            "browser" to "com.android.chrome",
            "gmail" to "com.google.android.gm",
            "maps" to "com.google.android.apps.maps",
            "spotify" to "com.spotify.music",
            "netflix" to "com.netflix.mediaclient",
            "twitter" to "com.twitter.android",
            "x" to "com.twitter.android",
            "telegram" to "org.telegram.messenger",
            "snapchat" to "com.snapchat.android",
            "settings" to "com.android.settings",
            "calculator" to "com.google.android.calculator",
            "calendar" to "com.google.android.calendar",
            "clock" to "com.google.android.deskclock",
            "phone" to "com.google.android.dialer",
            "contacts" to "com.google.android.contacts",
            "play store" to "com.android.vending",
            "amazon" to "com.amazon.mShop.android.shopping",
            "flipkart" to "com.flipkart.android",
            "paytm" to "net.one97.paytm",
            "phonepe" to "com.phonepe.app",
            "gpay" to "com.google.android.apps.nbu.paisa",
            "google pay" to "com.google.android.apps.nbu.paisa",
            "zoom" to "us.zoom.videomeetings",
            "meet" to "com.google.android.apps.meetings",
            "teams" to "com.microsoft.teams",
            "tiktok" to "com.zhiliaoapp.musically",
            "discord" to "com.discord",
            "linkedin" to "com.linkedin.android",
            "camera" to "com.google.android.GoogleCamera",
            "gallery" to "com.google.android.apps.photos",
            "photos" to "com.google.android.apps.photos",
            "files" to "com.google.android.documentsui",
            "music" to "com.google.android.apps.youtube.music"
        )
    }
}
