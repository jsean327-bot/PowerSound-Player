package com.powersound.player.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.powersound.player.audio.AudioEngine
import com.powersound.player.audio.BUILT_IN_PRESETS
import com.powersound.player.audio.BandType
import com.powersound.player.audio.EqBand
import com.powersound.player.audio.EqPreset
import com.powersound.player.audio.EqSettings
import com.powersound.player.audio.MAX_GAIN_DB
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EqualizerScreen(onClose: () -> Unit) {
    val settings by AudioEngine.settings.collectAsState()
    val custom by AudioEngine.customPresets.collectAsState()
    val colors = MaterialTheme.colorScheme
    var expandedBand by remember { mutableStateOf(-1) }
    var showSave by remember { mutableStateOf(false) }

    fun edit(change: (EqSettings) -> EqSettings) = AudioEngine.update(change)
    fun editBand(i: Int, change: (EqBand) -> EqBand) = edit { s ->
        s.copy(
            bands = s.bands.mapIndexed { idx, b -> if (idx == i) change(b) else b },
            presetName = "Custom"
        )
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        LazyColumn(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            // ---- header ----
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = colors.onBackground)
                    }
                    Text(
                        "Equalizer",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = colors.onBackground,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        if (settings.enabled) "ON" else "OFF",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (settings.enabled) colors.primary else colors.onSurfaceVariant
                    )
                    Spacer(Modifier.width(8.dp))
                    Switch(
                        checked = settings.enabled,
                        onCheckedChange = { on -> edit { it.copy(enabled = on) } },
                        colors = SwitchDefaults.colors(checkedTrackColor = colors.primary)
                    )
                    Spacer(Modifier.width(12.dp))
                }
            }

            // ---- response curve ----
            item {
                ResponseCurve(settings)
                val headroom = AudioEngine.headroomDb
                Text(
                    buildString {
                        append("Preset: ${settings.presetName}")
                        if (settings.enabled && headroom < -0.05) append("   •   Auto headroom %.1f dB".format(headroom))
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
                )
            }

            // ---- presets ----
            item {
                val all: List<EqPreset> = BUILT_IN_PRESETS + custom
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        FilterChip(
                            selected = false,
                            onClick = { showSave = true },
                            label = { Text("Save") },
                            leadingIcon = { Icon(Icons.Rounded.Add, contentDescription = null) }
                        )
                    }
                    items(all, key = { (if (it.builtIn) "b:" else "c:") + it.name }) { p ->
                        FilterChip(
                            selected = settings.presetName == p.name,
                            onClick = { AudioEngine.applyPreset(p) },
                            label = { Text(p.name) },
                            leadingIcon = if (settings.presetName == p.name) {
                                { Icon(Icons.Rounded.Check, contentDescription = null) }
                            } else null,
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = colors.primary,
                                selectedLabelColor = colors.onPrimary,
                                selectedLeadingIconColor = colors.onPrimary
                            )
                        )
                    }
                }
                val selectedCustom = custom.firstOrNull { it.name == settings.presetName }
                if (selectedCustom != null) {
                    TextButton(
                        onClick = { AudioEngine.deleteCustomPreset(selectedCustom.name) },
                        modifier = Modifier.padding(horizontal = 8.dp)
                    ) { Text("Delete preset \"${selectedCustom.name}\"") }
                }
            }

            // ---- bass & treble ----
            item { SectionTitle("Bass & Treble") }
            item {
                GainRow("Bass", settings.bassDb) { v -> edit { it.copy(bassDb = v) } }
                GainRow("Treble", settings.trebleDb) { v -> edit { it.copy(trebleDb = v) } }
            }

            // ---- bands ----
            item {
                SectionTitle("Parametric EQ")
                Text(
                    "Tap a frequency to change its type, frequency and Q.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
            }
            settings.bands.forEachIndexed { i, band ->
                item(key = "band$i") {
                    Column {
                        GainRow(
                            label = freqLabel(band.freq),
                            value = band.gainDb,
                            highlight = expandedBand == i,
                            onLabelClick = { expandedBand = if (expandedBand == i) -1 else i }
                        ) { v -> editBand(i) { it.copy(gainDb = v) } }
                        if (expandedBand == i) BandEditor(band) { change -> editBand(i, change) }
                    }
                }
            }

            // ---- output ----
            item { SectionTitle("Output") }
            item {
                GainRow("Preamp", settings.preampDb) { v -> edit { it.copy(preampDb = v) } }
                ToggleRow(
                    "Auto headroom",
                    "Lowers the volume by the largest boost so the EQ never clips.",
                    settings.autoHeadroom
                ) { on -> edit { it.copy(autoHeadroom = on) } }
                ToggleRow(
                    "Limiter",
                    "Catches any remaining peaks before they distort.",
                    settings.limiter
                ) { on -> edit { it.copy(limiter = on) } }
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = { AudioEngine.resetAll(); expandedBand = -1 },
                    modifier = Modifier.padding(horizontal = 8.dp)
                ) { Text("Reset everything to flat") }
            }
        }
    }

    if (showSave) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showSave = false },
            title = { Text("Save preset") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(24) },
                    singleLine = true,
                    placeholder = { Text("My sound") }
                )
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = { AudioEngine.saveCustomPreset(name); showSave = false }
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showSave = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun SectionTitle(title: String) {
    Column {
        Spacer(Modifier.height(14.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp)
        )
    }
}

@Composable
private fun GainRow(
    label: String,
    value: Double,
    highlight: Boolean = false,
    onLabelClick: (() -> Unit)? = null,
    onChange: (Double) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (highlight) colors.primary else colors.onBackground,
            fontWeight = if (highlight) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier
                .width(64.dp)
                .clip(RoundedCornerShape(6.dp))
                .then(if (onLabelClick != null) Modifier.clickable(onClick = onLabelClick) else Modifier)
                .padding(horizontal = 6.dp, vertical = 8.dp)
        )
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange((it * 10).roundToInt() / 10.0) },
            valueRange = (-MAX_GAIN_DB).toFloat()..MAX_GAIN_DB.toFloat(),
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(
                thumbColor = colors.primary,
                activeTrackColor = colors.primary,
                inactiveTrackColor = colors.outline
            )
        )
        Text(
            dbLabel(value),
            style = MaterialTheme.typography.labelMedium,
            color = if (value == 0.0) colors.onSurfaceVariant else colors.primary,
            modifier = Modifier
                .width(64.dp)
                .padding(start = 6.dp)
        )
    }
}

@Composable
private fun BandEditor(band: EqBand, onChange: ((EqBand) -> EqBand) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surfaceVariant)
            .padding(12.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            BandType.values().forEach { t ->
                FilterChip(
                    selected = band.type == t,
                    onClick = { onChange { it.copy(type = t) } },
                    label = { Text(t.label) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = colors.primary,
                        selectedLabelColor = colors.onPrimary
                    )
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        // Frequency on a log scale: slider 0..1 maps to 20 Hz .. 20 kHz
        Text("Frequency: ${freqLabel(band.freq)}", style = MaterialTheme.typography.labelMedium)
        Slider(
            value = (ln(band.freq / 20.0) / ln(1000.0)).toFloat().coerceIn(0f, 1f),
            onValueChange = { pos ->
                val f = 20.0 * 1000.0.pow(pos.toDouble())
                onChange { it.copy(freq = roundFreq(f)) }
            },
            colors = SliderDefaults.colors(thumbColor = colors.primary, activeTrackColor = colors.primary)
        )
        Text("Q (width): %.2f".format(band.q), style = MaterialTheme.typography.labelMedium)
        Slider(
            value = band.q.toFloat(),
            onValueChange = { q -> onChange { it.copy(q = (q * 100).roundToInt() / 100.0) } },
            valueRange = 0.3f..8f,
            colors = SliderDefaults.colors(thumbColor = colors.primary, activeTrackColor = colors.primary)
        )
        Text(
            "Low Q = wide and smooth, high Q = narrow and precise.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant
        )
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = colors.onBackground)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = colors.primary)
        )
    }
}

@Composable
private fun ResponseCurve(settings: EqSettings) {
    val colors = MaterialTheme.colorScheme
    val freqs = remember { AudioEngine.curveFrequencies(160) }
    val curve = remember(settings) { AudioEngine.curve(settings, freqs) }
    val lineColor = if (settings.enabled) colors.primary else colors.onSurfaceVariant
    val gridColor = colors.outline.copy(alpha = 0.6f)

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(170.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surface)
    ) {
        val w = size.width
        val h = size.height
        val range = 15.0 // dB shown above and below 0
        fun yOf(db: Double) = (h / 2 - (db / range) * (h / 2)).toFloat()
        fun xOf(i: Int) = w * i / (freqs.size - 1)

        // grid: 0 dB, ±6, ±12 and decade lines
        listOf(-12.0, -6.0, 0.0, 6.0, 12.0).forEach { db ->
            drawLine(
                gridColor,
                Offset(0f, yOf(db)),
                Offset(w, yOf(db)),
                strokeWidth = if (db == 0.0) 2f else 1f
            )
        }
        listOf(100.0, 1000.0, 10000.0).forEach { f ->
            val x = (ln(f / 20.0) / ln(1000.0) * w).toFloat()
            drawLine(gridColor, Offset(x, 0f), Offset(x, h), strokeWidth = 1f)
        }

        val path = Path()
        curve.forEachIndexed { i, db ->
            val x = xOf(i)
            val y = yOf(db.coerceIn(-range, range))
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        val fill = Path().apply {
            addPath(path)
            lineTo(w, yOf(0.0))
            lineTo(0f, yOf(0.0))
            close()
        }
        drawPath(
            fill,
            Brush.verticalGradient(listOf(lineColor.copy(alpha = 0.35f), lineColor.copy(alpha = 0.02f)))
        )
        drawPath(path, lineColor, style = Stroke(width = 5f, cap = StrokeCap.Round))
    }
}

private fun dbLabel(v: Double): String = when {
    v > 0.0 -> "+%.1f dB".format(v)
    v < 0.0 -> "%.1f dB".format(v)
    else -> "0 dB"
}

private fun freqLabel(f: Double): String =
    if (f >= 1000) {
        val k = f / 1000.0
        if (k >= 10 || k == k.roundToInt().toDouble()) "${k.roundToInt()}k" else "%.1fk".format(k)
    } else "${f.roundToInt()}"

private fun roundFreq(f: Double): Double = when {
    f < 100 -> f.roundToInt().toDouble()
    f < 1000 -> (f / 5).roundToInt() * 5.0
    f < 10000 -> (f / 50).roundToInt() * 50.0
    else -> (f / 100).roundToInt() * 100.0
}
