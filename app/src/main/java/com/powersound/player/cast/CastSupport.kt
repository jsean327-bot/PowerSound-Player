package com.powersound.player.cast

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.MediaItemConverter
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaQueueItem
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions
import com.google.android.gms.common.images.WebImage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import kotlin.math.roundToInt

/** Google's ready-made receiver on the Chromecast: plays a song from an address and shows its cover and name. */
const val CAST_RECEIVER_ID = "CC1AD845"

/**
 * Cast settings read by Google Play services (named in the manifest). PowerSound keeps its own notification,
 * lock screen and media session, so the Cast library's are turned off.
 */
class PowerSoundCastOptions : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions {
        val media = CastMediaOptions.Builder()
            .setNotificationOptions(null)
            .setMediaSessionEnabled(false)
            .build()
        return CastOptions.Builder()
            .setReceiverApplicationId(CAST_RECEIVER_ID)
            .setStopReceiverApplicationWhenEndingSession(true)
            .setResumeSavedSession(false)
            .setEnableReconnectionService(false)
            .setCastMediaOptions(media)
            .build()
    }

    override fun getAdditionalSessionProviders(context: Context): MutableList<SessionProvider>? = null
}

/** What the app screens need: which device plays now, and its volume / stop. */
object CastControl {
    private val _device = MutableStateFlow<String?>(null)

    /** Name of the Chromecast the music plays on, or null when playing on the phone. */
    val device: StateFlow<String?> = _device

    /** True while the video player is (or is about to start) casting: the music then stays off the Chromecast. */
    @Volatile
    var videoOwnsCast = false

    internal fun setDevice(name: String?) {
        _device.value = name
    }

    /** null when this phone has no (or too old) Google Play services. */
    fun context(c: Context): CastContext? = runCatching { CastContext.getSharedInstance(c.applicationContext) }.getOrNull()

    private fun session(c: Context): CastSession? = context(c)?.sessionManager?.currentCastSession

    fun volume(c: Context): Float = (session(c)?.volume ?: 0.0).toFloat()

    fun setVolume(c: Context, v: Float) {
        runCatching { session(c)?.volume = v.coerceIn(0f, 1f).toDouble() }
    }

    /** Ends casting; the music comes back to the phone (paused). */
    fun stop(c: Context) {
        runCatching { context(c)?.sessionManager?.endCurrentSession(true) }
    }
}

/**
 * Turns the app's songs into what the Chromecast plays: an address on the phone's own little web server
 * ([MediaServer]) plus title, artist, album and cover. Only a few words travel with each song, so a long
 * list still fits in one message to the Chromecast.
 */
@OptIn(UnstableApi::class)
class CastItems(private val context: Context, private val server: MediaServer) : MediaItemConverter {

    override fun toMediaQueueItem(mediaItem: MediaItem): MediaQueueItem {
        val songId = mediaItem.mediaId.toLongOrNull()
        val local = mediaItem.localConfiguration?.uri ?: mediaItem.requestMetadata.mediaUri
        val url: String
        val mime: String
        if (songId != null) {
            url = server.songUrl(songId) ?: ""
            mime = mimeOf(songId)
        } else {
            // a radio stream: the Chromecast fetches it straight from the internet
            url = local?.toString() ?: ""
            mime = mediaItem.localConfiguration?.mimeType
                ?: if (url.contains(".m3u8", ignoreCase = true)) "application/x-mpegURL" else "audio/mpeg"
        }
        val m = mediaItem.mediaMetadata
        val md = com.google.android.gms.cast.MediaMetadata(com.google.android.gms.cast.MediaMetadata.MEDIA_TYPE_MUSIC_TRACK)
        m.title?.let { md.putString(com.google.android.gms.cast.MediaMetadata.KEY_TITLE, it.toString()) }
        m.artist?.let { md.putString(com.google.android.gms.cast.MediaMetadata.KEY_ARTIST, it.toString()) }
        m.albumTitle?.let { md.putString(com.google.android.gms.cast.MediaMetadata.KEY_ALBUM_TITLE, it.toString()) }
        m.albumArtist?.let { md.putString(com.google.android.gms.cast.MediaMetadata.KEY_ALBUM_ARTIST, it.toString()) }
        if (songId != null) server.coverUrl(songId)?.let { md.addImage(WebImage(Uri.parse(it))) }
        val custom = JSONObject()
        custom.put("id", mediaItem.mediaId)
        local?.let { custom.put("uri", it.toString()) }
        m.artworkUri?.let { custom.put("art", it.toString()) }
        val info = MediaInfo.Builder(mediaItem.mediaId.ifEmpty { url })
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(mime)
            .setContentUrl(url)
            .setMetadata(md)
            .setCustomData(custom)
            .build()
        return MediaQueueItem.Builder(info).build()
    }

    override fun toMediaItem(mediaQueueItem: MediaQueueItem): MediaItem {
        val info = mediaQueueItem.media
        val custom = info?.customData
        val md = info?.metadata
        val id = custom?.optString("id")?.takeIf { it.isNotEmpty() } ?: info?.contentId ?: ""
        val uri = custom?.optString("uri")?.takeIf { it.isNotEmpty() }
            ?: id.toLongOrNull()?.let { ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, it).toString() }
            ?: info?.contentUrl ?: info?.contentId ?: ""
        val meta = androidx.media3.common.MediaMetadata.Builder()
        md?.getString(com.google.android.gms.cast.MediaMetadata.KEY_TITLE)?.let { meta.setTitle(it) }
        md?.getString(com.google.android.gms.cast.MediaMetadata.KEY_ARTIST)?.let { meta.setArtist(it) }
        md?.getString(com.google.android.gms.cast.MediaMetadata.KEY_ALBUM_TITLE)?.let { meta.setAlbumTitle(it) }
        md?.getString(com.google.android.gms.cast.MediaMetadata.KEY_ALBUM_ARTIST)?.let { meta.setAlbumArtist(it) }
        custom?.optString("art")?.takeIf { it.isNotEmpty() }?.let { meta.setArtworkUri(Uri.parse(it)) }
        return MediaItem.Builder()
            .setMediaId(id)
            .setUri(uri)
            .setMimeType(info?.contentType)
            .setMediaMetadata(meta.build())
            .build()
    }

    private fun mimeOf(id: Long): String {
        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
        val t = runCatching { context.contentResolver.getType(uri) }.getOrNull()?.lowercase()
        return when {
            t == null -> "audio/flac"
            t.contains("flac") -> "audio/flac"
            t == "audio/x-wav" || t == "audio/wave" -> "audio/wav"
            else -> t
        }
    }
}

/**
 * The player the media session uses while casting: Media3's [CastPlayer], plus the Chromecast's volume, so
 * the phone's volume buttons, the notification and the lock screen control the TV / speaker volume.
 * [onNewList] is told when the app starts a new list, so a long list can be sent to the Chromecast in parts.
 */
@OptIn(UnstableApi::class)
class RemotePlayer(
    private val cast: CastPlayer,
    private val castContext: CastContext,
    private val onNewList: (items: List<MediaItem>, startIndex: Int, startPositionMs: Long) -> Unit,
    private val shuffleNow: () -> Boolean,
    private val onShuffle: (Boolean) -> Unit,
    private val onRepeat: (Int) -> Unit
) : ForwardingPlayer(cast) {

    private val listeners = java.util.concurrent.CopyOnWriteArraySet<Player.Listener>()

    private fun session(): CastSession? = castContext.sessionManager?.currentCastSession

    // ---- a new list from the app → the service loads it in parts

    override fun setMediaItems(mediaItems: MutableList<MediaItem>) = onNewList(mediaItems, 0, 0L)

    override fun setMediaItems(mediaItems: MutableList<MediaItem>, resetPosition: Boolean) {
        if (resetPosition) onNewList(mediaItems, 0, 0L)
        else onNewList(mediaItems, currentMediaItemIndex.coerceIn(0, maxOf(0, mediaItems.size - 1)), currentPosition)
    }

    override fun setMediaItems(mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long) =
        onNewList(
            mediaItems,
            if (startIndex == C.INDEX_UNSET) 0 else startIndex,
            if (startPositionMs == C.TIME_UNSET) 0L else startPositionMs
        )

    override fun setMediaItem(mediaItem: MediaItem) = onNewList(listOf(mediaItem), 0, 0L)

    override fun setMediaItem(mediaItem: MediaItem, startPositionMs: Long) = onNewList(listOf(mediaItem), 0, startPositionMs)

    override fun setMediaItem(mediaItem: MediaItem, resetPosition: Boolean) = onNewList(listOf(mediaItem), 0, 0L)

    // ---- shuffle / repeat: the phone keeps the order; the Chromecast gets the songs in that order

    override fun getShuffleModeEnabled(): Boolean = shuffleNow()

    override fun setShuffleModeEnabled(shuffleModeEnabled: Boolean) {
        if (shuffleModeEnabled == shuffleNow()) return
        onShuffle(shuffleModeEnabled)
        for (l in listeners) runCatching { l.onShuffleModeEnabledChanged(shuffleModeEnabled) }
    }

    override fun setRepeatMode(repeatMode: Int) {
        super.setRepeatMode(repeatMode)
        onRepeat(repeatMode)
    }

    // ---- the Chromecast's volume (0…MAX steps)

    override fun getAvailableCommands(): Player.Commands =
        super.getAvailableCommands().buildUpon().addAll(*VOLUME_COMMANDS).build()

    override fun isCommandAvailable(command: Int): Boolean =
        command in VOLUME_COMMANDS || super.isCommandAvailable(command)

    override fun getDeviceInfo(): DeviceInfo =
        DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE).setMinVolume(0).setMaxVolume(MAX_VOLUME).build()

    override fun getDeviceVolume(): Int = ((session()?.volume ?: 0.0) * MAX_VOLUME).roundToInt()

    override fun isDeviceMuted(): Boolean = session()?.isMute ?: false

    @Deprecated("Deprecated in Java")
    override fun setDeviceVolume(volume: Int) = setDeviceVolume(volume, 0)

    override fun setDeviceVolume(volume: Int, flags: Int) {
        val v = volume.coerceIn(0, MAX_VOLUME)
        runCatching { session()?.volume = v / MAX_VOLUME.toDouble() }
        notifyVolume(v)
    }

    @Deprecated("Deprecated in Java")
    override fun increaseDeviceVolume() = increaseDeviceVolume(0)

    override fun increaseDeviceVolume(flags: Int) = setDeviceVolume(getDeviceVolume() + 1, flags)

    @Deprecated("Deprecated in Java")
    override fun decreaseDeviceVolume() = decreaseDeviceVolume(0)

    override fun decreaseDeviceVolume(flags: Int) = setDeviceVolume(getDeviceVolume() - 1, flags)

    @Deprecated("Deprecated in Java")
    override fun setDeviceMuted(muted: Boolean) = setDeviceMuted(muted, 0)

    override fun setDeviceMuted(muted: Boolean, flags: Int) {
        runCatching { session()?.setMute(muted) }
        notifyVolume(getDeviceVolume(), muted)
    }

    /** The volume changed on the Chromecast itself (its remote, Google Home): tell the session. */
    fun notifyVolume(volume: Int = getDeviceVolume(), muted: Boolean = isDeviceMuted()) {
        for (l in listeners) runCatching { l.onDeviceVolumeChanged(volume, muted) }
    }

    override fun addListener(listener: Player.Listener) {
        super.addListener(listener)
        listeners.add(listener)
    }

    override fun removeListener(listener: Player.Listener) {
        super.removeListener(listener)
        listeners.remove(listener)
    }

    companion object {
        const val MAX_VOLUME = 20

        @Suppress("DEPRECATION")
        private val VOLUME_COMMANDS = intArrayOf(
            Player.COMMAND_SET_SHUFFLE_MODE,
            Player.COMMAND_GET_DEVICE_VOLUME,
            Player.COMMAND_SET_DEVICE_VOLUME,
            Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS,
            Player.COMMAND_ADJUST_DEVICE_VOLUME,
            Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS
        )
    }
}
