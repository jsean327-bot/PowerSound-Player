package com.powersound.player.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Everything on the Settings screens. */
data class AppSettings(
    // ---- Look and Feel ----
    val accent: Int = 0,
    val keepScreenOn: Boolean = false,
    // ---- Audio ----
    val replayGain: Int = 0,             // 0 off, 1 track, 2 album
    val replayGainPreamp: Double = 0.0,  // dB added to the ReplayGain value
    val replayGainNoTagDb: Double = 0.0, // gain for files without ReplayGain tags
    val fadeOnPause: Boolean = true,
    val fadeMs: Int = 350,
    // ---- Visualization ----
    val vizFadedOpacity: Float = 0.42f,
    val vizPresetSeconds: Int = 0,       // 0 = keep the same preset
    // ---- Background ----
    val bgEnabled: Boolean = true,
    val bgBlur: Int = 80,                // dp
    val bgIntensity: Float = 0.55f,
    val bgSaturation: Float = 1f,
    // ---- Album Art ----
    val artHighQuality: Boolean = true,
    // ---- Library ----
    val minDurationSec: Int = 0,
    val excludedFolders: Set<String> = emptySet(),
    val longTrackMin: Int = 10,
    val hiddenCategories: Set<String> = emptySet(),
    // ---- Headset / Bluetooth ----
    val pauseOnDisconnect: Boolean = true,
    val resumeOnBluetooth: Boolean = false,
    val resumeOnWired: Boolean = false,
    // ---- Misc ----
    val rememberPosition: Boolean = true,
    val stopOnSwipeAway: Boolean = false,
    val confirmDelete: Boolean = true,
    val keepCrashLog: Boolean = true
)

object SettingsStore {

    const val PREFS = "powersound_settings"
    private const val KEY = "settings"
    private const val KEY_POS = "positions"

    private var prefs: SharedPreferences? = null
    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    /** Last position of long songs (songId → ms), for "Remember position". */
    private val positions = LinkedHashMap<Long, Long>()

    val value: AppSettings get() = _settings.value

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        _settings.value = p.getString(KEY, null)?.let { runCatching { fromJson(JSONObject(it)) }.getOrNull() } ?: AppSettings()
        runCatching {
            val o = JSONObject(p.getString(KEY_POS, "{}") ?: "{}")
            o.keys().forEach { k -> k.toLongOrNull()?.let { positions[it] = o.optLong(k) } }
        }
    }

    fun update(change: (AppSettings) -> AppSettings) {
        val next = change(_settings.value)
        _settings.value = next
        prefs?.edit()?.putString(KEY, toJson(next).toString())?.apply()
    }

    // ---- remembered positions ----

    fun savedPosition(songId: Long): Long = synchronized(positions) { positions[songId] ?: 0L }

    fun savePosition(songId: Long, ms: Long) {
        synchronized(positions) {
            positions.remove(songId)
            if (ms > 0) positions[songId] = ms
            while (positions.size > 300) positions.remove(positions.keys.first())
            val o = JSONObject()
            positions.forEach { (k, v) -> o.put(k.toString(), v) }
            prefs?.edit()?.putString(KEY_POS, o.toString())?.apply()
        }
    }

    // ---- json ----

    private fun toJson(s: AppSettings) = JSONObject().apply {
        put("accent", s.accent); put("keepScreenOn", s.keepScreenOn)
        put("rg", s.replayGain); put("rgPre", s.replayGainPreamp); put("rgNoTag", s.replayGainNoTagDb)
        put("fade", s.fadeOnPause); put("fadeMs", s.fadeMs)
        put("vizOpacity", s.vizFadedOpacity.toDouble()); put("vizSec", s.vizPresetSeconds)
        put("bg", s.bgEnabled); put("bgBlur", s.bgBlur); put("bgInt", s.bgIntensity.toDouble()); put("bgSat", s.bgSaturation.toDouble())
        put("artHq", s.artHighQuality)
        put("minDur", s.minDurationSec); put("longMin", s.longTrackMin)
        put("excluded", JSONArray().apply { s.excludedFolders.forEach { put(it) } })
        put("hidden", JSONArray().apply { s.hiddenCategories.forEach { put(it) } })
        put("pauseDisc", s.pauseOnDisconnect); put("resumeBt", s.resumeOnBluetooth); put("resumeWired", s.resumeOnWired)
        put("rememberPos", s.rememberPosition); put("stopSwipe", s.stopOnSwipeAway); put("confirmDel", s.confirmDelete)
        put("crashLog", s.keepCrashLog)
    }

    private fun fromJson(o: JSONObject): AppSettings {
        val d = AppSettings()
        fun strings(name: String): Set<String> {
            val a = o.optJSONArray(name) ?: return emptySet()
            return (0 until a.length()).map { a.optString(it) }.toSet()
        }
        return AppSettings(
            accent = o.optInt("accent", d.accent),
            keepScreenOn = o.optBoolean("keepScreenOn", d.keepScreenOn),
            replayGain = o.optInt("rg", d.replayGain),
            replayGainPreamp = o.optDouble("rgPre", d.replayGainPreamp),
            replayGainNoTagDb = o.optDouble("rgNoTag", d.replayGainNoTagDb),
            fadeOnPause = o.optBoolean("fade", d.fadeOnPause),
            fadeMs = o.optInt("fadeMs", d.fadeMs),
            vizFadedOpacity = o.optDouble("vizOpacity", d.vizFadedOpacity.toDouble()).toFloat(),
            vizPresetSeconds = o.optInt("vizSec", d.vizPresetSeconds),
            bgEnabled = o.optBoolean("bg", d.bgEnabled),
            bgBlur = o.optInt("bgBlur", d.bgBlur),
            bgIntensity = o.optDouble("bgInt", d.bgIntensity.toDouble()).toFloat(),
            bgSaturation = o.optDouble("bgSat", d.bgSaturation.toDouble()).toFloat(),
            artHighQuality = o.optBoolean("artHq", d.artHighQuality),
            minDurationSec = o.optInt("minDur", d.minDurationSec),
            longTrackMin = o.optInt("longMin", d.longTrackMin),
            excludedFolders = strings("excluded"),
            hiddenCategories = strings("hidden"),
            pauseOnDisconnect = o.optBoolean("pauseDisc", d.pauseOnDisconnect),
            resumeOnBluetooth = o.optBoolean("resumeBt", d.resumeOnBluetooth),
            resumeOnWired = o.optBoolean("resumeWired", d.resumeOnWired),
            rememberPosition = o.optBoolean("rememberPos", d.rememberPosition),
            stopOnSwipeAway = o.optBoolean("stopSwipe", d.stopOnSwipeAway),
            confirmDelete = o.optBoolean("confirmDel", d.confirmDelete),
            keepCrashLog = o.optBoolean("crashLog", d.keepCrashLog)
        )
    }
}

/** Export / import of every setting and the user's data (EQ, presets, playlists, bookmarks, stats…). */
object DataBackup {

    private val FILES = listOf(
        "powersound_audio", "powersound_stats", "powersound_library",
        "powersound_player", SettingsStore.PREFS, "powersound_hints"
    )

    fun export(context: Context): String {
        val root = JSONObject()
        root.put("app", "PowerSound Player")
        root.put("version", 1)
        FILES.forEach { name ->
            val p = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            val o = JSONObject()
            p.all.forEach { (k, v) ->
                val entry = JSONObject()
                when (v) {
                    is Boolean -> entry.put("t", "b").put("v", v)
                    is Int -> entry.put("t", "i").put("v", v)
                    is Long -> entry.put("t", "l").put("v", v)
                    is Float -> entry.put("t", "f").put("v", v.toDouble())
                    is String -> entry.put("t", "s").put("v", v)
                    else -> return@forEach
                }
                o.put(k, entry)
            }
            root.put(name, o)
        }
        return root.toString(2)
    }

    /** Returns true when the file was a PowerSound backup and was restored. */
    fun import(context: Context, json: String): Boolean {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return false
        if (root.optString("app") != "PowerSound Player") return false
        FILES.forEach { name ->
            val o = root.optJSONObject(name) ?: return@forEach
            val e = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
            o.keys().forEach { k ->
                val entry = o.optJSONObject(k) ?: return@forEach
                when (entry.optString("t")) {
                    "b" -> e.putBoolean(k, entry.optBoolean("v"))
                    "i" -> e.putInt(k, entry.optInt("v"))
                    "l" -> e.putLong(k, entry.optLong("v"))
                    "f" -> e.putFloat(k, entry.optDouble("v").toFloat())
                    "s" -> e.putString(k, entry.optString("v"))
                }
            }
            e.commit()
        }
        return true
    }
}

/** Saves the last crash to a file so it can be read and shared from Settings. */
object CrashLog {
    private const val FILE = "last_crash.txt"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is Handler) return
        Thread.setDefaultUncaughtExceptionHandler(Handler(app, previous))
    }

    private class Handler(val app: Context, val previous: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            try {
                if (SettingsStore.value.keepCrashLog) {
                    val text = "PowerSound crash • ${java.util.Date()}\nThread: ${t.name}\n\n" + android.util.Log.getStackTraceString(e)
                    java.io.File(app.filesDir, FILE).writeText(text)
                }
            } catch (_: Exception) {
            }
            previous?.uncaughtException(t, e)
        }
    }

    fun read(context: Context): String? = runCatching {
        java.io.File(context.filesDir, FILE).takeIf { it.exists() }?.readText()
    }.getOrNull()

    fun clear(context: Context) {
        runCatching { java.io.File(context.filesDir, FILE).delete() }
    }
}
