package com.powersound.player.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.powersound.player.audio.VisualizerBus
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

enum class VizMode(val label: String) {
    OFF("Disable Visualization"),
    FADE("Visualization - Fade Controls"),
    FULL("Visualization - Full Screen");

    fun next(): VizMode = values()[(ordinal + 1) % values().size]
}

enum class VizPreset(val label: String) {
    SPECTRUM_CENTERED("Spectrum Centered"),
    SPECTRUM("Spectrum Bars"),
    WAVE("Oscilloscope"),
    CIRCLE("Circle Spectrum"),
    MIRROR_WAVE("Mirror Wave");

    fun next(dir: Int): VizPreset = values()[(ordinal + dir + values().size) % values().size]

    /** Next preset that is not switched off in Settings → Visualization → Presets. */
    fun nextEnabled(dir: Int): VizPreset {
        val off = com.powersound.player.data.SettingsStore.value.vizDisabledPresets
        var p = this
        repeat(values().size) {
            p = p.next(dir)
            if (p.name !in off) return p
        }
        return this
    }

    /** Bar-type presets (Settings → Visualization → Scaled Bars For Faded Controls). */
    val isBars: Boolean get() = this == SPECTRUM_CENTERED || this == SPECTRUM
}

/** Live visualization of what is playing, redrawn every frame. */
@Composable
fun VisualizerView(preset: VizPreset, accent: Color, modifier: Modifier = Modifier) {
    DisposableEffect(Unit) {
        VisualizerBus.active = true
        onDispose { VisualizerBus.active = false }
    }
    val st = com.powersound.player.data.SettingsStore.value
    var frame by remember { mutableLongStateOf(0L) }
    LaunchedEffect(st.viz30fps) {
        var last = 0L
        while (true) withFrameMillis { t ->
            // Settings → Visualization → Force 30 FPS
            if (!st.viz30fps || t - last >= 32) {
                frame = t
                last = t
            }
        }
    }
    // Settings → Visualization → HD: twice the bars
    val bands = if (st.vizHd) 128 else 64
    val spec = remember(bands) { FloatArray(bands) }
    val smooth = remember(bands) { FloatArray(bands) }
    val work = remember { FloatArray(1024) }
    val re = remember { FloatArray(1024) }
    val im = remember { FloatArray(1024) }
    val wave = remember { FloatArray(1024) }

    // Settings → Visualization → Crop Aspect: keep a square drawing area instead of stretching
    val area = if (st.vizCrop && !preset.isBars) modifier.then(Modifier.wrapContentSize().aspectRatio(1f)) else modifier
    Canvas(area) {
        if (frame < 0L) return@Canvas // reading the frame clock redraws every frame
        VisualizerBus.spectrum(bands, spec, work, re, im)
        for (i in 0 until bands) smooth[i] = maxOf(spec[i], smooth[i] * 0.86f)
        val w = size.width
        val h = size.height
        val white = Color.White

        when (preset) {
            VizPreset.SPECTRUM_CENTERED -> {
                val n = bands
                val gap = (if (bands > 64) 1.5f else 3f).dp.toPx()
                val bw = (w - gap * (n - 1)) / n
                for (i in 0 until n) {
                    val v = smooth[i]
                    val bh = (0.02f + v * 0.98f) * h * 0.9f
                    drawRoundRect(
                        white.copy(alpha = 0.35f + 0.6f * v),
                        topLeft = Offset(i * (bw + gap), (h - bh) / 2f),
                        size = Size(bw, bh),
                        cornerRadius = CornerRadius(bw / 2f)
                    )
                }
            }
            VizPreset.SPECTRUM -> {
                val n = bands
                val gap = (if (bands > 64) 1f else 2f).dp.toPx()
                val bw = (w - gap * (n - 1)) / n
                for (i in 0 until n) {
                    val v = smooth[i]
                    val bh = (0.02f + v * 0.98f) * h
                    drawRect(
                        if (v > 0.8f) accent else white.copy(alpha = 0.5f + 0.5f * v),
                        topLeft = Offset(i * (bw + gap), h - bh),
                        size = Size(bw, bh)
                    )
                }
            }
            VizPreset.WAVE, VizPreset.MIRROR_WAVE -> {
                val n = 1024
                VisualizerBus.latest(n, wave)
                val path = Path()
                val mid = h / 2f
                for (i in 0 until n) {
                    val x = w * i / (n - 1)
                    val y = mid - wave[i] * h * 0.45f
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, white.copy(alpha = 0.9f), style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
                if (preset == VizPreset.MIRROR_WAVE) {
                    val p2 = Path()
                    for (i in 0 until n) {
                        val x = w * i / (n - 1)
                        val y = mid + wave[i] * h * 0.45f
                        if (i == 0) p2.moveTo(x, y) else p2.lineTo(x, y)
                    }
                    drawPath(p2, accent.copy(alpha = 0.7f), style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
                }
            }
            VizPreset.CIRCLE -> {
                val c = Offset(w / 2f, h / 2f)
                val r = min(w, h) * 0.22f
                val maxLen = min(w, h) * 0.26f
                val stroke = 3.dp.toPx()
                for (i in 0 until bands * 2) {
                    val v = smooth[if (i < bands) i else bands * 2 - 1 - i]
                    val a = 2 * PI * i / (bands * 2) - PI / 2
                    val start = Offset(c.x + (r * cos(a)).toFloat(), c.y + (r * sin(a)).toFloat())
                    val len = r + 4f + v * maxLen
                    val end = Offset(c.x + (len * cos(a)).toFloat(), c.y + (len * sin(a)).toFloat())
                    drawLine(if (v > 0.8f) accent else white.copy(alpha = 0.4f + 0.6f * v), start, end, strokeWidth = stroke, cap = StrokeCap.Round)
                }
                drawCircle(white.copy(alpha = 0.25f), r - 6f, c, style = Stroke(1.5.dp.toPx()))
            }
        }
    }
}


/**
 * Spectrum behind the Equalizer sliders (Settings → Visualization → Equalizer Screen Spectrum).
 * [rounded] draws soft round bars, otherwise classic flat bars.
 */
@Composable
fun EqSpectrum(rounded: Boolean, color: Color, modifier: Modifier = Modifier) {
    DisposableEffect(Unit) {
        VisualizerBus.active = true
        onDispose { VisualizerBus.active = false }
    }
    var frame by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) withFrameMillis { t -> if (t - last >= 32) { frame = t; last = t } }
    }
    val bands = 32
    val spec = remember { FloatArray(bands) }
    val smooth = remember { FloatArray(bands) }
    val work = remember { FloatArray(1024) }
    val re = remember { FloatArray(1024) }
    val im = remember { FloatArray(1024) }
    Canvas(modifier) {
        if (frame < 0L) return@Canvas
        VisualizerBus.spectrum(bands, spec, work, re, im)
        for (i in 0 until bands) smooth[i] = maxOf(spec[i], smooth[i] * 0.88f)
        val w = size.width
        val h = size.height
        val gap = 3.dp.toPx()
        val bw = (w - gap * (bands - 1)) / bands
        for (i in 0 until bands) {
            val bh = smooth[i] * h * 0.95f
            if (bh < 1f) continue
            if (rounded) {
                drawRoundRect(
                    color.copy(alpha = 0.18f + 0.22f * smooth[i]),
                    topLeft = Offset(i * (bw + gap), h - bh),
                    size = Size(bw, bh),
                    cornerRadius = CornerRadius(bw / 2f)
                )
            } else {
                drawRect(color.copy(alpha = 0.22f), topLeft = Offset(i * (bw + gap), h - bh), size = Size(bw, bh))
            }
        }
    }
}
