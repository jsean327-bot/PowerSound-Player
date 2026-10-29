package com.powersound.player.ui

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.powersound.player.data.AppSettings
import com.powersound.player.data.SettingsStore

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

/** Accent colours offered in Settings → Look and Feel → Skin. */
val ACCENTS = listOf(
    "Orange" to Color(0xFFFF8A1F),
    "Blue" to Color(0xFF4F9DFF),
    "Red" to Color(0xFFFF4D5E),
    "Green" to Color(0xFF3DDC84),
    "Purple" to Color(0xFFB07CFF),
    "Pink" to Color(0xFFFF6FB1),
    "Gold" to Color(0xFFE8C14A),
    "White" to Color(0xFFEDEDF2)
)

/** Built-in skins (Settings → Look and Feel → Skin). */
val SKINS = listOf(
    "PowerSound Dark" to "Dark blue-black skin with the blurred album art behind every screen",
    "PowerSound Black" to "Pure black (AMOLED) skin: saves battery on OLED screens like the Galaxy A55"
)

/** "Default / Alternative font / Bold / Bold+" → font family. */
fun fontFamilyFor(index: Int): FontFamily =
    if (index == 1) FontFamily(android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.NORMAL))
    else FontFamily.Default

fun fontWeightFor(index: Int, base: FontWeight = FontWeight.Normal): FontWeight = when (index) {
    2 -> FontWeight((base.weight + 100).coerceAtMost(900))
    3 -> FontWeight((base.weight + 200).coerceAtMost(900))
    else -> base
}

/** Settings → Look and Feel → Animations: 0 disabled, 1 fast, 2 default. */
fun animMs(ms: Int): Int = when (SettingsStore.value.animations) {
    0 -> 0
    1 -> ms / 2
    else -> ms
}

/** Settings → Skin → Rounded Corners. */
fun cornerDp(dp: Int): Int = if (SettingsStore.value.moreRounded) dp else (dp * 0.45f).toInt()

@Composable
fun PowerSoundTheme(settings: AppSettings = SettingsStore.value, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dynamic = remember(settings.materialYou) {
        if (settings.materialYou != 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) dynamicDarkColorScheme(context) else null
    }
    val accent = dynamic?.primary ?: (ACCENTS.getOrNull(settings.accent)?.second ?: Accent)
    var scheme = PowerColors.copy(primary = accent, secondary = accent)
    val night = androidx.compose.foundation.isSystemInDarkTheme()
    val skin = if (settings.followDayNight) (if (night) 1 else 0) else settings.skin
    if (skin == 1) {
        scheme = scheme.copy(background = Color.Black, surface = Color(0xFF0A0A0C), surfaceContainer = Color(0xFF0A0A0C), surfaceVariant = Color(0xFF141418))
    }
    if (dynamic != null && settings.materialYou == 1) {
        // "More pronounced": the wallpaper colours also tint the backgrounds
        scheme = scheme.copy(
            background = lerp(scheme.background, dynamic.primary, 0.08f),
            surface = lerp(scheme.surface, dynamic.primary, 0.10f),
            surfaceVariant = lerp(scheme.surfaceVariant, dynamic.primary, 0.14f),
            surfaceContainer = lerp(scheme.surface, dynamic.primary, 0.10f)
        )
    }
    val family = fontFamilyFor(settings.appFont)
    val base = Typography()
    val typography = remember(settings.appFont) {
        fun TextStyle.f() = copy(fontFamily = family, fontWeight = fontWeightFor(settings.appFont, fontWeight ?: FontWeight.Normal))
        base.copy(
            bodyLarge = base.bodyLarge.f(), bodyMedium = base.bodyMedium.f(), bodySmall = base.bodySmall.f(),
            titleLarge = base.titleLarge.f(), titleMedium = base.titleMedium.f(), titleSmall = base.titleSmall.f(),
            labelLarge = base.labelLarge.f(), labelMedium = base.labelMedium.f(), labelSmall = base.labelSmall.f(),
            headlineSmall = base.headlineSmall.f(), headlineMedium = base.headlineMedium.f()
        )
    }
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
