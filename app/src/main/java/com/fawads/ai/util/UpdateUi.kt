package com.fawads.ai.util

import android.app.Activity
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.fawads.ai.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * UI glue for [UpdateManager] — the "an update is available, tap to install"
 * half of the Play-Store-style updater.
 *
 * Called from MainActivity (silent, throttled) and from Settings (manual).
 */

private const val THROTTLE_MS = 12L * 60L * 60L * 1000L

/**
 * @param manual true → always report back (toast / dialog), false → only ping the
 *               user when there is something new and they didn't skip it.
 */
fun AppCompatActivity.checkForUpdates(manual: Boolean = true) {
    val prefs = Prefs(this)

    if (!UpdateManager.isEnabled(this)) {
        if (manual) toast(getString(R.string.update_no_feed))
        return
    }
    if (!manual) {
        if (!prefs.autoUpdateChecks) return
        if (System.currentTimeMillis() - prefs.lastUpdateCheckMs < THROTTLE_MS) return
    } else {
        toast(getString(R.string.update_checking))
    }

    val appContext = applicationContext
    lifecycleScope.launch {
        val result = withContext(Dispatchers.IO) { UpdateManager.check(appContext) }
        prefs.lastUpdateCheckMs = System.currentTimeMillis()

        val update = result.update
        if (update == null) {
            if (manual) {
                val error = result.error
                if (error != null) toast(getString(R.string.update_failed, error))
                else toast(getString(R.string.update_up_to_date, UpdateManager.currentVersionName(appContext)))
            }
            return@launch
        }
        if (!manual && prefs.skippedVersionCode == update.versionCode) return@launch
        showUpdateDialog(update)
    }
}

private fun AppCompatActivity.showUpdateDialog(update: UpdateManager.Update) {
    val prefs = Prefs(this)
    val sizeText = if (update.sizeBytes > 0)
        String.format(Locale.US, " • %.1f MB", update.sizeBytes / 1048576.0) else ""

    val message = buildString {
        append("v").append(update.versionName).append(" (build ").append(update.versionCode).append(")").append(sizeText)
        if (update.notes.isNotBlank()) {
            append("\n\n").append(update.notes)
        }
        if (update.forceUpdate) {
            append("\n\n").append(getString(R.string.update_force_note))
        }
    }

    AlertDialog.Builder(this)
        .setTitle(R.string.update_available_title)
        .setMessage(message)
        .setCancelable(!update.forceUpdate)
        .setPositiveButton(R.string.update_download) { dialog, _ ->
            dialog.dismiss()
            startDownload(update)
        }
        .setNeutralButton(R.string.update_later) { dialog, _ -> dialog.dismiss() }
        .setNegativeButton(R.string.update_skip) { _, _ -> prefs.skippedVersionCode = update.versionCode }
        .show()
}

private fun AppCompatActivity.startDownload(update: UpdateManager.Update) {
    val appContext = applicationContext
    lifecycleScope.launch {
        val outcome = withContext(Dispatchers.IO) {
            try {
                val file = UpdateManager.download(appContext, update) { percent ->
                    UpdateManager.notifyProgress(appContext, percent, "$percent%  •  v${update.versionName}")
                }
                DownloadOutcome(file, null)
            } catch (e: Exception) {
                DownloadOutcome(null, e.message ?: e.javaClass.simpleName)
            }
        }
        UpdateManager.clearProgress(appContext)

        val apk: File? = outcome.file
        if (apk == null) {
            toast(getString(R.string.update_failed, outcome.error ?: "?"))
            return@launch
        }

        UpdateManager.notifyDone(appContext, getString(R.string.update_ready))

        if (!UpdateManager.canInstallApks(this@startDownload)) {
            AlertDialog.Builder(this@startDownload)
                .setTitle(R.string.update_permission_title)
                .setMessage(R.string.update_permission_message)
                .setPositiveButton(R.string.update_open_settings) { _, _ ->
                    UpdateManager.openInstallPermission(this@startDownload)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return@launch
        }

        val started = runCatching { UpdateManager.install(this@startDownload, apk) }.getOrNull() ?: false
        if (!started) toast(getString(R.string.update_install_failed))
    }
}

private class DownloadOutcome(val file: File?, val error: String?)

private fun Activity.toast(message: String) {
    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
