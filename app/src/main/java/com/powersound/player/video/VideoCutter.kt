package com.powersound.player.video

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

/**
 * ⋮ → Cut: saves a part of the video as a new MP4 in Movies/PowerSound, without re-encoding (fast, same
 * quality). The cut starts at the key frame just before the chosen start. Sound or subtitle formats that MP4
 * can't hold are left out.
 */
object VideoCutter {

    class Result(val ok: Boolean, val message: String)

    suspend fun cut(
        context: Context,
        source: Uri,
        startMs: Long,
        endMs: Long,
        title: String,
        progress: (Float) -> Unit
    ): Result = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < 29) return@withContext Result(false, "Cutting needs Android 10 or newer")
        if (endMs <= startMs + 500) return@withContext Result(false, "The end must be after the start")
        val extractor = MediaExtractor()
        val resolver = context.contentResolver
        var outUri: Uri? = null
        try {
            extractor.setDataSource(context, source, null)
            val safe = title.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(60)
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "${safe}_cut_${System.currentTimeMillis() / 1000}.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/PowerSound")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            outUri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: return@withContext Result(false, "Couldn't create the new file")
            val pfd = resolver.openFileDescriptor(outUri, "w") ?: return@withContext Result(false, "Couldn't write the new file")
            var skipped = 0
            var hasVideo = false
            pfd.use {
                val muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                val map = HashMap<Int, Int>()
                var maxInput = 1 shl 20
                for (i in 0 until extractor.trackCount) {
                    val f = extractor.getTrackFormat(i)
                    val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                    if (!mime.startsWith("video/") && !mime.startsWith("audio/")) { skipped++; continue }
                    val track = runCatching { muxer.addTrack(f) }.getOrNull()
                    if (track == null) { skipped++; continue }
                    if (mime.startsWith("video/")) {
                        hasVideo = true
                        if (f.containsKey("rotation-degrees")) runCatching { muxer.setOrientationHint(f.getInteger("rotation-degrees")) }
                    }
                    if (f.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) maxInput = maxOf(maxInput, f.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                    extractor.selectTrack(i)
                    map[i] = track
                }
                if (map.isEmpty()) return@withContext Result(false, "This video's format can't be cut")
                muxer.start()
                val startUs = startMs * 1000
                val endUs = endMs * 1000
                extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                val base = extractor.sampleTime.coerceAtLeast(0L)
                val buf = ByteBuffer.allocate(maxInput * 2)
                val info = MediaCodec.BufferInfo()
                while (true) {
                    ensureActive()
                    val size = extractor.readSampleData(buf, 0)
                    if (size < 0) break
                    val t = extractor.sampleTime
                    if (t > endUs + 2_000_000) break
                    val track = map[extractor.sampleTrackIndex]
                    if (track != null && t in base..endUs) {
                        val key = (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                        info.set(0, size, t - base, if (key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                        muxer.writeSampleData(track, buf, info)
                        progress(((t - base).toFloat() / (endUs - base).coerceAtLeast(1)).coerceIn(0f, 1f))
                    }
                    extractor.advance()
                }
                muxer.stop()
                muxer.release()
            }
            resolver.update(outUri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            val note = if (skipped > 0) " (some tracks MP4 can't hold were left out)" else ""
            Result(true, if (hasVideo) "Saved in Movies/PowerSound$note" else "Saved the sound only in Movies/PowerSound$note")
        } catch (e: Exception) {
            outUri?.let { runCatching { resolver.delete(it, null, null) } }
            Result(false, "Couldn't cut this video (${e.javaClass.simpleName})")
        } finally {
            runCatching { extractor.release() }
        }
    }
}
