package com.powersound.player.video

import android.content.Context
import android.media.AudioManager
import android.net.Uri
import android.provider.OpenableColumns
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.BrightnessMedium
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import com.powersound.player.data.SettingsStore
import com.powersound.player.data.formatTime
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** PlayerView picture sizes (AspectRatioFrameLayout FIT = 0, ZOOM = 4, FILL = 3). */
private val RESIZE_MODES = listOf(
    0 to "Fit",
    4 to "Fill (crop)",
    3 to "Stretch"
)

private val SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

/** One choice in the audio / subtitle lists. */
private class TrackChoice(val label: String, val selected: Boolean, val apply: () -> Unit)

/**
 * The video screen: the picture, gestures (left side brightness, right side volume, sideways to jump,
 * double tap ±10 s) and the controls on top.
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoScreen(activity: VideoActivity, player: ExoPlayer, onBack: () -> Unit) {
    val exo = player
    val context = LocalContext.current
    val primary = MaterialTheme.colorScheme.primary
    val settings by SettingsStore.settings.collectAsState()
    val inPip by activity.inPip
    val error by activity.error

    // ---- player state
    var playing by remember { mutableStateOf(player.isPlaying) }
    var buffering by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf(player.mediaMetadata.title?.toString() ?: "") }
    var duration by remember { mutableLongStateOf(0L) }
    var position by remember { mutableLongStateOf(0L) }
    var tracks by remember { mutableStateOf(player.currentTracks) }
    var hasPrev by remember { mutableStateOf(false) }
    var hasNext by remember { mutableStateOf(false) }
    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) {
                playing = p.isPlaying
                buffering = p.playbackState == Player.STATE_BUFFERING
                title = p.mediaMetadata.title?.toString() ?: ""
                duration = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
                tracks = p.currentTracks
                hasPrev = p.hasPreviousMediaItem()
                hasNext = p.hasNextMediaItem()
            }
        }
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }
    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition
            delay(if (playing) 250 else 500)
        }
    }

    // ---- controls
    var controls by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
    var poke by remember { mutableIntStateOf(0) } // any touch restarts the hide timer
    var resize by remember { mutableIntStateOf(0) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var seeking by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(controls, playing, poke, dialog, seeking) {
        if (controls && playing && dialog == null && seeking == null) {
            delay(3500)
            controls = false
        }
    }

    // ---- gesture feedback in the middle of the screen
    var hint by remember { mutableStateOf<Pair<ImageVector?, String>?>(null) }
    var hintTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(hintTick) {
        if (hint != null) {
            delay(700)
            hint = null
        }
    }
    fun show(icon: ImageVector?, text: String, stay: Boolean = false) {
        hint = icon to text
        if (!stay) hintTick++
    }

    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVolume = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }

    // ---- subtitle file picker
    val pickSubtitle = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.addSubtitle(uri, displayName(context, uri))
            show(Icons.Rounded.ClosedCaption, "Subtitles added")
        }
    }

    BackHandler {
        if (locked) {
            controls = true
            show(Icons.Rounded.Lock, "Unlock first")
        } else onBack()
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val widthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val heightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)

        // ---- the picture (and the subtitles)
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    useController = false
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    keepScreenOn = true
                    this.player = exo
                }
            },
            update = { view ->
                view.resizeMode = RESIZE_MODES[resize].first
                view.subtitleView?.apply {
                    setApplyEmbeddedStyles(true)
                    setApplyEmbeddedFontSizes(false)
                    setStyle(
                        CaptionStyleCompat(
                            android.graphics.Color.WHITE,
                            android.graphics.Color.TRANSPARENT,
                            android.graphics.Color.TRANSPARENT,
                            CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                            android.graphics.Color.BLACK,
                            null
                        )
                    )
                    setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * settings.videoSubSize)
                    setBottomPaddingFraction(if (inPip) 0.02f else 0.06f)
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        if (inPip) return@BoxWithConstraints

        // ---- touch layer: taps, double taps, drags
        var dragMode by remember { mutableIntStateOf(0) } // 0 none, 1 seek, 2 brightness, 3 volume
        var dragStartValue by remember { mutableFloatStateOf(0f) }
        var dragTotal by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
        var dragStartX by remember { mutableFloatStateOf(0f) }
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(locked) {
                    detectTapGestures(
                        onTap = {
                            poke++
                            controls = !controls
                        },
                        onDoubleTap = { at ->
                            if (locked) return@detectTapGestures
                            when {
                                at.x < widthPx / 3 -> {
                                    player.seekBack()
                                    show(Icons.Rounded.Replay10, "−10 s")
                                }
                                at.x > widthPx * 2 / 3 -> {
                                    player.seekForward()
                                    show(Icons.Rounded.Forward10, "+10 s")
                                }
                                else -> if (player.isPlaying) player.pause() else player.play()
                            }
                        }
                    )
                }
                .pointerInput(locked) {
                    if (locked) return@pointerInput
                    detectDragGestures(
                        onDragStart = { at ->
                            dragMode = 0
                            dragTotal = androidx.compose.ui.geometry.Offset.Zero
                            dragStartX = at.x
                        },
                        onDragEnd = {
                            if (dragMode == 1) {
                                player.seekTo(dragStartValue.toLong().coerceAtLeast(0L))
                            }
                            dragMode = 0
                            hintTick++
                        },
                        onDragCancel = {
                            dragMode = 0
                            hintTick++
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            dragTotal += amount
                            if (dragMode == 0) {
                                if (abs(dragTotal.x) > 24f && abs(dragTotal.x) > abs(dragTotal.y)) {
                                    dragMode = 1
                                    dragStartValue = player.currentPosition.toFloat()
                                } else if (abs(dragTotal.y) > 24f) {
                                    if (dragStartX < widthPx / 2) {
                                        dragMode = 2
                                        dragStartValue = activity.brightness()
                                    } else {
                                        dragMode = 3
                                        dragStartValue = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
                                    }
                                }
                                if (dragMode == 1) dragTotal = androidx.compose.ui.geometry.Offset.Zero
                                return@detectDragGestures
                            }
                            when (dragMode) {
                                1 -> {
                                    // the whole width = 90 seconds
                                    val base = dragStartValue
                                    val target = (base + amount.x / widthPx * 90_000f)
                                        .coerceIn(0f, (duration.takeIf { it > 0 } ?: Long.MAX_VALUE).toFloat())
                                    dragStartValue = target
                                    val delta = ((target - player.currentPosition) / 1000).roundToInt()
                                    show(null, "${if (delta >= 0) "+" else "−"}${formatTime(abs(delta) * 1000L)}\n${formatTime(target.toLong())}", stay = true)
                                }
                                2 -> {
                                    val v = (dragStartValue - dragTotal.y / heightPx * 1.2f).coerceIn(0.01f, 1f)
                                    activity.setBrightness(v)
                                    show(Icons.Rounded.BrightnessMedium, "${(v * 100).roundToInt()}%", stay = true)
                                }
                                3 -> {
                                    val v = (dragStartValue - dragTotal.y / heightPx * maxVolume * 1.2f).roundToInt().coerceIn(0, maxVolume)
                                    if (v != audio.getStreamVolume(AudioManager.STREAM_MUSIC)) {
                                        audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
                                    }
                                    show(Icons.Rounded.VolumeUp, "${v * 100 / maxVolume}%", stay = true)
                                }
                            }
                        }
                    )
                }
        )

        if (buffering) CircularProgressIndicator(Modifier.align(Alignment.Center).size(48.dp), color = primary)

        // ---- gesture feedback
        hint?.let { (icon, text) ->
            Column(
                Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 22.dp, vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (icon != null) Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(30.dp))
                Text(text, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }

        // ---- a playback problem
        error?.let { msg ->
            Column(
                Modifier
                    .align(Alignment.Center)
                    .padding(32.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xE61B1B22))
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(msg, color = Color.White, fontSize = 15.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Spacer(Modifier.height(10.dp))
                Row {
                    if (hasNext) TextButton(onClick = { player.seekToNextMediaItem(); player.prepare(); player.play() }) { Text("Next video") }
                    TextButton(onClick = { activity.error.value = null; player.prepare(); player.play() }) { Text("Try again") }
                    TextButton(onClick = onBack) { Text("Close") }
                }
            }
        }

        // ---- locked: only the unlock button
        if (locked) {
            AnimatedVisibility(controls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.CenterStart)) {
                RoundIcon(Icons.Rounded.Lock, "Unlock", Modifier.padding(start = 24.dp)) {
                    locked = false
                    poke++
                    show(Icons.Rounded.LockOpen, "Unlocked")
                }
            }
            return@BoxWithConstraints
        }

        // ---- the controls
        AnimatedVisibility(controls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                // top: back, title, subtitles, audio, speed, more
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
                        .safeDrawingPadding()
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BarIcon(Icons.Rounded.ArrowBack, "Back", onBack)
                    Text(
                        title,
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(horizontal = 6.dp)
                    )
                    BarIcon(Icons.Rounded.ClosedCaption, "Subtitles") { dialog = "subs" }
                    BarIcon(Icons.Rounded.Audiotrack, "Audio track") { dialog = "audio" }
                    BarIcon(Icons.Rounded.Speed, "Speed") { dialog = "speed" }
                    BarIcon(Icons.Rounded.MoreVert, "More") { dialog = "more" }
                }

                // middle: previous, −10, play/pause, +10, next
                Row(
                    Modifier.align(Alignment.Center),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    if (hasPrev) RoundIcon(Icons.Rounded.SkipPrevious, "Previous video") { poke++; player.seekToPreviousMediaItem() }
                    RoundIcon(Icons.Rounded.Replay10, "Back 10 seconds") { poke++; player.seekBack() }
                    RoundIcon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play", big = true) {
                        poke++
                        if (player.isPlaying) player.pause()
                        else {
                            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0L)
                            player.play()
                        }
                    }
                    RoundIcon(Icons.Rounded.Forward10, "Forward 10 seconds") { poke++; player.seekForward() }
                    if (hasNext) RoundIcon(Icons.Rounded.SkipNext, "Next video") { poke++; player.seekToNextMediaItem() }
                }

                // bottom: time bar and tools
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))))
                        .safeDrawingPadding()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val shown = seeking?.toLong() ?: position
                        Text(formatTime(shown), color = Color.White, fontSize = 12.sp)
                        Slider(
                            value = if (duration > 0) ((seeking ?: position.toFloat()) / duration).coerceIn(0f, 1f) else 0f,
                            onValueChange = { f -> seeking = f * duration; poke++ },
                            onValueChangeFinished = {
                                seeking?.let { player.seekTo(it.toLong()) }
                                seeking = null
                            },
                            enabled = duration > 0,
                            colors = SliderDefaults.colors(thumbColor = primary, activeTrackColor = primary, inactiveTrackColor = Color.White.copy(alpha = 0.3f)),
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                        )
                        Text(if (duration > 0) formatTime(duration) else "--:--", color = Color.White, fontSize = 12.sp)
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        BarIcon(Icons.Rounded.Lock, "Lock the screen") {
                            locked = true
                            controls = false
                            show(Icons.Rounded.Lock, "Locked")
                        }
                        BarIcon(Icons.Rounded.AspectRatio, "Picture size") {
                            poke++
                            resize = (resize + 1) % RESIZE_MODES.size
                            show(Icons.Rounded.AspectRatio, RESIZE_MODES[resize].second)
                        }
                        BarIcon(Icons.Rounded.ScreenRotation, "Rotate") { poke++; activity.rotate() }
                        Spacer(Modifier.weight(1f))
                        val speed = player.playbackParameters.speed
                        if (speed != 1f) Text("${trimSpeed(speed)}×", color = primary, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 8.dp))
                        if (activity.canPip()) BarIcon(Icons.Rounded.PictureInPictureAlt, "Small window") { activity.enterPip() }
                    }
                }
            }
        }
    }

    // ---- dialogs
    when (dialog) {
        "audio" -> ChoiceDialog(
            title = "Audio track",
            choices = audioChoices(player, tracks),
            empty = "This video has one audio track.",
            onDismiss = { dialog = null }
        )
        "subs" -> ChoiceDialog(
            title = "Subtitles",
            choices = subtitleChoices(player, tracks),
            empty = null,
            onDismiss = { dialog = null },
            footer = {
                TextButton(onClick = {
                    dialog = null
                    pickSubtitle.launch(arrayOf("*/*"))
                }) { Text("Add subtitle file (.srt / .ass / .vtt)…") }
            }
        )
        "speed" -> ChoiceDialog(
            title = "Speed",
            choices = SPEEDS.map { s ->
                TrackChoice("${trimSpeed(s)}×", player.playbackParameters.speed == s) { player.setPlaybackSpeed(s) }
            },
            empty = null,
            onDismiss = { dialog = null }
        )
        "more" -> MoreDialog(onDismiss = { dialog = null })
    }
}

private fun trimSpeed(s: Float): String = if (s == s.toInt().toFloat()) s.toInt().toString() else s.toString()

@OptIn(UnstableApi::class)
private fun audioChoices(player: ExoPlayer, tracks: Tracks): List<TrackChoice> {
    val out = ArrayList<TrackChoice>()
    var n = 0
    for (g in tracks.groups) {
        if (g.type != C.TRACK_TYPE_AUDIO) continue
        for (i in 0 until g.length) {
            if (!g.isTrackSupported(i)) continue
            n++
            out += TrackChoice(trackName(g.getTrackFormat(i), n, audio = true), g.isTrackSelected(i)) {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                    .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i))
                    .build()
            }
        }
    }
    return if (out.size > 1) out else emptyList()
}

@OptIn(UnstableApi::class)
private fun subtitleChoices(player: ExoPlayer, tracks: Tracks): List<TrackChoice> {
    val off = player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
    val out = ArrayList<TrackChoice>()
    var anySelected = false
    var n = 0
    for (g in tracks.groups) {
        if (g.type != C.TRACK_TYPE_TEXT) continue
        for (i in 0 until g.length) {
            if (!g.isTrackSupported(i)) continue
            n++
            val sel = !off && g.isTrackSelected(i)
            anySelected = anySelected || sel
            out += TrackChoice(trackName(g.getTrackFormat(i), n, audio = false), sel) {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i))
                    .build()
            }
        }
    }
    out.add(0, TrackChoice("Off", off || !anySelected) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
    })
    return out
}

/** "English • 5.1 • AC3", "Arabic", "Track 2"… */
@OptIn(UnstableApi::class)
private fun trackName(f: Format, n: Int, audio: Boolean): String {
    val parts = ArrayList<String>()
    f.label?.takeIf { it.isNotBlank() }?.let { parts += it }
    f.language?.takeIf { it.isNotBlank() && it != "und" }?.let { code ->
        val name = Locale.forLanguageTag(code).displayLanguage.ifBlank { code }
        if (parts.none { it.contains(name, ignoreCase = true) }) parts += name
    }
    if (audio && f.channelCount != Format.NO_VALUE) {
        parts += when (f.channelCount) {
            1 -> "Mono"
            2 -> "Stereo"
            6 -> "5.1"
            8 -> "7.1"
            else -> "${f.channelCount} ch"
        }
    }
    val mime = f.sampleMimeType ?: f.containerMimeType
    val codec = when {
        mime == null -> null
        mime.contains("subrip") -> "SRT"
        mime.contains("ssa") -> "ASS"
        mime.contains("vtt") -> "VTT"
        mime.contains("pgs") -> "PGS"
        mime.contains("eac3") -> "E-AC3"
        mime.contains("ac3") -> "AC3"
        mime.contains("dts") -> "DTS"
        mime.contains("mp4a") || mime.contains("aac") -> "AAC"
        mime.contains("opus") -> "Opus"
        mime.contains("flac") -> "FLAC"
        mime.contains("mpeg") -> "MP3"
        else -> null
    }
    codec?.let { parts += it }
    return parts.joinToString(" • ").ifBlank { "Track $n" }
}

private fun displayName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
}.getOrNull()

@Composable
private fun BarIcon(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = description, tint = Color.White, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun RoundIcon(icon: ImageVector, description: String, modifier: Modifier = Modifier, big: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier
            .size(if (big) 72.dp else 52.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = description, tint = Color.White, modifier = Modifier.size(if (big) 44.dp else 30.dp))
    }
}

@Composable
private fun ChoiceDialog(
    title: String,
    choices: List<TrackChoice>,
    empty: String?,
    onDismiss: () -> Unit,
    footer: (@Composable () -> Unit)? = null
) {
    val primary = MaterialTheme.colorScheme.primary
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1B1B22),
        title = { Text(title, color = Color.White) },
        text = {
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                if (choices.isEmpty() && empty != null) Text(empty, color = Color.White.copy(alpha = 0.7f))
                choices.forEach { c ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { c.apply(); onDismiss() }
                            .padding(horizontal = 8.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(c.label, color = if (c.selected) primary else Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        if (c.selected) Icon(Icons.Rounded.Check, contentDescription = null, tint = primary)
                    }
                }
                footer?.invoke()
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

/** ⋮ : subtitle size, small window when leaving. */
@Composable
private fun MoreDialog(onDismiss: () -> Unit) {
    val st by SettingsStore.settings.collectAsState()
    val primary = MaterialTheme.colorScheme.primary
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1B1B22),
        title = { Text("Video settings", color = Color.White) },
        text = {
            Column {
                Text("Subtitle size", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0.8f to "Small", 1f to "Normal", 1.25f to "Large", 1.5f to "Huge").forEach { (v, label) ->
                        val on = abs(st.videoSubSize - v) < 0.01f
                        Text(
                            label,
                            color = if (on) Color.Black else Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(if (on) primary else Color.White.copy(alpha = 0.10f))
                                .clickable { SettingsStore.update { it.copy(videoSubSize = v) } }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(
                    Modifier.fillMaxWidth().clickable { SettingsStore.update { it.copy(videoAutoPip = !it.videoAutoPip) } },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Small window on Home", color = Color.White, fontSize = 15.sp)
                        Text("Pressing Home keeps the video playing in a small window", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                    }
                    Switch(checked = st.videoAutoPip, onCheckedChange = { v -> SettingsStore.update { it.copy(videoAutoPip = v) } })
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "Gestures: left side up/down = brightness, right side = volume, slide sideways = jump, " +
                        "double tap left/right = 10 seconds.",
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 12.sp
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}
