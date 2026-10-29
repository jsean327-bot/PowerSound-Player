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

    // ---------------------------------------------------------------- Settings → Audio (set by the playback service)

    /** "Perfect Bit Perfect": no DSP, no resampling, no fades. */
    @Volatile var bitPerfect = false
        private set
    /** "No Equ/Tone" for the current output. */
    @Volatile var eqBlocked = false
        private set
    @Volatile var fadeOnSeek = true
        private set
    @Volatile var ditherType = 2
        private set
    @Volatile var resamplerVeryHigh = false
        private set
    @Volatile var resamplerCutoff = 0.97
        private set
    /** Output sample rate rule of the current output: 0 follow track, 1 the phone's native rate, else Hz. */
    @Volatile var outputRateRule = 0
        private set
    /** The phone's native output rate (usually 48000). */
    @Volatile var deviceRate = 48000
    /** Volume reduction while another app speaks (Audio Focus → Duck Volume). */
    @Volatile var duckGain = 1.0

    // what the processor is doing now (Audio Info)
    @Volatile var inputRate = 0
        private set
    @Volatile var outputRate = 0
        private set
    @Volatile var channelCount = 0
        private set
    @Volatile var decoderName = ""
    @Volatile var audioSessionId = 0
    @Volatile var currentOutput = "Speaker"
    @Volatile var outputKey = "speaker"
    /** Settings → Audio Outputs → Visualization/Lyrics Delay of the current output. */
    @Volatile var outputDelayMs = 0

    fun outputRateFor(trackRate: Int): Int = when (val r = outputRateRule) {
        0 -> trackRate
        1 -> deviceRate
        else -> r
    }

    fun reportFormat(inRate: Int, outRate: Int, channels: Int) {
        inputRate = inRate
        outputRate = outRate
        channelCount = channels
    }

    /** Copies the audio options from the app settings (called on every settings / output change). */
    fun applyOptions(
        bitPerfect: Boolean,
        eqBlocked: Boolean,
        fadeOnSeek: Boolean,
        ditherType: Int,
        resamplerVeryHigh: Boolean,
        resamplerCutoff: Double,
        outputRateRule: Int
    ) {
        val changed = bitPerfect != this.bitPerfect || eqBlocked != this.eqBlocked || ditherType != this.ditherType
        this.bitPerfect = bitPerfect
        this.eqBlocked = eqBlocked
        this.fadeOnSeek = fadeOnSeek
        this.ditherType = ditherType
        this.resamplerVeryHigh = resamplerVeryHigh
        this.resamplerCutoff = resamplerCutoff
        this.outputRateRule = outputRateRule
        if (changed) version += 1
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
            val pb = preset.bands
            if (pb != null) {
                // parametric preset (e.g. AutoEq): exact bands and preamp
                s.copy(bands = pb, preampDb = preset.preampDb ?: s.preampDb, presetName = preset.name)
            } else {
                // graphic preset: its gains follow the current number of bands
                val layout = currentLayout()
                val src = if (preset.gains.size == BAND_FREQS.size) BAND_FREQS else bandFrequencies(preset.gains.size, 31.0, 16000.0)
                val bands = layout.map { f -> EqBand(freq = f, gainDb = interpolateGain(src, preset.gains, f), q = qFor(layout.size)) }
                s.copy(bands = bands, presetName = preset.name)
            }
        }
    }

    fun saveCustomPreset(name: String) {
        val clean = name.trim().ifBlank { return }
        val cur = _settings.value
        val isGraphic = cur.bands.all { it.type == BandType.PEAK } && cur.bands.map { it.freq } == currentLayout()
        val preset = if (isGraphic) EqPreset(clean, cur.bands.map { it.gainDb }, builtIn = false)
        else EqPreset(clean, emptyList(), builtIn = false, bands = cur.bands, preampDb = cur.preampDb)
        val list = _customPresets.value.filterNot { it.name == clean } + preset
        saveCustomList(list)
        update { it.copy(presetName = clean) }
    }

    /** Settings → Equalizer → Auto Save: band changes are kept in the user preset that is loaded. */
    fun autoSaveIfCustom() {
        val cur = _settings.value
        val p = _customPresets.value.firstOrNull { it.name == cur.presetName } ?: return
        val updated = if (p.bands != null) p.copy(bands = cur.bands, preampDb = cur.preampDb) else p.copy(gains = cur.bands.map { it.gainDb })
        saveCustomList(_customPresets.value.map { if (it.name == p.name) updated else it })
    }

    private fun saveCustomList(list: List<EqPreset>) {
        _customPresets.value = list
        prefs?.edit()?.putString(KEY_PRESETS, JSONArray().apply { list.forEach { put(it.toJson()) } }.toString())?.apply()
    }

    /** Adds presets imported from AutoEq "ParametricEQ.txt" files. Returns how many were added. */
    fun importAutoEq(presets: List<EqPreset>): Int {
        if (presets.isEmpty()) return 0
        val names = presets.map { it.name }.toSet()
        saveCustomList(_customPresets.value.filterNot { it.name in names } + presets)
        return presets.size
    }

    /** Settings → Equalizer → Restore Equalizer Presets: removes the imported AutoEq presets. */
    fun removeAutoEqPresets() = saveCustomList(_customPresets.value.filterNot { it.autoEq })

    /** Settings → Advanced Tweaks → Restore Reverb Presets: removes the user's reverb presets. */
    fun removeCustomReverbs() {
        _customReverbs.value = emptyList()
        prefs?.edit()?.remove(KEY_REVERBS)?.apply()
    }

    // ---------------------------------------------------------------- band layout (Settings → Equalizer → Graphic Equalizer Bands)

    @Volatile var layoutCount = 10
        private set
    @Volatile var layoutFirst = 31.0
        private set
    @Volatile var layoutLast = 16000.0
        private set

    fun currentLayout(): List<Double> = bandFrequencies(layoutCount, layoutFirst, layoutLast)

    /** Changes the number of graphic bands; the current curve is kept as well as possible. */
    fun setLayout(count: Int, first: Double, last: Double) {
        val changed = count != layoutCount || first != layoutFirst || last != layoutLast
        layoutCount = count; layoutFirst = first; layoutLast = last
        if (!changed) return
        update { s ->
            val freqs = currentLayout()
            val oldF = s.bands.map { it.freq }
            val oldG = s.bands.map { it.gainDb }
            s.copy(bands = freqs.map { f -> EqBand(f, interpolateGain(oldF, oldG, f), qFor(freqs.size)) })
        }
    }

    /** Loads the layout at start without touching the bands. */
    fun initLayout(count: Int, first: Double, last: Double) {
        layoutCount = count; layoutFirst = first; layoutLast = last
    }

    fun deleteCustomPreset(name: String) = saveCustomList(_customPresets.value.filterNot { it.name == name })

    fun resetAll() {
        update { s ->
            val freqs = currentLayout()
            EqSettings(enabled = s.enabled, bands = freqs.map { EqBand(it, 0.0, qFor(freqs.size)) }, autoHeadroom = s.autoHeadroom)
        }
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
            list.add(Biquad.design(BandType.LOW_SHELF, fs, s.bassFreq, s.bassDb, s.bassQ))
        }
        for (b in s.bands) {
            if (kotlin.math.abs(b.gainDb) > 0.01) list.add(Biquad.design(b.type, fs, b.freq, b.gainDb, b.q))
        }
        if (s.toneEnabled && kotlin.math.abs(s.trebleDb) > 0.01) {
            list.add(Biquad.design(BandType.HIGH_SHELF, fs, s.trebleFreq.coerceAtMost(fs * 0.45), s.trebleDb, s.trebleQ))
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
