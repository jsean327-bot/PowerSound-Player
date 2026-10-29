package com.powersound.player.audio

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.powersound.player.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Settings → Audio → Crossfade, Fade, and Gapless.
 *
 * A second, hidden player ("tail") keeps playing the end of the old track while the main player
 * already starts the next one, and their volumes cross (equal-power curves). The tail is started
 * muted a moment earlier and lined up with the main player, so the hand-over is seamless.
 */
@OptIn(UnstableApi::class)
class Crossfader(
    private val main: ExoPlayer,
    private val makeTail: () -> ExoPlayer,
    private val scope: CoroutineScope
) {
    private var tail: ExoPlayer? = null
    private var watchJob: Job? = null
    private var fadeJob: Job? = null
    private var preparing = false
    var fading = false
        private set

    private val mainListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) startWatching() else {
                watchJob?.cancel()
                if (!main.playWhenReady) abort()
            }
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            // the user seeked during a crossfade: stop the old track at once
            if (reason == Player.DISCONTINUITY_REASON_SEEK && fading && !switching) abort()
        }
    }
    private var switching = false

    init {
        main.addListener(mainListener)
    }

    fun release() {
        main.removeListener(mainListener)
        watchJob?.cancel()
        fadeJob?.cancel()
        tail?.release()
        tail = null
    }

    private fun tailPlayer(): ExoPlayer = tail ?: makeTail().also { tail = it }

    /** Stops a running crossfade and gives the main player its full volume back. */
    fun abort() {
        fadeJob?.cancel()
        fadeJob = null
        preparing = false
        if (fading || tail?.isPlaying == true) {
            tail?.stop()
            tail?.clearMediaItems()
        }
        fading = false
        main.volume = 1f
    }

    // ---------------------------------------------------------------- automatic (end of track)

    private fun startWatching() {
        if (watchJob?.isActive == true) return
        watchJob = scope.launch {
            while (isActive) {
                delay(50)
                if (!main.isPlaying || fading) continue
                val st = SettingsStore.value
                if (st.autoFade == 0 || st.perfectBitPerfect || st.silenceMs > 0 || com.powersound.player.PlayerCommands.stopAfterCurrent) continue
                val dur = main.duration
                val pos = main.currentPosition
                if (dur == C.TIME_UNSET || dur <= 0) continue
                val len = (st.crossfadeSec * 1000).toLong().coerceIn(500L, dur / 3)
                val remaining = dur - pos
                if (remaining > len + 1500 || remaining < len - 400) continue
                if (!shouldCrossfade(st.autoFade)) continue
                if (!preparing) {
                    // start the tail muted, a little ahead of the main player
                    preparing = true
                    val t = tailPlayer()
                    val item = main.currentMediaItem ?: continue
                    t.volume = 0f
                    t.setMediaItem(item, pos + 600)
                    t.prepare()
                    t.play()
                    continue
                }
                val t = tail ?: continue
                if (!t.isPlaying) {
                    if (remaining <= len - 300) { preparing = false; t.stop() } // too late: no crossfade this time
                    continue
                }
                // line the tail up with the main player (positions follow the real audio output)
                val drift = t.currentPosition - main.currentPosition
                if (abs(drift) > 35 && remaining > len) {
                    t.seekTo((t.currentPosition - drift + 15).coerceAtLeast(0))
                    continue
                }
                if (remaining <= len) startCrossfade(len) { main.seekToNextMediaItem() }
            }
        }
    }

    private fun shouldCrossfade(mode: Int): Boolean {
        if (main.repeatMode == Player.REPEAT_MODE_ONE) return false
        val nextIndex = main.nextMediaItemIndex
        if (nextIndex == C.INDEX_UNSET) return false
        val cur = main.currentMediaItem ?: return false
        val next = main.getMediaItemAt(nextIndex)
        if (cur.mediaId.toLongOrNull() == null || next.mediaId.toLongOrNull() == null) return false // streams
        return when (mode) {
            1 -> {
                // "Non-gapless": keep albums played in order gapless
                val sameAlbum = cur.mediaMetadata.albumTitle != null && cur.mediaMetadata.albumTitle == next.mediaMetadata.albumTitle
                val ct = cur.mediaMetadata.trackNumber ?: -10
                val nt = next.mediaMetadata.trackNumber ?: -20
                !(sameAlbum && nt == ct + 1)
            }
            2 -> true
            3 -> main.shuffleModeEnabled
            else -> false
        }
    }

    /** The tail (already playing the old track in sync) takes over, the main player jumps and fades in. */
    private fun startCrossfade(lengthMs: Long, jump: () -> Unit) {
        val t = tail ?: return
        fading = true
        preparing = false
        fadeJob?.cancel()
        fadeJob = scope.launch {
            // 40 ms overlap of the same audio so the hand-over has no gap
            t.volume = 1f
            delay(40)
            switching = true
            main.volume = 0f
            jump()
            switching = false
            val start = System.currentTimeMillis()
            while (isActive) {
                val p = ((System.currentTimeMillis() - start).toFloat() / lengthMs).coerceIn(0f, 1f)
                main.volume = sin(p * PI / 2).toFloat()
                t.volume = cos(p * PI / 2).toFloat()
                if (p >= 1f) break
                delay(30)
            }
            t.stop()
            t.clearMediaItems()
            main.volume = 1f
            fading = false
        }
    }

    // ---------------------------------------------------------------- manual track change

    /**
     * Next / previous pressed while playing (Settings → Manual Track Change Fading).
     * Returns false when no fade is wanted, so the caller changes the track directly.
     */
    fun manualChange(jump: () -> Unit): Boolean {
        val st = SettingsStore.value
        if (st.manualFade == 0 || st.perfectBitPerfect || !main.isPlaying) return false
        val item = main.currentMediaItem ?: return false
        if (item.mediaId.toLongOrNull() == null) return false
        abort()
        val len = if (st.manualFade == 1) 450L else (st.crossfadeSec * 1000).toLong().coerceIn(500L, 12_000L)
        val t = tailPlayer()
        val startPos = main.currentPosition + 300
        t.volume = 0f
        t.setMediaItem(item, startPos)
        t.prepare()
        t.play()
        fading = true
        fadeJob = scope.launch {
            // wait (max 1 s) for the tail to sound, then hand over
            val until = System.currentTimeMillis() + 1000
            while (!t.isPlaying && System.currentTimeMillis() < until) delay(15)
            if (!t.isPlaying) {
                t.stop()
                fading = false
                jump()
                return@launch
            }
            fading = false
            startCrossfade(len, jump)
        }
        return true
    }
}
