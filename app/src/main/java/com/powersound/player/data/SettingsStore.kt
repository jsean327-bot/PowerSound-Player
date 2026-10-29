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
    val keepScreenOn: Boolean = false,       // always keep the screen on in the app
    val settingsTheme: Int = 0,              // 0 default, 1 light, 2 dark, 3 follow day/night
    val settingsFont: Int = 0,               // 0 default, 1 alternative, 2 bold, 3 bold+
    val language: Int = 0,                   // 0 auto, 1 English
    val launcherIcon: Int = 0,               // index in LauncherIcons.ALL
    val orientation: Int = 0,                // 0 default, 1 portrait, 2 landscape
    val animations: Int = 2,                 // 0 disabled, 1 fast, 2 default
    val followDayNight: Boolean = false,     // PowerSound Dark by day, PowerSound Black at night
    val startAtLibrary: Boolean = false,
    val hideStatusBar: Boolean = false,
    val menuShortcuts: Int = 5,              // settings shortcuts in the main menu (0..MAX)
    // ---- Look and Feel → Skin ----
    val skin: Int = 0,                       // 0 PowerSound Dark, 1 PowerSound Black (AMOLED)
    val layout: Int = 0,                     // 0 default, 1 alternative, 2 full cover
    val labelsCentered: Boolean = false,
    val labelsBg: Boolean = true,
    val buttonsBg: Boolean = true,
    val materialYou: Int = 0,                // 0 disabled, 1 more pronounced, 2 less pronounced
    val appFont: Int = 0,                    // 0 default, 1 alternative, 2 bold, 3 bold+
    val moreRounded: Boolean = true,
    val seekbarStyle: Int = 0,               // 0 default (live waveform), 1 static waveform, 2 simple (+ pro buttons)
    val proButtons: Boolean = true,
    val knobHighlight: Int = 2,              // 0 none, 1 monochromatic, 2 colorful
    val eqCurveColorful: Boolean = true,
    val statusBarNoBg: Boolean = false,
    val navbarStyle: Int = 0,                // 0 default, 1 semi-transparent, 2 no background
    val offsetNavbar: Boolean = false,
    val androidNavNoBg: Boolean = false,
    // ---- Look and Feel → Player Screen ----
    val artAnimation: Boolean = true,
    val marquee: Boolean = true,
    val marqueeEverywhere: Boolean = false,
    val playerButtons: List<String> = DEFAULT_PLAYER_BUTTONS,
    val preferNoGap: Boolean = false,
    val noEditLongPress: Boolean = false,
    val ratingType: Int = 1,                 // 0 disabled, 1 like/unlike, 2 5 stars, 3 5 stars (menu/lists only)
    val menuButton: Boolean = true,
    val menuLongPress: Int = 0,              // index in MENU_LONG_PRESS
    val lyricsButton: Int = 1,               // 0 disabled, 1 when local lyrics available, 2 always
    val line2Press: Int = 0,                 // 0 disabled, 1 artist, 2 album, 3 folder, 4 genre
    val line2LongPress: Int = 1,
    val prevResets: Boolean = false,         // << first rewinds, second press changes track
    val proRewindLong: Boolean = true,       // pro buttons jump -10/+10 s in long tracks
    val navigateToFolders: Boolean = false,  // track menu → Folders category instead of the hierarchy
    // ---- Look and Feel → Lyrics ----
    val lyricsSwipeUp: Int = 2,              // 0 disabled, 1 when local lyrics available, 2 always
    val lyricsMenuItem: Int = 0,             // 0 open lyrics screen, 1 open 3rd party app
    val lyricsKeepScreenOn: Boolean = false,
    val lyricsOffsetMs: Int = 0,
    val lyricsSize: Int = 1,                 // 0 small, 1 default, 2 large
    val lyricsApp: Int = 2,                  // index in LYRICS_APPS
    val lyricsCustomUrl: String = DEFAULT_LYRICS_URL,
    val scanLrc: Boolean = true,
    val lyricsUtf8: Boolean = true,
    // ---- Look and Feel → Notifications ----
    val notifButtons: List<Int> = DEFAULT_NOTIF_BUTTONS,
    val notifNavigateToList: Boolean = false,
    val keepNotification: Boolean = true,
    val removeNotifOnDisconnect: Boolean = false,
    // ---- Look and Feel → List UI ----
    val filenameAsTitle: Boolean = false,
    val showTrackNumber: Boolean = false,
    // ---- Audio ----
    val replayGain: Int = 0,             // 0 don't apply, 1 apply gain, 2 apply gain and prevent clipping (peak)
    val rgSource: Int = 0,               // 0 album, 1 track
    val replayGainPreamp: Double = 0.0,  // dB added to the ReplayGain value
    val replayGainNoTagDb: Double = 0.0, // gain for files without ReplayGain tags
    val fadeOnPause: Boolean = true,
    val fadeMs: Int = 350,
    // ---- Audio → Crossfade, Fade, and Gapless ----
    val autoFade: Int = 0,                   // 0 no fading, 1 non-gapless tracks, 2 all songs, 3 shuffled songs
    val crossfadeSec: Float = 5f,
    val manualFade: Int = 1,                 // 0 no fading, 1 short crossfade, 2 crossfade
    val fadeOnSeek: Boolean = true,
    val silenceMs: Int = 0,
    // ---- Audio → Audio Focus ----
    val resumeAfterCall: Boolean = true,
    val resumeOnStart: Boolean = false,
    val resumeOnReopen: Boolean = false,
    val pauseOnShortFocus: Boolean = true,
    val resumeOnFocusGain: Boolean = true,
    val duckVolume: Boolean = true,
    val pauseOnPermanentFocus: Boolean = true,
    // ---- Audio → Equalizer ----
    val eqBandCount: Int = 10,
    val eqCustomBands: Boolean = false,
    val eqCustomCount: Int = 10,
    val eqFirstFreq: Int = 20,
    val eqLastFreq: Int = 16000,
    val eqValues: Int = 1,                   // 0 hidden, 1 dB, 2 %
    val toneValues: Int = 2,                 // 0 hidden, 1 dB, 2 %
    val eqAutoSave: Boolean = true,
    // ---- Audio → Resampler ----
    val resamplerType: Int = 0,              // 0 high quality, 1 very high quality
    val resamplerCutoff: Float = 0.97f,
    val dither: Int = 2,                     // index in Ditherer.NAMES (triangular)
    // ---- Audio → Audio Outputs (per output: wired, speaker, bt, usb, other) ----
    val outEqPreset: Map<String, String> = emptyMap(),
    val outNoEq: Set<String> = emptySet(),
    val outNoDuck: Set<String> = emptySet(),
    val outDelayMs: Map<String, Int> = mapOf("bt" to 300),
    val outRate: Map<String, Int> = emptyMap(),   // 0 follow track, 1 phone's native rate, else Hz
    // ---- Audio → Bit Perfect ----
    val perfectBitPerfect: Boolean = false,
    // ---- Audio → Advanced Tweaks ----
    val pauseOnVolumeZero: Boolean = false,
    val musicFx: Boolean = false,
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

/** Settings → Audio → Audio Outputs: the outputs that have their own settings. */
val OUTPUTS = listOf("wired" to "Wired Headset/AUX", "speaker" to "Speaker", "bt" to "Bluetooth", "usb" to "USB DAC", "other" to "Other Output Devices")

/** Equalizer band layout chosen in Settings → Equalizer → Graphic Equalizer Bands: (count, first Hz, last Hz). */
fun AppSettings.eqLayout(): Triple<Int, Double, Double> = when {
    eqCustomBands -> Triple(eqCustomCount, eqFirstFreq.toDouble(), eqLastFreq.toDouble())
    eqBandCount == 31 -> Triple(31, 20.0, 20000.0)
    eqBandCount == 5 -> Triple(5, 60.0, 12000.0)
    else -> Triple(eqBandCount, 31.0, 16000.0)
}

val DEFAULT_PLAYER_BUTTONS = listOf("viz", "sleep", "repeat", "shuffle")
val DEFAULT_NOTIF_BUTTONS = listOf(2, 3, 1, 7, 8, 0) // shuffle, close | repeat, -10, +10, none
/** Settings → Notifications → Buttons. Index stored in [AppSettings.notifButtons]. */
val NOTIF_ACTIONS = listOf("None", "Repeat", "Shuffle", "Close", "Like", "Unlike", "Rating", "-10", "+10", "Prev. category", "Next category")
/** Settings → Player Screen → Menu Button Long Press. */
val MENU_LONG_PRESS = listOf("Disabled", "Delete", "Add to Playlist", "Info/Tags", "Album Art", "Artist", "Album", "Folder", "Genre", "Share", "Like", "Unlike")
/** Settings → Lyrics → Preferred Lyrics App. */
val LYRICS_APPS = listOf("None", "Browser via custom URL", "Google", "Google (Web)", "Musixmatch", "Genius")
const val DEFAULT_LYRICS_URL = "https://www.google.com/search?q=lyrics %ARTIST% %TITLE%"

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
        put("rgMode", s.replayGain); put("rgSrc", s.rgSource); put("rgPre", s.replayGainPreamp); put("rgNoTag", s.replayGainNoTagDb)
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
        put("setTheme", s.settingsTheme); put("setFont", s.settingsFont); put("lang", s.language); put("icon", s.launcherIcon)
        put("orient", s.orientation); put("anim", s.animations); put("startLib", s.startAtLibrary); put("dayNight", s.followDayNight); put("hideStatus", s.hideStatusBar)
        put("shortcuts", s.menuShortcuts)
        put("skin", s.skin); put("layout", s.layout); put("labelsCenter", s.labelsCentered); put("labelsBg", s.labelsBg)
        put("buttonsBg", s.buttonsBg); put("you", s.materialYou); put("appFont", s.appFont); put("rounded", s.moreRounded)
        put("seekbar", s.seekbarStyle); put("pro", s.proButtons); put("knob", s.knobHighlight); put("curveColor", s.eqCurveColorful)
        put("statusNoBg", s.statusBarNoBg); put("navbar", s.navbarStyle); put("navOffset", s.offsetNavbar); put("sysNavNoBg", s.androidNavNoBg)
        put("artAnim", s.artAnimation); put("marquee", s.marquee); put("marqueeAll", s.marqueeEverywhere)
        put("playerButtons", JSONArray().apply { s.playerButtons.forEach { put(it) } })
        put("noGap", s.preferNoGap); put("noEditLp", s.noEditLongPress); put("ratingType", s.ratingType); put("menuBtn", s.menuButton)
        put("menuLp", s.menuLongPress); put("lyricsBtn", s.lyricsButton); put("line2", s.line2Press); put("line2Lp", s.line2LongPress)
        put("prevResets", s.prevResets); put("proRewind", s.proRewindLong); put("navFolders", s.navigateToFolders)
        put("lyricsSwipe", s.lyricsSwipeUp); put("lyricsMenu", s.lyricsMenuItem); put("lyricsScreen", s.lyricsKeepScreenOn)
        put("lyricsOffset", s.lyricsOffsetMs); put("lyricsSize", s.lyricsSize); put("lyricsApp", s.lyricsApp)
        put("lyricsUrl", s.lyricsCustomUrl); put("scanLrc", s.scanLrc); put("utf8", s.lyricsUtf8)
        put("notifButtons", JSONArray().apply { s.notifButtons.forEach { put(it) } })
        put("notifNav", s.notifNavigateToList); put("keepNotif", s.keepNotification); put("notifDisc", s.removeNotifOnDisconnect)
        put("fileTitle", s.filenameAsTitle); put("trackNo", s.showTrackNumber)
        put("autoFade", s.autoFade); put("xfadeSec", s.crossfadeSec.toDouble()); put("manualFade", s.manualFade)
        put("fadeSeek", s.fadeOnSeek); put("silenceMs", s.silenceMs)
        put("resCall", s.resumeAfterCall); put("resStart", s.resumeOnStart); put("resReopen", s.resumeOnReopen)
        put("shortFocus", s.pauseOnShortFocus); put("resFocus", s.resumeOnFocusGain); put("duck", s.duckVolume); put("permFocus", s.pauseOnPermanentFocus)
        put("eqBands", s.eqBandCount); put("eqCustom", s.eqCustomBands); put("eqCustomN", s.eqCustomCount)
        put("eqFirst", s.eqFirstFreq); put("eqLast", s.eqLastFreq); put("eqValues", s.eqValues); put("toneValues", s.toneValues)
        put("eqAutoSave", s.eqAutoSave)
        put("resType", s.resamplerType); put("resCut", s.resamplerCutoff.toDouble()); put("dither", s.dither)
        put("outEq", JSONObject().apply { s.outEqPreset.forEach { (k, v) -> put(k, v) } })
        put("outNoEq", JSONArray().apply { s.outNoEq.forEach { put(it) } })
        put("outNoDuck", JSONArray().apply { s.outNoDuck.forEach { put(it) } })
        put("outDelay", JSONObject().apply { s.outDelayMs.forEach { (k, v) -> put(k, v) } })
        put("outRate", JSONObject().apply { s.outRate.forEach { (k, v) -> put(k, v) } })
        put("bitPerfect", s.perfectBitPerfect); put("volZero", s.pauseOnVolumeZero); put("musicFx", s.musicFx)
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
            // older versions stored "rg": 1 = track gain, 2 = album gain
            replayGain = if (o.has("rgMode")) o.optInt("rgMode", d.replayGain) else if (o.optInt("rg", 0) > 0) 1 else 0,
            rgSource = if (o.has("rgSrc")) o.optInt("rgSrc", d.rgSource) else if (o.optInt("rg", 0) == 1) 1 else 0,
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
            keepCrashLog = o.optBoolean("crashLog", d.keepCrashLog),
            settingsTheme = o.optInt("setTheme", d.settingsTheme),
            settingsFont = o.optInt("setFont", d.settingsFont),
            language = o.optInt("lang", d.language),
            launcherIcon = o.optInt("icon", d.launcherIcon),
            orientation = o.optInt("orient", d.orientation),
            animations = o.optInt("anim", d.animations),
            startAtLibrary = o.optBoolean("startLib", d.startAtLibrary),
            followDayNight = o.optBoolean("dayNight", d.followDayNight),
            hideStatusBar = o.optBoolean("hideStatus", d.hideStatusBar),
            menuShortcuts = o.optInt("shortcuts", d.menuShortcuts),
            skin = o.optInt("skin", d.skin),
            layout = o.optInt("layout", d.layout),
            labelsCentered = o.optBoolean("labelsCenter", d.labelsCentered),
            labelsBg = o.optBoolean("labelsBg", d.labelsBg),
            buttonsBg = o.optBoolean("buttonsBg", d.buttonsBg),
            materialYou = o.optInt("you", d.materialYou),
            appFont = o.optInt("appFont", d.appFont),
            moreRounded = o.optBoolean("rounded", d.moreRounded),
            seekbarStyle = o.optInt("seekbar", d.seekbarStyle),
            proButtons = o.optBoolean("pro", d.proButtons),
            knobHighlight = o.optInt("knob", d.knobHighlight),
            eqCurveColorful = o.optBoolean("curveColor", d.eqCurveColorful),
            statusBarNoBg = o.optBoolean("statusNoBg", d.statusBarNoBg),
            navbarStyle = o.optInt("navbar", d.navbarStyle),
            offsetNavbar = o.optBoolean("navOffset", d.offsetNavbar),
            androidNavNoBg = o.optBoolean("sysNavNoBg", d.androidNavNoBg),
            artAnimation = o.optBoolean("artAnim", d.artAnimation),
            marquee = o.optBoolean("marquee", d.marquee),
            marqueeEverywhere = o.optBoolean("marqueeAll", d.marqueeEverywhere),
            playerButtons = o.optJSONArray("playerButtons")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: d.playerButtons,
            preferNoGap = o.optBoolean("noGap", d.preferNoGap),
            noEditLongPress = o.optBoolean("noEditLp", d.noEditLongPress),
            ratingType = o.optInt("ratingType", d.ratingType),
            menuButton = o.optBoolean("menuBtn", d.menuButton),
            menuLongPress = o.optInt("menuLp", d.menuLongPress),
            lyricsButton = o.optInt("lyricsBtn", d.lyricsButton),
            line2Press = o.optInt("line2", d.line2Press),
            line2LongPress = o.optInt("line2Lp", d.line2LongPress),
            prevResets = o.optBoolean("prevResets", d.prevResets),
            proRewindLong = o.optBoolean("proRewind", d.proRewindLong),
            navigateToFolders = o.optBoolean("navFolders", d.navigateToFolders),
            lyricsSwipeUp = o.optInt("lyricsSwipe", d.lyricsSwipeUp),
            lyricsMenuItem = o.optInt("lyricsMenu", d.lyricsMenuItem),
            lyricsKeepScreenOn = o.optBoolean("lyricsScreen", d.lyricsKeepScreenOn),
            lyricsOffsetMs = o.optInt("lyricsOffset", d.lyricsOffsetMs),
            lyricsSize = o.optInt("lyricsSize", d.lyricsSize),
            lyricsApp = o.optInt("lyricsApp", d.lyricsApp),
            lyricsCustomUrl = o.optString("lyricsUrl", d.lyricsCustomUrl),
            scanLrc = o.optBoolean("scanLrc", d.scanLrc),
            lyricsUtf8 = o.optBoolean("utf8", d.lyricsUtf8),
            notifButtons = o.optJSONArray("notifButtons")?.let { a -> (0 until a.length()).map { a.optInt(it) } }?.takeIf { it.size == 6 } ?: d.notifButtons,
            notifNavigateToList = o.optBoolean("notifNav", d.notifNavigateToList),
            keepNotification = o.optBoolean("keepNotif", d.keepNotification),
            removeNotifOnDisconnect = o.optBoolean("notifDisc", d.removeNotifOnDisconnect),
            filenameAsTitle = o.optBoolean("fileTitle", d.filenameAsTitle),
            showTrackNumber = o.optBoolean("trackNo", d.showTrackNumber),
            autoFade = o.optInt("autoFade", d.autoFade),
            crossfadeSec = o.optDouble("xfadeSec", d.crossfadeSec.toDouble()).toFloat(),
            manualFade = o.optInt("manualFade", d.manualFade),
            fadeOnSeek = o.optBoolean("fadeSeek", d.fadeOnSeek),
            silenceMs = o.optInt("silenceMs", d.silenceMs),
            resumeAfterCall = o.optBoolean("resCall", d.resumeAfterCall),
            resumeOnStart = o.optBoolean("resStart", d.resumeOnStart),
            resumeOnReopen = o.optBoolean("resReopen", d.resumeOnReopen),
            pauseOnShortFocus = o.optBoolean("shortFocus", d.pauseOnShortFocus),
            resumeOnFocusGain = o.optBoolean("resFocus", d.resumeOnFocusGain),
            duckVolume = o.optBoolean("duck", d.duckVolume),
            pauseOnPermanentFocus = o.optBoolean("permFocus", d.pauseOnPermanentFocus),
            eqBandCount = o.optInt("eqBands", d.eqBandCount),
            eqCustomBands = o.optBoolean("eqCustom", d.eqCustomBands),
            eqCustomCount = o.optInt("eqCustomN", d.eqCustomCount),
            eqFirstFreq = o.optInt("eqFirst", d.eqFirstFreq),
            eqLastFreq = o.optInt("eqLast", d.eqLastFreq),
            eqValues = o.optInt("eqValues", d.eqValues),
            toneValues = o.optInt("toneValues", d.toneValues),
            eqAutoSave = o.optBoolean("eqAutoSave", d.eqAutoSave),
            resamplerType = o.optInt("resType", d.resamplerType),
            resamplerCutoff = o.optDouble("resCut", d.resamplerCutoff.toDouble()).toFloat(),
            dither = o.optInt("dither", d.dither),
            outEqPreset = o.optJSONObject("outEq")?.let { j -> j.keys().asSequence().associateWith { j.optString(it) } } ?: d.outEqPreset,
            outNoEq = strings("outNoEq"),
            outNoDuck = strings("outNoDuck"),
            outDelayMs = o.optJSONObject("outDelay")?.let { j -> j.keys().asSequence().associateWith { j.optInt(it) } } ?: d.outDelayMs,
            outRate = o.optJSONObject("outRate")?.let { j -> j.keys().asSequence().associateWith { j.optInt(it) } } ?: d.outRate,
            perfectBitPerfect = o.optBoolean("bitPerfect", d.perfectBitPerfect),
            pauseOnVolumeZero = o.optBoolean("volZero", d.pauseOnVolumeZero),
            musicFx = o.optBoolean("musicFx", d.musicFx)
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
