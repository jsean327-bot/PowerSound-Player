package com.powersound.player.ui

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.FirstPage
import androidx.compose.material.icons.rounded.LastPage
import androidx.compose.material.icons.rounded.TextSnippet
import androidx.compose.material.icons.rounded.FormatListNumbered
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.LooksOne
import androidx.compose.material.icons.rounded.Loop
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.powersound.player.PlaybackPos
import com.powersound.player.PlayerUiState
import com.powersound.player.RepeatMode
import com.powersound.player.ShuffleMode
import com.powersound.player.data.Song
import com.powersound.player.data.Waveform
import com.powersound.player.data.formatTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/** Buttons that can be placed in the row under the cover (Settings → Player Screen → Edit Player Screen Buttons). */
data class PlayerButton(val id: String, val label: String, val icon: ImageVector)

val PLAYER_BUTTONS = listOf(
    PlayerButton("viz", "Visualization", Icons.Rounded.GraphicEq),
    PlayerButton("sleep", "Sleep timer", Icons.Rounded.Timer),
    PlayerButton("repeat", "Repeat", Icons.Rounded.Repeat),
    PlayerButton("shuffle", "Shuffle", Icons.Rounded.Shuffle),
    PlayerButton("queue", "Queue", Icons.Rounded.QueueMusic),
    PlayerButton("lyrics", "Lyrics", Icons.Rounded.TextSnippet),
    PlayerButton("eq", "Equalizer", Icons.Rounded.Equalizer),
    PlayerButton("info", "Audio Info", Icons.Rounded.Info),
    PlayerButton("bookmark", "Add bookmark", Icons.Rounded.BookmarkBorder),
    PlayerButton("playlist", "Add to playlist", Icons.Rounded.PlaylistAdd),
    PlayerButton("counter", "Track counter", Icons.Rounded.FormatListNumbered),
    PlayerButton("like", "Like", Icons.Outlined.ThumbUp)
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NowPlayingScreen(
    state: PlayerUiState,
    position: StateFlow<PlaybackPos>,
    song: Song?,
    rating: Int,
    stars: Int,
    playCount: Int,
    vizMode: VizMode,
    vizPreset: VizPreset,
    onVizMode: (VizMode) -> Unit,
    onVizPreset: (VizPreset) -> Unit,
    onClose: () -> Unit,
    onSourceList: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onSeekBy: (Long) -> Unit,
    onPrevCategory: () -> Boolean,
    onNextCategory: () -> Boolean,
    onShuffle: () -> Unit,
    onShuffleMenu: () -> Unit,
    onRepeat: () -> RepeatMode,
    onRepeatMenu: () -> Unit,
    onLike: () -> Unit,
    onDislike: () -> Unit,
    onStars: (Int) -> Unit,
    onQueue: () -> Unit,
    onMore: () -> Unit,
    onMenuAction: (Int) -> Unit,
    onLine2: (Int) -> Unit,
    onSleep: () -> Unit,
    onLyrics: () -> Unit,
    onLyricsApp: () -> Unit,
    onAudioInfo: () -> Unit,
    onEqualizer: () -> Unit,
    onBookmark: () -> Unit,
    onAddToPlaylist: () -> Unit
) {
    val np = state.nowPlaying
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val accent = MaterialTheme.colorScheme.primary
    val close by rememberUpdatedState(onClose)
    val toSource by rememberUpdatedState(onSourceList)
    val next by rememberUpdatedState(onNext)
    val prev by rememberUpdatedState(onPrevious)
    val nextCat by rememberUpdatedState(onNextCategory)
    val prevCat by rememberUpdatedState(onPrevCategory)
    val lyrics by rememberUpdatedState(onLyrics)
    val seek by rememberUpdatedState(onSeek)
    val more by rememberUpdatedState(onMore)
    val appSettings by com.powersound.player.data.SettingsStore.settings.collectAsState()
    val st = appSettings
    // Settings → Visualization → Visualization On Player Screen: shown only while music is playing
    val vizRequested = vizMode
    val vizMode = if (st.vizOnPlayer && state.isPlaying) vizRequested else VizMode.OFF

    // 0 = fully open, 1 = gone. Follows the finger, then finishes smoothly when released.
    val closeP = remember { Animatable(0f) }
    val artX = remember { Animatable(0f) }
    LaunchedEffect(Unit) { closeP.snapTo(0f) }

    // local lyrics (tag or .lrc) decide the lyrics button and the swipe up
    val hasLocalLyrics by produceState(false, song?.id) {
        value = song != null && com.powersound.player.data.LyricsReader.hasLocal(context, song.id, song.path)
    }
    val lyricsSwipeOn by rememberUpdatedState(st.lyricsSwipeUp == 2 || (st.lyricsSwipeUp == 1 && hasLocalLyrics))
    val showLyricsButton = st.lyricsButton == 2 || (st.lyricsButton == 1 && hasLocalLyrics)

    // ---- small toast at the bottom ("Play All Categories — Long press for more options") ----
    var toast by remember { mutableStateOf<Pair<String, String>?>(null) }
    var toastId by remember { mutableIntStateOf(0) }
    fun showToast(title: String, sub: String = "Long press for more options") {
        toast = title to sub
        toastId++
    }
    LaunchedEffect(toastId) {
        if (toast != null) {
            delay(2000)
            toast = null
        }
    }

    // ---- visualization: controls hidden in full screen until tapped ----
    var showControls by remember { mutableStateOf(false) }
    LaunchedEffect(showControls) {
        if (showControls) {
            delay(st.vizUiTimeoutMs.toLong()) // Settings → Visualization → UI Timeout
            showControls = false
        }
    }
    var choosePreset by remember { mutableStateOf(false) }
    var editButtons by remember { mutableStateOf(false) }
    // Settings → Visualization: change preset every N seconds
    LaunchedEffect(vizMode, appSettings.vizPresetSeconds, vizPreset) {
        val sec = appSettings.vizPresetSeconds
        if (vizMode != VizMode.OFF && sec > 0) {
            delay(sec * 1000L)
            onVizPreset(vizPreset.nextEnabled(1))
        }
    }
    // Settings → Visualization → Hide System Bars For Full Screen
    val hostView = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(vizMode == VizMode.FULL && st.vizHideBars) {
        val hide = vizMode == VizMode.FULL && st.vizHideBars
        val window = (hostView.context as? android.app.Activity)?.window
        val ctl = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, hostView) }
        if (hide && ctl != null) {
            ctl.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ctl.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            if (hide && ctl != null) {
                ctl.show(androidx.core.view.WindowInsetsCompat.Type.navigationBars())
                if (!st.hideStatusBar) ctl.show(androidx.core.view.WindowInsetsCompat.Type.statusBars())
            }
        }
    }
    val contentAlpha by animateFloatAsState(
        when (vizMode) {
            VizMode.OFF -> 1f
            VizMode.FADE -> if (showControls) 1f else appSettings.vizFadedOpacity
            VizMode.FULL -> if (showControls) 1f else 0f
        },
        tween(animMs(300)),
        label = "controls"
    )
    // Settings → Visualization → Track Opacity (labels, rating, menu, album art)
    val trackAlpha by animateFloatAsState(
        when (vizMode) {
            VizMode.OFF -> 1f
            VizMode.FADE -> if (showControls) 1f else appSettings.vizTrackOpacity
            VizMode.FULL -> if (showControls) 1f else 0f
        },
        tween(animMs(300)),
        label = "track"
    )
    // Settings → Visualization → Visible Album Art
    val artVisible = vizMode == VizMode.OFF || st.vizAlbumArt
    var coverRect by remember { mutableStateOf(Rect.Zero) }

    // ---- long-press scrubbing on the waveform / button area ----
    var scrubbing by remember { mutableStateOf(false) }
    var scrubFrac by remember { mutableFloatStateOf(0f) }
    val buttonsAlpha by animateFloatAsState(if (scrubbing) 0.08f else 1f, tween(animMs(180)), label = "buttons")

    var metaMode by remember { mutableIntStateOf(0) }

    // System back gesture: the screen shrinks and fades with the finger.
    PredictiveBackHandler { progress ->
        try {
            progress.collect { closeP.snapTo(it.progress) }
            closeP.animateTo(1f, tween(animMs(170)))
            close()
        } catch (e: CancellationException) {
            scope.launch { closeP.animateTo(0f, tween(animMs(200))) }
            throw e
        }
    }

    fun closeToSource() {
        scope.launch {
            closeP.animateTo(1f, tween(animMs(200)))
            toSource()
        }
    }

    val peaks by produceState<FloatArray?>(np?.songId?.let { Waveform.cached(it) }, np?.songId, st.seekbarStyle) {
        // Settings → Skin → Seekbar Style: "Static Seekbar" doesn't read the song's waveform
        value = if (st.seekbarStyle == 0) np?.songId?.let { Waveform.load(context, it) } else null
    }

    val layout = st.layout
    val centered = st.labelsCentered && layout != 0
    val title = np?.title ?: ""
    val line2 = listOfNotNull(np?.artist, np?.album?.takeIf { it.isNotBlank() }).joinToString(" - ")

    // ---- pieces shared by the layouts ----
    @Composable
    fun Line2(onCover: Boolean) {
        val mod = if (st.line2Press != 0 || st.line2LongPress != 0) {
            Modifier.combinedClickable(
                onClick = { if (st.line2Press != 0) onLine2(st.line2Press) },
                onLongClick = { if (st.line2LongPress != 0) onLine2(st.line2LongPress) }
            )
        } else Modifier
        if (onCover) {
            OverlayChip(line2, modifier = mod, background = st.labelsBg, marquee = st.marquee)
        } else {
            Text(
                line2,
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = if (st.marquee) TextOverflow.Clip else TextOverflow.Ellipsis,
                textAlign = if (centered) TextAlign.Center else TextAlign.Start,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .then(mod)
                    .then(if (st.marquee) Modifier.basicMarquee(iterations = Int.MAX_VALUE) else Modifier)
            )
        }
    }

    @Composable
    fun RatingButtons(small: Boolean) {
        when (st.ratingType) {
            1 -> {
                RoundIcon(if (rating == 1) Icons.Rounded.ThumbUp else Icons.Outlined.ThumbUp, rating == 1, onLike, small, st.buttonsBg)
                RoundIcon(if (rating == -1) Icons.Rounded.ThumbDown else Icons.Outlined.ThumbDown, rating == -1, onDislike, small, st.buttonsBg)
            }
            2 -> StarsRow(stars, onStars, small, st.buttonsBg)
            else -> Unit
        }
    }

    @Composable
    fun CoverButtons(small: Boolean) {
        if (showLyricsButton) RoundIcon(Icons.Rounded.TextSnippet, false, onLyrics, small, st.buttonsBg, onLongClick = onLyricsApp)
        RoundIcon(Icons.Rounded.QueueMusic, false, onQueue, small, st.buttonsBg)
        if (st.menuButton) {
            RoundIcon(
                Icons.Rounded.MoreVert, false, onMore, small, st.buttonsBg,
                onLongClick = if (st.menuLongPress != 0) ({ onMenuAction(st.menuLongPress) }) else null
            )
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                val p = closeP.value
                translationY = size.height * 0.22f * p
                scaleX = 1f - 0.22f * p
                scaleY = 1f - 0.22f * p
                alpha = (1f - p).coerceIn(0f, 1f)
                shape = RoundedCornerShape(36.dp.toPx() * p)
                clip = p > 0f
            }
            .background(MaterialTheme.colorScheme.background)
            .pointerInput(Unit) {
                // any touch shows the faded controls again (the touch itself still works)
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    showControls = true
                }
            }
            .pointerInput(Unit) {
                var up = 0f
                detectVerticalDragGestures(
                    onDragStart = { up = 0f },
                    onVerticalDrag = { change, dy ->
                        change.consume()
                        if (closeP.value == 0f && dy < 0f) {
                            up += dy // swipe up → lyrics
                        } else {
                            val nextP = (closeP.value + dy / (size.height * 0.5f)).coerceIn(0f, 1f)
                            scope.launch { closeP.snapTo(nextP) }
                        }
                    },
                    onDragEnd = {
                        scope.launch {
                            when {
                                closeP.value > 0.25f -> {
                                    // swipe down → the list this song is playing from
                                    closeP.animateTo(1f, tween(animMs(180)))
                                    toSource()
                                }
                                closeP.value > 0f -> closeP.animateTo(0f, tween(animMs(220)))
                                up < -120f && lyricsSwipeOn -> lyrics()
                                else -> Unit
                            }
                        }
                    },
                    onDragCancel = { scope.launch { closeP.animateTo(0f, tween(animMs(220))) } }
                )
            }
    ) {
        AppBackground(np?.songId)

        // Settings → Skin → Transparent Status Bar: "Default" keeps a soft dark band behind the status bar
        if (!st.statusBarNoBg) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .windowInsetsTopHeight(WindowInsets.statusBars)
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.45f), Color.Black.copy(alpha = 0.15f))))
            )
        }

        if (vizMode != VizMode.OFF) {
            val density = androidx.compose.ui.platform.LocalDensity.current
            val scaled = vizMode == VizMode.FADE && st.vizScaledBars && vizPreset.isBars && coverRect != Rect.Zero
            VisualizerView(
                preset = vizPreset,
                accent = accent,
                modifier = if (scaled) {
                    // Settings → Visualization → Scaled Bars For Faded Controls: bars inside the album art area
                    with(density) {
                        Modifier
                            .offset { androidx.compose.ui.unit.IntOffset(coverRect.left.toInt(), coverRect.top.toInt()) }
                            .size(coverRect.width.toDp(), coverRect.height.toDp())
                            .alpha(0.85f)
                    }
                } else Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(top = 64.dp, bottom = if (vizMode == VizMode.FULL) 40.dp else 220.dp)
                    .alpha(if (vizMode == VizMode.FADE) 0.85f else 1f)
            )
        }

        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 14.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                Modifier
                    .alpha(contentAlpha)
                    .padding(vertical = 6.dp)
                    .width(40.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.3f))
            )

            // ---- cover: tap = track list, long press = menu, swipe = change track, long swipe = change category ----
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .onGloballyPositioned { coverRect = it.boundsInRoot() }
                    .graphicsLayer {
                        translationX = artX.value
                        alpha = (1f - (kotlin.math.abs(artX.value) / size.width).coerceIn(0f, 1f) * 0.6f) * trackAlpha
                    }
                    .clip(RoundedCornerShape(cornerDp(24).dp))
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { closeToSource() },
                            onLongPress = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                more()
                            }
                        )
                    }
                    .pointerInput(Unit) {
                        val w = size.width.toFloat()
                        detectHorizontalDragGestures(
                            onHorizontalDrag = { change, dx ->
                                change.consume()
                                scope.launch { artX.snapTo(artX.value + dx) }
                            },
                            onDragEnd = {
                                scope.launch {
                                    val x = artX.value
                                    val dir = if (x < 0) 1 else -1 // left = next
                                    val category = kotlin.math.abs(x) > w * 0.62f
                                    if (kotlin.math.abs(x) > w * 0.25f) {
                                        artX.animateTo(if (x < 0) -w else w, tween(animMs(140)))
                                        if (category) {
                                            val ok = if (dir > 0) nextCat() else prevCat()
                                            showToast(
                                                if (ok) (if (dir > 0) "Next category" else "Previous category") else "No other category",
                                                "Swipe further to change category"
                                            )
                                        } else {
                                            if (dir > 0) next() else prev()
                                        }
                                        artX.snapTo(if (x < 0) w else -w)
                                        artX.animateTo(0f, tween(animMs(220)))
                                    } else {
                                        artX.animateTo(0f)
                                    }
                                }
                            },
                            onDragCancel = { scope.launch { artX.animateTo(0f) } }
                        )
                    }
            ) {
                val artSize = if (appSettings.artHighQuality) 1024 else 512
                if (st.artAnimation && st.animations != 0) {
                    // Settings → Player Screen → Album Art Animation
                    Crossfade(np?.songId, animationSpec = tween(animMs(380)), label = "art") { id ->
                        Artwork(id, Modifier.fillMaxSize().alpha(if (artVisible) 1f else 0f), sizePx = artSize, cornerDp = 0, placeholder = { BarsPlaceholder() })
                    }
                } else {
                    Artwork(np?.songId, Modifier.fillMaxSize().alpha(if (artVisible) 1f else 0f), sizePx = artSize, cornerDp = 0, placeholder = { BarsPlaceholder() })
                }
                if (layout == 0) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.55f)))
                    )
                    Column(
                        Modifier
                            .align(Alignment.BottomStart)
                            .padding(12.dp)
                            .fillMaxWidth(0.72f)
                    ) {
                        OverlayChip(title, big = true, background = st.labelsBg, marquee = st.marquee)
                        Spacer(Modifier.height(4.dp))
                        Line2(onCover = true)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { RatingButtons(small = false) }
                    }
                    Row(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) { CoverButtons(small = false) }
                } else if (layout == 1) {
                    // Alternative layout: smaller, faded buttons on the cover
                    Row(
                        Modifier
                            .align(Alignment.BottomStart)
                            .padding(10.dp)
                            .alpha(0.75f),
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) { RatingButtons(small = true) }
                    Row(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(10.dp)
                            .alpha(0.75f),
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) { CoverButtons(small = true) }
                }
            }

            // Alternative layout / Full cover: labels (and buttons) below the cover
            if (layout != 0) Column(Modifier.fillMaxWidth().alpha(trackAlpha), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(10.dp))
                Text(
                    title,
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = if (st.marquee) TextOverflow.Clip else TextOverflow.Ellipsis,
                    textAlign = if (centered) TextAlign.Center else TextAlign.Start,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (st.labelsBg) Modifier.clip(RoundedCornerShape(10.dp)).background(ChipBg).padding(horizontal = 10.dp, vertical = 2.dp) else Modifier)
                        .then(if (st.marquee) Modifier.basicMarquee(iterations = Int.MAX_VALUE) else Modifier)
                )
                Spacer(Modifier.height(2.dp))
                Box(if (st.labelsBg) Modifier.clip(RoundedCornerShape(10.dp)).background(ChipBg).padding(horizontal = 10.dp, vertical = 2.dp) else Modifier) {
                    Line2(onCover = false)
                }
                if (layout == 2) {
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        RatingButtons(small = false)
                        Spacer(Modifier.weight(1f))
                        CoverButtons(small = false)
                    }
                }
            }

          Column(Modifier.fillMaxWidth().weight(1f).alpha(contentAlpha), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(if (layout == 0) 12.dp else 8.dp))

            // ---- row of small buttons (editable); long press the empty space to edit ----
            val ids = st.playerButtons.filter { id -> PLAYER_BUTTONS.any { it.id == id } }
            @Composable
            fun ButtonFor(id: String) {
                when (id) {
                    "viz" -> if (st.vizOnPlayer) SmallPill(
                        Icons.Rounded.GraphicEq, vizRequested != VizMode.OFF, st.buttonsBg,
                        onClick = {
                            val m = vizRequested.next()
                            onVizMode(m)
                            showToast(m.label)
                        },
                        onLongClick = { choosePreset = true }
                    )
                    "sleep" -> SmallPill(Icons.Rounded.Timer, state.sleepEndsAt != null, st.buttonsBg, onClick = onSleep)
                    "repeat" -> SmallPill(
                        repeatIcon(state.repeat), state.repeat != RepeatMode.LIST_STOP, st.buttonsBg,
                        onClick = { showToast(onRepeat().label) },
                        onLongClick = onRepeatMenu
                    )
                    "shuffle" -> SmallPill(
                        Icons.Rounded.Shuffle, state.shuffleMode != ShuffleMode.OFF, st.buttonsBg,
                        onClick = {
                            onShuffle()
                            showToast(if (state.shuffleMode == ShuffleMode.OFF) "Shuffle songs" else "Shuffle off")
                        },
                        onLongClick = onShuffleMenu
                    )
                    "queue" -> SmallPill(Icons.Rounded.QueueMusic, false, st.buttonsBg, onClick = onQueue)
                    "lyrics" -> SmallPill(Icons.Rounded.TextSnippet, hasLocalLyrics, st.buttonsBg, onClick = onLyrics, onLongClick = onLyricsApp)
                    "eq" -> SmallPill(Icons.Rounded.Equalizer, false, st.buttonsBg, onClick = onEqualizer)
                    "info" -> SmallPill(Icons.Rounded.Info, false, st.buttonsBg, onClick = onAudioInfo)
                    "bookmark" -> SmallPill(Icons.Rounded.BookmarkBorder, false, st.buttonsBg, onClick = { onBookmark(); showToast("Bookmark added", "Library → Bookmarks") })
                    "playlist" -> SmallPill(Icons.Rounded.PlaylistAdd, false, st.buttonsBg, onClick = onAddToPlaylist)
                    "like" -> SmallPill(if (rating == 1) Icons.Rounded.ThumbUp else Icons.Outlined.ThumbUp, rating == 1, st.buttonsBg, onClick = onLike, onLongClick = onDislike)
                    "counter" -> {
                        val total = state.queue.size
                        Text(
                            if (total > 0) "${state.currentIndex + 1}/$total" else "–",
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .height(36.dp)
                                .clip(RoundedCornerShape(50))
                                .background(if (st.buttonsBg) Color.White.copy(alpha = 0.08f) else Color.Transparent)
                                .clickable(onClick = onQueue)
                                .padding(horizontal = 14.dp, vertical = 8.dp)
                        )
                    }
                }
            }
            val editOnLongPress = Modifier.pointerInput(st.noEditLongPress) {
                detectTapGestures(onLongPress = {
                    if (!st.noEditLongPress) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        editButtons = true
                    }
                })
            }
            if (!st.preferNoGap && ids.size in 2..5) {
                // half on the left, half on the right; the empty middle can be long-pressed to edit
                val half = (ids.size + 1) / 2
                Row(Modifier.fillMaxWidth().heightIn(min = 36.dp).then(editOnLongPress), verticalAlignment = Alignment.CenterVertically) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { ids.take(half).forEach { ButtonFor(it) } }
                    Spacer(Modifier.weight(1f))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { ids.drop(half).forEach { ButtonFor(it) } }
                }
            } else {
                // centered ("Prefer No Gap") or many buttons: a row that scrolls sideways when it doesn't fit
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 36.dp)
                        .then(editOnLongPress)
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
                ) {
                    ids.forEach { ButtonFor(it) }
                    if (ids.isEmpty()) Text("Long press here to add buttons", color = Color.White.copy(alpha = 0.4f), fontSize = 12.sp)
                }
            }

            Spacer(Modifier.weight(1f))

            // ---- transport over the waveform; long press anywhere here to scrub ----
            val pos by position.collectAsState()
            val duration = pos.durationMs.coerceAtLeast(1L)
            val fraction = (pos.positionMs.toFloat() / duration).coerceIn(0f, 1f)
            val durationNow by rememberUpdatedState(duration)
            var rowBounds by remember { mutableStateOf(Rect.Zero) }
            val edgeButton = with(androidx.compose.ui.platform.LocalDensity.current) { 54.dp.toPx() }
            val simple = st.seekbarStyle == 2
            val showPro = st.proButtons || simple
            val longTrack = pos.durationMs >= st.longTrackMin * 60_000L
            val proSeeks = st.proRewindLong && longTrack
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(130.dp)
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            val r = rowBounds
                            val onEdge = down.position.y in r.top..r.bottom &&
                                ((down.position.x in r.left..(r.left + edgeButton)) ||
                                    (down.position.x in (r.right - edgeButton)..r.right))
                            if (onEdge) return@awaitEachGesture // ⏮ / ⏭ keep their own long press
                            var released = false
                            val waited = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                while (true) {
                                    val e = awaitPointerEvent(PointerEventPass.Initial)
                                    val c = e.changes.firstOrNull { it.id == down.id }
                                    if (c == null || !c.pressed) {
                                        released = true
                                        break
                                    }
                                    if ((c.position - down.position).getDistance() > viewConfiguration.touchSlop) break
                                }
                                true
                            }
                            if (waited != null || released) return@awaitEachGesture
                            // long press: scrub
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            scrubbing = true
                            scrubFrac = (down.position.x / size.width).coerceIn(0f, 1f)
                            while (true) {
                                val e = awaitPointerEvent(PointerEventPass.Initial)
                                val c = e.changes.firstOrNull { it.id == down.id } ?: break
                                c.consume()
                                if (!c.pressed) break
                                scrubFrac = (c.position.x / size.width).coerceIn(0f, 1f)
                            }
                            seek((scrubFrac * durationNow).toLong())
                            scrubbing = false
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                if (simple) {
                    // Simple Seekbar: a thin line above the buttons
                    SimpleSeekBar(
                        fraction = if (scrubbing) scrubFrac else fraction,
                        onSeek = { f -> onSeek((f * duration).toLong()) },
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .height(28.dp)
                    )
                } else {
                    WaveSeekBar(
                        peaks = peaks,
                        fraction = if (scrubbing) scrubFrac else fraction,
                        cursor = if (scrubbing) scrubFrac else null,
                        onDrag = {},
                        onSeek = { f -> onSeek((f * duration).toLong()) },
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Row(
                    Modifier
                        .then(if (simple) Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp) else Modifier)
                        .alpha(buttonsAlpha)
                        .onGloballyPositioned { rowBounds = it.boundsInParent() },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(if (showPro) 6.dp else 18.dp)
                ) {
                    TransportButton(Icons.Rounded.SkipPrevious, 50, st.buttonsBg, onPrevious, onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        showToast(if (prevCat()) "Previous category" else "No other category", "Long press ⏮ / ⏭ to change category")
                    })
                    if (showPro) {
                        // Pro Buttons: category change, or -10 s in long tracks
                        TransportButton(if (proSeeks) Icons.Rounded.FastRewind else Icons.Rounded.FirstPage, 58, st.buttonsBg, {
                            if (proSeeks) onSeekBy(-10_000) else showToast(if (prevCat()) "Previous category" else "No other category", "Pro Buttons")
                        })
                    }
                    Box(
                        Modifier
                            .size(84.dp)
                            .clip(CircleShape)
                            .background(if (st.buttonsBg) Color.Black else Color.Transparent)
                            .border(2.dp, Color.White.copy(alpha = if (st.buttonsBg) 0.15f else 0f), CircleShape)
                            .clickable(onClick = onTogglePlay),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = "Play/Pause",
                            tint = Color.White,
                            modifier = Modifier.size(if (st.buttonsBg) 50.dp else 64.dp)
                        )
                    }
                    if (showPro) {
                        TransportButton(if (proSeeks) Icons.Rounded.FastForward else Icons.Rounded.LastPage, 58, st.buttonsBg, {
                            if (proSeeks) onSeekBy(10_000) else showToast(if (nextCat()) "Next category" else "No other category", "Pro Buttons")
                        })
                    }
                    TransportButton(Icons.Rounded.SkipNext, 50, st.buttonsBg, onNext, onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        showToast(if (nextCat()) "Next category" else "No other category", "Long press ⏮ / ⏭ to change category")
                    })
                }
            }

            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                // long press the elapsed time → back to 0:00
                TimeChip(
                    formatTime(if (scrubbing) (scrubFrac * duration).toLong() else pos.positionMs), big = scrubbing,
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onSeek(0L)
                        showToast("Back to 0:00", "Long press the elapsed time")
                    }
                )
                Spacer(Modifier.weight(1f))
                TimeChip(formatTime(pos.durationMs))
            }

            // tap = cycle what this line shows, long press = Audio Info
            val meta = when (metaMode % 4) {
                0 -> state.audioInfo.uppercase()
                1 -> listOfNotNull(song?.album, song?.year?.takeIf { it > 0 }?.toString()).joinToString(" • ").uppercase()
                2 -> listOfNotNull(song?.trackNumber?.takeIf { it > 0 }?.let { "TRACK $it" }, song?.folder).joinToString(" • ").uppercase()
                else -> "PLAYED $playCount ${if (playCount == 1) "TIME" else "TIMES"}"
            }
            Text(
                meta.ifBlank { " " },
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(top = 6.dp, bottom = 4.dp)
                    .clip(RoundedCornerShape(50))
                    .combinedClickable(onClick = { metaMode++ }, onLongClick = onAudioInfo)
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            )
          }
        }

        // full-screen visualization: tap shows the controls for a moment
        if (vizMode == VizMode.FULL && !showControls) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { showControls = true }
            )
        } else if (vizMode == VizMode.FADE && !showControls && st.vizIgnoreTouch) {
            // Settings → Visualization → Ignore Touch: the first touch only shows the controls
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { showControls = true }
            )
        }

        // visualization bar: preset name, previous / next preset, close
        if (vizMode != VizMode.OFF) {
            Row(
                Modifier
                    .statusBarsPadding()
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black.copy(alpha = st.vizPanelOpacity)) // Top Visualization Panel Opacity
                    .padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.GraphicEq, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f).clickable { choosePreset = true }) {
                    Text(vizPreset.label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text("Built-in • tap for list", color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp)
                }
                IconButton(onClick = { onVizPreset(vizPreset.nextEnabled(-1)) }) {
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, contentDescription = "Previous preset", tint = Color.White)
                }
                IconButton(onClick = { onVizPreset(vizPreset.nextEnabled(1)) }) {
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = "Next preset", tint = Color.White)
                }
                IconButton(onClick = { onVizMode(VizMode.OFF) }) {
                    Icon(Icons.Rounded.Close, contentDescription = "Close", tint = Color.White)
                }
            }
        }

        // toast
        AnimatedVisibility(
            visible = toast != null,
            enter = fadeIn(tween(animMs(220))) + slideInVertically(tween(animMs(220))) { it / 2 },
            exit = fadeOut(tween(animMs(220))) + slideOutVertically(tween(animMs(220))) { it / 2 },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 18.dp)
        ) {
            val t = toast
            Column(
                Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xFF26262E).copy(alpha = 0.96f))
                    .padding(horizontal = 18.dp, vertical = 8.dp)
            ) {
                Text(t?.first ?: "", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                if (!t?.second.isNullOrBlank()) Text(t?.second ?: "", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
            }
        }

        // first-time tip
        if (HintStore.shouldShow("player")) {
            HintBubble(
                title = "Player Screen",
                text = "Swipe the cover ◀ ▶ to change tracks, swipe further to change category. " +
                    "Swipe down or tap the cover for the track's list. Swipe up for lyrics. Long press the cover for the track menu. " +
                    "Long press the progress area to seek. Long press ⏮ ⏭ for previous / next category. " +
                    "Long press the empty space between the small buttons to edit them.",
                onClose = { HintStore.dismiss("player") },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 70.dp)
            )
        }
    }

    if (choosePreset) {
        ChoiceDialog(
            title = "Visualization",
            options = VizPreset.values().map { it.label } + VizMode.values().map { it.label },
            selected = vizPreset.ordinal,
            onPick = { i ->
                val presets = VizPreset.values()
                if (i < presets.size) {
                    onVizPreset(presets[i])
                    if (vizMode == VizMode.OFF) onVizMode(VizMode.FADE)
                } else {
                    onVizMode(VizMode.values()[i - presets.size])
                }
            },
            onDismiss = { choosePreset = false }
        )
    }
    if (editButtons) {
        EditPlayerButtonsDialog(
            current = st.playerButtons,
            onSave = { list -> com.powersound.player.data.SettingsStore.update { it.copy(playerButtons = list) } },
            onDismiss = { editButtons = false }
        )
    }
}

/** 5 stars on the cover: tap to rate (tap the same star again to clear). */
@Composable
private fun StarsRow(stars: Int, onStars: (Int) -> Unit, small: Boolean, background: Boolean) {
    Row(
        Modifier
            .height(if (small) 32.dp else 40.dp)
            .clip(RoundedCornerShape(50))
            .background(if (background) ChipBg else Color.Transparent)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (i in 1..5) {
            Icon(
                if (i <= stars) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                contentDescription = "$i stars",
                tint = if (i <= stars) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.8f),
                modifier = Modifier
                    .size(if (small) 22.dp else 26.dp)
                    .clip(CircleShape)
                    .clickable { onStars(i) }
                    .padding(2.dp)
            )
        }
    }
}

/** Plain progress line with a round thumb (Settings → Skin → Simple Seekbar). */
@Composable
fun SimpleSeekBar(fraction: Float, onSeek: (Float) -> Unit, modifier: Modifier = Modifier) {
    val seekTo by rememberUpdatedState(onSeek)
    var dragging by remember { mutableStateOf<Float?>(null) }
    val accent = MaterialTheme.colorScheme.primary
    Canvas(
        modifier
            .pointerInput(Unit) { detectTapGestures(onTap = { o -> seekTo((o.x / size.width).coerceIn(0f, 1f)) }) }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> dragging = (o.x / size.width).coerceIn(0f, 1f) },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        dragging = (change.position.x / size.width).coerceIn(0f, 1f)
                    },
                    onDragEnd = { dragging?.let { seekTo(it) }; dragging = null },
                    onDragCancel = { dragging = null }
                )
            }
    ) {
        val f = dragging ?: fraction
        val y = size.height / 2f
        val h = 4.dp.toPx()
        drawRoundRect(Color.White.copy(alpha = 0.22f), topLeft = Offset(0f, y - h / 2), size = Size(size.width, h), cornerRadius = CornerRadius(h / 2))
        drawRoundRect(accent, topLeft = Offset(0f, y - h / 2), size = Size(size.width * f, h), cornerRadius = CornerRadius(h / 2))
        drawCircle(Color.White, radius = 7.dp.toPx(), center = Offset(size.width * f, y))
    }
}

private fun repeatIcon(m: RepeatMode): ImageVector = when (m) {
    RepeatMode.ADVANCE -> Icons.Rounded.PlaylistPlay
    RepeatMode.SONG_REPEAT -> Icons.Rounded.RepeatOne
    RepeatMode.LIST_REPEAT -> Icons.Rounded.Repeat
    RepeatMode.ADVANCE_REPEAT -> Icons.Rounded.Loop
    RepeatMode.SONG_STOP -> Icons.Rounded.LooksOne
    RepeatMode.LIST_STOP -> Icons.Rounded.Repeat
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RoundIcon(
    icon: ImageVector,
    active: Boolean,
    onClick: () -> Unit,
    small: Boolean = false,
    background: Boolean = true,
    onLongClick: (() -> Unit)? = null
) {
    Box(
        Modifier
            .size(if (small) 32.dp else 40.dp)
            .clip(CircleShape)
            .background(if (background) ChipBg else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = if (active) MaterialTheme.colorScheme.primary else Color.White, modifier = Modifier.size(if (small) 17.dp else 21.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SmallPill(icon: ImageVector, active: Boolean, background: Boolean = true, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    Box(
        Modifier
            .width(56.dp)
            .height(36.dp)
            .clip(RoundedCornerShape(50))
            .background(
                when {
                    active -> MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                    background -> Color.White.copy(alpha = 0.08f)
                    else -> Color.Transparent
                }
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = if (active) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = if (background) 0.6f else 0.85f), modifier = Modifier.size(22.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TransportButton(icon: ImageVector, sizeDp: Int, background: Boolean, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    Box(
        Modifier
            .size(sizeDp.dp)
            .clip(CircleShape)
            .background(if (background) Color.Black.copy(alpha = 0.85f) else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size((sizeDp * if (background) 0.55f else 0.7f).dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TimeChip(text: String, big: Boolean = false, onLongClick: (() -> Unit)? = null) {
    Text(
        text,
        color = Color.White,
        fontSize = if (big) 16.sp else 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(ChipBg)
            .then(if (onLongClick != null) Modifier.combinedClickable(onClick = {}, onLongClick = onLongClick) else Modifier)
            .padding(horizontal = 8.dp, vertical = 2.dp)
    )
}

/** Seek bar drawn as the song's real waveform. Tap or drag to seek; [cursor] draws the scrub line. */
@Composable
fun WaveSeekBar(
    peaks: FloatArray?,
    fraction: Float,
    cursor: Float?,
    onDrag: (Float) -> Unit,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val drag by rememberUpdatedState(onDrag)
    val seekTo by rememberUpdatedState(onSeek)
    var last by remember { mutableFloatStateOf(0f) }
    val played = Color.White.copy(alpha = 0.95f)
    val rest = Color.White.copy(alpha = 0.22f)
    Canvas(
        modifier
            .pointerInput(Unit) {
                detectTapGestures(onTap = { o -> seekTo((o.x / size.width).coerceIn(0f, 1f)) })
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> last = (o.x / size.width).coerceIn(0f, 1f); drag(last) },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        last = (change.position.x / size.width).coerceIn(0f, 1f)
                        drag(last)
                    },
                    onDragEnd = { seekTo(last) },
                    onDragCancel = { seekTo(last) }
                )
            }
    ) {
        val bars = peaks ?: FloatArray(70) { 0.12f }
        val n = bars.size
        val gap = 2.dp.toPx()
        val bw = ((size.width - gap * (n - 1)) / n).coerceAtLeast(1f)
        for (i in 0 until n) {
            val h = (0.10f + 0.90f * bars[i]) * size.height
            val x = i * (bw + gap)
            val before = (i + 0.5f) / n <= fraction
            // while scrubbing (like the video) the part after the cursor is bright
            val color = if (cursor != null) (if (before) rest else played) else (if (before) played else rest)
            drawRoundRect(
                color,
                topLeft = Offset(x, (size.height - h) / 2f),
                size = Size(bw, h),
                cornerRadius = CornerRadius(bw / 2f)
            )
        }
        if (cursor != null) {
            val cx = cursor * size.width
            drawLine(Color.White, Offset(cx, 0f), Offset(cx, size.height), strokeWidth = 2.dp.toPx())
        }
    }
}
