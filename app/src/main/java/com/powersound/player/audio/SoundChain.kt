package com.powersound.player.audio

import kotlin.math.abs
import kotlin.math.exp

/**
 * What the chain applies. Rebuilt by [PowerSoundSink] whenever a sound setting changes.
 * Filter coefficients are designed for the chain's processing rate ([SoundChain.rate]).
 */
class ChainParams(
    /** 5 per section: b0 b1 b2 a1 a2. */
    val coeffs: DoubleArray = DoubleArray(0),
    val sections: Int = 0,
    val preamp: Double = 1.0,
    val eqOn: Boolean = false,
    /** Mono / stereo expand / balance / reverb. */
    val fxOn: Boolean = false,
    val mono: Boolean = false,
    val sideGain: Double = 1.0,
    val gainL: Double = 1.0,
    val gainR: Double = 1.0,
    /** Volume × Replay Gain (linear). */
    val gain: Double = 1.0,
    /** Reverb settings, or null when the reverb is off. */
    val reverb: FxSettings? = null,
    val limiter: Boolean = true
) {
    companion object {
        val NEUTRAL = ChainParams()
    }
}

/**
 * PowerSound's sound chain, all in 64-bit floating point:
 *
 *   decoded samples (any bit depth, as float)
 *   → resampler (to the output's rate; skipped when the rates match)
 *   → preamp → Tone bass → equalizer bands → Tone treble
 *   → mono / stereo expand / reverb / balance
 *   → volume, Replay Gain, ducking, fade-in after a seek (all glide, no clicks)
 *   → look-ahead peak limiter
 *   → output (32-bit float, or 16-bit with dither — see [PowerSoundSink])
 *
 * No Android parts here, so the chain can be measured on a computer.
 */
class SoundChain(
    val inRate: Int,
    val channels: Int,
    /** Wanted output rate (= [inRate] to keep the track's rate). */
    val outRate: Int,
    val quality: Int,
    val cutoff: Double,
    /** Perfect Bit Perfect: the samples pass untouched. */
    val bitPerfect: Boolean
) {
    val resampler: PolyphaseResampler? =
        if (!bitPerfect && outRate != inRate && outRate > 0) PolyphaseResampler(inRate, outRate, channels, quality, cutoff) else null

    /** Rate of everything after the resampler (= the output rate). */
    val rate: Int = if (resampler != null) outRate else inRate

    private val limiter = LookaheadLimiter(channels, rate)
    private var params = ChainParams.NEUTRAL
    private var state = DoubleArray(0)
    private var reverb: Reverb? = null
    private var reverbSettings: FxSettings? = null

    private var gainNow = 1.0
    private val glide = 1.0 - exp(-1.0 / (0.010 * rate))   // 10 ms
    private var fadeTotal = 0
    private var fadeLeft = 0

    private val frame = DoubleArray(channels)
    private var rsOut = DoubleArray(0)

    /** Output of the last [process] / [drain] call, interleaved, ±1.0 full scale. */
    var out = DoubleArray(0)
        private set

    /**
     * True when the last [process] call changed no sample (only delayed them): with a 16-bit source the output can
     * then be written back to 16-bit exactly, without dither.
     */
    var lastUntouched = true
        private set

    /** Gain the limiter applies right now (1.0 = not limiting). */
    val limiterGain: Double get() = limiter.gain

    fun setParams(p: ChainParams) {
        if (p.sections != params.sections || state.size != p.sections * channels * 2) {
            state = DoubleArray(p.sections * channels * 2)
        }
        params = p
        val rv = p.reverb
        if (rv == null) {
            reverb = null
            reverbSettings = null
        } else if (rv != reverbSettings) {
            val r = reverb ?: Reverb(rate)
            r.configure(rv)
            reverb = r
            reverbSettings = rv
        }
        limiter.enabled = p.limiter
    }

    /** After a seek: forget the past; optionally fade in over 60 ms (Crossfade → Fade on Seek). */
    fun reset(fadeIn: Boolean) {
        state.fill(0.0)
        reverb?.reset()
        resampler?.reset()
        limiter.reset()
        if (fadeIn && !bitPerfect) {
            fadeTotal = (rate * 0.06).toInt().coerceAtLeast(1)
            fadeLeft = fadeTotal
        } else {
            fadeLeft = 0
        }
    }

    /**
     * Processes [frames] interleaved input frames. [duck] is the Audio Focus ducking gain.
     * Returns the number of output frames, which are in [out].
     */
    fun process(input: DoubleArray, frames: Int, duck: Double): Int {
        if (bitPerfect) {
            ensureOut(frames)
            System.arraycopy(input, 0, out, 0, frames * channels)
            lastUntouched = true
            return frames
        }
        val rs = resampler
        if (rs == null) {
            ensureOut(frames)
            return post(input, frames, duck, 0)
        }
        val need = rs.maxOutputFrames(frames) * channels
        if (rsOut.size < need) rsOut = DoubleArray(need)
        val n = rs.process(input, frames, rsOut)
        ensureOut(n)
        return post(rsOut, n, duck, 0)
    }

    /** End of the track (or a format change): lets the last few milliseconds out. Output in [out]. */
    fun drain(duck: Double): Int {
        if (bitPerfect) return 0
        val rs = resampler
        var n1 = 0
        if (rs != null) {
            val need = (rs.maxOutputFrames(rs.latencyFrames) + 8) * channels
            if (rsOut.size < need) rsOut = DoubleArray(need)
            n1 = rs.drain(rsOut)
        }
        val d = limiter.delayFrames
        ensureOut(n1 + d)
        if (n1 > 0) post(rsOut, n1, duck, 0)
        val zeros = DoubleArray(d * channels)
        post(zeros, d, duck, n1)
        return n1 + d
    }

    private fun ensureOut(frames: Int) {
        val need = frames * channels
        if (out.size < need) {
            val keep = out
            out = DoubleArray(maxOf(need, keep.size * 3 / 2))
            System.arraycopy(keep, 0, out, 0, keep.size)
        }
    }

    /** Everything after the resampler. Writes [n] frames into [out] starting at frame [at]. */
    private fun post(src: DoubleArray, n: Int, duck: Double, at: Int): Int {
        val p = params
        val c = p.coeffs
        val st = state
        val sections = p.sections
        val ch = channels
        val buf = frame
        val rev = reverb
        val stereo = ch == 2
        val viz = VisualizerBus.active
        val target = p.gain * duck
        val o = out
        var touched = p.eqOn || p.fxOn || resampler != null

        for (f in 0 until n) {
            val si0 = f * ch
            // ---- equalizer per channel (transposed direct form II) ----
            for (k0 in 0 until ch) {
                var x = src[si0 + k0]
                if (p.eqOn) {
                    x *= p.preamp
                    var s = 0
                    while (s < sections) {
                        val k = s * 5
                        val m = (s * ch + k0) * 2
                        val y = c[k] * x + st[m]
                        st[m] = c[k + 1] * x - c[k + 3] * y + st[m + 1]
                        st[m + 1] = c[k + 2] * x - c[k + 4] * y
                        x = y
                        s++
                    }
                }
                buf[k0] = x
            }

            // ---- effects ----
            if (p.fxOn) {
                if (stereo) {
                    var l = buf[0]
                    var r = buf[1]
                    if (p.mono) {
                        val m = (l + r) * 0.5
                        l = m
                        r = m
                    } else if (p.sideGain != 1.0) {
                        val mid = (l + r) * 0.5
                        val side = (l - r) * 0.5 * p.sideGain
                        l = mid + side
                        r = mid - side
                    }
                    if (rev != null) {
                        rev.process(l, r)
                        l = rev.outL
                        r = rev.outR
                    }
                    buf[0] = l * p.gainL
                    buf[1] = r * p.gainR
                } else if (rev != null) {
                    for (k0 in 0 until ch) {
                        rev.process(buf[k0], buf[k0])
                        buf[k0] = (rev.outL + rev.outR) * 0.5
                    }
                }
            }

            // ---- volume, Replay Gain, ducking: glide to the target; fade-in after a seek ----
            if (gainNow != target) {
                gainNow += (target - gainNow) * glide
                if (abs(target - gainNow) < 1e-9) gainNow = target
            }
            var g = gainNow
            if (fadeLeft > 0) {
                val q = 1.0 - fadeLeft.toDouble() / fadeTotal
                g *= q * q
                fadeLeft--
            }
            if (g != 1.0) {
                touched = true
                for (k0 in 0 until ch) buf[k0] *= g
            }

            // ---- limiter (also delays by 1.5 ms) ----
            limiter.process(buf)
            if (limiter.gain != 1.0) touched = true

            val oi = (at + f) * ch
            var sum = 0.0
            for (k0 in 0 until ch) {
                o[oi + k0] = buf[k0]
                sum += buf[k0]
            }
            if (viz) VisualizerBus.write((sum / ch).toFloat())
        }
        lastUntouched = !touched
        return n
    }
}
