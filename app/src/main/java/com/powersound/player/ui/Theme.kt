package com.powersound.player.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Accent = Color(0xFFFF8A1F)
val AccentLight = Color(0xFFFFC46B)
private val Background = Color(0xFF0B0B10)
private val Surface = Color(0xFF14141C)
private val SurfaceHigh = Color(0xFF1E1E29)

private val PowerColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF1A0E00),
    secondary = AccentLight,
    onSecondary = Color(0xFF1A0E00),
    background = Background,
    onBackground = Color(0xFFF1F1F5),
    surface = Surface,
    onSurface = Color(0xFFF1F1F5),
    surfaceVariant = SurfaceHigh,
    onSurfaceVariant = Color(0xFFA9A9B8),
    outline = Color(0xFF3A3A48),
    surfaceContainer = Surface,
    surfaceContainerHigh = SurfaceHigh
)

@Composable
fun PowerSoundTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = PowerColors, content = content)
}
