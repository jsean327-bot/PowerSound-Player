package com.powersound.player.data

import android.content.ContentUris
import android.content.Context
import android.media.AudioFormat
import android.media.MediaExtractor
import android.media.MediaFormat
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Technical details of the file being played, e.g. "FLAC • 24-bit • 96 kHz • 2 ch • 2850 kbps". */
object AudioInfo {

    suspend fun read(context: Context, songId: Long, sizeBytes: Long, durationMs: Long): String =
        withContext(Dispatchers.IO) {
            val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, songId)
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(context, uri, null)
                for (i in 0 until extractor.trackCount) {
                    val f = extractor.getTrackFormat(i)
                    val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                    if (!mime.startsWith("audio/")) continue

                    val parts = ArrayList<String>()
                    parts.add(formatFromMime(mime))

                    val bits = bitDepth(f)
                    if (bits > 0) parts.add("$bits-bit")

                    if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        val sr = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        val khz = sr / 1000.0
                        parts.add(if (sr % 1000 == 0) "${sr / 1000} kHz" else "%.1f kHz".format(khz))
                    }
                    if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        parts.add("${f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)} ch")
                    }
                    if (sizeBytes > 0 && durationMs > 0) {
                        parts.add("${sizeBytes * 8 / durationMs} kbps")
                    }
                    return@withContext parts.joinToString(" • ")
                }
                ""
            } catch (_: Exception) {
                ""
            } finally {
                extractor.release()
            }
        }

    private fun bitDepth(f: MediaFormat): Int {
        try {
            if (f.containsKey("bits-per-sample")) return f.getInteger("bits-per-sample")
        } catch (_: Exception) {
        }
        try {
            if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                return when (f.getInteger(MediaFormat.KEY_PCM_ENCODING)) {
                    AudioFormat.ENCODING_PCM_8BIT -> 8
                    AudioFormat.ENCODING_PCM_16BIT -> 16
                    AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
                    AudioFormat.ENCODING_PCM_32BIT, AudioFormat.ENCODING_PCM_FLOAT -> 32
                    else -> 0
                }
            }
        } catch (_: Exception) {
        }
        return 0
    }
}
