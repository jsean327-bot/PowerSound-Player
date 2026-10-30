package com.powersound.player

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.powersound.player.data.ArtworkLoader
import com.powersound.player.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Address given to every library song's artwork, so the session asks [LockArtLoader] for it. */
const val ART_SCHEME = "powersound-art"

/**
 * Images for the lock screen, the notification and connected devices.
 * Uses the same covers as the app (embedded, cover.jpg, downloaded) and the Settings → Lock Screen options
 * (Blur, Show Default Image).
 */
@OptIn(UnstableApi::class)
class LockArtLoader(private val context: Context, private val scope: CoroutineScope) : BitmapLoader {

    override fun supportsMimeType(mimeType: String): Boolean = mimeType.startsWith("image/")

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> = run {
        val b = BitmapFactory.decodeByteArray(data, 0, data.size)
        val out = b?.let { finish(it) } ?: defaultImage()
        if (out != null) Futures.immediateFuture(out) else Futures.immediateFailedFuture(IllegalArgumentException("no image"))
    }

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
        val future = SettableFuture.create<Bitmap>()
        scope.launch {
            val id = if (uri.scheme == ART_SCHEME) uri.schemeSpecificPart.substringBefore('#').toLongOrNull() else null
            val bmp = withContext(Dispatchers.IO) {
                val raw = if (id != null) {
                    runCatching { ArtworkLoader.load(context, id, 512)?.asAndroidBitmap() }.getOrNull()
                } else runCatching {
                    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                }.getOrNull()
                raw?.let { finish(it) } ?: defaultImage()
            }
            if (bmp != null) future.set(bmp) else future.setException(IllegalStateException("no image"))
        }
        return future
    }

    override fun loadBitmapFromMetadata(metadata: MediaMetadata): ListenableFuture<Bitmap>? {
        // our own address first: it follows the app's album art settings
        metadata.artworkUri?.let { return loadBitmap(it) }
        metadata.artworkData?.let { return decodeBitmap(it) }
        return defaultImage()?.let { Futures.immediateFuture(it) }
    }

    /** Settings → Lock Screen → Blur. */
    private fun finish(b: Bitmap): Bitmap {
        if (!SettingsStore.value.lockBlur) return b
        // strong, cheap blur: shrink a lot, then scale up smoothly (twice)
        val small = Bitmap.createScaledBitmap(b, 24, 24, true)
        val mid = Bitmap.createScaledBitmap(small, 96, 96, true)
        return Bitmap.createScaledBitmap(mid, 512, 512, true)
    }

    /** Settings → Lock Screen → Show Default Image: a PowerSound placeholder when a song has no art. */
    private fun defaultImage(): Bitmap? {
        if (!SettingsStore.value.lockDefaultImage) return null
        val size = 512
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, size.toFloat(), size.toFloat(), 0xFF2A1F4F.toInt(), 0xFF0E0E14.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, size.toFloat(), size.toFloat(), p)
        p.shader = null
        p.color = 0xFF8E8CF0.toInt()
        // simple equalizer bars
        val heights = floatArrayOf(0.35f, 0.6f, 0.85f, 0.5f, 0.7f)
        val w = 44f
        val gap = 22f
        val total = heights.size * w + (heights.size - 1) * gap
        var x = (size - total) / 2
        for (h in heights) {
            val bh = 260f * h
            c.drawRoundRect(x, 386f - bh, x + w, 386f, 14f, 14f, p)
            x += w + gap
        }
        return bmp
    }
}

/** Last commands from headset / Bluetooth buttons, newest first (kept while the app runs). */
object ButtonLog {
    private val items = ArrayDeque<String>()

    @Synchronized
    fun add(text: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        items.addFirst("$time  $text")
        while (items.size > 30) items.removeLast()
    }

    @Synchronized
    fun all(): List<String> = items.toList()
}

/**
 * Broadcasts for other apps (Settings → Misc): the common "metachanged" intents used by widgets, smart watches,
 * scrobblers, and the scrobbling intents of the Last.fm app and Simple Scrobbler.
 */
object ExternalIntents {
    private var lastState = -1

    fun metaChanged(context: Context, player: Player) {
        val st = SettingsStore.value
        val md = player.mediaMetadata
        val id = player.currentMediaItem?.mediaId?.toLongOrNull() ?: return
        val artist = md.artist?.toString() ?: ""
        val album = md.albumTitle?.toString() ?: ""
        val track = md.title?.toString() ?: ""
        val durMs = player.duration.takeIf { it > 0 } ?: 0L
        if (st.metachangedIntent) {
            context.sendBroadcast(
                Intent("com.android.music.metachanged")
                    .putExtra("id", id).putExtra("artist", artist).putExtra("album", album).putExtra("track", track)
                    .putExtra("duration", durMs).putExtra("playing", player.isPlaying)
                    .putExtra("package", context.packageName)
            )
        }
        if (st.scrobbleLastfm) {
            context.sendBroadcast(
                Intent("fm.last.android.metachanged")
                    .putExtra("artist", artist).putExtra("album", album).putExtra("track", track).putExtra("duration", durMs)
            )
        }
        lastState = -1
        if (player.playWhenReady) simple(context, player, 0)
    }

    fun playState(context: Context, player: Player) {
        val st = SettingsStore.value
        if (st.metachangedIntent) {
            context.sendBroadcast(
                Intent("com.android.music.playstatechanged")
                    .putExtra("playing", player.playWhenReady)
                    .putExtra("track", player.mediaMetadata.title?.toString() ?: "")
                    .putExtra("artist", player.mediaMetadata.artist?.toString() ?: "")
                    .putExtra("package", context.packageName)
            )
        }
        if (st.scrobbleLastfm && !player.playWhenReady) context.sendBroadcast(Intent("fm.last.android.playbackpaused"))
        simple(context, player, if (player.playWhenReady) 1 else 2)
    }

    fun stopped(context: Context, player: Player) {
        if (SettingsStore.value.scrobbleLastfm) context.sendBroadcast(Intent("fm.last.android.playbackcomplete"))
        simple(context, player, 3)
    }

    /** Simple Scrobbler: 0 start, 1 resume, 2 pause, 3 complete. */
    private fun simple(context: Context, player: Player, state: Int) {
        if (!SettingsStore.value.scrobbleSimple || state == lastState) return
        lastState = state
        val md = player.mediaMetadata
        context.sendBroadcast(
            Intent("com.adam.aslfms.notify.playstatechanged")
                .putExtra("state", state)
                .putExtra("app-name", "PowerSound Player")
                .putExtra("app-package", context.packageName)
                .putExtra("artist", md.artist?.toString() ?: "")
                .putExtra("album", md.albumTitle?.toString() ?: "")
                .putExtra("track", md.title?.toString() ?: "")
                .putExtra("duration", ((player.duration.takeIf { it > 0 } ?: 0L) / 1000).toInt())
        )
    }
}
