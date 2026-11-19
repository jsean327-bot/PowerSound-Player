package com.powersound.player.video

import android.content.Context
import android.net.Uri
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONObject

/**
 * Subtitles → Search online: subtitles from OpenSubtitles.com, found by the video's own fingerprint (exact match)
 * and by its name, downloaded and turned on.
 *
 * OpenSubtitles gives each app user their own free API key (opensubtitles.com → API consumers). Without signing in
 * it allows 5 downloads a day, with a free account 20. The key, user and password stay on the phone only.
 */
object OnlineSubs {
    private const val PREFS = "powersound_osub"
    private const val UA = "PowerSound Player v1.0"

    class Account(val apiKey: String, val user: String, val password: String)

    class Result(
        val fileId: Long,
        val name: String,
        val language: String,
        val downloads: Int,
        /** Made for exactly this video file (same fingerprint). */
        val exact: Boolean,
        val hearingImpaired: Boolean,
        val machine: Boolean
    )

    class Failure(message: String) : Exception(message)

    fun account(context: Context): Account {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Account(p.getString("key", "").orEmpty(), p.getString("user", "").orEmpty(), p.getString("pass", "").orEmpty())
    }

    fun saveAccount(context: Context, a: Account) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("key", a.apiKey.trim()).putString("user", a.user.trim()).putString("pass", a.password)
            .remove("token").remove("base").apply()
    }

    /** The languages to search, "ar,en" (kept from the last search). */
    fun languages(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("langs", null) ?: "ar,en"

    fun saveLanguages(context: Context, langs: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("langs", langs).apply()
    }

    // ---------------------------------------------------------------- the video's fingerprint

    /**
     * OpenSubtitles' hash of a video file: its size plus the sum of the first and last 64 KB as 64-bit numbers.
     * Null when the file can't be read from the start and the end (a web stream).
     */
    fun hash(context: Context, uri: Uri): String? = runCatching {
        if (uri.scheme == "http" || uri.scheme == "https") return null
        val chunk = 65536
        fun sum(b: ByteArray, n: Int): Long {
            var h = 0L
            val bb = java.nio.ByteBuffer.wrap(b, 0, n - n % 8).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            while (bb.remaining() >= 8) h += bb.long
            return h
        }
        if (com.powersound.player.net.Remote.isRemote(uri)) {
            val ch = com.powersound.player.net.Remote.open(context, uri) ?: return null
            ch.use { c ->
                val size = c.size()
                if (size < chunk * 2) return null
                val b = ByteArray(chunk)
                fun readAt(at: Long): Int {
                    c.position(at)
                    var n = 0
                    while (n < chunk) {
                        val r = c.read(b, n, chunk - n)
                        if (r <= 0) break
                        n += r
                    }
                    return n
                }
                var h = size
                h += sum(b, readAt(0))
                h += sum(b, readAt(size - chunk))
                return "%016x".format(h)
            }
        }
        val pfd = (if (uri.scheme == "file") android.os.ParcelFileDescriptor.open(File(uri.path ?: return null), android.os.ParcelFileDescriptor.MODE_READ_ONLY)
            else context.contentResolver.openFileDescriptor(uri, "r")) ?: return null
        pfd.use { fd ->
            java.io.FileInputStream(fd.fileDescriptor).channel.use { c ->
                val size = c.size()
                if (size < chunk * 2) return null
                val b = ByteArray(chunk)
                fun readAt(at: Long): Int {
                    val buf = java.nio.ByteBuffer.wrap(b)
                    while (buf.hasRemaining()) if (c.read(buf, at + buf.position()) <= 0) break
                    return buf.position()
                }
                var h = size
                h += sum(b, readAt(0))
                h += sum(b, readAt(size - chunk))
                "%016x".format(h)
            }
        }
    }.getOrNull()

    /** "Show.Name.S02E05.1080p.WEB" → ("Show Name", 2, 5); "Movie (2019) 720p" → ("Movie 2019", null, null). */
    fun guess(name: String): Triple<String, Int?, Int?> {
        val base = if (name.substringAfterLast('.', "").length in 2..4) name.substringBeforeLast('.') else name
        val ep = Regex("(?i)\\bS(\\d{1,2})[ ._-]?E(\\d{1,3})\\b").find(base) ?: Regex("(?i)\\b(\\d{1,2})x(\\d{2,3})\\b").find(base)
        val head = if (ep != null) base.substring(0, ep.range.first) else base
        val clean = head.replace(Regex("[._]+"), " ")
            .replace(Regex("(?i)\\b(480p|576p|720p|1080p|2160p|4k|x264|x265|h264|h265|hevc|web(-?dl|rip)?|bluray|brrip|hdrip|dvdrip|aac|ac3|hdr|10bit)\\b.*"), "")
            .replace(Regex("[\\[\\](){}]"), " ").replace(Regex("\\s+"), " ").trim(' ', '-')
        return Triple(clean.ifBlank { base }, ep?.groupValues?.get(1)?.toIntOrNull(), ep?.groupValues?.get(2)?.toIntOrNull())
    }

    // ---------------------------------------------------------------- the API

    private fun base(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("base", null)?.let { "https://$it/api/v1" }
            ?: "https://api.opensubtitles.com/api/v1"

    private fun call(context: Context, method: String, path: String, body: JSONObject?, auth: Boolean): JSONObject {
        val a = account(context)
        if (a.apiKey.isBlank()) throw Failure("Add your OpenSubtitles API key first")
        val c = URL(base(context) + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 15000
            c.readTimeout = 20000
            c.setRequestProperty("Api-Key", a.apiKey)
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Accept", "application/json")
            if (auth) token(context)?.let { c.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = c.responseCode
            val text = (if (code >= 400) c.errorStream else c.inputStream)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            val json = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
            if (code >= 400) {
                val msg = json.optString("message").ifBlank { json.optJSONArray("errors")?.optString(0).orEmpty() }
                throw Failure(
                    when (code) {
                        401, 403 -> if (path == "/login") "Wrong OpenSubtitles user name or password" else "OpenSubtitles didn't accept the API key"
                        406 -> msg.ifBlank { "Today's downloads are used up (5 a day without signing in, 20 with a free account)" }
                        429 -> "Too many searches: wait a few seconds"
                        else -> msg.ifBlank { "OpenSubtitles answered $code" }
                    }
                )
            }
            return json
        } finally {
            c.disconnect()
        }
    }

    /** Signs in once (when a user is set) for 20 downloads a day; the token is kept. */
    private fun token(context: Context): String? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.getString("token", null)?.let { return it }
        val a = account(context)
        if (a.user.isBlank() || a.password.isBlank()) return null
        val r = call(context, "POST", "/login", JSONObject().put("username", a.user).put("password", a.password), auth = false)
        val t = r.optString("token").ifBlank { return null }
        p.edit().putString("token", t).apply {
            r.optString("base_url").takeIf { it.isNotBlank() }?.let { putString("base", it.removePrefix("https://").removeSuffix("/")) }
        }.apply()
        return t
    }

    /** Subtitles for a video: by its fingerprint (when known) and its name, in [languages] ("ar,en"). */
    fun search(context: Context, query: String, hash: String?, season: Int?, episode: Int?, languages: String): List<Result> {
        val q = StringBuilder("/subtitles?")
        fun add(k: String, v: String) {
            if (q.last() != '?') q.append('&')
            q.append(k).append('=').append(URLEncoder.encode(v, "UTF-8"))
        }
        // the parameters in the order OpenSubtitles asks for (alphabetical), lower case
        if (episode != null) add("episode_number", episode.toString())
        add("languages", languages.lowercase().split(',').map { it.trim() }.filter { it.isNotEmpty() }.sorted().joinToString(","))
        if (hash != null) add("moviehash", hash)
        add("order_by", "download_count")
        if (query.isNotBlank()) add("query", query.lowercase().trim())
        if (season != null) add("season_number", season.toString())
        val data = call(context, "GET", q.toString(), null, auth = false).optJSONArray("data") ?: return emptyList()
        val out = ArrayList<Result>()
        for (i in 0 until data.length()) {
            val at = data.optJSONObject(i)?.optJSONObject("attributes") ?: continue
            val files = at.optJSONArray("files") ?: continue
            val f = files.optJSONObject(0) ?: continue
            out += Result(
                fileId = f.optLong("file_id"),
                name = at.optString("release").ifBlank { f.optString("file_name") },
                language = at.optString("language"),
                downloads = at.optInt("download_count"),
                exact = at.optBoolean("moviehash_match"),
                hearingImpaired = at.optBoolean("hearing_impaired"),
                machine = at.optBoolean("machine_translated") || at.optBoolean("ai_translated")
            )
        }
        return out.sortedWith(compareByDescending<Result> { it.exact }.thenByDescending { it.downloads })
    }

    /** Downloads a subtitle into the app's folder; returns the file (and how many downloads are left today). */
    fun download(context: Context, r: Result): Pair<File, Int?> {
        val d = call(context, "POST", "/download", JSONObject().put("file_id", r.fileId), auth = true)
        val link = d.optString("link").ifBlank { throw Failure(d.optString("message").ifBlank { "No download link" }) }
        val bytes = (URL(link).openConnection() as HttpURLConnection).run {
            try {
                setRequestProperty("User-Agent", UA)
                connectTimeout = 15000
                readTimeout = 30000
                if (responseCode >= 400) throw Failure("The subtitle couldn't be downloaded ($responseCode)")
                inputStream.use { it.readBytes() }
            } finally {
                disconnect()
            }
        }
        val name = d.optString("file_name").ifBlank { r.name + ".srt" }.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val dir = File(context.filesDir, "subtitles").apply { mkdirs() }
        val f = File(dir, "${r.language}_${r.fileId}_$name")
        f.writeBytes(bytes)
        return f to (if (d.has("remaining")) d.optInt("remaining") else null)
    }
}
