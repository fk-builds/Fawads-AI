package com.fawads.ai.ui.security

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.fawads.ai.R
import com.fawads.ai.ai.FaceMatcher
import com.fawads.ai.databinding.ActivitySecurityBinding
import com.fawads.ai.util.Prefs
import com.fawads.ai.util.SecurityUtil

/**
 * Setup for the generic security lock.
 * Whoever sets the PIN and enrolls their face owns the lock — portable across users.
 */
class SecurityActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySecurityBinding
    private lateinit var prefs: Prefs

    private var enrollCamera: FaceCamera? = null
    private val enrollFrames = mutableListOf<FloatArray>()
    private var enrolling = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySecurityBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        binding.lockSwitch.isChecked = prefs.securityEnabled
        binding.lockSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked && prefs.pinHash.isBlank() && !prefs.hasFaceEnrolled) {
                toast("Pehle PIN ya Face set karo")
                binding.lockSwitch.isChecked = false
            } else {
                prefs.securityEnabled = checked
            }
            refreshStatus()
        }

        binding.setPinBtn.setOnClickListener { showSetPinDialog() }
        binding.enrollFaceBtn.setOnClickListener { toggleEnroll() }

        refreshStatus()
    }

    private fun refreshStatus() {
        binding.pinStatus.text =
            if (prefs.pinHash.isNotBlank()) "PIN set ✓" else "PIN not set"
        binding.faceStatus.text =
            if (prefs.hasFaceEnrolled) "Face enrolled ✓ (${SecurityUtil.base64ToFloats(prefs.faceDescriptor)?.size ?: 0} pts)" else "No face enrolled"
        binding.lockSwitch.isChecked = prefs.securityEnabled
    }

    private fun showSetPinDialog() {
        val input = EditText(this).apply {
            hint = "4-digit PIN"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this)
            .setTitle("Set PIN")
            .setMessage("Is PIN se hi lock set hoga. (Baad me change kar sakte ho.)")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val pin = input.text.toString().trim()
                if (pin.length >= 4) {
                    val salt = SecurityUtil.generateSalt()
                    prefs.pinSalt = salt
                    prefs.pinHash = SecurityUtil.hashPin(pin, salt)
                    toast("PIN saved")
                    refreshStatus()
                } else toast("Kam az kam 4 digit")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toggleEnroll() {
        if (enrolling) { stopEnroll(); return }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQ_CAM)
            return
        }
        binding.enrollPreview.visibility = android.view.View.VISIBLE
        binding.enrollFaceBtn.text = "Cancel Enroll"
        enrolling = true
        enrollFrames.clear()

        enrollCamera?.release()
        enrollCamera = FaceCamera(this, this, binding.enrollPreview) { desc ->
            if (desc != null) {
                enrollFrames.add(desc)
                val need = FaceMatcher.ENROLL_FRAMES
                if (enrollFrames.size >= need) {
                    runOnUiThread { finishEnroll() }
                } else {
                    runOnUiThread { binding.faceStatus.text = "Enrolling… ${enrollFrames.size}/$need — move slightly" }
                }
            }
        }
        enrollCamera?.start()
    }

    private fun finishEnroll() {
        val avg = FaceMatcher.average(enrollFrames.toList())
        if (avg != null) {
            prefs.faceDescriptor = SecurityUtil.floatsToBase64(avg)
            prefs.hasFaceEnrolled = true
            prefs.useFace = true
            toast("Face enrolled ✓")
        } else {
            toast("Enroll fail. Try again.")
        }
        stopEnroll()
        refreshStatus()
    }

    private fun stopEnroll() {
        enrolling = false
        enrollCamera?.release()
        enrollCamera = null
        binding.enrollPreview.visibility = android.view.View.GONE
        binding.enrollFaceBtn.text = "Enroll Face"
        refreshStatus()
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    companion object {
        private const val REQ_CAM = 51
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAM && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            toggleEnroll()
        } else if (requestCode == REQ_CAM) {
            toast("Camera permission needed")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        enrollCamera?.release()
    }
}
