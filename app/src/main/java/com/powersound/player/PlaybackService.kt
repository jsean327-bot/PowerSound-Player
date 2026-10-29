package com.powersound.player

import android.app.PendingIntent
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.powersound.player.audio.AudioEngine
import com.powersound.player.audio.BUILT_IN_PRESETS
import com.powersound.player.audio.Crossfader
import com.powersound.player.audio.PowerSoundProcessor
import com.powersound.player.data.AppSettings
import com.powersound.player.data.NOTIF_ACTIONS
import com.powersound.player.data.SettingsStore
import com.powersound.player.data.StatsStore
import com.powersound.player.data.eqLayout
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Commands from the notification buttons that need the app's lists (category change, the 6 repeat modes…).
 * The view model listens while the app is alive; otherwise the service does a simple version itself.
 */
object PlayerCommands {
    val flow = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 8)
    val hasListener: Boolean get() = flow.subscriptionCount.value > 0

    /** Set by the app when the current song must be the last one (repeat "song and stop", sleep timer). */
    @Volatile
    var stopAfterCurrent = false
}

/**
 * Background playback. Media3 builds the notification and the lock-screen
 * controls automatically from this session.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private var main: ExoPlayer? = null
    private var crossfader: Crossfader? = null
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)
    private var deviceCallback: android.media.AudioDeviceCallback? = null
    private var volumeObserver: android.database.ContentObserver? = null
    private lateinit var audioManager: AudioManager
    private var outputKey = "speaker"

    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .build()

    override fun onCreate() {
        super.onCreate()

        AudioEngine.init(this)
        SettingsStore.init(this)
        StatsStore.init(this)
        com.powersound.player.data.CrashLog.install(this)
        audioManager = getSystemService(AudioManager::class.java)
        AudioEngine.deviceRate = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 48000
        SettingsStore.value.eqLayout().let { (n, a, b) -> AudioEngine.initLayout(n, a, b) }

        // Audio focus is handled here (Settings → Audio → Audio Focus), not by ExoPlayer.
        val player = buildPlayer(wakeLock = true)
        main = player
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onAudioDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long
            ) {
                AudioEngine.decoderName = decoderName
            }

            override fun onAudioSessionIdChanged(eventTime: AnalyticsListener.EventTime, audioSessionId: Int) {
                openMusicFx(audioSessionId)
            }
        })
        openMusicFx(player.audioSessionId)

        val xf = Crossfader(player, { buildPlayer(wakeLock = false) }, scope)
        crossfader = xf

        val openApp = (packageManager.getLaunchIntentForPackage(packageName) ?: Intent(this, MainActivity::class.java))
            .putExtra(MainActivity.EXTRA_FROM_NOTIFICATION, true)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val sessionActivity = PendingIntent.getActivity(
            this, 0, openApp,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        session = MediaSession.Builder(this, SessionPlayer(player))
            .setCallback(SessionCallback())
            .setSessionActivity(sessionActivity)
            .build()

        // Settings follow live: headset pause, notification buttons, EQ bands, output options.
        scope.launch {
            var last: AppSettings? = null
            SettingsStore.settings.collect { st ->
                player.setHandleAudioBecomingNoisy(st.pauseOnDisconnect)
                session?.setCustomLayout(customLayout())
                st.eqLayout().let { (n, a, b) -> AudioEngine.setLayout(n, a, b) }
                applyOutputOptions(st, presetChanged = last == null || last?.outEqPreset != st.outEqPreset)
                if (last?.musicFx != st.musicFx) openMusicFx(player.audioSessionId)
                last = st
            }
        }
        player.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (playWhenReady) requestFocus() else if (!pausedByFocus && !pausedByCall) abandonFocus()
                // Settings → Notifications → Remove Notification on Disconnection
                if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY &&
                    SettingsStore.value.removeNotifOnDisconnect
                ) {
                    scope.launch { delay(400); if (!player.playWhenReady) player.stop() }
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                // Settings → Notifications → Keep Notification (off: remove it when playback ends by itself)
                if (state == Player.STATE_ENDED && !SettingsStore.value.keepNotification) {
                    scope.launch {
                        delay(2000)
                        if (player.playbackState == Player.STATE_ENDED) player.stop()
                    }
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // Settings → Crossfade → Silence Between Tracks
                val ms = SettingsStore.value.silenceMs
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && ms > 0 && !PlayerCommands.stopAfterCurrent) {
                    player.pause()
                    scope.launch {
                        delay(ms.toLong())
                        if (!PlayerCommands.stopAfterCurrent) player.play()
                    }
                }
            }
        })
        registerDeviceCallback(player)
        registerVolumeObserver(player)
    }

    /** Decoder → PowerSound DSP → 16-bit output. Float output stays off: it made some FLAC files distort. */
    private fun buildPlayer(wakeLock: Boolean): ExoPlayer {
        val renderers = RenderersFactory { handler, _, audioListener, _, _ ->
            @Suppress("DEPRECATION")
            val sink = DefaultAudioSink.Builder()
                .setEnableFloatOutput(false)
                .setAudioProcessors(arrayOf<AudioProcessor>(PowerSoundProcessor()))
                .build()
            arrayOf<Renderer>(
                MediaCodecAudioRenderer(this, MediaCodecSelector.DEFAULT, handler, audioListener, sink)
            )
        }
        return ExoPlayer.Builder(this, renderers)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ false)
            .setHandleAudioBecomingNoisy(wakeLock && SettingsStore.value.pauseOnDisconnect)
            .apply { if (wakeLock) setWakeMode(C.WAKE_MODE_LOCAL) }
            .build()
    }

    // ---------------------------------------------------------------- the player the session sees

    /** Adds fades to track changes and seeks (Settings → Crossfade, Fade, and Gapless). */
    private inner class SessionPlayer(private val p: ExoPlayer) : ForwardingPlayer(p) {

        private fun faded(jump: () -> Unit) {
            val xf = crossfader
            if (xf == null || !xf.manualChange(jump)) {
                xf?.abort()
                jump()
            }
        }

        override fun seekToNext() = faded { p.seekToNext() }
        override fun seekToNextMediaItem() = faded { p.seekToNextMediaItem() }
        override fun seekToPreviousMediaItem() = faded { p.seekToPreviousMediaItem() }

        override fun seekToPrevious() {
            // Settings → Player Screen → "<< Button Resets Current Track" (headset, Bluetooth, notification)
            val resets = SettingsStore.value.prevResets
            if ((resets && p.currentPosition > 3000) || !p.hasPreviousMediaItem()) seekTo(0) else faded { p.seekToPreviousMediaItem() }
        }

        override fun seekTo(positionMs: Long) {
            crossfader?.abort()
            val st = SettingsStore.value
            if (!st.fadeOnSeek || st.perfectBitPerfect || !p.isPlaying) {
                p.seekTo(positionMs)
                return
            }
            // short fade out, seek, the processor fades back in
            val before = p.volume
            scope.launch {
                for (i in 3 downTo 0) {
                    p.volume = before * i / 4f
                    delay(10)
                }
                p.seekTo(positionMs)
                p.volume = before
            }
        }

        override fun pause() {
            crossfader?.abort()
            super.pause()
        }
    }

    // ---------------------------------------------------------------- audio focus

    private var focusRequest: AudioFocusRequest? = null
    private var hasFocus = false
    private var pausedByFocus = false
    private var pausedByCall = false

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        val player = main ?: return@OnAudioFocusChangeListener
        val st = SettingsStore.value
        val inCall = audioManager.mode == AudioManager.MODE_IN_CALL ||
            audioManager.mode == AudioManager.MODE_IN_COMMUNICATION ||
            audioManager.mode == AudioManager.MODE_RINGTONE
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                hasFocus = false
                // another player started (Permanent Audio Focus Change)
                if (st.pauseOnPermanentFocus && player.playWhenReady) {
                    pausedByFocus = false
                    player.pause()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (!player.playWhenReady) return@OnAudioFocusChangeListener
                if (inCall) {
                    pausedByCall = true // calls always pause
                    player.pause()
                } else if (st.pauseOnShortFocus) {
                    pausedByFocus = true
                    player.pause()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (!player.playWhenReady) return@OnAudioFocusChangeListener
                when {
                    outputKey in st.outNoDuck -> Unit // Settings → Audio Outputs → No Duck
                    st.duckVolume -> AudioEngine.duckGain = 0.25
                    inCall -> { pausedByCall = true; player.pause() }
                    st.pauseOnShortFocus -> { pausedByFocus = true; player.pause() }
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasFocus = true
                AudioEngine.duckGain = 1.0
                val resume = (pausedByCall && st.resumeAfterCall) || (pausedByFocus && st.resumeOnFocusGain)
                pausedByCall = false
                pausedByFocus = false
                if (resume) player.play()
            }
        }
    }

    private fun requestFocus() {
        AudioEngine.duckGain = 1.0
        if (hasFocus) return
        val req = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setWillPauseWhenDucked(true) // ducking is done by PowerSound itself (or not, per settings)
            .setOnAudioFocusChangeListener(focusListener)
            .build()
            .also { focusRequest = it }
        pausedByFocus = false
        pausedByCall = false
        hasFocus = audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!hasFocus) {
            // e.g. during a phone call
            pausedByCall = true
            main?.pause()
        }
    }

    private fun abandonFocus() {
        val req = focusRequest ?: return
        if (!hasFocus) return
        audioManager.abandonAudioFocusRequest(req)
        hasFocus = false
    }

    // ---------------------------------------------------------------- outputs

    private fun detectOutput(): Pair<String, String> {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        fun has(vararg types: Int) = devices.firstOrNull { it.type in types }
        val bt = has(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, 26 /* BLE headset */, 27 /* BLE speaker */)
        if (bt != null) return "bt" to (bt.productName?.toString()?.ifBlank { null } ?: "Bluetooth")
        val usb = has(AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY)
        if (usb != null) return "usb" to (usb.productName?.toString()?.ifBlank { null } ?: "USB DAC")
        val wired = has(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_AUX_LINE)
        if (wired != null) return "wired" to "Wired Headset/AUX"
        val other = has(AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_LINE_DIGITAL, AudioDeviceInfo.TYPE_DOCK)
        if (other != null) return "other" to (other.productName?.toString()?.ifBlank { null } ?: "Other output")
        return "speaker" to "Speaker"
    }

    /** Per-output options (Settings → Audio → Audio Outputs) and the global audio options. */
    private fun applyOutputOptions(st: AppSettings, presetChanged: Boolean) {
        val (key, name) = detectOutput()
        val switched = key != outputKey
        outputKey = key
        AudioEngine.currentOutput = name
        AudioEngine.outputKey = key
        AudioEngine.outputDelayMs = st.outDelayMs[key] ?: 0
        AudioEngine.applyOptions(
            bitPerfect = st.perfectBitPerfect,
            eqBlocked = key in st.outNoEq,
            fadeOnSeek = st.fadeOnSeek,
            ditherType = st.dither,
            resamplerVeryHigh = st.resamplerType == 1,
            resamplerCutoff = st.resamplerCutoff.toDouble(),
            outputRateRule = st.outRate[key] ?: 0
        )
        // the equalizer preset assigned to this output
        if (switched || presetChanged) {
            st.outEqPreset[key]?.let { name ->
                val preset = (BUILT_IN_PRESETS + AudioEngine.customPresets.value).firstOrNull { it.name == name }
                if (preset != null && AudioEngine.settings.value.presetName != name) AudioEngine.applyPreset(preset)
            }
        }
    }

    /** Resume on headset connection (Settings → Headset) and output switching. */
    private fun registerDeviceCallback(player: ExoPlayer) {
        val registeredAt = android.os.SystemClock.elapsedRealtime()
        val cb = object : android.media.AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                applyOutputOptions(SettingsStore.value, presetChanged = false)
                // the callback also lists already-connected devices right after registering: ignore those
                if (android.os.SystemClock.elapsedRealtime() - registeredAt < 1500) return
                val st = SettingsStore.value
                val bt = addedDevices.any {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                        (android.os.Build.VERSION.SDK_INT >= 31 && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
                }
                val wired = addedDevices.any {
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                        it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                        it.type == AudioDeviceInfo.TYPE_USB_HEADSET
                }
                if (((bt && st.resumeOnBluetooth) || (wired && st.resumeOnWired)) &&
                    !player.isPlaying && player.mediaItemCount > 0
                ) {
                    // give the phone a moment to route audio to the new device
                    scope.launch {
                        delay(1200)
                        if (player.playbackState == Player.STATE_IDLE) player.prepare()
                        player.play()
                    }
                }
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                applyOutputOptions(SettingsStore.value, presetChanged = false)
            }
        }
        audioManager.registerAudioDeviceCallback(cb, null)
        deviceCallback = cb
    }

    // ---------------------------------------------------------------- Advanced Tweaks

    private var pausedByVolume = false

    /** Pause/Resume on Volume: volume 0 pauses, turning it up again resumes. */
    private fun registerVolumeObserver(player: ExoPlayer) {
        val obs = object : android.database.ContentObserver(android.os.Handler(mainLooper)) {
            override fun onChange(selfChange: Boolean) {
                if (!SettingsStore.value.pauseOnVolumeZero) return
                val vol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                if (vol == 0 && player.isPlaying) {
                    pausedByVolume = true
                    player.pause()
                } else if (vol > 0 && pausedByVolume) {
                    pausedByVolume = false
                    player.play()
                }
            }
        }
        contentResolver.registerContentObserver(android.provider.Settings.System.CONTENT_URI, true, obs)
        volumeObserver = obs
    }

    /** MusicFX: lets Android's / other apps' audio effects attach to PowerSound's audio. */
    private var musicFxSession = 0

    private fun openMusicFx(sessionId: Int) {
        AudioEngine.audioSessionId = sessionId
        if (musicFxSession != 0) {
            sendBroadcast(
                Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
                    .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, musicFxSession)
                    .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
            )
            musicFxSession = 0
        }
        if (SettingsStore.value.musicFx && sessionId != 0 && sessionId != C.AUDIO_SESSION_ID_UNSET) {
            sendBroadcast(
                Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
                    .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
                    .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
                    .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
            )
            musicFxSession = sessionId
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (SettingsStore.value.stopOnSwipeAway && player != null) {
            // Settings → Misc: swiping the app away stops the music
            player.pause()
            player.stop()
            stopSelf()
            return
        }
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        deviceCallback?.let { audioManager.unregisterAudioDeviceCallback(it) }
        deviceCallback = null
        volumeObserver?.let { contentResolver.unregisterContentObserver(it) }
        volumeObserver = null
        abandonFocus()
        openMusicFx(0)
        crossfader?.release()
        crossfader = null
        scope.cancel()
        session?.run {
            player.release()
            release()
        }
        session = null
        main = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------- notification buttons

    private fun commandFor(action: Int) = SessionCommand("ps.action.$action", android.os.Bundle.EMPTY)

    private fun iconFor(action: Int): Int = when (action) {
        1 -> R.drawable.ic_n_repeat
        2 -> R.drawable.ic_n_shuffle
        3 -> R.drawable.ic_n_close
        4 -> R.drawable.ic_n_like
        5 -> R.drawable.ic_n_unlike
        6 -> R.drawable.ic_n_star
        7 -> R.drawable.ic_n_rewind
        8 -> R.drawable.ic_n_forward
        9 -> R.drawable.ic_n_prev_cat
        else -> R.drawable.ic_n_next_cat
    }

    /** Buttons 1–6 from Settings → Notifications → Buttons ("None" is skipped). */
    private fun customLayout(): List<CommandButton> =
        SettingsStore.value.notifButtons.filter { it in 1 until NOTIF_ACTIONS.size }.map { a ->
            CommandButton.Builder()
                .setDisplayName(NOTIF_ACTIONS[a])
                .setIconResId(iconFor(a))
                .setSessionCommand(commandFor(a))
                .build()
        }

    private fun runAction(action: Int) {
        val player = session?.player ?: return
        val id = player.currentMediaItem?.mediaId?.toLongOrNull()
        when (action) {
            1 -> if (PlayerCommands.hasListener) PlayerCommands.flow.tryEmit("repeat") else
                player.repeatMode = (player.repeatMode + 1) % 3
            2 -> if (PlayerCommands.hasListener) PlayerCommands.flow.tryEmit("shuffle") else
                player.shuffleModeEnabled = !player.shuffleModeEnabled
            3 -> { player.pause(); player.stop() } // "Close": pause and remove the notification
            4 -> id?.let { StatsStore.setRating(it, if (StatsStore.stats.value.rating(it) == 1) 0 else 1) }
            5 -> id?.let { StatsStore.setRating(it, if (StatsStore.stats.value.rating(it) == -1) 0 else -1) }
            6 -> id?.let {
                // Rating: each press adds a star, after 5 stars the rating is cleared
                val stars = StatsStore.stats.value.stars(it)
                StatsStore.setRating(it, if (stars >= 5) 0 else 10 + stars + 1)
            }
            7 -> player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0))
            8 -> player.seekTo(player.currentPosition + 10_000)
            9 -> PlayerCommands.flow.tryEmit("prevCat")
            10 -> PlayerCommands.flow.tryEmit("nextCat")
        }
    }

    /** Restores the playable URI of each item sent from the app's controller. */
    private inner class SessionCallback : MediaSession.Callback {

        override fun onConnect(mediaSession: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val base = super.onConnect(mediaSession, controller)
            val commands = base.availableSessionCommands.buildUpon()
            for (a in 1 until NOTIF_ACTIONS.size) commands.add(commandFor(a))
            return MediaSession.ConnectionResult.accept(commands.build(), base.availablePlayerCommands)
        }

        override fun onPostConnect(mediaSession: MediaSession, controller: MediaSession.ControllerInfo) {
            mediaSession.setCustomLayout(controller, customLayout())
        }

        override fun onCustomCommand(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: android.os.Bundle
        ): ListenableFuture<SessionResult> {
            customCommand.customAction.removePrefix("ps.action.").toIntOrNull()?.let { runAction(it) }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            val resolved = mediaItems.map { item ->
                val uri = item.requestMetadata.mediaUri
                    ?: item.localConfiguration?.uri
                    ?: item.mediaId.toLongOrNull()?.let {
                        ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, it)
                    }
                if (uri != null) item.buildUpon().setUri(uri).build() else item
            }.toMutableList()
            return Futures.immediateFuture(resolved)
        }
    }
}
