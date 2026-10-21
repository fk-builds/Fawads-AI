package com.fawads.ai.ui.settings

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.fawads.ai.R
import com.fawads.ai.databinding.ActivitySettingsBinding
import com.fawads.ai.service.AccessibilityHelperService
import com.fawads.ai.util.Prefs
import com.fawads.ai.util.UpdateManager
import com.fawads.ai.util.checkForUpdates
import com.fawads.ai.util.PrimeContact

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: Prefs
    private val primeContacts = mutableListOf<PrimeContact>()
    private lateinit var primeAdapter: PrimeContactAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        setupSpinners()
        loadPrefs()
        setupPrimeContacts()
        setupAccessibility()
        setupSecurity()
        setupHandsFree()
        setupUpdates()
        setupSettingsSections()
        installPressFeedback(binding.root)
        binding.root.findViewById<View>(R.id.settingsBack).setOnClickListener { finish() }

        binding.saveButton.setOnClickListener { save() }
    }

    private fun setupSettingsSections() {
        val cardIds = listOf(
            R.id.profileSettingsCard, R.id.aiSettingsCard, R.id.personalitySettingsCard,
            R.id.contactsSettingsCard, R.id.securitySettingsCard, R.id.voiceSettingsCard,
            R.id.accessibilitySettingsCard, R.id.updatesSettingsCard
        )
        val tabs = listOf(
            findViewById<TextView>(R.id.settingsProfileTab),
            findViewById<TextView>(R.id.settingsAiTab),
            findViewById<TextView>(R.id.settingsContactsTab),
            findViewById<TextView>(R.id.settingsSecurityTab),
            findViewById<TextView>(R.id.settingsAppTab)
        )
        val groups = listOf(
            setOf(R.id.profileSettingsCard),
            setOf(R.id.aiSettingsCard, R.id.personalitySettingsCard, R.id.voiceSettingsCard),
            setOf(R.id.contactsSettingsCard),
            setOf(R.id.securitySettingsCard),
            setOf(R.id.accessibilitySettingsCard, R.id.updatesSettingsCard)
        )
        fun select(index: Int) {
            cardIds.forEach { id -> findViewById<View>(id).visibility = if (id in groups[index]) View.VISIBLE else View.GONE }
            tabs.forEachIndexed { tabIndex, tab ->
                val selected = tabIndex == index
                tab.setBackgroundResource(if (selected) R.drawable.bg_nav_active else R.drawable.bg_chip)
                tab.setTextColor(ContextCompat.getColor(this, if (selected) R.color.text_primary else R.color.text_hint))
                tab.isSelected = selected
            }
        }
        tabs.forEachIndexed { index, tab -> tab.setOnClickListener { select(index) } }
        select(0)
    }

    private fun installPressFeedback(view: View) {
        if (view.isClickable || view is androidx.appcompat.widget.SwitchCompat || view is android.widget.Button) {
            view.setOnTouchListener { target, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        target.animate().scaleX(0.97f).scaleY(0.97f).setDuration(80).start()
                        target.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> target.animate().scaleX(1f).scaleY(1f).setDuration(110).start()
                }
                false
            }
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) installPressFeedback(view.getChildAt(i))
    }

    private fun setupUpdates() {
        binding.updateUrlInput.setText(prefs.updateFeedUrl)
        binding.autoUpdateSwitch.isChecked = prefs.autoUpdateChecks
        val code = UpdateManager.currentVersionCode(this)
        binding.updateVersionText.text =
            "v" + UpdateManager.currentVersionName(this) + "  (build " + code + ")" +
            if (UpdateManager.isEnabled(this)) "" else "  •  updates OFF"
        binding.updateCheckBtn.setOnClickListener {
            prefs.updateFeedUrl = binding.updateUrlInput.text?.toString()?.trim().orEmpty()
            prefs.autoUpdateChecks = binding.autoUpdateSwitch.isChecked
            prefs.skippedVersionCode = -1
            checkForUpdates(manual = true)
        }
    }

    private fun setupSecurity() {
        binding.securityBtn.setOnClickListener {
            startActivity(Intent(this, com.fawads.ai.ui.security.SecurityActivity::class.java))
        }
    }

    private fun setupHandsFree() {
        binding.handsFreeSwitch.isChecked = prefs.handsFree
        binding.voiceHapticsSwitch.isChecked = prefs.voiceHaptics
        binding.handsFreeSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.handsFree = checked
        }
        binding.wakeWordInput.setText(prefs.wakeWord)
    }

    private fun setupSpinners() {
        val modelAdapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, Prefs.MODELS.map { friendly(it) }
        )
        modelAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.modelSpinner.adapter = modelAdapter

        val voiceAdapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, Prefs.VOICES.toList()
        )
        voiceAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.voiceSpinner.adapter = voiceAdapter
    }

    private fun friendly(model: String): String = when (model) {
        "models/gemini-2.5-flash-native-audio-preview-12-2025" -> "Native Audio (Human Voice) — DEFAULT"
        "models/gemini-2.0-flash-live-001" -> "Flash Live (Fast)"
        "models/gemini-2.5-flash-preview-native-audio-dialog" -> "Pro Audio Dialog"
        else -> model
    }

    private fun loadPrefs() {
        binding.apiKeyInput.setText(prefs.apiKey)
        binding.nameInput.setText(prefs.userName)

        val modelIdx = Prefs.MODELS.indexOf(prefs.geminiModel).coerceAtLeast(0)
        binding.modelSpinner.setSelection(modelIdx)
        val voiceIdx = Prefs.VOICES.indexOf(prefs.geminiVoice).coerceAtLeast(0)
        binding.voiceSpinner.setSelection(voiceIdx)

        when (prefs.personalityMode) {
            Prefs.PERSONALITY_PRO -> binding.radioProfessional.isChecked = true
            Prefs.PERSONALITY_ASSISTANT -> binding.radioAssistant.isChecked = true
            else -> binding.radioGf.isChecked = true
        }
    }

    private fun setupPrimeContacts() {
        primeContacts.clear()
        primeContacts.addAll(prefs.getPrimeContacts())
        primeAdapter = PrimeContactAdapter(primeContacts) { contact ->
            primeContacts.remove(contact)
            prefs.savePrimeContacts(primeContacts)
            primeAdapter.notifyDataSetChanged()
        }
        binding.primeRecycler.layoutManager = LinearLayoutManager(this)
        binding.primeRecycler.adapter = primeAdapter

        binding.addPrimeBtn.setOnClickListener { showAddPrimeDialog() }
    }

    private fun showAddPrimeDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_add_prime_contact, null)
        val nameInput = view.findViewById<EditText>(R.id.dialogNameInput)
        val numInput = view.findViewById<EditText>(R.id.dialogNumberInput)
        AlertDialog.Builder(this)
            .setTitle("Add Prime Contact")
            .setView(view)
            .setPositiveButton("Add") { _, _ ->
                val name = nameInput.text?.toString()?.trim().orEmpty()
                val num = numInput.text?.toString()?.trim().orEmpty()
                if (name.isNotBlank() && num.isNotBlank()) {
                    primeContacts.add(PrimeContact(name, num))
                    prefs.savePrimeContacts(primeContacts)
                    primeAdapter.notifyDataSetChanged()
                } else {
                    toast("Both name and number are required")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setupAccessibility() {
        refreshAccessStatus()
        binding.accessStatus.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    private fun refreshAccessStatus() {
        val enabled = AccessibilityHelperService.isEnabled(this)
        binding.accessStatus.text =
            if (enabled) "✅ Accessibility enabled" else "❌ Accessibility disabled — tap to enable"
        binding.accessStatus.setTextColor(
            ContextCompat.getColor(this, if (enabled) R.color.success else R.color.error)
        )
    }

    private fun save() {
        prefs.apiKey = binding.apiKeyInput.text?.toString()?.trim().orEmpty()
        prefs.userName = binding.nameInput.text?.toString()?.trim()?.ifEmpty { "Fawad" } ?: "Fawad"
        prefs.voiceHaptics = binding.voiceHapticsSwitch.isChecked
        prefs.geminiModel = Prefs.MODELS.getOrNull(binding.modelSpinner.selectedItemPosition) ?: Prefs.DEFAULT_MODEL
        prefs.geminiVoice = Prefs.VOICES.getOrNull(binding.voiceSpinner.selectedItemPosition) ?: Prefs.DEFAULT_VOICE
        val personality = when (binding.personalityGroup.checkedRadioButtonId) {
            R.id.radioProfessional -> Prefs.PERSONALITY_PRO
            R.id.radioAssistant -> Prefs.PERSONALITY_ASSISTANT
            else -> Prefs.PERSONALITY_GF
        }
        prefs.personalityMode = personality
        val wake = binding.wakeWordInput.text?.toString()?.trim().orEmpty()
        if (wake.isNotBlank()) prefs.wakeWord = wake
        prefs.updateFeedUrl = binding.updateUrlInput.text?.toString()?.trim().orEmpty()
        prefs.autoUpdateChecks = binding.autoUpdateSwitch.isChecked
        setResult(RESULT_OK)
        toast("Settings saved ✓")
    }

    override fun onResume() {
        super.onResume()
        refreshAccessStatus()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ---------------------------- Prime Contact Adapter ----------------------------
    class PrimeContactAdapter(
        private val list: MutableList<PrimeContact>,
        private val onDelete: (PrimeContact) -> Unit
    ) : RecyclerView.Adapter<PrimeContactAdapter.VH>() {

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.primeItemName)
            val number: TextView = view.findViewById(R.id.primeItemNumber)
            val delete: ImageButton = view.findViewById(R.id.primeItemDelete)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_prime_contact, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val c = list[position]
            holder.name.text = c.name
            holder.number.text = c.number
            holder.delete.setOnClickListener { onDelete(c) }
        }

        override fun getItemCount(): Int = list.size
    }
}
