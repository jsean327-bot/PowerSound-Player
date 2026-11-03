package com.powersound.player.data

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.powersound.player.audio.LoudnessMeter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.nio.ByteOrder

/** Measured loudness of a song (LUFS) and its highest sample (1.0 = full scale). */
data class Loudness(val lufs: Double, val peak: Double)

/**
 * Settings → Audio → Replay Gain → Scan Loudness: measures how loud each song really is, so songs without
 * Replay Gain tags are leveled too. Results are kept on the phone (a file is measured again only when it
 * changed). Album gain = the loudness of the whole album, so quiet and loud songs of one album stay as they are.
 */
object LoudnessStore {

    /** Replay Gain 2.0 reference level. */
    const val REFERENCE_LUFS = -18.0

    private var file: File? = null
    private val map = HashMap<Long, Pair<String, Loudness>>()   // song id → (file stamp, loudness)

    /** null = no scan running, else (done, total). */
    val progress = MutableStateFlow<Pair<Int, Int>?>(null)

    fun init(context: Context) {
        if (file != null) return
        val f = File(context.filesDir, "loudness.json")
        file = f
        runCatching {
            if (f.exists()) {
                val o = JSONObject(f.readText())
                for (k in o.keys()) {
                    val e = o.getJSONObject(k)
                    map[k.toLong()] = e.getString("s") to Loudness(e.getDouble("l"), e.getDouble("p"))
                }
            }
        }
    }

    private fun stamp(s: Song) = "${s.size}:${s.dateAdded}:${s.durationMs}"

    @Synchronized
    fun get(song: Song): Loudness? = map[song.id]?.takeIf { it.first == stamp(song) }?.second

    /** Gain in dB for one song (track) or its whole album, from the scan; null when not measured. */
    fun gainDb(song: Song, album: List<Song>?, useAlbum: Boolean): Pair<Double, Double>? {
        val own = get(song) ?: return null
        if (useAlbum && album != null && album.size > 1) {
            val parts = album.mapNotNull { s -> get(s)?.let { it to s.durationMs.coerceAtLeast(1) } }
            // only when (nearly) the whole album was measured
            if (parts.size * 10 >= album.size * 9) {
                val total = parts.sumOf { it.second.toDouble() }
                val energy = parts.sumOf { (l, d) -> Math.pow(10.0, l.lufs / 10.0) * d } / total
                val lufs = 10.0 * kotlin.math.log10(energy)
                return (REFERENCE_LUFS - lufs) to parts.maxOf { it.first.peak }
            }
        }
        return (REFERENCE_LUFS - own.lufs) to own.peak
    }

    fun missing(songs: List<Song>): List<Song> = songs.filter { get(it) == null }

    /** Measures every song in [songs] that isn't measured yet, two at a time. */
    suspend fun scan(context: Context, songs: List<Song>) = coroutineScope {
        val todo = missing(songs)
        if (todo.isEmpty()) return@coroutineScope
        var done = 0
        progress.value = 0 to todo.size
        val gate = Semaphore(2)
        try {
            todo.map { s ->
                async(Dispatchers.Default) {
                    gate.withPermit {
                        ensureActive()
                        val l = runCatching { measure(context, s) }.getOrNull()
                        synchronized(this@LoudnessStore) {
                            if (l != null) map[s.id] = stamp(s) to l
                            done++
                            progress.value = done to todo.size
                            if (done % 20 == 0) save()
                        }
                    }
                }
            }.awaitAll()
        } finally {
            synchronized(this@LoudnessStore) { save() }
            progress.value = null
        }
    }

    private fun save() {
        val f = file ?: return
        runCatching {
            val o = JSONObject()
            for ((id, v) in map) o.put(id.toString(), JSONObject().put("s", v.first).put("l", v.second.lufs).put("p", v.second.peak))
            f.writeText(o.toString())
        }
    }

    /** Decodes the whole song with Android's decoders and measures it. */
    suspend fun measure(context: Context, song: Song): Loudness? = withContext(Dispatchers.Default) {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(context, song.uri, null)
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: return@withContext null
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val c = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec = c
            c.configure(fmt, null, null, 0)
            c.start()
            var meter: LoudnessMeter? = null
            var channels = 0
            var floatOut = false
            var buf = FloatArray(0)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            while (true) {
                ensureActive()
                if (!inputDone) {
                    val i = c.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val n = ex.readSampleData(c.getInputBuffer(i)!!, 0)
                        if (n < 0) {
                            c.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            c.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val o = c.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED || (o >= 0 && meter == null)) {
                    val of = c.outputFormat
                    channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    val rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    floatOut = of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    meter = LoudnessMeter(rate, channels)
                }
                if (o >= 0) {
                    val out = c.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
                    out.position(info.offset)
                    out.limit(info.offset + info.size)
                    val samples = if (floatOut) info.size / 4 else info.size / 2
                    if (buf.size < samples) buf = FloatArray(samples)
                    if (floatOut) out.asFloatBuffer().get(buf, 0, samples)
                    else {
                        val sb = out.asShortBuffer()
                        for (k in 0 until samples) buf[k] = sb.get(k) / 32768f
                    }
                    meter?.add(buf, samples / channels.coerceAtLeast(1))
                    c.releaseOutputBuffer(o, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                }
            }
            val m = meter ?: return@withContext null
            m.integrated()?.let { Loudness(it, m.peak) }
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            ex.release()
        }
    }
}
