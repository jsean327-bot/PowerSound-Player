package com.powersound.player.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.powersound.player.data.AppSettings
import com.powersound.player.data.ArtworkLoader
import com.powersound.player.data.Category
import com.powersound.player.data.CrashLog
import com.powersound.player.data.LYRICS_APPS
import com.powersound.player.data.LauncherIcons
import com.powersound.player.data.LyricsReader
import com.powersound.player.data.MENU_LONG_PRESS
import com.powersound.player.data.NOTIF_ACTIONS
import com.powersound.player.data.SettingsStore
import com.powersound.player.data.Updater
import kotlin.math.roundToInt

/** Sections of the Settings screen. Pages with a [parent] are opened from inside another page. */
enum class SettingsPage(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val color: Color,
    val parent: SettingsPage? = null
) {
    LOOK("Look and Feel", "Skin, player interface, language, notifications", Icons.Rounded.Palette, Color(0xFF8E8CF0)),
    AUDIO("Audio", "ReplayGain, fade on pause, equalizer", Icons.Rounded.VolumeUp, Color(0xFFE0708A)),
    VISUALIZATION("Visualization", "Faded controls opacity, preset duration", Icons.Rounded.GraphicEq, Color(0xFFB07CFF)),
    BACKGROUND("Background", "Blur, intensity, saturation", Icons.Rounded.Wallpaper, Color(0xFF4FB6A0)),
    ALBUM_ART("Album Art", "Quality, cache cleanup", Icons.Rounded.Album, Color(0xFF6FBF73)),
    LIBRARY("Library", "Rescan, music folders, categories", Icons.Rounded.LibraryMusic, Color(0xFF4F8DF7)),
    HEADSET("Headset/Bluetooth", "Pause/resume on connection", Icons.Rounded.Headphones, Color(0xFFC9C9D6)),
    LOCK_SCREEN("Lock Screen", "Media controls on the lock screen", Icons.Rounded.Lock, Color(0xFFE59A4F)),
    MISC("Misc", "Remember position, swipe away, error log", Icons.Rounded.MoreHoriz, Color(0xFF4FB6C9)),
    ABOUT("About", "Version, changelog, updates", Icons.Rounded.Info, Color(0xFF9A9AB0)),

    // ---- inside Look and Feel ----
    SKIN("Skin", "Visual theme and per-skin options", Icons.Rounded.Palette, Color(0xFF8E8CF0), LOOK),
    PLAYER_SCREEN("Player Screen", "Buttons, labels, rating", Icons.Rounded.Palette, Color(0xFF8E8CF0), LOOK),
    LYRICS("Lyrics", "Swipe, button, size, offset", Icons.Rounded.Palette, Color(0xFF8E8CF0), LOOK),
    NOTIFICATIONS("Notifications", "Buttons, keeping", Icons.Rounded.Palette, Color(0xFF8E8CF0), LOOK),
    NOTIF_BUTTONS("Buttons", "Media notification buttons", Icons.Rounded.Palette, Color(0xFF8E8CF0), NOTIFICATIONS),
    ICON("Icon", "Launcher icon", Icons.Rounded.Palette, Color(0xFF8E8CF0), LOOK),
    LIST_UI("List UI", "Filename as title, track numbers", Icons.Rounded.Palette, Color(0xFF8E8CF0), LOOK)
}

/** Everything searchable: (page, item title). */
private val SEARCH_INDEX: List<Pair<SettingsPage, String>> = listOf(
    SettingsPage.LOOK to "Skin", SettingsPage.LOOK to "Follow Day/Night Mode", SettingsPage.LOOK to "Settings Theme",
    SettingsPage.LOOK to "Settings Font", SettingsPage.LOOK to "Language", SettingsPage.LOOK to "Icon",
    SettingsPage.LOOK to "Screen Orientation", SettingsPage.LOOK to "Animations", SettingsPage.LOOK to "Start at Library",
    SettingsPage.LOOK to "Hide Status Bar", SettingsPage.LOOK to "Keep Screen On", SettingsPage.LOOK to "Settings Shortcuts in Main Menu",
    SettingsPage.LOOK to "Show tips again",
    SettingsPage.SKIN to "Accent colour", SettingsPage.SKIN to "Layout", SettingsPage.SKIN to "Track Labels", SettingsPage.SKIN to "Labels Background",
    SettingsPage.SKIN to "Player Buttons Background", SettingsPage.SKIN to "Material You colors", SettingsPage.SKIN to "Font",
    SettingsPage.SKIN to "Rounded Corners", SettingsPage.SKIN to "Seekbar Style", SettingsPage.SKIN to "Pro Buttons",
    SettingsPage.SKIN to "Knob Highlight", SettingsPage.SKIN to "Eq. Graphic Mode Curve", SettingsPage.SKIN to "Transparent Status Bar",
    SettingsPage.SKIN to "Transparent Navbar", SettingsPage.SKIN to "Offset Navbar", SettingsPage.SKIN to "Android Navigation Bar",
    SettingsPage.PLAYER_SCREEN to "Album Art Animation", SettingsPage.PLAYER_SCREEN to "Animate Long Labels",
    SettingsPage.PLAYER_SCREEN to "Animate Long Labels Everywhere", SettingsPage.PLAYER_SCREEN to "Edit Player Screen Buttons",
    SettingsPage.PLAYER_SCREEN to "Prefer No Gap", SettingsPage.PLAYER_SCREEN to "Do Not Edit On Long Press",
    SettingsPage.PLAYER_SCREEN to "Restore Player Screen Buttons", SettingsPage.PLAYER_SCREEN to "Rating Type",
    SettingsPage.PLAYER_SCREEN to "Menu Button", SettingsPage.PLAYER_SCREEN to "Menu Button Long Press", SettingsPage.PLAYER_SCREEN to "Lyrics Button",
    SettingsPage.PLAYER_SCREEN to "Line2 Press", SettingsPage.PLAYER_SCREEN to "Line2 Long Press",
    SettingsPage.PLAYER_SCREEN to "<< Button Resets Current Track", SettingsPage.PLAYER_SCREEN to "-10/+10s Rewind Pro Buttons",
    SettingsPage.PLAYER_SCREEN to "Navigate to the Folders",
    SettingsPage.LYRICS to "Lyrics Swipe Up", SettingsPage.LYRICS to "Lyrics Item In The Track Menu", SettingsPage.LYRICS to "Keep Screen On (lyrics)",
    SettingsPage.LYRICS to "Lyrics Time Offset", SettingsPage.LYRICS to "Lyrics Size", SettingsPage.LYRICS to "Preferred Lyrics App",
    SettingsPage.LYRICS to "Custom URL", SettingsPage.LYRICS to "Rescan Lyrics Tags", SettingsPage.LYRICS to "Scan LRC Files",
    SettingsPage.LYRICS to "Always Use UTF-8",
    SettingsPage.NOTIFICATIONS to "Navigate to the List", SettingsPage.NOTIFICATIONS to "Keep Notification",
    SettingsPage.NOTIFICATIONS to "Remove Notification on Disconnection", SettingsPage.NOTIF_BUTTONS to "Notification buttons",
    SettingsPage.LIST_UI to "Filename As Title", SettingsPage.LIST_UI to "Show Track Number",
    SettingsPage.AUDIO to "ReplayGain", SettingsPage.AUDIO to "ReplayGain preamp", SettingsPage.AUDIO to "Files without ReplayGain",
    SettingsPage.AUDIO to "Fade on pause / resume", SettingsPage.AUDIO to "Fade length", SettingsPage.AUDIO to "Equalizer",
    SettingsPage.VISUALIZATION to "Faded controls opacity", SettingsPage.VISUALIZATION to "Change preset automatically",
    SettingsPage.BACKGROUND to "Blurred album art background", SettingsPage.BACKGROUND to "Blur", SettingsPage.BACKGROUND to "Intensity",
    SettingsPage.BACKGROUND to "Saturation",
    SettingsPage.ALBUM_ART to "High quality covers", SettingsPage.ALBUM_ART to "Clear album art cache",
    SettingsPage.LIBRARY to "Rescan library", SettingsPage.LIBRARY to "Music folders", SettingsPage.LIBRARY to "Skip short files",
    SettingsPage.LIBRARY to "Long songs", SettingsPage.LIBRARY to "Library categories",
    SettingsPage.HEADSET to "Pause when headphones disconnect", SettingsPage.HEADSET to "Resume on Bluetooth connection",
    SettingsPage.HEADSET to "Resume on wired headphones",
    SettingsPage.LOCK_SCREEN to "Lock screen controls",
    SettingsPage.MISC to "Remember position in long songs", SettingsPage.MISC to "Stop music when the app is swiped away",
    SettingsPage.MISC to "Ask before deleting files", SettingsPage.MISC to "Keep an error log", SettingsPage.MISC to "View error log",
    SettingsPage.ABOUT to "Check for updates", SettingsPage.ABOUT to "Changelog", SettingsPage.ABOUT to "Version"
)

// ---------------------------------------------------------------- colours of the Settings screen (Settings Theme)

private data class SettingsColors(
    val bg: Color,
    val text: Color,
    val sub: Color,
    val line: Color,
    val chipBorder: Color,
    val family: FontFamily,
    val font: Int
) {
    val titleWeight: FontWeight get() = fontWeightFor(font, FontWeight.Normal)
    val bodyWeight: FontWeight get() = fontWeightFor(font, FontWeight.Normal)
}

private val LocalSc = staticCompositionLocalOf {
    SettingsColors(Color.Black, Color.White, Color.White.copy(alpha = 0.55f), Color.White.copy(alpha = 0.08f), Color.White.copy(alpha = 0.25f), FontFamily.Default, 0)
}

@Composable
private fun settingsColors(st: AppSettings): SettingsColors {
    val light = when (st.settingsTheme) {
        1 -> true
        3 -> !isSystemInDarkTheme()
        else -> false
    }
    val family = fontFamilyFor(st.settingsFont)
    return if (light) SettingsColors(
        bg = Color(0xFFF7F7FA), text = Color(0xFF17171D), sub = Color(0xFF5E5E6A),
        line = Color(0xFFE2E2E8), chipBorder = Color(0xFFB4B4C0), family = family, font = st.settingsFont
    ) else SettingsColors(
        bg = MaterialTheme.colorScheme.background, text = Color.White, sub = Color.White.copy(alpha = 0.55f),
        line = Color.White.copy(alpha = 0.08f), chipBorder = Color.White.copy(alpha = 0.25f), family = family, font = st.settingsFont
    )
}

@Composable
fun SettingsScreen(
    page: SettingsPage?,
    allFolders: List<String>,
    onPage: (SettingsPage?) -> Unit,
    onClose: () -> Unit,
    onRescan: () -> Unit,
    onOpenEqualizer: () -> Unit,
    onCheckUpdate: () -> Unit,
    onChangelog: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit
) {
    val st by SettingsStore.settings.collectAsState()
    val sc = settingsColors(st)
    // light Settings Theme: dark status bar icons while Settings is open
    val view = androidx.compose.ui.platform.LocalView.current
    val light = sc.bg.luminance() > 0.5f
    androidx.compose.runtime.DisposableEffect(light) {
        val window = (view.context as? android.app.Activity)?.window
        val controller = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, view) }
        controller?.isAppearanceLightStatusBars = light
        controller?.isAppearanceLightNavigationBars = light
        onDispose {
            controller?.isAppearanceLightStatusBars = false
            controller?.isAppearanceLightNavigationBars = false
        }
    }
    CompositionLocalProvider(LocalSc provides sc) {
        SettingsContent(st, page, allFolders, onPage, onClose, onRescan, onOpenEqualizer, onCheckUpdate, onChangelog, onExport, onImport)
    }
}

@Composable
private fun SettingsContent(
    st: AppSettings,
    page: SettingsPage?,
    allFolders: List<String>,
    onPage: (SettingsPage?) -> Unit,
    onClose: () -> Unit,
    onRescan: () -> Unit,
    onOpenEqualizer: () -> Unit,
    onCheckUpdate: () -> Unit,
    onChangelog: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit
) {
    val sc = LocalSc.current
    val context = LocalContext.current
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    fun set(change: (AppSettings) -> AppSettings) = SettingsStore.update(change)
    val d = remember { AppSettings() }

    Column(
        Modifier
            .fillMaxSize()
            .background(sc.bg)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // ---- top bar: ← title 🔍 ✕ ----
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (searching) { searching = false; query = "" } else if (page != null) onPage(page.parent) else onClose() }) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = sc.text)
            }
            if (searching) {
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(color = sc.text, fontSize = 18.sp, fontFamily = sc.family),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner ->
                        Box {
                            if (query.isEmpty()) Text("Search settings", color = sc.sub, fontSize = 18.sp)
                            inner()
                        }
                    }
                )
            } else {
                Text(
                    page?.title ?: "Settings", color = sc.text, fontSize = 20.sp, fontFamily = sc.family,
                    fontWeight = fontWeightFor(sc.font, FontWeight.Medium), modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { searching = true }) { Icon(Icons.Rounded.Search, contentDescription = "Search", tint = sc.text) }
            }
            IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, contentDescription = "Close", tint = sc.text) }
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp)
        ) {
            when {
                searching -> {
                    val q = query.trim().lowercase()
                    val hits = if (q.isEmpty()) emptyList() else SEARCH_INDEX.filter { (p, t) ->
                        t.lowercase().contains(q) || p.title.lowercase().contains(q)
                    }
                    hits.forEach { (p, t) ->
                        val path = generateSequence(p) { it.parent }.toList().reversed().joinToString(" › ") { it.title }
                        NavRow(p.icon, p.color, t, path) { searching = false; query = ""; onPage(p) }
                    }
                    if (q.isNotEmpty() && hits.isEmpty()) Hint("Nothing found")
                }

                page == null -> {
                    SettingsPage.values().filter { it.parent == null }.forEach { p -> NavRow(p.icon, p.color, p.title, p.subtitle) { onPage(p) } }
                    SectionLabel("Other")
                    NavRow(Icons.Rounded.Upload, Color(0xFF9A9AB0), "Export Settings/Data", "Save EQ, presets, playlists, bookmarks and settings to a file", onExport)
                    NavRow(Icons.Rounded.Download, Color(0xFF9A9AB0), "Import Settings/Data", "Restore from a file saved with Export", onImport)
                }

                // ============================================================ LOOK AND FEEL
                page == SettingsPage.LOOK -> {
                    PlainRow("Skin", "Change the PowerSound visual theme. Per-skin options like extra Pro Buttons, Static Seekbar") { onPage(SettingsPage.SKIN) }
                    SwitchRow("Follow Day/Night Mode", "Follow the system Day/Night Mode: PowerSound Dark by day, PowerSound Black at night", st.followDayNight) { v ->
                        set { it.copy(followDayNight = v) }
                    }
                    ChipRow("Settings Theme", null, listOf("Default", "Light", "Dark", "Follow Day/Night Mode"), st.settingsTheme) { v -> set { it.copy(settingsTheme = v) } }
                    ChipRow("Settings Font", null, listOf("Default", "Alternative font", "Bold", "Bold+"), st.settingsFont) { v -> set { it.copy(settingsFont = v) } }

                    SectionLabel("General")
                    PlainRow("Player Screen") { onPage(SettingsPage.PLAYER_SCREEN) }
                    PlainRow("Lyrics") { onPage(SettingsPage.LYRICS) }
                    PlainRow("Notifications") { onPage(SettingsPage.NOTIFICATIONS) }
                    LinkHint("Press here for the additional List UI options like Filename As Title, Show Track Number, etc.") { onPage(SettingsPage.LIST_UI) }

                    SectionLabel("Misc")
                    ChoiceRow("Language", listOf("Auto", "English"), st.language) { v -> set { it.copy(language = v) } }
                    PlainRow("Icon", "Set launcher icon") { onPage(SettingsPage.ICON) }
                    ChipRow("Screen Orientation", null, listOf("Default", "Portrait (Vertical)", "Landscape (Horizontal)"), st.orientation) { v ->
                        set { it.copy(orientation = v) }
                    }
                    ChipRow("Animations", "Disable/enable or change UI animation speed where possible", listOf("Disabled", "Fast", "Default"), st.animations) { v ->
                        set { it.copy(animations = v) }
                    }
                    SwitchRow("Start at Library", "Changes the startup screen. Back action returns from the Player Screen to the current list", st.startAtLibrary) { v ->
                        set { it.copy(startAtLibrary = v) }
                    }
                    SwitchRow("Hide Status Bar", null, st.hideStatusBar) { v -> set { it.copy(hideStatusBar = v) } }
                    NoteBlock("Some phones can't change the status bar / navigation bar right away. You may need to leave and enter the app once for these options to apply")
                    SwitchRow("Keep Screen On", "Always keep the screen on in the app", st.keepScreenOn) { v -> set { it.copy(keepScreenOn = v) } }
                    StepSliderRow("Settings Shortcuts in Main Menu", if (st.menuShortcuts == 0) "Disabled" else "${st.menuShortcuts}", st.menuShortcuts, 0, MENU_SHORTCUT_MAX, "Disabled", "Max") { v ->
                        set { it.copy(menuShortcuts = v) }
                    }
                    ActionRow("Show tips again", "The first-time tips appear again on each screen") { HintStore.resetAll() }
                    RestoreRow {
                        set {
                            it.copy(
                                followDayNight = d.followDayNight, settingsTheme = d.settingsTheme, settingsFont = d.settingsFont,
                                language = d.language, orientation = d.orientation, animations = d.animations, startAtLibrary = d.startAtLibrary,
                                hideStatusBar = d.hideStatusBar, keepScreenOn = d.keepScreenOn, menuShortcuts = d.menuShortcuts
                            )
                        }
                    }
                }

                page == SettingsPage.SKIN -> {
                    SectionLabel("Skins")
                    SKINS.forEachIndexed { i, (name, desc) ->
                        RadioCard(name, desc, st.skin == i && !st.followDayNight) { set { it.copy(skin = i, followDayNight = false) } }
                    }
                    if (st.followDayNight) NoteBlock("Follow Day/Night Mode is on (Look and Feel), so the skin changes with the system")
                    SectionLabel("Accent colour")
                    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ACCENTS.forEachIndexed { i, (name, color) ->
                            Box(
                                Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(color)
                                    .border(2.dp, if (st.accent == i) sc.text else Color.Transparent, CircleShape)
                                    .clickable { set { it.copy(accent = i) } },
                                contentAlignment = Alignment.Center
                            ) {
                                if (st.accent == i) Icon(Icons.Rounded.Check, contentDescription = name, tint = Color.Black, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                    if (st.materialYou != 0) NoteBlock("Material You colors are on, so the accent colour comes from your wallpaper")
                    Divider()
                    Title("Layout")
                    RadioCard("Default", "Track labels and buttons on the cover. Best for small or wide screens", st.layout == 0) { set { it.copy(layout = 0) } }
                    RadioCard("Alternative layout", "Moves track labels below the cover, makes on-cover buttons smaller and faded", st.layout == 1) { set { it.copy(layout = 1) } }
                    RadioCard("Full cover", "Moves all track labels and buttons below the cover. Works best on long screen phones", st.layout == 2) { set { it.copy(layout = 2) } }
                    LinkHint("Use Player Screen and Lyrics options to hide/show the menu/lyrics buttons and rating") { onPage(SettingsPage.PLAYER_SCREEN) }
                    Divider()
                    ChipRow("Track Labels", null, listOf("Default", "Centered"), if (st.labelsCentered) 1 else 0) { v -> set { it.copy(labelsCentered = v == 1) } }
                    Hint("Centered: applied for Alternative layout / Full cover")
                    ChipRow("Labels Background", null, listOf("Enabled", "Hidden"), if (st.labelsBg) 0 else 1) { v -> set { it.copy(labelsBg = v == 0) } }
                    Hint("Hidden: applied on the Player Screen. You may want to adjust Background so labels are always visible")
                    ChipRow("Player Buttons Background", null, listOf("Enabled", "Hidden"), if (st.buttonsBg) 0 else 1) { v -> set { it.copy(buttonsBg = v == 0) } }
                    ChipRow("Material You colors", if (Build.VERSION.SDK_INT < 31) "Needs Android 12 or newer" else null, listOf("Disabled", "More pronounced", "Less pronounced"), st.materialYou) { v ->
                        set { it.copy(materialYou = v) }
                    }
                    ChipRow("Font", null, listOf("Default", "Alternative font", "Bold", "Bold+"), st.appFont) { v -> set { it.copy(appFont = v) } }
                    ChipRow("Rounded Corners", null, listOf("More rounded", "Less rounded"), if (st.moreRounded) 0 else 1) { v -> set { it.copy(moreRounded = v == 0) } }
                    ChipRow("Seekbar Style", null, listOf("Default", "Static Seekbar", "Simple Seekbar (with Pro Buttons)"), st.seekbarStyle) { v ->
                        set { it.copy(seekbarStyle = v) }
                    }
                    SwitchRow("Pro Buttons", "Category change (or -10/+10 s) buttons next to Play. Always enabled for Simple Seekbar", st.proButtons || st.seekbarStyle == 2) { v ->
                        set { it.copy(proButtons = v) }
                    }
                    ChipRow("Knob Highlight", null, listOf("None", "Monochromatic", "Colorful"), st.knobHighlight) { v -> set { it.copy(knobHighlight = v) } }
                    ChipRow("Eq. Graphic Mode Curve", null, listOf("Monochromatic", "Colorful"), if (st.eqCurveColorful) 1 else 0) { v -> set { it.copy(eqCurveColorful = v == 1) } }
                    ChipRow("Transparent Status Bar", null, listOf("Default", "No Background"), if (st.statusBarNoBg) 1 else 0) { v -> set { it.copy(statusBarNoBg = v == 1) } }
                    Hint("Some covers may make the status bar icons hard to read. In this case you can adjust the Background options")
                    ChipRow("Transparent Navbar", "Applied to the floating bar at the bottom", listOf("Default", "Semi-transparent", "No Background"), st.navbarStyle) { v ->
                        set { it.copy(navbarStyle = v) }
                    }
                    SwitchRow("Offset Navbar", "Move the bar slightly away from the corners (for screens with large corner radius)", st.offsetNavbar) { v ->
                        set { it.copy(offsetNavbar = v) }
                    }
                    ChipRow("Android Navigation Bar", null, listOf("Default", "No Background"), if (st.androidNavNoBg) 1 else 0) { v -> set { it.copy(androidNavNoBg = v == 1) } }
                    RestoreRow {
                        set {
                            it.copy(
                                skin = d.skin, accent = d.accent, layout = d.layout, labelsCentered = d.labelsCentered, labelsBg = d.labelsBg,
                                buttonsBg = d.buttonsBg, materialYou = d.materialYou, appFont = d.appFont, moreRounded = d.moreRounded,
                                seekbarStyle = d.seekbarStyle, proButtons = d.proButtons, knobHighlight = d.knobHighlight,
                                eqCurveColorful = d.eqCurveColorful, statusBarNoBg = d.statusBarNoBg, navbarStyle = d.navbarStyle,
                                offsetNavbar = d.offsetNavbar, androidNavNoBg = d.androidNavNoBg
                            )
                        }
                    }
                }

                page == SettingsPage.PLAYER_SCREEN -> {
                    var editButtons by remember { mutableStateOf(false) }
                    LinkHint("Press here for the additional Skin options like Static Seekbar, Pro Buttons") { onPage(SettingsPage.SKIN) }
                    SwitchRow("Album Art Animation", "Animate on track advance. Also applies to the bottom bar", st.artAnimation) { v -> set { it.copy(artAnimation = v) } }
                    SwitchRow("Animate Long Labels", "Scroll long track title and album/artist labels", st.marquee) { v -> set { it.copy(marquee = v) } }
                    SwitchRow("Animate Long Labels Everywhere", "Scroll long track labels also in lists and the mini player", st.marqueeEverywhere) { v ->
                        set { it.copy(marqueeEverywhere = v) }
                    }
                    SectionLabel("Player Screen Buttons")
                    NoteBlock(
                        "Player screen buttons appear as a row below the track cover",
                        "To customize, long-press the empty space between the buttons or use the item below"
                    )
                    PlainRow("Edit Player Screen Buttons", st.playerButtons.mapNotNull { id -> PLAYER_BUTTONS.firstOrNull { it.id == id }?.label }.joinToString(", ").ifEmpty { "No buttons" }) {
                        editButtons = true
                    }
                    SwitchRow("Prefer No Gap", "Minimize gaps between buttons for the centered layout", st.preferNoGap) { v -> set { it.copy(preferNoGap = v) } }
                    SwitchRow("Do Not Edit On Long Press", "Prevents button editing started by an accidental long press", st.noEditLongPress) { v ->
                        set { it.copy(noEditLongPress = v) }
                    }
                    PlainRow("Restore Player Screen Buttons") { set { it.copy(playerButtons = d.playerButtons) } }

                    SectionLabel("Other Buttons")
                    ChipRow("Rating Type", null, listOf("Disabled", "Like/unlike thumbs", "5 stars", "5 stars (menu/lists only)"), st.ratingType) { v ->
                        set { it.copy(ratingType = v) }
                    }
                    Hint("Stars: tap a star to rate, tap the same star again to clear the rating")
                    ChipRow("Menu Button", "The menu can also be opened by long pressing the cover", listOf("Disabled", "Enabled"), if (st.menuButton) 1 else 0) { v ->
                        set { it.copy(menuButton = v == 1) }
                    }
                    ChipRow("Menu Button Long Press", "Long press action for the track menu button", MENU_LONG_PRESS, st.menuLongPress) { v ->
                        set { it.copy(menuLongPress = v) }
                    }
                    ChipRow("Lyrics Button", "Button on the Player Screen cover", listOf("Disabled", "When local lyrics available", "Always"), st.lyricsButton) { v ->
                        set { it.copy(lyricsButton = v) }
                    }
                    ChipRow("Line2 Press", "Handle press on the 2nd track text line", listOf("Disabled", "Artist", "Album", "Folder", "Genre"), st.line2Press) { v ->
                        set { it.copy(line2Press = v) }
                    }
                    ChipRow("Line2 Long Press", "Handle long press on the 2nd track text line", listOf("Disabled", "Artist", "Album", "Folder", "Genre"), st.line2LongPress) { v ->
                        set { it.copy(line2LongPress = v) }
                    }
                    NoteBlock("A track counter (3/12) is available as a Player Screen button")
                    SwitchRow(
                        "<< Button Resets Current Track",
                        "First press rewinds the track, second press changes the track. Includes headset/Bluetooth buttons and the notification",
                        st.prevResets
                    ) { v -> set { it.copy(prevResets = v) } }
                    Hint("To reset a track to 0:00 on the Player Screen, long press the elapsed time (left of the Play button)")
                    SwitchRow(
                        "-10/+10s Rewind Pro Buttons",
                        "Change the category Pro Buttons to -10/+10 s jump buttons for long tracks (${st.longTrackMin} min or more, see Library → Long songs)",
                        st.proRewindLong
                    ) { v -> set { it.copy(proRewindLong = v) } }
                    SwitchRow("Navigate to the Folders", "Track menu → Folder opens the Folders category instead of the Folders Hierarchy", st.navigateToFolders) { v ->
                        set { it.copy(navigateToFolders = v) }
                    }
                    RestoreRow {
                        set {
                            it.copy(
                                artAnimation = d.artAnimation, marquee = d.marquee, marqueeEverywhere = d.marqueeEverywhere,
                                playerButtons = d.playerButtons, preferNoGap = d.preferNoGap, noEditLongPress = d.noEditLongPress,
                                ratingType = d.ratingType, menuButton = d.menuButton, menuLongPress = d.menuLongPress,
                                lyricsButton = d.lyricsButton, line2Press = d.line2Press, line2LongPress = d.line2LongPress,
                                prevResets = d.prevResets, proRewindLong = d.proRewindLong, navigateToFolders = d.navigateToFolders
                            )
                        }
                    }
                    if (editButtons) EditPlayerButtonsDialog(st.playerButtons, onSave = { list -> set { it.copy(playerButtons = list) } }) { editButtons = false }
                }

                page == SettingsPage.LYRICS -> {
                    var editUrl by remember { mutableStateOf(false) }
                    var rescanned by remember { mutableStateOf(false) }
                    NoteBlock(
                        "When Lyrics Swipe is enabled, swipe the album art up to show lyrics",
                        "Return from lyrics by swiping down or to the side",
                        "Pinch to zoom the lyrics text"
                    )
                    ChipRow("Lyrics Swipe Up", "Swipe action on the Player Screen cover", listOf("Disabled", "When local lyrics available", "Always"), st.lyricsSwipeUp) { v ->
                        set { it.copy(lyricsSwipeUp = v) }
                    }
                    NoteBlock("Local lyrics: the lyrics tag inside the file, or an .lrc file next to it")
                    ChipRow("Lyrics Button", "Button on the Player Screen cover", listOf("Disabled", "When local lyrics available", "Always"), st.lyricsButton) { v ->
                        set { it.copy(lyricsButton = v) }
                    }
                    Hint("Long press on the lyrics button always opens the preferred lyrics app")
                    ChipRow("Lyrics Item In The Track Menu", null, listOf("Open lyrics UI", "Open 3rd party app"), st.lyricsMenuItem) { v ->
                        set { it.copy(lyricsMenuItem = v) }
                    }
                    SwitchRow("Keep Screen On", "Keep the screen on while the lyrics are open", st.lyricsKeepScreenOn) { v -> set { it.copy(lyricsKeepScreenOn = v) } }
                    SliderRow(
                        "Lyrics Time Offset", "${st.lyricsOffsetMs} ms", st.lyricsOffsetMs.toFloat(), -5000f..5000f,
                        subtitle = "Applied to synced lyrics in addition to the offset tag"
                    ) { v -> set { it.copy(lyricsOffsetMs = ((v / 50).roundToInt() * 50)) } }
                    Hint("A positive value makes the lyrics text appear earlier, a negative value delays it")
                    ChipRow("Lyrics Size", "Pinch to zoom on the lyrics screen too", listOf("Small Font", "Default", "Large Font"), st.lyricsSize) { v ->
                        set { it.copy(lyricsSize = v) }
                    }
                    NoteBlock(
                        "PowerSound loads lyrics from the track tags or from the .lrc file",
                        "If no lyrics are found, the lyrics can be searched in the preferred lyrics app or in the browser"
                    )
                    ChoiceRow("Preferred Lyrics App", LYRICS_APPS, st.lyricsApp, subtitlePrefix = "App or site to search lyrics when none are found: ") { v ->
                        set { it.copy(lyricsApp = v) }
                    }
                    ActionRow(
                        "Custom URL", "For the Browser via custom URL\n${st.lyricsCustomUrl}",
                        enabled = st.lyricsApp == 1
                    ) { editUrl = true }
                    SectionLabel("Scanner")
                    ActionRow("Rescan Lyrics Tags", if (rescanned) "Done — lyrics will be read again" else "Read the lyrics of every track again (tags and .lrc files)") {
                        LyricsReader.clearCache(); rescanned = true
                    }
                    val allFiles = Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()
                    SwitchRow(
                        "Scan LRC Files",
                        "If enabled, PowerSound looks for .lrc files in the music folders with the same name as the track" +
                            if (!allFiles) "\nNeeds \"All files access\" — tap to allow" else "",
                        st.scanLrc
                    ) { v ->
                        set { it.copy(scanLrc = v) }
                        LyricsReader.clearCache()
                        if (v && !allFiles && Build.VERSION.SDK_INT >= 30) {
                            runCatching {
                                context.startActivity(
                                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        }
                    }
                    SwitchRow("Always Use UTF-8", "If disabled, .lrc files that are not UTF-8 are read as Arabic (Windows-1256)", st.lyricsUtf8) { v ->
                        set { it.copy(lyricsUtf8 = v) }; LyricsReader.clearCache()
                    }
                    RestoreRow {
                        set {
                            it.copy(
                                lyricsSwipeUp = d.lyricsSwipeUp, lyricsButton = d.lyricsButton, lyricsMenuItem = d.lyricsMenuItem,
                                lyricsKeepScreenOn = d.lyricsKeepScreenOn, lyricsOffsetMs = d.lyricsOffsetMs, lyricsSize = d.lyricsSize,
                                lyricsApp = d.lyricsApp, lyricsCustomUrl = d.lyricsCustomUrl, scanLrc = d.scanLrc, lyricsUtf8 = d.lyricsUtf8
                            )
                        }
                    }
                    if (editUrl) TextInputDialog(
                        "Custom URL", st.lyricsCustomUrl,
                        "Use %ARTIST% and %TITLE% for the track's artist and title",
                        onSave = { url -> set { it.copy(lyricsCustomUrl = url.ifBlank { d.lyricsCustomUrl }) } }
                    ) { editUrl = false }
                }

                page == SettingsPage.NOTIFICATIONS -> {
                    NoteBlock(
                        "Android 13 and newer draw the media notification themselves (colours from the album art, the seek bar and Play/Previous/Next)",
                        "PowerSound adds your chosen extra buttons to it"
                    )
                    PlainRow("Buttons", "Media action buttons") { onPage(SettingsPage.NOTIF_BUTTONS) }
                    SwitchRow("Navigate to the List", "Touching the notification opens the list the track is playing from", st.notifNavigateToList) { v ->
                        set { it.copy(notifNavigateToList = v) }
                    }
                    SectionLabel("Notification Keeping")
                    SwitchRow("Keep Notification", "The notification stays when playback ends by itself", st.keepNotification) { v ->
                        set { it.copy(keepNotification = v) }
                    }
                    NoteBlock(
                        "If disabled, the notification is removed when the list finishes playing",
                        "The notification can always be removed with the Close button or by stopping playback"
                    )
                    SwitchRow(
                        "Remove Notification on Disconnection",
                        "The notification is removed when the headset/Bluetooth disconnects and the player is paused",
                        st.removeNotifOnDisconnect
                    ) { v -> set { it.copy(removeNotifOnDisconnect = v) } }
                    RestoreRow {
                        set {
                            it.copy(
                                notifButtons = d.notifButtons, notifNavigateToList = d.notifNavigateToList,
                                keepNotification = d.keepNotification, removeNotifOnDisconnect = d.removeNotifOnDisconnect
                            )
                        }
                    }
                }

                page == SettingsPage.NOTIF_BUTTONS -> {
                    NoteBlock(
                        "The media buttons are shared by all media controllers: the Android media notification, the lock screen, Android Auto, watches, etc.",
                        "The playback buttons (play/pause/previous/next) are fixed and can't be changed",
                        "The first two buttons appear in the media notification; the others usually only in other controllers (Android Auto)"
                    )
                    SectionLabel("Notification/Android Auto")
                    for (i in 0 until 6) {
                        if (i == 2) SectionLabel("Android Auto/Other")
                        ChipRow(
                            "Button ${i + 1}", if (i == 1) "\"Close\" pauses the player and removes the notification" else null,
                            NOTIF_ACTIONS, st.notifButtons.getOrElse(i) { 0 }
                        ) { v -> set { s -> s.copy(notifButtons = s.notifButtons.toMutableList().also { it[i] = v }) } }
                    }
                    RestoreRow { set { it.copy(notifButtons = d.notifButtons) } }
                }

                page == SettingsPage.ICON -> {
                    NoteBlock(
                        "The icon may look different depending on your Android version, launcher and system settings",
                        "It may take a few seconds for the launcher to update the icon"
                    )
                    SectionLabel("Icons")
                    LauncherIcons.ALL.forEachIndexed { i, icon ->
                        val bmp = remember(icon.res) {
                            runCatching {
                                androidx.core.content.res.ResourcesCompat.getDrawable(context.resources, icon.res, context.theme)
                                    ?.toBitmap(144, 144)?.asImageBitmap()
                            }.getOrNull()
                        }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    set { it.copy(launcherIcon = i) }
                                    LauncherIcons.apply(context, i)
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = st.launcherIcon == i,
                                onClick = { set { it.copy(launcherIcon = i) }; LauncherIcons.apply(context, i) },
                                colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary, unselectedColor = sc.sub)
                            )
                            if (bmp != null) Image(bmp, contentDescription = null, modifier = Modifier.size(48.dp).clip(CircleShape))
                            Spacer(Modifier.width(14.dp))
                            Column {
                                Text(icon.title, color = sc.text, fontSize = 16.sp, fontFamily = sc.family, fontWeight = sc.titleWeight)
                                Text(icon.subtitle, color = sc.sub, fontSize = 12.sp, fontFamily = sc.family)
                            }
                        }
                    }
                }

                page == SettingsPage.LIST_UI -> {
                    SwitchRow("Filename As Title", "Show the file name instead of the title tag in the lists", st.filenameAsTitle) { v -> set { it.copy(filenameAsTitle = v) } }
                    SwitchRow("Show Track Number", "Show the track number before the title in the lists", st.showTrackNumber) { v -> set { it.copy(showTrackNumber = v) } }
                    Hint("More list options (sorting, zoom) are in the ⋮ menu of each list")
                    RestoreRow { set { it.copy(filenameAsTitle = d.filenameAsTitle, showTrackNumber = d.showTrackNumber) } }
                }

                // ============================================================ OTHER SECTIONS
                page == SettingsPage.AUDIO -> {
                    SectionLabel("ReplayGain (same loudness for every song)")
                    ChoiceRow("ReplayGain", listOf("Off", "Track gain", "Album gain"), st.replayGain) { v -> set { it.copy(replayGain = v) } }
                    SliderRow(
                        "ReplayGain preamp", "%+.1f dB".format(st.replayGainPreamp), st.replayGainPreamp.toFloat(), -6f..6f,
                        enabled = st.replayGain != 0
                    ) { v -> set { it.copy(replayGainPreamp = (v * 2).roundToInt() / 2.0) } }
                    SliderRow(
                        "Files without ReplayGain", "%+.1f dB".format(st.replayGainNoTagDb), st.replayGainNoTagDb.toFloat(), -12f..0f,
                        enabled = st.replayGain != 0
                    ) { v -> set { it.copy(replayGainNoTagDb = (v * 2).roundToInt() / 2.0) } }
                    Hint("Uses the REPLAYGAIN_TRACK_GAIN / REPLAYGAIN_ALBUM_GAIN tags inside your FLAC and MP3 files.")
                    SectionLabel("Fades")
                    SwitchRow("Fade on pause / resume", "Smooth volume fade instead of a hard stop", st.fadeOnPause) { v -> set { it.copy(fadeOnPause = v) } }
                    SliderRow("Fade length", "${st.fadeMs} ms", st.fadeMs.toFloat(), 100f..1500f, enabled = st.fadeOnPause) { v ->
                        set { it.copy(fadeMs = ((v / 50).roundToInt() * 50)) }
                    }
                    SectionLabel("Sound")
                    ActionRow("Equalizer, effects and reverb", "Open the sound tab", onClick = onOpenEqualizer)
                    Hint("Gapless playback is always on. Output: 16-bit PCM through Android (the Bluetooth codec is chosen by the phone).")
                }

                page == SettingsPage.VISUALIZATION -> {
                    SliderRow("Faded controls opacity", "${(st.vizFadedOpacity * 100).roundToInt()}%", st.vizFadedOpacity, 0.1f..0.9f) { v ->
                        set { it.copy(vizFadedOpacity = (v * 20).roundToInt() / 20f) }
                    }
                    ChoiceRow(
                        "Change preset automatically",
                        listOf("Never", "Every 15 s", "Every 30 s", "Every minute", "Every 2 minutes"),
                        listOf(0, 15, 30, 60, 120).indexOf(st.vizPresetSeconds).coerceAtLeast(0)
                    ) { i -> set { it.copy(vizPresetSeconds = listOf(0, 15, 30, 60, 120)[i]) } }
                    Hint("Turn a visualization on with the 🎵 button on the player screen.")
                }

                page == SettingsPage.BACKGROUND -> {
                    SwitchRow("Blurred album art background", "The current cover, blurred, behind every screen", st.bgEnabled) { v -> set { it.copy(bgEnabled = v) } }
                    SliderRow("Blur", "${st.bgBlur} dp", st.bgBlur.toFloat(), 0f..150f, enabled = st.bgEnabled) { v -> set { it.copy(bgBlur = v.roundToInt()) } }
                    SliderRow("Intensity", "${(st.bgIntensity * 100).roundToInt()}%", st.bgIntensity, 0.1f..1f, enabled = st.bgEnabled) { v ->
                        set { it.copy(bgIntensity = (v * 20).roundToInt() / 20f) }
                    }
                    SliderRow("Saturation", "${(st.bgSaturation * 100).roundToInt()}%", st.bgSaturation, 0f..2f, enabled = st.bgEnabled) { v ->
                        set { it.copy(bgSaturation = (v * 20).roundToInt() / 20f) }
                    }
                }

                page == SettingsPage.ALBUM_ART -> {
                    SwitchRow("High quality covers", "Sharper covers on the player screen (uses a bit more memory)", st.artHighQuality) { v -> set { it.copy(artHighQuality = v) } }
                    ActionRow("Clear album art cache", "Read every cover again from the files") { ArtworkLoader.clear() }
                    Hint("Covers are read from the pictures embedded in your music files.")
                }

                page == SettingsPage.LIBRARY -> {
                    var pickFolders by remember { mutableStateOf(false) }
                    var pickCategories by remember { mutableStateOf(false) }
                    ActionRow("Rescan library", "Look for new, changed and deleted songs", onClick = onRescan)
                    ActionRow(
                        "Music folders",
                        if (st.excludedFolders.isEmpty()) "All folders are included" else "${st.excludedFolders.size} folder(s) excluded"
                    ) { pickFolders = true }
                    ChoiceRow(
                        "Skip short files", listOf("Off", "Shorter than 10 s", "Shorter than 30 s", "Shorter than 1 min"),
                        listOf(0, 10, 30, 60).indexOf(st.minDurationSec).coerceAtLeast(0)
                    ) { i -> set { it.copy(minDurationSec = listOf(0, 10, 30, 60)[i]) } }
                    ChoiceRow(
                        "Long songs", listOf("5 min or more", "10 min or more", "15 min or more", "20 min or more", "30 min or more"),
                        listOf(5, 10, 15, 20, 30).indexOf(st.longTrackMin).coerceAtLeast(0)
                    ) { i -> set { it.copy(longTrackMin = listOf(5, 10, 15, 20, 30)[i]) } }
                    ActionRow(
                        "Library categories",
                        if (st.hiddenCategories.isEmpty()) "All categories are shown" else "${st.hiddenCategories.size} hidden"
                    ) { pickCategories = true }
                    PlainRow("List UI", "Filename as title, track numbers") { onPage(SettingsPage.LIST_UI) }

                    if (pickFolders) {
                        CheckListDialog(
                            title = "Music folders",
                            items = allFolders,
                            checked = allFolders.filter { it !in st.excludedFolders }.toSet(),
                            onDone = { kept -> set { it.copy(excludedFolders = allFolders.filter { f -> f !in kept }.toSet()) } },
                            onDismiss = { pickFolders = false }
                        )
                    }
                    if (pickCategories) {
                        val names = Category.values().map { it.name }
                        CheckListDialog(
                            title = "Show on the Library page",
                            items = names,
                            labels = Category.values().map { it.label },
                            checked = names.filter { it !in st.hiddenCategories }.toSet(),
                            onDone = { shown -> set { it.copy(hiddenCategories = names.filter { n -> n !in shown }.toSet()) } },
                            onDismiss = { pickCategories = false }
                        )
                    }
                }

                page == SettingsPage.HEADSET -> {
                    SwitchRow("Pause when headphones disconnect", "Bluetooth or wired", st.pauseOnDisconnect) { v -> set { it.copy(pauseOnDisconnect = v) } }
                    SwitchRow("Resume on Bluetooth connection", "Continue playing when Bluetooth earbuds connect", st.resumeOnBluetooth) { v -> set { it.copy(resumeOnBluetooth = v) } }
                    SwitchRow("Resume on wired headphones", "Continue playing when headphones are plugged in", st.resumeOnWired) { v -> set { it.copy(resumeOnWired = v) } }
                    Hint("Earbud buttons work through Android's media controls: tap = play/pause, and next/previous where your earbuds support it.")
                }

                page == SettingsPage.LOCK_SCREEN -> {
                    Hint(
                        "The lock screen and the notification show Android's media controls for PowerSound: " +
                            "cover, title, artist, a seek bar and play / pause / previous / next. They always follow the song that is playing."
                    )
                    PlainRow("Notification buttons", "Extra buttons in the media controls") { onPage(SettingsPage.NOTIF_BUTTONS) }
                }

                page == SettingsPage.MISC -> {
                    var showLog by remember { mutableStateOf(false) }
                    SwitchRow("Remember position in long songs", "Songs of 10 minutes or more continue where you stopped", st.rememberPosition) { v -> set { it.copy(rememberPosition = v) } }
                    SwitchRow("Stop music when the app is swiped away", "From the recent apps screen", st.stopOnSwipeAway) { v -> set { it.copy(stopOnSwipeAway = v) } }
                    SwitchRow("Ask before deleting files", "Show a confirmation first", st.confirmDelete) { v -> set { it.copy(confirmDelete = v) } }
                    SwitchRow("Keep an error log", "Saves crash details on the phone", st.keepCrashLog) { v -> set { it.copy(keepCrashLog = v) } }
                    ActionRow("View error log", "See, share or clear the last crash") { showLog = true }
                    if (showLog) ErrorLogDialog { showLog = false }
                }

                page == SettingsPage.ABOUT -> {
                    val version = remember {
                        runCatching {
                            @Suppress("DEPRECATION")
                            context.packageManager.getPackageInfo(context.packageName, 0).versionName
                        }.getOrNull() ?: ""
                    }
                    Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) { PowerSoundLogo() }
                    Hint("PowerSound Player $version")
                    ActionRow("Check for updates", "Look for a newer version on GitHub", onClick = onCheckUpdate)
                    ActionRow("Changelog", "What changed in each version", onClick = onChangelog)
                    ActionRow("Show tips again", "The first-time tips appear again") { HintStore.resetAll() }
                }
            }
        }
    }
}

/** Highest value of "Settings Shortcuts in Main Menu". */
const val MENU_SHORTCUT_MAX = 10

/** Settings pages offered as shortcuts in the main menu, in order. */
val MENU_SHORTCUT_PAGES = listOf(
    SettingsPage.AUDIO, SettingsPage.LOOK, SettingsPage.VISUALIZATION, SettingsPage.LIBRARY, SettingsPage.HEADSET,
    SettingsPage.BACKGROUND, SettingsPage.SKIN, SettingsPage.PLAYER_SCREEN, SettingsPage.LYRICS, SettingsPage.ALBUM_ART
)

// ---------------------------------------------------------------- building blocks

@Composable
private fun SectionLabel(text: String) {
    val sc = LocalSc.current
    Text(
        text,
        color = sc.sub,
        fontSize = 13.sp,
        fontFamily = sc.family,
        fontWeight = fontWeightFor(sc.font, FontWeight.Medium),
        modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 4.dp)
    )
}

@Composable
private fun Title(text: String) {
    val sc = LocalSc.current
    Text(text, color = sc.text, fontSize = 18.sp, fontFamily = sc.family, fontWeight = sc.titleWeight, modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 12.dp))
}

@Composable
private fun Divider() {
    val sc = LocalSc.current
    Box(Modifier.padding(horizontal = 18.dp, vertical = 6.dp).fillMaxWidth().height(1.dp).background(sc.line))
}

@Composable
private fun Hint(text: String) {
    val sc = LocalSc.current
    Text(
        text,
        color = sc.sub,
        fontSize = 12.sp,
        fontFamily = sc.family,
        modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp)
    )
}

/** Hint text that opens another page when pressed (underlined look like the reference). */
@Composable
private fun LinkHint(text: String, onClick: () -> Unit) {
    val sc = LocalSc.current
    Text(
        text,
        color = sc.sub,
        fontSize = 13.sp,
        fontFamily = sc.family,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp)
    )
}

/** Grey notes with a bar on the left. */
@Composable
private fun NoteBlock(vararg lines: String) {
    val sc = LocalSc.current
    Row(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min).padding(horizontal = 18.dp, vertical = 8.dp)) {
        Box(Modifier.width(4.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(sc.sub.copy(alpha = 0.6f)))
        Spacer(Modifier.width(14.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            lines.forEach { Text(it, color = sc.sub, fontSize = 12.sp, fontFamily = sc.family) }
        }
    }
}

@Composable
private fun NavRow(icon: ImageVector, color: Color, title: String, subtitle: String, onClick: () -> Unit) {
    val sc = LocalSc.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = sc.text, fontSize = 18.sp, fontFamily = sc.family, fontWeight = sc.titleWeight)
            Text(subtitle, color = sc.sub, fontSize = 13.sp, fontFamily = sc.family)
        }
    }
}

/** A row without an icon that opens a sub-page or runs an action. */
@Composable
private fun PlainRow(title: String, subtitle: String? = null, onClick: () -> Unit) {
    val sc = LocalSc.current
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp)
    ) {
        Text(title, color = sc.text, fontSize = 18.sp, fontFamily = sc.family, fontWeight = sc.titleWeight)
        if (subtitle != null) Text(subtitle, color = sc.sub, fontSize = 13.sp, fontFamily = sc.family)
    }
}

@Composable
private fun RestoreRow(onClick: () -> Unit) {
    var done by remember { mutableStateOf(false) }
    PlainRow("Restore Defaults", if (done) "Restored" else null) { onClick(); done = true }
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    val sc = LocalSc.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = sc.text, fontSize = 18.sp, fontFamily = sc.family, fontWeight = sc.titleWeight)
            if (subtitle != null) Text(subtitle, color = sc.sub, fontSize = 13.sp, fontFamily = sc.family)
        }
        Spacer(Modifier.width(10.dp))
        Switch(checked = checked, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary))
    }
}

@Composable
private fun ActionRow(title: String, subtitle: String, enabled: Boolean = true, onClick: () -> Unit) {
    val sc = LocalSc.current
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp)
    ) {
        Text(title, color = if (enabled) sc.text else sc.sub.copy(alpha = 0.6f), fontSize = 18.sp, fontFamily = sc.family, fontWeight = sc.titleWeight)
        Text(subtitle, color = if (enabled) sc.sub else sc.sub.copy(alpha = 0.6f), fontSize = 13.sp, fontFamily = sc.family)
    }
}

/** Title + pills to pick one option (like the reference's chip rows). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(title: String, subtitle: String?, options: List<String>, selected: Int, onPick: (Int) -> Unit) {
    val sc = LocalSc.current
    val primary = MaterialTheme.colorScheme.primary
    val onPrimary = if (primary.luminance() > 0.5f) Color.Black else Color.White
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp)) {
        Text(title, color = sc.text, fontSize = 18.sp, fontFamily = sc.family, fontWeight = sc.titleWeight)
        if (subtitle != null) Text(subtitle, color = sc.sub, fontSize = 13.sp, fontFamily = sc.family)
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEachIndexed { i, o ->
                val on = i == selected
                Text(
                    o,
                    color = if (on) onPrimary else sc.sub,
                    fontSize = 14.sp,
                    fontFamily = sc.family,
                    fontWeight = if (on) FontWeight.SemiBold else sc.bodyWeight,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (on) primary else Color.Transparent)
                        .border(1.dp, if (on) primary else sc.chipBorder, RoundedCornerShape(50))
                        .clickable { onPick(i) }
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }
    }
}

/** Radio button + title + description (skins, layouts). */
@Composable
private fun RadioCard(title: String, description: String, selected: Boolean, onClick: () -> Unit) {
    val sc = LocalSc.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        RadioButton(selected = selected, onClick = onClick, colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary, unselectedColor = sc.sub))
        Column(Modifier.padding(top = 10.dp, end = 10.dp)) {
            Text(title, color = sc.text, fontSize = 16.sp, fontFamily = sc.family, fontWeight = sc.titleWeight)
            Text(description, color = sc.sub, fontSize = 13.sp, fontFamily = sc.family)
        }
    }
}

@Composable
private fun ChoiceRow(title: String, options: List<String>, selected: Int, subtitlePrefix: String = "", onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ActionRow(title, subtitlePrefix + options.getOrElse(selected) { options.first() }) { open = true }
    if (open) ChoiceDialog(title, options, selected, onPick) { open = false }
}

@Composable
private fun SliderRow(
    title: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean = true,
    subtitle: String? = null,
    onChange: (Float) -> Unit
) {
    val sc = LocalSc.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp)) {
        Row {
            Text(title, color = if (enabled) sc.text else sc.sub, fontSize = 18.sp, fontFamily = sc.family, fontWeight = sc.titleWeight, modifier = Modifier.weight(1f))
            Text(valueText, color = MaterialTheme.colorScheme.primary, fontSize = 14.sp, fontFamily = sc.family)
        }
        if (subtitle != null) Text(subtitle, color = sc.sub, fontSize = 13.sp, fontFamily = sc.family)
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            enabled = enabled,
            colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary, activeTrackColor = MaterialTheme.colorScheme.primary)
        )
    }
}

/** Slider with fixed steps and labels under both ends ("Disabled … Max"). */
@Composable
private fun StepSliderRow(title: String, valueText: String, value: Int, min: Int, max: Int, minLabel: String, maxLabel: String, onChange: (Int) -> Unit) {
    val sc = LocalSc.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp)) {
        Text(title, color = sc.text, fontSize = 18.sp, fontFamily = sc.family, fontWeight = sc.titleWeight)
        Text(valueText, color = sc.sub, fontSize = 13.sp, fontFamily = sc.family)
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Text(minLabel, color = sc.sub, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text(maxLabel, color = sc.sub, fontSize = 12.sp)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = min.toFloat()..max.toFloat(),
            steps = (max - min - 1).coerceAtLeast(0),
            colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary, activeTrackColor = MaterialTheme.colorScheme.primary)
        )
    }
}

@Composable
private fun TextInputDialog(title: String, initial: String, hint: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1B1B22),
        title = { Text(title, color = Color.White) },
        text = {
            Column {
                OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = false, textStyle = TextStyle(color = Color.White, fontSize = 14.sp))
                Spacer(Modifier.height(6.dp))
                Text(hint, color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text.trim()); onDismiss() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Choose and order the buttons of the row under the cover. */
@Composable
fun EditPlayerButtonsDialog(current: List<String>, onSave: (List<String>) -> Unit, onDismiss: () -> Unit) {
    // shown ones first (in their order), then the hidden ones
    var order by remember { mutableStateOf(current.filter { id -> PLAYER_BUTTONS.any { it.id == id } } + PLAYER_BUTTONS.map { it.id }.filter { it !in current }) }
    var shown by remember { mutableStateOf(current.toSet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1B1B22),
        title = { Text("Player Screen Buttons", color = Color.White) },
        text = {
            LazyColumn(Modifier.heightIn(max = 440.dp)) {
                items(order, key = { it }) { id ->
                    val b = PLAYER_BUTTONS.first { it.id == id }
                    val i = order.indexOf(id)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = id in shown,
                            onCheckedChange = { shown = if (it) shown + id else shown - id },
                            colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary)
                        )
                        Icon(b.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(b.label, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        IconButton(onClick = { if (i > 0) order = order.toMutableList().also { l -> l.add(i - 1, l.removeAt(i)) } }, enabled = i > 0) {
                            Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = "Up", tint = Color.White.copy(alpha = if (i > 0) 1f else 0.3f))
                        }
                        IconButton(onClick = { if (i < order.lastIndex) order = order.toMutableList().also { l -> l.add(i + 1, l.removeAt(i)) } }, enabled = i < order.lastIndex) {
                            Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Down", tint = Color.White.copy(alpha = if (i < order.lastIndex) 1f else 0.3f))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(order.filter { it in shown }); onDismiss() }) { Text("Save") } },
        dismissButton = {
            Row {
                TextButton(onClick = { order = com.powersound.player.data.DEFAULT_PLAYER_BUTTONS + PLAYER_BUTTONS.map { it.id }.filter { it !in com.powersound.player.data.DEFAULT_PLAYER_BUTTONS }; shown = com.powersound.player.data.DEFAULT_PLAYER_BUTTONS.toSet() }) { Text("Restore") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

@Composable
private fun CheckListDialog(
    title: String,
    items: List<String>,
    labels: List<String> = items,
    checked: Set<String>,
    onDone: (Set<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var selection by remember { mutableStateOf(checked) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1B1B22),
        title = { Text(title, color = Color.White) },
        text = {
            if (items.isEmpty()) {
                Text("Nothing found yet — scan the library first.", color = Color.White.copy(alpha = 0.7f))
            } else {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(items.indices.toList()) { i ->
                        val item = items[i]
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { selection = if (item in selection) selection - item else selection + item },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = item in selection,
                                onCheckedChange = { selection = if (it) selection + item else selection - item },
                                colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary)
                            )
                            Text(labels.getOrElse(i) { item }, color = Color.White, fontSize = 14.sp)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDone(selection); onDismiss() }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ErrorLogDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var log by remember { mutableStateOf(CrashLog.read(context)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1B1B22),
        title = { Text("Error log", color = Color.White) },
        text = {
            Text(
                log ?: "No errors saved. 👍",
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 11.sp,
                modifier = Modifier
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState())
            )
        },
        confirmButton = {
            Row {
                if (log != null) {
                    TextButton(onClick = {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, log)
                        context.startActivity(Intent.createChooser(send, "Share error log").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }) { Text("Share") }
                    TextButton(onClick = { CrashLog.clear(context); log = null }) { Text("Clear") }
                }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        }
    )
}

@Composable
fun ChangelogDialog(onDismiss: () -> Unit) {
    val entries by produceState<List<Pair<String, String>>?>(null) {
        value = runCatching { Updater.changelog() }.getOrDefault(emptyList())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1B1B22),
        title = { Text("Changelog", color = Color.White) },
        text = {
            val list = entries
            when {
                list == null -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                list.isEmpty() -> Text("Couldn't load the changelog. Check your internet connection.", color = Color.White.copy(alpha = 0.7f))
                else -> LazyColumn(Modifier.heightIn(max = 460.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
                    items(list) { (name, body) ->
                        Text(name, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp))
                        if (body.isNotBlank()) Text(body, color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
fun HelpDialog(onDismiss: () -> Unit) {
    val tips = listOf(
        "Library" to "Swipe a page in any direction to go back. Pinch to zoom. ⋮ for sorting and zoom. Long press a song for its menu.",
        "Player" to "Swipe the cover to change songs (further = change album/folder). Swipe down or tap the cover for the song's list. Swipe up for lyrics. Long press the cover for the track menu.",
        "Seeking" to "Long press the progress area, move your finger, let go. Long press the elapsed time to go back to 0:00.",
        "Categories" to "Long press ⏮ ⏭ for the previous / next album or folder.",
        "Buttons" to "Long press the empty space between the buttons under the cover to edit them. Long press the 🎵, 🔁 and 🔀 buttons for more options. Tap the info line under the time to change it, long press for Audio Info.",
        "Mini player" to "Tap to open the player, swipe to change songs, long press for the song's list.",
        "Equalizer tab" to "Swipe between Equalizer, Effects and Reverb. Long press the equalizer icon below for quick on/off switches."
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1B1B22),
        title = { Text("Help", color = Color.White) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                tips.forEach { (t, d) ->
                    Text(t, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp))
                    Text(d, color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = { HintStore.resetAll(); onDismiss() }) { Text("Show tips again") }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        }
    )
}
