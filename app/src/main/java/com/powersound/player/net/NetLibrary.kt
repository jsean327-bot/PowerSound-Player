package com.powersound.player.net

import android.content.Context
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import com.powersound.player.data.Song
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Network songs in the library (Library → Network → a folder → Add to Library). They then show in Folders, Albums,
 * Artists, All Songs, search, Favorites, playlists and the statistics like the phone's own songs.
 *
 * A network song has a negative id (the phone's songs have positive MediaStore ids) and its address in [Song.path]:
 * http… (a media server) or smb://… (a Windows shared folder). Its folder is "Network/<server>/<folder>".
 */
object NetLibrary {
    private const val FILE = "netlib.json"

    /** One added song, with where it came from ([origin]: the added folder, for "Remove from Library"). */
    private class Entry(val song: Song, val origin: String, val art: String?)

    @Volatile
    private var entries: List<Entry>? = null

    /** "Adding… 120 / 800" while a folder is being added (null when nothing is). */
    val progress = MutableStateFlow<String?>(null)
    /** Added folders (their origin keys), to show "Remove from Library". */
    val origins = MutableStateFlow<Set<String>>(emptySet())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    fun isNetwork(id: Long) = id < 0

    /** The network songs, for the library. */
    fun songs(context: Context): List<Song> = load(context).map { it.song }

    fun song(context: Context, id: Long): Song? = load(context).firstOrNull { it.song.id == id }?.song

    /** The cover address a media server gave for this song (null: none). */
    fun artUrl(context: Context, id: Long): String? = load(context).firstOrNull { it.song.id == id }?.art

    @Synchronized
    private fun load(context: Context): List<Entry> {
        entries?.let { return it }
        val list = runCatching {
            val a = JSONArray(File(context.filesDir, FILE).takeIf { it.exists() }?.readText() ?: "[]")
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                Entry(
                    Song(
                        id = o.getLong("id"), title = o.optString("t"), artist = o.optString("a", "Unknown artist"),
                        album = o.optString("al", "Unknown album"), albumId = o.optLong("aid"), albumArtist = o.optString("aa", o.optString("a")),
                        durationMs = o.optLong("d"), track = o.optInt("n"), folder = o.optString("f"), mimeType = o.optString("m"),
                        size = o.optLong("s"), year = o.optInt("y"), composer = "", genre = o.optString("g"),
                        dateAdded = o.optLong("da"), path = o.getString("u"), hasAlbumArtist = o.has("aa")
                    ),
                    o.optString("o"), o.optString("art").ifEmpty { null }
                )
            }
        }.getOrDefault(emptyList())
        entries = list
        origins.value = list.map { it.origin }.toSet()
        return list
    }

    @Synchronized
    private fun save(context: Context, list: List<Entry>) {
        val a = JSONArray()
        list.forEach { e ->
            val s = e.song
            val o = JSONObject().put("id", s.id).put("t", s.title).put("a", s.artist).put("al", s.album).put("aid", s.albumId)
                .put("d", s.durationMs).put("n", s.track).put("f", s.folder).put("m", s.mimeType).put("s", s.size)
                .put("y", s.year).put("g", s.genre).put("da", s.dateAdded).put("u", s.path).put("o", e.origin)
            if (s.hasAlbumArtist) o.put("aa", s.albumArtist)
            e.art?.let { o.put("art", it) }
            a.put(o)
        }
        val f = File(context.filesDir, FILE)
        val tmp = File(context.filesDir, "$FILE.tmp")
        tmp.writeText(a.toString())
        tmp.renameTo(f)
        entries = list
        origins.value = list.map { it.origin }.toSet()
    }

    /** A stable negative id for a network address. */
    private fun idOf(url: String): Long {
        var h = 1125899906842597L
        for (c in url) h = 31 * h + c.code
        return -(h and Long.MAX_VALUE).coerceAtLeast(1L)
    }

    private fun albumIdOf(artist: String, album: String): Long = -((artist.lowercase() + "\u0000" + album.lowercase()).hashCode().toLong() and 0xffffffffL) - 1

    fun remove(context: Context, origin: String, done: () -> Unit) {
        scope.launch {
            save(context, load(context).filterNot { it.origin == origin })
            launch(Dispatchers.Main) { done() }
        }
    }

    /**
     * Adds the songs of [items] (a folder of the Network screen) and of its subfolders ([children] reads a subfolder).
     * Tags come from the media server (DLNA), or from the files themselves for a shared folder (SMB).
     */
    fun add(
        context: Context,
        origin: String,
        folderLabel: String,
        items: List<DlnaItem>,
        children: (DlnaItem) -> List<DlnaItem>?,
        smb: SmbServer?,
        done: (Int) -> Unit
    ) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        job = scope.launch {
            // 1. every song in the folder and its subfolders (up to 5000, 8 levels)
            val found = ArrayList<Pair<DlnaItem, String>>()
            fun walk(list: List<DlnaItem>, label: String, depth: Int) {
                for (e in list) {
                    if (!isActive || found.size >= 5000) return
                    when {
                        e.kind == 1 && e.url != null -> found += e to label
                        e.kind == 0 && depth < 8 -> {
                            progress.value = "Reading folders… ${found.size}"
                            children(e)?.let { walk(it, "$label/${e.title}", depth + 1) }
                        }
                    }
                }
            }
            walk(items, folderLabel, 0)
            // 2. their tags
            val now = System.currentTimeMillis() / 1000
            val out = ArrayList<Entry>()
            found.forEachIndexed { i, (e, label) ->
                if (!isActive) return@launch
                progress.value = "Adding to the library… ${i + 1} / ${found.size}"
                val url = e.url ?: return@forEachIndexed
                var title = e.title
                var artist = e.artist
                var album = e.album
                var albumArtist: String? = null
                var duration = e.durationMs
                var track = 0
                var year = 0
                var genre = ""
                if (smb != null) {
                    // a shared folder only has file names: the tags are read from the file over the network
                    runCatching {
                        val ch = Smb.open(smb, e.id)
                        val r = MediaMetadataRetriever()
                        try {
                            r.setDataSource(ChannelSource(ch))
                            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }?.let { title = it }
                            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() }?.let { artist = it }
                            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.takeIf { it.isNotBlank() }?.let { album = it }
                            albumArtist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)?.takeIf { it.isNotBlank() }
                            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let { duration = it }
                            track = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)?.substringBefore('/')?.trim()?.toIntOrNull() ?: 0
                            year = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)?.take(4)?.toIntOrNull() ?: 0
                            genre = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE).orEmpty()
                            // the cover inside the file: kept for the album (once)
                            val probe = Song(-1, title, artist ?: "", album ?: "", 0, albumArtist ?: artist ?: "", 0, 0, "", "", 0, 0, "", "", 0)
                            if (!com.powersound.player.data.ArtworkLoader.hasDownloaded(app, probe)) {
                                r.embeddedPicture?.let { com.powersound.player.data.ArtworkLoader.saveDownloaded(app, probe, it) }
                            }
                        } finally {
                            runCatching { r.release() }
                            ch.close()
                        }
                    }
                }
                val a = artist?.takeIf { it.isNotBlank() } ?: "Unknown artist"
                val al = album?.takeIf { it.isNotBlank() } ?: "Unknown album"
                out += Entry(
                    Song(
                        id = idOf(url), title = title, artist = a, album = al, albumId = albumIdOf(albumArtist ?: a, al),
                        albumArtist = albumArtist ?: a, durationMs = duration, track = track, folder = label,
                        mimeType = e.mime ?: "audio/mpeg", size = e.size, year = year, composer = "", genre = genre,
                        dateAdded = now, path = url, hasAlbumArtist = albumArtist != null
                    ),
                    origin, e.artUrl
                )
            }
            val keep = load(app).filterNot { it.origin == origin }
            val ids = out.map { it.song.id }.toSet()
            save(app, keep.filterNot { it.song.id in ids } + out)
            progress.value = null
            launch(Dispatchers.Main) { done(out.size) }
        }
    }

    fun cancel() {
        job?.cancel()
        progress.value = null
    }
}

/** Lets Android's tag reader read a file of a shared folder. */
private class ChannelSource(private val ch: SmbChannel) : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= ch.size()) return -1
        ch.position(position)
        var done = 0
        while (done < size) {
            val n = ch.read(buffer, offset + done, size - done)
            if (n <= 0) break
            done += n
        }
        return if (done == 0) -1 else done
    }

    override fun getSize(): Long = ch.size()
    override fun close() = Unit
}
