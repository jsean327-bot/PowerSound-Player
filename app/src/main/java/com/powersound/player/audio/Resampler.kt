package com.powersound.player.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Band-limited (windowed-sinc, Kaiser window) sample-rate converter, 64-bit.
 * Settings → Audio → Resampler: "SW – high quality" uses 32 taps, "Very high quality" 96 taps.
 * [cutoff] is the pass band as a share of the lower Nyquist frequency (0.90–0.99).
 *
 * Frames are interleaved; call [process] with any number of input frames, then read the output frames.
 */
class Resampler(
    val inRate: Int,
    val outRate: Int,
    val channels: Int,
    veryHigh: Boolean,
    cutoff: Double
) {
    private val halfTaps = if (veryHigh) 48 else 16
    private val phases = if (veryHigh) 512 else 256
    private val step = inRate.toDouble() / outRate          // input samples per output sample
    private val table: DoubleArray                           // (phases + 1) * (2 * halfTaps)

    // input history, interleaved
    private var hist = DoubleArray(4096 * channels)
    private var histFrames = 0
    private var t = 0.0                                       // position of the next output frame in [hist]

    init {
        val fc = cutoff.coerceIn(0.80, 0.995) * min(1.0, outRate.toDouble() / inRate) // of input Nyquist
        val beta = if (veryHigh) 10.0 else 8.0
        val taps = 2 * halfTaps
        table = DoubleArray((phases + 1) * taps)
        val i0b = besselI0(beta)
        for (p in 0..phases) {
            val frac = p.toDouble() / phases
            var sum = 0.0
            for (k in 0 until taps) {
                // sample k sits at distance x from the output point
                val x = (k - (halfTaps - 1)) - frac
                val w = x / halfTaps
                val win = if (abs(w) >= 1.0) 0.0 else besselI0(beta * sqrt(1.0 - w * w)) / i0b
                val v = fc * sinc(fc * x) * win
                table[p * taps + k] = v
                sum += v
            }
            // normalise DC gain of every phase to exactly 1
            if (sum != 0.0) for (k in 0 until taps) table[p * taps + k] /= sum
        }
        reset()
    }

    /** Clears the history (after a seek). */
    fun reset() {
        histFrames = halfTaps - 1 // start with silence so the first output is centred on input 0
        java.util.Arrays.fill(hist, 0.0)
        t = (halfTaps - 1).toDouble()
    }

    /** Output frames produced for [frames] more input frames (upper bound). */
    fun maxOutputFrames(frames: Int): Int = ((histFrames + frames) / step).toInt() + 4

    /**
     * Adds [frames] interleaved input frames from [input] and writes the output frames into [out].
     * Returns the number of output frames written.
     */
    fun process(input: DoubleArray, frames: Int, out: DoubleArray): Int {
        // append
        val need = (histFrames + frames) * channels
        if (need > hist.size) hist = hist.copyOf(maxOf(need, hist.size * 2))
        System.arraycopy(input, 0, hist, histFrames * channels, frames * channels)
        histFrames += frames

        val taps = 2 * halfTaps
        var produced = 0
        while (true) {
            val center = floor(t).toInt()
            if (center + halfTaps >= histFrames) break
            val frac = t - center
            val pf = frac * phases
            val p0 = pf.toInt()
            val a = pf - p0
            val base0 = p0 * taps
            val base1 = base0 + taps
            val first = center - (halfTaps - 1)
            for (ch in 0 until channels) {
                var acc0 = 0.0
                var acc1 = 0.0
                var idx = first * channels + ch
                for (k in 0 until taps) {
                    val s = hist[idx]
                    acc0 += s * table[base0 + k]
                    acc1 += s * table[base1 + k]
                    idx += channels
                }
                out[produced * channels + ch] = acc0 + (acc1 - acc0) * a
            }
            produced++
            t += step
        }
        // drop what is no longer needed
        val keepFrom = (floor(t).toInt() - (halfTaps - 1)).coerceAtLeast(0)
        if (keepFrom > 0) {
            val remain = histFrames - keepFrom
            System.arraycopy(hist, keepFrom * channels, hist, 0, remain * channels)
            histFrames = remain
            t -= keepFrom
        }
        return produced
    }

    /** Pushes silence through so the end of the track is not cut (end of stream). */
    fun drain(out: DoubleArray): Int {
        val zeros = DoubleArray(halfTaps * channels)
        return process(zeros, halfTaps, out)
    }

    private fun sinc(x: Double): Double = if (abs(x) < 1e-12) 1.0 else sin(PI * x) / (PI * x)

    private fun besselI0(x: Double): Double {
        var sum = 1.0
        var term = 1.0
        val q = x * x / 4.0
        var k = 1
        while (k < 60) {
            term *= q / (k.toDouble() * k)
            sum += term
            if (term < sum * 1e-16) break
            k++
        }
        return sum
    }
}

/**
 * Reduction to 16-bit with the dither chosen in Settings → Audio → Resampler → Dither.
 * Noise shaping uses error feedback with the classic F-weighted / e-weighted filters.
 */
class Ditherer(private val channels: Int, private val type: Int) {

    companion object {
        val NAMES = listOf(
            "None (fastest)", "Rectangular (fast)", "Triangular (fast)", "Triangular with high pass (fast)",
            "F-weighted noise shaping (slow)", "Modified-e-weighted noise shaping (slow)", "Improved-e-weighted noise shaping (slow)"
        )

        private val F_WEIGHTED = doubleArrayOf(2.412, -3.370, 3.937, -4.174, 3.353, -2.205, 1.281, -0.569, 0.0847)
        private val MOD_E = doubleArrayOf(1.662, -1.263, 0.4827, -0.2913, 0.1268, -0.1124, 0.03252, -0.01265, -0.03524)
        private val IMP_E = doubleArrayOf(2.847, -4.685, 6.214, -7.184, 6.639, -5.032, 3.263, -1.632, 0.4191)
    }

    private val rng = java.util.Random(4321)
    private val shaper: DoubleArray? = when (type) {
        4 -> F_WEIGHTED
        5 -> MOD_E
        6 -> IMP_E
        else -> null
    }
    private val err = Array(channels) { DoubleArray(9) }
    private val errPos = IntArray(channels)
    private val lastRand = DoubleArray(channels)

    /** [v] is in 16-bit units (±32768). Returns the 16-bit sample. */
    fun quantize(v0: Double, ch: Int): Short {
        var v = v0
        val s = shaper
        if (s != null) {
            // subtract the filtered previous errors
            val e = err[ch]
            var p = errPos[ch]
            var fb = 0.0
            for (k in s.indices) {
                p = if (p == 0) 8 else p - 1
                fb += s[k] * e[p]
            }
            v -= fb
        }
        val d = when (type) {
            0 -> 0.0
            1 -> rng.nextDouble() - 0.5
            3 -> {
                // high-pass TPDF: difference of consecutive random values
                val r = rng.nextDouble()
                val out = r - lastRand[ch]
                lastRand[ch] = r
                out
            }
            else -> rng.nextDouble() - rng.nextDouble()
        }
        var q = Math.round(v + d).toDouble()
        if (q > 32767.0) q = 32767.0 else if (q < -32768.0) q = -32768.0
        if (s != null) {
            val e = err[ch]
            val p = errPos[ch]
            // keep the error bounded so loud clipping can't make the shaper run away
            e[p] = (q - v).coerceIn(-1.5, 1.5)
            errPos[ch] = (p + 1) % 9
        }
        return q.toInt().toShort()
    }

    fun reset() {
        for (e in err) e.fill(0.0)
        errPos.fill(0)
        lastRand.fill(0.0)
    }
}
