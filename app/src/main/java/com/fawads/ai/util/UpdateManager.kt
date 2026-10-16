package com.fawads.ai.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.fawads.ai.R
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Play-Store-style self-update, without the Play Store.
 *
 * How it works: you publish two files on any static HTTPS host (your own server,
 * Cloudflare R2, a Dropbox/GitHub-release asset — anything that gives a direct
 * download link):
 *
 *   release.json          →  {"versionCode": 4, "versionName": "1.1.3",
 *                             "apkUrl": "https://host/FawadsAI-latest.apk",
 *                             "sizeBytes": 12345678, "sha256": "…",
 *                             "notes": "…", "forceUpdate": false}
 *   FawadsAI-latest.apk   →  the signed release APK from scripts/phone-build.sh
 *
 * The app compares `versionCode` with its own, downloads the APK into app-private
 * storage, verifies SHA-256, and hands it to the system installer. No root, no
 * ADB, no PC — and it works the same on every device/ROM.
 *
 * All methods here are blocking → call them from a background thread.
 */
object UpdateManager {

    private const val CHANNEL_ID = "fawads_updates"
    private const val NOTIF_ID = 7701
    private const val FEED_TIMEOUT_S = 20L

    /** One available release, parsed from release.json. */
    class Update(
        val versionCode: Int,
        val versionName: String,
        val url: String,
        val notes: String,
        val sha256: String,
        val sizeBytes: Long,
        val forceUpdate: Boolean
    )

    /** Result of a feed check. [update] is non-null only when it's newer. */
    class CheckResult(val update: Update?, val error: String? = null) {
        val ok: Boolean get() = error == null
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(FEED_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    // ─────────────────────────── installed version ───────────────────────────

    @Suppress("DEPRECATION")
    fun currentVersionCode(ctx: Context): Int = try {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode.toInt()
        else info.versionCode
    } catch (e: Exception) {
        0
    }

    fun currentVersionName(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "1.0.0"
    } catch (e: Exception) {
        "1.0.0"
    }

    /** The feed URL the user typed in Settings ("" → updater disabled). */
    fun feedUrl(ctx: Context): String =
        Prefs(ctx).updateFeedUrl.trim().ifBlank { com.fawads.ai.BuildConfig.UPDATE_FEED_URL.trim() }

    fun isEnabled(ctx: Context): Boolean = feedUrl(ctx).isNotBlank()

    // ─────────────────────────────── check ───────────────────────────────

    fun check(ctx: Context): CheckResult {
        val url = feedUrl(ctx)
        if (url.isBlank()) return CheckResult(null, "Update feed URL set nahi hai (Settings → Updates).")
        return try {
            val request = Request.Builder().url(url).header("Accept", "application/json").build()
            val json = client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return CheckResult(null, "Feed HTTP ${resp.code}")
                resp.body?.string() ?: return CheckResult(null, "Feed khali tha")
            }
            val o = parseJsonLoosely(json)
            val code = o.optInt("versionCode", 0)
            if (code <= 0) return CheckResult(null, "release.json me versionCode missing hai")
            val apkUrl = firstOf(o, "apkUrl", "url", "downloadUrl")
            if (apkUrl.isBlank()) return CheckResult(null, "release.json me apkUrl missing hai")
            val upd = Update(
                versionCode = code,
                versionName = o.optString("versionName", "?"),
                url = apkUrl,
                notes = o.optString("notes", ""),
                sha256 = firstOf(o, "sha256", "checksum"),
                sizeBytes = o.optLong("sizeBytes", 0L),
                forceUpdate = o.optBoolean("forceUpdate", false)
            )
            if (upd.versionCode <= currentVersionCode(ctx) && !force(upd)) CheckResult(null)
            else CheckResult(upd)
        } catch (e: Exception) {
            CheckResult(null, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun force(u: Update): Boolean = u.forceUpdate

    /** Accepts a plain object or a GitHub-release-shaped array, defensively. */
    private fun parseJsonLoosely(raw: String): JSONObject {
        val trimmed = raw.trim()
        if (trimmed.startsWith("[")) {
            val arr = org.json.JSONArray(trimmed)
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                if (item.has("versionCode") || item.has("tag_name")) return item
            }
            return JSONObject()
        }
        return JSONObject(trimmed)
    }

    private fun firstOf(o: JSONObject, vararg keys: String): String {
        for (k in keys) {
            val v = o.optString(k, "")
            if (v.isNotBlank()) return v
        }
        return ""
    }

    // ─────────────────────────────── download ───────────────────────────────

    /** App-private, no storage permission needed, readable by the installer via FileProvider. */
    private fun updatesDir(ctx: Context): File {
        val base = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        val dir = File(base, "updates")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun download(ctx: Context, update: Update, onProgress: (Int) -> Unit): File {
        val dir = updatesDir(ctx)
        dir.listFiles()?.forEach { if (it.name.endsWith(".apk")) runCatching { it.delete() } }
        val target = File(dir, "FawadsAI-${update.versionName}.apk")

        val request = Request.Builder().url(update.url).build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("Download fail: HTTP ${resp.code}")
            val body = resp.body ?: throw java.io.IOException("Empty response body")
            val total = if (body.contentLength() > 0) body.contentLength() else update.sizeBytes
            var written = 0L
            body.byteStream().use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n <= 0) break
                        output.write(buffer, 0, n)
                        written += n
                        if (total > 0) {
                            val pct = ((written * 100.0) / total).toInt().coerceIn(0, 100)
                            onProgress(pct)
                        }
                    }
                    output.flush()
                }
            }
        }

        if (update.sizeBytes > 0) {
            val diff = Math.abs(target.length() - update.sizeBytes)
            if (diff > update.sizeBytes / 20) { // >5% off → almost surely a broken/redirected download
                target.delete()
                throw java.io.IOException("File size match nahi kha (got ${target.length()}, expected ${update.sizeBytes})")
            }
        }
        if (update.sha256.isNotBlank()) {
            val actual = sha256(target)
            if (!actual.equals(update.sha256, ignoreCase = true)) {
                target.delete()
                throw java.io.IOException("SHA-256 mismatch — download corrupt ya fake ho sakta hai")
            }
        }
        onProgress(100)
        return target
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n <= 0) break
                digest.update(buffer, 0, n)
            }
        }
        val sb = StringBuilder()
        for (b in digest.digest()) sb.append((b.toInt() and 0xff).toString(16).padStart(2, '0'))
        return sb.toString()
    }

    // ─────────────────────────────── install ───────────────────────────────

    /** Android 8+ requires the "install unknown apps" permission for this app. */
    fun canInstallApks(ctx: Context): Boolean = try {
        ctx.packageManager.canRequestPackageInstalls()
    } catch (e: Exception) {
        false
    }

    fun openInstallPermission(ctx: Context) {
        try {
            ctx.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${ctx.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            try {
                ctx.startActivity(
                    Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) {
            }
        }
    }

    @Throws(Exception::class)
    fun install(ctx: Context, apk: File): Boolean {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return try {
            ctx.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }

    /** Delete the downloaded APK once the installer has been launched. */
    fun cleanup(ctx: Context) {
        runCatching {
            updatesDir(ctx).listFiles()?.forEach { f ->
                if (f.name.endsWith(".apk") && System.currentTimeMillis() - f.lastModified() > 60_000) {
                    runCatching { f.delete() }
                }
            }
        }
    }

    // ─────────────────────────────── progress notification ───────────────────────────────

    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = ctx.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        ctx.getString(R.string.update_channel_name),
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
            }
        }
    }

    fun notifyProgress(ctx: Context, percent: Int, text: String) {
        ensureChannel(ctx)
        val builder = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_fawads_notif)
            .setContentTitle(ctx.getString(R.string.update_downloading))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (percent in 1..99) builder.setProgress(100, percent, false)
        else builder.setProgress(0, 0, true)
        notify(ctx, builder.build())
    }

    fun notifyDone(ctx: Context, text: String) {
        ensureChannel(ctx)
        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_fawads_notif)
            .setContentTitle(ctx.getString(R.string.app_name))
            .setContentText(text)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        notify(ctx, notification)
    }

    fun clearProgress(ctx: Context) {
        try {
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)?.cancel(NOTIF_ID)
        } catch (_: Exception) {
        }
    }

    private fun notify(ctx: Context, notification: android.app.Notification) {
        try {
            androidx.core.app.NotificationManagerCompat.from(ctx)
                .notify(NOTIF_ID, notification)
        } catch (e: SecurityException) {
            // Notifications permission not granted yet — progress just won't show.
        }
    }
}
