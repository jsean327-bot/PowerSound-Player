package com.powersound.player.ui

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.compose.runtime.collectAsState
import com.powersound.player.PlaybackPos
import com.powersound.player.PlayerUiState
import kotlinx.coroutines.flow.StateFlow
import com.powersound.player.data.Waveform
import com.powersound.player.data.formatTime
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.max

@Composable
fun NowPlayingScreen(
    state: PlayerUiState,
    position: StateFlow<PlaybackPos>,
    rating: Int,
    sleepActive: Boolean,
    onClose: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onSeekBy: (Long) -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    onLike: () -> Unit,
    onDislike: () -> Unit,
    onQueue: () -> Unit,
    onMore: () -> Unit,
    onSleep: () -> Unit
) {
    val np = state.nowPlaying
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val close by rememberUpdatedState(onClose)
    val next by rememberUpdatedState(onNext)
    val prev by rememberUpdatedState(onPrevious)

    var showWave by rememberSaveable { mutableStateOf(true) }
    val dragY = remember { Animatable(0f) }
    val artX = remember { Animatable(0f) }
    var backProgress by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) { dragY.snapTo(0f) }

    // System back gesture: the screen shrinks with the finger, like the swipe down.
    PredictiveBackHandler { progress ->
        try {
            progress.collect { backProgress = it.progress }
            close()
        } catch (e: CancellationException) {
            backProgress = 0f
            throw e
        }
    }

    val peaks by produceState<FloatArray?>(np?.songId?.let { Waveform.cached(it) }, np?.songId) {
        value = np?.songId?.let { Waveform.load(context, it) }
    }

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                val p = max((dragY.value / size.height).coerceIn(0f, 1f) * 1.6f, backProgress).coerceIn(0f, 1f)
                translationY = dragY.value
                scaleX = 1f - 0.18f * p
                scaleY = 1f - 0.18f * p
                alpha = 1f - 0.5f * p
                shape = RoundedCornerShape(36.dp.toPx() * p)
                clip = p > 0f
            }
            .background(MaterialTheme.colorScheme.background)
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onVerticalDrag = { change, dy ->
                        change.consume()
                        scope.launch { dragY.snapTo((dragY.value + dy).coerceAtLeast(0f)) }
                    },
                    onDragEnd = {
                        scope.launch {
                            if (dragY.value > size.height * 0.15f) close() else dragY.animateTo(0f)
                        }
                    },
                    onDragCancel = { scope.launch { dragY.animateTo(0f) } }
                )
            }
    ) {
        AppBackground(np?.songId)

        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 14.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // drag handle
            Box(
                Modifier
                    .padding(vertical = 6.dp)
                    .width(40.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.3f))
            )

            // ---- cover card: swipe left/right for next/previous ----
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .graphicsLayer {
                        translationX = artX.value
                        alpha = 1f - (kotlin.math.abs(artX.value) / size.width).coerceIn(0f, 1f) * 0.6f
                    }
                    .clip(RoundedCornerShape(24.dp))
                    .pointerInput(Unit) {
                        val w = size.width.toFloat()
                        detectHorizontalDragGestures(
                            onHorizontalDrag = { change, dx ->
                                change.consume()
                                scope.launch { artX.snapTo(artX.value + dx) }
                            },
                            onDragEnd = {
                                scope.launch {
                                    when {
                                        artX.value < -w * 0.25f -> {
                                            artX.animateTo(-w, tween(140))
                                            next()
                                            artX.snapTo(w)
                                            artX.animateTo(0f, tween(220))
                                        }
                                        artX.value > w * 0.25f -> {
                                            artX.animateTo(w, tween(140))
                                            prev()
                                            artX.snapTo(-w)
                                            artX.animateTo(0f, tween(220))
                                        }
                                        else -> artX.animateTo(0f)
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

            // ---- small pills: waveform, sleep | repeat, shuffle ----
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SmallPill(Icons.Rounded.GraphicEq, showWave) { showWave = !showWave }
                Spacer(Modifier.width(8.dp))
                SmallPill(Icons.Rounded.Timer, sleepActive, onSleep)
                Spacer(Modifier.weight(1f))
                SmallPill(
                    if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                    state.repeatMode != Player.REPEAT_MODE_OFF,
                    onRepeat
                )
                Spacer(Modifier.width(8.dp))
                SmallPill(Icons.Rounded.Shuffle, state.shuffle, onShuffle)
            }

            Spacer(Modifier.weight(1f))

            // ---- transport over the waveform ----
            val pos by position.collectAsState()
            val duration = pos.durationMs.coerceAtLeast(1L)
            val fraction = (pos.positionMs.toFloat() / duration).coerceIn(0f, 1f)
            var dragFraction by remember { mutableStateOf<Float?>(null) }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(130.dp),
                contentAlignment = Alignment.Center
            ) {
                if (showWave) {
                    WaveSeekBar(
                        peaks = peaks,
                        fraction = dragFraction ?: fraction,
                        onDrag = { dragFraction = it },
                        onSeek = { f -> dragFraction = null; onSeek((f * duration).toLong()) },
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    TransportButton(Icons.Rounded.SkipPrevious, 50, onPrevious)
                    TransportButton(Icons.Rounded.FastRewind, 58) { onSeekBy(-10_000) }
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
                    TransportButton(Icons.Rounded.FastForward, 58) { onSeekBy(10_000) }
                    TransportButton(Icons.Rounded.SkipNext, 50, onNext)
                }
            }

            if (!showWave) {
                Slider(
                    value = dragFraction ?: fraction,
                    onValueChange = { dragFraction = it },
                    onValueChangeFinished = {
                        dragFraction?.let { onSeek((it * duration).toLong()) }
                        dragFraction = null
                    },
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color.White,
                        inactiveTrackColor = Color.White.copy(alpha = 0.2f)
                    )
                )
            }

            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                val shownPos = dragFraction?.let { (it * duration).toLong() } ?: pos.positionMs
                TimeChip(formatTime(shownPos))
                Spacer(Modifier.weight(1f))
                TimeChip(formatTime(pos.durationMs))
            }
            Text(
                state.audioInfo.uppercase(),
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp, bottom = 6.dp)
            )
        }
    }
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

@Composable
private fun SmallPill(icon: ImageVector, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .width(56.dp)
            .height(36.dp)
            .clip(RoundedCornerShape(50))
            .background(if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.08f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = if (active) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.6f), modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun TransportButton(icon: ImageVector, sizeDp: Int, onClick: () -> Unit) {
    Box(
        Modifier
            .size(sizeDp.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.85f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size((sizeDp * 0.55f).dp))
    }
}

@Composable
private fun TimeChip(text: String) {
    Text(
        text,
        color = Color.White,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(ChipBg)
            .padding(horizontal = 8.dp, vertical = 2.dp)
    )
}

/** Seek bar drawn as the song's real waveform. Tap or drag to seek. */
@Composable
fun WaveSeekBar(
    peaks: FloatArray?,
    fraction: Float,
    onDrag: (Float) -> Unit,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val drag by rememberUpdatedState(onDrag)
    val seek by rememberUpdatedState(onSeek)
    var last by remember { mutableFloatStateOf(0f) }
    val played = Color.White.copy(alpha = 0.95f)
    val rest = Color.White.copy(alpha = 0.22f)
    Canvas(
        modifier
            .pointerInput(Unit) {
                detectTapGestures(onTap = { o -> seek((o.x / size.width).coerceIn(0f, 1f)) })
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> last = (o.x / size.width).coerceIn(0f, 1f); drag(last) },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        last = (change.position.x / size.width).coerceIn(0f, 1f)
                        drag(last)
                    },
                    onDragEnd = { seek(last) },
                    onDragCancel = { seek(last) }
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
            val color = if ((i + 0.5f) / n <= fraction) played else rest
            drawRoundRect(
                color,
                topLeft = Offset(x, (size.height - h) / 2f),
                size = Size(bw, h),
                cornerRadius = CornerRadius(bw / 2f)
            )
        }
    }
}
