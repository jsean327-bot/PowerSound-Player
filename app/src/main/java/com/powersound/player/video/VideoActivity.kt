package com.powersound.player.video

import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.provider.MediaStore
import android.util.Rational
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.ui.PlayerView
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.powersound.player.PlaybackService
import com.powersound.player.R
import com.powersound.player.cast.CastControl
import com.powersound.player.cast.MediaServer
import com.powersound.player.cast.ServedFile
import com.powersound.player.data.SettingsStore
import com.powersound.player.ui.PowerSoundTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Video decoders: hardware, hardware with fallback (and faster codec feeding), software. */
val DECODERS = listOf("HW", "HW+", "SW")
val DECODER_NAMES = listOf(
    "HW decoder" to "The phone's video chip: smooth, saves battery",
    "HW+ decoder" to "Hardware, and when it fails on a file, software takes over",
    "SW decoder" to "Software: plays odd files the chip refuses, uses more battery"
)

/** Rotation choices (⋮ → Screen Rotation). */
val ROTATIONS = listOf("By the video", "Auto (turn with the phone)", "Landscape", "Portrait")

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

    /** The player (it is rebuilt when the decoder changes, so the screen follows this). */
    val playerState = mutableStateOf<ExoPlayer?>(null)
    val player: ExoPlayer get() = playerState.value!!
    fun playerOrNull(): ExoPlayer? = playerState.value

    private var request: VideoRequest? = null
    private var keys: MutableList<String> = ArrayList()
    private var ended = false
    private var headers: Map<String, String> = emptyMap()
    private var audioSessionId = C.AUDIO_SESSION_ID_UNSET

    /** The picture view (for screenshots). */
    var playerView: PlayerView? = null

    // ---- what the screen shows
    val inPip = mutableStateOf(false)
    val error = mutableStateOf<String?>(null)
    val night = mutableStateOf(false)
    val muted = mutableStateOf(false)
    val stereoMode = mutableIntStateOf(0)
    val audioDelayMs = mutableIntStateOf(0)
    val abA = mutableStateOf<Long?>(null)
    val abB = mutableStateOf<Long?>(null)
    /** Sleep timer: the time it stops (ms), -1 = at the end of this video, null = off. */
    val sleepAt = mutableStateOf<Long?>(null)
    val rotation = mutableIntStateOf(0)
    val castingTo = mutableStateOf<String?>(null)
    val castPosition = mutableLongStateOf(0L)
    val castDuration = mutableLongStateOf(0L)
    val castPlaying = mutableStateOf(false)

    /** ⋮ → Aspect Ratio for this video (0 default … 7 custom). */
    val aspect = mutableIntStateOf(0)

    private val stereo = StereoModeProcessor()
    private var reverb: android.media.audiofx.PresetReverb? = null
    var equalizer: Equalizer? = null
        private set
    var bassBoost: BassBoost? = null
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // opened straight from another app: the settings may not be loaded yet
        SettingsStore.init(this)
        VideoPrefsStore.init(this)
        // opened from a file manager / another app: crashes are saved too (Settings → Misc → View error log)
        com.powersound.player.data.CrashLog.install(this)
        com.powersound.player.data.CrashLog.watchMainThread(this)
        val req = VideoRequest.from(this, intent)
        if (req == null) {
            finish()
            return
        }
        request = req
        keys = req.keys.toMutableList()
        headers = req.headers
        goFullScreen()
        VideoPrefsStore.value.let { vp ->
            // Display Settings → Screen → Orientation (default: turn with the phone)
            if (vp.orientation != 0) setRotationMode(vp.orientation) else rotation.intValue = 0
            // Display Settings → Screen → Brightness
            if (vp.brightnessOn) setBrightness((vp.brightnessLevel / 15f).coerceIn(0.02f, 1f))
            aspect.intValue = if (vp.aspectAll) vp.aspect else 0
        }
        // the music stops while a video plays (unless it is playing on a Chromecast)
        PlaybackService.instance?.pauseForVideo()
        audioSessionId = runCatching { Util.generateAudioSessionIdV21(this) }.getOrDefault(C.AUDIO_SESSION_ID_UNSET)

        val p = buildPlayer()
        playerState.value = p
        attachEffects()
        startRequest(req)

        lifecycleScope.launch {
            var tick = 0
            while (isActive) {
                delay(100)
                tick++
                val q = playerOrNull() ?: continue
                // A-B repeat
                val a = abA.value
                val b = abB.value
                if (a != null && b != null && q.currentPosition >= b) q.seekTo(a)
                // where you are is saved every 10 seconds too, in case the phone kills the app
                if (tick % 100 == 0 && q.isPlaying) savePosition()
            }
        }

        setContent {
            PowerSoundTheme {
                VideoScreen(activity = this, onBack = { leave() })
            }
        }
        runCatching { registerPipReceiver() }
        runCatching { setupCast() }
    }

    // ---------------------------------------------------------------- the player

    /** Plays a request; library folders first get their subtitle files (read in the background). */
    private fun startRequest(req: VideoRequest) {
        val p = playerOrNull() ?: return
        if (req.paths.isEmpty()) {
            p.setMediaItems(req.items, req.startIndex, req.startPositionMs)
            p.prepare()
            if (!recastIfCasting()) p.play()
            return
        }
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) {
                runCatching { VideoRequest.withSidecars(req.items, req.paths) }.getOrDefault(req.items)
            }
            val q = playerOrNull() ?: return@launch
            if (request !== req) return@launch
            q.setMediaItems(items, req.startIndex, req.startPositionMs)
            q.prepare()
            if (!recastIfCasting()) q.play()
        }
    }

    /** Casting and a new video came (another episode from the app): it goes to the TV instead of the phone. */
    private fun recastIfCasting(): Boolean {
        if (castingTo.value == null) return false
        val session = CastControl.context(this)?.sessionManager?.currentCastSession
        if (session == null || !session.isConnected) return false
        CastControl.videoOwnsCast = true
        castVideo(session)
        return true
    }

    private val listener = object : Player.Listener {
        override fun onVideoSizeChanged(videoSize: VideoSize) = followVideoShape(videoSize)

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            error.value = null
            abA.value = null
            abB.value = null
            if (!VideoPrefsStore.value.aspectAll) aspect.intValue = 0
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                // the previous episode was watched to the end
                keys.getOrNull(player.currentMediaItemIndex - 1)?.let { VideoStore.savePosition(this@VideoActivity, it, 0L, 0L) }
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                ended = true
                currentKey()?.let { VideoStore.savePosition(this@VideoActivity, it, 0L, 0L) }
                if (request?.returnResult == true) finishWithResult("playback_completion")
            } else ended = false
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            // "end of this video" sleep timer did its job
            if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM && sleepAt.value == -1L) {
                sleepAt.value = null
                playerOrNull()?.pauseAtEndOfMediaItems = false
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) savePosition()
            updatePipActions()
            VideoBackgroundService.refresh()
        }

        override fun onTracksChanged(tracks: Tracks) {
            if (pendingExternalAudio) selectExternalAudio(tracks)
        }

        override fun onPlayerError(e: PlaybackException) {
            error.value = explain(e)
        }
    }

    private fun buildPlayer(): ExoPlayer {
        val st = SettingsStore.value
        val decoder = st.videoDecoder.coerceIn(0, 2)
        val ua = headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value
            ?: "Mozilla/5.0 (Linux; Android ${Build.VERSION.RELEASE}) PowerSound"
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(ua)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
            .setDefaultRequestProperties(headers.filterKeys { !it.equals("User-Agent", true) })
        dataSources = DefaultDataSource.Factory(this, http)
        val swAudio = st.videoSwAudio
        // HW: the phone's chip first; SW: software first (for the picture, and for the sound when chosen)
        val selector = MediaCodecSelector { mime, secure, tunneling ->
            val list = MediaCodecUtil.getDecoderInfos(mime, secure, tunneling)
            val software = if (mime.startsWith("audio/")) swAudio else decoder == 2
            if (software) list.sortedBy { if (it.softwareOnly) 0 else 1 } else list
        }
        val delayState = audioDelayMs
        val stereoProcessor = stereo
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink {
                val sink = DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf(stereoProcessor))
                    .build()
                return DelayedAudioSink(sink) { delayState.intValue * 1000L }
            }
        }
        renderers.setMediaCodecSelector(selector)
        renderers.setEnableDecoderFallback(decoder != 0)
        if (decoder == 1) renderers.forceEnableMediaCodecAsynchronousQueueing()
        val p = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSources!!))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        if (audioSessionId != C.AUDIO_SESSION_ID_UNSET) p.setAudioSessionId(audioSessionId)
        p.volume = if (muted.value) 0f else 1f
        p.addListener(listener)
        return p
    }

    private var dataSources: DefaultDataSource.Factory? = null

    /** HW / HW+ / SW, or "Use SW audio decoder": the player is built again at the same spot. */
    fun rebuildPlayer() {
        val old = playerOrNull() ?: return
        val items = (0 until old.mediaItemCount).map { old.getMediaItemAt(it) }
        val index = old.currentMediaItemIndex
        val pos = old.currentPosition
        val play = old.playWhenReady
        val params = old.trackSelectionParameters
        val speed = old.playbackParameters
        val repeat = old.repeatMode
        val shuffle = old.shuffleModeEnabled
        savePosition()
        old.removeListener(listener)
        old.release()
        val p = buildPlayer()
        p.trackSelectionParameters = params
        p.playbackParameters = speed
        p.repeatMode = repeat
        p.shuffleModeEnabled = shuffle
        if (items.isNotEmpty()) p.setMediaItems(items, index, pos)
        p.prepare()
        p.playWhenReady = play
        error.value = null
        playerState.value = p
        attachEffects()
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
            "This phone can't decode this video's format. Try another decoder (the HW button at the top)."
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> "This video file isn't supported or is damaged."
        else -> "Can't play this video (${e.errorCodeName})."
    }

    fun togglePlay() {
        val p = playerOrNull() ?: return
        if (p.isPlaying) p.pause()
        else {
            if (p.playbackState == Player.STATE_ENDED) p.seekTo(0L)
            if (p.playbackState == Player.STATE_IDLE) p.prepare()
            p.play()
        }
    }

    // ---------------------------------------------------------------- tools (⋮)

    fun setMuted(on: Boolean) {
        muted.value = on
        playerOrNull()?.volume = if (on) 0f else 1f
    }

    /** Loop: off → this video → the whole list. */
    fun cycleLoop(): Int {
        val p = playerOrNull() ?: return Player.REPEAT_MODE_OFF
        p.repeatMode = when (p.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ONE
            Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ALL
            else -> Player.REPEAT_MODE_OFF
        }
        return p.repeatMode
    }

    /** A-B repeat: first press marks A, second marks B (the part between them repeats), third clears. */
    fun abPress(): String {
        val p = playerOrNull() ?: return ""
        val pos = p.currentPosition
        return when {
            abA.value == null -> { abA.value = pos; "A set — press again at the end" }
            abB.value == null -> {
                if (pos <= (abA.value ?: 0L) + 500) { abA.value = null; "A-B repeat off" }
                else { abB.value = pos; p.seekTo(abA.value ?: 0L); "Repeating A ↔ B" }
            }
            else -> { abA.value = null; abB.value = null; "A-B repeat off" }
        }
    }

    private var sleepJob: Job? = null

    /** Sleep timer: [minutes] > 0, 0 off, -1 at the end of this video. The sound fades out over the last 10 seconds. */
    fun setSleep(minutes: Int) {
        sleepJob?.cancel()
        val p = playerOrNull() ?: return
        p.pauseAtEndOfMediaItems = minutes == -1
        when {
            minutes == 0 -> sleepAt.value = null
            minutes == -1 -> sleepAt.value = -1L
            else -> {
                val ms = minutes * 60_000L
                sleepAt.value = System.currentTimeMillis() + ms
                sleepJob = lifecycleScope.launch {
                    delay(ms - 10_000)
                    for (i in 19 downTo 0) {
                        if (!muted.value) playerOrNull()?.volume = i / 20f
                        delay(500)
                    }
                    playerOrNull()?.pause()
                    playerOrNull()?.volume = if (muted.value) 0f else 1f
                    sleepAt.value = null
                    savePosition()
                }
            }
        }
    }

    fun setStereoMode(mode: Int) {
        stereoMode.intValue = mode
        stereo.mode = mode
    }

    /** Synchronization: + makes the sound later, − earlier (milliseconds). */
    fun setAudioDelay(ms: Int) {
        audioDelayMs.intValue = ms.coerceIn(-5000, 5000)
    }

    fun setRotationMode(mode: Int) {
        rotation.intValue = mode
        requestedOrientation = when (mode) {
            1 -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
            2 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            3 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else -> {
                playerOrNull()?.videoSize?.let { followVideoShape(it, force = true) }
                requestedOrientation
            }
        }
    }

    /** Takes a picture of the video (no controls) into Pictures/PowerSound. */
    fun screenshot(done: (String) -> Unit) {
        (playerView?.videoSurfaceView as? android.view.TextureView)?.let { tv ->
            if (Build.VERSION.SDK_INT < 29) { done("Screenshots need Android 10 or newer"); return }
            val size = playerOrNull()?.videoSize
            val bmp = runCatching {
                if (size != null && size.width > 0 && size.height > 0) tv.getBitmap(size.width, size.height) else tv.bitmap
            }.getOrNull()
            if (bmp == null) { done("No picture to capture"); return }
            saveScreenshot(bmp, done)
            return
        }
        val sv = playerView?.videoSurfaceView as? SurfaceView
        val size = playerOrNull()?.videoSize
        if (sv == null || size == null || size.width <= 0 || size.height <= 0) {
            done("No picture to capture")
            return
        }
        if (Build.VERSION.SDK_INT < 29) {
            done("Screenshots need Android 10 or newer")
            return
        }
        val bmp = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
        PixelCopy.request(sv, bmp, { result ->
            if (result != PixelCopy.SUCCESS) {
                done("Couldn't capture the picture")
                return@request
            }
            saveScreenshot(bmp, done)
        }, Handler(Looper.getMainLooper()))
    }

    private fun saveScreenshot(bmp: Bitmap, done: (String) -> Unit) {
            val title = (playerOrNull()?.mediaMetadata?.title?.toString() ?: "video").replace(Regex("[\\\\/:*?\"<>|]"), "_").take(60)
            lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        val values = ContentValues().apply {
                            put(MediaStore.Images.Media.DISPLAY_NAME, "${title}_${System.currentTimeMillis()}.jpg")
                            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PowerSound")
                        }
                        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@runCatching false
                        contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) } ?: false
                    }.getOrDefault(false)
                }
                done(if (ok) "Saved in Pictures/PowerSound" else "Couldn't save the picture")
            }
    }

    // ---------------------------------------------------------------- audio: external file, tracks, equalizer

    private var pendingExternalAudio = false

    /** Audio → Open: plays another sound file (another language, a better track) with this video. */
    fun openExternalAudio(uri: Uri) {
        val p = playerOrNull() ?: return
        val item = p.currentMediaItem ?: return
        val f = DefaultMediaSourceFactory(dataSources ?: return)
        val pos = p.currentPosition
        val key = currentKey()
        val merged = MergingMediaSource(f.createMediaSource(item), f.createMediaSource(MediaItem.fromUri(uri)))
        pendingExternalAudio = true
        p.setMediaSource(merged, pos)
        p.prepare()
        p.play()
        keys = mutableListOf(key ?: VideoStore.keyOf(uri))
    }

    private fun selectExternalAudio(tracks: Tracks) {
        val groups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        if (groups.isEmpty()) return
        pendingExternalAudio = false
        val g = groups.last()
        val p = playerOrNull() ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, 0))
            .build()
    }

    /** The equalizer and bass boost (⋮ → Equalizer) on this video's sound. */
    private fun attachEffects() {
        runCatching { equalizer?.release() }
        runCatching { bassBoost?.release() }
        equalizer = null
        bassBoost = null
        if (audioSessionId == C.AUDIO_SESSION_ID_UNSET) return
        val st = SettingsStore.value
        equalizer = runCatching {
            Equalizer(0, audioSessionId).apply {
                val levels = st.videoEqBands.split(',').mapNotNull { it.trim().toIntOrNull() }
                for (b in 0 until numberOfBands.toInt()) {
                    levels.getOrNull(b)?.let { setBandLevel(b.toShort(), it.toShort()) }
                }
                enabled = st.videoEqOn
            }
        }.getOrNull()
        bassBoost = runCatching {
            BassBoost(0, audioSessionId).apply {
                if (strengthSupported) setStrength(st.videoBass.coerceIn(0, 1000).toShort())
                enabled = st.videoEqOn && st.videoBass > 0
            }
        }.getOrNull()
        runCatching { reverb?.release() }
        reverb = null
        setReverb(VideoPrefsStore.value.reverb)
    }

    /** Equalizer → Reverb: 0 none, 1 small room, 2 medium room, 3 large room, 4 medium hall, 5 large hall, 6 plate. */
    fun setReverb(preset: Int) {
        VideoPrefsStore.update { it.copy(reverb = preset) }
        if (audioSessionId == C.AUDIO_SESSION_ID_UNSET) return
        if (preset == 0) {
            runCatching { reverb?.enabled = false }
            return
        }
        runCatching {
            val r = reverb ?: android.media.audiofx.PresetReverb(0, audioSessionId).also { reverb = it }
            r.preset = preset.toShort()
            r.enabled = true
        }
    }

    /** Saves the equalizer as it is now (it is used again for the next videos). */
    fun saveEqualizer(on: Boolean, bass: Int) {
        val eq = equalizer
        val bands = if (eq != null) (0 until eq.numberOfBands.toInt()).joinToString(",") { eq.getBandLevel(it.toShort()).toString() } else ""
        SettingsStore.update { it.copy(videoEqOn = on, videoEqBands = bands, videoBass = bass) }
        runCatching { eq?.enabled = on }
        runCatching {
            bassBoost?.let { b ->
                if (b.strengthSupported) b.setStrength(bass.coerceIn(0, 1000).toShort())
                b.enabled = on && bass > 0
            }
        }
    }

    // ---------------------------------------------------------------- screen

    private fun goFullScreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        applyBars(uiVisible)
    }

    private var uiVisible = false

    /**
     * Display Settings → Screen → Full Screen / Soft buttons: the status bar and the navigation bar show with
     * the controls ("auto"), never, or always.
     */
    fun applyBars(controlsVisible: Boolean) {
        uiVisible = controlsVisible
        val p = VideoPrefsStore.value
        val status = when (p.fullScreen) { 1 -> false; 2 -> true; else -> controlsVisible }
        val nav = when (p.softButtons) { 1 -> false; 2 -> true; else -> controlsVisible }
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (status) show(WindowInsetsCompat.Type.statusBars()) else hide(WindowInsetsCompat.Type.statusBars())
            if (nav) show(WindowInsetsCompat.Type.navigationBars()) else hide(WindowInsetsCompat.Type.navigationBars())
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyBars(uiVisible)
    }

    /** Wide videos turn the phone sideways, tall ones keep it upright (rotation "By the video"). */
    private fun followVideoShape(size: VideoSize, force: Boolean = false) {
        if ((rotation.intValue != 0 && !force) || size.width <= 0 || size.height <= 0) return
        val wide = size.width * size.pixelWidthHeightRatio > size.height
        requestedOrientation = if (wide) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        updatePipActions()
    }

    /** Rotate button: sideways ↔ upright. */
    fun rotate() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        setRotationMode(if (landscape) 3 else 2)
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
        val p = playerOrNull() ?: return
        val i = p.currentMediaItemIndex
        val item = p.currentMediaItem ?: return
        val pos = p.currentPosition
        val sub = VideoRequest.subtitle(uri, name ?: uri.lastPathSegment, selected = true)
        val subs = item.localConfiguration?.subtitleConfigurations.orEmpty().map {
            it.buildUpon().setSelectionFlags(0).build()
        } + sub
        p.replaceMediaItem(i, item.buildUpon().setSubtitleConfigurations(subs).build())
        p.seekTo(i, pos)
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .build()
    }

    // ---------------------------------------------------------------- Chromecast

    private var castServer: MediaServer? = null
    private val castFiles = HashMap<Long, Uri>()
    private var castListener: SessionManagerListener<CastSession>? = null
    private var castPoll: Job? = null

    /** True when this phone can cast at all (Google Play services). */
    fun canCast(): Boolean = CastControl.context(this) != null

    private fun setupCast() {
        val ctx = CastControl.context(this) ?: return
        val l = object : SessionManagerListener<CastSession> {
            override fun onSessionStarted(session: CastSession, sessionId: String) {
                if (CastControl.videoOwnsCast) castVideo(session)
            }

            override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
                if (CastControl.videoOwnsCast) castVideo(session)
            }

            override fun onSessionEnded(session: CastSession, error: Int) = backFromCast()
            override fun onSessionSuspended(session: CastSession, reason: Int) = Unit
            override fun onSessionStarting(session: CastSession) = Unit
            override fun onSessionStartFailed(session: CastSession, error: Int) {
                CastControl.videoOwnsCast = false
            }
            override fun onSessionEnding(session: CastSession) = Unit
            override fun onSessionResuming(session: CastSession, sessionId: String) = Unit
            override fun onSessionResumeFailed(session: CastSession, error: Int) = Unit
        }
        runCatching { ctx.sessionManager.addSessionManagerListener(l, CastSession::class.java) }
        castListener = l
    }

    /**
     * The Cast button. Already connected to a Chromecast (music playing there, say): the video goes there now.
     * Otherwise returns false and the screen shows the list of Chromecasts; picking one sends the video.
     */
    fun castButton(): Boolean {
        CastControl.videoOwnsCast = true
        val session = CastControl.context(this)?.sessionManager?.currentCastSession
        if (session != null && session.isConnected) {
            castVideo(session)
            return true
        }
        return false
    }

    private fun castVideo(session: CastSession) {
        val p = playerOrNull() ?: return
        val item = p.currentMediaItem ?: return
        val uri = item.localConfiguration?.uri ?: return
        val web = uri.scheme == "http" || uri.scheme == "https"
        val url: String
        val mime: String
        if (web) {
            url = uri.toString()
            mime = item.localConfiguration?.mimeType
                ?: when {
                    url.contains(".m3u8", true) -> "application/x-mpegURL"
                    url.contains(".mpd", true) -> "application/dash+xml"
                    url.contains(".webm", true) -> "video/webm"
                    else -> "video/mp4"
                }
        } else {
            val server = castServer ?: MediaServer(openSong = { id -> openForCast(id) }, cover = { null }).also { castServer = it }
            server.start()
            val id = (castFiles.keys.maxOrNull() ?: 0L) + 1
            castFiles[id] = uri
            url = server.songUrl(id) ?: run {
                castError("The phone needs to be on Wi-Fi to cast its videos")
                return
            }
            mime = runCatching { contentResolver.getType(uri) }.getOrNull() ?: "video/mp4"
        }
        val md = com.google.android.gms.cast.MediaMetadata(com.google.android.gms.cast.MediaMetadata.MEDIA_TYPE_MOVIE)
        md.putString(com.google.android.gms.cast.MediaMetadata.KEY_TITLE, item.mediaMetadata.title?.toString() ?: "Video")
        val info = MediaInfo.Builder(url)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(mime)
            .setContentUrl(url)
            .setMetadata(md)
            .build()
        val load = MediaLoadRequestData.Builder()
            .setMediaInfo(info)
            .setAutoplay(true)
            .setCurrentTime(p.currentPosition)
            .build()
        val client = session.remoteMediaClient ?: return
        runCatching { client.load(load) }.onFailure { castError("The Chromecast didn't accept the video"); return }
        p.pause()
        val name = session.castDevice?.friendlyName ?: "Chromecast"
        castingTo.value = name
        CastControl.setDevice(name)
        castPoll?.cancel()
        castPoll = lifecycleScope.launch {
            while (isActive) {
                val c = CastControl.context(this@VideoActivity)?.sessionManager?.currentCastSession?.remoteMediaClient
                if (c != null) {
                    castPosition.longValue = c.approximateStreamPosition
                    castDuration.longValue = c.streamDuration.coerceAtLeast(0L)
                    if (castPlaying.value != c.isPlaying) {
                        castPlaying.value = c.isPlaying
                        VideoBackgroundService.refresh()
                    }
                }
                delay(500)
            }
        }
    }

    private fun castError(msg: String) {
        error.value = msg
        CastControl.videoOwnsCast = false
    }

    private fun openForCast(id: Long): ServedFile? = runCatching {
        val uri = castFiles[id] ?: return null
        val pfd = contentResolver.openFileDescriptor(uri, "r") ?: return null
        val stream = java.io.FileInputStream(pfd.fileDescriptor)
        val size = pfd.statSize.takeIf { it > 0 } ?: stream.channel.size()
        val mime = contentResolver.getType(uri) ?: "video/mp4"
        ServedFile(stream.channel, size, mime) {
            stream.close()
            pfd.close()
        }
    }.getOrNull()

    private fun remote() = CastControl.context(this)?.sessionManager?.currentCastSession?.remoteMediaClient

    fun castToggle() {
        val c = remote() ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    fun castSeek(ms: Long) {
        runCatching { remote()?.seek(MediaSeekOptions.Builder().setPosition(ms.coerceAtLeast(0L)).build()) }
    }

    fun stopCasting() {
        CastControl.stop(this)
    }

    /** Casting ended: the video goes on here, paused at the spot the TV reached. */
    private fun backFromCast() {
        if (castingTo.value == null) {
            CastControl.videoOwnsCast = false
            return
        }
        castPoll?.cancel()
        val at = castPosition.longValue
        castingTo.value = null
        // the casting notification goes (the screen is behind: nothing is playing any more)
        if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) VideoBackgroundService.stop(this)
        CastControl.videoOwnsCast = false
        castServer?.stop()
        playerOrNull()?.let { p ->
            if (at > 0) p.seekTo(at)
            p.pause()
        }
    }

    // ---------------------------------------------------------------- ⋮ panel: stream, information, share, bookmarks, cut

    /** ⋮ → Network Stream: plays a web address here. */
    fun playUrl(url: String) {
        val p = playerOrNull() ?: return
        val uri = Uri.parse(url.trim())
        val mime = when {
            url.contains(".m3u8", true) -> MimeTypes.APPLICATION_M3U8
            url.contains(".mpd", true) -> MimeTypes.APPLICATION_MPD
            else -> null
        }
        savePosition()
        val title = uri.lastPathSegment?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: uri.host ?: "Stream"
        keys = mutableListOf(VideoStore.keyOf(uri))
        ended = false
        p.setMediaItem(
            MediaItem.Builder().setUri(uri).setMimeType(mime)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build()).build()
        )
        p.prepare()
        p.play()
    }

    /** ⋮ → Information: the file and its picture / sound / subtitle tracks. */
    fun information(): List<Pair<String, String>> {
        val p = playerOrNull() ?: return emptyList()
        val out = ArrayList<Pair<String, String>>()
        val item = p.currentMediaItem
        val uri = item?.localConfiguration?.uri
        out += "Title" to (item?.mediaMetadata?.title?.toString() ?: "")
        uri?.let { u ->
            if (u.scheme == "content") {
                runCatching {
                    contentResolver.query(u, arrayOf(MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.SIZE), null, null, null)?.use { c ->
                        if (c.moveToFirst()) {
                            c.getString(0)?.let { out += "File" to it }
                            val size = c.getLong(1)
                            if (size > 0) out += "Size" to (if (size > 1L shl 30) "%.2f GB".format(size / (1L shl 30).toDouble()) else "${size / (1L shl 20)} MB")
                        }
                    }
                }
            } else out += "Address" to u.toString()
        }
        if (p.duration > 0) out += "Length" to com.powersound.player.data.formatTime(p.duration)
        p.videoFormat?.let { f ->
            out += "Video" to listOfNotNull(
                f.sampleMimeType?.substringAfter('/')?.uppercase(),
                if (f.width > 0) "${f.width}×${f.height}" else null,
                if (f.frameRate > 0) "%.3f fps".format(f.frameRate).replace(".000", "") else null,
                if (f.bitrate > 0) "${f.bitrate / 1000} kbps" else null,
                f.codecs
            ).joinToString(" • ")
        }
        p.videoDecoderCounters?.let { out += "Dropped frames" to it.droppedBufferCount.toString() }
        var a = 0
        var t = 0
        for (g in p.currentTracks.groups) {
            for (i in 0 until g.length) {
                val f = g.getTrackFormat(i)
                when (g.type) {
                    C.TRACK_TYPE_AUDIO -> {
                        a++
                        out += "Audio $a${if (g.isTrackSelected(i)) " (playing)" else ""}" to listOfNotNull(
                            f.language?.takeIf { it != "und" },
                            f.sampleMimeType?.substringAfter('/')?.uppercase(),
                            if (f.channelCount > 0) "${f.channelCount} ch" else null,
                            if (f.sampleRate > 0) "${f.sampleRate} Hz" else null,
                            if (f.bitrate > 0) "${f.bitrate / 1000} kbps" else null
                        ).joinToString(" • ")
                    }
                    C.TRACK_TYPE_TEXT -> {
                        t++
                        out += "Subtitle $t" to listOfNotNull(f.label, f.language, f.sampleMimeType?.substringAfter('/')).joinToString(" • ")
                    }
                }
            }
        }
        out += "Decoder" to DECODERS[SettingsStore.value.videoDecoder.coerceIn(0, 2)]
        return out
    }

    /** ⋮ → Share: the file (or the web address). */
    fun share() {
        val uri = playerOrNull()?.currentMediaItem?.localConfiguration?.uri ?: return
        val web = uri.scheme == "http" || uri.scheme == "https"
        val send = Intent(Intent.ACTION_SEND).apply {
            if (web) {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, uri.toString())
            } else {
                type = runCatching { contentResolver.getType(uri) }.getOrNull() ?: "video/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        runCatching { startActivity(Intent.createChooser(send, "Share")) }
    }

    fun bookmarks(): List<Long> = currentKey()?.let { VideoStore.bookmarks(this, it) }.orEmpty()

    fun addBookmark(): Long? {
        val key = currentKey() ?: return null
        val pos = playerOrNull()?.currentPosition ?: return null
        VideoStore.addBookmark(this, key, pos)
        return pos
    }

    fun removeBookmark(ms: Long) {
        currentKey()?.let { VideoStore.removeBookmark(this, it, ms) }
    }

    /** True when the video is a file on the phone (Cut works only on those). */
    fun isLocalFile(): Boolean = playerOrNull()?.currentMediaItem?.localConfiguration?.uri?.scheme.let { it == "content" || it == "file" }

    fun cut(startMs: Long, endMs: Long, progress: (Float) -> Unit, done: (String) -> Unit) {
        val p = playerOrNull() ?: return
        val uri = p.currentMediaItem?.localConfiguration?.uri ?: return
        val title = p.mediaMetadata.title?.toString() ?: "video"
        lifecycleScope.launch {
            val r = VideoCutter.cut(this@VideoActivity, uri, startMs, endMs, title) { f ->
                lifecycleScope.launch { progress(f) }
            }
            done(r.message)
        }
    }

    // ---------------------------------------------------------------- where you stopped

    private fun currentKey(): String? = playerOrNull()?.let { keys.getOrNull(it.currentMediaItemIndex) }

    private fun savePosition() {
        val p = playerOrNull() ?: return
        if (ended || castingTo.value != null) return
        val key = currentKey() ?: return
        val d = p.duration
        if (d == C.TIME_UNSET || d <= 0) return
        VideoStore.savePosition(this, key, p.currentPosition, d)
    }

    // ---------------------------------------------------------------- leaving, result for the app that sent the video

    /**
     * Back. While casting the screen goes behind (to the app the video came from, e.g. to pick another episode):
     * the TV keeps playing, the controls stay in the notification, and opening another video sends it to the TV.
     */
    fun leave() {
        if (castingTo.value != null) {
            moveTaskToBack(true)
            return
        }
        finishWithResult("user")
    }

    private fun finishWithResult(endBy: String) {
        val req = request
        val p = playerOrNull()
        if (req != null && p != null) {
            val pos = if (endBy == "playback_completion") p.duration.coerceAtLeast(0L) else p.currentPosition
            val dur = p.duration.coerceAtLeast(0L)
            val data = Intent()
                // MX Player style
                .putExtra("end_by", endBy)
                .putExtra("position", pos.toInt())
                .putExtra("duration", dur.toInt())
                // VLC style
                .putExtra("extra_position", pos)
                .putExtra("extra_duration", dur)
            p.currentMediaItem?.localConfiguration?.uri?.let { data.setData(it) }
            setResult(Activity.RESULT_OK, data)
        }
        savePosition()
        finish()
    }

    override fun onPause() {
        super.onPause()
        // Display Settings → Screen → Pause playback if obstructed by another window
        if (VideoPrefsStore.value.pauseObstructed && !isInPictureInPictureModeCompat() && !isChangingConfigurations &&
            !SettingsStore.value.videoBackground
        ) playerOrNull()?.pause()
        // ⋮ → Background Play: the sound goes on with a notification
        val p = playerOrNull()
        if (p != null && SettingsStore.value.videoBackground && p.isPlaying && !isChangingConfigurations &&
            !isInPictureInPictureModeCompat() && !isFinishing && castingTo.value == null
        ) VideoBackgroundService.start(this)
        // casting: the TV's controls stay in a notification while you are in another app
        if (castingTo.value != null && !isChangingConfigurations && !isFinishing) VideoBackgroundService.start(this)
    }

    override fun onResume() {
        super.onResume()
        VideoBackgroundService.stop(this)
    }

    override fun onStop() {
        super.onStop()
        // left the app (not to the floating window, not playing in the background): pause and remember the spot
        val p = playerOrNull() ?: return
        val background = SettingsStore.value.videoBackground && p.isPlaying
        if (!isInPictureInPictureModeCompat() && !background) {
            p.pause()
        }
        savePosition()
    }

    override fun onDestroy() {
        pipReceiver?.let { runCatching { unregisterReceiver(it) } }
        VideoBackgroundService.stop(this)
        castListener?.let { l -> runCatching { CastControl.context(this)?.sessionManager?.removeSessionManagerListener(l, CastSession::class.java) } }
        castPoll?.cancel()
        // the TV keeps playing the video; the phone's little server stops with this screen
        castServer?.stop()
        if (castingTo.value != null) CastControl.videoOwnsCast = false
        runCatching { equalizer?.release() }
        runCatching { bassBoost?.release() }
        runCatching { reverb?.release() }
        playerOrNull()?.let {
            savePosition()
            it.release()
        }
        super.onDestroy()
    }

    // ---------------------------------------------------------------- floating window (picture in picture)

    private var pipReceiver: BroadcastReceiver? = null

    private fun isInPictureInPictureModeCompat() = Build.VERSION.SDK_INT >= 24 && isInPictureInPictureMode

    fun canPip(): Boolean = Build.VERSION.SDK_INT >= 26 &&
        packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)

    fun enterPip() {
        if (!canPip() || playerOrNull() == null) return
        runCatching { enterPictureInPictureMode(pipParams()) }
    }

    private fun pipParams(): PictureInPictureParams {
        val p = player
        val size = p.videoSize
        var w = if (size.width > 0) (size.width * size.pixelWidthHeightRatio).toInt() else 16
        var h = if (size.height > 0) size.height else 9
        // Android allows 1:2.39 … 2.39:1
        if (w > h * 239 / 100) w = h * 239 / 100
        if (h > w * 239 / 100) h = w * 239 / 100
        val b = PictureInPictureParams.Builder().setAspectRatio(Rational(maxOf(1, w), maxOf(1, h)))
        val playing = p.playWhenReady
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
        if (canPip() && playerOrNull() != null) runCatching { setPictureInPictureParams(pipParams()) }
    }

    private fun registerPipReceiver() {
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = togglePlay()
        }
        ContextCompat.registerReceiver(this, r, IntentFilter(ACTION_PIP), ContextCompat.RECEIVER_NOT_EXPORTED)
        pipReceiver = r
    }

    /** Home button while a video plays: it keeps playing in a small window. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val p = playerOrNull() ?: return
        if (p.isPlaying && SettingsStore.value.videoAutoPip && !SettingsStore.value.videoBackground) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip.value = isInPictureInPictureMode
        // the floating window was closed (swiped away / X): stop, like leaving the video
        if (!isInPictureInPictureMode && !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
            playerOrNull()?.pause()
            leave()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // another video was opened while this one is on screen
        val req = VideoRequest.from(this, intent) ?: return
        val p = playerOrNull() ?: return
        savePosition()
        request = req
        keys = req.keys.toMutableList()
        ended = false
        abA.value = null
        abB.value = null
        if (rotation.intValue == 0) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        startRequest(req)
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
    val returnResult: Boolean,
    /** Library videos: their file paths (to find subtitle files next to them). */
    val paths: List<String?> = emptyList()
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
                    .build()
            }
            val start = VideoStore.position(context, keys[index])
            // subtitle files next to the videos are looked for later, off the screen thread
            return VideoRequest(items, keys, index, start, emptyMap(), returnResult = false, paths = paths?.toList().orEmpty())
        }

        @Suppress("DEPRECATION")
        private fun fromOtherApp(activity: Activity, intent: Intent): VideoRequest? {
            val uri: Uri = intent.data
                ?: (intent.getParcelableExtra<Parcelable>(Intent.EXTRA_STREAM) as? Uri)
                ?: linkIn(intent.getStringExtra(Intent.EXTRA_TEXT))?.let { Uri.parse(it) }
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

        /** The first web address in a shared text ("Watch this: https://… .m3u8"). */
        fun linkIn(text: String?): String? =
            text?.let { Regex("https?://\\S+").find(it)?.value?.trimEnd('.', ',', ')', ']', '"', '\'') }

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
        /**
         * Adds "Episode 1.srt" (or .ass / .vtt) found next to each video. Each folder is read only once, so a
         * folder of hundreds of episodes stays quick. Runs in the background.
         */
        fun withSidecars(items: List<MediaItem>, paths: List<String?>): List<MediaItem> {
            val folders = HashMap<String, List<File>>()
            return items.mapIndexed { i, item ->
                val path = paths.getOrNull(i)?.takeIf { it.isNotBlank() } ?: return@mapIndexed item
                val f = File(path)
                val dir = f.parent ?: return@mapIndexed item
                val subFiles = folders.getOrPut(dir) {
                    runCatching {
                        File(dir).listFiles { x -> x.name.substringAfterLast('.').lowercase() in setOf("srt", "ass", "ssa", "vtt") }?.toList()
                    }.getOrNull().orEmpty()
                }
                if (subFiles.isEmpty()) return@mapIndexed item
                val base = f.name.substringBeforeLast('.')
                val found = subFiles.filter { it.name.startsWith(base) && runCatching { it.canRead() }.getOrDefault(false) }
                if (found.isEmpty()) item
                else item.buildUpon().setSubtitleConfigurations(
                    found.sortedBy { it.name.length }.mapIndexed { k, s ->
                        subtitle(Uri.fromFile(s), s.name.removePrefix(base).trim('.', ' ', '_', '-').ifBlank { s.name }, selected = k == 0)
                    }
                ).build()
            }
        }

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
