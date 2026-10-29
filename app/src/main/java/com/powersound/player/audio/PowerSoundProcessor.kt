package com.powersound.player.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.random.Random

/**
 * PowerSound DSP chain:
 *   preamp -> bass shelf -> parametric bands -> treble shelf      (EQ page)
 *   -> mono / stereo expand / balance                             (Effects page)
 *   -> reverb                                                     (Reverb page)
 *   -> volume -> limiter -> resampler (Settings → Audio → Outputs → Sample Rate)
 *   -> 16-bit with the chosen dither (Settings → Audio → Resampler).
 * All math runs in 64-bit floating point. When nothing is switched on the audio passes through untouched
 * ("Perfect Bit Perfect" forces that).
 */
@UnstableApi
class PowerSoundProcessor : BaseAudioProcessor() {

    private var seenVersion = -1
    private var configuredRate = 0
    private var configuredChannels = 0

    // filter coefficients, 5 per section: b0 b1 b2 a1 a2
    private var coeffs = DoubleArray(0)
    private var sectionCount = 0
    // filter memory, 2 per section per channel
    private var state = DoubleArray(0)

    private var preampGain = 1.0
    private var eqOn = false
    private var useLimiter = true

    // effects
    private var fxOn = false
    private var mono = false
    private var sideGain = 1.0
    private var gainL = 1.0
    private var gainR = 1.0
    private var volume = 1.0
    private var reverbOn = false
    private var reverb: Reverb? = null

    private var limiterGain = 1.0
    private var releaseCoef = 0.0
    private val ceiling = 0.966 // -0.3 dBFS

    private var frameBuf = DoubleArray(2)
    private val rng = Random(1234)

    // resampler (null = output at the track's own rate)
    private var resampler: Resampler? = null
    private var rsIn = DoubleArray(0)
    private var rsOut = DoubleArray(0)
    private var ditherer: Ditherer? = null
    private var ditherType = -1
    private var bitPerfect = false

    // short fade-in after a seek / track change (Settings → Audio → Crossfade → Fade on Seek)
    private var fadeInTotal = 0
    private var fadeInLeft = 0

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val enc = inputAudioFormat.encoding
        if (enc != C.ENCODING_PCM_16BIT && enc != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        bitPerfect = AudioEngine.bitPerfect
        val target = if (bitPerfect) inputAudioFormat.sampleRate else AudioEngine.outputRateFor(inputAudioFormat.sampleRate)
        resampler = if (target != inputAudioFormat.sampleRate && target > 0) {
            Resampler(inputAudioFormat.sampleRate, target, inputAudioFormat.channelCount, AudioEngine.resamplerVeryHigh, AudioEngine.resamplerCutoff)
        } else null
        AudioEngine.reportFormat(inputAudioFormat.sampleRate, if (resampler != null) target else inputAudioFormat.sampleRate, inputAudioFormat.channelCount)
        return AudioProcessor.AudioFormat(
            if (resampler != null) target else inputAudioFormat.sampleRate,
            inputAudioFormat.channelCount,
            C.ENCODING_PCM_16BIT
        )
    }

    override fun onFlush() {
        seenVersion = -1
        state.fill(0.0)
        reverb?.reset()
        limiterGain = 1.0
        resampler?.reset()
        ditherer?.reset()
        if (AudioEngine.fadeOnSeek && !bitPerfect) {
            fadeInTotal = (inputAudioFormat.sampleRate * 0.06).toInt().coerceAtLeast(1)
            fadeInLeft = fadeInTotal
        }
    }

    override fun onQueueEndOfStream() {
        // let the last few milliseconds out of the resampler
        val r = resampler ?: return
        val ch = inputAudioFormat.channelCount
        if (rsOut.size < (r.maxOutputFrames(64) + 64) * ch) rsOut = DoubleArray((r.maxOutputFrames(64) + 64) * ch)
        val n = r.drain(rsOut)
        if (n <= 0) return
        val out = replaceOutputBuffer(n * ch * 2)
        val d = ditherer ?: Ditherer(ch, AudioEngine.ditherType).also { ditherer = it }
        for (i in 0 until n * ch) out.putShort(d.quantize(rsOut[i] * 32767.0, i % ch))
        out.flip()
    }

    override fun onReset() {
        seenVersion = -1
        configuredRate = 0
        configuredChannels = 0
        coeffs = DoubleArray(0)
        state = DoubleArray(0)
        sectionCount = 0
        reverb = null
        limiterGain = 1.0
        resampler = null
        ditherer = null
        ditherType = -1
    }

    private fun rebuildIfNeeded(sampleRate: Int, channels: Int) {
        val version = AudioEngine.version
        if (version == seenVersion && sampleRate == configuredRate && channels == configuredChannels) return

        // ---- EQ ----
        val s = AudioEngine.snapshot
        // Settings → Audio → Outputs → "No Equ/Tone" for this output, or Perfect Bit Perfect
        val eqAllowed = s.enabled && !AudioEngine.eqBlocked && !bitPerfect
        val filters = if (eqAllowed) AudioEngine.filtersFor(s, sampleRate.toDouble()) else emptyList()
        val newCoeffs = DoubleArray(filters.size * 5)
        filters.forEachIndexed { i, c ->
            newCoeffs[i * 5] = c.b0
            newCoeffs[i * 5 + 1] = c.b1
            newCoeffs[i * 5 + 2] = c.b2
            newCoeffs[i * 5 + 3] = c.a1
            newCoeffs[i * 5 + 4] = c.a2
        }
        // Keep filter memory while sliders move (no clicks); reset only when the layout changes.
        if (filters.size != sectionCount || channels != configuredChannels) {
            state = DoubleArray(filters.size * channels * 2)
        }
        coeffs = newCoeffs
        sectionCount = filters.size
        preampGain = if (eqAllowed) linearGain(s.preampDb + AudioEngine.headroomDb) else 1.0
        eqOn = eqAllowed && (sectionCount > 0 || preampGain != 1.0)

        // ---- effects & reverb ----
        val fx = AudioEngine.fxSnapshot
        val rg = if (bitPerfect) 0.0 else AudioEngine.replayGainDb
        fxOn = !bitPerfect && (fx.active || kotlin.math.abs(rg) > 0.01)
        mono = fx.mono
        sideGain = 1.0 + 1.5 * fx.stereoExpand
        gainL = min(1.0, 1.0 - fx.balance)
        gainR = min(1.0, 1.0 + fx.balance)
        volume = fx.volume.coerceIn(0.0, 1.0) * linearGain(rg)
        reverbOn = fx.reverbEnabled && !bitPerfect
        if (reverbOn) {
            val r = reverb?.takeIf { sampleRate == configuredRate } ?: Reverb(sampleRate)
            r.configure(fx)
            reverb = r
        } else {
            reverb = null
        }

        useLimiter = (eqOn && s.limiter) || reverbOn || sideGain > 1.0 || rg > 0.0
        if (sampleRate != configuredRate) {
            releaseCoef = 1.0 - exp(-1.0 / (0.15 * sampleRate)) // 150 ms release
        }
        if (frameBuf.size < channels) frameBuf = DoubleArray(channels)
        if (AudioEngine.ditherType != ditherType || ditherer == null) {
            ditherType = AudioEngine.ditherType
            ditherer = Ditherer(channels, ditherType)
        }

        configuredRate = sampleRate
        configuredChannels = channels
        seenVersion = version
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val fmt = inputAudioFormat
        val channels = fmt.channelCount
        val isFloat = fmt.encoding == C.ENCODING_PCM_FLOAT
        val bytesPerSample = if (isFloat) 4 else 2
        val frames = inputBuffer.remaining() / (bytesPerSample * channels)
        if (frames == 0) {
            inputBuffer.position(inputBuffer.limit())
            return
        }

        rebuildIfNeeded(fmt.sampleRate, channels)
        val rs = resampler
        val duck = if (bitPerfect) 1.0 else AudioEngine.duckGain
        val out = replaceOutputBuffer(
            (if (rs != null) rs.maxOutputFrames(frames) else frames) * channels * 2
        )

        // Fast path: nothing switched on and already 16-bit -> copy untouched (bit-exact).
        if (!isFloat && !eqOn && !fxOn && rs == null && duck >= 0.999 && fadeInLeft == 0) {
            val bytes = frames * channels * 2
            if (VisualizerBus.active) {
                val view = inputBuffer.duplicate().order(java.nio.ByteOrder.nativeOrder()).asShortBuffer()
                for (f in 0 until frames) {
                    var sum = 0f
                    for (ch in 0 until channels) sum += view.get(f * channels + ch) / 32768f
                    VisualizerBus.write(sum / channels)
                }
            }
            val slice = inputBuffer.duplicate()
            slice.limit(slice.position() + bytes)
            out.put(slice)
            inputBuffer.position(inputBuffer.limit())
            out.flip()
            return
        }

        val c = coeffs
        val st = state
        val sections = sectionCount
        val buf = frameBuf
        val stereo = channels == 2
        val rev = reverb
        val viz = VisualizerBus.active
        val dith = ditherer ?: Ditherer(channels, AudioEngine.ditherType).also { ditherer = it }
        if (rs != null && rsIn.size < frames * channels) rsIn = DoubleArray(frames * channels)

        for (f in 0 until frames) {
            // ---- EQ per channel ----
            for (ch in 0 until channels) {
                var x = if (isFloat) inputBuffer.float.toDouble() else inputBuffer.short / 32768.0
                if (eqOn) {
                    x *= preampGain
                    var si = 0
                    while (si < sections) {
                        val k = si * 5
                        val m = (si * channels + ch) * 2
                        // Transposed direct form II
                        val y = c[k] * x + st[m]
                        st[m] = c[k + 1] * x - c[k + 3] * y + st[m + 1]
                        st[m + 1] = c[k + 2] * x - c[k + 4] * y
                        x = y
                        si++
                    }
                }
                buf[ch] = x
            }

            // ---- effects (stereo) ----
            if (fxOn && stereo) {
                var l = buf[0]
                var r = buf[1]
                if (mono) {
                    val m = (l + r) * 0.5
                    l = m; r = m
                } else if (sideGain != 1.0) {
                    val mid = (l + r) * 0.5
                    val side = (l - r) * 0.5 * sideGain
                    l = mid + side
                    r = mid - side
                }
                if (rev != null) {
                    rev.process(l, r)
                    l = rev.outL
                    r = rev.outR
                }
                buf[0] = l * gainL * volume
                buf[1] = r * gainR * volume
            } else if (fxOn) {
                for (ch in 0 until channels) {
                    var x = buf[ch]
                    if (rev != null) {
                        rev.process(x, x)
                        x = (rev.outL + rev.outR) * 0.5
                    }
                    buf[ch] = x * volume
                }
            }

            // ---- limiter ----
            var peak = 0.0
            for (ch in 0 until channels) {
                val a = abs(buf[ch])
                if (a > peak) peak = a
            }
            if (useLimiter) {
                // Instant attack, smooth release: never lets a peak pass the ceiling.
                if (peak * limiterGain > ceiling) {
                    limiterGain = ceiling / peak
                } else {
                    limiterGain += releaseCoef * (1.0 - limiterGain)
                    if (peak * limiterGain > ceiling) limiterGain = ceiling / peak
                }
            } else {
                limiterGain = 1.0
            }

            if (viz) {
                var sum = 0.0
                for (ch in 0 until channels) sum += buf[ch]
                VisualizerBus.write((sum / channels * limiterGain).toFloat())
            }

            // ducking (Audio Focus) and the fade-in after a seek
            var g = limiterGain * duck
            if (fadeInLeft > 0) {
                val p = 1.0 - fadeInLeft.toDouble() / fadeInTotal
                g *= p * p
                fadeInLeft--
            }
            if (rs != null) {
                for (ch in 0 until channels) rsIn[f * channels + ch] = buf[ch] * g
            } else {
                for (ch in 0 until channels) out.putShort(dith.quantize(buf[ch] * g * 32767.0, ch))
            }
        }

        if (rs != null) {
            val need = (rs.maxOutputFrames(frames) + 8) * channels
            if (rsOut.size < need) rsOut = DoubleArray(need)
            val n = rs.process(rsIn, frames, rsOut)
            for (i in 0 until n * channels) out.putShort(dith.quantize(rsOut[i] * 32767.0, i % channels))
        }

        inputBuffer.position(inputBuffer.limit())
        out.flip()
    }
}
