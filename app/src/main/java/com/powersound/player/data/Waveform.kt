package com.powersound.player.data

import android.content.ContentUris
import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.provider.MediaStore
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.log10
import kotlin.math.pow

/**
 * Loudness of a song in 100 ms blocks (mean square of the samples, 0..1).
 * While the song is still being read only the first [known] blocks are filled.
 */
class WaveData(
    val durationMs: Long,
    val blocks: FloatArray,
    val known: Int,
    val complete: Boolean,
    /** Loudest block so far (for scaling). */
    val maxBlock: Float
) {
    /**
     * Bar height 0..1 for the part [fromMs, toMs) of the song, or -1 when that part hasn't been read yet.
     * dB scale over 30 dB below the loudest moment, so quiet intros, verses and choruses look different.
     */
    fun level(fromMs: Long, toMs: Long): Float {
        val a = (fromMs / Waveform.BLOCK_MS).toInt().coerceAtLeast(0)
        val b = ((toMs + Waveform.BLOCK_MS - 1) / Waveform.BLOCK_MS).toInt().coerceAtMost(blocks.size)
        if (a >= b) return 0f
        if (b > known) return if (complete) 0f else -1f
        var sum = 0.0
        var max = 0f
        for (i in a until b) {
            val v = blocks[i]
            sum += v
            if (v > max) max = v
        }
        val ms = 0.5f * (sum / (b - a)).toFloat() + 0.5f * max
        if (ms <= 0f || maxBlock <= 0f) return 0f
        val db = 10f * log10(ms / maxBlock)
        return (1f + db / 30f).coerceIn(0f, 1f).pow(1.4f)
    }

    /** [n] bars over the whole song (Static Seekbar). Parts not read yet are 0. */
    fun bars(n: Int): FloatArray {
        val d = durationMs.coerceAtLeast(1L)
        return FloatArray(n) { i -> level(d * i / n, d * (i + 1) / n).coerceAtLeast(0f) }
    }
}

/**
 * Reads songs once to draw their waveform. The waveform appears while it is being read (the first seconds
 * within a fraction of a second), is kept in memory and on disk, and the next song is prepared in advance.
 */
object Waveform {

    const val BLOCK_MS = 100L

    private class Progress(val data: WaveData?, val finished: Boolean)

    private class Task(val prefetch: Boolean) {
        val state = MutableStateFlow(Progress(null, false))
        @Volatile var lastWatched = System.currentTimeMillis()
    }

    private val memory = LruCache<Long, WaveData>(80)
    private val running = HashMap<Long, Task>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun cached(songId: Long): WaveData? = memory.get(songId)

    /**
     * The waveform of [songId]: [onUpdate] gets it while it is being read (and once more when complete).
     * Returns the complete waveform, or null when the song can't be read.
     */
    suspend fun load(context: Context, songId: Long, onUpdate: (WaveData) -> Unit): WaveData? {
        val hit: WaveData? = memory.get(songId)
        if (hit != null) {
            onUpdate(hit)
            return hit
        }
        val task = start(context.applicationContext, songId, prefetch = false)
        val end = task.state
            .onEach { p ->
                task.lastWatched = System.currentTimeMillis()
                p.data?.let(onUpdate)
            }
            .first { it.finished }
        return end.data?.takeIf { it.complete }
    }

    /** Reads [songId] in the background (the song that starts now, or the next one) so it's ready. */
    fun prefetch(context: Context, songId: Long) {
        if (memory.get(songId) != null) return
        start(context.applicationContext, songId, prefetch = true)
    }

    private fun start(app: Context, songId: Long, prefetch: Boolean): Task {
        synchronized(running) {
            val existing = running[songId]
            if (existing != null) return existing
            val task = Task(prefetch)
            running[songId] = task
            scope.launch {
                try {
                    val fromDisk = readDisk(app, songId)
                    val result = fromDisk ?: decode(app, songId, task)
                    if (result != null) {
                        memory.put(songId, result)
                        if (fromDisk == null) writeDisk(app, songId, result)
                    }
                    task.state.value = Progress(result, true)
                } catch (e: Exception) {
                    task.state.value = Progress(null, true)
                } finally {
                    synchronized(running) { running.remove(songId) }
                }
            }
            return task
        }
    }

    // ---------------------------------------------------------------- disk cache

    private const val MAGIC = 0x57415632 // "WAV2"

    private fun file(app: Context, songId: Long) = File(File(app.cacheDir, "waves").apply { mkdirs() }, "$songId.wf")

    private fun readDisk(app: Context, songId: Long): WaveData? {
        val f = file(app, songId)
        if (!f.exists()) return null
        return try {
            DataInputStream(f.inputStream().buffered()).use { s ->
                if (s.readInt() != MAGIC) {
                    null
                } else {
                    val duration = s.readLong()
                    val n = s.readInt()
                    if (n <= 0 || n > 200_000) {
                        null
                    } else {
                        val blocks = FloatArray(n) { s.readFloat() }
                        WaveData(duration, blocks, n, true, blocks.maxOrNull() ?: 0f)
                    }
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun writeDisk(app: Context, songId: Long, d: WaveData) {
        runCatching {
            DataOutputStream(file(app, songId).outputStream().buffered()).use { s ->
                s.writeInt(MAGIC)
                s.writeLong(d.durationMs)
                s.writeInt(d.known)
                for (i in 0 until d.known) s.writeFloat(d.blocks[i])
            }
        }
    }

    // ---------------------------------------------------------------- decoding

    private suspend fun decode(context: Context, songId: Long, task: Task): WaveData? {
        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, songId)
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            var track = -1
            for (i in 0 until extractor.trackCount) {
                val m = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                if (m.startsWith("audio/")) {
                    track = i
                    break
                }
            }
            if (track < 0) return null
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            if (durationUs <= 0L) return null
            val durationMs = durationUs / 1000

            val c = MediaCodec.createDecoderByType(mime)
            codec = c
            c.configure(format, null, null, 0)
            c.start()

            var blocks = FloatArray((durationMs / BLOCK_MS + 4).toInt())
            var filled = 0
            var maxBlock = 0f
            var rate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
            var channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
            var framesPerBlock = (rate * BLOCK_MS / 1000).toInt().coerceAtLeast(1)
            var blockSum = 0.0
            var blockFrames = 0
            var isFloat = false

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var lastPublish = System.currentTimeMillis()
            val deadline = lastPublish + 60_000

            fun closeBlock() {
                if (filled >= blocks.size) blocks = blocks.copyOf(blocks.size + 64)
                val v = if (blockFrames > 0) (blockSum / (blockFrames * channels)).toFloat() else 0f
                blocks[filled++] = v
                if (v > maxBlock) maxBlock = v
                blockSum = 0.0
                blockFrames = 0
            }

            while (!outputDone && coroutineContext.isActive && System.currentTimeMillis() < deadline) {
                // nobody waits for this song any more (and it isn't a prefetch): stop
                if (!task.prefetch && task.state.subscriptionCount.value == 0 &&
                    System.currentTimeMillis() - task.lastWatched > 3000
                ) return null

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
                        val bb = out.slice().order(ByteOrder.nativeOrder())
                        if (isFloat) {
                            val fb = bb.asFloatBuffer()
                            val n = fb.limit()
                            var i = 0
                            while (i < n) {
                                var s = 0.0
                                for (ch in 0 until channels) {
                                    if (i + ch >= n) break
                                    val v = fb.get(i + ch).toDouble()
                                    s += v * v
                                }
                                blockSum += s
                                blockFrames++
                                if (blockFrames >= framesPerBlock) closeBlock()
                                i += channels
                            }
                        } else {
                            val sb = bb.asShortBuffer()
                            val n = sb.limit()
                            var i = 0
                            while (i < n) {
                                var s = 0.0
                                for (ch in 0 until channels) {
                                    if (i + ch >= n) break
                                    val v = sb.get(i + ch) / 32768.0
                                    s += v * v
                                }
                                blockSum += s
                                blockFrames++
                                if (blockFrames >= framesPerBlock) closeBlock()
                                i += channels
                            }
                        }
                    }
                    c.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = c.outputFormat
                    isFloat = of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                    framesPerBlock = (rate * BLOCK_MS / 1000).toInt().coerceAtLeast(1)
                }

                // show what is read so far, a few times per second
                val now = System.currentTimeMillis()
                if (now - lastPublish >= 200 && filled > 0) {
                    lastPublish = now
                    task.state.value = Progress(WaveData(durationMs, blocks.copyOf(), filled, false, maxBlock), false)
                }
            }
            if (!outputDone) return null
            if (blockFrames > 0) closeBlock()
            return WaveData(durationMs, blocks.copyOf(filled), filled, true, maxBlock)
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
