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

/** User playlists and bookmarks, saved on the phone. */
object LibraryStores {

    private const val PREFS = "powersound_library"
    private const val KEY_PLAYLISTS = "playlists"
    private const val KEY_BOOKMARKS = "bookmarks"

    private var prefs: SharedPreferences? = null

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

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
    }

    // ---- playlists ----

    fun createPlaylist(name: String, songIds: List<Long> = emptyList()): Playlist {
        val pl = Playlist(System.currentTimeMillis(), name.trim().ifBlank { "Playlist" }, songIds.distinct())
        savePlaylists(_playlists.value + pl)
        return pl
    }

    fun addToPlaylist(id: Long, songIds: List<Long>) {
        savePlaylists(_playlists.value.map { p ->
            if (p.id == id) p.copy(songIds = (p.songIds + songIds).distinct()) else p
        })
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
