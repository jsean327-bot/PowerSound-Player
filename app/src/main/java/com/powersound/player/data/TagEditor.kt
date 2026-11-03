package com.powersound.player.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Info/Tags → Edit: writes new tags into a song file.
 *
 * The file is copied into the app, the copy gets the new tags ([TagWriter]: the audio stays byte for byte the same),
 * and the result is written back over the original. If writing back fails half way, the untouched copy is put back.
 * Android is then asked to read the file again, so the library shows the new tags.
 */
object TagEditor {

    /** FLAC and MP3 files can be edited. */
    fun canEdit(song: Song): Boolean = typeOf(song) != null

    /** "flac", "mp3" or null, from the file's type and name. */
    private fun typeOf(song: Song): String? {
        val mime = song.mimeType.lowercase()
        val ext = song.path.substringAfterLast('.', "").lowercase()
        return when {
            "flac" in mime || ext == "flac" -> "flac"
            mime == "audio/mpeg" || mime == "audio/mp3" || ext == "mp3" -> "mp3"
            else -> null
        }
    }

    /** Returns null when the tags were saved, else a short message saying why not. */
    suspend fun save(context: Context, song: Song, edits: TagEdits, rescan: Boolean = true): String? = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "tagedit").apply { mkdirs() }
        val original = File(dir, "original")
        val edited = File(dir, "edited")
        val resolver = context.contentResolver
        try {
            val read = resolver.openInputStream(song.uri)?.use { i -> original.outputStream().use { i.copyTo(it, 1 shl 16) } }
            if (read == null || original.length() == 0L) return@withContext "PowerSound couldn't read this file"
            TagWriter.rewrite(original, edited, edits, expected = typeOf(song))
            try {
                writeBack(context, song.uri, edited)
            } catch (e: Exception) {
                // never leave a half-written song behind
                runCatching { writeBack(context, song.uri, original) }
                throw e
            }
            if (rescan) rescan(context, listOf(song))
            null
        } catch (e: TagWriter.Unsupported) {
            e.message
        } catch (e: SecurityException) {
            "Android didn't allow PowerSound to change this file"
        } catch (e: Exception) {
            e.message ?: "Saving failed"
        } finally {
            original.delete()
            edited.delete()
        }
    }

    private fun writeBack(context: Context, uri: Uri, file: File) {
        val out = context.contentResolver.openOutputStream(uri, "wt") ?: throw IllegalStateException("PowerSound couldn't write this file")
        out.use { o -> file.inputStream().use { it.copyTo(o, 1 shl 16) } }
    }

    /** Asks Android to read the files' tags again (waits until done, at most 10 s + 1 s per file). */
    suspend fun rescan(context: Context, songs: List<Song>) {
        val paths = songs.map { it.path }.filter { it.isNotBlank() }.distinct()
        if (paths.isEmpty()) return
        withTimeoutOrNull(10_000L + 1000L * paths.size) {
            suspendCancellableCoroutine<Unit> { cont ->
                var left = paths.size
                MediaScannerConnection.scanFile(context, paths.toTypedArray(), null) { _, _ ->
                    synchronized(this@TagEditor) {
                        left--
                        if (left == 0 && cont.isActive) cont.resumeWith(Result.success(Unit))
                    }
                }
            }
        }
    }

    /**
     * A picture chosen for the cover, made ready for the file: JPEG, at most 1000 × 1000 pixels
     * (big enough for any screen, small enough to keep the file light).
     */
    suspend fun coverFrom(context: Context, uri: Uri): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@runCatching null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 1000 && bounds.outHeight / (sample * 2) >= 1000) sample *= 2
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return@runCatching null
            val scale = minOf(1f, 1000f / maxOf(decoded.width, decoded.height))
            val bmp = if (scale < 1f) Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true) else decoded
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 92, out)
            out.toByteArray()
        }.getOrNull()
    }
}
