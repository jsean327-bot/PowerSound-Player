package com.powersound.player.data

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Collections

/**
 * Album art (Settings → Album Art), in this order:
 *  1. a downloaded image, when "Prefer Downloaded Album Art" is on
 *  2. the picture embedded in the file (FLAC picture, ID3 cover)
 *  3. cover.jpg / folder.jpg … next to the file (needs "All files access")
 *  4. a downloaded image (iTunes Search, saved on the phone), when "Download Album Art" is on
 * Artist images come from Deezer.
 */
object ArtworkLoader {

    private val cache = object : LruCache<String, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val missing: MutableSet<String> = Collections.synchronizedSet(HashSet())
    private val gate = Semaphore(4)
    private val netGate = Semaphore(2)
    private val netLock = HashMap<String, Mutex>()

    /** Set by the app so a song id can be turned into artist / album / title. */
    @Volatile
    var songInfo: (Long) -> Song? = { null }

    private var appContext: Context? = null

    /** Bumped when the cache is cleared so the screens load again. */
    val generation = kotlinx.coroutines.flow.MutableStateFlow(0)

    /** Forget every loaded cover so they are read again. */
    fun clear() {
        cache.evictAll()
        missing.clear()
        generation.value++
    }

    /** Settings → Album Art → Delete Cache: also deletes the downloaded images. */
    fun deleteCache(context: Context) {
        clear()
        runCatching { artDir(context).deleteRecursively() }
    }

    /** Settings → Album Art → Reset Negative Status: search again for images that were not found before. */
    fun resetNegative(context: Context) {
        context.getSharedPreferences("powersound_art", Context.MODE_PRIVATE).edit().clear().apply()
        clear()
    }

    fun cached(songId: Long, sizePx: Int): ImageBitmap? =
        cache.get(key(songId, sizePx))?.asImageBitmap()

    suspend fun load(context: Context, songId: Long, sizePx: Int): ImageBitmap? {
        appContext = context.applicationContext
        if (SettingsStore.value.artForceDefault) return null
        val key = key(songId, sizePx)
        cache.get(key)?.let { return it.asImageBitmap() }
        if (missing.contains(key)) return null

        var bitmap = gate.withPermit {
            withContext(Dispatchers.IO) { decodeLocal(context, songId, sizePx) }
        }
        if (bitmap == null) bitmap = download(context, songId, sizePx)
        if (bitmap != null) cache.put(key, bitmap) else missing.add(key)
        return bitmap?.asImageBitmap()
    }

    /** Image of an artist / album artist / composer, or null (then the album art is used). */
    suspend fun loadArtist(context: Context, name: String, sizePx: Int): ImageBitmap? {
        appContext = context.applicationContext
        val st = SettingsStore.value
        if (st.artForceDefault || !st.artDownloadArtist || name.isBlank() || name.startsWith("Unknown")) return null
        val key = "artist:$name:$sizePx"
        cache.get(key)?.let { return it.asImageBitmap() }
        if (missing.contains(key)) return null
        val file = File(artDir(context), "artist_" + safe(name) + ".jpg")
        val bmp = withContext(Dispatchers.IO) {
            if (!file.exists()) fetchOnce(context, "artist:$name", file) { deezerArtist(name, st.artHighRes) }
            if (file.exists()) decodeFile(file, sizePx) else null
        }
        if (bmp != null) cache.put(key, bmp) else missing.add(key)
        return bmp?.asImageBitmap()
    }

    private fun key(id: Long, size: Int) = "$id:$size"

    private fun artDir(context: Context) = File(context.filesDir, "art").apply { mkdirs() }

    private fun safe(s: String) = s.lowercase().replace(Regex("[^a-z0-9\\u0600-\\u06FF]+"), "_").take(80)

    // ---------------------------------------------------------------- local

    private fun decodeLocal(context: Context, songId: Long, sizePx: Int): Bitmap? {
        val st = SettingsStore.value
        val song = songInfo(songId)
        if (st.artPreferDownloaded && song != null) {
            val f = downloadedFile(context, song)
            if (f.exists()) decodeFile(f, sizePx)?.let { return it }
        }
        decodeEmbedded(context, songId, sizePx)?.let { return finish(it) }
        if (song != null) {
            folderImage(song)?.let { f -> decodeFile(f, sizePx)?.let { return it } }
            val f = downloadedFile(context, song)
            if (f.exists()) decodeFile(f, sizePx)?.let { return it }
        }
        return null
    }

    private fun decodeEmbedded(context: Context, songId: Long, sizePx: Int): Bitmap? {
        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, songId)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                return context.contentResolver.loadThumbnail(uri, Size(sizePx, sizePx), null)
            } catch (_: Exception) {
                // fall through to the retriever
            }
        }
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val bytes = retriever.embeddedPicture ?: return null
            decodeSampled(bytes, sizePx)
        } catch (_: Exception) {
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    /** cover.jpg, folder.jpg, albumart.jpg, front.jpg … in the song's folder. */
    private fun folderImage(song: Song): File? {
        if (song.path.isBlank()) return null
        return try {
            val dir = File(song.path).parentFile ?: return null
            val names = listOf("cover", "folder", "albumart", "front", "album")
            dir.listFiles()?.firstOrNull { f ->
                f.isFile && f.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp") &&
                    names.any { f.nameWithoutExtension.equals(it, ignoreCase = true) }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeFile(f: File, sizePx: Int): Bitmap? = try {
        decodeSampled(f.readBytes(), sizePx)
    } catch (_: Exception) {
        null
    }

    private fun decodeSampled(bytes: ByteArray, sizePx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= sizePx && bounds.outHeight / (sample * 2) >= sizePx) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            // Settings → Album Art → Use 24-bit RGB (off = 16-bit, half the memory)
            inPreferredConfig = if (SettingsStore.value.art24bit) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    private fun finish(b: Bitmap): Bitmap =
        if (!SettingsStore.value.art24bit && b.config != Bitmap.Config.RGB_565) {
            b.copy(Bitmap.Config.RGB_565, false) ?: b
        } else b

    // ---------------------------------------------------------------- download

    private fun albumKey(song: Song): String =
        if (song.album.isNotBlank() && !song.album.startsWith("Unknown")) "album_" + safe(song.albumArtist + "_" + song.album)
        else "track_" + safe(song.artist + "_" + song.title)

    private fun downloadedFile(context: Context, song: Song) = File(artDir(context), albumKey(song) + ".jpg")

    private suspend fun download(context: Context, songId: Long, sizePx: Int): Bitmap? {
        val st = SettingsStore.value
        if (!st.artDownload) return null
        val song = songInfo(songId) ?: return null
        val file = downloadedFile(context, song)
        return withContext(Dispatchers.IO) {
            if (!file.exists()) fetchOnce(context, albumKey(song), file) { itunesArt(song, st) }
            if (file.exists()) decodeFile(file, sizePx) else null
        }
    }

    /** Downloads [file] once; remembers "not found" so it isn't searched again (until Reset Negative Status). */
    private suspend fun fetchOnce(context: Context, key: String, file: File, findUrl: () -> String?) {
        val lock = synchronized(netLock) { netLock.getOrPut(key) { Mutex() } }
        lock.withLock {
            if (file.exists()) return
            val neg = context.getSharedPreferences("powersound_art", Context.MODE_PRIVATE)
            if (neg.getBoolean(key, false)) return
            if (!networkAllowed(context)) return
            netGate.withPermit {
                val url = runCatching { findUrl() }.getOrNull()
                if (url == null) {
                    neg.edit().putBoolean(key, true).apply()
                    return
                }
                val u: String = url
                runCatching {
                    val bytes = httpBytes(u) ?: return
                    val tmp = File(file.parentFile, file.name + ".tmp")
                    tmp.writeBytes(bytes)
                    tmp.renameTo(file)
                }
            }
        }
    }

    private fun networkAllowed(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        // Settings → Album Art → Download Only On Wi-Fi
        if (SettingsStore.value.artWifiOnly) {
            return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        }
        return true
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun clean(s: String) = s.replace(Regex("\\s*[(\\[].*?[)\\]]"), "").trim()

    /** iTunes Search: album cover by artist + album, or (Also Search By Title/Filename) by artist + title. */
    private fun itunesArt(song: Song, st: AppSettings): String? {
        val size = if (st.artHighRes) "1200x1200" else "600x600"
        fun pick(json: String?): String? {
            val arr = json?.let { JSONObject(it).optJSONArray("results") } ?: return null
            for (i in 0 until arr.length()) {
                val u = arr.getJSONObject(i).optString("artworkUrl100")
                if (u.isNotBlank()) return u.replace("100x100bb", "${size}bb")
            }
            return null
        }
        val artist = if (song.albumArtist.startsWith("Unknown")) song.artist else song.albumArtist
        val hasAlbum = song.album.isNotBlank() && !song.album.startsWith("Unknown")
        val hasArtist = !artist.startsWith("Unknown")
        if (hasAlbum) {
            val term = listOf(if (hasArtist) clean(artist) else "", clean(song.album)).filter { it.isNotBlank() }.joinToString(" ")
            pick(httpText("https://itunes.apple.com/search?media=music&entity=album&limit=5&term=" + enc(term)))?.let { return it }
        }
        if (st.artSearchTitle) {
            val title = clean(song.title.ifBlank { song.fileName })
            val term = listOf(if (hasArtist) clean(song.artist) else "", title).filter { it.isNotBlank() }.joinToString(" ")
            if (term.isNotBlank()) {
                pick(httpText("https://itunes.apple.com/search?media=music&entity=song&limit=5&term=" + enc(term)))?.let { return it }
            }
        }
        return null
    }

    /** Deezer artist picture. */
    private fun deezerArtist(name: String, high: Boolean): String? {
        val json = httpText("https://api.deezer.com/search/artist?limit=1&q=" + enc(clean(name))) ?: return null
        val first = JSONObject(json).optJSONArray("data")?.optJSONObject(0) ?: return null
        val url = first.optString(if (high) "picture_xl" else "picture_big")
        // Deezer's placeholder for unknown artists has an empty hash in the url
        return url.takeIf { it.isNotBlank() && !url.contains("/artist//") }
    }

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 8000
        readTimeout = 12000
        setRequestProperty("User-Agent", "PowerSound Player")
    }

    private fun httpText(url: String): String? = try {
        val c = open(url)
        try {
            if (c.responseCode in 200..299) c.inputStream.use { it.readBytes().decodeToString() } else null
        } finally {
            c.disconnect()
        }
    } catch (_: Exception) {
        null
    }

    private fun httpBytes(url: String): ByteArray? = try {
        val c = open(url)
        try {
            if (c.responseCode in 200..299) c.inputStream.use { it.readBytes() } else null
        } finally {
            c.disconnect()
        }
    } catch (_: Exception) {
        null
    }
}
