package com.powersound.player.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class Playlist(val id: Long, val name: String, val songIds: List<Long>)

data class Bookmark(val songId: Long, val positionMs: Long, val createdAt: Long)

/** An internet radio / stream address. */
data class StreamItem(val id: Long, val name: String, val url: String)

/** User playlists and bookmarks, saved on the phone. */
object LibraryStores {

    private const val PREFS = "powersound_library"
    private const val KEY_PLAYLISTS = "playlists"
    private const val KEY_BOOKMARKS = "bookmarks"
    private const val KEY_STREAMS = "streams"

    private var prefs: SharedPreferences? = null

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    private val _streams = MutableStateFlow<List<StreamItem>>(emptyList())
    val streams: StateFlow<List<StreamItem>> = _streams.asStateFlow()

    private val _bookmarks = MutableStateFlow<List<Bookmark>>(emptyList())
    val bookmarks: StateFlow<List<Bookmark>> = _bookmarks.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        _playlists.value = runCatching {
            val arr = JSONArray(p.getString(KEY_PLAYLISTS, "[]"))
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val ids = o.optJSONArray("songs") ?: JSONArray()
                Playlist(o.optLong("id"), o.optString("name"), (0 until ids.length()).map { ids.optLong(it) })
            }
        }.getOrDefault(emptyList())
        _bookmarks.value = runCatching {
            val arr = JSONArray(p.getString(KEY_BOOKMARKS, "[]"))
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Bookmark(o.optLong("song"), o.optLong("pos"), o.optLong("at"))
            }
        }.getOrDefault(emptyList())
        _streams.value = runCatching {
            val arr = JSONArray(p.getString(KEY_STREAMS, "[]"))
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                StreamItem(o.optLong("id"), o.optString("name"), o.optString("url"))
            }
        }.getOrDefault(emptyList())
    }

    // ---- streams ----

    fun saveStream(item: StreamItem) {
        val list = if (_streams.value.any { it.id == item.id }) _streams.value.map { if (it.id == item.id) item else it }
        else _streams.value + item
        saveStreams(list)
    }

    fun deleteStream(id: Long) = saveStreams(_streams.value.filterNot { it.id == id })

    private fun saveStreams(list: List<StreamItem>) {
        _streams.value = list
        val arr = JSONArray()
        list.forEach { s -> arr.put(JSONObject().apply { put("id", s.id); put("name", s.name); put("url", s.url) }) }
        prefs?.edit()?.putString(KEY_STREAMS, arr.toString())?.apply()
    }

    // ---- playlists ----

    fun createPlaylist(name: String, songIds: List<Long> = emptyList()): Playlist {
        val pl = Playlist(maxOf(System.currentTimeMillis(), (_playlists.value.maxOfOrNull { it.id } ?: 0L) + 1), name.trim().ifBlank { "Playlist" }, songIds.distinct())
        savePlaylists(_playlists.value + pl)
        return pl
    }

    /** Settings → Library → Playlists: Playlist Insert Position and Don't Add Duplicates. */
    fun addToPlaylist(id: Long, songIds: List<Long>) {
        val st = SettingsStore.value
        savePlaylists(_playlists.value.map { p ->
            if (p.id != id) return@map p
            val add = if (st.playlistNoDuplicates) songIds.distinct().filterNot { it in p.songIds } else songIds
            val merged = when (st.playlistInsert) {
                0 -> add + p.songIds
                2 -> {
                    val out = p.songIds.toMutableList()
                    add.forEach { out.add((0..out.size).random(), it) }
                    out
                }
                else -> p.songIds + add
            }
            p.copy(songIds = merged)
        })
    }

    fun replacePlaylistSongs(id: Long, songIds: List<Long>) {
        savePlaylists(_playlists.value.map { if (it.id == id) it.copy(songIds = songIds) else it })
    }

    /** A deleted song leaves every playlist. */
    fun removeSongEverywhere(songId: Long) {
        if (_playlists.value.none { songId in it.songIds }) return
        savePlaylists(_playlists.value.map { p -> p.copy(songIds = p.songIds.filterNot { it == songId }) })
    }

    // ---- m3u / m3u8 (Settings → Library → Playlists) ----

    /** Reads an .m3u / .m3u8 file; entries are matched to library songs by path, then by file name. */
    fun importM3u(name: String, bytes: ByteArray, isUtf8File: Boolean, songs: List<Song>): Int {
        val st = SettingsStore.value
        val charset = if (isUtf8File || st.m3uUtf8) Charsets.UTF_8
        else runCatching { java.nio.charset.Charset.forName(TAG_ENCODINGS[st.tagEncoding.coerceIn(0, TAG_ENCODINGS.lastIndex)].second) }
            .getOrDefault(Charsets.ISO_8859_1)
        val byPath = songs.associateBy { it.path.lowercase() }
        val byName = songs.groupBy { it.path.substringAfterLast('/').lowercase() }
        val ids = String(bytes, charset).removePrefix("\uFEFF").lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line ->
                val path = line.replace('\\', '/').removePrefix("file://")
                byPath[path.lowercase()]?.id ?: byName[path.substringAfterLast('/').lowercase()]?.firstOrNull()?.id
            }.toList()
        if (ids.isEmpty()) return 0
        replacePlaylistSongs(createPlaylist(name).id, ids)
        return ids.size
    }

    /** One .m3u8 text per playlist (UTF-8, with #EXTINF lines). */
    fun toM3u8(p: Playlist, songOf: (Long) -> Song?): String = buildString {
        append("#EXTM3U\n")
        p.songIds.mapNotNull(songOf).forEach { s ->
            append("#EXTINF:").append(s.durationMs / 1000).append(',').append(s.artist).append(" - ").append(s.title).append('\n')
            append(s.path).append('\n')
        }
    }

    fun removeFromPlaylist(id: Long, songId: Long) {
        savePlaylists(_playlists.value.map { p -> if (p.id == id) p.copy(songIds = p.songIds - songId) else p })
    }

    fun renamePlaylist(id: Long, name: String) {
        savePlaylists(_playlists.value.map { p -> if (p.id == id) p.copy(name = name.trim().ifBlank { p.name }) else p })
    }

    fun deletePlaylist(id: Long) {
        savePlaylists(_playlists.value.filterNot { it.id == id })
    }

    private fun savePlaylists(list: List<Playlist>) {
        _playlists.value = list
        val arr = JSONArray()
        list.forEach { p ->
            arr.put(JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("songs", JSONArray().apply { p.songIds.forEach { put(it) } })
            })
        }
        prefs?.edit()?.putString(KEY_PLAYLISTS, arr.toString())?.apply()
    }

    // ---- bookmarks ----

    fun addBookmark(songId: Long, positionMs: Long) {
        val list = listOf(Bookmark(songId, positionMs, System.currentTimeMillis())) + _bookmarks.value
        saveBookmarks(list.take(200))
    }

    fun removeBookmark(b: Bookmark) {
        saveBookmarks(_bookmarks.value.filterNot { it.createdAt == b.createdAt && it.songId == b.songId })
    }

    private fun saveBookmarks(list: List<Bookmark>) {
        _bookmarks.value = list
        val arr = JSONArray()
        list.forEach { b ->
            arr.put(JSONObject().apply {
                put("song", b.songId)
                put("pos", b.positionMs)
                put("at", b.createdAt)
            })
        }
        prefs?.edit()?.putString(KEY_BOOKMARKS, arr.toString())?.apply()
    }
}
