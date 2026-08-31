package com.fawads.ai.ui.security

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.Manifest
import android.content.pm.PackageManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.fawads.ai.R
import com.fawads.ai.ai.FaceMatcher
import com.fawads.ai.databinding.ActivityLockBinding
import com.fawads.ai.ui.main.MainActivity
import com.fawads.ai.util.Prefs
import com.fawads.ai.util.SecurityUtil

/**
 * The unlock gate shown on every launch (when security is enabled).
 * Whoever set the PIN / enrolled their face is the person who unlocks it.
 */
class LockActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_UNLOCKED = "unlocked"
        const val PIN_LENGTH = 4
        private const val REQ_CAM = 41
    }

    private lateinit var binding: ActivityLockBinding
    private lateinit var prefs: Prefs

    private val pin = StringBuilder()
    private var usingFace = false
    private var faceCamera: FaceCamera? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLockBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        buildPinDots()
        buildKeypad()

        binding.scanFaceBtn.setOnClickListener {
            if (prefs.hasFaceEnrolled && prefs.useFace) startFaceScan()
            else toast("Pehle Security settings mein face enroll karo")
        }
        binding.usePinBtn.setOnClickListener {
            toggle(usePin = true)
        }

        // If face is enabled, start scan immediately.
        if (prefs.useFace && prefs.hasFaceEnrolled) startFaceScan() else toggle(usePin = true)
    }

    private fun toggle(usePin: Boolean) {
        usingFace = !usePin
        binding.facePreview.visibility = if (usingFace) View.VISIBLE else View.GONE
        binding.lockPanel.visibility = if (usingFace) View.INVISIBLE else View.VISIBLE
        binding.keypad.visibility = if (usePin) View.VISIBLE else View.GONE
        if (usingFace) startFaceScan() else stopFaceScan()
    }

    // ------------------------- FACE -------------------------
    private fun startFaceScan() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO), REQ_CAM)
            return
        }
        usingFace = true
        binding.lockPanel.visibility = View.INVISIBLE
        binding.facePreview.visibility = View.VISIBLE
        binding.lockStatus.text = "Look straight into the camera…"
        val enrolled = SecurityUtil.base64ToFloats(prefs.faceDescriptor)
        if (enrolled == null) { toast("Face data missing"); toggle(usePin = true); return }
        faceCamera?.release()
        faceCamera = FaceCamera(this, this, binding.facePreview) { desc ->
            if (desc == null) return@FaceCamera
            runOnUiThread {
                if (desc != null && FaceMatcher.isMatch(enrolled, desc)) {
                    unlock()
                } else {
                    binding.lockStatus.text = "Face not recognised — try again"
                }
            }
        }
        faceCamera?.start()
    }

    private fun stopFaceScan() {
        faceCamera?.release()
        faceCamera = null
    }

    // ------------------------- PIN -------------------------
    private fun buildPinDots() {
        binding.pinDots.removeAllViews()
        repeat(PIN_LENGTH) {
            val dot = View(this)
            val lp = LinearLayout.LayoutParams(18, 18).apply { marginEnd = 14 }
            dot.layoutParams = lp
            dot.background = getDrawable(R.drawable.bg_orb_glow)
            binding.pinDots.addView(dot)
        }
        refreshPinDots()
    }

    private fun refreshPinDots() {
        for (i in 0 until binding.pinDots.childCount) {
            val dot = binding.pinDots.getChildAt(i)
            dot.alpha = if (i < pin.length) 1f else 0.28f
        }
    }

    private fun buildKeypad() {
        val keys = listOf("1","2","3","4","5","6","7","8","9","⌫","0","OK")
        val grid = binding.keypadGrid
        grid.removeAllViews()
        grid.setPadding(0, 0, 0, 0)
        keys.forEach { k ->
            val btn = Button(this)
            val lp = GridLayout.LayoutParams()
            lp.width = (resources.displayMetrics.density * 92).toInt()
            lp.height = (resources.displayMetrics.density * 62).toInt()
            lp.setMargins(10, 10, 10, 10)
            btn.layoutParams = lp
            btn.text = k
            btn.setTextColor(0xFFEEEAE2.toInt())
            btn.textSize = 20f
            btn.setBackgroundColor(0xFF15120E.toInt())
            btn.setOnClickListener { onKey(k) }
            grid.addView(btn)
        }
    }

    private fun onKey(k: String) {
        when (k) {
            "⌫" -> if (pin.isNotEmpty()) { pin.deleteCharAt(pin.length - 1); refreshPinDots() }
            "OK" -> verifyPin()
            else -> if (pin.length < PIN_LENGTH) { pin.append(k); refreshPinDots() }
        }
    }

    private fun verifyPin() {
        if (pin.length < PIN_LENGTH) { toast("4 digit PIN daalo"); return }
        if (SecurityUtil.verifyPin(pin.toString(), prefs.pinSalt, prefs.pinHash)) {
            unlock()
        } else {
            toast("Galat PIN")
            pin.setLength(0)
            refreshPinDots()
        }
    }

    private fun unlock() {
        stopFaceScan()
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_UNLOCKED, true)
        startActivity(intent)
        finish()
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAM) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                if (prefs.useFace && prefs.hasFaceEnrolled) startFaceScan() else toggle(usePin = true)
            } else {
                toast("Camera permission needed for Face Unlock")
                toggle(usePin = true)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopFaceScan()
    }
}
