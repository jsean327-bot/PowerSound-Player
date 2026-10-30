package com.powersound.player.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 3D Surround (Effects page): the song is played on virtual speakers around your head, in a small studio.
 *
 *  - Front: the left and right channels on two speakers at ±30°, rendered with measured head responses
 *    (SADIE II, see [HrirData]); the voice in the middle keeps its tone.
 *  - Around: the stereo ambience (what differs between left and right) and the early reflections of a studio
 *    come from speakers beside / behind you (±110°) and above you (±45°, 35° up).
 *  - Room: a short, dense studio reverb, also from around and above.
 *
 * Works on stereo at any sample rate (the head responses are converted once). [process] takes one frame.
 */
class Spatializer(private val rate: Int) {

    private val frontSum = Fir(resample(HrirData.FRONT_SUM))
    private val frontDiff = Fir(resample(HrirData.FRONT_DIFF))
    private val surrSum = Fir(resample(HrirData.SURR_SUM))
    private val surrDiff = Fir(resample(HrirData.SURR_DIFF))
    private val topSum = Fir(resample(HrirData.TOP_SUM))
    private val topDiff = Fir(resample(HrirData.TOP_DIFF))

    // early reflections and the ambience come out of these
    private val maxDelay = ms(40.0)
    private val histL = DoubleArray(maxDelay + 1)
    private val histR = DoubleArray(maxDelay + 1)
    private val histS = DoubleArray(maxDelay + 1)
    private var hPos = 0

    // reflection taps (delay in samples)
    private val dSL1 = ms(9.3); private val dSL2 = ms(14.1); private val dSL3 = ms(21.7)
    private val dSR1 = ms(10.1); private val dSR2 = ms(13.3); private val dSR3 = ms(22.9)
    private val dTL1 = ms(6.1); private val dTL2 = ms(17.3)
    private val dTR1 = ms(6.7); private val dTR2 = ms(18.1)
    private val dAmbL = ms(12.0); private val dAmbR = ms(12.7)

    // walls take some treble away (one-pole low-pass per virtual speaker feed) and a studio's bass traps
    // keep the room out of the bass (one-pole high-pass at 150 Hz), so the low end stays tight
    private val wallCoef = 1.0 - exp(-2.0 * PI * 7000.0 / rate)
    private val trapCoef = 1.0 - exp(-2.0 * PI * 150.0 / rate)
    private var lpSL = 0.0; private var lpSR = 0.0; private var lpTL = 0.0; private var lpTR = 0.0
    private var bSL = 0.0; private var bSR = 0.0; private var bTL = 0.0; private var bTR = 0.0

    // studio reverb: 4-line feedback delay network
    private val fdnLen = intArrayOf(ms(29.7), ms(37.0), ms(43.0), ms(49.9))
    private val fdn = Array(4) { DoubleArray(fdnLen[it]) }
    private val fdnPos = IntArray(4)
    private val fdnGain = DoubleArray(4)
    private val fdnDamp = DoubleArray(4)
    private val fdnCoef = 1.0 - exp(-2.0 * PI * 5500.0 / rate)

    private var amount = 0.6
    private var room = 0.35
    private var outGain = 1.0

    var outL = 0.0
        private set
    var outR = 0.0
        private set

    init {
        setRt60(0.55)
    }

    /** [amount] 0..1: how much comes from around and above. [room] 0..1: how much studio reverb. */
    fun configure(amount: Double, room: Double) {
        this.amount = amount.coerceIn(0.0, 1.0)
        this.room = room.coerceIn(0.0, 1.0)
        // keep the loudness about the same whatever the settings (measured with music-like noise)
        outGain = 1.0 / sqrt(1.0 + 0.15 * this.amount * this.amount + 0.10 * this.room * this.room)
    }

    fun reset() {
        frontSum.reset(); frontDiff.reset(); surrSum.reset(); surrDiff.reset(); topSum.reset(); topDiff.reset()
        histL.fill(0.0); histR.fill(0.0); histS.fill(0.0)
        lpSL = 0.0; lpSR = 0.0; lpTL = 0.0; lpTR = 0.0
        bSL = 0.0; bSR = 0.0; bTL = 0.0; bTR = 0.0
        for (d in fdn) d.fill(0.0)
        fdnPos.fill(0)
        fdnDamp.fill(0.0)
        outL = 0.0
        outR = 0.0
    }

    fun process(l: Double, r: Double) {
        val s = (l - r) * 0.5

        // ---- history for reflections / ambience
        histL[hPos] = l
        histR[hPos] = r
        histS[hPos] = s
        val a = amount
        val sl = a * (0.42 * tap(histL, dSL1) + 0.26 * tap(histR, dSL2) + 0.18 * tap(histL, dSL3) + 0.85 * tap(histS, dAmbL))
        val sr = a * (0.42 * tap(histR, dSR1) + 0.26 * tap(histL, dSR2) + 0.18 * tap(histR, dSR3) - 0.85 * tap(histS, dAmbR))
        val tl = a * (0.32 * tap(histL, dTL1) + 0.16 * tap(histR, dTL2))
        val tr = a * (0.32 * tap(histR, dTR1) + 0.16 * tap(histL, dTR2))
        hPos = if (hPos == maxDelay) 0 else hPos + 1

        // ---- studio reverb
        var r0 = 0.0
        var r1 = 0.0
        var r2 = 0.0
        var r3 = 0.0
        if (room > 0.0) {
            val o0 = fdn[0][fdnPos[0]]
            val o1 = fdn[1][fdnPos[1]]
            val o2 = fdn[2][fdnPos[2]]
            val o3 = fdn[3][fdnPos[3]]
            // Householder mixing: x - (2/4) * sum
            val half = 0.5 * (o0 + o1 + o2 + o3)
            val inp = (l + r) * 0.5 * 0.35
            val f0 = feed(0, (o0 - half)) + inp
            val f1 = feed(1, (o1 - half)) - inp
            val f2 = feed(2, (o2 - half)) + inp
            val f3 = feed(3, (o3 - half)) - inp
            fdn[0][fdnPos[0]] = f0
            fdn[1][fdnPos[1]] = f1
            fdn[2][fdnPos[2]] = f2
            fdn[3][fdnPos[3]] = f3
            for (i in 0 until 4) fdnPos[i] = if (fdnPos[i] + 1 == fdnLen[i]) 0 else fdnPos[i] + 1
            val g = room * 0.5
            r0 = o0 * g; r1 = o1 * g; r2 = o2 * g; r3 = o3 * g
        }

        // ---- feeds of the virtual speakers around / above (walls soften the treble, no bass build-up)
        lpSL += (sl + r0 - lpSL) * wallCoef
        lpSR += (sr + r1 - lpSR) * wallCoef
        lpTL += (tl + r2 - lpTL) * wallCoef
        lpTR += (tr + r3 - lpTR) * wallCoef
        bSL += (lpSL - bSL) * trapCoef
        bSR += (lpSR - bSR) * trapCoef
        bTL += (lpTL - bTL) * trapCoef
        bTR += (lpTR - bTR) * trapCoef
        val fSL = lpSL - bSL
        val fSR = lpSR - bSR
        val fTL = lpTL - bTL
        val fTR = lpTR - bTR

        // ---- binaural rendering, symmetric pairs as sum / difference
        val fA = frontSum.push(l + r)
        val fB = frontDiff.push(l - r)
        val sA = surrSum.push(fSL + fSR)
        val sB = surrDiff.push(fSL - fSR)
        val tA = topSum.push(fTL + fTR)
        val tB = topDiff.push(fTL - fTR)
        val sumA = fA + sA + tA
        val sumB = fB + sB + tB
        outL = 0.5 * (sumA + sumB) * outGain
        outR = 0.5 * (sumA - sumB) * outGain
    }

    private fun feed(i: Int, x: Double): Double {
        // damping: the tail gets darker as it decays
        fdnDamp[i] += (x - fdnDamp[i]) * fdnCoef
        return fdnDamp[i] * fdnGain[i]
    }

    private fun setRt60(seconds: Double) {
        for (i in 0 until 4) fdnGain[i] = 10.0.pow(-3.0 * fdnLen[i] / (seconds * rate))
    }

    private fun tap(h: DoubleArray, delay: Int): Double {
        var i = hPos - delay
        if (i < 0) i += h.size
        return h[i]
    }

    private fun ms(v: Double): Int = max(1, (v * rate / 1000.0).roundToInt())

    /** The head responses are measured at 48 kHz: other rates get them band-limited and re-sampled once. */
    private fun resample(h: DoubleArray): DoubleArray {
        if (rate == HrirData.RATE) return h
        val ratio = rate.toDouble() / HrirData.RATE          // output samples per input sample
        val n = (h.size * ratio).roundToInt().coerceAtLeast(8)
        val cutoff = min(1.0, ratio)                           // of the input Nyquist
        val half = 24
        val out = DoubleArray(n)
        for (j in 0 until n) {
            val t = j / ratio                                  // position in input samples
            val k0 = kotlin.math.floor(t).toInt()
            var acc = 0.0
            for (k in (k0 - half + 1)..(k0 + half)) {
                if (k < 0 || k >= h.size) continue
                val x = t - k
                val w = if (abs(x) >= half) 0.0 else 0.5 * (1.0 + kotlin.math.cos(PI * x / half))
                val arg = PI * cutoff * x
                val sinc = if (abs(arg) < 1e-12) 1.0 else sin(arg) / arg
                acc += h[k] * cutoff * sinc * w
            }
            out[j] = acc / ratio
        }
        return out
    }

    /** FIR filter with a doubled ring buffer, so every output is one straight multiply-add loop. */
    private class Fir(h: DoubleArray) {
        private val n = h.size
        private val rev = DoubleArray(n) { h[n - 1 - it] }
        private val buf = DoubleArray(2 * n)
        private var pos = 0

        fun push(x: Double): Double {
            buf[pos] = x
            buf[pos + n] = x
            pos = if (pos + 1 == n) 0 else pos + 1
            // four independent sums: the processor doesn't wait for each multiply-add to finish
            var a0 = 0.0
            var a1 = 0.0
            var a2 = 0.0
            var a3 = 0.0
            var j = 0
            val b = buf
            val c = rev
            val p = pos
            val end4 = n - 3
            while (j < end4) {
                a0 += b[p + j] * c[j]
                a1 += b[p + j + 1] * c[j + 1]
                a2 += b[p + j + 2] * c[j + 2]
                a3 += b[p + j + 3] * c[j + 3]
                j += 4
            }
            while (j < n) {
                a0 += b[p + j] * c[j]
                j++
            }
            return (a0 + a1) + (a2 + a3)
        }

        fun reset() {
            buf.fill(0.0)
            pos = 0
        }
    }
}
