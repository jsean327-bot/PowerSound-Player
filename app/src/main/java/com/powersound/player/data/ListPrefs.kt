package com.powersound.player.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/**
 * ⋮ → List Options of one list (Folders, Albums, Artists…): sort, reverse, how it looks. Kept per list, like
 * Poweramp.
 */
data class ListPref(
    /** 0 name, 1 path (case sensitive), 2 path, 3 date added to Library, 4 date added/modified (file), 5 number of tracks */
    val sort: Int = 0,
    val reverse: Boolean = false,
    /** See [GROUP_VIEWS]. */
    val view: Int = VIEW_GRID,
    val titlesOnly: Boolean = false
)

const val VIEW_GRID = 9

/** View As choices, in Poweramp's order (two columns). */
val GROUP_VIEWS = listOf(
    "Grid - 1 line small", "1 line extra small",
    "1 line", "List - no images",
    "List - compact", "List - small",
    "List", "Grid - extra small",
    "Grid - small", "Grid"
)

/** Grid views and their columns; all others are lists (one column). */
fun gridColumns(view: Int): Int = when (view) {
    9 -> 2
    8 -> 3
    0 -> 3
    7 -> 4
    else -> 1
}

object ListPrefs {
    private const val PREFS = "powersound_lists"
    private val _all = MutableStateFlow<Map<String, ListPref>>(emptyMap())
    val all: StateFlow<Map<String, ListPref>> = _all
    private var file: android.content.SharedPreferences? = null

    fun init(context: Context) {
        if (file != null) return
        val f = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        file = f
        _all.value = runCatching {
            val o = JSONObject(f.getString("v", null) ?: "{}")
            o.keys().asSequence().associateWith { k ->
                val p = o.getJSONObject(k)
                ListPref(p.optInt("sort", 0), p.optBoolean("reverse", false), p.optInt("view", VIEW_GRID), p.optBoolean("titles", false))
            }
        }.getOrDefault(emptyMap())
    }

    fun get(list: String): ListPref = _all.value[list] ?: ListPref()

    fun update(list: String, change: (ListPref) -> ListPref) {
        val next = _all.value + (list to change(get(list)))
        _all.value = next
        val o = JSONObject()
        next.forEach { (k, p) ->
            o.put(k, JSONObject().put("sort", p.sort).put("reverse", p.reverse).put("view", p.view).put("titles", p.titlesOnly))
        }
        file?.edit()?.putString("v", o.toString())?.apply()
    }
}
