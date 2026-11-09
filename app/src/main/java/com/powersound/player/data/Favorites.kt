package com.powersound.player.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Favorites and pins.
 * - songs: favorite songs (Library → Favorite Songs)
 * - groups: favorite albums / artists / folders / genres… by their library key ("album:…", "artist:…", "folder:…")
 * - pinned: groups kept at the top of their list (Folders, Albums, Artists…), whatever the sort
 */
data class Favs(
    val songs: Set<Long> = emptySet(),
    val groups: Set<String> = emptySet(),
    val pinned: Set<String> = emptySet()
) {
    fun isFav(songId: Long) = songId in songs
    fun isFav(groupKey: String) = groupKey in groups
    fun isPinned(groupKey: String) = groupKey in pinned
}

object Favorites {
    private const val PREFS = "powersound_favorites"
    private val _state = MutableStateFlow(Favs())
    val state: StateFlow<Favs> = _state
    val value: Favs get() = _state.value
    private var file: android.content.SharedPreferences? = null

    fun init(context: Context) {
        if (file != null) return
        val f = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        file = f
        _state.value = runCatching {
            val o = JSONObject(f.getString("v", null) ?: "{}")
            fun strings(name: String): Set<String> =
                o.optJSONArray(name)?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() }.toSet() } ?: emptySet()
            Favs(
                songs = o.optJSONArray("songs")?.let { a -> (0 until a.length()).map { a.optLong(it) }.toSet() } ?: emptySet(),
                groups = strings("groups"),
                pinned = strings("pinned")
            )
        }.getOrDefault(Favs())
    }

    private fun set(next: Favs) {
        _state.value = next
        val o = JSONObject()
            .put("songs", JSONArray().apply { next.songs.forEach { put(it) } })
            .put("groups", JSONArray().apply { next.groups.forEach { put(it) } })
            .put("pinned", JSONArray().apply { next.pinned.forEach { put(it) } })
        file?.edit()?.putString("v", o.toString())?.apply()
    }

    fun toggleSong(id: Long) {
        val v = value
        set(v.copy(songs = if (id in v.songs) v.songs - id else v.songs + id))
    }

    /** All favorite → all removed; otherwise all added. Returns true when they are favorites now. */
    fun toggleSongs(ids: Collection<Long>): Boolean {
        val v = value
        val all = ids.isNotEmpty() && ids.all { it in v.songs }
        set(v.copy(songs = if (all) v.songs - ids.toSet() else v.songs + ids))
        return !all
    }

    fun toggleGroup(key: String) = toggleGroups(listOf(key))

    /** All favorite → all removed; otherwise all added. Returns true when they are favorites now. */
    fun toggleGroups(keys: Collection<String>): Boolean {
        val v = value
        val all = keys.isNotEmpty() && keys.all { it in v.groups }
        set(v.copy(groups = if (all) v.groups - keys.toSet() else v.groups + keys))
        return !all
    }

    /** All pinned → all unpinned; otherwise all pinned. Returns true when they are pinned now. */
    fun togglePins(keys: Collection<String>): Boolean {
        val v = value
        val all = keys.isNotEmpty() && keys.all { it in v.pinned }
        set(v.copy(pinned = if (all) v.pinned - keys.toSet() else v.pinned + keys))
        return !all
    }
}
