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
    val presetName: String = "Flat",
    // Settings → Equalizer → Tone
    val bassFreq: Double = BASS_FREQ,
    val bassQ: Double = SHELF_Q,
    val trebleFreq: Double = TREBLE_FREQ,
    val trebleQ: Double = SHELF_Q
)

/**
 * A graphic preset has [gains] (one per band); a parametric one (AutoEq import) has its own [bands] and [preampDb].
 */
data class EqPreset(
    val name: String,
    val gains: List<Double>,
    val builtIn: Boolean = true,
    val bands: List<EqBand>? = null,
    val preampDb: Double? = null,
    val autoEq: Boolean = false
)

const val DEFAULT_Q = 1.41          // one-octave bands
const val MAX_GAIN_DB = 12.0
const val BASS_FREQ = 100.0
const val TREBLE_FREQ = 10000.0
const val SHELF_Q = 0.707

val BAND_FREQS = listOf(31.0, 62.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0)

fun defaultBands(): List<EqBand> = BAND_FREQS.map { EqBand(it) }

/** ISO 1/3-octave centres, used for the 31-band layout. */
val ISO_31 = listOf(
    20.0, 25.0, 31.5, 40.0, 50.0, 63.0, 80.0, 100.0, 125.0, 160.0, 200.0, 250.0, 315.0, 400.0, 500.0, 630.0,
    800.0, 1000.0, 1250.0, 1600.0, 2000.0, 2500.0, 3150.0, 4000.0, 5000.0, 6300.0, 8000.0, 10000.0, 12500.0, 16000.0, 20000.0
)

/** Band centres: ISO for 10 and 31 bands, otherwise log-spaced from [first] to [last]. */
fun bandFrequencies(count: Int, first: Double, last: Double): List<Double> = when {
    count == 10 && first == 31.0 && last == 16000.0 -> BAND_FREQS
    count == 31 && first == 20.0 && last == 20000.0 -> ISO_31
    count <= 1 -> listOf(1000.0)
    else -> (0 until count).map { i ->
        val f = first * (last / first).pow(i.toDouble() / (count - 1))
        // round to friendly values
        when {
            f < 100 -> Math.round(f).toDouble()
            f < 1000 -> Math.round(f / 5) * 5.0
            else -> Math.round(f / 50) * 50.0
        }
    }
}

/** Q that makes neighbouring bands of an n-band layout meet nicely. */
fun qFor(count: Int): Double {
    if (count == 10) return DEFAULT_Q
    if (count <= 1) return 1.0
    val octaves = kotlin.math.ln(16000.0 / 31.0) / kotlin.math.ln(2.0) / (count - 1)
    val b = 2.0.pow(octaves)
    return (kotlin.math.sqrt(b) / (b - 1)).coerceIn(0.4, 6.0)
}

/** Gain at [f] read from a curve given at [freqs] (log-frequency interpolation). */
fun interpolateGain(freqs: List<Double>, gains: List<Double>, f: Double): Double {
    if (freqs.isEmpty()) return 0.0
    if (f <= freqs.first()) return gains.first()
    if (f >= freqs.last()) return gains.last()
    for (i in 0 until freqs.size - 1) {
        val a = freqs[i]
        val b = freqs[i + 1]
        if (f in a..b) {
            val t = kotlin.math.ln(f / a) / kotlin.math.ln(b / a)
            return gains[i] + (gains[i + 1] - gains[i]) * t
        }
    }
    return 0.0
}

/**
 * Reads an AutoEq "ParametricEQ.txt" file:
 *   Preamp: -6.4 dB
 *   Filter 1: ON LSC Fc 105 Hz Gain 5.5 dB Q 0.70
 *   Filter 2: ON PK Fc 2400 Hz Gain -3.2 dB Q 1.41
 */
fun parseAutoEq(name: String, text: String): EqPreset? {
    var preamp = 0.0
    val bands = ArrayList<EqBand>()
    val pre = Regex("""(?i)preamp:\s*(-?[\d.]+)\s*db""")
    val filt = Regex("""(?i)filter\s*\d*:\s*on\s+(PK|PEQ|LSC|LS|HSC|HS|LSQ|HSQ)\s+fc\s+([\d.]+)\s*hz(?:\s+gain\s+(-?[\d.]+)\s*db)?(?:\s+q\s+([\d.]+))?""")
    text.lineSequence().forEach { line ->
        pre.find(line)?.let { preamp = it.groupValues[1].toDoubleOrNull() ?: 0.0 }
        filt.find(line)?.let { m ->
            val type = when (m.groupValues[1].uppercase()) {
                "LSC", "LS", "LSQ" -> BandType.LOW_SHELF
                "HSC", "HS", "HSQ" -> BandType.HIGH_SHELF
                else -> BandType.PEAK
            }
            val fc = m.groupValues[2].toDoubleOrNull() ?: return@let
            val gain = m.groupValues[3].toDoubleOrNull() ?: 0.0
            val q = m.groupValues[4].toDoubleOrNull() ?: if (type == BandType.PEAK) DEFAULT_Q else SHELF_Q
            bands.add(EqBand(fc.coerceIn(10.0, 22000.0), gain.coerceIn(-MAX_GAIN_DB * 2, MAX_GAIN_DB * 2), q.coerceIn(0.1, 20.0), type))
        }
    }
    if (bands.isEmpty()) return null
    return EqPreset(name, emptyList(), builtIn = false, bands = bands.sortedBy { it.freq }, preampDb = preamp, autoEq = true)
}

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
    put("bassF", bassFreq); put("bassQ", bassQ); put("trebleF", trebleFreq); put("trebleQ", trebleQ)
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
        presetName = o.optString("preset", "Custom"),
        bassFreq = o.optDouble("bassF", BASS_FREQ),
        bassQ = o.optDouble("bassQ", SHELF_Q),
        trebleFreq = o.optDouble("trebleF", TREBLE_FREQ),
        trebleQ = o.optDouble("trebleQ", SHELF_Q)
    )
}

fun EqPreset.toJson(): JSONObject = JSONObject().apply {
    put("name", name)
    put("gains", JSONArray().apply { gains.forEach { put(it) } })
    bands?.let { list ->
        put("bands", JSONArray().apply {
            list.forEach { b -> put(JSONObject().apply { put("f", b.freq); put("g", b.gainDb); put("q", b.q); put("t", b.type.name) }) }
        })
    }
    preampDb?.let { put("preamp", it) }
    if (autoEq) put("autoEq", true)
}

fun eqPresetFromJson(o: JSONObject): EqPreset {
    val arr = o.optJSONArray("gains") ?: JSONArray()
    val b = o.optJSONArray("bands")
    return EqPreset(
        name = o.optString("name", "Preset"),
        gains = (0 until arr.length()).map { arr.optDouble(it, 0.0) },
        builtIn = false,
        bands = b?.let { list ->
            (0 until list.length()).map { i ->
                val x = list.getJSONObject(i)
                EqBand(
                    x.optDouble("f", 1000.0), x.optDouble("g", 0.0), x.optDouble("q", DEFAULT_Q),
                    runCatching { BandType.valueOf(x.optString("t", "PEAK")) }.getOrDefault(BandType.PEAK)
                )
            }
        },
        preampDb = if (o.has("preamp")) o.optDouble("preamp", 0.0) else null,
        autoEq = o.optBoolean("autoEq", false)
    )
}
