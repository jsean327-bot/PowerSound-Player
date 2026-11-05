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
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.SettableFuture
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

    /** Set right before a pause that must be immediate (the next song already started, "stop after this song"). */
    @Volatile
    var pauseNow = false

    /** True while the sleep timer is lowering the volume: crossfades stay out of its way. */
    @Volatile
    var sleepFading = false
}

/**
 * Background playback. Media3 builds the notification and the lock-screen
 * controls automatically from this session.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {

    companion object {
        /** The running service (the home-screen widget talks to it). */
        @Volatile
        var instance: PlaybackService? = null
            private set
    }

    private var session: MediaLibrarySession? = null
    private lateinit var car: CarLibrary
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
        com.powersound.player.data.LibraryStores.init(this)
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

        // a library session: Android Auto (and similar) can browse the music too
        car = CarLibrary(this)
        val local = SessionPlayer(player)
        localPlayer = local
        session = MediaLibrarySession.Builder(this, local, SessionCallback())
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

        // the home-screen widget follows the song and the play state
        player.addListener(widgetListener)
        instance = this
        setupCast()
        refreshWidget(force = true)
    }

    private val widgetListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(
                    Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_PLAY_WHEN_READY_CHANGED,
                    Player.EVENT_MEDIA_METADATA_CHANGED, Player.EVENT_TIMELINE_CHANGED,
                    Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_PLAYBACK_STATE_CHANGED,
                    Player.EVENT_REPEAT_MODE_CHANGED, Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED
                )
            ) refreshWidget()
            // a seek: the large widget's bar and time jump with it
            if (events.contains(Player.EVENT_POSITION_DISCONTINUITY)) refreshWidget(force = true)
        }
    }

    // ---------------------------------------------------------------- Chromecast

    private var localPlayer: Player? = null
    private var castContext: com.google.android.gms.cast.framework.CastContext? = null
    private var castPlayer: androidx.media3.cast.CastPlayer? = null
    private var remote: com.powersound.player.cast.RemotePlayer? = null
    private var castServer: com.powersound.player.cast.MediaServer? = null
    private var castTicker: kotlinx.coroutines.Job? = null
    private var castLastId: String? = null
    private var castLastPos = 0L
    /** Where the song now on the Chromecast sits in the phone's (whole) list. */
    private var castMainIndex = 0
    private var castVolumeListener: com.google.android.gms.cast.Cast.Listener? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null
    private var castWake: android.os.PowerManager.WakeLock? = null

    /** A video starts in the video player: the music pauses (not when it plays on a Chromecast). */
    fun pauseForVideo() {
        if (isCasting) return
        val p = session?.player ?: return
        if (p.playWhenReady) p.pause()
    }

    /** True while the music plays on a Chromecast. */
    val isCasting: Boolean get() = remote != null && session?.player === remote

    /**
     * Chromecast support. The phone keeps the whole list (in [main], not playing); the Chromecast gets the
     * current song and the next ones in play order, a few at a time, and fetches each file from the phone
     * ([com.powersound.player.cast.MediaServer]). Effects and EQ are not heard there: the Chromecast plays
     * the original file itself.
     */
    private fun setupCast() {
        val ctx = com.powersound.player.cast.CastControl.context(this) ?: return // no Google Play services
        castContext = ctx
        val server = com.powersound.player.cast.MediaServer(
            openSong = { id -> openSongFile(id) },
            cover = { id -> coverJpeg(id) }
        )
        castServer = server
        val cp = runCatching {
            androidx.media3.cast.CastPlayer(ctx, com.powersound.player.cast.CastItems(this, server))
        }.getOrNull() ?: return
        castPlayer = cp
        remote = com.powersound.player.cast.RemotePlayer(
            cp, ctx,
            onNewList = { items, index, pos -> castNewList(items, index, pos) },
            shuffleNow = { main?.shuffleModeEnabled == true },
            onShuffle = { on -> castShuffle(on) },
            onRepeat = { mode -> main?.repeatMode = mode }
        )
        cp.addListener(widgetListener)
        cp.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (isCasting) castFollow()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isCasting) holdCastLocks(isPlaying)
            }

            override fun onEvents(player: Player, events: Player.Events) {
                rememberCast()
            }
        })
        cp.setSessionAvailabilityListener(object : androidx.media3.cast.SessionAvailabilityListener {
            // posted: the Cast player is still setting itself up when it calls these
            override fun onCastSessionAvailable() {
                scope.launch { startCasting() }
            }

            override fun onCastSessionUnavailable() {
                scope.launch { stopCasting() }
            }
        })
        if (cp.isCastSessionAvailable) scope.launch { startCasting() }
    }

    private fun startCasting() {
        val s = session ?: return
        val p = main ?: return
        val r = remote ?: return
        if (s.player === r) return
        // the video player is sending a video to the Chromecast: the music stays on the phone
        if (com.powersound.player.cast.CastControl.videoOwnsCast) return
        val cs = castContext?.sessionManager?.currentCastSession ?: return
        castServer?.start()
        val play = p.playWhenReady
        crossfader?.abort()
        p.pause()
        p.volume = 1f
        p.stop() // keeps the list; the phone's audio is free
        abandonFocus()
        s.setPlayer(r)
        loadCastWindow(play)
        com.powersound.player.cast.CastControl.setDevice(cs.castDevice?.friendlyName ?: "Chromecast")
        val vl = object : com.google.android.gms.cast.Cast.Listener() {
            override fun onVolumeChanged() {
                r.notifyVolume()
            }
        }
        runCatching { cs.addCastListener(vl) }
        castVolumeListener = vl
        castTicker?.cancel()
        castTicker = scope.launch {
            while (true) {
                delay(1000)
                rememberCast()
            }
        }
        refreshWidget(force = true)
    }

    /** The Chromecast was disconnected (from the app, Google Home, or the Wi-Fi dropped): back to the phone, paused. */
    private fun stopCasting() {
        castTicker?.cancel()
        castTicker = null
        holdCastLocks(false)
        com.powersound.player.cast.CastControl.setDevice(null)
        castVolumeListener?.let { vl -> runCatching { castContext?.sessionManager?.currentCastSession?.removeCastListener(vl) } }
        castVolumeListener = null
        castServer?.stop()
        val s = session ?: return
        val p = main ?: return
        val local = localPlayer ?: return
        if (s.player === local) return
        val at = castLastId?.let { findInMain(it, castMainIndex) } ?: -1
        s.setPlayer(local)
        if (p.mediaItemCount > 0) {
            if (at >= 0) p.seekTo(at, castLastPos) else p.seekTo(p.currentMediaItemIndex.coerceAtLeast(0), 0L)
            p.playWhenReady = false
            p.prepare()
        }
        refreshWidget(force = true)
    }

    private fun rememberCast() {
        val cp = castPlayer ?: return
        if (!isCasting) return
        val item = cp.currentMediaItem ?: return
        castLastId = item.mediaId
        castLastPos = cp.currentPosition
    }

    /** A new list from the app while casting: the phone keeps it all, the Chromecast gets the first part. */
    private fun castNewList(items: List<MediaItem>, index: Int, pos: Long) {
        val p = main ?: return
        if (items.isEmpty()) {
            p.clearMediaItems()
            castPlayer?.clearMediaItems()
            return
        }
        p.setMediaItems(items, index.coerceIn(0, items.size - 1), pos)
        loadCastWindow(castPlayer?.playWhenReady ?: true)
    }

    /** Up to 10 songs before and 40 after the current one, in play order (shuffle included). */
    private fun loadCastWindow(play: Boolean) {
        val p = main ?: return
        val cp = castPlayer ?: return
        if (p.mediaItemCount == 0) return
        val cur = p.currentMediaItemIndex.coerceAtLeast(0)
        val before = playOrder(p, cur, 10, forward = false).reversed()
        val after = playOrder(p, cur, 40, forward = true)
        val order = before + cur + after
        castMainIndex = cur
        cp.repeatMode = p.repeatMode
        cp.setMediaItems(order.map { p.getMediaItemAt(it) }, before.size, p.currentPosition.coerceAtLeast(0L))
        cp.playWhenReady = play
    }

    /** The Chromecast moved to another song: the phone's list follows, and more songs are sent before they run out. */
    private fun castFollow() {
        val cp = castPlayer ?: return
        val p = main ?: return
        val id = cp.currentMediaItem?.mediaId ?: return
        val at = findInMain(id, castMainIndex)
        if (at >= 0) {
            castMainIndex = at
            if (at != p.currentMediaItemIndex) p.seekTo(at, 0L)
        }
        val n = cp.mediaItemCount
        val cur = cp.currentMediaItemIndex
        if (n - cur <= 5) {
            val lastAt = findInMain(cp.getMediaItemAt(n - 1).mediaId, castMainIndex + (n - 1 - cur))
            if (lastAt >= 0) {
                val more = playOrder(p, lastAt, 30, forward = true)
                if (more.isNotEmpty()) cp.addMediaItems(more.map { p.getMediaItemAt(it) })
            }
        }
    }

    /** Shuffle switched while casting: the songs after the current one are sent again in the new order. */
    private fun castShuffle(on: Boolean) {
        val p = main ?: return
        val cp = castPlayer ?: return
        p.shuffleModeEnabled = on
        val n = cp.mediaItemCount
        val cur = cp.currentMediaItemIndex
        if (n == 0 || cur < 0) return
        if (n > cur + 1) cp.removeMediaItems(cur + 1, n)
        val more = playOrder(p, castMainIndex, 40, forward = true)
        if (more.isNotEmpty()) cp.addMediaItems(more.map { p.getMediaItemAt(it) })
    }

    private fun playOrder(p: Player, from: Int, count: Int, forward: Boolean): List<Int> {
        val tl = p.currentTimeline
        if (tl.isEmpty) return emptyList()
        // "repeat all" carries on from the start, but never past the song we began with
        val rep = if (p.repeatMode == Player.REPEAT_MODE_ALL) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
        val out = ArrayList<Int>()
        var i = from
        repeat(count) {
            i = if (forward) tl.getNextWindowIndex(i, rep, p.shuffleModeEnabled)
            else tl.getPreviousWindowIndex(i, rep, p.shuffleModeEnabled)
            if (i == C.INDEX_UNSET || i == from) return out
            out.add(i)
        }
        return out
    }

    /** Index of the song [id] in the phone's list, the one nearest to [near]; -1 when it isn't there. */
    private fun findInMain(id: String, near: Int): Int {
        val p = main ?: return -1
        val n = p.mediaItemCount
        if (n == 0) return -1
        val start = near.coerceIn(0, n - 1)
        for (d in 0 until n) {
            val a = start + d
            val b = start - d
            if (a < n && p.getMediaItemAt(a).mediaId == id) return a
            if (d > 0 && b >= 0 && p.getMediaItemAt(b).mediaId == id) return b
            if (a >= n && b < 0) break
        }
        return -1
    }

    /** A song file for the Chromecast (runs on the web server's thread). */
    private fun openSongFile(id: Long): com.powersound.player.cast.ServedFile? = runCatching {
        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
        val pfd = contentResolver.openFileDescriptor(uri, "r") ?: return null
        val stream = java.io.FileInputStream(pfd.fileDescriptor)
        val size = pfd.statSize.takeIf { it > 0 } ?: stream.channel.size()
        val type = contentResolver.getType(uri)?.lowercase()
        val mime = when {
            type == null || type.contains("flac") -> "audio/flac"
            type == "audio/x-wav" || type == "audio/wave" -> "audio/wav"
            else -> type
        }
        com.powersound.player.cast.ServedFile(stream.channel, size, mime) {
            stream.close()
            pfd.close()
        }
    }.getOrNull()

    /** The cover the Chromecast shows (runs on the web server's thread). */
    private fun coverJpeg(id: Long): ByteArray? = runCatching {
        val bmp = kotlinx.coroutines.runBlocking {
            com.powersound.player.data.ArtworkLoader.loadBitmap(this@PlaybackService, id, 720)
        } ?: return null
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
        out.toByteArray()
    }.getOrNull()

    /** While the Chromecast plays, the phone keeps its Wi-Fi awake so the songs keep flowing with the screen off. */
    @Suppress("DEPRECATION")
    private fun holdCastLocks(on: Boolean) {
        if (on) {
            if (wifiLock == null) wifiLock = runCatching {
                (applicationContext.getSystemService(WIFI_SERVICE) as android.net.wifi.WifiManager)
                    .createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "PowerSound:cast")
                    .apply { setReferenceCounted(false); acquire() }
            }.getOrNull()
            if (castWake == null) castWake = runCatching {
                getSystemService(android.os.PowerManager::class.java)
                    .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "PowerSound:cast")
                    .apply { setReferenceCounted(false); acquire(6 * 60 * 60 * 1000L) }
            }.getOrNull()
        } else {
            wifiLock?.let { runCatching { it.release() } }
            wifiLock = null
            castWake?.let { runCatching { it.release() } }
            castWake = null
        }
    }

    // ---------------------------------------------------------------- home-screen widget

    private var widgetJob: kotlinx.coroutines.Job? = null
    private var widgetArt: android.graphics.Bitmap? = null
    private var widgetArtId: Long? = null
    private var widgetShown: String? = null

    /** Redraws the widgets (song, cover, play/pause, song bar, shuffle / repeat). Cheap when nothing changed. */
    fun refreshWidget(force: Boolean = false) {
        if (!WidgetUpdater.hasWidgets(this)) { stopWidgetTicker(); return }
        val p: Player = session?.player ?: main ?: return
        val state = widgetState(p)
        val id = p.currentMediaItem?.mediaId?.toLongOrNull()
        val key = "${state.title}|${state.artist}|$id|${state.playing}|${p.isPlaying}|${state.durationMs}|${state.repeat}|${state.shuffle}"
        updateWidgetTicker(p)
        if (!force && key == widgetShown) return
        widgetShown = key
        // text and buttons at once; the cover follows as soon as it is loaded
        WidgetUpdater.show(this, state, widgetArt.takeIf { id != null && widgetArtId == id })
        if (id != null && widgetArtId != id) {
            widgetJob?.cancel()
            widgetJob = scope.launch {
                val art = runCatching { com.powersound.player.data.ArtworkLoader.loadBitmap(this@PlaybackService, id, 256) }.getOrNull()
                widgetArt = art
                widgetArtId = id
                val now: Player = session?.player ?: main ?: return@launch
                if (art != null) WidgetUpdater.show(this@PlaybackService, widgetState(now), art)
            }
        }
    }

    private fun widgetState(p: Player): WidgetState {
        val item = p.currentMediaItem
        val md = item?.mediaMetadata
        val modes = getSharedPreferences("powersound_player", MODE_PRIVATE)
        val dur = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        return WidgetState(
            title = md?.title?.toString() ?: "PowerSound",
            artist = md?.artist?.toString() ?: if (item == null) "Tap play to listen" else "",
            // the play button follows the press at once; the clock only runs while sound really plays
            playing = p.playWhenReady,
            positionMs = p.currentPosition,
            durationMs = dur,
            repeat = WidgetUpdater.repeatLevel(modes.getString("repeat", null)),
            shuffle = (modes.getString("shuffle", null) ?: "OFF") != "OFF"
        )
    }

    private var widgetTicker: kotlinx.coroutines.Job? = null

    /** While music plays and a large widget is on the home screen, its bar moves once a second (screen on only). */
    private fun updateWidgetTicker(p: Player) {
        if (!p.isPlaying || !WidgetUpdater.hasLarge(this)) { stopWidgetTicker(); return }
        if (widgetTicker?.isActive == true) return
        val power = getSystemService(android.os.PowerManager::class.java)
        widgetTicker = scope.launch {
            while (true) {
                delay(1000)
                val q: Player = session?.player ?: main ?: break
                if (!q.isPlaying) break
                if (power?.isInteractive != false) {
                    val dur = q.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
                    WidgetUpdater.progress(this@PlaybackService, q.currentPosition, dur, true)
                }
            }
        }
    }

    private fun stopWidgetTicker() {
        widgetTicker?.cancel()
        widgetTicker = null
    }

    /**
     * Widget shuffle / repeat. Repeat goes off → repeat all → repeat the song → off; shuffle is on / off.
     * With the app open it makes the change (so its screen and settings follow), else the service does.
     */
    private fun widgetMode(action: String, p: Player) {
        val modes = getSharedPreferences("powersound_player", MODE_PRIVATE)
        if (action == WidgetUpdater.ACTION_REPEAT) {
            val next = when (modes.getString("repeat", null)) {
                "LIST_REPEAT", "ADVANCE_REPEAT" -> "SONG_REPEAT"
                "SONG_REPEAT" -> "LIST_STOP"
                else -> "LIST_REPEAT"
            }
            if (PlayerCommands.hasListener) PlayerCommands.flow.tryEmit("repeat:$next")
            else {
                modes.edit().putString("repeat", next).commit()
                p.repeatMode = when (next) {
                    "SONG_REPEAT" -> Player.REPEAT_MODE_ONE
                    "LIST_REPEAT" -> Player.REPEAT_MODE_ALL
                    else -> Player.REPEAT_MODE_OFF
                }
            }
        } else {
            val next = if ((modes.getString("shuffle", null) ?: "OFF") == "OFF") "SONGS" else "OFF"
            if (PlayerCommands.hasListener) PlayerCommands.flow.tryEmit("shuffle:$next")
            else {
                modes.edit().putString("shuffle", next).commit()
                p.shuffleModeEnabled = next == "SONGS"
            }
        }
        refreshWidget(force = true)
    }

    /**
     * Widget buttons. A tap can start this service while the app is closed: Android then wants it in the
     * foreground within seconds, so a short "PowerSound" notification is shown until Media3's own player
     * notification (same id) takes its place. When the queue is empty, the last one is brought back.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action != null && action.startsWith(WidgetUpdater.ACTION_PREFIX)) widgetCommand(action)
        return super.onStartCommand(intent, flags, startId)
    }

    private fun widgetCommand(action: String) {
        val s = session ?: return
        val p = main ?: return
        val active = s.player
        val inForeground = active.playWhenReady &&
            (active.playbackState == Player.STATE_READY || active.playbackState == Player.STATE_BUFFERING)
        if (!inForeground) holdForeground()
        val modeTap = action == WidgetUpdater.ACTION_REPEAT || action == WidgetUpdater.ACTION_SHUFFLE
        if (modeTap) {
            // shuffle / repeat change the mode only: nothing is restored or started
            widgetMode(action, p)
            if (p.mediaItemCount == 0) {
                releasePlaceholder()
                if (session?.connectedControllers.isNullOrEmpty()) stopSelf()
                return
            }
        } else {
            if (p.mediaItemCount == 0 && !restoreLastQueue(p)) {
                // nothing to play at all
                releasePlaceholder()
                if (session?.connectedControllers.isNullOrEmpty()) stopSelf()
                return
            }
            if (!isCasting && p.playbackState == Player.STATE_IDLE) p.prepare()
        }
        // the command runs first, while this service is in the foreground: when Media3's own player
        // notification arrives, it can stay there for as long as the music plays
        val sp = s.player
        when (action) {
            WidgetUpdater.ACTION_PLAY_PAUSE -> if (sp.playWhenReady) sp.pause() else sp.play()
            WidgetUpdater.ACTION_NEXT -> sp.seekToNext()
            WidgetUpdater.ACTION_PREV -> sp.seekToPrevious()
        }
        // Media3 shows its player notification once its controller is connected to the session
        addSession(s)
        refreshWidget(force = true)
        if (placeholderShown) {
            scope.launch {
                delay(400)
                placeholderShown = false
                // make sure the player notification replaced the short one (playing: in the foreground, paused: just shown)
                if (s.connectedControllers.any { s.isMediaNotificationController(it) }) {
                    val playing = sp.playWhenReady &&
                        (sp.playbackState == Player.STATE_READY || sp.playbackState == Player.STATE_BUFFERING)
                    runCatching { onUpdateNotification(s, playing) }
                }
            }
        }
    }

    private var placeholderShown = false

    private fun holdForeground() {
        if (android.os.Build.VERSION.SDK_INT < 26) return
        runCatching {
            val nm = getSystemService(android.app.NotificationManager::class.java)
            // the same channel and id as Media3's player notification, so it simply replaces this one
            if (nm.getNotificationChannel("default_channel_id") == null) {
                nm.createNotificationChannel(
                    android.app.NotificationChannel("default_channel_id", "Now playing", android.app.NotificationManager.IMPORTANCE_LOW)
                )
            }
            val title = main?.currentMediaItem?.mediaMetadata?.title?.toString() ?: "PowerSound"
            val n = androidx.core.app.NotificationCompat.Builder(this, "default_channel_id")
                .setSmallIcon(R.drawable.ic_w_note)
                .setContentTitle(title)
                .setContentText("PowerSound")
                .setOngoing(true)
                .setSilent(true)
                .build()
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                startForeground(1001, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(1001, n)
            }
            placeholderShown = true
        }
    }

    private fun releasePlaceholder() {
        if (!placeholderShown) return
        placeholderShown = false
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
            else @Suppress("DEPRECATION") stopForeground(true)
        }
    }

    /** Puts the queue the app saved last time back into the player. */
    private fun restoreLastQueue(p: ExoPlayer): Boolean {
        val (items, index, pos) = lastQueue() ?: return false
        p.setMediaItems(items, index, pos)
        p.prepare()
        return true
    }

    /** The queue the app saved last time (songs, current song, position), or null. */
    private fun lastQueue(): Triple<List<MediaItem>, Int, Long>? {
        val prefs = getSharedPreferences("powersound_player", MODE_PRIVATE)
        val ids = prefs.getString("queueIds", null)?.split(',')?.mapNotNull { it.toLongOrNull() } ?: return null
        if (ids.isEmpty()) return null
        val meta = HashMap<Long, Array<String?>>()
        for (chunk in ids.distinct().chunked(500)) {
            runCatching {
                contentResolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM),
                    "${MediaStore.Audio.Media._ID} IN (${chunk.joinToString(",")})", null, null
                )?.use { c ->
                    while (c.moveToNext()) meta[c.getLong(0)] = arrayOf(c.getString(1), c.getString(2), c.getString(3))
                }
            }
        }
        val known = ids.filter { it in meta }
        if (known.isEmpty()) return null
        val items = known.map { id ->
            val m = meta[id]
            val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
            fun clean(s: String?) = s?.trim()?.takeUnless { it.isEmpty() || it == "<unknown>" }
            MediaItem.Builder()
                .setMediaId(id.toString())
                .setUri(uri)
                .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
                .setMediaMetadata(
                    androidx.media3.common.MediaMetadata.Builder()
                        .setTitle(clean(m?.get(0)) ?: "Unknown")
                        .setArtist(clean(m?.get(1)) ?: "Unknown artist")
                        .setAlbumTitle(clean(m?.get(2)) ?: "")
                        .setArtworkUri(android.net.Uri.parse("$ART_SCHEME:$id"))
                        .build()
                )
                .build()
        }
        val currentId = ids.getOrNull(prefs.getInt("queueIndex", 0))
        val index = known.indexOf(currentId).coerceAtLeast(0)
        val pos = if (known.getOrNull(index) == currentId) prefs.getLong("queuePos", 0L) else 0L
        return Triple(items, index, pos)
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

        private var pauseFade: kotlinx.coroutines.Job? = null
        private var fadingOut = false

        /**
         * Settings → Crossfade, Fade, and Gapless → Fade Play/Pause: every pause (app, notification, lock screen,
         * widget, headset button, sleep timer) lowers the volume smoothly first. Unplugging the headphones still
         * stops at once (ExoPlayer handles that itself).
         */
        override fun pause() {
            crossfader?.abort()
            pauseFade?.cancel()
            val st = SettingsStore.value
            if (PlayerCommands.pauseNow || !st.fadeOnPause || st.fadeMs <= 0 || !p.isPlaying) {
                PlayerCommands.pauseNow = false
                fadingOut = false
                p.pause()
                p.volume = 1f
                return
            }
            val from = p.volume
            val steps = 20
            fadingOut = true
            pauseFade = scope.launch {
                for (i in steps - 1 downTo 0) {
                    // squared curve: sounds even to the ear, not "loud, loud, gone"
                    val f = i / steps.toFloat()
                    p.volume = from * f * f
                    delay(st.fadeMs / steps.toLong())
                }
                p.pause()
                p.volume = 1f
                fadingOut = false
            }
        }

        override fun play() {
            val st = SettingsStore.value
            val wasFadingOut = fadingOut
            pauseFade?.cancel()
            fadingOut = false
            if (p.isPlaying && !wasFadingOut) { super.play(); return } // already playing: leave the volume alone
            if (!st.fadeOnPause || st.fadeMs <= 0) { p.volume = 1f; super.play(); return }
            // start silent (or from where a pause fade was), then rise
            val from = if (wasFadingOut) p.volume else 0f
            p.volume = from
            super.play()
            val steps = 20
            pauseFade = scope.launch {
                for (i in 1..steps) {
                    delay(st.fadeMs / steps.toLong())
                    val f = i / steps.toFloat()
                    p.volume = from + (1f - from) * f * f
                }
                p.volume = 1f
            }
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
                if (((bt && st.resumeOnBluetooth) || (wired && st.resumeOnWired)) && !isCasting &&
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
                if (!SettingsStore.value.pauseOnVolumeZero || isCasting) return
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

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

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
        instance = null
        widgetJob?.cancel()
        // the widget keeps the song, with the play button ready to start again
        main?.let { p ->
            val md = p.currentMediaItem?.mediaMetadata
            if (md != null && WidgetUpdater.hasWidgets(this)) {
                WidgetUpdater.show(this, widgetState(p).copy(playing = false), widgetArt)
            }
        }
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
        castTicker?.cancel()
        holdCastLocks(false)
        castServer?.stop()
        com.powersound.player.cast.CastControl.setDevice(null)
        runCatching {
            castPlayer?.setSessionAvailabilityListener(null)
            castPlayer?.release()
        }
        localPlayer?.release()
        session?.release()
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
            3 -> { PlayerCommands.pauseNow = true; player.pause(); player.stop() } // "Close": pause and remove the notification
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
    private inner class SessionCallback : MediaLibrarySession.Callback {

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

        // ---- browsing (Android Auto) ----

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> = Futures.immediateFuture(LibraryResult.ofItem(car.root(), params))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val result = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
            scope.launch {
                val all = runCatching { car.children(parentId) }.getOrDefault(emptyList())
                val from = (page.toLong() * pageSize).coerceAtMost(all.size.toLong()).toInt()
                val to = (from.toLong() + pageSize).coerceAtMost(all.size.toLong()).toInt()
                result.set(LibraryResult.ofItemList(all.subList(from, to), params))
            }
            return result
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val result = SettableFuture.create<LibraryResult<MediaItem>>()
            scope.launch {
                val item = runCatching { car.item(mediaId) }.getOrNull()
                result.set(if (item != null) LibraryResult.ofItem(item, null) else LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
            }
            return result
        }

        /** A song picked in the car plays with the rest of its list. */
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val one = mediaItems.singleOrNull()
            if (one == null || !one.mediaId.startsWith("s:")) {
                return withStart(onAddMediaItems(mediaSession, controller, mediaItems), startIndex, startPositionMs)
            }
            val result = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            scope.launch {
                val list = runCatching { car.playlistFor(one.mediaId) }.getOrNull()
                if (list != null) {
                    result.set(MediaSession.MediaItemsWithStartPosition(list.first, list.second, C.TIME_UNSET))
                } else {
                    result.setFuture(withStart(onAddMediaItems(mediaSession, controller, mediaItems), startIndex, startPositionMs))
                }
            }
            return result
        }

        private fun withStart(
            items: ListenableFuture<MutableList<MediaItem>>,
            startIndex: Int,
            startPositionMs: Long
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = Futures.transform(
            items,
            { list -> MediaSession.MediaItemsWithStartPosition(list ?: emptyList(), startIndex, startPositionMs) },
            com.google.common.util.concurrent.MoreExecutors.directExecutor()
        )

        /** "Play" with nothing queued (the car, Bluetooth, the system media controls): the last queue. */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val last = lastQueue()
                ?: return Futures.immediateFailedFuture(UnsupportedOperationException("Nothing played yet"))
            return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(last.first, last.second, last.third))
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            val resolved = mediaItems.map { item ->
                val uri = item.requestMetadata.mediaUri
                    ?: item.localConfiguration?.uri
                    ?: car.songIdOf(item.mediaId)?.let {
                        ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, it)
                    }
                // the lock screen / notification art is loaded by LockArtLoader from this address
                val songId = car.songIdOf(item.mediaId)
                val art = songId?.let { android.net.Uri.parse("$ART_SCHEME:$it") }
                val md = if (art != null) item.mediaMetadata.buildUpon().setArtworkUri(art).build() else item.mediaMetadata
                // a song picked in the car keeps a plain id, like the app's own queue items
                val id = if (item.mediaId.startsWith("s:") && songId != null) songId.toString() else item.mediaId
                if (uri != null) item.buildUpon().setMediaId(id).setUri(uri).setMediaMetadata(md).build() else item
            }.toMutableList()
            return Futures.immediateFuture(resolved)
        }
    }
}
