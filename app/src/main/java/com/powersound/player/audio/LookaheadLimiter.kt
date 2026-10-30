package com.powersound.player.audio

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Peak limiter that looks 1.5 ms ahead (Equalizer → Limiter).
 *
 * The gain never jumps: it glides down over the look-ahead time *before* a peak arrives and recovers slowly
 * (80 ms) afterwards, so the waveform itself is never bent — no clipping, no crackle on loud masters, even
 * after resampling has created peaks between the original samples.
 *
 * While nothing goes over the ceiling the gain is exactly 1.0, so the samples pass bit-for-bit
 * (only delayed by 1.5 ms).
 */
class LookaheadLimiter(private val channels: Int, sampleRate: Int, var ceiling: Double = CEILING) {

    companion object {
        /**
         * Full scale. Music that is not boosted never goes over it, so the limiter never touches it;
         * only boosts (EQ, Replay Gain, effects) and the peaks that resampling creates between samples are caught.
         */
        const val CEILING = 1.0
    }

    private val len = max(8, (sampleRate * 0.0015).roundToInt())
    private val releaseCoef = 1.0 - exp(-1.0 / (0.080 * sampleRate))

    // the audio waits here while the gain is prepared
    private val delayLen = len - 1
    private val delay = DoubleArray(delayLen * channels)
    private var delayPos = 0

    // lowest required gain of the last [len] frames (monotonic queue)
    private val qVal = DoubleArray(len + 1)
    private val qIdx = LongArray(len + 1)
    private var qHead = 0
    private var qSize = 0
    private var t = 0L

    // release smoothing and the moving average that turns steps into ramps
    private var release = 1.0
    private val box = DoubleArray(len) { 1.0 }
    private var boxPos = 0
    private var boxSum = len.toDouble()
    private var belowOne = 0

    /** When false, nothing is limited (the short delay stays, so switching makes no click). */
    var enabled = true

    /** Gain applied to the last frame (1.0 = untouched). */
    var gain = 1.0
        private set

    val delayFrames: Int get() = delayLen

    fun reset() {
        java.util.Arrays.fill(delay, 0.0)
        delayPos = 0
        qHead = 0
        qSize = 0
        t = 0L
        release = 1.0
        java.util.Arrays.fill(box, 1.0)
        boxPos = 0
        boxSum = len.toDouble()
        belowOne = 0
        gain = 1.0
    }

    /** Takes one frame in [frame]; on return it holds the delayed, limited frame. */
    fun process(frame: DoubleArray) {
        // gain this frame needs to stay under the ceiling
        var need = 1.0
        if (enabled) {
            var peak = 0.0
            for (c in 0 until channels) {
                val a = abs(frame[c])
                if (a > peak) peak = a
            }
            if (peak > ceiling) need = ceiling / peak
        }

        // lowest need of the last [len] frames
        val cap = qVal.size
        while (qSize > 0) {
            val back = (qHead + qSize - 1) % cap
            if (qVal[back] >= need) qSize-- else break
        }
        val slot = (qHead + qSize) % cap
        qVal[slot] = need
        qIdx[slot] = t
        qSize++
        while (qIdx[qHead] <= t - len) {
            qHead = (qHead + 1) % cap
            qSize--
        }
        val hold = qVal[qHead]
        t++

        // fall at once, rise slowly
        var r = release + (1.0 - release) * releaseCoef
        if (r > 0.999999999) r = 1.0
        if (hold < r) r = hold
        release = r

        // moving average over [len] frames: a smooth ramp that still reaches every peak's gain in time
        val old = box[boxPos]
        if (old < 1.0) belowOne--
        if (r < 1.0) belowOne++
        box[boxPos] = r
        boxPos = if (boxPos + 1 == len) 0 else boxPos + 1
        val g: Double
        if (belowOne == 0) {
            boxSum = len.toDouble()
            g = 1.0
        } else {
            boxSum += r - old
            g = boxSum / len
        }
        gain = g

        // delay the audio by len - 1 frames and apply the gain
        val base = delayPos * channels
        for (c in 0 until channels) {
            val x = frame[c]
            val y = delay[base + c]
            delay[base + c] = x
            frame[c] = if (g == 1.0) y else y * g
        }
        delayPos = if (delayPos + 1 == delayLen) 0 else delayPos + 1
    }
}
