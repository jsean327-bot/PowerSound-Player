package com.powersound.player.audio

import org.json.JSONObject

/** Effects page (balance, stereo, tempo, mono, volume) and Reverb page settings. */
data class FxSettings(
    // ---- effects ----
    val balance: Double = 0.0,          // -1 (left) .. +1 (right)
    val stereoExpand: Double = 0.0,     // 0 .. 1  (0 = untouched)
    val mono: Boolean = false,
    val tempoEnabled: Boolean = false,
    val tempo: Double = 1.0,            // 0.5x .. 2.0x, pitch kept
    val volume: Double = 1.0,           // 0 .. 1  (1 = full, untouched)
    // ---- 3D Surround: virtual speakers around and above you, in a studio ----
    val spatialEnabled: Boolean = false,
    val spatialAmount: Double = 0.6,    // 0 .. 1  how much comes from around and above
    val spatialRoom: Double = 0.35,     // 0 .. 1  studio reverb
    // ---- reverb (all 0 .. 1) ----
    val reverbEnabled: Boolean = false,
    val damp: Double = 0.5,
    val filter: Double = 0.0,
    val fade: Double = 0.5,
    val preDelay: Double = 0.0,
    val preDelayMix: Double = 0.0,
    val size: Double = 0.5,
    val mix: Double = 0.3,
    val reverbPreset: String = "Custom"
) {
    /** True when any effect changes the sound (otherwise audio can pass through untouched). */
    val active: Boolean
        get() = kotlin.math.abs(balance) > 0.001 || stereoExpand > 0.001 || mono ||
            volume < 0.999 || reverbEnabled || spatialEnabled

    val preDelayMs: Double get() = preDelay * 200.0
}

data class ReverbPreset(
    val name: String,
    val damp: Double,
    val filter: Double,
    val fade: Double,
    val preDelay: Double,
    val preDelayMix: Double,
    val size: Double,
    val mix: Double,
    val builtIn: Boolean = true
)

const val TEMPO_MIN = 0.5
const val TEMPO_MAX = 2.0

val BUILT_IN_REVERBS = listOf(
    ReverbPreset("Studio", 0.70, 0.30, 0.25, 0.02, 0.00, 0.20, 0.15),
    ReverbPreset("Small Room", 0.60, 0.20, 0.35, 0.05, 0.00, 0.25, 0.25),
    ReverbPreset("Medium Room", 0.50, 0.15, 0.50, 0.10, 0.00, 0.45, 0.30),
    ReverbPreset("Plate", 0.10, 0.00, 0.60, 0.00, 0.00, 0.40, 0.30),
    ReverbPreset("Large Hall", 0.35, 0.10, 0.75, 0.20, 0.10, 0.80, 0.35),
    ReverbPreset("Church", 0.25, 0.05, 0.90, 0.30, 0.15, 1.00, 0.40),
    ReverbPreset("Stadium", 0.40, 0.30, 0.85, 0.60, 0.30, 0.95, 0.35)
)

fun FxSettings.withReverb(p: ReverbPreset) = copy(
    damp = p.damp, filter = p.filter, fade = p.fade, preDelay = p.preDelay,
    preDelayMix = p.preDelayMix, size = p.size, mix = p.mix, reverbPreset = p.name, reverbEnabled = true
)

fun FxSettings.toJson(): JSONObject = JSONObject().apply {
    put("balance", balance); put("expand", stereoExpand); put("mono", mono)
    put("tempoOn", tempoEnabled); put("tempo", tempo); put("volume", volume)
    put("s3d", spatialEnabled); put("s3dAmt", spatialAmount); put("s3dRoom", spatialRoom)
    put("revOn", reverbEnabled); put("damp", damp); put("filter", filter); put("fade", fade)
    put("pre", preDelay); put("preMix", preDelayMix); put("size", size); put("mix", mix)
    put("revPreset", reverbPreset)
}

fun fxFromJson(o: JSONObject): FxSettings {
    val d = FxSettings()
    return FxSettings(
        balance = o.optDouble("balance", d.balance),
        stereoExpand = o.optDouble("expand", d.stereoExpand),
        mono = o.optBoolean("mono", d.mono),
        tempoEnabled = o.optBoolean("tempoOn", d.tempoEnabled),
        tempo = o.optDouble("tempo", d.tempo),
        volume = o.optDouble("volume", d.volume),
        spatialEnabled = o.optBoolean("s3d", d.spatialEnabled),
        spatialAmount = o.optDouble("s3dAmt", d.spatialAmount),
        spatialRoom = o.optDouble("s3dRoom", d.spatialRoom),
        reverbEnabled = o.optBoolean("revOn", d.reverbEnabled),
        damp = o.optDouble("damp", d.damp),
        filter = o.optDouble("filter", d.filter),
        fade = o.optDouble("fade", d.fade),
        preDelay = o.optDouble("pre", d.preDelay),
        preDelayMix = o.optDouble("preMix", d.preDelayMix),
        size = o.optDouble("size", d.size),
        mix = o.optDouble("mix", d.mix),
        reverbPreset = o.optString("revPreset", d.reverbPreset)
    )
}

fun ReverbPreset.toJson(): JSONObject = JSONObject().apply {
    put("name", name); put("damp", damp); put("filter", filter); put("fade", fade)
    put("pre", preDelay); put("preMix", preDelayMix); put("size", size); put("mix", mix)
}

fun reverbPresetFromJson(o: JSONObject) = ReverbPreset(
    name = o.optString("name", "Preset"),
    damp = o.optDouble("damp", 0.5), filter = o.optDouble("filter", 0.0), fade = o.optDouble("fade", 0.5),
    preDelay = o.optDouble("pre", 0.0), preDelayMix = o.optDouble("preMix", 0.0),
    size = o.optDouble("size", 0.5), mix = o.optDouble("mix", 0.3), builtIn = false
)
