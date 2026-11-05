package com.powersound.player.video

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Audio → Stereo mode: 0 stereo, 1 mono, 2 left channel in both ears, 3 right channel, 4 left and right swapped. */
val STEREO_MODES = listOf("Stereo", "Mono", "Left only", "Right only", "Reverse (swap left/right)")

/**
 * Stereo mode for videos. Works on 2-channel sound (16-bit or float); other sound passes untouched.
 * The mode can change while playing.
 */
@OptIn(UnstableApi::class)
class StereoModeProcessor : BaseAudioProcessor() {

    @Volatile
    var mode = 0

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
        if (inputAudioFormat.channelCount == 2 &&
            (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT || inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT)
        ) inputAudioFormat else AudioProcessor.AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        inputBuffer.order(ByteOrder.nativeOrder())
        val out = replaceOutputBuffer(size)
        val m = mode
        if (inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) {
            while (inputBuffer.remaining() >= 8) {
                val l = inputBuffer.float
                val r = inputBuffer.float
                when (m) {
                    1 -> { val v = (l + r) * 0.5f; out.putFloat(v); out.putFloat(v) }
                    2 -> { out.putFloat(l); out.putFloat(l) }
                    3 -> { out.putFloat(r); out.putFloat(r) }
                    4 -> { out.putFloat(r); out.putFloat(l) }
                    else -> { out.putFloat(l); out.putFloat(r) }
                }
            }
        } else {
            while (inputBuffer.remaining() >= 4) {
                val l = inputBuffer.short
                val r = inputBuffer.short
                when (m) {
                    1 -> { val v = ((l + r) / 2).toShort(); out.putShort(v); out.putShort(v) }
                    2 -> { out.putShort(l); out.putShort(l) }
                    3 -> { out.putShort(r); out.putShort(r) }
                    4 -> { out.putShort(r); out.putShort(l) }
                    else -> { out.putShort(l); out.putShort(r) }
                }
            }
        }
        // a broken half frame at the end (never expected) is dropped
        inputBuffer.position(inputBuffer.limit())
        out.flip()
    }
}

/**
 * Audio → Synchronization. The picture follows the sound's clock; moving that clock by the chosen delay
 * makes the sound come later (+) or earlier (−) than the picture.
 */
@OptIn(UnstableApi::class)
class DelayedAudioSink(sink: AudioSink, private val delayUs: () -> Long) : ForwardingAudioSink(sink) {
    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        val p = super.getCurrentPositionUs(sourceEnded)
        return if (p == AudioSink.CURRENT_POSITION_NOT_SET) p else maxOf(0L, p + delayUs())
    }
}
