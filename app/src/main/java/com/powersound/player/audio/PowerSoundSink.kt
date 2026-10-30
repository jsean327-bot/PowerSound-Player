package com.powersound.player.audio

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * PowerSound's hi-res audio path, between the decoder and Android:
 *
 *  - tells the decoder to deliver 32-bit float, so 24-bit FLAC keeps all 24 bits
 *    (Android's default path cuts everything to 16 bits first, by plain truncation);
 *  - runs [SoundChain] in 64-bit floating point;
 *  - hands 32-bit float to Android's AudioTrack (or 16-bit with dither when an output is set to 16-bit).
 *
 * The wrapped sink only plays what this one gives it. Encoded passthrough formats go straight to it.
 */
@UnstableApi
class PowerSoundSink(private val sink: AudioSink) : ForwardingAudioSink(sink) {

    private var passthrough = false
    private var chain: SoundChain? = null
    private var encoder: OutputEncoder? = null
    private var inEncoding = C.ENCODING_PCM_16BIT
    private var inChannels = 2
    private var inRate = 44100
    private var floatOut = true
    private var paramsVersion = -1

    // output waiting for the wrapped sink, oldest first
    private val queue = ArrayDeque<Pair<ByteBuffer, Long>>()
    private var outBuf: ByteBuffer? = null
    /** Format the wrapped sink gets once the queued audio of the previous format has been written. */
    private var innerFormat: Format? = null
    private var endTimeUs = 0L
    private var eosDone = false

    // gapless trimming of MP3 / AAC (encoder delay and padding); FLAC has none
    private var trimStart = 0
    private var padFrames = 0
    private var hold = DoubleArray(0)
    private var holdFrames = 0
    private var dataSinceConfigure = false

    private var inBuf = DoubleArray(0)
    private var floatScratch = FloatArray(0)
    private var shortScratch = ShortArray(0)
    private var intScratch = IntArray(0)
    private var byteScratch = ByteArray(0)

    // ---------------------------------------------------------------- formats

    override fun getFormatSupport(format: Format): Int {
        if (MimeTypes.AUDIO_RAW == format.sampleMimeType && Util.isEncodingLinearPcm(format.pcmEncoding)) {
            // every PCM format (8 / 16 / 24 / 32-bit, float) is read by PowerSound itself
            return AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
        }
        return super.getFormatSupport(format)
    }

    override fun supportsFormat(format: Format): Boolean = getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        if (MimeTypes.AUDIO_RAW != inputFormat.sampleMimeType) {
            // encoded audio (passthrough / offload) goes to Android as it is
            queue.clear()
            innerFormat = null
            chain = null
            passthrough = true
            super.configure(inputFormat, specifiedBufferSize, outputChannels)
            return
        }
        val wasPassthrough = passthrough
        passthrough = false
        val bitPerfect = AudioEngine.bitPerfect
        val rate = inputFormat.sampleRate
        val ch = inputFormat.channelCount
        val outRate = if (bitPerfect) rate else AudioEngine.outputRateFor(rate)
        val wantFloat = AudioEngine.outputFloat || (bitPerfect && inputFormat.pcmEncoding != C.ENCODING_PCM_16BIT)
        val quality = AudioEngine.resamplerQuality
        val cutoff = AudioEngine.resamplerCutoff

        val oldEncoding = inEncoding
        inEncoding = inputFormat.pcmEncoding
        inChannels = ch
        inRate = rate
        // the previous item's encoder padding is not played; this item's delay is skipped
        holdFrames = 0
        trimStart = inputFormat.encoderDelay
        padFrames = inputFormat.encoderPadding
        dataSinceConfigure = false
        eosDone = false
        AudioEngine.reportInput(inEncoding)

        val old = chain
        if (old != null && !wasPassthrough && old.inRate == rate && old.channels == ch && old.outRate == outRate &&
            old.bitPerfect == bitPerfect && old.quality == quality && old.cutoff == cutoff && floatOut == wantFloat
        ) {
            // same output format: the chain keeps running, so the tracks join without a gap
            return
        }
        // let the last milliseconds of the previous format out before switching
        if (old != null && !wasPassthrough) {
            val n = old.drain(duck())
            val exact = oldEncoding == C.ENCODING_PCM_16BIT && old.lastUntouched
            if (n > 0) encoder?.let { queue.addLast(it.encode(old.out, n, exact, null) to endTimeUs) }
        }
        val c = SoundChain(rate, ch, outRate, quality, cutoff, bitPerfect)
        chain = c
        paramsVersion = -1
        floatOut = wantFloat
        encoder = OutputEncoder(ch, wantFloat, AudioEngine.ditherType)
        VisualizerBus.rate = c.rate
        AudioEngine.reportFormat(rate, c.rate, ch, wantFloat)
        innerFormat = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(if (wantFloat) C.ENCODING_PCM_FLOAT else C.ENCODING_PCM_16BIT)
            .setChannelCount(ch)
            .setSampleRate(c.rate)
            .build()
        if (queue.isEmpty()) applyInnerFormat()
    }

    private fun applyInnerFormat() {
        val f = innerFormat ?: return
        innerFormat = null
        super.configure(f, 0, null)
    }

    /** Writes the queued output; true when everything is written (and the wrapped sink has the current format). */
    private fun drainQueue(): Boolean {
        while (queue.isNotEmpty()) {
            val (buf, time) = queue.first()
            if (!super.handleBuffer(buf, time, 1)) return false
            queue.removeFirst()
        }
        applyInnerFormat()
        return true
    }

    // ---------------------------------------------------------------- audio

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        if (passthrough) return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        val c = chain ?: return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        if (!drainQueue()) return false
        if (!buffer.hasRemaining()) return true

        refreshParams(c)
        val bytesPerFrame = bytesPerSample(inEncoding) * inChannels
        val inputFrames = buffer.remaining() / bytesPerFrame
        endTimeUs = presentationTimeUs + inputFrames * 1_000_000L / inRate
        val frames = read(buffer)
        dataSinceConfigure = true
        if (frames > 0) {
            val n = c.process(inBuf, frames, duck())
            if (n > 0) {
                val exact = inEncoding == C.ENCODING_PCM_16BIT && c.lastUntouched
                val out = encoder!!.encode(c.out, n, exact, outBuf)
                outBuf = out
                queue.addLast(out to presentationTimeUs)
                drainQueue() // the input is used up; what can't be written now goes first next time
            }
        }
        return true
    }

    override fun playToEndOfStream() {
        if (passthrough) {
            super.playToEndOfStream()
            return
        }
        if (!drainQueue()) return
        if (!eosDone) {
            eosDone = true
            holdFrames = 0 // the encoder padding at the end is not played
            val c = chain
            if (c != null) {
                val n = c.drain(duck())
                if (n > 0) {
                    val exact = inEncoding == C.ENCODING_PCM_16BIT && c.lastUntouched
                    val out = encoder!!.encode(c.out, n, exact, outBuf)
                    outBuf = out
                    queue.addLast(out to endTimeUs)
                    if (!drainQueue()) return
                }
            }
        }
        super.playToEndOfStream()
    }

    override fun isEnded(): Boolean =
        if (passthrough) super.isEnded() else queue.isEmpty() && innerFormat == null && super.isEnded()

    override fun hasPendingData(): Boolean =
        if (passthrough) super.hasPendingData() else queue.isNotEmpty() || super.hasPendingData()

    override fun flush() {
        queue.clear()
        eosDone = false
        holdFrames = 0
        if (dataSinceConfigure) trimStart = 0 // the encoder delay is only skipped at the very start
        chain?.reset(AudioEngine.fadeOnSeek)
        super.flush()
    }

    override fun reset() {
        queue.clear()
        innerFormat = null
        chain = null
        encoder = null
        passthrough = false
        eosDone = false
        holdFrames = 0
        super.reset()
    }

    private fun duck(): Double = if (AudioEngine.bitPerfect) 1.0 else AudioEngine.duckGain

    // ---------------------------------------------------------------- settings → chain

    private fun refreshParams(c: SoundChain) {
        val v = AudioEngine.version
        if (v == paramsVersion) return
        paramsVersion = v
        if (c.bitPerfect) {
            c.setParams(ChainParams.NEUTRAL)
            return
        }
        val s = AudioEngine.snapshot
        // Settings → Audio Outputs → "No Equ/Tone" for this output
        val eqAllowed = s.enabled && !AudioEngine.eqBlocked
        val filters = if (eqAllowed) AudioEngine.filtersFor(s, c.rate.toDouble()) else emptyList()
        val coeffs = DoubleArray(filters.size * 5)
        filters.forEachIndexed { i, f ->
            coeffs[i * 5] = f.b0
            coeffs[i * 5 + 1] = f.b1
            coeffs[i * 5 + 2] = f.b2
            coeffs[i * 5 + 3] = f.a1
            coeffs[i * 5 + 4] = f.a2
        }
        val preamp = if (eqAllowed) linearGain(s.preampDb + AudioEngine.headroomDb) else 1.0
        val fx = AudioEngine.fxSnapshot
        val rg = AudioEngine.replayGainDb
        val reverbOn = fx.reverbEnabled
        val sideGain = 1.0 + 1.5 * fx.stereoExpand
        c.setParams(
            ChainParams(
                coeffs = coeffs,
                sections = filters.size,
                preamp = preamp,
                eqOn = eqAllowed && (filters.isNotEmpty() || preamp != 1.0),
                fxOn = fx.mono || sideGain != 1.0 || kotlin.math.abs(fx.balance) > 0.001 || reverbOn,
                mono = fx.mono,
                sideGain = sideGain,
                gainL = min(1.0, 1.0 - fx.balance),
                gainR = min(1.0, 1.0 + fx.balance),
                gain = fx.volume.coerceIn(0.0, 1.0) * linearGain(rg),
                reverb = if (reverbOn) fx else null,
                limiter = s.limiter
            )
        )
    }

    // ---------------------------------------------------------------- input → 64-bit

    /**
     * Reads the whole buffer into [inBuf] (±1.0), skipping the encoder delay and holding back the encoder padding.
     * Returns the number of frames to process.
     */
    private fun read(buffer: ByteBuffer): Int {
        val ch = inChannels
        val bps = bytesPerSample(inEncoding)
        val frames = buffer.remaining() / (bps * ch)
        val total = holdFrames + frames
        if (inBuf.size < total * ch) inBuf = DoubleArray(total * ch + 1024)
        if (holdFrames > 0) System.arraycopy(hold, 0, inBuf, 0, holdFrames * ch)
        decode(buffer, frames * ch, inBuf, holdFrames * ch)
        buffer.position(buffer.limit())

        var start = 0
        if (trimStart > 0) {
            start = min(trimStart, total)
            trimStart -= start
        }
        val keep = min(padFrames, total - start)
        val usable = total - start - keep
        if (keep > 0) {
            if (hold.size < keep * ch) hold = DoubleArray(keep * ch)
            System.arraycopy(inBuf, (start + usable) * ch, hold, 0, keep * ch)
        }
        holdFrames = keep
        if (start > 0 && usable > 0) System.arraycopy(inBuf, start * ch, inBuf, 0, usable * ch)
        return usable
    }

    private fun decode(buffer: ByteBuffer, samples: Int, dst: DoubleArray, at: Int) {
        val le = ByteOrder.LITTLE_ENDIAN
        val be = ByteOrder.BIG_ENDIAN
        when (inEncoding) {
            C.ENCODING_PCM_FLOAT -> {
                if (floatScratch.size < samples) floatScratch = FloatArray(samples)
                buffer.duplicate().order(le).asFloatBuffer().get(floatScratch, 0, samples)
                for (i in 0 until samples) dst[at + i] = floatScratch[i].toDouble()
            }
            C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> {
                if (shortScratch.size < samples) shortScratch = ShortArray(samples)
                buffer.duplicate().order(if (inEncoding == C.ENCODING_PCM_16BIT) le else be).asShortBuffer().get(shortScratch, 0, samples)
                for (i in 0 until samples) dst[at + i] = shortScratch[i] / 32768.0
            }
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN -> {
                if (intScratch.size < samples) intScratch = IntArray(samples)
                buffer.duplicate().order(if (inEncoding == C.ENCODING_PCM_32BIT) le else be).asIntBuffer().get(intScratch, 0, samples)
                for (i in 0 until samples) dst[at + i] = intScratch[i] / 2147483648.0
            }
            C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> {
                val bytes = samples * 3
                if (byteScratch.size < bytes) byteScratch = ByteArray(bytes)
                buffer.duplicate().get(byteScratch, 0, bytes)
                val little = inEncoding == C.ENCODING_PCM_24BIT
                var j = 0
                for (i in 0 until samples) {
                    val b0 = byteScratch[j].toInt()
                    val b1 = byteScratch[j + 1].toInt()
                    val b2 = byteScratch[j + 2].toInt()
                    val v = if (little) (b2 shl 16) or ((b1 and 0xff) shl 8) or (b0 and 0xff)
                    else (b0 shl 16) or ((b1 and 0xff) shl 8) or (b2 and 0xff)
                    dst[at + i] = v / 8388608.0
                    j += 3
                }
            }
            C.ENCODING_PCM_8BIT -> {
                if (byteScratch.size < samples) byteScratch = ByteArray(samples)
                buffer.duplicate().get(byteScratch, 0, samples)
                for (i in 0 until samples) dst[at + i] = ((byteScratch[i].toInt() and 0xff) - 128) / 128.0
            }
            else -> java.util.Arrays.fill(dst, at, at + samples, 0.0)
        }
    }

    private fun bytesPerSample(encoding: Int): Int = when (encoding) {
        C.ENCODING_PCM_8BIT -> 1
        C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> 2
        C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> 3
        else -> 4
    }
}

/** 64-bit samples → the output format: 32-bit float as they are, or 16-bit with the chosen dither. */
class OutputEncoder(private val channels: Int, val float: Boolean, ditherType: Int) {
    private val dither = Ditherer(channels, ditherType)
    private var floats = FloatArray(0)
    private var shorts = ShortArray(0)

    /**
     * Encodes [frames] frames of [src]. [exact]: the samples are untouched 16-bit values, written back without dither.
     * Reuses [reuse] when it is big enough. Returns a direct little-endian buffer, position 0, limit = data size.
     */
    fun encode(src: DoubleArray, frames: Int, exact: Boolean, reuse: ByteBuffer?): ByteBuffer {
        val n = frames * channels
        val bytes = n * if (float) 4 else 2
        val out = if (reuse != null && reuse.capacity() >= bytes) reuse
        else ByteBuffer.allocateDirect(maxOf(bytes, 32768)).order(ByteOrder.LITTLE_ENDIAN)
        out.clear()
        out.order(ByteOrder.LITTLE_ENDIAN)
        if (float) {
            if (floats.size < n) floats = FloatArray(n)
            for (i in 0 until n) floats[i] = src[i].toFloat()
            out.asFloatBuffer().put(floats, 0, n)
        } else {
            if (shorts.size < n) shorts = ShortArray(n)
            if (exact) {
                for (i in 0 until n) shorts[i] = (src[i] * 32768.0).roundToInt().coerceIn(-32768, 32767).toShort()
            } else {
                for (i in 0 until n) shorts[i] = dither.quantize(src[i] * 32768.0, i % channels)
            }
            out.asShortBuffer().put(shorts, 0, n)
        }
        out.position(0)
        out.limit(bytes)
        return out
    }
}
