package com.powersound.player.video

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/**
 * Video player ⋮ → Display Settings (Style, Screen, Controls, Navigation, Text, Layout), Aspect Ratio and
 * Speed FF. Kept apart from the music settings.
 */
data class VideoPrefs(
    // ---- Style
    val frameColor: Int = 0xCC000000.toInt(),
    val controlColor: Int = 0xFFFFFFFF.toInt(),
    val controlBg: Int = 0x73000000,
    /** 0 = the app's color. */
    val progressColor: Int = 0,
    /** 0 Material (round knob), 1 Flat (thin bar). */
    val progressStyle: Int = 0,
    val progressBelow: Boolean = false,

    // ---- Screen
    /** 0 by the video, 1 auto (turn with the phone), 2 landscape, 3 portrait. */
    val orientation: Int = 0,
    /** Status bar: 0 shows with the controls, 1 always hidden, 2 always shown. */
    val fullScreen: Int = 0,
    /** Navigation bar: 0 shows with the controls, 1 always hidden, 2 always shown. */
    val softButtons: Int = 0,
    val brightnessOn: Boolean = false,
    /** 0…15 */
    val brightnessLevel: Int = 15,
    /** Always on screen, also with the controls hidden. */
    val osdElapsed: Boolean = false,
    val osdClock: Boolean = false,
    val osdOffset: Int = 0,
    val osdTextColor: Int = 0xFFDDDDDD.toInt(),
    val osdBackground: Boolean = true,
    val rotationButton: Boolean = true,
    val titleClock: Boolean = false,
    val remainingTime: Boolean = false,
    val keepScreenOn: Boolean = false,
    val pauseObstructed: Boolean = false,
    val showUiAtStart: Boolean = false,

    // ---- Controls
    /** 0 show/hide interface, 1 show interface → pause/resume, 2 show interface + pause/resume, 3 pause/resume. */
    val touchAction: Int = 0,
    /** 0 lock, 1 kids lock, 2 kids lock with touch effects. */
    val lockMode: Int = 0,
    val gSeek: Boolean = true,
    val gZoomPan: Boolean = false,
    val gZoom: Boolean = true,
    val gPan: Boolean = false,
    val gVolume: Boolean = true,
    val gBrightness: Boolean = true,
    val gDoubleTapPlay: Boolean = true,
    val gDoubleTapZoom: Boolean = false,
    val gDoubleTapSeek: Boolean = true,
    val gLongPressSpeed: Boolean = true,
    val gSpeed: Boolean = true,
    val gSubUpDown: Boolean = true,
    val gSubZoom: Boolean = true,
    val shortcuts: Boolean = true,
    val autoHide: Boolean = true,
    /** tenths of a second */
    val autoHideTenths: Int = 30,
    val showUiWhenLocked: Boolean = false,

    // ---- Navigation
    /** seconds of video per inch of finger movement */
    val seekSpeed: Int = 25,
    val moveButtons: Boolean = false,
    val moveInterval: Int = 10,
    val prevNextButtons: Boolean = true,
    val showSeekPosition: Boolean = true,

    // ---- Text (subtitles)
    /** 0 default, 1 sans, 2 serif, 3 monospace, 4 condensed, 5 light, 6 medium, 7 black */
    val subFont: Int = 0,
    /** sp */
    val subSize: Int = 21,
    /** % */
    val subScale: Int = 100,
    val subColor: Int = 0xFFFFFFFF.toInt(),
    val subBold: Boolean = false,
    val subBgOn: Boolean = false,
    val subBgColor: Int = 0x99000000.toInt(),
    val subBorderOn: Boolean = true,
    val subBorderColor: Int = 0xFF000000.toInt(),
    val subShadow: Boolean = false,
    /** Use colors / styles written in SSA/ASS subtitles. */
    val subSsaStyles: Boolean = true,
    /** Use font sizes written in the subtitles. */
    val subSsaSizes: Boolean = false,

    // ---- Layout
    /** 0 left, 1 center, 2 right */
    val subAlign: Int = 1,
    /** 0…50: distance from the bottom, in % of the picture height / 2 */
    val subBottom: Int = 8,
    val subBoxOn: Boolean = false,
    val subBoxColor: Int = 0x66000000,
    val subFitVideo: Boolean = true,

    // ---- More
    /** 0 default, 1 1:1, 2 3:4, 3 9:16, 4 16:9, 5 4:3, 6 21:9, 7 custom */
    val aspect: Int = 0,
    val aspectW: Int = 16,
    val aspectH: Int = 9,
    val aspectAll: Boolean = false,
    /** ×10 */
    val speedFF: Int = 20,
    val reverb: Int = 0
) {
    val progressColorOr: (Int) -> Int get() = { app -> if (progressColor == 0) app else progressColor }
}

object VideoPrefsStore {
    private const val PREFS = "powersound_video_prefs"
    private val _prefs = MutableStateFlow(VideoPrefs())
    val prefs: StateFlow<VideoPrefs> = _prefs
    val value: VideoPrefs get() = _prefs.value
    private var file: android.content.SharedPreferences? = null

    fun init(context: Context) {
        if (file != null) return
        val f = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        file = f
        _prefs.value = runCatching { fromJson(JSONObject(f.getString("v", null) ?: "{}")) }.getOrDefault(VideoPrefs())
    }

    fun update(change: (VideoPrefs) -> VideoPrefs) {
        val next = change(_prefs.value)
        _prefs.value = next
        file?.edit()?.putString("v", toJson(next).toString())?.apply()
    }

    fun reset(section: (VideoPrefs, VideoPrefs) -> VideoPrefs) = update { section(it, VideoPrefs()) }

    private fun toJson(p: VideoPrefs): JSONObject = JSONObject().apply {
        put("frameColor", p.frameColor)
        put("controlColor", p.controlColor)
        put("controlBg", p.controlBg)
        put("progressColor", p.progressColor)
        put("progressStyle", p.progressStyle)
        put("progressBelow", p.progressBelow)
        put("orientation", p.orientation)
        put("fullScreen", p.fullScreen)
        put("softButtons", p.softButtons)
        put("brightnessOn", p.brightnessOn)
        put("brightnessLevel", p.brightnessLevel)
        put("osdElapsed", p.osdElapsed)
        put("osdClock", p.osdClock)
        put("osdOffset", p.osdOffset)
        put("osdTextColor", p.osdTextColor)
        put("osdBackground", p.osdBackground)
        put("rotationButton", p.rotationButton)
        put("titleClock", p.titleClock)
        put("remainingTime", p.remainingTime)
        put("keepScreenOn", p.keepScreenOn)
        put("pauseObstructed", p.pauseObstructed)
        put("showUiAtStart", p.showUiAtStart)
        put("touchAction", p.touchAction)
        put("lockMode", p.lockMode)
        put("gSeek", p.gSeek)
        put("gZoomPan", p.gZoomPan)
        put("gZoom", p.gZoom)
        put("gPan", p.gPan)
        put("gVolume", p.gVolume)
        put("gBrightness", p.gBrightness)
        put("gDoubleTapPlay", p.gDoubleTapPlay)
        put("gDoubleTapZoom", p.gDoubleTapZoom)
        put("gDoubleTapSeek", p.gDoubleTapSeek)
        put("gLongPressSpeed", p.gLongPressSpeed)
        put("gSpeed", p.gSpeed)
        put("gSubUpDown", p.gSubUpDown)
        put("gSubZoom", p.gSubZoom)
        put("shortcuts", p.shortcuts)
        put("autoHide", p.autoHide)
        put("autoHideTenths", p.autoHideTenths)
        put("showUiWhenLocked", p.showUiWhenLocked)
        put("seekSpeed", p.seekSpeed)
        put("moveButtons", p.moveButtons)
        put("moveInterval", p.moveInterval)
        put("prevNextButtons", p.prevNextButtons)
        put("showSeekPosition", p.showSeekPosition)
        put("subFont", p.subFont)
        put("subSize", p.subSize)
        put("subScale", p.subScale)
        put("subColor", p.subColor)
        put("subBold", p.subBold)
        put("subBgOn", p.subBgOn)
        put("subBgColor", p.subBgColor)
        put("subBorderOn", p.subBorderOn)
        put("subBorderColor", p.subBorderColor)
        put("subShadow", p.subShadow)
        put("subSsaStyles", p.subSsaStyles)
        put("subSsaSizes", p.subSsaSizes)
        put("subAlign", p.subAlign)
        put("subBottom", p.subBottom)
        put("subBoxOn", p.subBoxOn)
        put("subBoxColor", p.subBoxColor)
        put("subFitVideo", p.subFitVideo)
        put("aspect", p.aspect)
        put("aspectW", p.aspectW)
        put("aspectH", p.aspectH)
        put("aspectAll", p.aspectAll)
        put("speedFF", p.speedFF)
        put("reverb", p.reverb)
    }

    private fun fromJson(o: JSONObject): VideoPrefs {
        val d = VideoPrefs()
        return VideoPrefs(
            frameColor = o.optInt("frameColor", d.frameColor),
            controlColor = o.optInt("controlColor", d.controlColor),
            controlBg = o.optInt("controlBg", d.controlBg),
            progressColor = o.optInt("progressColor", d.progressColor),
            progressStyle = o.optInt("progressStyle", d.progressStyle),
            progressBelow = o.optBoolean("progressBelow", d.progressBelow),
            orientation = o.optInt("orientation", d.orientation),
            fullScreen = o.optInt("fullScreen", d.fullScreen),
            softButtons = o.optInt("softButtons", d.softButtons),
            brightnessOn = o.optBoolean("brightnessOn", d.brightnessOn),
            brightnessLevel = o.optInt("brightnessLevel", d.brightnessLevel),
            osdElapsed = o.optBoolean("osdElapsed", d.osdElapsed),
            osdClock = o.optBoolean("osdClock", d.osdClock),
            osdOffset = o.optInt("osdOffset", d.osdOffset),
            osdTextColor = o.optInt("osdTextColor", d.osdTextColor),
            osdBackground = o.optBoolean("osdBackground", d.osdBackground),
            rotationButton = o.optBoolean("rotationButton", d.rotationButton),
            titleClock = o.optBoolean("titleClock", d.titleClock),
            remainingTime = o.optBoolean("remainingTime", d.remainingTime),
            keepScreenOn = o.optBoolean("keepScreenOn", d.keepScreenOn),
            pauseObstructed = o.optBoolean("pauseObstructed", d.pauseObstructed),
            showUiAtStart = o.optBoolean("showUiAtStart", d.showUiAtStart),
            touchAction = o.optInt("touchAction", d.touchAction),
            lockMode = o.optInt("lockMode", d.lockMode),
            gSeek = o.optBoolean("gSeek", d.gSeek),
            gZoomPan = o.optBoolean("gZoomPan", d.gZoomPan),
            gZoom = o.optBoolean("gZoom", d.gZoom),
            gPan = o.optBoolean("gPan", d.gPan),
            gVolume = o.optBoolean("gVolume", d.gVolume),
            gBrightness = o.optBoolean("gBrightness", d.gBrightness),
            gDoubleTapPlay = o.optBoolean("gDoubleTapPlay", d.gDoubleTapPlay),
            gDoubleTapZoom = o.optBoolean("gDoubleTapZoom", d.gDoubleTapZoom),
            gDoubleTapSeek = o.optBoolean("gDoubleTapSeek", d.gDoubleTapSeek),
            gLongPressSpeed = o.optBoolean("gLongPressSpeed", d.gLongPressSpeed),
            gSpeed = o.optBoolean("gSpeed", d.gSpeed),
            gSubUpDown = o.optBoolean("gSubUpDown", d.gSubUpDown),
            gSubZoom = o.optBoolean("gSubZoom", d.gSubZoom),
            shortcuts = o.optBoolean("shortcuts", d.shortcuts),
            autoHide = o.optBoolean("autoHide", d.autoHide),
            autoHideTenths = o.optInt("autoHideTenths", d.autoHideTenths),
            showUiWhenLocked = o.optBoolean("showUiWhenLocked", d.showUiWhenLocked),
            seekSpeed = o.optInt("seekSpeed", d.seekSpeed),
            moveButtons = o.optBoolean("moveButtons", d.moveButtons),
            moveInterval = o.optInt("moveInterval", d.moveInterval),
            prevNextButtons = o.optBoolean("prevNextButtons", d.prevNextButtons),
            showSeekPosition = o.optBoolean("showSeekPosition", d.showSeekPosition),
            subFont = o.optInt("subFont", d.subFont),
            subSize = o.optInt("subSize", d.subSize),
            subScale = o.optInt("subScale", d.subScale),
            subColor = o.optInt("subColor", d.subColor),
            subBold = o.optBoolean("subBold", d.subBold),
            subBgOn = o.optBoolean("subBgOn", d.subBgOn),
            subBgColor = o.optInt("subBgColor", d.subBgColor),
            subBorderOn = o.optBoolean("subBorderOn", d.subBorderOn),
            subBorderColor = o.optInt("subBorderColor", d.subBorderColor),
            subShadow = o.optBoolean("subShadow", d.subShadow),
            subSsaStyles = o.optBoolean("subSsaStyles", d.subSsaStyles),
            subSsaSizes = o.optBoolean("subSsaSizes", d.subSsaSizes),
            subAlign = o.optInt("subAlign", d.subAlign),
            subBottom = o.optInt("subBottom", d.subBottom),
            subBoxOn = o.optBoolean("subBoxOn", d.subBoxOn),
            subBoxColor = o.optInt("subBoxColor", d.subBoxColor),
            subFitVideo = o.optBoolean("subFitVideo", d.subFitVideo),
            aspect = o.optInt("aspect", d.aspect),
            aspectW = o.optInt("aspectW", d.aspectW),
            aspectH = o.optInt("aspectH", d.aspectH),
            aspectAll = o.optBoolean("aspectAll", d.aspectAll),
            speedFF = o.optInt("speedFF", d.speedFF),
            reverb = o.optInt("reverb", d.reverb)
        )
    }
}
