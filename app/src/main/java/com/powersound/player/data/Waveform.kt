package com.powersound.player.data

import android.content.ContentUris
import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.provider.MediaStore
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/** Decodes a song once and returns the real loudness shape used by the waveform seek bar. */
object Waveform {

    private val cache = LruCache<Long, FloatArray>(40)

    fun cached(songId: Long): FloatArray? = cache.get(songId)

    suspend fun load(context: Context, songId: Long, bars: Int = 70): FloatArray? {
        cache.get(songId)?.let { return it }
        val result = withContext(Dispatchers.Default) { decode(context, songId, bars) }
        if (result != null) cache.put(songId, result)
        return result
    }

    private suspend fun decode(context: Context, songId: Long, bars: Int): FloatArray? = withContext(Dispatchers.Default) {
        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, songId)
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            var track = -1
            for (i in 0 until extractor.trackCount) {
                val m = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                if (m.startsWith("audio/")) {
                    track = i; break
                }
            }
            if (track < 0) return@withContext null
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return@withContext null
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            if (durationUs <= 0L) return@withContext null

            val c = MediaCodec.createDecoderByType(mime)
            codec = c
            c.configure(format, null, null, 0)
            c.start()

            val sums = DoubleArray(bars)
            val counts = IntArray(bars)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var isFloat = false
            val deadline = System.currentTimeMillis() + 20_000

            while (!outputDone && isActive && System.currentTimeMillis() < deadline) {
                if (!inputDone) {
                    val inIndex = c.dequeueInputBuffer(5_000)
                    if (inIndex >= 0) {
                        val buf = c.getInputBuffer(inIndex)
                        val n = if (buf != null) extractor.readSampleData(buf, 0) else -1
                        if (n < 0) {
                            c.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            c.queueInputBuffer(inIndex, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = c.dequeueOutputBuffer(info, 5_000)
                if (outIndex >= 0) {
                    val out = c.getOutputBuffer(outIndex)
                    if (out != null && info.size > 0) {
                        out.position(info.offset)
                        out.limit(info.offset + info.size)
                        val bar = ((info.presentationTimeUs.toDouble() / durationUs) * bars).toInt().coerceIn(0, bars - 1)
                        val bb = out.slice().order(ByteOrder.nativeOrder())
                        var sum = 0.0
                        var n = 0
                        if (isFloat) {
                            val fb = bb.asFloatBuffer()
                            var i = 0
                            while (i < fb.limit()) {
                                val v = fb.get(i).toDouble(); sum += v * v; n++; i += 16
                            }
                        } else {
                            val sb = bb.asShortBuffer()
                            var i = 0
                            while (i < sb.limit()) {
                                val v = sb.get(i) / 32768.0; sum += v * v; n++; i += 16
                            }
                        }
                        sums[bar] += sum
                        counts[bar] += n
                    }
                    c.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = c.outputFormat
                    isFloat = of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                }
            }
            if (!outputDone) return@withContext null

            // RMS per bar, then scaled so the loudest bar fills the height.
            val rms = FloatArray(bars) { i -> if (counts[i] > 0) sqrt(sums[i] / counts[i]).toFloat() else 0f }
            val max = rms.maxOrNull()?.takeIf { it > 0f } ?: return@withContext null
            FloatArray(bars) { i -> abs(rms[i] / max).coerceIn(0f, 1f) }
        } catch (e: Exception) {
            null
        } finally {
            try {
                codec?.stop()
            } catch (_: Exception) {
            }
            try {
                codec?.release()
            } catch (_: Exception) {
            }
            extractor.release()
        }
    }
}
