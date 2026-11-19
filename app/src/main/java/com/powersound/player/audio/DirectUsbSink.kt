package com.powersound.player.audio

import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.audio.AudioSink
import java.nio.ByteBuffer

/**
 * Writes the samples straight to an AudioTrack in the USB DAC's own format (16 / 24 / 32-bit at the song's rate),
 * for [UsbBitPerfect]. Android's own sink can only give 16-bit or float, so it can't feed a 24-bit DAC untouched.
 * No speed change, no effects: it is only used with Perfect Bit Perfect.
 */
@UnstableApi
class DirectUsbSink : AudioSink {

    /** The plan for the next [configure] (set by [SwitchSink]). */
    @Volatile
    var nextPlan: UsbBitPerfect.Plan? = null

    private var listener: AudioSink.Listener? = null
    private var track: AudioTrack? = null
    private var plan: UsbBitPerfect.Plan? = null
    private var format: Format? = null
    /** A new format waiting for the old one's last sound to be played. */
    private var pendingPlan: UsbBitPerfect.Plan? = null
    private var pendingFormat: Format? = null

    private var frameBytes = 4
    private var writtenBytes = 0L
    private var startUs = C.TIME_UNSET
    private var rawHead = 0L
    private var headWraps = 0L
    private var playing = false
    private var ended = false
    private var volume = 1f
    private var attributes = AudioAttributes.DEFAULT

    override fun setListener(listener: AudioSink.Listener) {
        this.listener = listener
    }

    override fun supportsFormat(format: Format): Boolean = getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED

    override fun getFormatSupport(format: Format): Int =
        if (MimeTypes.AUDIO_RAW == format.sampleMimeType && Util.isEncodingLinearPcm(format.pcmEncoding)) AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
        else AudioSink.SINK_FORMAT_UNSUPPORTED

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        val p = nextPlan ?: throw AudioSink.ConfigurationException("No USB plan", inputFormat)
        val cur = plan
        if (track != null && cur != null && cur.encoding == p.encoding && cur.rate == p.rate && cur.channels == p.channels && cur.deviceId == p.deviceId) {
            // same format: the songs join without a gap
            format = inputFormat
            return
        }
        if (track != null && unplayedFrames() > 0) {
            // let the previous song's end play first (see handleBuffer)
            pendingPlan = p
            pendingFormat = inputFormat
            return
        }
        releaseTrack()
        plan = p
        format = inputFormat
    }

    private fun playedFrames(): Long {
        val t = track ?: return 0
        val raw = t.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        if (raw < rawHead - 0x80000000L) headWraps += 0x100000000L
        rawHead = raw
        return headWraps + raw
    }

    private fun writtenFrames() = writtenBytes / frameBytes

    private fun unplayedFrames(): Long = if (track == null) 0 else (writtenFrames() - playedFrames()).coerceAtLeast(0)

    private fun create(): AudioTrack {
        val p = plan ?: throw AudioSink.InitializationException(AudioTrack.STATE_UNINITIALIZED, 0, 0, 0, format ?: Format.Builder().build(), false, null)
        UsbBitPerfect.apply(p)
        val mask = when (p.channels) {
            1 -> AudioFormat.CHANNEL_OUT_MONO
            2 -> AudioFormat.CHANNEL_OUT_STEREO
            else -> Util.getAudioTrackChannelConfig(p.channels)
        }
        val bytesPerSample = when (p.bits) {
            16 -> 2
            24 -> 3
            else -> 4
        }
        frameBytes = bytesPerSample * p.channels
        val min = AudioTrack.getMinBufferSize(p.rate, mask, p.encoding).coerceAtLeast(frameBytes * 1024)
        // about a quarter of a second, like Android's own sink
        val size = maxOf(min * 4, p.rate / 4 * frameBytes).let { it - it % frameBytes }
        val t = try {
            AudioTrack.Builder()
                .setAudioAttributes(UsbBitPerfect.attributes)
                .setAudioFormat(AudioFormat.Builder().setEncoding(p.encoding).setSampleRate(p.rate).setChannelMask(mask).build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(size)
                .build()
        } catch (e: Exception) {
            throw AudioSink.InitializationException(AudioTrack.STATE_UNINITIALIZED, p.rate, mask, size, format ?: Format.Builder().build(), false, e)
        }
        if (t.state != AudioTrack.STATE_INITIALIZED) {
            t.release()
            throw AudioSink.InitializationException(AudioTrack.STATE_UNINITIALIZED, p.rate, mask, size, format ?: Format.Builder().build(), false, null)
        }
        UsbBitPerfect.device(p.deviceId)?.let { d: AudioDeviceInfo -> t.setPreferredDevice(d) }
        t.setVolume(volume)
        writtenBytes = 0
        rawHead = 0
        headWraps = 0
        startUs = C.TIME_UNSET
        if (playing) t.play()
        return t
    }

    private fun releaseTrack() {
        val t = track ?: return
        track = null
        runCatching { t.pause() }
        runCatching { t.flush() }
        runCatching { t.release() }
        writtenBytes = 0
        rawHead = 0
        headWraps = 0
        startUs = C.TIME_UNSET
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        if (pendingPlan != null) {
            if (unplayedFrames() > 0) return false
            releaseTrack()
            plan = pendingPlan
            format = pendingFormat
            pendingPlan = null
            pendingFormat = null
        }
        if (!buffer.hasRemaining()) return true
        val t = track ?: create().also { track = it }
        if (startUs == C.TIME_UNSET) startUs = presentationTimeUs
        ended = false
        val n = t.write(buffer, buffer.remaining(), AudioTrack.WRITE_NON_BLOCKING)
        if (n < 0) throw AudioSink.WriteException(n, format ?: Format.Builder().build(), n == AudioTrack.ERROR_DEAD_OBJECT)
        writtenBytes += n
        return !buffer.hasRemaining()
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        val p = plan ?: return AudioSink.CURRENT_POSITION_NOT_SET
        if (track == null || startUs == C.TIME_UNSET) return AudioSink.CURRENT_POSITION_NOT_SET
        return startUs + playedFrames() * 1_000_000L / p.rate
    }

    override fun play() {
        playing = true
        runCatching { track?.play() }
    }

    override fun pause() {
        playing = false
        runCatching { track?.pause() }
    }

    override fun handleDiscontinuity() = Unit

    override fun playToEndOfStream() {
        if (pendingPlan != null) return
        if (!ended) {
            ended = true
            // stop() plays out what is left in the track, even a last bit smaller than its start level
            runCatching { track?.stop() }
        }
    }

    override fun isEnded(): Boolean = pendingPlan == null && (track == null || (ended && unplayedFrames() == 0L))

    override fun hasPendingData(): Boolean = track != null && unplayedFrames() > 0

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) = Unit // bit-perfect: always 1×
    override fun getPlaybackParameters(): PlaybackParameters = PlaybackParameters.DEFAULT
    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) = Unit
    override fun getSkipSilenceEnabled(): Boolean = false

    override fun setAudioAttributes(audioAttributes: AudioAttributes) {
        attributes = audioAttributes
    }

    override fun getAudioAttributes(): AudioAttributes? = attributes
    override fun setAudioSessionId(audioSessionId: Int) = Unit
    override fun setAuxEffectInfo(auxEffectInfo: AuxEffectInfo) = Unit
    override fun enableTunnelingV21() = Unit
    override fun disableTunneling() = Unit

    override fun setVolume(volume: Float) {
        this.volume = volume
        runCatching { track?.setVolume(volume) }
    }

    override fun flush() {
        // a new track after a seek (Android's own sink does the same): its position starts again at 0
        if (pendingPlan != null) {
            plan = pendingPlan
            format = pendingFormat
            pendingPlan = null
            pendingFormat = null
        }
        releaseTrack()
        ended = false
    }

    override fun reset() {
        flush()
        plan = null
        format = null
    }

    override fun release() {
        reset()
        UsbBitPerfect.clear()
    }
}

/**
 * Android's own sink, or [DirectUsbSink] while a USB DAC gets the sound bit-perfect: [PowerSoundSink] sets
 * [direct] before each format change. The settings go to both, the sound to the one in use.
 */
@UnstableApi
class SwitchSink(private val normal: AudioSink, private val usb: DirectUsbSink?) : AudioSink {
    val canDirect: Boolean get() = usb != null && android.os.Build.VERSION.SDK_INT >= 34

    /** The plan for the next [configure]: non-null sends the sound to the USB DAC directly. */
    @Volatile
    var direct: UsbBitPerfect.Plan? = null

    private var current: AudioSink = normal
    private var playing = false

    private fun all(f: (AudioSink) -> Unit) {
        f(normal)
        usb?.let(f)
    }

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        val p = direct
        val next: AudioSink = if (p != null && usb != null) usb.also { it.nextPlan = p } else normal
        if (next !== current) {
            // switching paths (a song the DAC can't take untouched, or Bit Perfect turned on / off)
            runCatching { current.pause() }
            runCatching { current.flush() }
            if (current === usb) UsbBitPerfect.clear()
            current = next
            if (playing) next.play()
        }
        next.configure(inputFormat, specifiedBufferSize, outputChannels)
    }

    override fun setListener(listener: AudioSink.Listener) = all { it.setListener(listener) }
    override fun setPlayerId(playerId: androidx.media3.exoplayer.analytics.PlayerId?) = all { it.setPlayerId(playerId) }
    override fun setClock(clock: androidx.media3.common.util.Clock) = all { it.setClock(clock) }
    override fun supportsFormat(format: Format): Boolean = normal.supportsFormat(format)
    override fun getFormatSupport(format: Format): Int = normal.getFormatSupport(format)
    override fun getFormatOffloadSupport(format: Format) = normal.getFormatOffloadSupport(format)
    override fun getCurrentPositionUs(sourceEnded: Boolean): Long = current.getCurrentPositionUs(sourceEnded)

    override fun play() {
        playing = true
        current.play()
    }

    override fun handleDiscontinuity() = current.handleDiscontinuity()
    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean =
        current.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)

    override fun playToEndOfStream() = current.playToEndOfStream()
    override fun isEnded(): Boolean = current.isEnded()
    override fun hasPendingData(): Boolean = current.hasPendingData()
    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) = all { it.setPlaybackParameters(playbackParameters) }
    override fun getPlaybackParameters(): PlaybackParameters = current.playbackParameters
    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) = all { it.setSkipSilenceEnabled(skipSilenceEnabled) }
    override fun getSkipSilenceEnabled(): Boolean = current.skipSilenceEnabled
    override fun setAudioAttributes(audioAttributes: AudioAttributes) = all { it.setAudioAttributes(audioAttributes) }
    override fun getAudioAttributes(): AudioAttributes? = current.audioAttributes
    override fun setAudioSessionId(audioSessionId: Int) = all { it.setAudioSessionId(audioSessionId) }
    override fun setAuxEffectInfo(auxEffectInfo: AuxEffectInfo) = all { it.setAuxEffectInfo(auxEffectInfo) }
    override fun setPreferredDevice(audioDeviceInfo: AudioDeviceInfo?) = normal.setPreferredDevice(audioDeviceInfo)
    override fun setOutputStreamOffsetUs(outputStreamOffsetUs: Long) = all { it.setOutputStreamOffsetUs(outputStreamOffsetUs) }
    override fun enableTunnelingV21() = normal.enableTunnelingV21()
    override fun disableTunneling() = normal.disableTunneling()
    override fun setOffloadMode(offloadMode: Int) = normal.setOffloadMode(offloadMode)
    override fun setOffloadDelayPadding(delayInFrames: Int, paddingInFrames: Int) = all { it.setOffloadDelayPadding(delayInFrames, paddingInFrames) }
    override fun setVolume(volume: Float) = all { it.setVolume(volume) }

    override fun pause() {
        playing = false
        current.pause()
    }

    override fun flush() = current.flush()

    override fun reset() {
        all { it.reset() }
    }

    override fun release() = all { it.release() }
}
