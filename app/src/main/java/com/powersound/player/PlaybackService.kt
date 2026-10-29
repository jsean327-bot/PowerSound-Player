package com.powersound.player

import android.app.PendingIntent
import android.content.ContentUris
import android.content.Intent
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import com.powersound.player.audio.AudioEngine
import com.powersound.player.audio.PowerSoundProcessor
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import com.google.common.util.concurrent.ListenableFuture
import androidx.media3.common.Player
import androidx.media3.session.CommandButton
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.powersound.player.data.NOTIF_ACTIONS
import com.powersound.player.data.SettingsStore
import com.powersound.player.data.StatsStore

/**
 * Commands from the notification buttons that need the app's lists (category change, the 6 repeat modes…).
 * The view model listens while the app is alive; otherwise the service does a simple version itself.
 */
object PlayerCommands {
    val flow = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 8)
    val hasListener: Boolean get() = flow.subscriptionCount.value > 0
}

/**
 * Background playback. Media3 builds the notification and the lock-screen
 * controls automatically from this session.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)
    private var deviceCallback: android.media.AudioDeviceCallback? = null

    override fun onCreate() {
        super.onCreate()

        AudioEngine.init(this)
        com.powersound.player.data.SettingsStore.init(this)
        com.powersound.player.data.CrashLog.install(this)

        // Audio chain: decoder -> PowerSound DSP (EQ, bass/treble, preamp, limiter) -> 16-bit output.
        // 16-bit PCM output kept on purpose: float output made some FLAC files distort on some phones.
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

        val player = ExoPlayer.Builder(this, renderers)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(com.powersound.player.data.SettingsStore.value.pauseOnDisconnect) // pause when earbuds disconnect
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

        val openApp = (packageManager.getLaunchIntentForPackage(packageName) ?: Intent(this, MainActivity::class.java))
            .putExtra(MainActivity.EXTRA_FROM_NOTIFICATION, true)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val sessionActivity = PendingIntent.getActivity(
            this, 0, openApp,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        StatsStore.init(this)
        session = MediaSession.Builder(this, player)
            .setCallback(SessionCallback())
            .setSessionActivity(sessionActivity)
            .build()

        // Settings → Headset/Bluetooth: "pause on disconnect" follows the setting live.
        // Settings → Notifications → Buttons: the extra buttons follow the setting live too.
        scope.launch {
            SettingsStore.settings.collect { st ->
                player.setHandleAudioBecomingNoisy(st.pauseOnDisconnect)
                session?.setCustomLayout(customLayout())
            }
        }
        player.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                // Settings → Notifications → Remove Notification on Disconnection
                if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY &&
                    SettingsStore.value.removeNotifOnDisconnect
                ) {
                    scope.launch { kotlinx.coroutines.delay(400); if (!player.playWhenReady) player.stop() }
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                // Settings → Notifications → Keep Notification (off: remove it when playback ends by itself)
                if (state == Player.STATE_ENDED && !SettingsStore.value.keepNotification) {
                    scope.launch {
                        kotlinx.coroutines.delay(2000)
                        if (player.playbackState == Player.STATE_ENDED) player.stop()
                    }
                }
            }
        })
        registerHeadsetResume(player)
    }

    /** Resume playback when Bluetooth / wired headphones connect (if switched on in Settings). */
    private fun registerHeadsetResume(player: ExoPlayer) {
        val am = getSystemService(android.media.AudioManager::class.java) ?: return
        val registeredAt = android.os.SystemClock.elapsedRealtime()
        val cb = object : android.media.AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out android.media.AudioDeviceInfo>) {
                // the callback also lists already-connected devices right after registering: ignore those
                if (android.os.SystemClock.elapsedRealtime() - registeredAt < 1500) return
                val st = com.powersound.player.data.SettingsStore.value
                val bt = addedDevices.any {
                    it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                        (android.os.Build.VERSION.SDK_INT >= 31 && it.type == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET)
                }
                val wired = addedDevices.any {
                    it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                        it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                        it.type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET
                }
                if (((bt && st.resumeOnBluetooth) || (wired && st.resumeOnWired)) &&
                    !player.isPlaying && player.mediaItemCount > 0
                ) {
                    // give the phone a moment to route audio to the new device
                    scope.launch {
                        kotlinx.coroutines.delay(1200)
                        if (player.playbackState == androidx.media3.common.Player.STATE_IDLE) player.prepare()
                        player.play()
                    }
                }
            }
        }
        am.registerAudioDeviceCallback(cb, null)
        deviceCallback = cb
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (com.powersound.player.data.SettingsStore.value.stopOnSwipeAway && player != null) {
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
        deviceCallback?.let { getSystemService(android.media.AudioManager::class.java)?.unregisterAudioDeviceCallback(it) }
        deviceCallback = null
        scope.cancel()
        session?.run {
            player.release()
            release()
        }
        session = null
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
