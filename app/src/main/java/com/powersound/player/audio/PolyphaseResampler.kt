package com.powersound.player.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Sample-rate converter (Settings → Audio → Resampler), 64-bit, Kaiser-windowed sinc.
 *
 * For the usual music rates (44.1 ↔ 48 ↔ 88.2 ↔ 96 ↔ 176.4 ↔ 192 kHz) the ratio is an exact fraction, so every
 * output sample gets its own exact filter phase: no interpolation error at all.
 *
 * The band up to [cutoff] × the lower Nyquist frequency (e.g. 21.4 kHz for 44.1 kHz at 0.97) stays flat.
 * Anything that could fold back (alias / image) is pushed above that band and attenuated by the stop band:
 * 130 dB for "High quality", 160 dB for "Very high quality" — far below what any DAC can reproduce.
 *
 * Frames are interleaved. Feed any number of input frames with [process]; read the output frames it returns.
 */
class PolyphaseResampler(
    val inRate: Int,
    val outRate: Int,
    val channels: Int,
    val quality: Int,
    val cutoff: Double
) {
    private val up: Int            // output samples per [down] input samples
    private val down: Int
    private val exact: Boolean
    private val phases: Int
    /** Filter length in input samples (even). */
    val taps: Int
    private val half: Int
    private val table: DoubleArray

    // input history, interleaved; [center] is the input index of the next output point
    private var hist = DoubleArray(8192 * channels)
    private var histFrames = 0
    private var center = 0
    private var phase = 0          // exact mode: next output is at center + phase / up
    private var frac = 0.0         // other ratios: next output is at center + frac
    private val step = inRate.toDouble() / outRate

    /** How many input frames the filter looks ahead (its delay). */
    val latencyFrames: Int get() = half

    init {
        val g = gcd(inRate, outRate)
        up = outRate / g
        down = inRate / g
        exact = up <= 4096
        val stopDb = if (quality >= 1) 160.0 else 130.0
        val fLow = min(inRate, outRate).toDouble()
        val fPass = cutoff.coerceIn(0.80, 0.995) * fLow / 2
        val fStop = fLow - fPass                    // images / aliases of the pass band land above fPass
        val nu = (fPass + fStop) / 2 / inRate       // cutoff in cycles per input sample (= lower Nyquist)
        val transition = (fStop - fPass) / inRate   // in cycles per input sample
        var n = ((stopDb - 7.95) / (14.36 * transition)).toInt() + 1
        n = ((n + 3) / 4 * 4).coerceIn(16, 2048)
        taps = n
        half = n / 2
        val beta = 0.1102 * (stopDb - 8.7)
        phases = if (exact) up else 1024
        val rows = if (exact) phases else phases + 1
        table = DoubleArray(rows * taps)
        val i0b = besselI0(beta)
        for (p in 0 until rows) {
            val phi = p.toDouble() / phases
            var sum = 0.0
            for (k in 0 until taps) {
                // input sample k sits tau input samples before the output point
                val tau = phi + (half - 1) - k
                val w = tau / half
                val win = if (abs(w) >= 1.0) 0.0 else besselI0(beta * sqrt(1.0 - w * w)) / i0b
                val v = 2.0 * nu * sinc(2.0 * nu * tau) * win
                table[p * taps + k] = v
                sum += v
            }
            // every phase passes DC with a gain of exactly 1
            if (sum != 0.0) for (k in 0 until taps) table[p * taps + k] /= sum
        }
        reset()
    }

    /** Clears the history (after a seek). */
    fun reset() {
        java.util.Arrays.fill(hist, 0.0)
        histFrames = half - 1          // silence before the first sample, so output 0 is centred on input 0
        center = half - 1
        phase = 0
        frac = 0.0
    }

    /** Upper bound of the output frames for [frames] more input frames. */
    fun maxOutputFrames(frames: Int): Int =
        ((histFrames + frames).toLong() * up / down + 4).toInt()

    /**
     * Adds [frames] interleaved input frames from [input] and writes the output frames into [out]
     * (size at least [maxOutputFrames] × channels). Returns the number of output frames.
     */
    fun process(input: DoubleArray, frames: Int, out: DoubleArray): Int {
        val need = (histFrames + frames) * channels
        if (need > hist.size) hist = hist.copyOf(maxOf(need, hist.size * 2))
        System.arraycopy(input, 0, hist, histFrames * channels, frames * channels)
        histFrames += frames

        val produced = if (exact) runExact(out) else runInterpolated(out)

        // drop the input that no output needs any more
        val keepFrom = center - (half - 1)
        if (keepFrom > 0) {
            val remain = histFrames - keepFrom
            System.arraycopy(hist, keepFrom * channels, hist, 0, remain * channels)
            histFrames = remain
            center -= keepFrom
        }
        return produced
    }

    private fun runExact(out: DoubleArray): Int {
        val h = hist
        val t = table
        val ch = channels
        var produced = 0
        while (center + half < histFrames) {
            val base = phase * taps
            val first = center - (half - 1)
            if (ch == 2) {
                // two independent sums per channel (taps is a multiple of 4)
                var a0 = 0.0
                var a1 = 0.0
                var b0 = 0.0
                var b1 = 0.0
                var idx = first * 2
                var k = 0
                while (k < taps) {
                    val c0 = t[base + k]
                    val c1 = t[base + k + 1]
                    a0 += h[idx] * c0
                    a1 += h[idx + 1] * c0
                    b0 += h[idx + 2] * c1
                    b1 += h[idx + 3] * c1
                    idx += 4
                    k += 2
                }
                out[produced * 2] = a0 + b0
                out[produced * 2 + 1] = a1 + b1
            } else {
                for (c0 in 0 until ch) {
                    var a = 0.0
                    var idx = first * ch + c0
                    for (k in 0 until taps) {
                        a += h[idx] * t[base + k]
                        idx += ch
                    }
                    out[produced * ch + c0] = a
                }
            }
            produced++
            phase += down
            center += phase / up
            phase %= up
        }
        return produced
    }

    private fun runInterpolated(out: DoubleArray): Int {
        val h = hist
        val t = table
        val ch = channels
        var produced = 0
        while (center + half < histFrames) {
            val pf = frac * phases
            val p0 = pf.toInt().coerceAtMost(phases - 1)
            val a = pf - p0
            val base0 = p0 * taps
            val base1 = base0 + taps
            val first = center - (half - 1)
            for (c0 in 0 until ch) {
                var acc0 = 0.0
                var acc1 = 0.0
                var idx = first * ch + c0
                for (k in 0 until taps) {
                    val s = h[idx]
                    acc0 += s * t[base0 + k]
                    acc1 += s * t[base1 + k]
                    idx += ch
                }
                out[produced * ch + c0] = acc0 + (acc1 - acc0) * a
            }
            produced++
            frac += step
            val whole = frac.toInt()
            center += whole
            frac -= whole
        }
        return produced
    }

    /** Pushes silence through so the last input samples come out (end of the track). */
    fun drain(out: DoubleArray): Int {
        val zeros = DoubleArray(half * channels)
        return process(zeros, half, out)
    }

    companion object {
        fun gcd(a: Int, b: Int): Int {
            var x = a
            var y = b
            while (y != 0) {
                val r = x % y
                x = y
                y = r
            }
            return if (x == 0) 1 else x
        }

        private fun sinc(x: Double): Double = if (abs(x) < 1e-12) 1.0 else sin(PI * x) / (PI * x)

        private fun besselI0(x: Double): Double {
            var sum = 1.0
            var term = 1.0
            val q = x * x / 4.0
            var k = 1
            while (k < 200) {
                term *= q / (k.toDouble() * k)
                sum += term
                if (term < sum * 1e-17) break
                k++
            }
            return sum
        }
    }
}
