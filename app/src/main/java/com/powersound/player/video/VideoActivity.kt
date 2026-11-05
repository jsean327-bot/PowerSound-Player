package com.powersound.player.video

import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.provider.MediaStore
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.powersound.player.PlaybackService
import com.powersound.player.R
import com.powersound.player.ui.PowerSoundTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * The video player: videos from Library → Videos, and "Open with" / "External player" from other apps
 * (file managers, anime and movie apps). Works like MX Player and VLC for other apps: it reads the title,
 * start position, request headers and subtitles they send, and gives back where you stopped.
 */
@OptIn(UnstableApi::class)
class VideoActivity : ComponentActivity() {

    companion object {
        const val EXTRA_IDS = "com.powersound.player.video.IDS"
        const val EXTRA_INDEX = "com.powersound.player.video.INDEX"
        const val EXTRA_TITLES = "com.powersound.player.video.TITLES"
        const val EXTRA_PATHS = "com.powersound.player.video.PATHS"
        private const val ACTION_PIP = "com.powersound.player.video.PIP_PLAY_PAUSE"

        /** Opens [videos] (a folder) at [index]. */
        fun open(context: Context, videos: List<Video>, index: Int) {
            context.startActivity(
                Intent(context, VideoActivity::class.java)
                    .putExtra(EXTRA_IDS, videos.map { it.id }.toLongArray())
                    .putExtra(EXTRA_INDEX, index)
                    .putExtra(EXTRA_TITLES, videos.map { it.title }.toTypedArray())
                    .putExtra(EXTRA_PATHS, videos.map { it.path }.toTypedArray())
            )
        }
    }

    lateinit var player: ExoPlayer
        private set
    private var request: VideoRequest? = null
    private var ended = false

    /** True while the video is in its small floating window. */
    val inPip = mutableStateOf(false)

    /** A playback problem to show on screen. */
    val error = mutableStateOf<String?>(null)

    /** Rotation chosen with the rotate button: null = follow the video's shape. */
    var userOrientation: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // opened straight from another app: the settings may not be loaded yet
        com.powersound.player.data.SettingsStore.init(this)
        val req = VideoRequest.from(this, intent)
        if (req == null) {
            finish()
            return
        }
        request = req
        goFullScreen()
        // the music stops while a video plays (unless it is playing on a Chromecast)
        PlaybackService.instance?.pauseForVideo()

        player = buildPlayer(req.headers)
        player.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) = followVideoShape(videoSize)

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                error.value = null
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                    // the previous episode was watched to the end
                    req.keys.getOrNull(player.currentMediaItemIndex - 1)?.let { VideoStore.savePosition(this@VideoActivity, it, 0L, 0L) }
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    ended = true
                    currentKey()?.let { VideoStore.savePosition(this@VideoActivity, it, 0L, 0L) }
                    if (req.returnResult) finishWithResult("playback_completion")
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) savePosition()
                updatePipActions()
            }

            override fun onPlayerError(e: PlaybackException) {
                error.value = explain(e)
            }
        })
        player.setMediaItems(req.items, req.startIndex, req.startPositionMs)
        player.prepare()
        player.playWhenReady = true

        // where you are is saved every few seconds too, in case the phone kills the app
        lifecycleScope.launch {
            while (true) {
                delay(10_000)
                if (player.isPlaying) savePosition()
            }
        }

        setContent {
            PowerSoundTheme {
                VideoScreen(
                    activity = this,
                    player = player,
                    onBack = { leave() }
                )
            }
        }
        registerPipReceiver()
    }

    private fun buildPlayer(headers: Map<String, String>): ExoPlayer {
        val ua = headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value
            ?: "Mozilla/5.0 (Linux; Android ${Build.VERSION.RELEASE}) PowerSound"
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(ua)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
            .setDefaultRequestProperties(headers.filterKeys { !it.equals("User-Agent", true) })
        val sources = DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http))
        val renderers = DefaultRenderersFactory(this).setEnableDecoderFallback(true)
        return ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(sources)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
    }

    private fun explain(e: PlaybackException): String = when (e.errorCode) {
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
            "The server refused the video (it may need the app that sent it, or the link expired)."
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "No connection to the video. Check the internet."
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "The video file wasn't found."
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> "PowerSound isn't allowed to open this file."
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ->
            "This phone can't decode this video's format."
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> "This video file isn't supported or is damaged."
        else -> "Can't play this video (${e.errorCodeName})."
    }

    // ---------------------------------------------------------------- screen

    private fun goFullScreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goFullScreen()
    }

    /** Wide videos turn the phone sideways, tall ones keep it upright (until the rotate button is used). */
    private fun followVideoShape(size: VideoSize) {
        if (userOrientation != null || size.width <= 0 || size.height <= 0) return
        val wide = size.width * size.pixelWidthHeightRatio > size.height
        requestedOrientation = if (wide) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        updatePipActions()
    }

    /** Rotate button: sideways ↔ upright. */
    fun rotate() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val next = if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        userOrientation = next
        requestedOrientation = next
    }

    /** Screen brightness for this video only (0…1), or -1 for the phone's own. */
    fun setBrightness(value: Float) {
        window.attributes = window.attributes.apply { screenBrightness = value }
    }

    fun brightness(): Float {
        val own = window.attributes.screenBrightness
        if (own >= 0f) return own
        return runCatching {
            android.provider.Settings.System.getInt(contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS) / 255f
        }.getOrDefault(0.5f)
    }

    // ---------------------------------------------------------------- subtitles added by hand

    /** Adds a subtitle file (picked with the file picker) to the video that is playing, and turns it on. */
    fun addSubtitle(uri: Uri, name: String?) {
        val i = player.currentMediaItemIndex
        val item = player.currentMediaItem ?: return
        val pos = player.currentPosition
        val sub = VideoRequest.subtitle(uri, name ?: uri.lastPathSegment, selected = true)
        val subs = item.localConfiguration?.subtitleConfigurations.orEmpty().map {
            it.buildUpon().setSelectionFlags(0).build()
        } + sub
        player.replaceMediaItem(i, item.buildUpon().setSubtitleConfigurations(subs).build())
        player.seekTo(i, pos)
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .build()
    }

    // ---------------------------------------------------------------- where you stopped

    private fun currentKey(): String? = request?.keys?.getOrNull(player.currentMediaItemIndex)

    private fun savePosition() {
        if (!::player.isInitialized || ended) return
        val key = currentKey() ?: return
        val d = player.duration
        if (d == C.TIME_UNSET || d <= 0) return
        VideoStore.savePosition(this, key, player.currentPosition, d)
    }

    // ---------------------------------------------------------------- leaving, result for the app that sent the video

    fun leave() = finishWithResult("user")

    private fun finishWithResult(endBy: String) {
        val req = request
        if (req != null && ::player.isInitialized) {
            val pos = if (endBy == "playback_completion") player.duration.coerceAtLeast(0L) else player.currentPosition
            val dur = player.duration.coerceAtLeast(0L)
            val data = Intent()
                // MX Player style
                .putExtra("end_by", endBy)
                .putExtra("position", pos.toInt())
                .putExtra("duration", dur.toInt())
                // VLC style
                .putExtra("extra_position", pos)
                .putExtra("extra_duration", dur)
            req.items.getOrNull(player.currentMediaItemIndex)?.localConfiguration?.uri?.let { data.setData(it) }
            setResult(Activity.RESULT_OK, data)
        }
        savePosition()
        finish()
    }

    override fun onStop() {
        super.onStop()
        // left the app (not to the floating window): pause and remember the spot
        if (::player.isInitialized && !isInPictureInPictureModeCompat()) {
            player.pause()
            savePosition()
        }
    }

    override fun onDestroy() {
        pipReceiver?.let { runCatching { unregisterReceiver(it) } }
        if (::player.isInitialized) {
            savePosition()
            player.release()
        }
        super.onDestroy()
    }

    // ---------------------------------------------------------------- floating window (picture in picture)

    private var pipReceiver: BroadcastReceiver? = null

    private fun isInPictureInPictureModeCompat() = Build.VERSION.SDK_INT >= 24 && isInPictureInPictureMode

    fun canPip(): Boolean = Build.VERSION.SDK_INT >= 26 &&
        packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)

    fun enterPip() {
        if (!canPip() || !::player.isInitialized) return
        runCatching { enterPictureInPictureMode(pipParams()) }
    }

    private fun pipParams(): PictureInPictureParams {
        val size = player.videoSize
        var w = if (size.width > 0) (size.width * size.pixelWidthHeightRatio).toInt() else 16
        var h = if (size.height > 0) size.height else 9
        // Android allows 1:2.39 … 2.39:1
        if (w > h * 239 / 100) w = h * 239 / 100
        if (h > w * 239 / 100) h = w * 239 / 100
        val b = PictureInPictureParams.Builder().setAspectRatio(Rational(maxOf(1, w), maxOf(1, h)))
        val playing = player.playWhenReady
        val action = RemoteAction(
            Icon.createWithResource(this, if (playing) R.drawable.ic_w_pause else R.drawable.ic_w_play),
            if (playing) "Pause" else "Play",
            if (playing) "Pause" else "Play",
            PendingIntent.getBroadcast(
                this, 31, Intent(ACTION_PIP).setPackage(packageName),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        )
        b.setActions(listOf(action))
        return b.build()
    }

    private fun updatePipActions() {
        if (canPip() && ::player.isInitialized) runCatching { setPictureInPictureParams(pipParams()) }
    }

    private fun registerPipReceiver() {
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (player.playWhenReady) player.pause() else player.play()
            }
        }
        ContextCompat.registerReceiver(this, r, IntentFilter(ACTION_PIP), ContextCompat.RECEIVER_NOT_EXPORTED)
        pipReceiver = r
    }

    /** Home button while a video plays: it keeps playing in a small window. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (::player.isInitialized && player.isPlaying && com.powersound.player.data.SettingsStore.value.videoAutoPip) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip.value = isInPictureInPictureMode
        // the floating window was closed (swiped away / X): stop, like leaving the video
        if (!isInPictureInPictureMode && !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
            player.pause()
            leave()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // another video was opened while this one is on screen
        val req = VideoRequest.from(this, intent) ?: return
        savePosition()
        request = req
        ended = false
        userOrientation = null
        player.setMediaItems(req.items, req.startIndex, req.startPositionMs)
        player.prepare()
        player.play()
    }
}

/** What to play, read from the intent: from the library or from another app. */
@OptIn(UnstableApi::class)
class VideoRequest(
    val items: List<MediaItem>,
    /** Where-you-stopped key of each item. */
    val keys: List<String>,
    val startIndex: Int,
    val startPositionMs: Long,
    val headers: Map<String, String>,
    val returnResult: Boolean
) {
    companion object {

        fun from(activity: Activity, intent: Intent): VideoRequest? {
            val ids = intent.getLongArrayExtra(VideoActivity.EXTRA_IDS)
            return if (ids != null && ids.isNotEmpty()) fromLibrary(activity, intent, ids) else fromOtherApp(activity, intent)
        }

        private fun fromLibrary(context: Context, intent: Intent, ids: LongArray): VideoRequest {
            val index = intent.getIntExtra(VideoActivity.EXTRA_INDEX, 0).coerceIn(0, ids.size - 1)
            val titles = intent.getStringArrayExtra(VideoActivity.EXTRA_TITLES)
            val paths = intent.getStringArrayExtra(VideoActivity.EXTRA_PATHS)
            val keys = ArrayList<String>()
            val items = ids.mapIndexed { i, id ->
                val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                keys += VideoStore.keyOf(uri)
                MediaItem.Builder()
                    .setUri(uri)
                    .setMediaId(id.toString())
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(titles?.getOrNull(i) ?: "Video").build())
                    .setSubtitleConfigurations(paths?.getOrNull(i)?.takeIf { it.isNotBlank() }?.let { sidecarSubtitles(it) }.orEmpty())
                    .build()
            }
            val start = VideoStore.position(context, keys[index])
            return VideoRequest(items, keys, index, start, emptyMap(), returnResult = false)
        }

        @Suppress("DEPRECATION")
        private fun fromOtherApp(activity: Activity, intent: Intent): VideoRequest? {
            val uri: Uri = intent.data
                ?: (intent.getParcelableExtra<Parcelable>(Intent.EXTRA_STREAM) as? Uri)
                ?: return null
            val ex = intent.extras ?: Bundle()
            val title = ex.getString("title")
                ?: ex.getString(Intent.EXTRA_TITLE)
                ?: uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.')
                ?: "Video"
            val headers = readHeaders(ex.get("headers"))
            val web = uri.scheme == "http" || uri.scheme == "https"
            val type = intent.type?.lowercase()
            val mime = when {
                type?.contains("mpegurl") == true || uri.toString().contains(".m3u8", true) -> MimeTypes.APPLICATION_M3U8
                type?.contains("dash") == true || uri.toString().contains(".mpd", true) -> MimeTypes.APPLICATION_MPD
                else -> null
            }

            // subtitles sent along (MX Player: "subs", "subs.name", "subs.enable"; VLC: "subtitles_location")
            val subs = ArrayList<MediaItem.SubtitleConfiguration>()
            val subUris = (ex.get("subs") as? Array<*>)?.filterIsInstance<Uri>().orEmpty()
            val subNames = ex.getStringArray("subs.name")
            val enable = (ex.get("subs.enable") as? Array<*>)?.filterIsInstance<Uri>().orEmpty().toSet()
            subUris.forEachIndexed { i, s ->
                subs += subtitle(s, subNames?.getOrNull(i), selected = s in enable || (enable.isEmpty() && i == 0))
            }
            ex.getString("subtitles_location")?.let { loc ->
                val s = if (loc.contains("://")) Uri.parse(loc) else Uri.fromFile(File(loc))
                subs += subtitle(s, null, selected = subs.isEmpty())
            }
            if (!web && uri.scheme == "file") uri.path?.let { subs += sidecarSubtitles(it) }

            val item = MediaItem.Builder()
                .setUri(uri)
                .setMimeType(mime)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
                .setSubtitleConfigurations(subs)
                .build()
            // stream addresses change every time: anime / movie apps are remembered by their title
            val key = if (web && (ex.getString("title") != null || ex.getString(Intent.EXTRA_TITLE) != null)) "t:$title"
            else VideoStore.keyOf(uri)
            val fromStart = ex.getBoolean("from_start", false)
            val sent = (ex.get("position") as? Number)?.toLong()?.takeIf { it > 0 }
            val start = when {
                fromStart -> 0L
                sent != null -> sent
                else -> VideoStore.position(activity, key)
            }
            return VideoRequest(
                listOf(item), listOf(key), 0, start, headers,
                returnResult = ex.getBoolean("return_result", false) || activity.callingActivity != null
            )
        }

        /** Headers as MX Player gets them (["Referer", "https://…", "Cookie", "…"]), or as a Bundle / Map. */
        private fun readHeaders(raw: Any?): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            when (raw) {
                is Array<*> -> {
                    val list = raw.map { it?.toString() ?: "" }
                    var i = 0
                    while (i + 1 < list.size) {
                        if (list[i].isNotBlank()) out[list[i]] = list[i + 1]
                        i += 2
                    }
                }
                is Bundle -> raw.keySet().forEach { k -> raw.getString(k)?.let { out[k] = it } }
                is Map<*, *> -> raw.forEach { (k, v) -> if (k != null && v != null) out[k.toString()] = v.toString() }
            }
            return out
        }

        fun subtitle(uri: Uri, name: String?, selected: Boolean): MediaItem.SubtitleConfiguration {
            val probe = (name ?: "") + " " + uri.toString()
            val mime = when {
                probe.contains(".ass", true) || probe.contains(".ssa", true) -> MimeTypes.TEXT_SSA
                probe.contains(".vtt", true) || probe.contains("vtt", true) -> MimeTypes.TEXT_VTT
                probe.contains(".ttml", true) || probe.contains(".dfxp", true) -> MimeTypes.APPLICATION_TTML
                else -> MimeTypes.APPLICATION_SUBRIP
            }
            return MediaItem.SubtitleConfiguration.Builder(uri)
                .setMimeType(mime)
                .setLabel(name?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: "Subtitles")
                .setSelectionFlags(if (selected) C.SELECTION_FLAG_DEFAULT else 0)
                .build()
        }

        /** "Episode 1.srt" / ".ass" / ".vtt" next to "Episode 1.mkv" (needs All Files Access to be read). */
        fun sidecarSubtitles(videoPath: String): List<MediaItem.SubtitleConfiguration> {
            val f = File(videoPath)
            val dir = f.parentFile ?: return emptyList()
            val base = f.name.substringBeforeLast('.')
            val found = runCatching {
                dir.listFiles { x ->
                    val n = x.name
                    n.startsWith(base) && n.substringAfterLast('.').lowercase() in setOf("srt", "ass", "ssa", "vtt")
                }?.filter { it.canRead() }.orEmpty()
            }.getOrDefault(emptyList())
            return found.sortedBy { it.name.length }.mapIndexed { i, s ->
                subtitle(Uri.fromFile(s), s.name.removePrefix(base).trim('.', ' ', '_', '-').ifBlank { s.name }, selected = i == 0)
            }
        }
    }
}
