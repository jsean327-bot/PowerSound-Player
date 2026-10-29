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
}

/** Live visualization of what is playing, redrawn every frame. */
@Composable
fun VisualizerView(preset: VizPreset, accent: Color, modifier: Modifier = Modifier) {
    DisposableEffect(Unit) {
        VisualizerBus.active = true
        onDispose { VisualizerBus.active = false }
    }
    var frame by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) withFrameMillis { frame = it }
    }
    val bands = 64
    val spec = remember { FloatArray(bands) }
    val smooth = remember { FloatArray(bands) }
    val work = remember { FloatArray(1024) }
    val re = remember { FloatArray(1024) }
    val im = remember { FloatArray(1024) }
    val wave = remember { FloatArray(1024) }

    Canvas(modifier) {
        if (frame < 0L) return@Canvas // reading the frame clock redraws every frame
        VisualizerBus.spectrum(bands, spec, work, re, im)
        for (i in 0 until bands) smooth[i] = maxOf(spec[i], smooth[i] * 0.86f)
        val w = size.width
        val h = size.height
        val white = Color.White

        when (preset) {
            VizPreset.SPECTRUM_CENTERED -> {
                val n = bands
                val gap = 3.dp.toPx()
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
                val gap = 2.dp.toPx()
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
