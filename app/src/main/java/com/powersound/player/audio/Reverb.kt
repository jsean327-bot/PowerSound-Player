package com.powersound.player.audio

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Stereo room reverb (Schroeder/Freeverb design: 8 damped comb filters + 4 all-pass per side),
 * with pre-delay, a tone filter on the reverb tail and wet/dry mix.
 */
class Reverb(private val sampleRate: Int) {

    private class Comb(size: Int) {
        val buf = DoubleArray(max(size, 1))
        var idx = 0
        var store = 0.0
        fun process(x: Double, feedback: Double, damp: Double): Double {
            val out = buf[idx]
            store = out * (1.0 - damp) + store * damp
            buf[idx] = x + store * feedback
            if (++idx >= buf.size) idx = 0
            return out
        }
    }

    private class AllPass(size: Int) {
        val buf = DoubleArray(max(size, 1))
        var idx = 0
        fun process(x: Double): Double {
            val b = buf[idx]
            val out = -x + b
            buf[idx] = x + b * 0.5
            if (++idx >= buf.size) idx = 0
            return out
        }
    }

    private val combTuning = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)
    private val allPassTuning = intArrayOf(556, 441, 341, 225)
    private val spread = 23

    private var combsL: Array<Comb> = emptyArray()
    private var combsR: Array<Comb> = emptyArray()
    private var apL: Array<AllPass> = emptyArray()
    private var apR: Array<AllPass> = emptyArray()
    private var builtSize = -1.0

    private val preBuf = DoubleArray((sampleRate * 0.25).toInt() + 2)   // up to 250 ms
    private val preBufR = DoubleArray(preBuf.size)
    private var preIdx = 0
    private var preDelaySamples = 0

    private var feedback = 0.84
    private var damp = 0.2
    private var lpCoef = 1.0
    private var lpL = 0.0
    private var lpR = 0.0
    private var wet = 0.3
    private var dry = 1.0
    private var echo = 0.0

    var outL = 0.0
        private set
    var outR = 0.0
        private set

    fun configure(fx: FxSettings) {
        // Size scales the comb/all-pass lengths (room dimensions): 0.4x .. 1.6x.
        val scale = (0.4 + 1.2 * fx.size) * sampleRate / 44100.0
        if (kotlin.math.abs(scale - builtSize) > 1e-6) {
            combsL = Array(combTuning.size) { Comb((combTuning[it] * scale).roundToInt()) }
            combsR = Array(combTuning.size) { Comb(((combTuning[it] + spread) * scale).roundToInt()) }
            apL = Array(allPassTuning.size) { AllPass((allPassTuning[it] * scale).roundToInt()) }
            apR = Array(allPassTuning.size) { AllPass(((allPassTuning[it] + spread) * scale).roundToInt()) }
            builtSize = scale
        }
        feedback = 0.70 + 0.28 * fx.fade             // how long the tail rings
        damp = 0.05 + 0.85 * fx.damp                 // high frequencies absorbed by the walls
        val cutoff = 18000.0 * (1.0 - fx.filter) * (1.0 - fx.filter) + 900.0
        lpCoef = 1.0 - exp(-2.0 * PI * cutoff / sampleRate)
        preDelaySamples = (fx.preDelayMs / 1000.0 * sampleRate).roundToInt().coerceIn(0, preBuf.size - 1)
        wet = fx.mix * 1.6
        dry = 1.0 - fx.mix * 0.45
        echo = fx.preDelayMix * 0.6
    }

    fun reset() {
        combsL.forEach { it.buf.fill(0.0); it.store = 0.0 }
        combsR.forEach { it.buf.fill(0.0); it.store = 0.0 }
        apL.forEach { it.buf.fill(0.0) }
        apR.forEach { it.buf.fill(0.0) }
        preBuf.fill(0.0)
        preBufR.fill(0.0)
        lpL = 0.0
        lpR = 0.0
    }

    fun process(inL: Double, inR: Double) {
        // pre-delay line
        preBuf[preIdx] = inL
        preBufR[preIdx] = inR
        var r = preIdx - preDelaySamples
        if (r < 0) r += preBuf.size
        val dL = preBuf[r]
        val dR = preBufR[r]
        if (++preIdx >= preBuf.size) preIdx = 0

        val input = (dL + dR) * 0.015
        var wl = 0.0
        var wr = 0.0
        for (c in combsL) wl += c.process(input, feedback, damp)
        for (c in combsR) wr += c.process(input, feedback, damp)
        for (a in apL) wl = a.process(wl)
        for (a in apR) wr = a.process(wr)

        // tone filter on the tail
        lpL += lpCoef * (wl - lpL)
        lpR += lpCoef * (wr - lpR)

        outL = inL * dry + lpL * wet + dL * echo
        outR = inR * dry + lpR * wet + dR * echo
    }
}
