package com.powersound.player.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The audio processor copies what is being played into this ring buffer while a
 * visualization is on screen; the visualization reads it (no microphone permission needed).
 */
object VisualizerBus {

    private const val SIZE = 4096
    private val ring = FloatArray(SIZE)

    @Volatile
    private var writePos = 0

    /** Set by the screen while a visualization is visible, so nothing is copied otherwise. */
    @Volatile
    var active = false

    fun write(sample: Float) {
        val p = writePos
        ring[p] = sample
        writePos = (p + 1) and (SIZE - 1)
    }

    /** Latest [n] samples (oldest first). */
    fun latest(n: Int, out: FloatArray) {
        val end = writePos
        for (i in 0 until n) {
            out[i] = ring[(end - n + i + SIZE) and (SIZE - 1)]
        }
    }

    /**
     * Magnitude spectrum of the latest 1024 samples grouped into [bands] log-spaced bands,
     * scaled 0..1.
     */
    fun spectrum(bands: Int, out: FloatArray, work: FloatArray, re: FloatArray, im: FloatArray) {
        val n = 1024
        latest(n, work)
        for (i in 0 until n) {
            val w = 0.5f - 0.5f * cos(2.0 * PI * i / (n - 1)).toFloat() // Hann window
            re[i] = work[i] * w
            im[i] = 0f
        }
        fft(re, im, n)
        val half = n / 2
        val minBin = 2.0
        val maxBin = half.toDouble()
        for (b in 0 until bands) {
            val lo = (minBin * Math.pow(maxBin / minBin, b.toDouble() / bands)).toInt()
            val hi = (minBin * Math.pow(maxBin / minBin, (b + 1).toDouble() / bands)).toInt().coerceAtLeast(lo + 1)
            var sum = 0f
            for (k in lo until hi.coerceAtMost(half)) {
                sum = maxOf(sum, sqrt(re[k] * re[k] + im[k] * im[k]))
            }
            // dB-like scale: 0 at very quiet, 1 near full scale
            val db = 20 * ln((sum / (n / 4f)).coerceAtLeast(1e-5f)) / ln(10f)
            out[b] = ((db + 60f) / 60f).coerceIn(0f, 1f)
        }
    }

    private fun fft(re: FloatArray, im: FloatArray, n: Int) {
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wr = cos(ang).toFloat()
            val wi = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var cr = 1f
                var ci = 0f
                for (k in 0 until len / 2) {
                    val ur = re[i + k]
                    val ui = im[i + k]
                    val vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k] = ur + vr
                    im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr
                    im[i + k + len / 2] = ui - vi
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }
}
