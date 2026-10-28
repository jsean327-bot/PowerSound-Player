package com.powersound.player.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.exp
import kotlin.random.Random

/**
 * PowerSound DSP: preamp -> bass shelf -> parametric bands -> treble shelf -> limiter.
 * All math runs in 64-bit floating point; output is 16-bit PCM with TPDF dither.
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
    private var processing = false
    private var useLimiter = true

    private var limiterGain = 1.0
    private var releaseCoef = 0.0
    private val ceiling = 0.966 // -0.3 dBFS

    private var frameBuf = DoubleArray(2)
    private val rng = Random(1234)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val enc = inputAudioFormat.encoding
        if (enc != C.ENCODING_PCM_16BIT && enc != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return AudioProcessor.AudioFormat(
            inputAudioFormat.sampleRate,
            inputAudioFormat.channelCount,
            C.ENCODING_PCM_16BIT
        )
    }

    override fun onFlush() {
        seenVersion = -1
        state.fill(0.0)
        limiterGain = 1.0
    }

    override fun onReset() {
        seenVersion = -1
        configuredRate = 0
        configuredChannels = 0
        coeffs = DoubleArray(0)
        state = DoubleArray(0)
        sectionCount = 0
        limiterGain = 1.0
    }

    private fun rebuildIfNeeded(sampleRate: Int, channels: Int) {
        val version = AudioEngine.version
        if (version == seenVersion && sampleRate == configuredRate && channels == configuredChannels) return

        val s = AudioEngine.snapshot
        val filters = if (s.enabled) AudioEngine.filtersFor(s, sampleRate.toDouble()) else emptyList()
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
        preampGain = if (s.enabled) linearGain(s.preampDb + AudioEngine.headroomDb) else 1.0
        processing = s.enabled
        useLimiter = s.enabled && s.limiter
        if (sampleRate != configuredRate) {
            releaseCoef = 1.0 - exp(-1.0 / (0.15 * sampleRate)) // 150 ms release
        }
        if (frameBuf.size < channels) frameBuf = DoubleArray(channels)

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
        val out = replaceOutputBuffer(frames * channels * 2)

        // Fast path: engine off (or completely flat) and already 16-bit -> copy untouched (bit-exact).
        val passthrough = !isFloat && (!processing || (sectionCount == 0 && preampGain == 1.0))
        if (passthrough) {
            val bytes = frames * channels * 2
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

        for (f in 0 until frames) {
            var peak = 0.0
            for (ch in 0 until channels) {
                var x = if (isFloat) inputBuffer.float.toDouble() else inputBuffer.short / 32768.0
                if (processing) {
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
                val a = abs(x)
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

            for (ch in 0 until channels) {
                // TPDF dither (±1 LSB) when reducing to 16-bit
                val dither = (rng.nextDouble() - rng.nextDouble())
                var v = buf[ch] * limiterGain * 32767.0 + dither
                if (v > 32767.0) v = 32767.0 else if (v < -32768.0) v = -32768.0
                out.putShort(Math.round(v).toInt().toShort())
            }
        }

        inputBuffer.position(inputBuffer.limit())
        out.flip()
    }
}
