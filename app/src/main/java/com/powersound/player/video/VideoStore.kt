package com.powersound.player.video

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** One video file on the phone. */
data class Video(
    val id: Long,
    val title: String,
    val folder: String,
    val path: String,
    val durationMs: Long,
    val size: Long,
    val width: Int,
    val height: Int,
    val dateAdded: Long
) {
    val uri: Uri get() = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)

    /** "1080p", "4K"… from the picture size. */
    val quality: String
        get() {
            val h = minOf(width, height).takeIf { it > 0 } ?: return ""
            return when {
                h >= 2000 -> "4K"
                h >= 1400 -> "1440p"
                h >= 1000 -> "1080p"
                h >= 700 -> "720p"
                h >= 470 -> "480p"
                else -> "${h}p"
            }
        }
}

/** A folder of videos (or "All videos"). */
data class VideoFolder(val name: String, val path: String, val videos: List<Video>)

/**
 * The phone's videos (Library → Videos), kept apart from the music: they are read only when that page is
 * opened, and never mixed into songs, albums or folders.
 */
object VideoStore {

    suspend fun load(context: Context): List<Video> = withContext(Dispatchers.IO) {
        val out = ArrayList<Video>()
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.TITLE,
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.DATE_ADDED
        )
        runCatching {
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, projection, null, null,
                MediaStore.Video.Media.DATE_ADDED + " DESC"
            )?.use { c ->
                val idC = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameC = c.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME)
                val titleC = c.getColumnIndex(MediaStore.Video.Media.TITLE)
                val dataC = c.getColumnIndex(MediaStore.Video.Media.DATA)
                val durC = c.getColumnIndex(MediaStore.Video.Media.DURATION)
                val sizeC = c.getColumnIndex(MediaStore.Video.Media.SIZE)
                val wC = c.getColumnIndex(MediaStore.Video.Media.WIDTH)
                val hC = c.getColumnIndex(MediaStore.Video.Media.HEIGHT)
                val addC = c.getColumnIndex(MediaStore.Video.Media.DATE_ADDED)
                while (c.moveToNext()) {
                    val path = if (dataC >= 0) c.getString(dataC) ?: "" else ""
                    val name = (if (nameC >= 0) c.getString(nameC) else null)
                        ?: (if (titleC >= 0) c.getString(titleC) else null) ?: "Video"
                    val folderPath = path.substringBeforeLast('/', "")
                    out += Video(
                        id = c.getLong(idC),
                        title = name.substringBeforeLast('.').ifBlank { name },
                        folder = folderPath.substringAfterLast('/').ifBlank { "Videos" },
                        path = path,
                        durationMs = if (durC >= 0) c.getLong(durC) else 0L,
                        size = if (sizeC >= 0) c.getLong(sizeC) else 0L,
                        width = if (wC >= 0) c.getInt(wC) else 0,
                        height = if (hC >= 0) c.getInt(hC) else 0,
                        dateAdded = if (addC >= 0) c.getLong(addC) else 0L
                    )
                }
            }
        }
        out
    }

    /** Videos grouped by their folder, folders A–Z, videos A–Z inside (episodes in order). */
    fun folders(videos: List<Video>): List<VideoFolder> =
        videos.groupBy { it.path.substringBeforeLast('/', it.folder) }
            .map { (path, list) -> VideoFolder(list.first().folder, path, list.sortedWith(naturalOrder)) }
            .sortedBy { it.name.lowercase() }

    /** "Ep 2" before "Ep 10". */
    val naturalOrder: Comparator<Video> = Comparator { a, b -> natural(a.title, b.title) }

    private fun natural(a: String, b: String): Int {
        val x = a.lowercase()
        val y = b.lowercase()
        var i = 0
        var j = 0
        while (i < x.length && j < y.length) {
            val cx = x[i]
            val cy = y[j]
            if (cx.isDigit() && cy.isDigit()) {
                var ei = i
                while (ei < x.length && x[ei].isDigit()) ei++
                var ej = j
                while (ej < y.length && y[ej].isDigit()) ej++
                val nx = x.substring(i, ei).trimStart('0')
                val ny = y.substring(j, ej).trimStart('0')
                if (nx.length != ny.length) return nx.length - ny.length
                val c = nx.compareTo(ny)
                if (c != 0) return c
                i = ei
                j = ej
            } else {
                if (cx != cy) return cx - cy
                i++
                j++
            }
        }
        return (x.length - i) - (y.length - j)
    }

    // ---------------------------------------------------------------- thumbnails

    private val thumbs = object : LruCache<Long, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: Long, value: Bitmap) = value.byteCount
    }

    fun cachedThumb(id: Long): Bitmap? = thumbs.get(id)

    suspend fun thumb(context: Context, v: Video): Bitmap? = thumbs.get(v.id) ?: withContext(Dispatchers.IO) {
        val bmp = runCatching {
            if (Build.VERSION.SDK_INT >= 29) context.contentResolver.loadThumbnail(v.uri, Size(384, 216), null)
            else {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(context, v.uri)
                    r.getFrameAtTime(minOf(10_000_000L, v.durationMs * 1000 / 3), MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        ?.let { Bitmap.createScaledBitmap(it, 384, 384 * it.height / maxOf(1, it.width), true) }
                } finally {
                    runCatching { r.release() }
                }
            }
        }.getOrNull()
        bmp?.also { thumbs.put(v.id, it) }
    }

    // ---------------------------------------------------------------- where you stopped

    private const val PREFS = "powersound_video"
    private const val MAX_REMEMBERED = 400

    /** A stable key for a video: its MediaStore address, or the web address for streams. */
    fun keyOf(uri: Uri): String = uri.toString().substringBefore('#')

    /** Where you stopped in this video (ms), or 0 when it was watched to the end / never opened. */
    fun position(context: Context, key: String): Long =
        runCatching { all(context).optJSONObject(key)?.optLong("p") ?: 0L }.getOrDefault(0L)

    /** How much of the video was watched, 0…1 (for the bar under the thumbnail). */
    fun progress(context: Context, key: String): Float = runCatching {
        val o = all(context).optJSONObject(key) ?: return 0f
        val d = o.optLong("d")
        if (d <= 0) 0f else (o.optLong("p").toFloat() / d).coerceIn(0f, 1f)
    }.getOrDefault(0f)

    fun savePosition(context: Context, key: String, positionMs: Long, durationMs: Long) {
        val all = all(context)
        // the last minute / 95% counts as "watched": next time it starts from the beginning
        val done = durationMs > 0 && (positionMs > durationMs * 0.95 || durationMs - positionMs < 20_000)
        if (done || positionMs < 5_000) all.remove(key)
        else all.put(key, JSONObject().put("p", positionMs).put("d", durationMs).put("t", System.currentTimeMillis()))
        // keep the list short: forget the oldest
        if (all.length() > MAX_REMEMBERED) {
            val keys = all.keys().asSequence().toList().sortedBy { all.optJSONObject(it)?.optLong("t") ?: 0L }
            keys.take(all.length() - MAX_REMEMBERED).forEach { all.remove(it) }
        }
        cache = all
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("positions", all.toString()).apply()
    }

    @Volatile
    private var cache: JSONObject? = null

    private fun all(context: Context): JSONObject = cache ?: runCatching {
        JSONObject(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("positions", null) ?: "{}")
    }.getOrDefault(JSONObject()).also { cache = it }
}
