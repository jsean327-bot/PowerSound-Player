package com.powersound.player.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** Play counts, last-played times and like/dislike ratings, saved on the phone. */
data class Stats(
    val plays: Map<Long, Int> = emptyMap(),
    val lastPlayed: Map<Long, Long> = emptyMap(),
    val ratings: Map<Long, Int> = emptyMap()
) {
    /** 1 = liked, -1 = disliked, 11..15 = 1..5 stars, 0 = none. */
    fun rating(id: Long?): Int = if (id == null) 0 else ratings[id] ?: 0

    /** Rating shown as stars (likes count as 5, dislikes as 1). */
    fun stars(id: Long?): Int = when (val r = rating(id)) {
        1 -> 5
        -1 -> 1
        in 11..15 -> r - 10
        else -> 0
    }

    fun isTop(id: Long?): Boolean = rating(id) == 1 || stars(id) >= 4
    fun isLow(id: Long?): Boolean = rating(id) == -1 || stars(id) in 1..2
}

object StatsStore {

    private const val PREFS = "powersound_stats"
    private const val KEY = "stats"

    private var prefs: SharedPreferences? = null
    private val _stats = MutableStateFlow(Stats())
    val stats: StateFlow<Stats> = _stats.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        _stats.value = p.getString(KEY, null)?.let { runCatching { fromJson(JSONObject(it)) }.getOrNull() } ?: Stats()
    }

    fun recordPlay(id: Long) {
        val s = _stats.value
        save(
            s.copy(
                plays = s.plays + (id to (s.plays[id] ?: 0) + 1),
                lastPlayed = s.lastPlayed + (id to System.currentTimeMillis())
            )
        )
    }

    /** 1 = liked, -1 = disliked, 11..15 = stars, 0 = none. */
    fun setRating(id: Long, rating: Int) {
        val s = _stats.value
        save(s.copy(ratings = if (rating == 0) s.ratings - id else s.ratings + (id to rating)))
    }

    private fun save(s: Stats) {
        _stats.value = s
        prefs?.edit()?.putString(KEY, toJson(s).toString())?.apply()
    }

    private fun toJson(s: Stats) = JSONObject().apply {
        put("p", JSONObject().apply { s.plays.forEach { (k, v) -> put(k.toString(), v) } })
        put("l", JSONObject().apply { s.lastPlayed.forEach { (k, v) -> put(k.toString(), v) } })
        put("r", JSONObject().apply { s.ratings.forEach { (k, v) -> put(k.toString(), v) } })
    }

    private fun fromJson(o: JSONObject): Stats {
        fun ints(name: String): Map<Long, Int> {
            val obj = o.optJSONObject(name) ?: return emptyMap()
            val m = HashMap<Long, Int>()
            obj.keys().forEach { k -> k.toLongOrNull()?.let { m[it] = obj.optInt(k) } }
            return m
        }
        val last = HashMap<Long, Long>()
        o.optJSONObject("l")?.let { obj ->
            obj.keys().forEach { k -> k.toLongOrNull()?.let { last[it] = obj.optLong(k) } }
        }
        return Stats(ints("p"), last, ints("r"))
    }
}
