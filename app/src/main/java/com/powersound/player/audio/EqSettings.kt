package com.powersound.player.audio

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.pow

enum class BandType(val label: String) {
    PEAK("Peak"),
    LOW_SHELF("Low shelf"),
    HIGH_SHELF("High shelf")
}

data class EqBand(
    val freq: Double,
    val gainDb: Double = 0.0,
    val q: Double = DEFAULT_Q,
    val type: BandType = BandType.PEAK
)

data class EqSettings(
    val enabled: Boolean = true,
    val preampDb: Double = 0.0,
    val autoHeadroom: Boolean = true,
    val bassDb: Double = 0.0,
    val trebleDb: Double = 0.0,
    val toneEnabled: Boolean = true,
    val limiter: Boolean = true,
    val bands: List<EqBand> = defaultBands(),
    val presetName: String = "Flat"
)

data class EqPreset(val name: String, val gains: List<Double>, val builtIn: Boolean = true)

const val DEFAULT_Q = 1.41          // one-octave bands
const val MAX_GAIN_DB = 12.0
const val BASS_FREQ = 100.0
const val TREBLE_FREQ = 10000.0
const val SHELF_Q = 0.707

val BAND_FREQS = listOf(31.0, 62.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0)

fun defaultBands(): List<EqBand> = BAND_FREQS.map { EqBand(it) }

val BUILT_IN_PRESETS = listOf(
    EqPreset("Flat", listOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)),
    EqPreset("Bass Boost", listOf(6.0, 5.0, 4.0, 2.0, 0.5, 0.0, 0.0, 0.0, 0.0, 0.0)),
    EqPreset("Deep Bass", listOf(8.0, 6.5, 4.0, 1.0, 0.0, 0.0, 0.0, 0.0, 0.5, 1.0)),
    EqPreset("Treble Boost", listOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.5, 1.5, 3.5, 5.0, 6.0)),
    EqPreset("V-Shape", listOf(5.0, 4.0, 2.0, 0.0, -1.5, -1.5, 0.0, 2.0, 4.0, 5.0)),
    EqPreset("Vocal", listOf(-2.0, -1.5, -1.0, 1.0, 3.0, 3.5, 2.5, 1.0, 0.0, -1.0)),
    EqPreset("Rock", listOf(5.0, 4.0, 2.0, -1.0, -2.0, -1.0, 1.5, 3.0, 4.0, 4.0)),
    EqPreset("Pop", listOf(-1.0, 1.0, 3.0, 4.0, 3.0, 0.0, -1.0, -1.0, 1.0, 2.0)),
    EqPreset("Jazz", listOf(3.0, 2.0, 1.0, 2.0, -1.0, -1.0, 0.0, 1.0, 2.0, 3.0)),
    EqPreset("Classical", listOf(3.0, 2.0, 1.0, 0.0, -1.0, -1.0, 0.0, 1.0, 2.0, 3.0)),
    EqPreset("Electronic", listOf(5.0, 4.0, 1.0, 0.0, -2.0, 1.5, 1.0, 2.0, 4.0, 5.0)),
    EqPreset("Hip-Hop", listOf(5.0, 4.5, 1.5, 3.0, -1.0, -1.0, 1.0, -0.5, 2.0, 3.0)),
    EqPreset("Arabic", listOf(3.0, 2.5, 1.0, 0.0, 1.0, 2.5, 3.0, 2.0, 1.5, 1.0))
)

fun linearGain(db: Double): Double = 10.0.pow(db / 20.0)

// ---- JSON (saved in SharedPreferences) ----

fun EqSettings.toJson(): JSONObject = JSONObject().apply {
    put("enabled", enabled)
    put("preamp", preampDb)
    put("autoHeadroom", autoHeadroom)
    put("bass", bassDb)
    put("treble", trebleDb)
    put("tone", toneEnabled)
    put("limiter", limiter)
    put("preset", presetName)
    put("bands", JSONArray().apply {
        bands.forEach { b ->
            put(JSONObject().apply {
                put("f", b.freq)
                put("g", b.gainDb)
                put("q", b.q)
                put("t", b.type.name)
            })
        }
    })
}

fun eqSettingsFromJson(o: JSONObject): EqSettings {
    val arr = o.optJSONArray("bands")
    val bands = if (arr != null && arr.length() > 0) {
        (0 until arr.length()).map { i ->
            val b = arr.getJSONObject(i)
            EqBand(
                freq = b.optDouble("f", BAND_FREQS.getOrElse(i) { 1000.0 }),
                gainDb = b.optDouble("g", 0.0),
                q = b.optDouble("q", DEFAULT_Q),
                type = runCatching { BandType.valueOf(b.optString("t", "PEAK")) }.getOrDefault(BandType.PEAK)
            )
        }
    } else defaultBands()
    return EqSettings(
        enabled = o.optBoolean("enabled", true),
        preampDb = o.optDouble("preamp", 0.0),
        autoHeadroom = o.optBoolean("autoHeadroom", true),
        bassDb = o.optDouble("bass", 0.0),
        trebleDb = o.optDouble("treble", 0.0),
        toneEnabled = o.optBoolean("tone", true),
        limiter = o.optBoolean("limiter", true),
        bands = bands,
        presetName = o.optString("preset", "Custom")
    )
}

fun EqPreset.toJson(): JSONObject = JSONObject().apply {
    put("name", name)
    put("gains", JSONArray().apply { gains.forEach { put(it) } })
}

fun eqPresetFromJson(o: JSONObject): EqPreset {
    val arr = o.optJSONArray("gains") ?: JSONArray()
    return EqPreset(
        name = o.optString("name", "Preset"),
        gains = (0 until arr.length()).map { arr.optDouble(it, 0.0) },
        builtIn = false
    )
}
