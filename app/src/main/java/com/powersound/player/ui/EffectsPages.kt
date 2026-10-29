package com.powersound.player.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SurroundSound
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.powersound.player.audio.AudioEngine
import com.powersound.player.audio.BUILT_IN_REVERBS
import com.powersound.player.audio.FxSettings
import com.powersound.player.audio.TEMPO_MAX
import com.powersound.player.audio.TEMPO_MIN
import com.powersound.player.audio.withReverb
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The sound tab: three pages you swipe between (or pick from the icons on top):
 * Equalizer • Effects (balance, stereo, tempo, mono, volume) • Reverb.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EqualizerScreen(bottomInset: Dp) {
    val pager = rememberPagerState(pageCount = { 3 })
    val scope = rememberCoroutineScope()
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp)
                .clip(RoundedCornerShape(50))
                .background(Color(0xFF16161D).copy(alpha = 0.92f))
                .padding(4.dp)
        ) {
            listOf(Icons.Rounded.Tune, Icons.Rounded.Speed, Icons.Rounded.SurroundSound).forEachIndexed { i, icon ->
                val selected = pager.currentPage == i
                Box(
                    Modifier
                        .weight(1f)
                        .height(40.dp)
                        .clip(RoundedCornerShape(50))
                        .background(if (selected) Color.White.copy(alpha = 0.12f) else Color.Transparent)
                        .clickable { scope.launch { pager.animateScrollToPage(i) } },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        icon,
                        contentDescription = listOf("Equalizer", "Effects", "Reverb")[i],
                        tint = if (selected) Color.White else Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
        }
        HorizontalPager(
            state = pager,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            beyondBoundsPageCount = 1
        ) { page ->
            when (page) {
                0 -> EqPage(bottomInset)
                1 -> EffectsPage(bottomInset)
                else -> ReverbPage(bottomInset)
            }
        }
    }
}

// ================================================================= Effects page

@Composable
private fun EffectsPage(bottomInset: Dp) {
    val fx by AudioEngine.fx.collectAsState()
    fun edit(change: (FxSettings) -> FxSettings) = AudioEngine.updateFx(change)

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = bottomInset + 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Balance • Stereo expand
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
            PowerKnob(
                label = "Balance",
                valueText = when {
                    fx.balance < -0.005 -> "L %.2f".format(-fx.balance)
                    fx.balance > 0.005 -> "R %.2f".format(fx.balance)
                    else -> "0.00"
                },
                fraction = ((fx.balance + 1) / 2).toFloat(),
                bipolar = true,
                enabled = true,
                sizeDp = 76,
                onChange = { f -> edit { it.copy(balance = ((f * 2 - 1) * 100).roundToInt() / 100.0) } },
                onReset = { edit { it.copy(balance = 0.0) } }
            )
            PowerKnob(
                label = "Stereo Expand",
                valueText = "${(fx.stereoExpand * 100).roundToInt()}%",
                fraction = fx.stereoExpand.toFloat(),
                bipolar = false,
                enabled = !fx.mono,
                sizeDp = 76,
                onChange = { f -> edit { it.copy(stereoExpand = (f * 100).roundToInt() / 100.0) } },
                onReset = { edit { it.copy(stereoExpand = 0.0) } }
            )
        }

        Spacer(Modifier.height(18.dp))

        // Tempo
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            FxToggle("Tempo", fx.tempoEnabled) { edit { it.copy(tempoEnabled = !it.tempoEnabled) } }
            Spacer(Modifier.weight(1f))
            PowerKnob(
                label = "Tempo",
                valueText = "%.2fx".format(fx.tempo),
                fraction = tempoToFraction(fx.tempo),
                bipolar = true,
                enabled = fx.tempoEnabled,
                sizeDp = 96,
                onChange = { f -> edit { it.copy(tempo = fractionToTempo(f), tempoEnabled = true) } },
                onReset = { edit { it.copy(tempo = 1.0) } }
            )
            Spacer(Modifier.weight(1f))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallRound(Icons.Rounded.Add) {
                    edit { it.copy(tempo = ((it.tempo + 0.05) * 100).roundToInt().coerceAtMost((TEMPO_MAX * 100).toInt()) / 100.0, tempoEnabled = true) }
                }
                SmallRound(Icons.Rounded.Remove) {
                    edit { it.copy(tempo = ((it.tempo - 0.05) * 100).roundToInt().coerceAtLeast((TEMPO_MIN * 100).toInt()) / 100.0, tempoEnabled = true) }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            FxToggle("Mono", fx.mono) { edit { it.copy(mono = !it.mono) } }
            Spacer(Modifier.weight(1f))
            FxButton("Reset") { AudioEngine.resetEffects() }
        }

        Spacer(Modifier.height(10.dp))

        // Volume
        PowerKnob(
            label = "Volume",
            valueText = "${(fx.volume * 100).roundToInt()}%",
            fraction = fx.volume.toFloat(),
            bipolar = false,
            enabled = true,
            sizeDp = 120,
            onChange = { f -> edit { it.copy(volume = (f * 100).roundToInt() / 100.0) } },
            onReset = { edit { it.copy(volume = 1.0) } }
        )

        Spacer(Modifier.height(10.dp))
        Text(
            "Turn a knob by dragging up or down • double-tap a knob to reset it",
            color = Color.White.copy(alpha = 0.45f),
            fontSize = 11.sp
        )
    }
}

/** Tempo 0.5x..2x on a log scale so 1.0x sits at the top of the knob. */
private fun tempoToFraction(t: Double): Float = ((ln(t) / ln(2.0) + 1) / 2).toFloat().coerceIn(0f, 1f)
private fun fractionToTempo(f: Float): Double = (2.0.pow(f * 2.0 - 1.0) * 100).roundToInt() / 100.0

// ================================================================= Reverb page

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReverbPage(bottomInset: Dp) {
    val fx by AudioEngine.fx.collectAsState()
    val custom by AudioEngine.customReverbs.collectAsState()
    var showPresets by remember { mutableStateOf(false) }
    var showSave by remember { mutableStateOf(false) }
    fun edit(change: (FxSettings) -> FxSettings) = AudioEngine.updateFx { change(it).copy(reverbPreset = "Custom") }
    val on = fx.reverbEnabled

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = bottomInset + 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        @Composable
        fun knob(label: String, value: Double, text: String, set: (FxSettings, Double) -> FxSettings, default: Double) {
            PowerKnob(
                label = label,
                valueText = text,
                fraction = value.toFloat(),
                bipolar = false,
                enabled = on,
                sizeDp = 70,
                onChange = { f -> edit { set(it, (f * 100).roundToInt() / 100.0) } },
                onReset = { edit { set(it, default) } }
            )
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
            knob("Damp", fx.damp, "%.2f".format(fx.damp), { s, v -> s.copy(damp = v) }, 0.5)
            knob("Filter", fx.filter, "%.2f".format(fx.filter), { s, v -> s.copy(filter = v) }, 0.0)
            knob("Fade", fx.fade, "%.2f".format(fx.fade), { s, v -> s.copy(fade = v) }, 0.5)
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
            knob("Pre-Delay", fx.preDelay, "${fx.preDelayMs.roundToInt()} ms", { s, v -> s.copy(preDelay = v) }, 0.0)
            knob("Pre-Delay Mix", fx.preDelayMix, "%.2f".format(fx.preDelayMix), { s, v -> s.copy(preDelayMix = v) }, 0.0)
            knob("Size", fx.size, "%.2f".format(fx.size), { s, v -> s.copy(size = v) }, 0.5)
        }

        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FxToggle("Reverb", on) { AudioEngine.updateFx { it.copy(reverbEnabled = !it.reverbEnabled) } }
            Box(
                Modifier
                    .weight(1f)
                    .height(38.dp)
                    .clip(RoundedCornerShape(50))
                    .border(1.5.dp, Color.White.copy(alpha = 0.6f), RoundedCornerShape(50))
                    .clickable { showPresets = true },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (fx.reverbPreset == "Custom") "Preset" else fx.reverbPreset,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }
            FxButton("Save") { showSave = true }
            FxButton("Reset") { AudioEngine.resetReverb() }
        }

        Spacer(Modifier.height(14.dp))
        PowerKnob(
            label = "Mix",
            valueText = "%.2f".format(fx.mix),
            fraction = fx.mix.toFloat(),
            bipolar = false,
            enabled = on,
            sizeDp = 110,
            onChange = { f -> edit { it.copy(mix = (f * 100).roundToInt() / 100.0) } },
            onReset = { edit { it.copy(mix = 0.3) } }
        )
        if (!on) {
            Spacer(Modifier.height(8.dp))
            Text("Reverb is off — tap Reverb or pick a preset", color = Color.White.copy(alpha = 0.45f), fontSize = 11.sp)
        }
    }

    if (showPresets) {
        ModalBottomSheet(onDismissRequest = { showPresets = false }, containerColor = Color(0xFF17171F)) {
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                items(BUILT_IN_REVERBS + custom, key = { (if (it.builtIn) "b:" else "c:") + it.name }) { p ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                AudioEngine.updateFx { it.withReverb(p) }
                                showPresets = false
                            }
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.width(38.dp)) {
                            if (fx.reverbPreset == p.name) {
                                Icon(Icons.Rounded.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                        Text(p.name, color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
                        if (!p.builtIn) {
                            IconButton(onClick = { AudioEngine.deleteReverbPreset(p.name) }) {
                                Icon(Icons.Rounded.Delete, contentDescription = "Delete", tint = Color.White.copy(alpha = 0.6f))
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSave) {
        SaveNameDialog("Save reverb preset", onSave = { AudioEngine.saveReverbPreset(it) }, onDismiss = { showSave = false })
    }
}

// ================================================================= shared controls

@Composable
fun SaveNameDialog(title: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF17171F),
        title = { Text(title, color = Color.White) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(24) },
                singleLine = true,
                placeholder = { Text("My sound") }
            )
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onSave(name); onDismiss() }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun FxToggle(text: String, on: Boolean, onClick: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    Box(
        Modifier
            .height(36.dp)
            .clip(RoundedCornerShape(50))
            .background(if (on) primary.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.08f))
            .border(1.dp, if (on) primary else Color.White.copy(alpha = 0.2f), RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = if (on) primary else Color.White.copy(alpha = 0.75f), fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

@Composable
private fun FxButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .height(36.dp)
            .clip(RoundedCornerShape(50))
            .border(1.5.dp, Color.White.copy(alpha = 0.6f), RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

@Composable
private fun SmallRound(icon: ImageVector, onClick: () -> Unit) {
    Box(
        Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.08f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(18.dp))
    }
}

/**
 * Rotary knob. [fraction] is 0..1. A bipolar knob is centred at the top (balance, tempo, bass);
 * a normal one starts bottom-left. Drag up/down to turn, double-tap to reset.
 * A sideways swipe is left alone so the pages can still be swiped.
 */
@Composable
fun PowerKnob(
    label: String,
    valueText: String,
    fraction: Float,
    bipolar: Boolean,
    enabled: Boolean,
    sizeDp: Int,
    onChange: (Float) -> Unit,
    onReset: () -> Unit
) {
    val current by rememberUpdatedState(fraction)
    val change by rememberUpdatedState(onChange)
    val reset by rememberUpdatedState(onReset)
    val primary = MaterialTheme.colorScheme.primary
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.alpha(if (enabled) 1f else 0.45f)
    ) {
        Canvas(
            Modifier
                .size(sizeDp.dp)
                .pointerInput(Unit) { detectTapGestures(onDoubleTap = { reset() }) }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val start = current
                        var moved = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val c = event.changes.firstOrNull() ?: break
                            if (!c.pressed) break
                            val dx = c.position.x - down.position.x
                            val dy = c.position.y - down.position.y
                            if (!moved && kotlin.math.abs(dx) > viewConfiguration.touchSlop) break
                            if (!moved && kotlin.math.abs(dy) > viewConfiguration.touchSlop) moved = true
                            if (moved) {
                                c.consume()
                                change((start - dy / 220.dp.toPx()).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
        ) {
            val r = size.minDimension / 2f
            val center = Offset(size.width / 2f, size.height / 2f)
            val stroke = (r * 0.10f).coerceIn(2.dp.toPx(), 5.dp.toPx())
            val arcTopLeft = Offset(center.x - r + stroke, center.y - r + stroke)
            val arcSize = Size((r - stroke) * 2, (r - stroke) * 2)
            // body
            drawCircle(Brush.radialGradient(listOf(Color(0xFF3A3A45), Color(0xFF141419)), center, r), r * 0.80f, center)
            // track (270° starting bottom-left)
            drawArc(Color.White.copy(alpha = 0.14f), 135f, 270f, false, topLeft = arcTopLeft, size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            // value arc
            val f = fraction.coerceIn(0f, 1f)
            val start = if (bipolar) 270f else 135f
            val sweep = if (bipolar) (f - 0.5f) * 270f else f * 270f
            // Settings → Skin → Knob Highlight: none / monochromatic / colorful
            val highlight = com.powersound.player.data.SettingsStore.value.knobHighlight
            if (highlight != 0 && kotlin.math.abs(sweep) > 0.5f) {
                drawArc(if (highlight == 2) primary else Color.White.copy(alpha = 0.85f), start, sweep, false, topLeft = arcTopLeft, size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            // pointer
            val angle = Math.toRadians((135.0 + f * 270.0))
            val inner = r * 0.30f
            val outer = r * 0.66f
            drawLine(
                Color.White,
                Offset(center.x + (inner * cos(angle)).toFloat(), center.y + (inner * sin(angle)).toFloat()),
                Offset(center.x + (outer * cos(angle)).toFloat(), center.y + (outer * sin(angle)).toFloat()),
                strokeWidth = (r * 0.07f).coerceIn(2.dp.toPx(), 4.dp.toPx()),
                cap = StrokeCap.Round
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text(valueText, color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
    }
}
