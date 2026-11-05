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
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.CompareArrows
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.NightsStay
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import com.powersound.player.data.DEFAULT_VIDEO_TOOLS
import com.powersound.player.data.SettingsStore
import com.powersound.player.data.formatTime
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/** PlayerView picture sizes (AspectRatioFrameLayout FIT = 0, ZOOM = 4, FILL = 3). */
private val RESIZE_MODES = listOf(
    0 to "Fit",
    4 to "Fill (crop)",
    3 to "Stretch"
)

private val SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)

/** One choice in the audio / subtitle lists. */
private class TrackChoice(val label: String, val selected: Boolean, val apply: () -> Unit)

/** A tool in the ⋮ strip. */
private class Tool(val id: String, val label: String, val icon: ImageVector)

private val TOOLS = listOf(
    Tool("night", "Night Mode", Icons.Rounded.NightsStay),
    Tool("shuffle", "Shuffle", Icons.Rounded.Shuffle),
    Tool("loop", "Loop", Icons.Rounded.Repeat),
    Tool("mute", "Mute", Icons.Rounded.VolumeOff),
    Tool("sleep", "Sleep Timer", Icons.Rounded.Timer),
    Tool("ab", "A - B Repeat", Icons.Rounded.CompareArrows),
    Tool("eq", "Equalizer", Icons.Rounded.Tune),
    Tool("speed", "Speed", Icons.Rounded.Speed),
    Tool("screenshot", "Screenshot", Icons.Rounded.PhotoCamera),
    Tool("background", "Background Play", Icons.Rounded.Headphones),
    Tool("rotation", "Screen Rotation", Icons.Rounded.ScreenRotation)
)

/**
 * The video screen, laid out like MX Player: top bar (back, title, Cast, audio, subtitles, decoder, ⋮),
 * the ⋮ tools strip, the time bar, and lock / previous / play / next / picture size / small window at the
 * bottom. Gestures: left side brightness, right side volume, sideways to jump, double tap ±10 s.
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoScreen(activity: VideoActivity, onBack: () -> Unit) {
    val player = activity.playerState.value ?: return
    val context = LocalContext.current
    val primary = MaterialTheme.colorScheme.primary
    val settings by SettingsStore.settings.collectAsState()
    val inPip by activity.inPip
    val error by activity.error
    val night by activity.night
    val muted by activity.muted
    val castingTo by activity.castingTo
    val abA by activity.abA
    val abB by activity.abB
    val sleepAt by activity.sleepAt

    // ---- player state (follows the player when it is rebuilt)
    var playing by remember(player) { mutableStateOf(player.isPlaying) }
    var buffering by remember(player) { mutableStateOf(false) }
    var title by remember(player) { mutableStateOf(player.mediaMetadata.title?.toString() ?: "") }
    var duration by remember(player) { mutableLongStateOf(0L) }
    var position by remember(player) { mutableLongStateOf(0L) }
    var tracks by remember(player) { mutableStateOf(player.currentTracks) }
    var hasPrev by remember(player) { mutableStateOf(false) }
    var hasNext by remember(player) { mutableStateOf(false) }
    var repeat by remember(player) { mutableIntStateOf(player.repeatMode) }
    var shuffle by remember(player) { mutableStateOf(player.shuffleModeEnabled) }
    var speed by remember(player) { mutableFloatStateOf(player.playbackParameters.speed) }
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
                repeat = p.repeatMode
                shuffle = p.shuffleModeEnabled
                speed = p.playbackParameters.speed
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
    var tools by remember { mutableStateOf(false) }
    var locked by remember { mutableStateOf(false) }
    var poke by remember { mutableIntStateOf(0) } // any touch restarts the hide timer
    var resize by remember { mutableIntStateOf(0) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var seeking by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(controls, playing, poke, dialog, seeking, tools) {
        if (controls && playing && dialog == null && seeking == null) {
            delay(if (tools) 6000 else 3500)
            controls = false
            tools = false
        }
    }

    // ---- gesture feedback in the middle of the screen
    var hint by remember { mutableStateOf<Pair<ImageVector?, String>?>(null) }
    var hintTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(hintTick) {
        if (hint != null) {
            delay(900)
            hint = null
        }
    }
    fun show(icon: ImageVector?, text: String, stay: Boolean = false) {
        hint = icon to text
        if (!stay) hintTick++
    }

    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVolume = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }

    // ---- file pickers: subtitles, another sound track
    val pickSubtitle = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.addSubtitle(uri, displayName(context, uri))
            show(Icons.Rounded.Subtitles, "Subtitles added")
        }
    }
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            activity.openExternalAudio(uri)
            show(Icons.Rounded.MusicNote, "Playing ${displayName(context, uri) ?: "the sound file"}")
        }
    }

    BackHandler {
        when {
            locked -> {
                controls = true
                show(Icons.Rounded.Lock, "Unlock first")
            }
            tools -> tools = false
            else -> onBack()
        }
    }

    fun runTool(id: String) {
        poke++
        when (id) {
            "night" -> {
                activity.night.value = !night
                show(Icons.Rounded.NightsStay, if (!night) "Night mode on" else "Night mode off")
            }
            "customise" -> dialog = "customise"
            "shuffle" -> {
                player.shuffleModeEnabled = !player.shuffleModeEnabled
                show(Icons.Rounded.Shuffle, if (player.shuffleModeEnabled) "Shuffle on" else "Shuffle off")
            }
            "loop" -> {
                val m = activity.cycleLoop()
                show(
                    if (m == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                    when (m) { Player.REPEAT_MODE_ONE -> "Loop this video"; Player.REPEAT_MODE_ALL -> "Loop all"; else -> "Loop off" }
                )
            }
            "mute" -> {
                activity.setMuted(!muted)
                show(if (!muted) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp, if (!muted) "Muted" else "Sound on")
            }
            "sleep" -> dialog = "sleep"
            "ab" -> show(Icons.Rounded.CompareArrows, activity.abPress())
            "eq" -> dialog = "eq"
            "speed" -> dialog = "speed"
            "screenshot" -> {
                tools = false
                controls = false
                activity.screenshot { msg -> show(Icons.Rounded.PhotoCamera, msg) }
            }
            "background" -> {
                val on = !settings.videoBackground
                SettingsStore.update { it.copy(videoBackground = on) }
                show(Icons.Rounded.Headphones, if (on) "Background play on: leave the video, the sound goes on" else "Background play off")
            }
            "rotation" -> dialog = "rotation"
            "settings" -> dialog = "more"
        }
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
                    activity.playerView = this
                }
            },
            update = { view ->
                if (view.player !== player) view.player = player
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

        // ---- night mode: a dark veil over the picture
        if (night) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))

        if (inPip) return@BoxWithConstraints

        // ---- playing on a Chromecast
        castingTo?.let { device ->
            CastPanel(activity, device, title)
            return@BoxWithConstraints
        }

        // ---- touch layer: taps, double taps, drags
        var dragMode by remember { mutableIntStateOf(0) } // 0 none, 1 seek, 2 brightness, 3 volume
        var dragStartValue by remember { mutableFloatStateOf(0f) }
        var dragTotal by remember { mutableStateOf(Offset.Zero) }
        var dragStartX by remember { mutableFloatStateOf(0f) }
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(locked, player) {
                    detectTapGestures(
                        onTap = {
                            poke++
                            if (tools) tools = false else controls = !controls
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
                                else -> activity.togglePlay()
                            }
                        }
                    )
                }
                .pointerInput(locked, player) {
                    if (locked) return@pointerInput
                    detectDragGestures(
                        onDragStart = { at ->
                            dragMode = 0
                            dragTotal = Offset.Zero
                            dragStartX = at.x
                        },
                        onDragEnd = {
                            if (dragMode == 1) player.seekTo(dragStartValue.toLong().coerceAtLeast(0L))
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
                                    dragTotal = Offset.Zero
                                }
                                return@detectDragGestures
                            }
                            when (dragMode) {
                                1 -> {
                                    // the whole width = 90 seconds
                                    val target = (dragStartValue + amount.x / widthPx * 90_000f)
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
                                        runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0) }
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
                    .padding(24.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 22.dp, vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (icon != null) Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(30.dp))
                Text(text, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
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
                Text(msg, color = Color.White, fontSize = 15.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(10.dp))
                Row {
                    TextButton(onClick = { dialog = "decoder" }) { Text("Decoder") }
                    if (hasNext) TextButton(onClick = { player.seekToNextMediaItem(); player.prepare(); player.play() }) { Text("Next") }
                    TextButton(onClick = { activity.error.value = null; player.prepare(); player.play() }) { Text("Retry") }
                    TextButton(onClick = onBack) { Text("Close") }
                }
            }
        }

        // ---- locked: only the unlock button
        if (locked) {
            AnimatedVisibility(controls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomStart)) {
                Box(Modifier.safeDrawingPadding().padding(16.dp)) {
                    RoundIcon(Icons.Rounded.Lock, "Unlock") {
                        locked = false
                        poke++
                        show(Icons.Rounded.LockOpen, "Unlocked")
                    }
                }
            }
            return@BoxWithConstraints
        }

        // ---- the controls
        AnimatedVisibility(controls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent)))
                        .safeDrawingPadding()
                ) {
                    // top: back, title, Cast, audio, subtitles, decoder, ⋮
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
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
                        if (activity.canCast()) BarIcon(Icons.Rounded.Cast, "Cast") {
                            if (!activity.castButton()) dialog = "cast"
                        }
                        BarIcon(Icons.Rounded.MusicNote, "Audio") { dialog = "audio" }
                        BarIcon(Icons.Rounded.Subtitles, "Subtitles") { dialog = "subs" }
                        Box(
                            Modifier
                                .height(44.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { dialog = "decoder" }
                                .padding(horizontal = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(DECODERS[settings.videoDecoder.coerceIn(0, 2)], color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        }
                        BarIcon(Icons.Rounded.MoreVert, "More") { tools = !tools; poke++ }
                    }
                    // ⋮ tools
                    AnimatedVisibility(tools) {
                        val chosen = settings.videoTools
                        val ids = buildList {
                            if ("night" in chosen) add("night")
                            add("customise")
                            addAll(chosen.filter { it != "night" })
                            add("settings")
                        }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            ids.forEach { id ->
                                val t = TOOLS.firstOrNull { it.id == id }
                                val (icon, label) = when (id) {
                                    "customise" -> Icons.Rounded.Edit to "Customise Items"
                                    "settings" -> Icons.Rounded.Settings to "Settings"
                                    "speed" -> Icons.Rounded.Speed to "${trimSpeed(speed)}X"
                                    "loop" -> (if (repeat == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat) to "Loop"
                                    "mute" -> (if (muted) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp) to "Mute"
                                    else -> (t?.icon ?: Icons.Rounded.Settings) to (t?.label ?: id)
                                }
                                val active = when (id) {
                                    "night" -> night
                                    "shuffle" -> shuffle
                                    "loop" -> repeat != Player.REPEAT_MODE_OFF
                                    "mute" -> muted
                                    "sleep" -> sleepAt != null
                                    "ab" -> abA != null
                                    "eq" -> settings.videoEqOn
                                    "speed" -> speed != 1f
                                    "background" -> settings.videoBackground
                                    else -> false
                                }
                                ToolItem(icon, label, active, primary) { runTool(id) }
                            }
                        }
                    }
                }

                // bottom: what is on, the time bar, lock / previous / play / next / size / small window
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))))
                        .safeDrawingPadding()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    val chips = buildList {
                        if (abA != null) add(if (abB != null) "A-B ${formatTime(abA ?: 0)}–${formatTime(abB ?: 0)}" else "A ${formatTime(abA ?: 0)}")
                        sleepAt?.let { at -> add(if (at == -1L) "Sleep: end of video" else "Sleep ${formatTime((at - System.currentTimeMillis()).coerceAtLeast(0L))}") }
                        if (speed != 1f) add("${trimSpeed(speed)}×")
                        if (muted) add("Muted")
                        if (repeat == Player.REPEAT_MODE_ONE) add("Loop 1") else if (repeat == Player.REPEAT_MODE_ALL) add("Loop all")
                        if (shuffle) add("Shuffle")
                        if (activity.audioDelayMs.intValue != 0) add("Audio ${if (activity.audioDelayMs.intValue > 0) "+" else ""}${activity.audioDelayMs.intValue} ms")
                    }
                    if (chips.isNotEmpty()) {
                        Row(Modifier.padding(start = 4.dp, bottom = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            chips.forEach { c ->
                                Text(
                                    c,
                                    color = Color.Black,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        .background(primary)
                                        .padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
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
                            tools = false
                            show(Icons.Rounded.Lock, "Locked")
                        }
                        Spacer(Modifier.weight(1f))
                        BarIcon(Icons.Rounded.SkipPrevious, "Previous") {
                            poke++
                            if (hasPrev && player.currentPosition < 3000) player.seekToPreviousMediaItem() else player.seekTo(0L)
                        }
                        Spacer(Modifier.width(14.dp))
                        RoundIcon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play", big = true) {
                            poke++
                            activity.togglePlay()
                        }
                        Spacer(Modifier.width(14.dp))
                        BarIcon(Icons.Rounded.SkipNext, "Next") {
                            poke++
                            if (hasNext) player.seekToNextMediaItem() else show(Icons.Rounded.SkipNext, "Last video")
                        }
                        Spacer(Modifier.weight(1f))
                        BarIcon(Icons.Rounded.AspectRatio, "Picture size") {
                            poke++
                            resize = (resize + 1) % RESIZE_MODES.size
                            show(Icons.Rounded.AspectRatio, RESIZE_MODES[resize].second)
                        }
                        if (activity.canPip()) BarIcon(Icons.Rounded.PictureInPictureAlt, "Small window") { activity.enterPip() }
                    }
                }
            }
        }
    }

    // ---- dialogs
    when (dialog) {
        "audio" -> AudioDialog(
            activity = activity,
            player = player,
            tracks = tracks,
            onOpen = { dialog = null; pickAudio.launch(arrayOf("audio/*")) },
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
                }) { Text("Open subtitle file (.srt / .ass / .vtt)…") }
            }
        )
        "decoder" -> ChoiceDialog(
            title = "Select decoder",
            choices = DECODER_NAMES.mapIndexed { i, (name, about) ->
                TrackChoice("$name\n$about", settings.videoDecoder == i) {
                    if (settings.videoDecoder != i) {
                        SettingsStore.update { it.copy(videoDecoder = i) }
                        activity.rebuildPlayer()
                        show(null, DECODERS[i])
                    }
                }
            },
            empty = null,
            onDismiss = { dialog = null }
        )
        "speed" -> ChoiceDialog(
            title = "Speed",
            choices = SPEEDS.map { s ->
                TrackChoice("${trimSpeed(s)}×", speed == s) { player.setPlaybackSpeed(s) }
            },
            empty = null,
            onDismiss = { dialog = null }
        )
        "sleep" -> ChoiceDialog(
            title = "Sleep timer",
            choices = listOf(0 to "Off", -1 to "At the end of this video", 15 to "15 minutes", 30 to "30 minutes",
                45 to "45 minutes", 60 to "1 hour", 90 to "1 hour 30", 120 to "2 hours").map { (m, label) ->
                val on = when (m) { 0 -> sleepAt == null; -1 -> sleepAt == -1L; else -> false }
                TrackChoice(label, on) { activity.setSleep(m) }
            },
            empty = null,
            onDismiss = { dialog = null }
        )
        "rotation" -> ChoiceDialog(
            title = "Screen rotation",
            choices = ROTATIONS.mapIndexed { i, label -> TrackChoice(label, activity.rotation.intValue == i) { activity.setRotationMode(i) } },
            empty = null,
            onDismiss = { dialog = null }
        )
        "eq" -> EqualizerDialog(activity, onDismiss = { dialog = null })
        "customise" -> CustomiseDialog(onDismiss = { dialog = null })
        "more" -> MoreDialog(onDismiss = { dialog = null })
        "cast" -> com.powersound.player.ui.CastDialog(onDismiss = {
            dialog = null
            // nothing chosen (or still connecting is fine): the music may use the Chromecast again
            val connecting = runCatching { !androidx.mediarouter.media.MediaRouter.getInstance(context).selectedRoute.isDefault }.getOrDefault(false)
            if (activity.castingTo.value == null && !connecting) com.powersound.player.cast.CastControl.videoOwnsCast = false
        })
    }
}

/** While the video plays on a Chromecast: its name, the time bar, play / pause, ±10 s, stop. */
@Composable
private fun CastPanel(activity: VideoActivity, device: String, title: String) {
    val primary = MaterialTheme.colorScheme.primary
    val pos by activity.castPosition
    val dur by activity.castDuration
    val playing by activity.castPlaying
    var seeking by remember { mutableStateOf<Float?>(null) }
    Box(Modifier.fillMaxSize().background(Color(0xFF0E0E13)).safeDrawingPadding(), contentAlignment = Alignment.Center) {
        Column(Modifier.padding(24.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.CastConnected, contentDescription = null, tint = primary, modifier = Modifier.size(56.dp))
            Spacer(Modifier.height(10.dp))
            Text("Playing on $device", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(title, color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatTime(seeking?.toLong() ?: pos), color = Color.White, fontSize = 12.sp)
                Slider(
                    value = if (dur > 0) ((seeking ?: pos.toFloat()) / dur).coerceIn(0f, 1f) else 0f,
                    onValueChange = { seeking = it * dur },
                    onValueChangeFinished = {
                        seeking?.let { activity.castSeek(it.toLong()) }
                        seeking = null
                    },
                    enabled = dur > 0,
                    colors = SliderDefaults.colors(thumbColor = primary, activeTrackColor = primary),
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                )
                Text(if (dur > 0) formatTime(dur) else "--:--", color = Color.White, fontSize = 12.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                RoundIcon(Icons.Rounded.Replay10, "Back 10 seconds") { activity.castSeek(pos - 10_000) }
                RoundIcon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play", big = true) { activity.castToggle() }
                RoundIcon(Icons.Rounded.Forward10, "Forward 10 seconds") { activity.castSeek(pos + 10_000) }
            }
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = { activity.stopCasting() }) { Text("Stop casting") }
            Text(
                "The phone's volume buttons change the TV's volume. The Chromecast plays the file itself, " +
                    "so subtitles, audio settings and the equalizer are not used there.",
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 12.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun ToolItem(icon: ImageVector, label: String, active: Boolean, accent: Color, onClick: () -> Unit) {
    Column(
        Modifier
            .width(84.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = label, tint = if (active) accent else Color.White, modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            color = if (active) accent else Color.White,
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            maxLines = 2,
            lineHeight = 13.sp
        )
    }
}

/** Audio: tracks, Disable, Use SW audio decoder, Open, Stereo mode, Synchronization. */
@OptIn(UnstableApi::class)
@Composable
private fun AudioDialog(activity: VideoActivity, player: ExoPlayer, tracks: Tracks, onOpen: () -> Unit, onDismiss: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val st by SettingsStore.settings.collectAsState()
    var page by remember { mutableIntStateOf(0) } // 0 main, 1 stereo mode, 2 synchronization
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1B1B22),
        title = { Text(when (page) { 1 -> "Stereo mode"; 2 -> "Synchronization"; else -> "Audio track" }, color = Color.White) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                when (page) {
                    1 -> STEREO_MODES.forEachIndexed { i, label ->
                        RadioLine(label, activity.stereoMode.intValue == i, primary) { activity.setStereoMode(i); page = 0 }
                    }
                    2 -> {
                        val d = activity.audioDelayMs.intValue
                        Text(
                            "Move the sound when it doesn't match the lips. + makes the sound later, − earlier.",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 13.sp
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(-500, -50).forEach { step ->
                                SmallButton("${step}") { activity.setAudioDelay(d + step) }
                            }
                            Text(
                                "${if (d > 0) "+" else ""}$d ms",
                                color = primary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 17.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.weight(1f)
                            )
                            listOf(50, 500).forEach { step ->
                                SmallButton("+$step") { activity.setAudioDelay(d + step) }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { activity.setAudioDelay(0) }) { Text("Reset") }
                    }
                    else -> {
                        val off = player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_AUDIO)
                        audioChoices(player, tracks, all = true).forEach { c ->
                            RadioLine(c.label, !off && c.selected, primary) { c.apply(); onDismiss() }
                        }
                        RadioLine("Disable", off, primary) {
                            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
                            onDismiss()
                        }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    SettingsStore.update { it.copy(videoSwAudio = !it.videoSwAudio) }
                                    activity.rebuildPlayer()
                                }
                                .padding(horizontal = 4.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = st.videoSwAudio,
                                onCheckedChange = {
                                    SettingsStore.update { s -> s.copy(videoSwAudio = it) }
                                    activity.rebuildPlayer()
                                },
                                colors = CheckboxDefaults.colors(checkedColor = primary)
                            )
                            Text("Use SW audio decoder", color = Color.White, fontSize = 15.sp)
                        }
                        Box(Modifier.fillMaxWidth().padding(vertical = 6.dp).height(1.dp).background(Color.White.copy(alpha = 0.12f)))
                        MenuLine("Open", "Play another sound file with this video", onOpen)
                        MenuLine("Stereo mode", STEREO_MODES[activity.stereoMode.intValue]) { page = 1 }
                        val d = activity.audioDelayMs.intValue
                        MenuLine("Synchronization", if (d == 0) "In sync" else "${if (d > 0) "+" else ""}$d ms") { page = 2 }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (page != 0) page = 0 else onDismiss() }) { Text(if (page != 0) "Back" else "Close") }
        }
    )
}

@Composable
private fun RadioLine(label: String, selected: Boolean, accent: Color, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick, colors = RadioButtonDefaults.colors(selectedColor = accent))
        Text(label, color = Color.White, fontSize = 15.sp)
    }
}

@Composable
private fun MenuLine(label: String, sub: String?, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp)
    ) {
        Text(label, color = Color.White, fontSize = 15.sp)
        if (!sub.isNullOrBlank()) Text(sub, color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp)
    }
}

@Composable
private fun SmallButton(label: String, onClick: () -> Unit) {
    Text(
        label,
        color = Color.White,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    )
}

/** ⋮ → Equalizer: on/off, presets, the bands, bass boost. Kept for the next videos. */
@Composable
private fun EqualizerDialog(activity: VideoActivity, onDismiss: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val st = SettingsStore.value
    val eq = activity.equalizer
    var on by remember { mutableStateOf(st.videoEqOn) }
    var bass by remember { mutableIntStateOf(st.videoBass) }
    val bands = remember { eq?.let { e -> (0 until e.numberOfBands.toInt()) }?.toList().orEmpty() }
    val levels = remember { mutableStateListOf<Int>().apply { bands.forEach { b -> add(runCatching { eq!!.getBandLevel(b.toShort()).toInt() }.getOrDefault(0)) } } }
    val range = remember { runCatching { eq!!.bandLevelRange.let { it[0].toInt() to it[1].toInt() } }.getOrDefault(-1500 to 1500) }
    val presets = remember { eq?.let { e -> (0 until e.numberOfPresets.toInt()).map { it to e.getPresetName(it.toShort()) } }.orEmpty() }
    fun close() {
        activity.saveEqualizer(on, bass)
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = { close() },
        containerColor = Color(0xFF1B1B22),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Equalizer", color = Color.White, modifier = Modifier.weight(1f))
                Switch(checked = on, onCheckedChange = { on = it; runCatching { eq?.enabled = it } })
            }
        },
        text = {
            if (eq == null) {
                Text("This phone has no equalizer for videos.", color = Color.White.copy(alpha = 0.7f))
                return@AlertDialog
            }
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                if (presets.isNotEmpty()) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        presets.forEach { (i, name) ->
                            SmallButton(name) {
                                runCatching {
                                    eq.usePreset(i.toShort())
                                    bands.forEachIndexed { k, b -> levels[k] = eq.getBandLevel(b.toShort()).toInt() }
                                }
                                on = true
                                runCatching { eq.enabled = true }
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }
                bands.forEachIndexed { k, b ->
                    val hz = runCatching { eq.getCenterFreq(b.toShort()) / 1000 }.getOrDefault(0)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (hz >= 1000) "${hz / 1000} kHz" else "$hz Hz",
                            color = Color.White.copy(alpha = 0.75f),
                            fontSize = 12.sp,
                            modifier = Modifier.width(60.dp)
                        )
                        Slider(
                            value = levels[k].toFloat(),
                            onValueChange = { v ->
                                levels[k] = v.roundToInt()
                                runCatching { eq.setBandLevel(b.toShort(), v.roundToInt().toShort()) }
                                if (!on) { on = true; runCatching { eq.enabled = true } }
                            },
                            valueRange = range.first.toFloat()..range.second.toFloat(),
                            colors = SliderDefaults.colors(thumbColor = primary, activeTrackColor = primary),
                            modifier = Modifier.weight(1f)
                        )
                        Text("${"%+.0f".format(levels[k] / 100f)} dB", color = Color.White, fontSize = 12.sp, modifier = Modifier.width(52.dp), textAlign = TextAlign.End)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Bass boost", color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp)
                Slider(
                    value = bass.toFloat(),
                    onValueChange = { bass = it.roundToInt(); if (!on) on = true },
                    valueRange = 0f..1000f,
                    colors = SliderDefaults.colors(thumbColor = primary, activeTrackColor = primary)
                )
                TextButton(onClick = {
                    runCatching { bands.forEachIndexed { k, b -> eq.setBandLevel(b.toShort(), 0); levels[k] = 0 } }
                    bass = 0
                }) { Text("Reset") }
            }
        },
        confirmButton = { TextButton(onClick = { close() }) { Text("Close") } }
    )
}

/** ⋮ → Customise Items: which tools show in the strip. */
@Composable
private fun CustomiseDialog(onDismiss: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val st by SettingsStore.settings.collectAsState()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1B1B22),
        title = { Text("Customise items", color = Color.White) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                TOOLS.forEach { t ->
                    val shown = t.id in st.videoTools
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                SettingsStore.update { s ->
                                    s.copy(videoTools = if (shown) s.videoTools - t.id else DEFAULT_VIDEO_TOOLS.filter { it in s.videoTools || it == t.id })
                                }
                            }
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = shown,
                            onCheckedChange = null,
                            colors = CheckboxDefaults.colors(checkedColor = primary)
                        )
                        Icon(t.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(t.label, color = Color.White, fontSize = 15.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}

private fun trimSpeed(s: Float): String = if (s == s.toInt().toFloat()) s.toInt().toString() else s.toString()

@OptIn(UnstableApi::class)
private fun audioChoices(player: ExoPlayer, tracks: Tracks, all: Boolean = false): List<TrackChoice> {
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
    return if (out.size > 1 || all) out else emptyList()
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
