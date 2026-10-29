package com.powersound.player.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.powersound.player.audio.AudioEngine
import com.powersound.player.audio.BUILT_IN_PRESETS
import com.powersound.player.audio.BandType
import com.powersound.player.audio.EqBand
import com.powersound.player.audio.EqSettings
import com.powersound.player.audio.MAX_GAIN_DB
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

private val Panel = Color(0xFF16161D).copy(alpha = 0.92f)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EqPage(bottomInset: Dp) {
    val settings by AudioEngine.settings.collectAsState()
    var editBand by remember { mutableIntStateOf(-1) }
    var showPresets by remember { mutableStateOf(false) }
    var showSave by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

    fun edit(change: (EqSettings) -> EqSettings) = AudioEngine.update(change)
    fun editBandAt(i: Int, change: (EqBand) -> EqBand) = edit { s ->
        s.copy(bands = s.bands.mapIndexed { idx, b -> if (idx == i) change(b) else b }, presetName = "Custom")
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 10.dp, end = 10.dp, top = 4.dp, bottom = bottomInset + 10.dp)
    ) {
        // ---- sliders panel ----
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(Panel)
                .padding(vertical = 12.dp)
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(250.dp)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 4.dp)
            ) {
                SliderColumn(
                    label = "Preamp",
                    value = settings.preampDb,
                    enabled = settings.enabled,
                    onLabel = null,
                    onChange = { v -> edit { it.copy(preampDb = v) } },
                    modifier = Modifier.width(58.dp)
                )
                Box(
                    Modifier
                        .padding(vertical = 8.dp)
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(Color.White.copy(alpha = 0.12f))
                )
                settings.bands.forEachIndexed { i, band ->
                    SliderColumn(
                        label = freqLabel(band.freq),
                        value = band.gainDb,
                        enabled = settings.enabled,
                        onLabel = { editBand = i },
                        onChange = { v -> editBandAt(i) { it.copy(gainDb = v) } },
                        selected = editBand == i,
                        modifier = Modifier.width(46.dp)
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            ResponseCurve(settings)
            Text(
                statusLine(settings),
                color = Color.White.copy(alpha = 0.75f),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
            )
        }

        Spacer(Modifier.height(10.dp))

        // ---- controls: toggles | preset + knobs ----
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(Panel)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ToggleChip("Equ", settings.enabled) { edit { it.copy(enabled = !it.enabled) } }
                ToggleChip("Tone", settings.toneEnabled) { edit { it.copy(toneEnabled = !it.toneEnabled) } }
                ToggleChip("Limit", settings.limiter) { edit { it.copy(limiter = !it.limiter) } }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .weight(1f)
                            .height(40.dp)
                            .clip(RoundedCornerShape(50))
                            .border(1.5.dp, Color.White.copy(alpha = 0.6f), RoundedCornerShape(50))
                            .clickable { showPresets = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (settings.presetName == "Flat") "Preset" else settings.presetName,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }
                    Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = "More", tint = Color.White)
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Auto headroom") },
                                leadingIcon = { Checkbox(checked = settings.autoHeadroom, onCheckedChange = null) },
                                onClick = { edit { it.copy(autoHeadroom = !it.autoHeadroom) } }
                            )
                            DropdownMenuItem(
                                text = { Text("Save as preset") },
                                onClick = { menu = false; showSave = true }
                            )
                            DropdownMenuItem(
                                text = { Text("Reset to flat") },
                                onClick = { menu = false; AudioEngine.resetAll() }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                    PowerKnob(
                        label = "Bass",
                        valueText = dbText(settings.bassDb),
                        fraction = ((settings.bassDb + MAX_GAIN_DB) / (2 * MAX_GAIN_DB)).toFloat(),
                        bipolar = true,
                        enabled = settings.enabled && settings.toneEnabled,
                        sizeDp = 74,
                        onChange = { f -> edit { it.copy(bassDb = ((f * 2 - 1) * MAX_GAIN_DB * 2).roundToInt() / 2.0) } },
                        onReset = { edit { it.copy(bassDb = 0.0) } }
                    )
                    PowerKnob(
                        label = "Treble",
                        valueText = dbText(settings.trebleDb),
                        fraction = ((settings.trebleDb + MAX_GAIN_DB) / (2 * MAX_GAIN_DB)).toFloat(),
                        bipolar = true,
                        enabled = settings.enabled && settings.toneEnabled,
                        sizeDp = 74,
                        onChange = { f -> edit { it.copy(trebleDb = ((f * 2 - 1) * MAX_GAIN_DB * 2).roundToInt() / 2.0) } },
                        onReset = { edit { it.copy(trebleDb = 0.0) } }
                    )
                }
            }
        }

        Text(
            "Drag a slider up or down • double-tap to reset • tap a frequency to change its type, frequency and width",
            color = Color.White.copy(alpha = 0.45f),
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
        )
    }

    // ---- band editor ----
    if (editBand in settings.bands.indices) {
        val i = editBand
        val band = settings.bands[i]
        AlertDialog(
            onDismissRequest = { editBand = -1 },
            containerColor = Color(0xFF17171F),
            title = { Text("Band ${i + 1} • ${freqLabel(band.freq)} Hz", color = Color.White) },
            text = { BandEditor(band) { change -> editBandAt(i, change) } },
            confirmButton = { TextButton(onClick = { editBand = -1 }) { Text("Done") } }
        )
    }

    // ---- presets ----
    if (showPresets) {
        val custom by AudioEngine.customPresets.collectAsState()
        ModalBottomSheet(onDismissRequest = { showPresets = false }, containerColor = Color(0xFF17171F)) {
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                item {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { showPresets = false; showSave = true }
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(14.dp))
                        Text("Save current sound as preset", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }
                }
                items(BUILT_IN_PRESETS + custom, key = { (if (it.builtIn) "b:" else "c:") + it.name }) { p ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { AudioEngine.applyPreset(p); showPresets = false }
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.width(38.dp)) {
                            if (settings.presetName == p.name) {
                                Icon(Icons.Rounded.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                        Text(p.name, color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
                        if (!p.builtIn) {
                            IconButton(onClick = { AudioEngine.deleteCustomPreset(p.name) }) {
                                Icon(Icons.Rounded.Delete, contentDescription = "Delete", tint = Color.White.copy(alpha = 0.6f))
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSave) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showSave = false },
            containerColor = Color(0xFF17171F),
            title = { Text("Save preset", color = Color.White) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(24) },
                    singleLine = true,
                    placeholder = { Text("My sound") }
                )
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = { AudioEngine.saveCustomPreset(name); showSave = false }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showSave = false }) { Text("Cancel") } }
        )
    }
}

private fun statusLine(s: EqSettings): String {
    if (!s.enabled) return "EQ OFF • BIT-PERFECT PASS-THROUGH"
    val parts = ArrayList<String>()
    parts.add("EQ ${s.bands.size}")
    if (s.toneEnabled) parts.add("TONE")
    if (s.limiter) parts.add("LMT")
    val hr = AudioEngine.headroomDb
    if (s.autoHeadroom && hr < -0.05) parts.add("HEADROOM %.1f dB".format(hr))
    return parts.joinToString("  •  ")
}

@Composable
private fun ToggleChip(text: String, on: Boolean, onClick: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    Box(
        Modifier
            .width(64.dp)
            .height(34.dp)
            .clip(RoundedCornerShape(50))
            .background(if (on) primary.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.08f))
            .border(1.dp, if (on) primary else Color.Transparent, RoundedCornerShape(50))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = if (on) primary else Color.White.copy(alpha = 0.7f), fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

@Composable
private fun SliderColumn(
    label: String,
    value: Double,
    enabled: Boolean,
    onLabel: (() -> Unit)?,
    onChange: (Double) -> Unit,
    selected: Boolean = false,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
        VerticalGainSlider(value, enabled, onChange, Modifier.weight(1f).fillMaxWidth())
        Spacer(Modifier.height(6.dp))
        Text(
            label,
            color = Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            modifier = if (onLabel != null) Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(if (selected) Color.White.copy(alpha = 0.18f) else Color.Transparent)
                .clickable(onClick = onLabel)
                .padding(horizontal = 2.dp, vertical = 2.dp) else Modifier.padding(vertical = 2.dp)
        )
        Text(
            "%.1f".format(value),
            color = if (value == 0.0) Color.White.copy(alpha = 0.5f) else MaterialTheme.colorScheme.primary,
            fontSize = 10.sp
        )
    }
}

/** Vertical fader. Drag to change (relative, so a touch never jumps the value); double-tap = 0 dB. */
@Composable
private fun VerticalGainSlider(
    value: Double,
    enabled: Boolean,
    onChange: (Double) -> Unit,
    modifier: Modifier = Modifier
) {
    val current by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    val primary = MaterialTheme.colorScheme.primary
    val fill = if (enabled) primary else Color.White.copy(alpha = 0.3f)
    Canvas(
        modifier
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { change(0.0) })
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val startY = down.position.y
                    val startValue = current
                    var moved = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val c = event.changes.firstOrNull() ?: break
                        if (!c.pressed) break
                        val dy = c.position.y - startY
                        val dx = c.position.x - down.position.x
                        if (!moved && kotlin.math.abs(dx) > viewConfiguration.touchSlop) break // sideways: let the row / pages scroll
                        if (!moved && kotlin.math.abs(dy) > viewConfiguration.touchSlop) moved = true
                        if (moved) {
                            c.consume()
                            val usable = (size.height * 0.82f).coerceAtLeast(1f)
                            val v = (startValue - dy / usable * (2 * MAX_GAIN_DB)).coerceIn(-MAX_GAIN_DB, MAX_GAIN_DB)
                            change((v * 10).roundToInt() / 10.0)
                        }
                    }
                }
            }
    ) {
        val w = size.width
        val h = size.height
        val pad = 14.dp.toPx()
        val top = pad
        val bottom = h - pad
        fun yOf(db: Double) = (top + (bottom - top) * (1 - (db + MAX_GAIN_DB) / (2 * MAX_GAIN_DB))).toFloat()
        val cx = w / 2f
        val track = 3.dp.toPx()

        // track + tick marks
        drawRoundRect(Color.White.copy(alpha = 0.14f), Offset(cx - track / 2, top), Size(track, bottom - top), CornerRadius(track))
        listOf(-12.0, -6.0, 0.0, 6.0, 12.0).forEach { db ->
            val y = yOf(db)
            val len = if (db == 0.0) 10.dp.toPx() else 5.dp.toPx()
            drawLine(Color.White.copy(alpha = 0.3f), Offset(cx - len, y), Offset(cx - track, y), strokeWidth = 1.dp.toPx())
        }
        // fill from the bottom up to the value
        val yv = yOf(value)
        drawRoundRect(fill, Offset(cx - track / 2, yv), Size(track, bottom - yv), CornerRadius(track))
        // thumb
        val tw = (w * 0.62f).coerceAtMost(28.dp.toPx())
        val th = 16.dp.toPx()
        drawRoundRect(Color(0xFFE8E8EE), Offset(cx - tw / 2, yv - th / 2), Size(tw, th), CornerRadius(5.dp.toPx()))
        drawLine(Color(0xFF55555F), Offset(cx - tw * 0.3f, yv), Offset(cx + tw * 0.3f, yv), strokeWidth = 2.dp.toPx())
    }
}

private fun dbText(v: Double) = if (v > 0) "+%.1f dB".format(v) else "%.1f dB".format(v)

@Composable
private fun ResponseCurve(settings: EqSettings) {
    val freqs = remember { AudioEngine.curveFrequencies(140) }
    val curve = remember(settings) { AudioEngine.curve(settings, freqs) }
    val line = if (settings.enabled) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.4f)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.35f))
    ) {
        val w = size.width
        val h = size.height
        val range = 15.0
        fun yOf(db: Double) = (h / 2 - (db / range) * (h / 2)).toFloat()
        drawLine(Color.White.copy(alpha = 0.15f), Offset(0f, yOf(0.0)), Offset(w, yOf(0.0)), strokeWidth = 1f)
        listOf(100.0, 1000.0, 10000.0).forEach { f ->
            val x = (ln(f / 20.0) / ln(1000.0) * w).toFloat()
            drawLine(Color.White.copy(alpha = 0.08f), Offset(x, 0f), Offset(x, h), strokeWidth = 1f)
        }
        val path = Path()
        curve.forEachIndexed { i, db ->
            val x = w * i / (freqs.size - 1)
            val y = yOf(db.coerceIn(-range, range))
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, line, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Composable
private fun BandEditor(band: EqBand, onChange: ((EqBand) -> EqBand) -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            BandType.values().forEach { t ->
                FilterChip(
                    selected = band.type == t,
                    onClick = { onChange { it.copy(type = t) } },
                    label = { Text(t.label, fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                    )
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text("Gain: %.1f dB".format(band.gainDb), color = Color.White, fontSize = 13.sp)
        Slider(
            value = band.gainDb.toFloat(),
            onValueChange = { g -> onChange { it.copy(gainDb = (g * 10).roundToInt() / 10.0) } },
            valueRange = (-MAX_GAIN_DB).toFloat()..MAX_GAIN_DB.toFloat(),
            colors = SliderDefaults.colors(thumbColor = primary, activeTrackColor = primary)
        )
        Text("Frequency: ${freqLabel(band.freq)} Hz", color = Color.White, fontSize = 13.sp)
        Slider(
            value = (ln(band.freq / 20.0) / ln(1000.0)).toFloat().coerceIn(0f, 1f),
            onValueChange = { pos -> onChange { it.copy(freq = roundFreq(20.0 * 1000.0.pow(pos.toDouble()))) } },
            colors = SliderDefaults.colors(thumbColor = primary, activeTrackColor = primary)
        )
        Text("Width (Q): %.2f".format(band.q), color = Color.White, fontSize = 13.sp)
        Slider(
            value = band.q.toFloat(),
            onValueChange = { q -> onChange { it.copy(q = (q * 100).roundToInt() / 100.0) } },
            valueRange = 0.3f..8f,
            colors = SliderDefaults.colors(thumbColor = primary, activeTrackColor = primary)
        )
        Text(
            "Low Q = wide and smooth, high Q = narrow and precise.",
            color = Color.White.copy(alpha = 0.55f),
            fontSize = 11.sp
        )
    }
}

private fun freqLabel(f: Double): String =
    if (f >= 1000) {
        val k = f / 1000.0
        if (k >= 10 || k == k.roundToInt().toDouble()) "${k.roundToInt()}K" else "%.1fK".format(k)
    } else "${f.roundToInt()}"

private fun roundFreq(f: Double): Double = when {
    f < 100 -> f.roundToInt().toDouble()
    f < 1000 -> (f / 5).roundToInt() * 5.0
    f < 10000 -> (f / 50).roundToInt() * 50.0
    else -> (f / 100).roundToInt() * 100.0
}

