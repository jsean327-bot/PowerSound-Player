package com.powersound.player.audio

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.pow

/**
 * The single source of truth for the sound settings.
 * The Equalizer screen writes here, the audio processor reads [snapshot].
 */
object AudioEngine {

    private const val PREFS = "powersound_audio"
    private const val KEY_SETTINGS = "settings"
    private const val KEY_PRESETS = "custom_presets"
    private const val KEY_FX = "fx"
    private const val KEY_REVERBS = "custom_reverbs"
    private const val PREVIEW_FS = 48000.0

    private var prefs: SharedPreferences? = null

    private val _settings = MutableStateFlow(EqSettings())
    val settings: StateFlow<EqSettings> = _settings.asStateFlow()

    private val _customPresets = MutableStateFlow<List<EqPreset>>(emptyList())
    val customPresets: StateFlow<List<EqPreset>> = _customPresets.asStateFlow()

    private val _fx = MutableStateFlow(FxSettings())
    val fx: StateFlow<FxSettings> = _fx.asStateFlow()

    private val _customReverbs = MutableStateFlow<List<ReverbPreset>>(emptyList())
    val customReverbs: StateFlow<List<ReverbPreset>> = _customReverbs.asStateFlow()

    /** Effects + reverb settings read by the audio thread. */
    @Volatile
    var fxSnapshot: FxSettings = FxSettings()
        private set

    /** Read by the audio thread. */
    @Volatile
    var snapshot: EqSettings = EqSettings()
        private set

    /** Bumped on every change so the processor knows to rebuild its filters. */
    @Volatile
    var version: Int = 0
        private set

    /** ReplayGain of the song now playing (dB, already including the preamp). 0 = off. */
    @Volatile
    var replayGainDb: Double = 0.0
        private set

    fun setReplayGain(db: Double) {
        if (kotlin.math.abs(db - replayGainDb) < 0.01) return
        replayGainDb = db
        version += 1
    }

    /** Automatic negative gain that keeps EQ boosts from clipping. */
    @Volatile
    var headroomDb: Double = 0.0
        private set

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        val loaded = p.getString(KEY_SETTINGS, null)?.let {
            runCatching { eqSettingsFromJson(JSONObject(it)) }.getOrNull()
        } ?: EqSettings()
        publish(loaded)
        _customPresets.value = p.getString(KEY_PRESETS, null)?.let { raw ->
            runCatching {
                val arr = JSONArray(raw)
                (0 until arr.length()).map { eqPresetFromJson(arr.getJSONObject(it)) }
            }.getOrNull()
        } ?: emptyList()
        val fxLoaded = p.getString(KEY_FX, null)?.let { runCatching { fxFromJson(JSONObject(it)) }.getOrNull() } ?: FxSettings()
        _fx.value = fxLoaded
        fxSnapshot = fxLoaded
        _customReverbs.value = p.getString(KEY_REVERBS, null)?.let { raw ->
            runCatching {
                val arr = JSONArray(raw)
                (0 until arr.length()).map { reverbPresetFromJson(arr.getJSONObject(it)) }
            }.getOrNull()
        } ?: emptyList()
        version += 1
    }

    // ---- effects & reverb ----

    fun updateFx(transform: (FxSettings) -> FxSettings) {
        val next = transform(_fx.value)
        _fx.value = next
        fxSnapshot = next
        version += 1
        prefs?.edit()?.putString(KEY_FX, next.toJson().toString())?.apply()
    }

    fun resetEffects() = updateFx {
        val d = FxSettings()
        it.copy(balance = d.balance, stereoExpand = d.stereoExpand, mono = d.mono,
            tempoEnabled = d.tempoEnabled, tempo = d.tempo, volume = d.volume)
    }

    fun resetReverb() = updateFx {
        val d = FxSettings()
        it.copy(damp = d.damp, filter = d.filter, fade = d.fade, preDelay = d.preDelay,
            preDelayMix = d.preDelayMix, size = d.size, mix = d.mix, reverbPreset = "Custom")
    }

    fun saveReverbPreset(name: String) {
        val clean = name.trim().ifBlank { return }
        val f = _fx.value
        val preset = ReverbPreset(clean, f.damp, f.filter, f.fade, f.preDelay, f.preDelayMix, f.size, f.mix, builtIn = false)
        val list = _customReverbs.value.filterNot { it.name == clean } + preset
        _customReverbs.value = list
        prefs?.edit()?.putString(KEY_REVERBS, JSONArray().apply { list.forEach { put(it.toJson()) } }.toString())?.apply()
        updateFx { it.copy(reverbPreset = clean) }
    }

    fun deleteReverbPreset(name: String) {
        val list = _customReverbs.value.filterNot { it.name == name }
        _customReverbs.value = list
        prefs?.edit()?.putString(KEY_REVERBS, JSONArray().apply { list.forEach { put(it.toJson()) } }.toString())?.apply()
    }

    fun update(transform: (EqSettings) -> EqSettings) {
        val next = transform(_settings.value)
        publish(next)
        prefs?.edit()?.putString(KEY_SETTINGS, next.toJson().toString())?.apply()
    }

    fun applyPreset(preset: EqPreset) {
        update { s ->
            val bands = BAND_FREQS.mapIndexed { i, f ->
                EqBand(freq = f, gainDb = preset.gains.getOrElse(i) { 0.0 })
            }
            s.copy(bands = bands, presetName = preset.name)
        }
    }

    fun saveCustomPreset(name: String) {
        val clean = name.trim().ifBlank { return }
        val gains = _settings.value.bands.map { it.gainDb }
        val list = _customPresets.value.filterNot { it.name == clean } + EqPreset(clean, gains, builtIn = false)
        _customPresets.value = list
        prefs?.edit()?.putString(KEY_PRESETS, JSONArray().apply { list.forEach { put(it.toJson()) } }.toString())?.apply()
        update { it.copy(presetName = clean) }
    }

    fun deleteCustomPreset(name: String) {
        val list = _customPresets.value.filterNot { it.name == name }
        _customPresets.value = list
        prefs?.edit()?.putString(KEY_PRESETS, JSONArray().apply { list.forEach { put(it.toJson()) } }.toString())?.apply()
    }

    fun resetAll() {
        update { EqSettings(enabled = it.enabled) }
    }

    private fun publish(s: EqSettings) {
        _settings.value = s
        headroomDb = if (s.autoHeadroom) -max(0.0, maxBoostDb(s)) else 0.0
        snapshot = s
        version += 1
    }

    // ---- curve helpers (also used by the screen to draw the graph) ----

    fun filtersFor(s: EqSettings, fs: Double): List<BiquadCoeffs> {
        val list = ArrayList<BiquadCoeffs>()
        if (s.toneEnabled && kotlin.math.abs(s.bassDb) > 0.01) {
            list.add(Biquad.design(BandType.LOW_SHELF, fs, BASS_FREQ, s.bassDb, SHELF_Q))
        }
        for (b in s.bands) {
            if (kotlin.math.abs(b.gainDb) > 0.01) list.add(Biquad.design(b.type, fs, b.freq, b.gainDb, b.q))
        }
        if (s.toneEnabled && kotlin.math.abs(s.trebleDb) > 0.01) {
            list.add(Biquad.design(BandType.HIGH_SHELF, fs, TREBLE_FREQ, s.trebleDb, SHELF_Q))
        }
        return list
    }

    /** Frequencies (log spaced, 20 Hz to 20 kHz) used for the graph. */
    fun curveFrequencies(points: Int = 120): List<Double> =
        (0 until points).map { i -> 20.0 * 1000.0.pow(i.toDouble() / (points - 1)) }

    /** Total EQ response in dB at each frequency (without preamp). */
    fun curve(s: EqSettings, freqs: List<Double> = curveFrequencies()): List<Double> {
        val filters = filtersFor(s, PREVIEW_FS)
        return freqs.map { f -> filters.sumOf { Biquad.responseDb(it, f, PREVIEW_FS) } }
    }

    private fun maxBoostDb(s: EqSettings): Double = curve(s).maxOrNull() ?: 0.0
}
