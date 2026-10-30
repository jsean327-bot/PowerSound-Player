package com.powersound.player.audio

/**
 * Reduction to 16-bit with the dither chosen in Settings → Audio → Resampler → Dither.
 * Only used when an output is set to 16-bit (Audio Outputs → output → Output Format);
 * the default 32-bit float output needs no dither.
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
