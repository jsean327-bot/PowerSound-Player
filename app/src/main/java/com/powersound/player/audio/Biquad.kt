package com.powersound.player.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Normalized biquad coefficients (a0 = 1). */
class BiquadCoeffs(
    val b0: Double,
    val b1: Double,
    val b2: Double,
    val a1: Double,
    val a2: Double
)

/** Filter designs from the RBJ "Audio EQ Cookbook". */
object Biquad {

    fun design(type: BandType, fs: Double, freq: Double, gainDb: Double, q: Double): BiquadCoeffs {
        // Keep the centre frequency safely below Nyquist so the filter stays stable.
        val f0 = freq.coerceIn(10.0, fs * 0.45)
        val qq = q.coerceIn(0.1, 20.0)
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * f0 / fs
        val cw = cos(w0)
        val alpha = sin(w0) / (2.0 * qq)

        val b0: Double
        val b1: Double
        val b2: Double
        val a0: Double
        val a1: Double
        val a2: Double

        when (type) {
            BandType.PEAK -> {
                b0 = 1.0 + alpha * a
                b1 = -2.0 * cw
                b2 = 1.0 - alpha * a
                a0 = 1.0 + alpha / a
                a1 = -2.0 * cw
                a2 = 1.0 - alpha / a
            }
            BandType.LOW_SHELF -> {
                val sa = 2.0 * sqrt(a) * alpha
                b0 = a * ((a + 1) - (a - 1) * cw + sa)
                b1 = 2.0 * a * ((a - 1) - (a + 1) * cw)
                b2 = a * ((a + 1) - (a - 1) * cw - sa)
                a0 = (a + 1) + (a - 1) * cw + sa
                a1 = -2.0 * ((a - 1) + (a + 1) * cw)
                a2 = (a + 1) + (a - 1) * cw - sa
            }
            BandType.HIGH_SHELF -> {
                val sa = 2.0 * sqrt(a) * alpha
                b0 = a * ((a + 1) + (a - 1) * cw + sa)
                b1 = -2.0 * a * ((a - 1) + (a + 1) * cw)
                b2 = a * ((a + 1) + (a - 1) * cw - sa)
                a0 = (a + 1) - (a - 1) * cw + sa
                a1 = 2.0 * ((a - 1) - (a + 1) * cw)
                a2 = (a + 1) - (a - 1) * cw - sa
            }
        }
        return BiquadCoeffs(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
    }

    /** Gain in dB of one filter at frequency f. */
    fun responseDb(c: BiquadCoeffs, f: Double, fs: Double): Double {
        val w = 2.0 * PI * f / fs
        val c1 = cos(w)
        val s1 = sin(w)
        val c2 = cos(2 * w)
        val s2 = sin(2 * w)
        // H(z) with z^-1 = e^{-jw}
        val numRe = c.b0 + c.b1 * c1 + c.b2 * c2
        val numIm = -(c.b1 * s1 + c.b2 * s2)
        val denRe = 1.0 + c.a1 * c1 + c.a2 * c2
        val denIm = -(c.a1 * s1 + c.a2 * s2)
        val num = numRe * numRe + numIm * numIm
        val den = denRe * denRe + denIm * denIm
        if (num <= 0.0 || den <= 0.0) return 0.0
        return 10.0 * log10(num / den)
    }
}
