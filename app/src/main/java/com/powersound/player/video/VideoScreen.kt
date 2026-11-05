package com.powersound.player.video

import android.content.Context
import android.graphics.Typeface
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.provider.OpenableColumns
import android.text.Layout
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.foundation.layout.requiredSize
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
import androidx.compose.material.icons.rounded.DisplaySettings
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
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
import androidx.compose.material.icons.rounded.ZoomIn
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import com.powersound.player.R
import com.powersound.player.data.DEFAULT_VIDEO_TOOLS
import com.powersound.player.data.SettingsStore
import com.powersound.player.data.formatTime
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** Picture size button: fit, fill (crop), stretch, the video's real pixel size. */
private val RESIZE_MODES = listOf("Fit to screen", "Crop", "Stretch", "100%")

private val SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f, 4f)

/** One choice in the audio / subtitle lists. */
private class TrackChoice(val label: String, val selected: Boolean, val apply: () -> Unit)

/** A tool in the shortcuts strip. */
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

private fun typefaceOf(font: Int, bold: Boolean): Typeface? {
    val base = when (font) {
        1 -> Typeface.SANS_SERIF
        2 -> Typeface.SERIF
        3 -> Typeface.MONOSPACE
        4 -> Typeface.create("sans-serif-condensed", Typeface.NORMAL)
        5 -> Typeface.create("sans-serif-light", Typeface.NORMAL)
        6 -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
        7 -> Typeface.create("sans-serif-black", Typeface.NORMAL)
        else -> if (bold) Typeface.DEFAULT else null
    }
    return if (bold && base != null) Typeface.create(base, Typeface.BOLD) else base
}

/**
 * The video screen, laid out like MX Player: top bar (back, title, Cast, audio, subtitles, decoder, ⋮),
 * the shortcuts strip, the time bar, and lock / previous / play / next / picture size / small window at the
 * bottom. Everything is set in ⋮ → Display Settings.
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoScreen(activity: VideoActivity, onBack: () -> Unit) {
    val player = activity.playerState.value ?: return
    val context = LocalContext.current
    val appPrimary = MaterialTheme.colorScheme.primary
    val settings by SettingsStore.settings.collectAsState()
    val vp by VideoPrefsStore.prefs.collectAsState()
    val primary = if (vp.progressColor == 0) appPrimary else Color(vp.progressColor)
    val controlColor = Color(vp.controlColor)
    val frame = Color(vp.frameColor)
    val inPip by activity.inPip
    val error by activity.error
    val night by activity.night
    val muted by activity.muted
    val castingTo by activity.castingTo
    val abA by activity.abA
    val abB by activity.abB
    val sleepAt by activity.sleepAt
    val aspect by activity.aspect

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
    var videoSize by remember(player) { mutableStateOf(player.videoSize) }
    var cues by remember(player) { mutableStateOf<List<Cue>>(emptyList()) }
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
                videoSize = p.videoSize
            }

            override fun onCues(cueGroup: CueGroup) {
                cues = cueGroup.cues
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
    var controls by remember { mutableStateOf(vp.showUiAtStart) }
    var locked by remember { mutableStateOf(false) }
    var poke by remember { mutableIntStateOf(0) } // any touch restarts the hide timer
    var resize by remember { mutableIntStateOf(0) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var seeking by remember { mutableStateOf<Float?>(null) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var subBottomLive by remember { mutableStateOf<Int?>(null) }
    var subScaleLive by remember { mutableStateOf<Float?>(null) }
    var fastForward by remember { mutableStateOf(false) }
    var speedBeforeFF by remember { mutableFloatStateOf(1f) }
    LaunchedEffect(controls, playing, poke, dialog, seeking, vp.autoHide, vp.autoHideTenths) {
        if (controls && playing && dialog == null && seeking == null && vp.autoHide) {
            delay(vp.autoHideTenths * 100L)
            controls = false
        }
    }
    // status / navigation bars follow the controls (Display Settings → Screen)
    LaunchedEffect(controls, locked, vp.fullScreen, vp.softButtons) { activity.applyBars(controls && !locked) }
    LaunchedEffect(player) {
        // a new video: the picture's zoom starts fresh
        zoom = 1f
        pan = Offset.Zero
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

    // ---- kids lock: touch effects
    val ripples = remember { mutableStateListOf<Pair<Offset, Long>>() }
    var frameTick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(ripples.size) {
        while (ripples.isNotEmpty()) {
            frameTick = System.currentTimeMillis()
            ripples.removeAll { frameTick - it.second > 700 }
            delay(16)
        }
    }

    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVolume = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }

    // ---- clock and battery (title bar / always on screen)
    var clock by remember { mutableStateOf("") }
    LaunchedEffect(vp.titleClock, vp.osdClock) {
        while (vp.titleClock || vp.osdClock) {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val battery = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            val time = java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(java.util.Date())
            clock = if (battery >= 0) "$time  •  $battery%" else time
            delay(20_000)
        }
    }

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
                if (vp.lockMode == 0) controls = true
                show(Icons.Rounded.Lock, if (vp.lockMode == 0) "Unlock first" else "Hold the lock 2 seconds to unlock")
            }
            else -> onBack()
        }
    }

    fun seekBy(ms: Long) {
        val target = (player.currentPosition + ms).coerceIn(0L, if (duration > 0) duration else Long.MAX_VALUE)
        player.seekTo(target)
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
                controls = false
                activity.screenshot { msg -> show(Icons.Rounded.PhotoCamera, msg) }
            }
            "background" -> {
                val on = !settings.videoBackground
                SettingsStore.update { it.copy(videoBackground = on) }
                show(Icons.Rounded.Headphones, if (on) "Background play on: leave the video, the sound goes on" else "Background play off")
            }
            "rotation" -> dialog = "rotation"
            "settings" -> dialog = "display"
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black).clipToBounds()) {
        val widthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val heightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val density = LocalDensity.current

        // ---- where the picture goes (aspect ratio, picture size, zoom)
        val rotated = videoSize.unappliedRotationDegrees == 90 || videoSize.unappliedRotationDegrees == 270
        val videoRatio = if (videoSize.width > 0 && videoSize.height > 0) {
            val r = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
            if (rotated) 1f / r else r
        } else null
        val ratio = aspectRatioOf(aspect, vp) ?: videoRatio
        val screenRatio = widthPx / heightPx
        val box: Pair<Float, Float> = when {
            ratio == null || resize == 2 -> widthPx to heightPx
            resize == 3 && videoSize.width > 0 -> {
                val w = (if (rotated) videoSize.height else videoSize.width).toFloat() * (if (aspectRatioOf(aspect, vp) != null) 1f else videoSize.pixelWidthHeightRatio)
                w to w / ratio
            }
            resize == 1 -> if (screenRatio > ratio) widthPx to widthPx / ratio else heightPx * ratio to heightPx
            else -> if (screenRatio > ratio) heightPx * ratio to heightPx else widthPx to widthPx / ratio
        }
        val boxW = with(density) { box.first.toDp() }
        val boxH = with(density) { box.second.toDp() }

        // ---- the picture
        Box(
            Modifier
                .align(Alignment.Center)
                .requiredSize(boxW, boxH)
                .graphicsLayer {
                    scaleX = zoom
                    scaleY = zoom
                    translationX = pan.x
                    translationY = pan.y
                }
        ) {
            AndroidView(
                factory = { ctx ->
                    (LayoutInflater.from(ctx).inflate(R.layout.video_player_view, null) as PlayerView).apply {
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        useController = false
                        resizeMode = 3 // FILL: the box above already has the right shape
                        subtitleView?.visibility = View.GONE // the subtitles are drawn by our own view, not zoomed
                        activity.playerView = this
                    }
                },
                update = { view ->
                    if (view.player !== player) view.player = player
                    view.keepScreenOn = vp.keepScreenOn || playing
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // ---- night mode: a dark veil over the picture
        if (night) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))

        // ---- subtitles (our own view: alignment, position, size, colors from Display Settings)
        val subW = if (vp.subFitVideo && ratio != null && resize != 2) minOf(box.first, widthPx) else widthPx
        val subH = if (vp.subFitVideo && ratio != null && resize != 2) minOf(box.second, heightPx) else heightPx
        AndroidView(
            factory = { ctx -> SubtitleView(ctx) },
            update = { sv ->
                val bottom = (subBottomLive ?: vp.subBottom).coerceIn(0, 50)
                val scale = (subScaleLive ?: vp.subScale.toFloat()) / 100f
                sv.setApplyEmbeddedStyles(vp.subSsaStyles)
                sv.setApplyEmbeddedFontSizes(vp.subSsaSizes)
                sv.setStyle(
                    CaptionStyleCompat(
                        vp.subColor,
                        if (vp.subBgOn) vp.subBgColor else android.graphics.Color.TRANSPARENT,
                        if (vp.subBoxOn) vp.subBoxColor else android.graphics.Color.TRANSPARENT,
                        when {
                            vp.subBorderOn -> CaptionStyleCompat.EDGE_TYPE_OUTLINE
                            vp.subShadow -> CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
                            else -> CaptionStyleCompat.EDGE_TYPE_NONE
                        },
                        vp.subBorderColor,
                        typefaceOf(vp.subFont, vp.subBold)
                    )
                )
                if (inPip) sv.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * scale)
                else sv.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, vp.subSize * scale)
                sv.setBottomPaddingFraction(if (inPip) 0.02f else 0.02f + bottom / 100f)
                sv.setCues(alignCues(cues, vp.subAlign))
            },
            modifier = Modifier
                .align(Alignment.Center)
                .size(with(density) { subW.toDp() }, with(density) { subH.toDp() })
        )

        if (inPip) return@BoxWithConstraints

        // ---- playing on a Chromecast
        castingTo?.let { device ->
            CastPanel(activity, device, title)
            return@BoxWithConstraints
        }

        // ---- always on screen: elapsed time / battery and clock
        if ((vp.osdElapsed || vp.osdClock) && !controls) {
            val bg = if (vp.osdBackground) Color.Black.copy(alpha = 0.5f) else Color.Transparent
            Column(
                Modifier
                    .align(Alignment.TopStart)
                    .safeDrawingPadding()
                    .padding(start = (8 + vp.osdOffset).dp, top = (8 + vp.osdOffset).dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(bg)
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                if (vp.osdElapsed) Text("${formatTime(position)} / ${formatTime(duration)}", color = Color(vp.osdTextColor), fontSize = 12.sp)
                if (vp.osdClock) Text(clock, color = Color(vp.osdTextColor), fontSize = 12.sp)
            }
        }

        // ---- touch layer
        val subZoneTop = heightPx * (0.62f - (subBottomLive ?: vp.subBottom) / 100f)
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(locked, player, vp) {
                    detectTapGestures(
                        onPress = {
                            tryAwaitRelease()
                            if (fastForward) {
                                fastForward = false
                                player.setPlaybackSpeed(speedBeforeFF)
                                hintTick++
                            }
                        },
                        onTap = { at ->
                            poke++
                            if (locked) {
                                when (vp.lockMode) {
                                    0 -> controls = !controls
                                    2 -> ripples.add(at to System.currentTimeMillis())
                                }
                                return@detectTapGestures
                            }
                            when (vp.touchAction) {
                                1 -> if (!controls) controls = true else activity.togglePlay()
                                2 -> { activity.togglePlay(); controls = true }
                                3 -> activity.togglePlay()
                                else -> controls = !controls
                            }
                        },
                        onDoubleTap = { at ->
                            if (locked) {
                                if (vp.lockMode == 2) ripples.add(at to System.currentTimeMillis())
                                return@detectTapGestures
                            }
                            val interval = vp.moveInterval * 1000L
                            when {
                                vp.gDoubleTapSeek && at.x < widthPx / 3 -> {
                                    seekBy(-interval)
                                    show(Icons.Rounded.FastRewind, "−${vp.moveInterval} s")
                                }
                                vp.gDoubleTapSeek && at.x > widthPx * 2 / 3 -> {
                                    seekBy(interval)
                                    show(Icons.Rounded.FastForward, "+${vp.moveInterval} s")
                                }
                                vp.gDoubleTapZoom -> {
                                    zoom = if (zoom > 1.05f) 1f else 2f
                                    pan = Offset.Zero
                                    show(Icons.Rounded.ZoomIn, "${(zoom * 100).roundToInt()}%")
                                }
                                vp.gDoubleTapPlay -> activity.togglePlay()
                            }
                        },
                        onLongPress = { at ->
                            if (locked) {
                                if (vp.lockMode == 2) ripples.add(at to System.currentTimeMillis())
                                return@detectTapGestures
                            }
                            if (vp.gLongPressSpeed && player.isPlaying) {
                                fastForward = true
                                speedBeforeFF = player.playbackParameters.speed
                                player.setPlaybackSpeed(vp.speedFF / 10f)
                                show(Icons.Rounded.FastForward, "${vp.speedFF / 10f}X", stay = true)
                            }
                        }
                    )
                }
                .pointerInput(locked, player, vp, duration, zoom > 1.01f) {
                    if (locked) return@pointerInput
                    val pxPerInch = this@pointerInput.density * 160f // the gesture area's density, not the screen's Density object
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val start = down.position
                        var mode = 0 // 1 seek, 2 brightness, 3 volume, 4 speed, 5 subtitles up/down, 6 move picture, 10 zoom, 11 subtitle size, -1 nothing
                        var total = Offset.Zero
                        var startValue = 0f
                        var target = 0f
                        while (true) {
                            val ev = awaitPointerEvent()
                            val fingers = ev.changes.count { it.pressed }
                            if (fingers == 0) break
                            if (fingers >= 2) {
                                if (mode !in 10..11) {
                                    val c = ev.calculateCentroid()
                                    mode = when {
                                        vp.gSubZoom && c.y > subZoneTop && cues.isNotEmpty() -> 11.also { subScaleLive = vp.subScale.toFloat() }
                                        vp.gZoom || vp.gZoomPan -> 10
                                        else -> -1
                                    }
                                }
                                val z = ev.calculateZoom()
                                if (mode == 10) {
                                    zoom = (zoom * z).coerceIn(0.5f, 4f)
                                    if (vp.gZoomPan || vp.gPan) pan += ev.calculatePan()
                                    show(Icons.Rounded.ZoomIn, "${(zoom * 100).roundToInt()}%", stay = true)
                                } else if (mode == 11) {
                                    subScaleLive = ((subScaleLive ?: 100f) * z).coerceIn(50f, 200f)
                                    show(Icons.Rounded.Subtitles, "${subScaleLive?.roundToInt()}%", stay = true)
                                }
                                ev.changes.forEach { it.consume() }
                                continue
                            }
                            if (mode >= 10 || mode == -1) continue
                            val ch = ev.changes.firstOrNull { it.pressed } ?: break
                            val d = ch.positionChange()
                            total += d
                            if (mode == 0) {
                                if (total.getDistance() < viewConfiguration.touchSlop) continue
                                val horizontal = abs(total.x) > abs(total.y)
                                mode = when {
                                    zoom > 1.01f && (vp.gPan || vp.gZoomPan) -> 6
                                    horizontal -> if (vp.gSeek) 1 else -1
                                    vp.gSubUpDown && start.y > subZoneTop && cues.isNotEmpty() -> 5
                                    start.x < widthPx * 0.4f -> if (vp.gBrightness) 2 else -1
                                    start.x > widthPx * 0.6f -> if (vp.gVolume) 3 else -1
                                    else -> if (vp.gSpeed) 4 else -1
                                }
                                startValue = when (mode) {
                                    1 -> player.currentPosition.toFloat()
                                    2 -> activity.brightness()
                                    3 -> audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
                                    4 -> player.playbackParameters.speed
                                    5 -> vp.subBottom.toFloat()
                                    else -> 0f
                                }
                                total = Offset.Zero
                                if (mode == -1) continue
                            }
                            ch.consume()
                            when (mode) {
                                1 -> {
                                    // Navigation → Seek Speed (seconds of video per inch)
                                    target = (startValue + total.x / pxPerInch * vp.seekSpeed * 1000f)
                                        .coerceIn(0f, (if (duration > 0) duration else Long.MAX_VALUE).toFloat())
                                    val delta = ((target - startValue) / 1000).roundToInt()
                                    show(null, "${if (delta >= 0) "+" else "−"}${formatTime(abs(delta) * 1000L)}\n${formatTime(target.toLong())}", stay = true)
                                }
                                2 -> {
                                    val v = (startValue - total.y / heightPx * 1.2f).coerceIn(0.01f, 1f)
                                    activity.setBrightness(v)
                                    target = v
                                    show(Icons.Rounded.BrightnessMedium, "${(v * 15).roundToInt()}", stay = true)
                                }
                                3 -> {
                                    val v = (startValue - total.y / heightPx * maxVolume * 1.2f).roundToInt().coerceIn(0, maxVolume)
                                    if (v != audio.getStreamVolume(AudioManager.STREAM_MUSIC)) runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0) }
                                    show(Icons.Rounded.VolumeUp, "${v * 100 / maxVolume}%", stay = true)
                                }
                                4 -> {
                                    val v = ((startValue - total.y / heightPx * 3f) * 20).roundToInt() / 20f
                                    val s = v.coerceIn(0.25f, 4f)
                                    if (s != player.playbackParameters.speed) player.setPlaybackSpeed(s)
                                    show(Icons.Rounded.Speed, "${trimSpeed(s)}×", stay = true)
                                }
                                5 -> {
                                    subBottomLive = (startValue - total.y / heightPx * 100f).roundToInt().coerceIn(0, 50)
                                    show(Icons.Rounded.Subtitles, "↕ ${subBottomLive}", stay = true)
                                }
                                6 -> pan += d
                            }
                        }
                        // the finger lifted
                        when (mode) {
                            1 -> player.seekTo(target.toLong().coerceAtLeast(0L))
                            2 -> if (vp.brightnessOn) VideoPrefsStore.update { it.copy(brightnessLevel = (target * 15).roundToInt().coerceIn(0, 15)) }
                            5 -> subBottomLive?.let { v -> VideoPrefsStore.update { it.copy(subBottom = v) } }
                            11 -> subScaleLive?.let { v -> VideoPrefsStore.update { it.copy(subScale = (v / 5).roundToInt() * 5) } }
                        }
                        if (mode == 5) subBottomLive = null
                        if (mode == 11) subScaleLive = null
                        if (mode == 10 && zoom in 0.95f..1.05f) { zoom = 1f; pan = Offset.Zero }
                        if (mode != 0) hintTick++
                    }
                }
        )

        // kids lock touch effects
        if (ripples.isNotEmpty()) {
            Canvas(Modifier.fillMaxSize()) {
                val now = frameTick
                ripples.forEach { (at, t) ->
                    val age = ((now - t) / 700f).coerceIn(0f, 1f)
                    drawCircle(primary.copy(alpha = 0.5f * (1f - age)), radius = 30.dp.toPx() + 70.dp.toPx() * age, center = at)
                }
            }
        }

        if (buffering) CircularProgressIndicator(Modifier.align(Alignment.Center).size(48.dp), color = primary)

        // ---- gesture feedback / position while dragging the bar
        val bigText = if (seeking != null && vp.showSeekPosition) null to formatTime(seeking!!.toLong()) else hint
        bigText?.let { (icon, text) ->
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
                Text(text, color = Color.White, fontSize = if (seeking != null) 26.sp else 17.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
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

        // ---- the time bar (also used read-only when the screen is locked)
        @Composable
        fun TimeBar(interactive: Boolean) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val shown = seeking?.toLong() ?: position
                Text(formatTime(shown), color = controlColor, fontSize = 12.sp)
                val fraction = if (duration > 0) ((seeking ?: position.toFloat()) / duration).coerceIn(0f, 1f) else 0f
                if (vp.progressStyle == 1) {
                    FlatBar(
                        fraction, primary, interactive && duration > 0,
                        Modifier.weight(1f).padding(horizontal = 10.dp).height(28.dp),
                        onChange = { f -> seeking = f * duration; poke++ },
                        onDone = { seeking?.let { player.seekTo(it.toLong()) }; seeking = null }
                    )
                } else {
                    Slider(
                        value = fraction,
                        onValueChange = { f -> seeking = f * duration; poke++ },
                        onValueChangeFinished = {
                            seeking?.let { player.seekTo(it.toLong()) }
                            seeking = null
                        },
                        enabled = interactive && duration > 0,
                        colors = SliderDefaults.colors(
                            thumbColor = primary, activeTrackColor = primary, inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                            disabledThumbColor = primary, disabledActiveTrackColor = primary
                        ),
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                    )
                }
                Text(
                    when {
                        duration <= 0 -> "--:--"
                        vp.remainingTime -> "−" + formatTime(duration - (seeking?.toLong() ?: position))
                        else -> formatTime(duration)
                    },
                    color = controlColor,
                    fontSize = 12.sp,
                    modifier = Modifier.clickable(enabled = interactive) { VideoPrefsStore.update { it.copy(remainingTime = !it.remainingTime) } }
                )
            }
        }

        // ---- locked
        if (locked) {
            if (vp.lockMode == 0) {
                AnimatedVisibility(controls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
                    Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, frame))).safeDrawingPadding().padding(12.dp)) {
                        if (vp.showUiWhenLocked) TimeBar(interactive = false)
                        RoundIcon(Icons.Rounded.Lock, "Unlock", vp) {
                            locked = false
                            poke++
                            show(Icons.Rounded.LockOpen, "Unlocked")
                        }
                    }
                }
            } else {
                // kids lock: a faint lock that opens only when held for 2 seconds
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .safeDrawingPadding()
                        .padding(16.dp)
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.25f))
                        .pointerInput(Unit) {
                            detectTapGestures(onPress = {
                                val released = withTimeoutOrNull(2000) { tryAwaitRelease() }
                                if (released == null) {
                                    locked = false
                                    controls = true
                                    show(Icons.Rounded.LockOpen, "Unlocked")
                                }
                            })
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.Lock, contentDescription = "Hold to unlock", tint = Color.White.copy(alpha = 0.35f))
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
                        .background(Brush.verticalGradient(listOf(frame, Color.Transparent)))
                        .safeDrawingPadding()
                ) {
                    // top: back, title, (clock), Cast, audio, subtitles, decoder, ⋮
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        BarIcon(Icons.Rounded.ArrowBack, "Back", vp, onBack)
                        Text(
                            title,
                            color = controlColor,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(horizontal = 6.dp)
                        )
                        if (vp.titleClock) Text(clock, color = controlColor.copy(alpha = 0.8f), fontSize = 12.sp, modifier = Modifier.padding(end = 6.dp))
                        if (activity.canCast()) BarIcon(Icons.Rounded.Cast, "Cast", vp) {
                            if (!activity.castButton()) dialog = "cast"
                        }
                        BarIcon(Icons.Rounded.MusicNote, "Audio", vp) { dialog = "audio" }
                        BarIcon(Icons.Rounded.Subtitles, "Subtitles", vp) { dialog = "subs" }
                        Box(
                            Modifier
                                .height(44.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { dialog = "decoder" }
                                .padding(horizontal = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(DECODERS[settings.videoDecoder.coerceIn(0, 2)], color = controlColor, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        }
                        BarIcon(Icons.Rounded.MoreVert, "More", vp) { dialog = "more" }
                    }
                    // shortcuts
                    if (vp.shortcuts) {
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
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            ids.forEach { id ->
                                val t = TOOLS.firstOrNull { it.id == id }
                                val (icon, label) = when (id) {
                                    "customise" -> Icons.Rounded.Edit to "Customise Items"
                                    "settings" -> Icons.Rounded.DisplaySettings to "Display Settings"
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
                                ToolItem(icon, label, active, primary, controlColor) { runTool(id) }
                            }
                        }
                    }
                }

                // bottom: what is on, the time bar, lock / previous / play / next / size / small window
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, frame)))
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
                        if (zoom != 1f) add("Zoom ${(zoom * 100).roundToInt()}%")
                        if (aspect != 0) add(ASPECTS[aspect])
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
                                        .clickable {
                                            if (c.startsWith("Zoom")) { zoom = 1f; pan = Offset.Zero }
                                        }
                                        .padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    if (!vp.progressBelow) TimeBar(interactive = true)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        BarIcon(Icons.Rounded.Lock, "Lock the screen", vp) {
                            locked = true
                            controls = false
                            show(Icons.Rounded.Lock, if (vp.lockMode == 0) "Locked" else "Kids lock: hold the lock 2 seconds to unlock")
                        }
                        if (vp.rotationButton) BarIcon(Icons.Rounded.ScreenRotation, "Rotate", vp) { poke++; activity.rotate() }
                        Spacer(Modifier.weight(1f))
                        if (vp.prevNextButtons) BarIcon(Icons.Rounded.SkipPrevious, "Previous", vp) {
                            poke++
                            if (hasPrev && player.currentPosition < 3000) player.seekToPreviousMediaItem() else player.seekTo(0L)
                        }
                        if (vp.moveButtons) BarIcon(Icons.Rounded.FastRewind, "Back ${vp.moveInterval} s", vp) { poke++; seekBy(-vp.moveInterval * 1000L) }
                        Spacer(Modifier.width(10.dp))
                        RoundIcon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play", vp, big = true) {
                            poke++
                            activity.togglePlay()
                        }
                        Spacer(Modifier.width(10.dp))
                        if (vp.moveButtons) BarIcon(Icons.Rounded.FastForward, "Forward ${vp.moveInterval} s", vp) { poke++; seekBy(vp.moveInterval * 1000L) }
                        if (vp.prevNextButtons) BarIcon(Icons.Rounded.SkipNext, "Next", vp) {
                            poke++
                            if (hasNext) player.seekToNextMediaItem() else show(Icons.Rounded.SkipNext, "Last video")
                        }
                        Spacer(Modifier.weight(1f))
                        BarIcon(Icons.Rounded.AspectRatio, "Picture size", vp) {
                            poke++
                            resize = (resize + 1) % RESIZE_MODES.size
                            zoom = 1f
                            pan = Offset.Zero
                            show(Icons.Rounded.AspectRatio, RESIZE_MODES[resize])
                        }
                        if (activity.canPip()) BarIcon(Icons.Rounded.PictureInPictureAlt, "Small window", vp) { activity.enterPip() }
                    }
                    if (vp.progressBelow) TimeBar(interactive = true)
                }
            }
        }
    }

    // ---- dialogs and panels
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
                TextButton(onClick = { dialog = "display" }) { Text("Subtitle look (Display Settings → Text)…") }
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
        "eq" -> EqualizerPanel(activity, onDismiss = { dialog = null })
        "customise" -> CustomiseDialog(onDismiss = { dialog = null })
        "more" -> MorePanel(activity, onOpen = { dialog = it }, onDismiss = { dialog = null })
        "display" -> DisplaySettingsPanel(onDismiss = { dialog = null })
        "playlist" -> PlaylistDialog(player, onDismiss = { dialog = null })
        "stream" -> NetworkStreamDialog(onPlay = { activity.playUrl(it) }, onDismiss = { dialog = null })
        "info" -> InfoDialog(remember(player, title) { activity.information() }, onDismiss = { dialog = null })
        "bookmarks" -> BookmarksDialog(activity, onDismiss = { dialog = null })
        "cut" -> CutDialog(activity, onDismiss = { dialog = null })
        "tutorial" -> TutorialDialog(onDismiss = { dialog = null })
        "cast" -> com.powersound.player.ui.CastDialog(onDismiss = {
            dialog = null
            // nothing chosen (or still connecting is fine): the music may use the Chromecast again
            val connecting = runCatching { !androidx.mediarouter.media.MediaRouter.getInstance(context).selectedRoute.isDefault }.getOrDefault(false)
            if (activity.castingTo.value == null && !connecting) com.powersound.player.cast.CastControl.videoOwnsCast = false
        })
    }
}

/** Display Settings → Layout → Alignment, for subtitles that don't place themselves. */
@OptIn(UnstableApi::class)
private fun alignCues(cues: List<Cue>, align: Int): List<Cue> {
    if (align == 1) return cues
    return cues.map { c ->
        if (c.bitmap != null || c.position != Cue.DIMEN_UNSET) c
        else c.buildUpon()
            .setTextAlignment(if (align == 0) Layout.Alignment.ALIGN_NORMAL else Layout.Alignment.ALIGN_OPPOSITE)
            .setPosition(if (align == 0) 0.05f else 0.95f)
            .setPositionAnchor(if (align == 0) Cue.ANCHOR_TYPE_START else Cue.ANCHOR_TYPE_END)
            .build()
    }
}

/** Style → Progress bar: Flat (a thin line you can drag). */
@Composable
private fun FlatBar(fraction: Float, color: Color, enabled: Boolean, modifier: Modifier, onChange: (Float) -> Unit, onDone: () -> Unit) {
    Box(
        modifier
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { o -> onChange((o.x / size.width).coerceIn(0f, 1f)); onDone() }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragEnd = onDone,
                    onDragCancel = onDone
                ) { change, _ ->
                    change.consume()
                    onChange((change.position.x / size.width).coerceIn(0f, 1f))
                }
            },
        contentAlignment = Alignment.CenterStart
    ) {
        Box(Modifier.fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.3f)))
        Box(Modifier.fillMaxWidth(fraction).height(3.dp).background(color))
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
private fun ToolItem(icon: ImageVector, label: String, active: Boolean, accent: Color, base: Color = Color.White, onClick: () -> Unit) {
    Column(
        Modifier
            .width(84.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = label, tint = if (active) accent else base, modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            color = if (active) accent else base,
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
private fun BarIcon(icon: ImageVector, description: String, prefs: VideoPrefs = VideoPrefsStore.value, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = description, tint = Color(prefs.controlColor), modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun RoundIcon(
    icon: ImageVector,
    description: String,
    prefs: VideoPrefs = VideoPrefsStore.value,
    modifier: Modifier = Modifier,
    big: Boolean = false,
    onClick: () -> Unit
) {
    Box(
        modifier
            .size(if (big) 72.dp else 52.dp)
            .clip(CircleShape)
            .background(Color(prefs.controlBg))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = description, tint = Color(prefs.controlColor), modifier = Modifier.size(if (big) 44.dp else 30.dp))
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

