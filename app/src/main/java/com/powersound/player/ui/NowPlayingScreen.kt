package com.powersound.player.ui

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.rounded.Close
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NowPlayingScreen(
    state: PlayerUiState,
    position: StateFlow<PlaybackPos>,
    song: Song?,
    rating: Int,
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
    onQueue: () -> Unit,
    onMore: () -> Unit,
    onSleep: () -> Unit,
    onLyrics: () -> Unit,
    onAudioInfo: () -> Unit
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

    // 0 = fully open, 1 = gone. Follows the finger, then finishes smoothly when released.
    val closeP = remember { Animatable(0f) }
    val artX = remember { Animatable(0f) }
    LaunchedEffect(Unit) { closeP.snapTo(0f) }

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
            delay(3500)
            showControls = false
        }
    }
    var choosePreset by remember { mutableStateOf(false) }
    val contentAlpha by animateFloatAsState(
        when (vizMode) {
            VizMode.OFF -> 1f
            VizMode.FADE -> if (showControls) 1f else 0.42f
            VizMode.FULL -> if (showControls) 1f else 0f
        },
        tween(300),
        label = "controls"
    )

    // ---- long-press scrubbing on the waveform / button area ----
    var scrubbing by remember { mutableStateOf(false) }
    var scrubFrac by remember { mutableFloatStateOf(0f) }
    val buttonsAlpha by animateFloatAsState(if (scrubbing) 0.08f else 1f, tween(180), label = "buttons")

    var metaMode by remember { mutableIntStateOf(0) }

    // System back gesture: the screen shrinks and fades with the finger.
    PredictiveBackHandler { progress ->
        try {
            progress.collect { closeP.snapTo(it.progress) }
            closeP.animateTo(1f, tween(170))
            close()
        } catch (e: CancellationException) {
            scope.launch { closeP.animateTo(0f, tween(200)) }
            throw e
        }
    }

    fun closeToSource() {
        scope.launch {
            closeP.animateTo(1f, tween(200))
            toSource()
        }
    }

    val peaks by produceState<FloatArray?>(np?.songId?.let { Waveform.cached(it) }, np?.songId) {
        value = np?.songId?.let { Waveform.load(context, it) }
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
                                    closeP.animateTo(1f, tween(180))
                                    toSource()
                                }
                                closeP.value > 0f -> closeP.animateTo(0f, tween(220))
                                up < -120f -> lyrics()
                            }
                        }
                    },
                    onDragCancel = { scope.launch { closeP.animateTo(0f, tween(220)) } }
                )
            }
    ) {
        AppBackground(np?.songId)

        if (vizMode != VizMode.OFF) {
            VisualizerView(
                preset = vizPreset,
                accent = accent,
                modifier = Modifier
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
                .alpha(contentAlpha)
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 14.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                Modifier
                    .padding(vertical = 6.dp)
                    .width(40.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.3f))
            )

            // ---- cover: tap = track list, swipe = change track, long swipe = change category ----
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .graphicsLayer {
                        translationX = artX.value
                        alpha = 1f - (kotlin.math.abs(artX.value) / size.width).coerceIn(0f, 1f) * 0.6f
                    }
                    .clip(RoundedCornerShape(24.dp))
                    .pointerInput(Unit) { detectTapGestures(onTap = { closeToSource() }) }
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
                                        artX.animateTo(if (x < 0) -w else w, tween(140))
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
                                        artX.animateTo(0f, tween(220))
                                    } else {
                                        artX.animateTo(0f)
                                    }
                                }
                            },
                            onDragCancel = { scope.launch { artX.animateTo(0f) } }
                        )
                    }
            ) {
                Artwork(np?.songId, Modifier.fillMaxSize(), sizePx = 1024, cornerDp = 0, placeholder = { BarsPlaceholder() })
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.55f)))
                )
                Column(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(12.dp)
                ) {
                    OverlayChip(np?.title ?: "", big = true)
                    Spacer(Modifier.height(4.dp))
                    OverlayChip(listOfNotNull(np?.artist, np?.album?.takeIf { it.isNotBlank() }).joinToString(" - "))
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        RoundIcon(if (rating == 1) Icons.Rounded.ThumbUp else Icons.Outlined.ThumbUp, rating == 1, onLike)
                        RoundIcon(if (rating == -1) Icons.Rounded.ThumbDown else Icons.Outlined.ThumbDown, rating == -1, onDislike)
                    }
                }
                Row(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    RoundIcon(Icons.Rounded.QueueMusic, false, onQueue)
                    RoundIcon(Icons.Rounded.MoreVert, false, onMore)
                }
            }

            Spacer(Modifier.height(12.dp))

            // ---- small pills: visualization, sleep | repeat mode, shuffle ----
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SmallPill(
                    Icons.Rounded.GraphicEq, vizMode != VizMode.OFF,
                    onClick = {
                        val m = vizMode.next()
                        onVizMode(m)
                        showToast(m.label)
                    },
                    onLongClick = { choosePreset = true }
                )
                Spacer(Modifier.width(8.dp))
                SmallPill(Icons.Rounded.Timer, state.sleepEndsAt != null, onClick = onSleep)
                Spacer(Modifier.weight(1f))
                SmallPill(
                    repeatIcon(state.repeat), state.repeat != RepeatMode.LIST_STOP,
                    onClick = { showToast(onRepeat().label) },
                    onLongClick = onRepeatMenu
                )
                Spacer(Modifier.width(8.dp))
                SmallPill(
                    Icons.Rounded.Shuffle, state.shuffleMode != ShuffleMode.OFF,
                    onClick = {
                        onShuffle()
                        showToast(if (state.shuffleMode == ShuffleMode.OFF) "Shuffle songs" else "Shuffle off")
                    },
                    onLongClick = onShuffleMenu
                )
            }

            Spacer(Modifier.weight(1f))

            // ---- transport over the waveform; long press anywhere here to scrub ----
            val pos by position.collectAsState()
            val duration = pos.durationMs.coerceAtLeast(1L)
            val fraction = (pos.positionMs.toFloat() / duration).coerceIn(0f, 1f)
            var rowBounds by remember { mutableStateOf(Rect.Zero) }
            val edgeButton = with(androidx.compose.ui.platform.LocalDensity.current) { 54.dp.toPx() }
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
                            val waited = kotlinx.coroutines.withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
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
                            seek((scrubFrac * duration).toLong())
                            scrubbing = false
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                WaveSeekBar(
                    peaks = peaks,
                    fraction = if (scrubbing) scrubFrac else fraction,
                    cursor = if (scrubbing) scrubFrac else null,
                    onDrag = {},
                    onSeek = { f -> onSeek((f * duration).toLong()) },
                    modifier = Modifier.fillMaxSize()
                )
                Row(
                    Modifier
                        .alpha(buttonsAlpha)
                        .onGloballyPositioned { rowBounds = it.boundsInParent() },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    TransportButton(Icons.Rounded.SkipPrevious, 50, onPrevious, onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        showToast(if (prevCat()) "Previous category" else "No other category", "Long press ⏮ / ⏭ to change category")
                    })
                    TransportButton(Icons.Rounded.FastRewind, 58, { onSeekBy(-10_000) })
                    Box(
                        Modifier
                            .size(84.dp)
                            .clip(CircleShape)
                            .background(Color.Black)
                            .border(2.dp, Color.White.copy(alpha = 0.15f), CircleShape)
                            .clickable(onClick = onTogglePlay),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = "Play/Pause",
                            tint = Color.White,
                            modifier = Modifier.size(50.dp)
                        )
                    }
                    TransportButton(Icons.Rounded.FastForward, 58, { onSeekBy(10_000) })
                    TransportButton(Icons.Rounded.SkipNext, 50, onNext, onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        showToast(if (nextCat()) "Next category" else "No other category", "Long press ⏮ / ⏭ to change category")
                    })
                }
            }

            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TimeChip(formatTime(if (scrubbing) (scrubFrac * duration).toLong() else pos.positionMs), big = scrubbing)
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

        // full-screen visualization: tap shows the controls for a moment
        if (vizMode == VizMode.FULL && !showControls) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { showControls = true }
            )
        } else if (vizMode == VizMode.FADE && !showControls) {
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
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.GraphicEq, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f).clickable { choosePreset = true }) {
                    Text(vizPreset.label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text("Built-in • tap for list", color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp)
                }
                IconButton(onClick = { onVizPreset(vizPreset.next(-1)) }) {
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, contentDescription = "Previous preset", tint = Color.White)
                }
                IconButton(onClick = { onVizPreset(vizPreset.next(1)) }) {
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
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
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
                    "Swipe down or tap the cover for the track's list. Swipe up for lyrics. " +
                    "Long press the progress area to seek. Long press ⏮ ⏭ for previous / next category. " +
                    "Tap the info line to change it, long press for Audio Info.",
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
}

private fun repeatIcon(m: RepeatMode): ImageVector = when (m) {
    RepeatMode.ADVANCE -> Icons.Rounded.PlaylistPlay
    RepeatMode.SONG_REPEAT -> Icons.Rounded.RepeatOne
    RepeatMode.LIST_REPEAT -> Icons.Rounded.Repeat
    RepeatMode.ADVANCE_REPEAT -> Icons.Rounded.Loop
    RepeatMode.SONG_STOP -> Icons.Rounded.LooksOne
    RepeatMode.LIST_STOP -> Icons.Rounded.Repeat
}

@Composable
private fun RoundIcon(icon: ImageVector, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(ChipBg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = if (active) MaterialTheme.colorScheme.primary else Color.White, modifier = Modifier.size(21.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SmallPill(icon: ImageVector, active: Boolean, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    Box(
        Modifier
            .width(56.dp)
            .height(36.dp)
            .clip(RoundedCornerShape(50))
            .background(if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.08f))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = if (active) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.6f), modifier = Modifier.size(22.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TransportButton(icon: ImageVector, sizeDp: Int, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    Box(
        Modifier
            .size(sizeDp.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.85f))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size((sizeDp * 0.55f).dp))
    }
}

@Composable
private fun TimeChip(text: String, big: Boolean = false) {
    Text(
        text,
        color = Color.White,
        fontSize = if (big) 16.sp else 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(ChipBg)
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
