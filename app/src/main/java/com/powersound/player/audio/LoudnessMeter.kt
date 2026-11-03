package com.powersound.player.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.tan

/**
 * Loudness of a whole song as people hear it (EBU R128 / ITU-R BS.1770 integrated loudness, in LUFS),
 * plus its highest sample. Used by Settings → Audio → Replay Gain → Scan Loudness for songs without
 * Replay Gain tags: gain = −18 LUFS − loudness (the Replay Gain 2.0 level).
 *
 * K-weighting (a high shelf + a low cut), 400 ms blocks every 100 ms, a −70 LUFS gate and a gate 10 LU
 * under the average. No Android parts, so it is checked against ffmpeg on a computer.
 */
class LoudnessMeter(rate: Int, private val channels: Int) {

    // ---- K-weighting filter coefficients for this sample rate (same design as libebur128) ----
    private val b = DoubleArray(5)
    private val a = DoubleArray(5)

    init {
        var f0 = 1681.974450955533
        val g = 3.999843853973347
        var q = 0.7071752369554196
        var k = tan(PI * f0 / rate)
        val vh = 10.0.pow(g / 20.0)
        val vb = vh.pow(0.4996667741545416)
        val pb = DoubleArray(3)
        val pa = DoubleArray(3)
        var a0 = 1.0 + k / q + k * k
        pb[0] = (vh + vb * k / q + k * k) / a0
        pb[1] = 2.0 * (k * k - vh) / a0
        pb[2] = (vh - vb * k / q + k * k) / a0
        pa[0] = 1.0
        pa[1] = 2.0 * (k * k - 1.0) / a0
        pa[2] = (1.0 - k / q + k * k) / a0

        f0 = 38.13547087602444
        q = 0.5003270373238773
        k = tan(PI * f0 / rate)
        val rb = doubleArrayOf(1.0, -2.0, 1.0)
        a0 = 1.0 + k / q + k * k
        val ra = doubleArrayOf(1.0, 2.0 * (k * k - 1.0) / a0, (1.0 - k / q + k * k) / a0)

        // the two filters as one 4th-order filter
        b[0] = pb[0] * rb[0]
        b[1] = pb[0] * rb[1] + pb[1] * rb[0]
        b[2] = pb[0] * rb[2] + pb[1] * rb[1] + pb[2] * rb[0]
        b[3] = pb[1] * rb[2] + pb[2] * rb[1]
        b[4] = pb[2] * rb[2]
        a[0] = pa[0] * ra[0]
        a[1] = pa[0] * ra[1] + pa[1] * ra[0]
        a[2] = pa[0] * ra[2] + pa[1] * ra[1] + pa[2] * ra[0]
        a[3] = pa[1] * ra[2] + pa[2] * ra[1]
        a[4] = pa[2] * ra[2]
    }

    private val state = Array(channels) { DoubleArray(5) }   // direct form II
    private val weight = DoubleArray(channels) { c -> if (channels > 4 && (c == 3 || c == 4)) 1.41 else 1.0 }
    private val step = (rate / 10).coerceAtLeast(1)        // 100 ms
    private var inStep = 0
    private var stepSum = 0.0
    private val steps = ArrayList<Double>()                  // mean square of each 100 ms step (all channels)

    var peak = 0.0
        private set

    /** Interleaved samples, ±1.0 full scale. */
    fun add(samples: FloatArray, frames: Int) {
        var i = 0
        for (f in 0 until frames) {
            var sum = 0.0
            for (c in 0 until channels) {
                val x = samples[i++].toDouble()
                val ax = abs(x)
                if (ax > peak) peak = ax
                if (c >= 6 || (channels == 6 && c == 3)) continue // the LFE channel is not counted
                val s = state[c]
                val w = x - a[1] * s[1] - a[2] * s[2] - a[3] * s[3] - a[4] * s[4]
                val y = b[0] * w + b[1] * s[1] + b[2] * s[2] + b[3] * s[3] + b[4] * s[4]
                s[4] = s[3]; s[3] = s[2]; s[2] = s[1]; s[1] = w
                sum += weight[c] * y * y
            }
            stepSum += sum
            if (++inStep == step) {
                steps.add(stepSum / step)
                stepSum = 0.0
                inStep = 0
            }
        }
    }

    /** Integrated loudness in LUFS, or null when the song is (almost) silent or too short. */
    fun integrated(): Double? {
        if (steps.size < 4) return null
        val blocks = DoubleArray(steps.size - 3) { j -> (steps[j] + steps[j + 1] + steps[j + 2] + steps[j + 3]) / 4.0 }
        fun lufs(z: Double) = -0.691 + 10.0 * log10(z)
        val absGate = blocks.filter { it > 0 && lufs(it) > -70.0 }
        if (absGate.isEmpty()) return null
        val relGate = lufs(absGate.average()) - 10.0
        val kept = absGate.filter { lufs(it) > relGate }
        if (kept.isEmpty()) return null
        return lufs(kept.average())
    }
}
