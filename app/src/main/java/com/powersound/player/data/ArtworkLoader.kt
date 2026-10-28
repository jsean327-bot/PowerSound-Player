package com.powersound.player.data

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.Collections

/** Loads album art embedded in the audio files (FLAC pictures, ID3 covers...). */
object ArtworkLoader {

    private val cache = object : LruCache<String, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val missing: MutableSet<String> = Collections.synchronizedSet(HashSet())
    private val gate = Semaphore(4)

    fun cached(songId: Long, sizePx: Int): ImageBitmap? =
        cache.get(key(songId, sizePx))?.asImageBitmap()

    suspend fun load(context: Context, songId: Long, sizePx: Int): ImageBitmap? {
        val key = key(songId, sizePx)
        cache.get(key)?.let { return it.asImageBitmap() }
        if (missing.contains(key)) return null

        val bitmap = gate.withPermit {
            withContext(Dispatchers.IO) { decode(context, songId, sizePx) }
        }
        if (bitmap != null) cache.put(key, bitmap) else missing.add(key)
        return bitmap?.asImageBitmap()
    }

    private fun key(id: Long, size: Int) = "$id:$size"

    private fun decode(context: Context, songId: Long, sizePx: Int): Bitmap? {
        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, songId)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                return context.contentResolver.loadThumbnail(uri, Size(sizePx, sizePx), null)
            } catch (_: Exception) {
                // fall through to the retriever
            }
        }
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val bytes = retriever.embeddedPicture ?: return null
            decodeSampled(bytes, sizePx)
        } catch (_: Exception) {
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun decodeSampled(bytes: ByteArray, sizePx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= sizePx && bounds.outHeight / (sample * 2) >= sizePx) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }
}
