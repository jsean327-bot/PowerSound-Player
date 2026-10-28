package com.powersound.player.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * In-app updates from GitHub Releases.
 * Every build on GitHub Actions publishes a release tagged "v<run number>" with the APK,
 * and the app's versionCode is 100 + run number, so the two can be compared.
 */
object Updater {

    private const val REPO = "jsean327-bot/PowerSound-Player"
    private const val VERSION_BASE = 100L

    data class UpdateInfo(
        val versionCode: Long,
        val versionName: String,
        val notes: String,
        val apkUrl: String,
        val sizeBytes: Long
    )

    fun installedVersionCode(context: Context): Long = try {
        PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0))
    } catch (_: Exception) {
        0L
    }

    /** Returns the newer release, or null when the app is already up to date. */
    suspend fun check(context: Context): UpdateInfo? = withContext(Dispatchers.IO) {
        val conn = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "PowerSound-Player")
            val code = conn.responseCode
            if (code == 404) return@withContext null // no release yet
            if (code !in 200..299) throw IllegalStateException("GitHub answered $code")

            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val tag = json.optString("tag_name")
            val run = tag.trimStart('v', 'V').toLongOrNull() ?: return@withContext null
            val remoteCode = VERSION_BASE + run
            if (remoteCode <= installedVersionCode(context)) return@withContext null

            val assets = json.optJSONArray("assets") ?: return@withContext null
            var url: String? = null
            var size = 0L
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                    url = a.optString("browser_download_url")
                    size = a.optLong("size")
                    break
                }
            }
            if (url.isNullOrBlank()) return@withContext null
            UpdateInfo(
                versionCode = remoteCode,
                versionName = json.optString("name").ifBlank { tag },
                notes = json.optString("body").trim(),
                apkUrl = url,
                sizeBytes = size
            )
        } finally {
            conn.disconnect()
        }
    }

    /** Downloads the APK into the app's cache, reporting progress 0..1. */
    suspend fun download(context: Context, info: UpdateInfo, onProgress: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, "PowerSound-${info.versionCode}.apk")
            val conn = URL(info.apkUrl).openConnection() as HttpURLConnection
            try {
                conn.instanceFollowRedirects = true
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.setRequestProperty("User-Agent", "PowerSound-Player")
                if (conn.responseCode !in 200..299) throw IllegalStateException("Download failed (${conn.responseCode})")
                val total = conn.contentLengthLong.takeIf { it > 0 } ?: info.sizeBytes
                conn.inputStream.use { input ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }
            file
        }

    /**
     * Opens Android's installer. Returns false when the user first has to allow
     * "Install unknown apps" for PowerSound (the settings page is opened for them).
     */
    fun install(context: Context, file: File): Boolean {
        if (!context.packageManager.canRequestPackageInstalls()) {
            val settings = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(settings)
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return true
    }
}
