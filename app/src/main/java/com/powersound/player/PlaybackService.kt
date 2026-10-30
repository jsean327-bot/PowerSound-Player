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
import com.powersound.player.audio.PowerSoundSink
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
            .setBitmapLoader(LockArtLoader(this, scope))
            .setSessionActivity(sessionActivity)
            .build()

        // Settings follow live: headset pause, notification buttons, EQ bands, output options.
        scope.launch {
            var last: AppSettings? = null
            SettingsStore.settings.collect { st ->
                player.setHandleAudioBecomingNoisy(st.pauseOnDisconnect)
                // Settings → Misc → Use Wakelock
                player.setWakeMode(if (st.useWakelock) C.WAKE_MODE_LOCAL else C.WAKE_MODE_NONE)
                if (last != null && (last?.lockBlur != st.lockBlur || last?.lockDefaultImage != st.lockDefaultImage)) refreshArtwork()
                if (last?.shutdownIntent != st.shutdownIntent) registerShutdownIntent(st.shutdownIntent)
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
                ExternalIntents.playState(this@PlaybackService, player)
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
                ExternalIntents.metaChanged(this@PlaybackService, player)
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
        registerScreenReceiver(player)
    }

    /**
     * Decoder (32-bit float) → PowerSound's 64-bit sound chain → 32-bit float to Android ([PowerSoundSink]).
     * Android's own sink only plays what PowerSound hands it; the tempo uses Android's time stretching.
     */
    private fun buildPlayer(wakeLock: Boolean): ExoPlayer {
        val renderers = RenderersFactory { handler, _, audioListener, _, _ ->
            val androidSink = DefaultAudioSink.Builder(this)
                .setEnableFloatOutput(true)
                .setEnableAudioTrackPlaybackParams(true)
                .build()
            arrayOf<Renderer>(
                MediaCodecAudioRenderer(this, softwareDecodersFirst, handler, audioListener, PowerSoundSink(androidSink))
            )
        }
        // Settings → Misc → Network Stream Timeout / User Agent (read for every new connection)
        val http = androidx.media3.datasource.DataSource.Factory {
            val st = SettingsStore.value
            val timeout = if (st.streamTimeoutSec > 60) 0 else st.streamTimeoutSec * 1000
            androidx.media3.datasource.DefaultHttpDataSource.Factory()
                .setUserAgent(st.userAgent.ifBlank { "PowerSound Player" })
                .setConnectTimeoutMs(timeout)
                .setReadTimeoutMs(timeout)
                .setAllowCrossProtocolRedirects(true)
                .createDataSource()
        }
        // Settings → Misc → Network Stream Buffer (used when the player is created)
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setTargetBufferBytes(SettingsStore.value.streamBufferKb.coerceIn(256, 16384) * 1024)
            .build()
        return ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(
                androidx.media3.exoplayer.source.DefaultMediaSourceFactory(androidx.media3.datasource.DefaultDataSource.Factory(this, http))
            )
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ false)
            .setHandleAudioBecomingNoisy(wakeLock && SettingsStore.value.pauseOnDisconnect)
            .apply { if (wakeLock && SettingsStore.value.useWakelock) setWakeMode(C.WAKE_MODE_LOCAL) }
            .build()
    }

    /**
     * Android's own (Google) audio decoders first: they deliver 32-bit float, so 24-bit FLAC keeps every bit.
     * Some phone makers' decoders only give 16-bit.
     */
    private val softwareDecodersFirst = MediaCodecSelector { mime, secure, tunneling ->
        val list = androidx.media3.exoplayer.mediacodec.MediaCodecUtil.getDecoderInfos(mime, secure, tunneling)
        if (!mime.startsWith("audio/")) list
        else list.sortedBy { if (it.name.startsWith("c2.android.") || it.name.startsWith("OMX.google.")) 0 else 1 }
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

        /** Settings → Headset → Ignore Repeat/Shuffle: Bluetooth devices / other apps can't change them. */
        private fun fromOutside(): Boolean {
            val c = session?.controllerForCurrentRequest ?: return false
            return c.packageName != packageName
        }

        override fun setRepeatMode(repeatMode: Int) {
            if (SettingsStore.value.ignoreRepeatShuffle && fromOutside()) return
            super.setRepeatMode(repeatMode)
        }

        override fun setShuffleModeEnabled(shuffleModeEnabled: Boolean) {
            if (SettingsStore.value.ignoreRepeatShuffle && fromOutside()) return
            super.setShuffleModeEnabled(shuffleModeEnabled)
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
        AudioEngine.nativeRate() // the new output's own rate (Audio Outputs → Sample Rate → Native)
        AudioEngine.applyOptions(
            bitPerfect = st.perfectBitPerfect,
            eqBlocked = key in st.outNoEq,
            fadeOnSeek = st.fadeOnSeek,
            ditherType = st.dither,
            resamplerQuality = st.resamplerType,
            resamplerCutoff = st.resamplerCutoff.toDouble(),
            outputRateRule = st.outRate[key] ?: 1,
            outputFloat = (st.outFormat[key] ?: 0) == 0
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
                if (addedDevices.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        (android.os.Build.VERSION.SDK_INT >= 31 && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET) }
                ) buttons.btConnectedAt = android.os.SystemClock.elapsedRealtime()
                // Settings → Headset → Pause On USB DAC connection
                if (st.pauseOnUsbDac && player.isPlaying && addedDevices.any {
                        it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_ACCESSORY ||
                            it.type == AudioDeviceInfo.TYPE_USB_HEADSET
                    }
                ) {
                    player.pause()
                    return
                }
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
        screenReceiver?.let { runCatching { unregisterReceiver(it) } }
        screenReceiver = null
        shutdownReceiver?.let { runCatching { unregisterReceiver(it) } }
        shutdownReceiver = null
        main?.let { ExternalIntents.stopped(this, it) }
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

    // ---------------------------------------------------------------- headset / Bluetooth buttons

    private val buttons = ButtonHandler()

    /** Last commands from headset / Bluetooth buttons (Settings → Headset → Last Processed Commands). */
    private inner class ButtonHandler {
        var btConnectedAt = 0L
        private var presses = 0
        private var pressJob: kotlinx.coroutines.Job? = null
        private var longHandled = false

        fun handle(intent: Intent): Boolean {
            @Suppress("DEPRECATION")
            val ev = intent.getParcelableExtra<android.view.KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return false
            val player = session?.player ?: return false
            val st = SettingsStore.value
            val name = android.view.KeyEvent.keyCodeToString(ev.keyCode).removePrefix("KEYCODE_")
            if (!st.respondButtons) { ButtonLog.add("$name — ignored (Respond To Buttons is off)"); return true }
            val bt = outputKey == "bt"
            if (bt && st.ignoreBtSec > 0 &&
                android.os.SystemClock.elapsedRealtime() - btConnectedAt < st.ignoreBtSec * 1000L
            ) { ButtonLog.add("$name — ignored (just connected)"); return true }
            val mode = if (bt) st.btPressMode else st.wiredPressMode
            when (ev.keyCode) {
                android.view.KeyEvent.KEYCODE_HEADSETHOOK, android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    when (mode) {
                        0 -> if (ev.repeatCount == 0) { toggle(player); log(name, "play/pause") }
                        1 -> if (ev.repeatCount == 0) {
                            // count presses: 1 play/pause, 2 next, 3 previous
                            presses++
                            pressJob?.cancel()
                            pressJob = scope.launch {
                                delay(450)
                                val n = presses
                                presses = 0
                                when {
                                    n >= 3 -> { player.seekToPrevious(); log(name, "×3 previous", true) }
                                    n == 2 -> { player.seekToNext(); log(name, "×2 next", true) }
                                    else -> { toggle(player); log(name, "play/pause") }
                                }
                            }
                        }
                        else -> {
                            // long press for next track (when playing)
                            if (ev.repeatCount == 0) {
                                longHandled = false
                                pressJob?.cancel()
                                pressJob = scope.launch {
                                    delay(600)
                                    if (!longHandled) { toggle(player); log(name, "play/pause") }
                                }
                            } else if (!longHandled) {
                                longHandled = true
                                pressJob?.cancel()
                                if (player.isPlaying) { player.seekToNext(); log(name, "long press → next", true) }
                            }
                        }
                    }
                }
                android.view.KeyEvent.KEYCODE_MEDIA_PLAY -> if (ev.repeatCount == 0) {
                    // Strict Resume/Pause/Stop off: a stray "play" from some devices toggles instead
                    if (st.strictPlayPause || !player.playWhenReady) { startPlay(player); log(name, "play") } else { player.pause(); log(name, "pause (not strict)") }
                }
                android.view.KeyEvent.KEYCODE_MEDIA_PAUSE, android.view.KeyEvent.KEYCODE_MEDIA_STOP -> if (ev.repeatCount == 0) {
                    if (st.strictPlayPause || player.playWhenReady) { player.pause(); log(name, "pause") } else { startPlay(player); log(name, "play (not strict)") }
                }
                android.view.KeyEvent.KEYCODE_MEDIA_NEXT, android.view.KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD ->
                    if (ev.repeatCount == 0) { player.seekToNext(); log(name, "next") }
                android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS, android.view.KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD ->
                    if (ev.repeatCount == 0) { player.seekToPrevious(); log(name, "previous") }
                android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { player.seekTo(player.currentPosition + 10_000); log(name, "+10 s") }
                android.view.KeyEvent.KEYCODE_MEDIA_REWIND -> { player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0)); log(name, "-10 s") }
                else -> return false
            }
            return true
        }

        private fun startPlay(player: Player) {
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            player.play()
        }

        private fun toggle(player: Player) = if (player.playWhenReady) player.pause() else startPlay(player)

        /** Beep on multi-press / long-press commands; Beep More on all; Vibrate. */
        private fun log(key: String, action: String, special: Boolean = false) {
            ButtonLog.add("$key → $action (${if (outputKey == "bt") "Bluetooth" else "wired"})")
            val st = SettingsStore.value
            if ((st.buttonBeep && special) || st.buttonBeepMore) beep()
            if (st.buttonVibrate) vibrate()
        }
    }

    private fun beep() = runCatching {
        val tg = android.media.ToneGenerator(AudioManager.STREAM_MUSIC, 60)
        tg.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 120)
        scope.launch { delay(400); tg.release() }
    }

    private fun vibrate() = runCatching {
        val v = if (android.os.Build.VERSION.SDK_INT >= 31) getSystemService(android.os.VibratorManager::class.java).defaultVibrator
        else @Suppress("DEPRECATION") getSystemService(android.os.Vibrator::class.java)
        v.vibrate(android.os.VibrationEffect.createOneShot(40, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
    }

    // ---------------------------------------------------------------- screen off, shutdown intent, lock screen

    private var screenReceiver: android.content.BroadcastReceiver? = null
    private var shutdownReceiver: android.content.BroadcastReceiver? = null

    private fun registerScreenReceiver(player: ExoPlayer) {
        val r = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != Intent.ACTION_SCREEN_OFF) return
                val st = SettingsStore.value
                // Settings → Misc → Pause on Screen Off
                if (st.pauseOnScreenOff && player.isPlaying) { player.pause(); return }
                // Settings → Lock Screen → Show On Lock Screen (needs "Display over other apps" on newer Android)
                if (st.showOnLockScreen && player.isPlaying &&
                    (android.os.Build.VERSION.SDK_INT < 29 || android.provider.Settings.canDrawOverlays(context))
                ) {
                    runCatching {
                        startActivity(
                            Intent(context, MainActivity::class.java)
                                .putExtra(MainActivity.EXTRA_FROM_NOTIFICATION, true)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        )
                    }
                }
            }
        }
        androidx.core.content.ContextCompat.registerReceiver(
            this, r, android.content.IntentFilter(Intent.ACTION_SCREEN_OFF), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
        screenReceiver = r
    }

    /** Settings → Misc → Shutdown Intent: another app sends this action, PowerSound pauses (its state is kept). */
    private fun registerShutdownIntent(action: String) {
        shutdownReceiver?.let { runCatching { unregisterReceiver(it) } }
        shutdownReceiver = null
        if (action.isBlank()) return
        val r = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                main?.pause()
            }
        }
        androidx.core.content.ContextCompat.registerReceiver(
            this, r, android.content.IntentFilter(action.trim()), androidx.core.content.ContextCompat.RECEIVER_EXPORTED
        )
        shutdownReceiver = r
    }

    /** Lock screen art options changed: load the current cover again. */
    private fun refreshArtwork() {
        val p = main ?: return
        val i = p.currentMediaItemIndex
        val item = p.currentMediaItem ?: return
        val md = item.mediaMetadata
        // a changed address makes the session load the image again
        val uri = md.artworkUri ?: return
        val fresh = android.net.Uri.parse(uri.toString().substringBefore('#') + "#" + System.currentTimeMillis())
        p.replaceMediaItem(i, item.buildUpon().setMediaMetadata(md.buildUpon().setArtworkUri(fresh).build()).build())
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

        override fun onMediaButtonEvent(session: MediaSession, controllerInfo: MediaSession.ControllerInfo, intent: Intent): Boolean =
            buttons.handle(intent)

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
                // the lock screen / notification art is loaded by LockArtLoader from this address
                val art = item.mediaId.toLongOrNull()?.let { android.net.Uri.parse("$ART_SCHEME:$it") }
                val md = if (art != null) item.mediaMetadata.buildUpon().setArtworkUri(art).build() else item.mediaMetadata
                if (uri != null) item.buildUpon().setUri(uri).setMediaMetadata(md).build() else item
            }.toMutableList()
            return Futures.immediateFuture(resolved)
        }
    }
}
