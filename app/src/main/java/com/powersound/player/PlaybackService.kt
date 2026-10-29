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

        val openApp = packageManager.getLaunchIntentForPackage(packageName)
        val sessionActivity = PendingIntent.getActivity(
            this, 0, openApp,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        session = MediaSession.Builder(this, player)
            .setCallback(SessionCallback())
            .setSessionActivity(sessionActivity)
            .build()

        // Settings → Headset/Bluetooth: "pause on disconnect" follows the setting live.
        scope.launch {
            com.powersound.player.data.SettingsStore.settings.collect { st ->
                player.setHandleAudioBecomingNoisy(st.pauseOnDisconnect)
            }
        }
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

    /** Restores the playable URI of each item sent from the app's controller. */
    private inner class SessionCallback : MediaSession.Callback {
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
