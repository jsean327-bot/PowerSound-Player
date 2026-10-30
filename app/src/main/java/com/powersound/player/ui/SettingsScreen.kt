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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
    AUDIO("Audio", "Crossfade, replay gain, focus, equalizer, outputs", Icons.Rounded.VolumeUp, Color(0xFFE0708A)),
    VISUALIZATION("Visualization", "Faded controls opacity, preset duration, spectrum", Icons.Rounded.GraphicEq, Color(0xFFB07CFF)),
    BACKGROUND("Background", "Blur, details, gradient, intensity, saturation", Icons.Rounded.Wallpaper, Color(0xFF4FB6A0)),
    ALBUM_ART("Album Art", "Download, quality, cache cleanup", Icons.Rounded.Album, Color(0xFF6FBF73)),
    LIBRARY("Library", "Rescan, folders, lists, search, playlists, shuffle, scanner", Icons.Rounded.LibraryMusic, Color(0xFF4F8DF7)),
    HEADSET("Headset/Bluetooth", "Pause/resume on connection, headset buttons", Icons.Rounded.Headphones, Color(0xFFC9C9D6)),
    LOCK_SCREEN("Lock Screen", "Blur, default image, PowerSound on the lock screen", Icons.Rounded.Lock, Color(0xFFE59A4F)),
    MISC("Misc", "Scrobbling, tweaks, network streams, error log", Icons.Rounded.MoreHoriz, Color(0xFF4FB6C9)),
    ABOUT("About", "Version/changelog, updates, licenses, privacy", Icons.Rounded.Info, Color(0xFF9A9AB0)),

    // ---- inside Look and Feel ----
    SKIN("Skin", "Visual theme and per-skin options", Icons.Rounded.Palette, Color(0xFF8E8CF0), LOOK),
    PLAYER_SCREEN("Player Screen", "Buttons, labels, rating", Icons.Rounded.Palette, Color(0xFF8E8CF0), LOOK),
    LYRICS("Lyrics", "Swipe, button, size, offset", Icons.Rounded.Palette, Color(0xFF8E8CF0), LOOK),
    NOTIFICATIONS("Notifications", "Buttons, keeping", Icons.Rounded.Palette, Color(0xFF8E8CF0), LOOK),
    NOTIF_BUTTONS("Buttons", "Media notification buttons", Icons.Rounded.Palette, Color(0xFF8E8CF0), NOTIFICATIONS),
    ICON("Icon", "Launcher icon", Icons.Rounded.Palette, Color(0xFF8E8CF0), LOOK),

    // ---- inside Library ----
    LIST_UI("Lists", "", Icons.Rounded.LibraryMusic, Color(0xFF4F8DF7), LIBRARY),
    LIB_SEARCH("Search", "", Icons.Rounded.LibraryMusic, Color(0xFF4F8DF7), LIBRARY),
    LIB_QUEUE("Queue", "", Icons.Rounded.LibraryMusic, Color(0xFF4F8DF7), LIBRARY),
    LIB_PLAYLISTS("Playlists", "", Icons.Rounded.LibraryMusic, Color(0xFF4F8DF7), LIBRARY),
    LIB_SHUFFLE("Shuffle", "", Icons.Rounded.LibraryMusic, Color(0xFF4F8DF7), LIBRARY),
    LIB_SCANNER("Scanner", "", Icons.Rounded.LibraryMusic, Color(0xFF4F8DF7), LIBRARY),

    // ---- inside Audio ----
    CROSSFADE("Crossfade, Fade, and Gapless", "", Icons.Rounded.VolumeUp, Color(0xFFE0708A), AUDIO),
    REPLAY_GAIN("Replay Gain (RG)", "", Icons.Rounded.VolumeUp, Color(0xFFE0708A), AUDIO),
    AUDIO_FOCUS("Audio Focus", "", Icons.Rounded.VolumeUp, Color(0xFFE0708A), AUDIO),
    EQ_SETTINGS("Equalizer", "", Icons.Rounded.VolumeUp, Color(0xFFE0708A), AUDIO),
    EQ_BANDS("Graphic Equalizer Bands", "", Icons.Rounded.VolumeUp, Color(0xFFE0708A), EQ_SETTINGS),
    TONE("Tone", "", Icons.Rounded.VolumeUp, Color(0xFFE0708A), EQ_SETTINGS),
    RESAMPLER("Resampler", "", Icons.Rounded.VolumeUp, Color(0xFFE0708A), AUDIO),
    OUTPUTS("Audio Outputs", "", Icons.Rounded.VolumeUp, Color(0xFFE0708A), AUDIO),
    OUT_WIRED("Wired Headset/AUX", "", Icons.Rounded.VolumeUp, Color(0xFFE0708A), OUTPUTS),
    OUT_SPEAKER("Speaker", "", Icons.Rounded.VolumeUp, Color(0xFFE0708A), OUTPUTS),
    OUT_BT("Bluetooth", "", Icons.Rounded.VolumeUp, Color(0xFFE0708A), OUTPUTS),
    OUT_USB("USB DAC", "", Icons.Rounded.VolumeUp, Color(0xFFE0708A), OUTPUTS),
    OUT_OTHER("Other Output Devices", "", Icons.Rounded.VolumeUp, Color(0xFFE0708A), OUTPUTS),
    BIT_PERFECT("Bit Perfect", "", Icons.Rounded.VolumeUp, Color(0xFFE0708A), AUDIO),
    TWEAKS("Advanced Tweaks", "", Icons.Rounded.VolumeUp, Color(0xFFE0708A), AUDIO)
}

/** Output key → its settings page. */
private val OUTPUT_PAGES = listOf(
    "wired" to SettingsPage.OUT_WIRED, "speaker" to SettingsPage.OUT_SPEAKER, "bt" to SettingsPage.OUT_BT,
    "usb" to SettingsPage.OUT_USB, "other" to SettingsPage.OUT_OTHER
)

/** Everything searchable: (page, item title). */
private val SEARCH_INDEX: List<Pair<SettingsPage, String>> = listOf(
    SettingsPage.LOOK to "Skin", SettingsPage.LOOK to "Follow Day/Night Mode", SettingsPage.LOOK to "Settings Theme",
    SettingsPage.LOOK to "Settings Font", SettingsPage.LOOK to "Language", SettingsPage.LOOK to "Icon",
    SettingsPage.LOOK to "Screen Orientation", SettingsPage.LOOK to "Animations", 
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
    SettingsPage.PLAYER_SCREEN to "Menu Button", SettingsPage.PLAYER_SCREEN to "Menu Button Long Press", SettingsPage.LYRICS to "Lyrics Button",
    SettingsPage.PLAYER_SCREEN to "Line2 Press", SettingsPage.PLAYER_SCREEN to "Line2 Long Press",
    SettingsPage.PLAYER_SCREEN to "<< Button Resets Current Track",
    SettingsPage.PLAYER_SCREEN to "Navigate to the Folders",
    SettingsPage.LYRICS to "Lyrics Swipe Up", SettingsPage.LYRICS to "Lyrics Item In The Track Menu", SettingsPage.LYRICS to "Keep Screen On (lyrics)",
    SettingsPage.LYRICS to "Lyrics Time Offset", SettingsPage.LYRICS to "Lyrics Size", SettingsPage.LYRICS to "Preferred Lyrics App",
    SettingsPage.LYRICS to "Custom URL", SettingsPage.LYRICS to "Rescan Lyrics Tags", SettingsPage.LYRICS to "Scan LRC Files",
    SettingsPage.LYRICS to "Always Use UTF-8",
    SettingsPage.NOTIFICATIONS to "Navigate to the List", SettingsPage.NOTIFICATIONS to "Keep Notification",
    SettingsPage.NOTIFICATIONS to "Remove Notification on Disconnection", SettingsPage.NOTIF_BUTTONS to "Notification buttons",
    SettingsPage.AUDIO to "Audio Info",
    SettingsPage.CROSSFADE to "Auto-advance Fading", SettingsPage.CROSSFADE to "Manual Track Change Fading", SettingsPage.CROSSFADE to "Crossfade Length",
    SettingsPage.CROSSFADE to "Fade Play/Pause/Stop", SettingsPage.CROSSFADE to "Fade Length", SettingsPage.CROSSFADE to "Fade on Seek",
    SettingsPage.CROSSFADE to "Silence Between Tracks", SettingsPage.CROSSFADE to "Gapless",
    SettingsPage.REPLAY_GAIN to "Replay Gain", SettingsPage.REPLAY_GAIN to "Source", SettingsPage.REPLAY_GAIN to "RG preamp",
    SettingsPage.REPLAY_GAIN to "Preamp for songs without RG info",
    SettingsPage.AUDIO_FOCUS to "Resume After Call", SettingsPage.AUDIO_FOCUS to "Resume On Start", SettingsPage.AUDIO_FOCUS to "Resume On Reopen",
    SettingsPage.AUDIO_FOCUS to "Short Audio Focus Change / Calls", SettingsPage.AUDIO_FOCUS to "Resume On Focus Gain",
    SettingsPage.AUDIO_FOCUS to "Duck Volume", SettingsPage.AUDIO_FOCUS to "Permanent Audio Focus Change",
    SettingsPage.EQ_SETTINGS to "Equalizer Values", SettingsPage.EQ_SETTINGS to "Tone Values", SettingsPage.EQ_SETTINGS to "Auto Save",
    SettingsPage.EQ_SETTINGS to "Smooth Equalizer/Tone Gains", SettingsPage.EQ_SETTINGS to "Import AutoEq Presets",
    SettingsPage.EQ_SETTINGS to "Restore Equalizer Presets",
    SettingsPage.EQ_BANDS to "Number of Bands", SettingsPage.EQ_BANDS to "Enable Custom Bands", SettingsPage.EQ_BANDS to "Custom Number of Bands",
    SettingsPage.EQ_BANDS to "First Frequency", SettingsPage.EQ_BANDS to "Last Frequency",
    SettingsPage.TONE to "Bass Frequency", SettingsPage.TONE to "Bass Q", SettingsPage.TONE to "Treble Frequency", SettingsPage.TONE to "Treble Q",
    SettingsPage.RESAMPLER to "Resampler Type", SettingsPage.RESAMPLER to "Resampler Cutoff Frequency Ratio", SettingsPage.RESAMPLER to "Dither",
    SettingsPage.OUTPUTS to "Audio Outputs", SettingsPage.OUT_BT to "Bluetooth output", SettingsPage.OUT_WIRED to "Wired Headset/AUX output",
    SettingsPage.OUT_SPEAKER to "Speaker output", SettingsPage.OUT_USB to "USB DAC output", SettingsPage.OUT_OTHER to "Other Output Devices",
    SettingsPage.BIT_PERFECT to "Perfect Bit Perfect", SettingsPage.BIT_PERFECT to "64-bit Audio Pipeline",
    SettingsPage.OUT_BT to "Output Format", SettingsPage.OUT_BT to "Sample Rate",
    SettingsPage.TWEAKS to "Pause/Resume on Volume", SettingsPage.TWEAKS to "MusicFX", SettingsPage.TWEAKS to "Restore Reverb Presets",
    SettingsPage.VISUALIZATION to "Visualization On Player Screen", SettingsPage.VISUALIZATION to "Equalizer Screen Spectrum",
    SettingsPage.VISUALIZATION to "Visualization in Library", SettingsPage.VISUALIZATION to "Preset Duration",
    SettingsPage.VISUALIZATION to "Top Visualization Panel Opacity", SettingsPage.VISUALIZATION to "Faded Controls Opacity",
    SettingsPage.VISUALIZATION to "UI Timeout", SettingsPage.VISUALIZATION to "Visible Album Art", SettingsPage.VISUALIZATION to "Ignore Touch",
    SettingsPage.VISUALIZATION to "Track Opacity", SettingsPage.VISUALIZATION to "Hide System Bars For Full Screen",
    SettingsPage.VISUALIZATION to "Scaled Bars For Faded Controls", SettingsPage.VISUALIZATION to "HD", SettingsPage.VISUALIZATION to "Crop Aspect",
    SettingsPage.VISUALIZATION to "Force 30 FPS", SettingsPage.VISUALIZATION to "Visualization Presets",
    SettingsPage.BACKGROUND to "Enable Blurred Backgrounds", SettingsPage.BACKGROUND to "List Background", SettingsPage.BACKGROUND to "Lyrics Background",
    SettingsPage.BACKGROUND to "Background Gradient", SettingsPage.BACKGROUND to "Background Gradient Color",
    SettingsPage.BACKGROUND to "Background Gradient For Lists", SettingsPage.BACKGROUND to "Background Blur",
    SettingsPage.BACKGROUND to "Background Details", SettingsPage.BACKGROUND to "Background Intensity", SettingsPage.BACKGROUND to "Background Saturation",
    SettingsPage.ALBUM_ART to "Download Album Art", SettingsPage.ALBUM_ART to "Download Artist Images", SettingsPage.ALBUM_ART to "High Resolution",
    SettingsPage.ALBUM_ART to "Download Only On Wi-Fi", SettingsPage.ALBUM_ART to "Also Search By Title/Filename",
    SettingsPage.ALBUM_ART to "Prefer Downloaded Album Art", SettingsPage.ALBUM_ART to "Reset Negative Status", SettingsPage.ALBUM_ART to "Delete Cache",
    SettingsPage.ALBUM_ART to "Force Default Image", SettingsPage.ALBUM_ART to "Use 24-bit RGB", SettingsPage.ALBUM_ART to "Increase Resolution",
    SettingsPage.ALBUM_ART to "Aspect Ratio",
    SettingsPage.LIBRARY to "Rescan", SettingsPage.LIBRARY to "Full Rescan", SettingsPage.LIBRARY to "Music Folders",
    SettingsPage.LIBRARY to "All Files Access", SettingsPage.LIBRARY to "Ignore Short Tracks", SettingsPage.LIBRARY to "Ignore Video Tracks",
    SettingsPage.LIBRARY to "Store/Restore Per Track Progress", SettingsPage.LIBRARY to "-10/+10s Rewind Pro Buttons",
    SettingsPage.LIBRARY to "Track Duration For Per Track Progress and -10/+10s Buttons", SettingsPage.LIBRARY to "Count As Played",
    SettingsPage.LIBRARY to "Reset Stats",
    SettingsPage.LIST_UI to "Library List Options", SettingsPage.LIST_UI to "Static Navbar", SettingsPage.LIST_UI to "Seekbar in Navbar",
    SettingsPage.LIST_UI to "Headers With Meta", SettingsPage.LIST_UI to "A-Z Scroll", SettingsPage.LIST_UI to "Header Buttons",
    SettingsPage.LIST_UI to "Bottom Buttons", SettingsPage.LIST_UI to "Show Track Number", SettingsPage.LIST_UI to "Filename As Title",
    SettingsPage.LIST_UI to "Click Restarts Track", SettingsPage.LIST_UI to "List Item Action", SettingsPage.LIST_UI to "Delete Action",
    SettingsPage.LIST_UI to "Join Albums", SettingsPage.LIST_UI to "Album Artist Label for Tracks", SettingsPage.LIST_UI to "Album Artist Label for Albums",
    SettingsPage.LIST_UI to "Album Artist Label for Album Artist Tracks", SettingsPage.LIST_UI to "Hide Unknown Album",
    SettingsPage.LIST_UI to "Hide Unknown Artist", SettingsPage.LIST_UI to "Show Unsplit Combined Categories",
    SettingsPage.LIST_UI to "Folders Hierarchy Immediate Root", SettingsPage.LIST_UI to "Don't Ignore Articles For Sort",
    SettingsPage.LIB_SEARCH to "Play Tracks Only", SettingsPage.LIB_SEARCH to "Search Track Titles Only", SettingsPage.LIB_SEARCH to "Search Categories",
    SettingsPage.LIB_QUEUE to "Queue Insert Position", SettingsPage.LIB_QUEUE to "Play Next Insert Position",
    SettingsPage.LIB_PLAYLISTS to "Import System Library Playlists", SettingsPage.LIB_PLAYLISTS to "Import .m3u / .m3u8 Playlists",
    SettingsPage.LIB_PLAYLISTS to "Export Playlists", SettingsPage.LIB_PLAYLISTS to "Playlist Insert Position",
    SettingsPage.LIB_PLAYLISTS to "Remove Playlist Entries On Track Deletion", SettingsPage.LIB_PLAYLISTS to "Don't Add Duplicates",
    SettingsPage.LIB_SHUFFLE to "No Reshuffle", SettingsPage.LIB_SHUFFLE to "No Reshuffle For Large Lists",
    SettingsPage.LIB_SHUFFLE to "Category Items Shuffle", SettingsPage.LIB_SHUFFLE to "Shuffle All Songs In Folder Hierarchy",
    SettingsPage.LIB_SCANNER to "Auto-scan", SettingsPage.LIB_SCANNER to "Rescan Immediately",
    SettingsPage.LIB_SCANNER to "Symbols to Split Multiple Artists", SettingsPage.LIB_SCANNER to "Symbols to Split Multiple Composers",
    SettingsPage.LIB_SCANNER to "Symbols to Split Multiple Genres", SettingsPage.LIB_SCANNER to "Unsplit Artists",
    SettingsPage.LIB_SCANNER to "Tag Encoding", SettingsPage.LIB_SCANNER to "Always Use UTF-8 for .m3u",
    SettingsPage.HEADSET to "Resume On Wired Headset", SettingsPage.HEADSET to "Resume On Bluetooth",
    SettingsPage.HEADSET to "Pause On Headset Disconnect", SettingsPage.HEADSET to "Pause On USB DAC connection",
    SettingsPage.HEADSET to "Respond To Buttons", SettingsPage.HEADSET to "Wired Headset", SettingsPage.HEADSET to "Bluetooth",
    SettingsPage.HEADSET to "Strict Resume/Pause/Stop", SettingsPage.HEADSET to "Ignore Bluetooth Commands",
    SettingsPage.HEADSET to "Ignore Repeat/Shuffle", SettingsPage.HEADSET to "Beep", SettingsPage.HEADSET to "Beep More",
    SettingsPage.HEADSET to "Vibrate", SettingsPage.HEADSET to "Last Processed Commands",
    SettingsPage.LOCK_SCREEN to "Blur", SettingsPage.LOCK_SCREEN to "Show Default Image", SettingsPage.LOCK_SCREEN to "Show On Lock Screen",
    SettingsPage.LOCK_SCREEN to "Open App Settings",
    SettingsPage.MISC to "Scrobble via Official Last.fm app", SettingsPage.MISC to "Scrobble via Simple Scrobbler",
    SettingsPage.MISC to "Metachanged Intent", SettingsPage.MISC to "Use Wakelock", SettingsPage.MISC to "Shutdown Intent",
    SettingsPage.MISC to "Pause on Screen Off", SettingsPage.MISC to "Apply High Framerate",
    SettingsPage.MISC to "Stop music when the app is swiped away", SettingsPage.MISC to "Ask before deleting files",
    SettingsPage.MISC to "Network Stream Timeout", SettingsPage.MISC to "Network Stream Buffer", SettingsPage.MISC to "User Agent",
    SettingsPage.MISC to "Keep an error log", SettingsPage.MISC to "View error log",
    SettingsPage.ABOUT to "Version/Changelog", SettingsPage.ABOUT to "Check for updates", SettingsPage.ABOUT to "Get Support",
    SettingsPage.ABOUT to "Open Source Licenses", SettingsPage.ABOUT to "Privacy Policy"
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
    onAudioInfo: () -> Unit,
    onImportAutoEq: () -> Unit,
    onCheckUpdate: () -> Unit,
    onChangelog: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    lib: LibraryActions
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
        SettingsContent(st, page, allFolders, onPage, onClose, onRescan, onAudioInfo, onImportAutoEq, onCheckUpdate, onChangelog, onExport, onImport, lib)
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
    onAudioInfo: () -> Unit,
    onImportAutoEq: () -> Unit,
    onCheckUpdate: () -> Unit,
    onChangelog: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    lib: LibraryActions
) {
    val sc = LocalSc.current
    val context = LocalContext.current
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    fun set(change: (AppSettings) -> AppSettings) = SettingsStore.update(change)
    val d = remember { AppSettings() }
    val args = PageArgs(st, d, allFolders, onPage, onRescan, onAudioInfo, onImportAutoEq, onCheckUpdate, onChangelog, onExport, onImport, lib)

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

                page == null -> SettingsPage9(page, args)

                // ============================================================ LOOK AND FEEL
                page == SettingsPage.LOOK -> SettingsPage1(page, args)

                page == SettingsPage.SKIN -> SettingsPage2(page, args)

                page == SettingsPage.PLAYER_SCREEN -> SettingsPage3(page, args)

                page == SettingsPage.LYRICS -> SettingsPage4(page, args)

                page == SettingsPage.NOTIFICATIONS -> SettingsPage5(page, args)

                page == SettingsPage.NOTIF_BUTTONS -> SettingsPage6(page, args)

                page == SettingsPage.ICON -> SettingsPage7(page, args)

                page == SettingsPage.LIST_UI -> SettingsPage8(page, args)

                // ============================================================ OTHER SECTIONS
                // ============================================================ AUDIO
                page == SettingsPage.AUDIO -> SettingsPage10(page, args)

                page == SettingsPage.CROSSFADE -> SettingsPage11(page, args)

                page == SettingsPage.REPLAY_GAIN -> SettingsPage12(page, args)

                page == SettingsPage.AUDIO_FOCUS -> SettingsPage13(page, args)

                page == SettingsPage.EQ_SETTINGS -> SettingsPage14(page, args)

                page == SettingsPage.EQ_BANDS -> SettingsPage15(page, args)

                page == SettingsPage.TONE -> SettingsPage16(page, args)

                page == SettingsPage.RESAMPLER -> SettingsPage17(page, args)

                page == SettingsPage.OUTPUTS -> SettingsPage18(page, args)

                page in OUTPUT_PAGES.map { it.second } -> SettingsPage19(page, args)

                page == SettingsPage.BIT_PERFECT -> SettingsPage20(page, args)

                page == SettingsPage.TWEAKS -> SettingsPage21(page, args)

                page == SettingsPage.VISUALIZATION -> SettingsPage22(page, args)

                page == SettingsPage.BACKGROUND -> SettingsPage23(page, args)

                page == SettingsPage.ALBUM_ART -> SettingsPage24(page, args)

                page == SettingsPage.LIBRARY -> SettingsPage25(page, args)

                page == SettingsPage.HEADSET -> SettingsPage26(page, args)

                page == SettingsPage.LOCK_SCREEN -> SettingsPage27(page, args)

                page == SettingsPage.MISC -> SettingsPage28(page, args)

                page == SettingsPage.ABOUT -> SettingsPage29(page, args)

                page == SettingsPage.LIB_SEARCH -> SettingsPage30(page, args)

                page == SettingsPage.LIB_QUEUE -> SettingsPage31(page, args)

                page == SettingsPage.LIB_PLAYLISTS -> SettingsPage32(page, args)

                page == SettingsPage.LIB_SHUFFLE -> SettingsPage33(page, args)

                page == SettingsPage.LIB_SCANNER -> SettingsPage34(page, args)
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
private fun StepSliderRow(
    title: String,
    valueText: String,
    value: Int,
    min: Int,
    max: Int,
    minLabel: String,
    maxLabel: String,
    enabled: Boolean = true,
    onChange: (Int) -> Unit
) {
    val sc = LocalSc.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp)) {
        Text(title, color = if (enabled) sc.text else sc.sub.copy(alpha = 0.6f), fontSize = 18.sp, fontFamily = sc.family, fontWeight = sc.titleWeight)
        Text(valueText, color = sc.sub, fontSize = 13.sp, fontFamily = sc.family)
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Text(minLabel, color = sc.sub, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text(maxLabel, color = sc.sub, fontSize = 12.sp)
        }
        Slider(
            value = value.coerceIn(min, max).toFloat(),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = min.toFloat()..max.toFloat(),
            steps = (max - min - 1).coerceAtLeast(0),
            enabled = enabled,
            colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary, activeTrackColor = MaterialTheme.colorScheme.primary)
        )
    }
}

/** Title on the left, a knob with its value on the right (Replay Gain, Resampler). */
@Composable
private fun KnobRow(
    title: String,
    subtitle: String?,
    valueText: String,
    fraction: Float,
    bipolar: Boolean = true,
    enabled: Boolean = true,
    onChange: (Float) -> Unit,
    onReset: () -> Unit
) {
    val sc = LocalSc.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = if (enabled) sc.text else sc.sub.copy(alpha = 0.6f), fontSize = 18.sp, fontFamily = sc.family, fontWeight = sc.titleWeight)
            if (subtitle != null) Text(subtitle, color = sc.sub, fontSize = 13.sp, fontFamily = sc.family)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            PowerKnob(
                label = "", valueText = "", fraction = fraction.coerceIn(0f, 1f), bipolar = bipolar, enabled = enabled,
                sizeDp = 72, onChange = onChange, onReset = onReset
            )
            Text(valueText, color = sc.sub, fontSize = 13.sp, fontFamily = sc.family)
        }
    }
}

@Composable
private fun ConfirmDialog(title: String, text: String, onOk: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1B1B22),
        title = { Text(title, color = Color.White) },
        text = { Text(text, color = Color.White.copy(alpha = 0.8f)) },
        confirmButton = { TextButton(onClick = { onOk(); onDismiss() }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private val OUTPUT_RATES = listOf(0, 1, 44100, 48000, 88200, 96000, 176400, 192000)
private val OUTPUT_RATE_LABELS = listOf("Follow Track", "Native", "44.1 kHz", "48 kHz", "88.2 kHz", "96 kHz", "176.4 kHz", "192 kHz")
private val OUTPUT_FORMAT_LABELS = listOf("32-bit float (Hi-Res)", "16-bit")

private fun outputSummary(st: AppSettings, key: String): String {
    val parts = ArrayList<String>()
    parts.add(OUTPUT_FORMAT_LABELS[(st.outFormat[key] ?: 0).coerceIn(0, 1)])
    parts.add(OUTPUT_RATE_LABELS[OUTPUT_RATES.indexOf(st.outRate[key] ?: 1).coerceAtLeast(0)])
    st.outEqPreset[key]?.let { parts.add("EQ: $it") }
    if (key in st.outNoEq) parts.add("No Equ/Tone")
    return parts.joinToString(" • ")
}

/** One output (Settings → Audio → Audio Outputs → output). */
@Composable
private fun OutputPage(st: AppSettings, key: String, set: ((AppSettings) -> AppSettings) -> Unit) {
    val sc = LocalSc.current
    val engine = com.powersound.player.audio.AudioEngine
    val active = engine.outputKey == key
    val presets by engine.customPresets.collectAsState()
    var pickPreset by remember { mutableStateOf(false) }
    val presetNames = listOf("None") + com.powersound.player.audio.BUILT_IN_PRESETS.map { it.name } + presets.map { it.name }

    ActionRow("Equalizer Preset", st.outEqPreset[key] ?: "None — the equalizer stays as it is") { pickPreset = true }
    Hint("The preset is loaded automatically when this output becomes active.")
    // status like the reference: device, driver, status
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Output Device: " + if (active) engine.currentOutput else OUTPUT_LABEL[key], color = sc.text, fontSize = 14.sp, fontFamily = sc.family)
        Text(
            "Output Driver: Android AudioTrack, " + if ((st.outFormat[key] ?: 0) == 0) "32-bit float" else "16-bit PCM",
            color = sc.sub, fontSize = 13.sp, fontFamily = sc.family
        )
        Text(
            if (active && engine.inputRate > 0) {
                val inK = engine.inputRate / 1000.0
                val outK = engine.outputRate / 1000.0
                "Status: Active • " + if (engine.inputRate == engine.outputRate) "%.1f kHz".format(inK) else "%.1f → %.1f kHz (resampled)".format(inK, outK)
            } else if (active) "Status: Active" else "Status: Inactive",
            color = if (active) MaterialTheme.colorScheme.primary else sc.sub, fontSize = 13.sp, fontFamily = sc.family
        )
    }
    ChipRow(
        "Output Format", "What PowerSound sends to this output", OUTPUT_FORMAT_LABELS, (st.outFormat[key] ?: 0).coerceIn(0, 1)
    ) { i -> set { it.copy(outFormat = if (i == 0) it.outFormat - key else it.outFormat + (key to i)) } }
    Hint(
        "32-bit float keeps every bit of 24-bit files and of the 64-bit processing, with no rounding and no dither. " +
            "Use 16-bit only if this output has trouble with it."
    )
    val rateIndex = OUTPUT_RATES.indexOf(st.outRate[key] ?: 1).coerceAtLeast(0)
    ChipRow("Sample Rate", "The sample rate PowerSound sends to this output", OUTPUT_RATE_LABELS, rateIndex) { i ->
        set { it.copy(outRate = if (i == 1) it.outRate - key else it.outRate + (key to OUTPUT_RATES[i])) }
    }
    Hint(
        "Native (recommended): the output's own rate (now ${engine.deviceRate / 1000.0} kHz). PowerSound converts once, " +
            "with its own 64-bit resampler, and catches the peaks this creates on loud recordings — so Android doesn't resample " +
            "again with its simpler converter and nothing clips. Follow Track: no conversion by PowerSound (Android may still convert). " +
            "A change starts with the next track."
    )
    SwitchRow("No Equ/Tone", "Disable the equalizer and tone for this output", key in st.outNoEq) { v ->
        set { it.copy(outNoEq = if (v) it.outNoEq + key else it.outNoEq - key) }
    }
    SwitchRow("No Duck", "Don't lower the volume for notifications on this output", key in st.outNoDuck) { v ->
        set { it.copy(outNoDuck = if (v) it.outNoDuck + key else it.outNoDuck - key) }
    }
    val delay = st.outDelayMs[key] ?: 0
    StepSliderRow("Visualization/Lyrics Delay", "Extra delay so the visualization and the lyrics match what you hear: $delay ms", delay / 50, 0, 20, "0", "1000 ms") { v ->
        set { it.copy(outDelayMs = it.outDelayMs + (key to v * 50)) }
    }
    if (pickPreset) {
        ChoiceDialog(
            "Equalizer Preset", presetNames,
            presetNames.indexOf(st.outEqPreset[key] ?: "None").coerceAtLeast(0),
            onPick = { i -> set { it.copy(outEqPreset = if (i == 0) it.outEqPreset - key else it.outEqPreset + (key to presetNames[i])) } },
            onDismiss = { pickPreset = false }
        )
    }
}

private val OUTPUT_LABEL = com.powersound.player.data.OUTPUTS.toMap()

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


/** Colour picker: saturation/value square, hue bar, hex field and a palette (Background Gradient Color). */
@Composable
private fun ColorPickerDialog(initial: Color, onPick: (Color) -> Unit, onDismiss: () -> Unit) {
    val hsv0 = remember {
        FloatArray(3).also { android.graphics.Color.colorToHSV(initial.toArgb(), it) }
    }
    var hue by remember { mutableStateOf(hsv0[0]) }
    var sat by remember { mutableStateOf(hsv0[1]) }
    var value by remember { mutableStateOf(hsv0[2]) }
    val color = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value)))
    var hex by remember(color) { mutableStateOf("%06X".format(color.toArgb() and 0xFFFFFF)) }
    val palette = listOf(
        0xFFFFFFFF, 0xFFD0D0D8, 0xFF9A9AB0, 0xFF5A5A6E, 0xFF2A2A36, 0xFF000000,
        0xFF0F2440, 0xFF1F4F9E, 0xFF4F9DFF, 0xFF3A1F5E, 0xFFB07CFF, 0xFF5E1F3A,
        0xFFFF4D5E, 0xFFFF8A1F, 0xFFE8C14A, 0xFF1F5E3A, 0xFF3DDC84, 0xFF4FB6C9
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1B1B22),
        text = {
            Column {
                Row(Modifier.fillMaxWidth().height(190.dp)) {
                    androidx.compose.foundation.Canvas(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(8.dp))
                            .pointerInput(Unit) {
                                detectDragGestures(
                                    onDragStart = { o -> sat = (o.x / size.width).coerceIn(0f, 1f); value = 1f - (o.y / size.height).coerceIn(0f, 1f) }
                                ) { ch, _ -> sat = (ch.position.x / size.width).coerceIn(0f, 1f); value = 1f - (ch.position.y / size.height).coerceIn(0f, 1f) }
                            }
                            .pointerInput(Unit) {
                                detectTapGestures { o ->
                                    sat = (o.x / size.width).coerceIn(0f, 1f); value = 1f - (o.y / size.height).coerceIn(0f, 1f)
                                }
                            }
                    ) {
                        val pure = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f)))
                        drawRect(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color.White, pure)))
                        drawRect(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                        drawCircle(Color.White, 8.dp.toPx(), androidx.compose.ui.geometry.Offset(sat * size.width, (1f - value) * size.height), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
                    }
                    Spacer(Modifier.width(10.dp))
                    androidx.compose.foundation.Canvas(
                        Modifier
                            .width(28.dp)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(6.dp))
                            .pointerInput(Unit) {
                                detectDragGestures(
                                    onDragStart = { o -> hue = (o.y / size.height).coerceIn(0f, 1f) * 360f }
                                ) { ch, _ -> hue = (ch.position.y / size.height).coerceIn(0f, 1f) * 360f }
                            }
                            .pointerInput(Unit) {
                                detectTapGestures { o -> hue = (o.y / size.height).coerceIn(0f, 1f) * 360f }
                            }
                    ) {
                        val stops = (0..6).map { Color(android.graphics.Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f))) }
                        drawRect(androidx.compose.ui.graphics.Brush.verticalGradient(stops))
                        val y = hue / 360f * size.height
                        drawLine(Color.White, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y), strokeWidth = 3.dp.toPx())
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(width = 46.dp, height = 26.dp).clip(RoundedCornerShape(50)).background(color).border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(50)))
                    Spacer(Modifier.width(12.dp))
                    Text("#", color = Color.White)
                    BasicTextField(
                        value = hex,
                        onValueChange = { t ->
                            val clean = t.uppercase().filter { it in "0123456789ABCDEF" }.take(6)
                            hex = clean
                            if (clean.length == 6) {
                                val c = (0xFF000000 or clean.toLong(16)).toInt()
                                val h = FloatArray(3).also { android.graphics.Color.colorToHSV(c, it) }
                                hue = h[0]; sat = h[1]; value = h[2]
                            }
                        },
                        singleLine = true,
                        textStyle = TextStyle(color = Color.White, fontSize = 18.sp),
                        cursorBrush = SolidColor(Color.White),
                        modifier = Modifier.width(110.dp)
                    )
                }
                Spacer(Modifier.height(10.dp))
                FlowRowPalette(palette.map { Color(it) }) { c ->
                    val h = FloatArray(3).also { android.graphics.Color.colorToHSV(c.toArgb(), it) }
                    hue = h[0]; sat = h[1]; value = h[2]
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(color); onDismiss() }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowPalette(colors: List<Color>, onPick: (Color) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        colors.forEach { c ->
            Box(
                Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(c)
                    .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape)
                    .clickable { onPick(c) }
            )
        }
    }
}

// ---------------------------------------------------------------- pages (one function each, keeps the methods small)

private class PageArgs(
    val st: AppSettings,
    val d: AppSettings,
    val allFolders: List<String>,
    val onPage: (SettingsPage?) -> Unit,
    val onRescan: () -> Unit,
    val onAudioInfo: () -> Unit,
    val onImportAutoEq: () -> Unit,
    val onCheckUpdate: () -> Unit,
    val onChangelog: () -> Unit,
    val onExport: () -> Unit,
    val onImport: () -> Unit,
    val lib: LibraryActions
)

/** What Settings → Library needs from the app (counts, rescans, playlist files). */
class LibraryActions(
    val counts: String,
    val fullRescan: () -> Unit,
    val resetStats: () -> Unit,
    val importSystemPlaylists: () -> Unit,
    val exportPlaylists: () -> Unit,
    val importM3u: () -> Unit
)

/** page == SettingsPage.LOOK */
@Suppress("UNUSED_VARIABLE", "UNUSED_PARAMETER")
@Composable
private fun SettingsPage1(page: SettingsPage?, args: PageArgs) {
    val st = args.st
    val d = args.d
    val sc = LocalSc.current
    val context = LocalContext.current
    val allFolders = args.allFolders
    val onPage = args.onPage
    val onRescan = args.onRescan
    val onAudioInfo = args.onAudioInfo
    val onImportAutoEq = args.onImportAutoEq
    val onCheckUpdate = args.onCheckUpdate
    val onChangelog = args.onChangelog
    val onExport = args.onExport
    val onImport = args.onImport
    fun set(change: (AppSettings) -> AppSettings) = SettingsStore.update(change)
    Column(Modifier.fillMaxWidth()) {
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
    Hint("List options like Filename As Title and Show Track Number are in Library → Lists")

    SectionLabel("Misc")
    ChoiceRow("Language", listOf("Auto", "English"), st.language) { v -> set { it.copy(language = v) } }
    PlainRow("Icon", "Set launcher icon") { onPage(SettingsPage.ICON) }
    ChipRow("Screen Orientation", null, listOf("Default", "Portrait (Vertical)", "Landscape (Horizontal)"), st.orientation) { v ->
        set { it.copy(orientation = v) }
    }
    ChipRow("Animations", "Disable/enable or change UI animation speed where possible", listOf("Disabled", "Fast", "Default"), st.animations) { v ->
        set { it.copy(animations = v) }
    }
    Hint("PowerSound always opens on Library → Folders")
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
}

/** page == SettingsPage.SKIN */
@Suppress("UNUSED_VARIABLE", "UNUSED_PARAMETER")
@Composable
private fun SettingsPage2(page: SettingsPage?, args: PageArgs) {
    val st = args.st
    val d = args.d
    val sc = LocalSc.current
    val context = LocalContext.current
    val allFolders = args.allFolders
    val onPage = args.onPage
    val onRescan = args.onRescan
    val onAudioInfo = args.onAudioInfo
    val onImportAutoEq = args.onImportAutoEq
    val onCheckUpdate = args.onCheckUpdate
    val onChangelog = args.onChangelog
    val onExport = args.onExport
    val onImport = args.onImport
    fun set(change: (AppSettings) -> AppSettings) = SettingsStore.update(change)
    Column(Modifier.fillMaxWidth()) {
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
    Hint("The menu button and the rating are set in Look and Feel → Player Screen, the lyrics button in Look and Feel → Lyrics")
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
    ChipRow(
        "Seekbar Style",
        "Default: the song's waveform moves to the left, about a minute of it, now in the middle. Static: the whole song at once",
        listOf("Default", "Static Seekbar", "Simple Seekbar (with Pro Buttons)"), st.seekbarStyle
    ) { v ->
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
}

/** page == SettingsPage.PLAYER_SCREEN */
@Suppress("UNUSED_VARIABLE", "UNUSED_PARAMETER")
@Composable
private fun SettingsPage3(page: SettingsPage?, args: PageArgs) {
    val st = args.st
    val d = args.d
    val sc = LocalSc.current
    val context = LocalContext.current
    val allFolders = args.allFolders
    val onPage = args.onPage
    val onRescan = args.onRescan
    val onAudioInfo = args.onAudioInfo
    val onImportAutoEq = args.onImportAutoEq
    val onCheckUpdate = args.onCheckUpdate
    val onChangelog = args.onChangelog
    val onExport = args.onExport
    val onImport = args.onImport
    fun set(change: (AppSettings) -> AppSettings) = SettingsStore.update(change)
    Column(Modifier.fillMaxWidth()) {
    var editButtons by remember { mutableStateOf(false) }
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
    Hint("-10/+10s Rewind Pro Buttons for long tracks are in Library → Advanced")
    SwitchRow("Navigate to the Folders", "Track menu → Folder opens the Folders category instead of the Folders Hierarchy", st.navigateToFolders) { v ->
        set { it.copy(navigateToFolders = v) }
    }
    RestoreRow {
        set {
            it.copy(
                artAnimation = d.artAnimation, marquee = d.marquee, marqueeEverywhere = d.marqueeEverywhere,
                playerButtons = d.playerButtons, preferNoGap = d.preferNoGap, noEditLongPress = d.noEditLongPress,
                ratingType = d.ratingType, menuButton = d.menuButton, menuLongPress = d.menuLongPress,
                line2Press = d.line2Press, line2LongPress = d.line2LongPress,
                prevResets = d.prevResets, navigateToFolders = d.navigateToFolders
            )
        }
    }
    if (editButtons) EditPlayerButtonsDialog(st.playerButtons, onSave = { list -> set { it.copy(playerButtons = list) } }) { editButtons = false }
    }
}

/** page == SettingsPage.LYRICS */
@Suppress("UNUSED_VARIABLE", "UNUSED_PARAMETER")
@Composable
private fun SettingsPage4(page: SettingsPage?, args: PageArgs) {
    val st = args.st
    val d = args.d
    val sc = LocalSc.current
    val context = LocalContext.current
    val allFolders = args.allFolders
    val onPage = args.onPage
    val onRescan = args.onRescan
    val onAudioInfo = args.onAudioInfo
    val onImportAutoEq = args.onImportAutoEq
    val onCheckUpdate = args.onCheckUpdate
    val onChangelog = args.onChangelog
    val onExport = args.onExport
    val onImport = args.onImport
    fun set(change: (AppSettings) -> AppSettings) = SettingsStore.update(change)
    Column(Modifier.fillMaxWidth()) {
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
        "If enabled, PowerSound looks for .lrc files in the music folders with the same name as the track",
        st.scanLrc
    ) { v ->
        set { it.copy(scanLrc = v) }
        LyricsReader.clearCache()
    }
    if (!allFiles) Hint("Reading .lrc files needs All Files Access — allow it in Library → All Files Access")
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
}

/** page == SettingsPage.NOTIFICATIONS */
@Suppress("UNUSED_VARIABLE", "UNUSED_PARAMETER")
@Composable
private fun SettingsPage5(page: SettingsPage?, args: PageArgs) {
    val st = args.st
    val d = args.d
    val sc = LocalSc.current
    val context = LocalContext.current
    val allFolders = args.allFolders
    val onPage = args.onPage
    val onRescan = args.onRescan
    val onAudioInfo = args.onAudioInfo
    val onImportAutoEq = args.onImportAutoEq
    val onCheckUpdate = args.onCheckUpdate
    val onChangelog = args.onChangelog
    val onExport = args.onExport
    val onImport = args.onImport
    fun set(change: (AppSettings) -> AppSettings) = SettingsStore.update(change)
    Column(Modifier.fillMaxWidth()) {
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
}

/** page == SettingsPage.NOTIF_BUTTONS */
@Suppress("UNUSED_VARIABLE", "UNUSED_PARAMETER")
@Composable
private fun SettingsPage6(page: SettingsPage?, args: PageArgs) {
    val st = args.st
    val d = args.d
    val sc = LocalSc.current
    val context = LocalContext.current
    val allFolders = args.allFolders
    val onPage = args.onPage
    val onRescan = args.onRescan
    val onAudioInfo = args.onAudioInfo
    val onImportAutoEq = args.onImportAutoEq
    val onCheckUpdate = args.onCheckUpdate
    val onChangelog = args.onChangelog
    val onExport = args.onExport
    val onImport = args.onImport
    fun set(change: (AppSettings) -> AppSettings) = SettingsStore.update(change)
    Column(Modifier.fillMaxWidth()) {
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
}

/** page == SettingsPage.ICON */
@Suppress("UNUSED_VARIABLE", "UNUSED_PARAMETER")
@Composable
private fun SettingsPage7(page: SettingsPage?, args: PageArgs) {
    val st = args.st
    val d = args.d
    val sc = LocalSc.current
    val context = LocalContext.current
    val allFolders = args.allFolders
    val onPage = args.onPage
    val onRescan = args.onRescan
    val onAudioInfo = args.onAudioInfo
    val onImportAutoEq = args.onImportAutoEq
    val onCheckUpdate = args.onCheckUpdate
    val onChangelog = args.onChangelog
    val onExport = args.onExport
    val onImport = args.onImport
    fun set(change: (AppSettings) -> AppSettings) = SettingsStore.update(change)
    Column(Modifier.fillMaxWidth()) {
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
}

/** page == null */
@Suppress("UNUSED_VARIABLE", "UNUSED_PARAMETER")
@Composable
private fun SettingsPage9(page: SettingsPage?, args: PageArgs) {
    val st = args.st
    val d = args.d
    val sc = LocalSc.current
    val context = LocalContext.current
    val allFolders = args.allFolders
    val onPage = args.onPage
    val onRescan = args.onRescan
    val onAudioInfo = args.onAudioInfo
    val onImportAutoEq = args.onImportAutoEq
    val onCheckUpdate = args.onCheckUpdate
    val onChangelog = args.onChangelog
    val onExport = args.onExport
    val onImport = args.onImport
    fun set(change: (AppSettings) -> AppSettings) = SettingsStore.update(change)
    Column(Modifier.fillMaxWidth()) {
    SettingsPage.values().filter { it.parent == null }.forEach { p -> NavRow(p.icon, p.color, p.title, p.subtitle) { onPage(p) } }
    SectionLabel("Other")
    NavRow(Icons.Rounded.Upload, Color(0xFF9A9AB0), "Export Settings/Data", "Save EQ, presets, playlists, bookmarks and settings to a file", onExport)
    NavRow(Icons.Rounded.Download, Color(0xFF9A9AB0), "Import Settings/Data", "Restore from a file saved with Export", onImport)
    }
}

/** page == SettingsPage.AUDIO */
@Suppress("UNUSED_VARIABLE", "UNUSED_PARAMETER")
@Composable
private fun SettingsPage10(page: SettingsPage?, args: PageArgs) {
    val st = args.st
    val d = args.d
    val sc = LocalSc.current
    val context = LocalContext.current
    val allFolders = args.allFolders
    val onPage = args.onPage
    val onRescan = args.onRescan
    val onAudioInfo = args.onAudioInfo
    val onImportAutoEq = args.onImportAutoEq
    val onCheckUpdate = args.onCheckUpdate
    val onChangelog = args.onChangelog
    val onExport = args.onExport
    val onImport = args.onImport
    fun set(change: (AppSettings) -> AppSettings) = SettingsStore.update(change)
    Column(Modifier.fillMaxWidth()) {
    PlainRow("Audio Info", "Detailed info about the audio processing. Also available by long pressing the small info line on the Player Screen", onClick = onAudioInfo)
    PlainRow("Crossfade, Fade, and Gapless", "Crossfade options, fade type and length, gapless") { onPage(SettingsPage.CROSSFADE) }
    PlainRow("Replay Gain (RG)", "Enable RG, set source, preamp values") { onPage(SettingsPage.REPLAY_GAIN) }
    PlainRow("Audio Focus", "Pause/Resume/Duck volume on calls/notifications/start") { onPage(SettingsPage.AUDIO_FOCUS) }
    PlainRow("Equalizer", "Equalizer settings, number of bands, frequencies") { onPage(SettingsPage.EQ_SETTINGS) }
    PlainRow("Resampler", "Resampling/dither settings") { onPage(SettingsPage.RESAMPLER) }
    PlainRow("Audio Outputs", "Wired Headset, Speaker, Bluetooth, USB and other outputs") { onPage(SettingsPage.OUTPUTS) }
    PlainRow("Bit Perfect", "Hi-Res 64-bit audio path, Perfect Bit Perfect") { onPage(SettingsPage.BIT_PERFECT) }
    PlainRow("Advanced Tweaks", "Pause/resume on volume, MusicFX, presets reset") { onPage(SettingsPage.TWEAKS) }
    }
}

/** page == SettingsPage.CROSSFADE */
@Suppress("UNUSED_VARIABLE", "UNUSED_PARAMETER")
@Composable
private fun SettingsPage11(page: SettingsPage?, args: PageArgs) {
    val st = args.st
    val d = args.d
    val sc = LocalSc.current
    val context = LocalContext.current
    val allFolders = args.allFolders
    val onPage = args.onPage
    val onRescan = args.onRescan
    val onAudioInfo = args.onAudioInfo
    val onImportAutoEq = args.onImportAutoEq
    val onCheckUpdate = args.onCheckUpdate
    val onChangelog = args.onChangelog
    val onExport = args.onExport
    val onImport = args.onImport
    fun set(change: (AppSettings) -> AppSettings) = SettingsStore.update(change)
    Column(Modifier.fillMaxWidth()) {
    ChipRow(
        "Auto-advance Fading", "Applied when the song changes automatically",
        listOf("No fading", "Non-gapless tracks", "All songs", "Shuffled songs"), st.autoFade
    ) { v -> set { it.copy(autoFade = v) } }
    ChipRow(
        "Manual Track Change Fading", "Applied when you change the song (next / previous)",
        listOf("No fading", "Short crossfade", "Crossfade"), st.manualFade
    ) { v -> set { it.copy(manualFade = v) } }
    SliderRow(
        "Crossfade Length", "%.1f s".format(st.crossfadeSec), st.crossfadeSec, 1f..12f,
        enabled = st.autoFade != 0 || st.manualFade == 2,
        subtitle = "How long the old and the new song overlap"
    ) { v -> set { it.copy(crossfadeSec = (v * 2).roundToInt() / 2f) } }
    NoteBlock(
        "\"Non-gapless tracks\" keeps albums that play in order gapless and crossfades everything else",
        "Perfect Bit Perfect disables all fading effects",
        "Crossfade is skipped when the song is the last one (repeat \"song and stop\", sleep timer)"
    )
    SwitchRow("Fade Play/Pause/Stop", "Smooth volume fade instead of a hard stop", st.fadeOnPause) { v -> set { it.copy(fadeOnPause = v) } }
    SliderRow("Fade Length", "${st.fadeMs} ms", st.fadeMs.toFloat(), 100f..1500f, enabled = st.fadeOnPause) { v ->
        set { it.copy(fadeMs = ((v / 50).roundToInt() * 50)) }
    }
    SwitchRow("Fade on Seek", "A very short fade when you jump in the song, so there is no click", st.fadeOnSeek) { v -> set { it.copy(fadeOnSeek = v) } }
    StepSliderRow(
        "Silence Between Tracks", if (st.silenceMs == 0) "0 ms (gapless)" else "${st.silenceMs} ms",
        st.silenceMs / 250, 0, 20, "None", "5 s"
    ) { v -> set { it.copy(silenceMs = v * 250) } }
    Hint("Gapless playback is always on when there is no silence and no crossfade. Silence turns crossfade off.")
    RestoreRow {
        set {
            it.copy(
                autoFade = d.autoFade, manualFade = d.manualFade, crossfadeSec = d.crossfadeSec, fadeOnPause = d.fadeOnPause,
                fadeMs = d.fadeMs, fadeOnSeek = d.fadeOnSeek, silenceMs = d.silenceMs
            )
        }
    }
    }
}

/** page == SettingsPage.REPLAY_GAIN */
@Suppress("UNUSED_VARIABLE", "UNUSED_PARAMETER")
@Composable
private fun SettingsPage12(page: SettingsPage?, args: PageArgs) {
    val st = args.st
    val d = args.d
    val sc = LocalSc.current
    val context = LocalContext.current
    val allFolders = args.allFolders
    val onPage = args.onPage
    val onRescan = args.onRescan
    val onAudioInfo = args.onAudioInfo
    val onImportAutoEq = args.onImportAutoEq
    val onCheckUpdate = args.onCheckUpdate
    val onChangelog = args.onChangelog
    val onExport = args.onExport
    val onImport = args.onImport
    fun set(change: (AppSettings) -> AppSettings) = SettingsStore.update(change)
    Column(Modifier.fillMaxWidth()) {
    ChipRow(
        "Replay Gain", null,
        listOf("Don't apply", "Apply gain", "Apply gain / prevent clipping according to the peak"), st.replayGain
    ) { v -> set { it.copy(replayGain = v) } }
    ChipRow("Source", null, listOf("Album", "Track"), st.rgSource) { v -> set { it.copy(rgSource = v) } }
    KnobRow(
        "RG preamp", null, "%+.1fdB".format(st.replayGainPreamp),
        ((st.replayGainPreamp + 15) / 30).toFloat(), enabled = st.replayGain != 0,
        onChange = { f -> set { it.copy(replayGainPreamp = ((f * 30 - 15) * 2).roundToInt() / 2.0) } },
        onReset = { set { it.copy(replayGainPreamp = 0.0) } }
    )
    KnobRow(
        "Preamp for songs without RG info", null, "%+.1fdB".format(st.replayGainNoTagDb),
        ((st.replayGainNoTagDb + 15) / 30).toFloat(), enabled = st.replayGain != 0,
        onChange = { f -> set { it.copy(replayGainNoTagDb = ((f * 30 - 15) * 2).roundToInt() / 2.0) } },
        onReset = { set { it.copy(replayGainNoTagDb = 0.0) } }
    )
    Hint("Reads REPLAYGAIN_ALBUM/TRACK_GAIN and _PEAK tags from FLAC and MP3 files. Drag a knob up/down, double-tap to reset.")
    RestoreRow {
        set { it.copy(replayGain = d.replayGain, rgSource = d.rgSource, replayGainPreamp = d.replayGainPreamp, replayGainNoTagDb = d.replayGainNoTagDb) }
    }
    }
}

/** page == SettingsPage.AUDIO_FOCUS */
@Suppress("UNUSED_VARIABLE", "UNUSED_PARAMETER")
@Composable
private fun SettingsPage13(page: SettingsPage?, args: PageArgs) {
    val st = args.st
    val d = args.d
    val sc = LocalSc.current
    val context = LocalContext.current
    val allFolders = args.allFolders
    val onPage = args.onPage
    val onRescan = args.onRescan
    val onAudioInfo = args.onAudioInfo
    val onImportAutoEq = args.onImportAutoEq
    val onCheckUpdate = args.onCheckUpdate
    val onChangelog = args.onChangelog
    val onExport = args.onExport
    val onImport = args.onImport
    fun set(change: (AppSettings) -> AppSettings) = SettingsStore.update(change)
    Column(Modifier.fillMaxWidth()) {
    SwitchRow("Resume After Call", "Resume playing on hang up (if paused by a call)", st.resumeAfterCall) { v -> set { it.copy(resumeAfterCall = v) } }
    SwitchRow("Resume On Start", "Resume playing when PowerSound is started", st.resumeOnStart) { v -> set { it.copy(resumeOnStart = v) } }
    SwitchRow(
        "Resume On Reopen", "Also resume when PowerSound is already running and reopened" +
            if (!st.resumeOnStart) " (needs Resume On Start)" else "",
        st.resumeOnReopen
    ) { v -> set { it.copy(resumeOnReopen = v) } }
    SectionLabel("Audio Focus")
    NoteBlock("Audio focus decides what happens when another app or a call needs the sound")
    SwitchRow(
        "Short Audio Focus Change / Calls",
        "Temporarily pause on short audio focus changes (notifications, navigation, voice messages…). Calls always pause",
        st.pauseOnShortFocus
    ) { v -> set { it.copy(pauseOnShortFocus = v) } }
    SwitchRow("Resume On Focus Gain", "Resume after getting the focus back. If disabled, the player stays paused", st.resumeOnFocusGain) { v ->
        set { it.copy(resumeOnFocusGain = v) }
    }
    SwitchRow(
        "Duck Volume", "When possible, lower the volume on short audio focus instead of pausing. Can be turned off per output (Audio Outputs → No Duck)",
        st.duckVolume
    ) { v -> set { it.copy(duckVolume = v) } }
    SwitchRow("Permanent Audio Focus Change", "Pause when another player, game, etc. takes over the sound", st.pauseOnPermanentFocus) { v ->
        set { it.copy(pauseOnPermanentFocus = v) }
    }
    RestoreRow {
        set {
            it.copy(
                resumeAfterCall = d.resumeAfterCall, resumeOnStart = d.resumeOnStart, resumeOnReopen = d.resumeOnReopen,
                pauseOnShortFocus = d.pauseOnShortFocus, resumeOnFocusGain = d.resumeOnFocusGain,
                duckVolume = d.duckVolume, pauseOnPermanentFocus = d.pauseOnPermanentFocus
            )
        }
    }
    }
}

/** page == SettingsPage.EQ_SETTINGS */
@Suppress("UNUSED_VARIABLE", "UNUSED_PARAMETER")
@Composable
private fun SettingsPage14(page: SettingsPage?, args: PageArgs) {
    val st = args.st
    val d = args.d
    val sc = LocalSc.current
    val context = LocalContext.current
    val allFolders = args.allFolders
    val onPage = args.onPage
    val onRescan = args.onRescan
    val onAudioInfo = args.onAudioInfo
    val onImportAutoEq = args.onImportAutoEq
    val onCheckUpdate = args.onCheckUpdate
    val onChangelog = args.onChangelog
    val onExport = args.onExport
    val onImport = args.onImport
    fun set(change: (AppSettings) -> AppSettings) = SettingsStore.update(change)
    Column(Modifier.fillMaxWidth()) {
    val eq by com.powersound.player.audio.AudioEngine.settings.collectAsState()
    var confirmRestore by remember { mutableStateOf(false) }
    NoteBlock(
        "Depending on the loaded preset, the equalizer is in Graphic or Parametric mode",
        "Graphic mode: 5 to 32 bands spread over the whole range",
        "Parametric mode (e.g. AutoEq presets): every band has its own frequency, gain, Q and type"
    )
    PlainRow("Graphic Equalizer Bands", "${eq.bands.size} bands • set number of bands, frequencies") { onPage(SettingsPage.EQ_BANDS) }
    PlainRow("Tone", "Set Bass/Treble frequencies and Q factors") { onPage(SettingsPage.TONE) }
    ChipRow("Equalizer Values", null, listOf("Hidden", "dB", "%"), st.eqValues) { v -> set { it.copy(eqValues = v) } }
    ChipRow("Tone Values", null, listOf("Hidden", "dB", "%"), st.toneValues) { v -> set { it.copy(toneValues = v) } }
    SwitchRow("Auto Save", "Changes are saved into the loaded user preset", st.eqAutoSave) { v -> set { it.copy(eqAutoSave = v) } }
    SwitchRow(
        "Smooth Equalizer/Tone Gains",
        "Automatically lower the volume by the biggest boost (minus what the preset's own preamp already lowers), so boosts never overload and the limiter rarely has to act",
        eq.autoHeadroom
    ) { v -> com.powersound.player.audio.AudioEngine.update { it.copy(autoHeadroom = v) } }
    PlainRow("Import AutoEq Presets", "Import \"ParametricEQ.txt\" files from the AutoEq project (e.g. for your earbuds). \"Liberty 4 NC (AutoEq)\" is built in", onClick = onImportAutoEq)
    PlainRow("Restore Equalizer Presets", "Imported AutoEq presets are removed, built-in presets stay") { confirmRestore = true }
    RestoreRow {
        set {
            it.copy(
                eqValues = d.eqValues, toneValues = d.toneValues, eqAutoSave = d.eqAutoSave, eqBandCount = d.eqBandCount,
                eqCustomBands = d.eqCustomBands, eqCustomCount = d.eqCustomCount, eqFirstFreq = d.eqFirstFreq, eqLastFreq = d.eqLastFreq
            )
        }
        com.powersound.player.audio.AudioEngine.update { it.copy(autoHeadroom = true) }
    }
    if (confirmRestore) ConfirmDialog(
        "Restore Equalizer Presets", "Imported AutoEq presets will be removed.",
        onOk = { com.powersound.player.audio.AudioEngine.removeAutoEqPresets() }
    ) { confirmRestore = false }
    }
}

/** page == SettingsPage.EQ_BANDS */
@Suppress("UNUSED_VARIABLE", "UNUSED_PARAMETER")
@Composable
private fun SettingsPage15(page: SettingsPage?, args: PageArgs) {
    val st = args.st
    val d = args.d
    val sc = LocalSc.current
    val context = LocalContext.current
    val allFolders = args.allFolders
    val onPage = args.onPage
    val onRescan = args.onRescan
    val onAudioInfo = args.onAudioInfo
    val onImportAutoEq = args.onImportAutoEq
    val onCheckUpdate = args.onCheckUpdate
    val onChangelog = args.onChangelog
    val onExport = args.onExport
    val onImport = args.onImport
    fun set(change: (AppSettings) -> AppSettings) = SettingsStore.update(change)
    Column(Modifier.fillMaxWidth()) {
    val counts = listOf(5, 10, 12, 16, 24, 31)
    ChipRow(
        "Number of Bands", "Predefined number of bands, including the recommended band spread",
        listOf("5", "10 (ISO)", "12", "16", "24", "31 (ISO)"), if (st.eqCustomBands) -1 else counts.indexOf(st.eqBandCount)
    ) { i -> set { it.copy(eqBandCount = counts[i], eqCustomBands = false) } }
    SectionLabel("Advanced")
    SwitchRow("Enable Custom Bands", "Your own number of bands and frequency range", st.eqCustomBands) { v -> set { it.copy(eqCustomBands = v) } }
    StepSliderRow("Custom Number of Bands", "${st.eqCustomCount}", st.eqCustomCount, 5, 32, "5", "32", enabled = st.eqCustomBands) { v ->
        set { it.copy(eqCustomCount = v) }
    }
    val firsts = listOf(20, 25, 31, 40, 50, 63, 80, 100)
    StepSliderRow("First Frequency", "${st.eqFirstFreq} Hz", firsts.indexOf(st.eqFirstFreq).coerceAtLeast(0), 0, firsts.lastIndex, "20 Hz", "100 Hz", enabled = st.eqCustomBands) { i ->
        set { it.copy(eqFirstFreq = firsts[i]) }
    }
    val lasts = listOf(8000, 10000, 12000, 14000, 16000, 18000, 20000)
    StepSliderRow("Last Frequency", "%.1f kHz".format(st.eqLastFreq / 1000.0), lasts.indexOf(st.eqLastFreq).coerceAtLeast(0), 0, lasts.lastIndex, "8 kHz", "20 kHz", enabled = st.eqCustomBands) { i ->
        set { it.copy(eqLastFreq = lasts[i]) }
    }
    Hint("Changing the bands keeps your current curve as well as possible. Tap a frequency on the Equalizer to change one band.")
    }
}

/** page == SettingsPage.TONE */
@Suppress("UNUSED_VARIABLE", "UNUSED_PARAMETER")
@Composable
private fun SettingsPage16(page: SettingsPage?, args: PageArgs) {
    val st = args.st
    val d = args.d
    val sc = LocalSc.current
    val context = LocalContext.current
    val allFolders = args.allFolders
    val onPage = args.onPage
    val onRescan = args.onRescan
    val onAudioInfo = args.onAudioInfo
    val onImportAutoEq = args.onImportAutoEq
    val onCheckUpdate = args.onCheckUpdate
    val onChangelog = args.onChangelog
    val onExport = args.onExport
    val onImport = args.onImport
    fun set(change: (AppSettings) -> AppSettings) = SettingsStore.update(change)
    Column(Modifier.fillMaxWidth()) {
    val eq by com.powersound.player.audio.AudioEngine.settings.collectAsState()
    fun tone(change: (com.powersound.player.audio.EqSettings) -> com.powersound.player.audio.EqSettings) = com.powersound.player.audio.AudioEngine.update(change)
    val bassFreqs = listOf(40.0, 50.0, 60.0, 70.0, 80.0, 90.0, 100.0, 120.0, 150.0, 200.0, 250.0, 300.0)
    StepSliderRow(
        "Bass Frequency", "${eq.bassFreq.roundToInt()} Hz", bassFreqs.indexOfFirst { it >= eq.bassFreq }.coerceAtLeast(0), 0, bassFreqs.lastIndex, "40 Hz", "300 Hz"
    ) { i -> tone { it.copy(bassFreq = bassFreqs[i]) } }
    SliderRow("Bass Q", "%.2f".format(eq.bassQ), eq.bassQ.toFloat(), 0.3f..2f) { v -> tone { it.copy(bassQ = (v * 20).roundToInt() / 20.0) } }
    val trebleFreqs = listOf(3000.0, 4000.0, 5000.0, 6000.0, 7000.0, 8000.0, 10000.0, 12000.0, 14000.0, 16000.0)
    StepSliderRow(
        "Treble Frequency", "%.1f kHz".format(eq.trebleFreq / 1000), trebleFreqs.indexOfFirst { it >= eq.trebleFreq }.coerceAtLeast(0), 0, trebleFreqs.lastIndex, "3 kHz", "16 kHz"
    ) { i -> tone { it.copy(trebleFreq = trebleFreqs[i]) } }
    SliderRow("Treble Q", "%.2f".format(eq.trebleQ), eq.trebleQ.toFloat(), 0.3f..2f) { v -> tone { it.copy(trebleQ = (v * 20).roundToInt() / 20.0) } }
    Hint("Bass and Treble are shelf filters controlled by the knobs on the Equalizer.")
    RestoreRow {
        tone {
            it.copy(
                bassFreq = com.powersound.player.audio.BASS_FREQ, bassQ = com.powersound.player.audio.SHELF_Q,
                trebleFreq = com.powersound.player.audio.TREBLE_FREQ, trebleQ = com.powersound.player.audio.SHELF_Q
            )
        }
    }
    }
}

/** page == SettingsPage.RESAMPLER */
@Suppress("UNUSED_VARIABLE", "UNUSED_PARAMETER")
@Composable
private fun SettingsPage17(page: SettingsPage?, args: PageArgs) {
    val st = args.st
    val d = args.d
    val sc = LocalSc.current
    val context = LocalContext.current
    val allFolders = args.allFolders
    val onPage = args.onPage
    val onRescan = args.onRescan
    val onAudioInfo = args.onAudioInfo
    val onImportAutoEq = args.onImportAutoEq
    val onCheckUpdate = args.onCheckUpdate
    val onChangelog = args.onChangelog
    val onExport = args.onExport
    val onImport = args.onImport
    fun set(change: (AppSettings) -> AppSettings) = SettingsStore.update(change)
    Column(Modifier.fillMaxWidth()) {
    NoteBlock(
        "PowerSound converts a track to the output's own rate (Audio Outputs → output → Sample Rate → Native), once, in 64-bit",
        "High quality: 130 dB stop band, distortion below -134 dB. Very high quality: 160 dB, below -160 dB — " +
            "both far under what a 24-bit file can hold. Very high uses a little more battery"
    )
    ChipRow("Resampler Type", null, listOf("High quality", "Very high quality"), st.resamplerType) { v -> set { it.copy(resamplerType = v) } }
    KnobRow(
        "Resampler Cutoff Frequency Ratio", "Higher keeps more treble, lower keeps the filter cleaner",
        "%.1f%%".format(st.resamplerCutoff * 100), (st.resamplerCutoff - 0.90f) / 0.09f, bipolar = false,
        onChange = { f -> set { it.copy(resamplerCutoff = (0.90f + f * 0.09f).let { v -> (v * 1000).roundToInt() / 1000f }) } },
        onReset = { set { it.copy(resamplerCutoff = d.resamplerCutoff) } }
    )
    ChoiceRow("Dither", com.powersound.player.audio.Ditherer.NAMES, st.dither) { v -> set { it.copy(dither = v) } }
    Hint("Dither is only used for outputs set to 16-bit (Audio Outputs → output → Output Format). The default 32-bit float output needs none. Triangular is a safe choice; noise shaping moves the noise to frequencies the ear hears less.")
    RestoreRow { set { it.copy(resamplerType = d.resamplerType, resamplerCutoff = d.resamplerCutoff, dither = d.dither) } }
    }
}

/** page == SettingsPage.OUTPUTS */
@Suppress("UNUSED_VARIABLE", "UNUSED_PARAMETER")
@Composable
private fun SettingsPage18(page: SettingsPage?, args: PageArgs) {
    val st = args.st
    val d = args.d
    val sc = LocalSc.current
    val context = LocalContext.current
    val allFolders = args.allFolders
    val onPage = args.onPage
    val onRescan = args.onRescan
    val onAudioInfo = args.onAudioInfo
    val onImportAutoEq = args.onImportAutoEq
    val onCheckUpdate = args.onCheckUpdate
    val onChangelog = args.onChangelog
    val onExport = args.onExport
    val onImport = args.onImport
    fun set(change: (AppSettings) -> AppSettings) = SettingsStore.update(change)
    Column(Modifier.fillMaxWidth()) {
    NoteBlock(
        "Every output has its own settings: equalizer preset, sample rate, no equalizer, no ducking and visualization/lyrics delay",
        "Now playing on: ${com.powersound.player.audio.AudioEngine.currentOutput}"
    )
    OUTPUT_PAGES.forEach { (key, p) ->
        val active = com.powersound.player.audio.AudioEngine.outputKey == key
        PlainRow(p.title, outputSummary(st, key) + if (active) "\nActive" else "") { onPage(p) }
    }
    RestoreRow {
        set { it.copy(outEqPreset = d.outEqPreset, outNoEq = d.outNoEq, outNoDuck = d.outNoDuck, outDelayMs = d.outDelayMs, outRate = d.outRate) }
    }
    }
}

/** page in OUTPUT_PAGES.map { it.second } */
@Suppress("UNUSED_VARIABLE", "UNUSED_PARAMETER")
@Composable
private fun SettingsPage19(page: SettingsPage?, args: PageArgs) {
    val st = args.st
    val d = args.d
    val sc = LocalSc.current
    val context = LocalContext.current
    val allFolders = args.allFolders
    val onPage = args.onPage
    val onRescan = args.onRescan
    val onAudioInfo = args.onAudioInfo
    val onImportAutoEq = args.onImportAutoEq
    val onCheckUpdate = args.onCheckUpdate
    val onChangelog = args.onChangelog
    val onExport = args.onExport
    val onImport = args.onImport
    fun set(change: (AppSettings) -> AppSettings) = SettingsStore.update(change)
    Column(Modifier.fillMaxWidth()) {
    val key = OUTPUT_PAGES.first { it.second == page }.first
    OutputPage(st, key, ::set)
    RestoreRow {
        set {
            it.copy(
                outEqPreset = it.outEqPreset - key, outNoEq = it.outNoEq - key, outNoDuck = it.outNoDuck - key,
                outDelayMs = (it.outDelayMs - key).let { m -> val dv = d.outDelayMs[key]; if (dv != null) m + (key to dv) else m },
                outRate = it.outRate - key, outFormat = it.outFormat - key
            )
        }
    }
    }
}

/** page == SettingsPage.BIT_PERFECT */
@Suppress("UNUSED_VARIABLE", "UNUSED_PARAMETER")
@Composable
private fun SettingsPage20(page: SettingsPage?, args: PageArgs) {
    val st = args.st
    val d = args.d
    val sc = LocalSc.current
    val context = LocalContext.current
    val allFolders = args.allFolders
    val onPage = args.onPage
    val onRescan = args.onRescan
    val onAudioInfo = args.onAudioInfo
    val onImportAutoEq = args.onImportAutoEq
    val onCheckUpdate = args.onCheckUpdate
    val onChangelog = args.onChangelog
    val onExport = args.onExport
    val onImport = args.onImport
    fun set(change: (AppSettings) -> AppSettings) = SettingsStore.update(change)
    Column(Modifier.fillMaxWidth()) {
    NoteBlock(
        "The decoder delivers 32-bit float (24-bit FLAC keeps all 24 bits), PowerSound processes in 64-bit floating point and " +
            "sends 32-bit float to Android",
        "When nothing changes the sound, every sample passes bit-for-bit. A look-ahead limiter only acts on peaks above 0 dB " +
            "(EQ boosts, Replay Gain, resampling) and never bends the waveform"
    )
    SwitchRow(
        "Perfect Bit Perfect",
        "Direct decoder-to-output path: no Equalizer, no Tone, no effects, no Replay Gain, no resampling, no fades",
        st.perfectBitPerfect
    ) { v -> set { it.copy(perfectBitPerfect = v) } }
    NoteBlock("Android still mixes the sound for the Bluetooth codec or the speaker; use a USB DAC for the cleanest path")
    RestoreRow { set { it.copy(perfectBitPerfect = d.perfectBitPerfect) } }
    }
}

/** page == SettingsPage.TWEAKS */
@Suppress("UNUSED_VARIABLE", "UNUSED_PARAMETER")
@Composable
private fun SettingsPage21(page: SettingsPage?, args: PageArgs) {
    val st = args.st
    val d = args.d
    val sc = LocalSc.current
    val context = LocalContext.current
    val allFolders = args.allFolders
    val onPage = args.onPage
    val onRescan = args.onRescan
    val onAudioInfo = args.onAudioInfo
    val onImportAutoEq = args.onImportAutoEq
    val onCheckUpdate = args.onCheckUpdate
    val onChangelog = args.onChangelog
    val onExport = args.onExport
    val onImport = args.onImport
    fun set(change: (AppSettings) -> AppSettings) = SettingsStore.update(change)
    Column(Modifier.fillMaxWidth()) {
    var confirmReverb by remember { mutableStateOf(false) }
    SectionLabel("Volume")
    SwitchRow("Pause/Resume on Volume", "Pause when the volume is set to 0, resume when it is turned up again", st.pauseOnVolumeZero) { v ->
        set { it.copy(pauseOnVolumeZero = v) }
    }
    SectionLabel("Other")
    SwitchRow(
        "MusicFX", "Let Android's audio effects (and equalizer apps) work with PowerSound. Adds \"MusicFX\" to the ⋮ menu of the Equalizer",
        st.musicFx
    ) { v -> set { it.copy(musicFx = v) } }
    PlainRow("Restore Reverb Presets", "Your saved reverb presets are removed, built-in ones stay") { confirmReverb = true }
    RestoreRow { set { it.copy(pauseOnVolumeZero = d.pauseOnVolumeZero, musicFx = d.musicFx) } }
    if (confirmReverb) ConfirmDialog(
        "Restore Reverb Presets", "Your saved reverb presets will be removed.",
        onOk = { com.powersound.player.audio.AudioEngine.removeCustomReverbs() }
    ) { confirmReverb = false }
    }
}

/** page == SettingsPage.VISUALIZATION */
@Suppress("UNUSED_VARIABLE", "UNUSED_PARAMETER")
@Composable
private fun SettingsPage22(page: SettingsPage?, args: PageArgs) {
    val st = args.st
    val d = args.d
    val sc = LocalSc.current
    val context = LocalContext.current
    val allFolders = args.allFolders
    val onPage = args.onPage
    val onRescan = args.onRescan
    val onAudioInfo = args.onAudioInfo
    val onImportAutoEq = args.onImportAutoEq
    val onCheckUpdate = args.onCheckUpdate
    val onChangelog = args.onChangelog
    val onExport = args.onExport
    val onImport = args.onImport
    fun set(change: (AppSettings) -> AppSettings) = SettingsStore.update(change)
    Column(Modifier.fillMaxWidth()) {
    SwitchRow(
        "Visualization On Player Screen",
        "Visualization can be switched on with the Player Screen 🎵 button. It is shown when music is playing",
        st.vizOnPlayer
    ) { v -> set { it.copy(vizOnPlayer = v) } }
    ChipRow("Equalizer Screen Spectrum", null, listOf("Disabled", "Classic", "Rounded"), st.eqSpectrum) { v -> set { it.copy(eqSpectrum = v) } }
    SwitchRow(
        "Visualization in Library",
        "Visible behind the lists when visualization on the Player Screen is on. Some presets may make the lists harder to read",
        st.vizInLibrary
    ) { v -> set { it.copy(vizInLibrary = v) } }
    val secs = listOf(0, 5, 10, 15, 20, 30, 45, 60, 90, 120)
    StepSliderRow(
        "Preset Duration", if (st.vizPresetSeconds == 0) "Never change automatically" else "Change each: ${st.vizPresetSeconds} s",
        secs.indexOf(st.vizPresetSeconds).coerceAtLeast(0), 0, secs.lastIndex, "Never", "2 min"
    ) { i -> set { it.copy(vizPresetSeconds = secs[i]) } }
    SliderRow("Top Visualization Panel Opacity", "${(st.vizPanelOpacity * 100).roundToInt()}%", st.vizPanelOpacity, 0f..1f) { v ->
        set { it.copy(vizPanelOpacity = (v * 20).roundToInt() / 20f) }
    }
    SliderRow("Faded Controls Opacity", "${(st.vizFadedOpacity * 100).roundToInt()}%", st.vizFadedOpacity, 0f..1f) { v ->
        set { it.copy(vizFadedOpacity = (v * 20).roundToInt() / 20f) }
    }
    StepSliderRow("UI Timeout", "Time to show the controls during active visualization: ${st.vizUiTimeoutMs} ms", st.vizUiTimeoutMs / 500, 1, 10, "0.5 s", "5 s") { v ->
        set { it.copy(vizUiTimeoutMs = v * 500) }
    }
    SwitchRow("Visible Album Art", "Keep the album art visible during visualization", st.vizAlbumArt) { v -> set { it.copy(vizAlbumArt = v) } }
    SwitchRow("Ignore Touch", "In Fade Controls mode the first touch only shows the controls", st.vizIgnoreTouch) { v -> set { it.copy(vizIgnoreTouch = v) } }
    SliderRow(
        "Track Opacity", "${(st.vizTrackOpacity * 100).roundToInt()}%", st.vizTrackOpacity, 0f..1f,
        subtitle = "Track labels, rating, menu and album art (if visible) opacity"
    ) { v -> set { it.copy(vizTrackOpacity = (v * 20).roundToInt() / 20f) } }
    SwitchRow("Hide System Bars For Full Screen", "Hide the status bar and navigation completely in Full Screen mode", st.vizHideBars) { v -> set { it.copy(vizHideBars = v) } }
    SwitchRow(
        "Scaled Bars For Faded Controls",
        "Bars visualizations fit the album art area in Fade Controls mode. If disabled, bars are always full-screen",
        st.vizScaledBars
    ) { v -> set { it.copy(vizScaledBars = v) } }
    SwitchRow("HD", "Twice as many bars, uses a bit more battery", st.vizHd) { v -> set { it.copy(vizHd = v) } }
    SwitchRow("Crop Aspect", "Keep round / wave visualizations square instead of stretching them", st.vizCrop) { v -> set { it.copy(vizCrop = v) } }
    SwitchRow("Force 30 FPS", "Reduce the frame rate to 30 frames per second (saves battery)", st.viz30fps) { v -> set { it.copy(viz30fps = v) } }
    NoteBlock("Visualization Delay: the extra delay that keeps the visualization and lyrics in time with the sound is set for each output (Audio → Audio Outputs)")
    SectionLabel("Presets")
    Hint("Built-in: ${VizPreset.values().size}. Switched-off presets are skipped by the ◀ ▶ buttons and Preset Duration.")
    VizPreset.values().forEach { p ->
        SwitchRow(p.label, null, p.name !in st.vizDisabledPresets) { on ->
            set {
                val off = if (on) it.vizDisabledPresets - p.name else it.vizDisabledPresets + p.name
                // keep at least one preset on
                it.copy(vizDisabledPresets = if (off.size >= VizPreset.values().size) it.vizDisabledPresets else off)
            }
        }
    }
    RestoreRow {
        set {
            it.copy(
                vizOnPlayer = d.vizOnPlayer, eqSpectrum = d.eqSpectrum, vizInLibrary = d.vizInLibrary, vizPresetSeconds = d.vizPresetSeconds,
                vizPanelOpacity = d.vizPanelOpacity, vizFadedOpacity = d.vizFadedOpacity, vizUiTimeoutMs = d.vizUiTimeoutMs,
                vizAlbumArt = d.vizAlbumArt, vizIgnoreTouch = d.vizIgnoreTouch, vizTrackOpacity = d.vizTrackOpacity,
                vizHideBars = d.vizHideBars, vizScaledBars = d.vizScaledBars, vizHd = d.vizHd, vizCrop = d.vizCrop,
                viz30fps = d.viz30fps, vizDisabledPresets = d.vizDisabledPresets
            )
        }
    }
    }
}

/** page == SettingsPage.BACKGROUND */
@Suppress("UNUSED_VARIABLE", "UNUSED_PARAMETER")
@Composable
private fun SettingsPage23(page: SettingsPage?, args: PageArgs) {
    val st = args.st
    val d = args.d
    val sc = LocalSc.current
    val context = LocalContext.current
    val allFolders = args.allFolders
    val onPage = args.onPage
    val onRescan = args.onRescan
    val onAudioInfo = args.onAudioInfo
    val onImportAutoEq = args.onImportAutoEq
    val onCheckUpdate = args.onCheckUpdate
    val onChangelog = args.onChangelog
    val onExport = args.onExport
    val onImport = args.onImport
    fun set(change: (AppSettings) -> AppSettings) = SettingsStore.update(change)
    Column(Modifier.fillMaxWidth()) {
    var pickColor by remember { mutableStateOf(false) }
    SwitchRow("Enable Blurred Backgrounds", "The current album art, blurred, behind the screens", st.bgEnabled) { v -> set { it.copy(bgEnabled = v) } }
    SwitchRow("List Background", "Also behind the library lists", st.bgList) { v -> set { it.copy(bgList = v) } }
    SwitchRow("Lyrics Background", "Also behind the lyrics", st.bgLyrics) { v -> set { it.copy(bgLyrics = v) } }
    NoteBlock("Lyrics and list backgrounds are dimmed a bit more to keep the text readable")
    StepSliderRow("Background Gradient", "${st.bgGradient}", st.bgGradient, 0, 8, "None", "Max") { v -> set { it.copy(bgGradient = v) } }
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { pickColor = true }
            .padding(horizontal = 18.dp, vertical = 10.dp)
    ) {
        Text("Background Gradient Color", color = sc.text, fontSize = 18.sp, fontFamily = sc.family, fontWeight = sc.titleWeight)
        Text("#%06X".format(st.bgGradientColor and 0xFFFFFF), color = sc.sub, fontSize = 13.sp, fontFamily = sc.family)
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .size(width = 46.dp, height = 24.dp)
                .clip(RoundedCornerShape(50))
                .background(Color(st.bgGradientColor))
                .border(1.dp, sc.chipBorder, RoundedCornerShape(50))
        )
    }
    SwitchRow("Background Gradient For Lists", "Also apply the gradient to the list background", st.bgGradientLists) { v -> set { it.copy(bgGradientLists = v) } }
    StepSliderRow("Background Blur", "${st.bgBlurStep}", st.bgBlurStep, 0, 10, "Less", "More", enabled = st.bgEnabled) { v -> set { it.copy(bgBlurStep = v) } }
    StepSliderRow("Background Details", "${st.bgDetails}", st.bgDetails, 0, 10, "Solid Color", "Detailed", enabled = st.bgEnabled) { v -> set { it.copy(bgDetails = v) } }
    SliderRow("Background Intensity", "${(st.bgIntensity * 100).roundToInt()}%", st.bgIntensity, 0f..2f, enabled = st.bgEnabled) { v ->
        set { it.copy(bgIntensity = (v * 20).roundToInt() / 20f) }
    }
    SliderRow("Background Saturation", "${(st.bgSaturation * 100).roundToInt()}%", st.bgSaturation, 0f..3f, enabled = st.bgEnabled) { v ->
        set { it.copy(bgSaturation = (v * 20).roundToInt() / 20f) }
    }
    RestoreRow {
        set {
            it.copy(
                bgEnabled = d.bgEnabled, bgList = d.bgList, bgLyrics = d.bgLyrics, bgGradient = d.bgGradient,
                bgGradientColor = d.bgGradientColor, bgGradientLists = d.bgGradientLists, bgBlurStep = d.bgBlurStep,
                bgDetails = d.bgDetails, bgIntensity = d.bgIntensity, bgSaturation = d.bgSaturation
            )
        }
    }
    if (pickColor) ColorPickerDialog(Color(st.bgGradientColor), onPick = { c -> set { it.copy(bgGradientColor = c.toArgb()) } }) { pickColor = false }
    }
}

/** page == SettingsPage.ALBUM_ART */
@Suppress("UNUSED_VARIABLE", "UNUSED_PARAMETER")
@Composable
private fun SettingsPage24(page: SettingsPage?, args: PageArgs) {
    val st = args.st
    val d = args.d
    val sc = LocalSc.current
    val context = LocalContext.current
    val allFolders = args.allFolders
    val onPage = args.onPage
    val onRescan = args.onRescan
    val onAudioInfo = args.onAudioInfo
    val onImportAutoEq = args.onImportAutoEq
    val onCheckUpdate = args.onCheckUpdate
    val onChangelog = args.onChangelog
    val onExport = args.onExport
    val onImport = args.onImport
    fun set(change: (AppSettings) -> AppSettings) = SettingsStore.update(change)
    Column(Modifier.fillMaxWidth()) {
    var confirm by remember { mutableStateOf(0) } // 1 reset negative, 2 delete cache
    SectionLabel("Download")
    SwitchRow("Download Album Art", "Automatically search and download missing album art (iTunes)", st.artDownload) { v -> set { it.copy(artDownload = v) } }
    SwitchRow(
        "Download Artist Images", "For Artists, Album Artists and Composers (Deezer). If disabled, the album art is used",
        st.artDownloadArtist
    ) { v -> set { it.copy(artDownloadArtist = v) } }
    SwitchRow("High Resolution", "Higher downloaded image resolution, uses more storage and data", st.artHighRes) { v -> set { it.copy(artHighRes = v) } }
    SwitchRow("Download Only On Wi-Fi", "Images are downloaded only when Wi-Fi is connected", st.artWifiOnly) { v -> set { it.copy(artWifiOnly = v) } }
    SwitchRow("Also Search By Title/Filename", "Search for album art even if the album tag is missing", st.artSearchTitle) { v -> set { it.copy(artSearchTitle = v) } }
    SwitchRow(
        "Prefer Downloaded Album Art", "Prefer downloaded album art over the embedded picture and Cover.jpg/Folder.jpg",
        st.artPreferDownloaded
    ) { v -> set { it.copy(artPreferDownloaded = v) }; ArtworkLoader.clear() }
    PlainRow("Reset Negative Status", "Clear the stored \"not found\" status of album/artist images") { confirm = 1 }
    PlainRow("Delete Cache", "Downloaded images and the image cache will be deleted") { confirm = 2 }
    SectionLabel("Advanced Tweaks")
    SwitchRow("Force Default Image", "Always show the default image instead of any album art", st.artForceDefault) { v -> set { it.copy(artForceDefault = v) }; ArtworkLoader.clear() }
    SwitchRow("Use 24-bit RGB", "Higher colour quality for album art. Needs 2x more memory per image", st.art24bit) { v -> set { it.copy(art24bit = v) }; ArtworkLoader.clear() }
    SwitchRow("Increase Resolution", "Sharper album art on the Player Screen. Uses more memory", st.artHighQuality) { v -> set { it.copy(artHighQuality = v) } }
    ChipRow(
        "Aspect Ratio", "Keep album art aspect ratio as is. Tall images won't fill some places fully",
        listOf("Default", "Keep Aspect Ratio", "Square"), st.artAspect
    ) { v -> set { it.copy(artAspect = v) } }
    NoteBlock("Cover.jpg / Folder.jpg next to the songs are read when All Files Access is allowed (Library → All Files Access)")
    RestoreRow {
        set {
            it.copy(
                artDownload = d.artDownload, artDownloadArtist = d.artDownloadArtist, artHighRes = d.artHighRes,
                artWifiOnly = d.artWifiOnly, artSearchTitle = d.artSearchTitle, artPreferDownloaded = d.artPreferDownloaded,
                artForceDefault = d.artForceDefault, art24bit = d.art24bit, artHighQuality = d.artHighQuality, artAspect = d.artAspect
            )
        }
        ArtworkLoader.clear()
    }
    if (confirm == 1) ConfirmDialog(
        "Reset Negative Status", "PowerSound will search again for album/artist images that were not found before.",
        onOk = { ArtworkLoader.resetNegative(context) }
    ) { confirm = 0 }
    if (confirm == 2) ConfirmDialog(
        "Delete Cache", "Downloaded images and the image cache will be deleted.",
        onOk = { ArtworkLoader.deleteCache(context) }
    ) { confirm = 0 }
    }
}

/** page == SettingsPage.LIBRARY */
@Suppress("UNUSED_VARIABLE", "UNUSED_PARAMETER")
@Composable
private fun SettingsPage25(page: SettingsPage?, args: PageArgs) {
    val st = args.st
    val d = args.d
    val context = LocalContext.current
    val allFolders = args.allFolders
    val onPage = args.onPage
    val lib = args.lib
    fun set(change: (AppSettings) -> AppSettings) = SettingsStore.update(change)
    Column(Modifier.fillMaxWidth()) {
    var pickFolders by remember { mutableStateOf(false) }
    var confirm by remember { mutableIntStateOf(0) }
    ActionRow("Rescan", lib.counts, onClick = args.onRescan)
    ActionRow("Full Rescan", "Read every file's tags again, clear the album art and lyrics caches and reload the library") { confirm = 1 }
    ActionRow(
        "Music Folders",
        if (st.excludedFolders.isEmpty()) "Folders Selection — all ${allFolders.size} folders are included"
        else "Folders Selection — ${st.excludedFolders.size} of ${allFolders.size} folders excluded"
    ) { pickFolders = true }
    val allFiles = Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()
    ActionRow(
        "All Files Access",
        if (allFiles) "Allowed. PowerSound can read .lrc lyrics files and cover.jpg / folder.jpg next to your songs"
        else "Not allowed — tap to allow. Needed for .lrc lyrics files, cover.jpg / folder.jpg and a deeper Full Rescan",
        enabled = !allFiles
    ) {
        if (Build.VERSION.SDK_INT >= 30) runCatching {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
    val shortSecs = listOf(0, 2, 6, 10, 15, 30, 45)
    ChoiceRow(
        "Ignore Short Tracks",
        listOf("Don't ignore", "Shorter than 2 s", "Shorter than 6 s", "Shorter than 10 s", "Shorter than 15 s", "Shorter than 30 s", "Shorter than 45 s"),
        shortSecs.indexOf(st.minDurationSec).coerceAtLeast(0)
    ) { i -> set { it.copy(minDurationSec = shortSecs[i]) } }
    SwitchRow("Ignore Video Tracks", "Don't add the sound of video files (mp4, 3gp …) to the library", st.ignoreVideo) { v -> set { it.copy(ignoreVideo = v) } }

    SectionLabel("Categories")
    PlainRow("Lists", "Library categories, navbar, headers, track labels, albums, sorting") { onPage(SettingsPage.LIST_UI) }
    PlainRow("Search", "What search looks in and what Play plays") { onPage(SettingsPage.LIB_SEARCH) }
    PlainRow("Queue", "Where added songs go") { onPage(SettingsPage.LIB_QUEUE) }
    PlainRow("Playlists", "Import, export, insert position, duplicates") { onPage(SettingsPage.LIB_PLAYLISTS) }
    PlainRow("Shuffle", "Reshuffle, category shuffle, folders hierarchy") { onPage(SettingsPage.LIB_SHUFFLE) }
    PlainRow("Scanner", "Auto-scan, split symbols, tag encoding") { onPage(SettingsPage.LIB_SCANNER) }

    SectionLabel("Advanced")
    SwitchRow(
        "Store/Restore Per Track Progress",
        "Long tracks (${st.longTrackMin} min or more) continue where you stopped them",
        st.rememberPosition
    ) { v -> set { it.copy(rememberPosition = v) } }
    SwitchRow(
        "-10/+10s Rewind Pro Buttons",
        "In long tracks (${st.longTrackMin} min or more) the category Pro Buttons become -10/+10 s jump buttons",
        st.proRewindLong
    ) { v -> set { it.copy(proRewindLong = v) } }
    StepSliderRow(
        "Track Duration For Per Track Progress and -10/+10s Buttons",
        "${st.longTrackMin} min — also used by the Long Tracks category",
        st.longTrackMin, 1, 60, "1 min", "60 min"
    ) { v -> set { it.copy(longTrackMin = v) } }
    SliderRow(
        "Count As Played",
        if (st.countPlayedPct == 0) "At start" else "${st.countPlayedPct}%",
        st.countPlayedPct.toFloat(), 0f..100f,
        subtitle = "How much of a track must be heard before it counts in Most Played / Recently Played"
    ) { v -> set { it.copy(countPlayedPct = ((v / 5f).roundToInt() * 5).coerceIn(0, 100)) } }
    PlainRow("Reset Stats", "Clear play counts and recently played. Ratings and likes stay") { confirm = 2 }
    RestoreRow {
        set {
            it.copy(
                excludedFolders = d.excludedFolders, minDurationSec = d.minDurationSec, ignoreVideo = d.ignoreVideo,
                rememberPosition = d.rememberPosition, proRewindLong = d.proRewindLong, longTrackMin = d.longTrackMin,
                countPlayedPct = d.countPlayedPct
            )
        }
    }

    if (pickFolders) {
        CheckListDialog(
            title = "Folders Selection",
            items = allFolders,
            checked = allFolders.filter { it !in st.excludedFolders }.toSet(),
            onDone = { kept -> set { it.copy(excludedFolders = allFolders.filter { f -> f !in kept }.toSet()) } },
            onDismiss = { pickFolders = false }
        )
    }
    if (confirm == 1) ConfirmDialog(
        "Full Rescan", "PowerSound will ask Android to read all your music files again, clear its caches and reload the library. It can take a while.",
        onOk = lib.fullRescan
    ) { confirm = 0 }
    if (confirm == 2) ConfirmDialog(
        "Reset Stats", "Play counts and recently played will be cleared. Ratings and likes are kept.",
        onOk = lib.resetStats
    ) { confirm = 0 }
    }
}

/** page == SettingsPage.LIST_UI ("Lists") */
@Suppress("UNUSED_VARIABLE", "UNUSED_PARAMETER")
@Composable
private fun SettingsPage8(page: SettingsPage?, args: PageArgs) {
    val st = args.st
    val d = args.d
    fun set(change: (AppSettings) -> AppSettings) = SettingsStore.update(change)
    Column(Modifier.fillMaxWidth()) {
    var pickCategories by remember { mutableStateOf(false) }
    ActionRow(
        "Library List Options",
        if (st.hiddenCategories.isEmpty()) "All categories are shown on the Library page" else "${st.hiddenCategories.size} categories hidden"
    ) { pickCategories = true }

    SectionLabel("Layout")
    SwitchRow("Static Navbar", "Keep the bottom bar in place. If disabled, it slides away while you scroll down a list", st.staticNavbar) { v -> set { it.copy(staticNavbar = v) } }
    SwitchRow("Seekbar in Navbar", "Show the thin progress line under the playing track in the bottom bar", st.navbarSeekbar) { v -> set { it.copy(navbarSeekbar = v) } }
    SwitchRow("Headers With Meta", "Show the songs count and total time in album / folder headers", st.headersMeta) { v -> set { it.copy(headersMeta = v) } }
    SwitchRow("A-Z Scroll", "Letter bar on the right side of long lists", st.azScroll) { v -> set { it.copy(azScroll = v) } }
    SwitchRow("Header Buttons", "Shuffle / Play / Search / ⋮ buttons in the list header", st.headerButtons) { v -> set { it.copy(headerButtons = v) } }
    ChipRow(
        "Bottom Buttons", "The same buttons floating at the bottom while you scroll (always shown when Header Buttons are off)",
        listOf("Disabled", "Semi-transparent", "Enabled"), st.bottomButtons
    ) { v -> set { it.copy(bottomButtons = v) } }

    SectionLabel("Track Labels")
    ChoiceRow(
        "Show Track Number",
        listOf("Disabled", "Separate number in album / folder lists", "In the meta line", "In the title, everywhere"),
        st.trackNumberMode
    ) { v -> set { it.copy(trackNumberMode = v) } }
    SwitchRow("Filename As Title", "Show the file name instead of the title tag", st.filenameAsTitle) { v -> set { it.copy(filenameAsTitle = v) } }

    SectionLabel("Behavior")
    SwitchRow("Click Restarts Track", "Tapping the song that is playing starts it again. If disabled, it just keeps playing", st.clickRestarts) { v -> set { it.copy(clickRestarts = v) } }
    ChoiceRow(
        "List Item Action",
        listOf("Play and go to the player", "Play and stay in the list", "Add to queue and stay in the list"),
        st.listItemAction
    ) { v -> set { it.copy(listItemAction = v) } }
    SwitchRow("Delete Action", "Show Delete in the track menu", st.deleteAction) { v -> set { it.copy(deleteAction = v) } }

    SectionLabel("Albums")
    SwitchRow("Join Albums", "Tracks with the same album name and album artist are shown as one album, even from different folders", st.joinAlbums) { v -> set { it.copy(joinAlbums = v) } }
    SwitchRow("Album Artist Label for Tracks", "Show the album artist instead of the track artist under track titles", st.albumArtistTracks) { v -> set { it.copy(albumArtistTracks = v) } }
    SwitchRow("Album Artist Label for Albums", "Show (and sort by) the album artist under album names", st.albumArtistAlbums) { v -> set { it.copy(albumArtistAlbums = v) } }
    SwitchRow("Album Artist Label for Album Artist Tracks", "Inside Album Artists, show the album artist under track titles", st.albumArtistAATracks) { v -> set { it.copy(albumArtistAATracks = v) } }
    SwitchRow("Hide Unknown Album", "Don't show \"Unknown album\" in track labels", st.hideUnknownAlbum) { v -> set { it.copy(hideUnknownAlbum = v) } }
    SwitchRow("Hide Unknown Artist", "Don't show \"Unknown artist\" in track labels", st.hideUnknownArtist) { v -> set { it.copy(hideUnknownArtist = v) } }

    SectionLabel("Advanced")
    SwitchRow("Show Unsplit Combined Categories", "Besides each split artist / composer / genre, also show the full combined name (see Scanner)", st.showUnsplit) { v -> set { it.copy(showUnsplit = v) } }
    SwitchRow("Folders Hierarchy Immediate Root", "Skip the top folders that only contain one folder and open where the music starts", st.hierImmediateRoot) { v -> set { it.copy(hierImmediateRoot = v) } }
    SwitchRow("Don't Ignore Articles For Sort", "If disabled, \"The\", \"A\" and \"An\" at the start are skipped when sorting (The Beatles → B)", st.keepArticles) { v -> set { it.copy(keepArticles = v) } }
    Hint("Sorting and zoom of each list are in its ⋮ menu")
    RestoreRow {
        set {
            it.copy(
                hiddenCategories = d.hiddenCategories, staticNavbar = d.staticNavbar, navbarSeekbar = d.navbarSeekbar,
                headersMeta = d.headersMeta, azScroll = d.azScroll, headerButtons = d.headerButtons, bottomButtons = d.bottomButtons,
                trackNumberMode = d.trackNumberMode, filenameAsTitle = d.filenameAsTitle, clickRestarts = d.clickRestarts,
                listItemAction = d.listItemAction, deleteAction = d.deleteAction, joinAlbums = d.joinAlbums,
                albumArtistTracks = d.albumArtistTracks, albumArtistAlbums = d.albumArtistAlbums, albumArtistAATracks = d.albumArtistAATracks,
                hideUnknownAlbum = d.hideUnknownAlbum, hideUnknownArtist = d.hideUnknownArtist, showUnsplit = d.showUnsplit,
                hierImmediateRoot = d.hierImmediateRoot, keepArticles = d.keepArticles
            )
        }
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
}

/** page == SettingsPage.LIB_SEARCH */
@Suppress("UNUSED_VARIABLE", "UNUSED_PARAMETER")
@Composable
private fun SettingsPage30(page: SettingsPage?, args: PageArgs) {
    val st = args.st
    val d = args.d
    fun set(change: (AppSettings) -> AppSettings) = SettingsStore.update(change)
    Column(Modifier.fillMaxWidth()) {
    var pick by remember { mutableStateOf(false) }
    SwitchRow(
        "Play Tracks Only", "Play / Shuffle in Search play only the found tracks. If disabled, also the songs of the found albums, artists …",
        st.searchTracksOnly
    ) { v -> set { it.copy(searchTracksOnly = v) } }
    SwitchRow("Search Track Titles Only", "Find tracks by their title only, not by artist or album", st.searchTitlesOnly) { v -> set { it.copy(searchTitlesOnly = v) } }
    ActionRow(
        "Search Categories",
        if (st.searchHidden.isEmpty()) "Search looks in all categories" else "${SEARCH_CATEGORIES.size - st.searchHidden.size} of ${SEARCH_CATEGORIES.size} categories"
    ) { pick = true }
    RestoreRow { set { it.copy(searchTracksOnly = d.searchTracksOnly, searchTitlesOnly = d.searchTitlesOnly, searchHidden = d.searchHidden) } }
    if (pick) CheckListDialog(
        title = "Search Categories",
        items = SEARCH_CATEGORIES,
        checked = SEARCH_CATEGORIES.filter { it !in st.searchHidden }.toSet(),
        onDone = { shown -> set { it.copy(searchHidden = SEARCH_CATEGORIES.filter { c -> c !in shown }.toSet()) } },
        onDismiss = { pick = false }
    )
    }
}

/** page == SettingsPage.LIB_QUEUE */
@Suppress("UNUSED_VARIABLE", "UNUSED_PARAMETER")
@Composable
private fun SettingsPage31(page: SettingsPage?, args: PageArgs) {
    val st = args.st
    val d = args.d
    fun set(change: (AppSettings) -> AppSettings) = SettingsStore.update(change)
    Column(Modifier.fillMaxWidth()) {
    ChipRow("Queue Insert Position", "\"Add to queue\" puts the songs at the end in their order, or mixed", listOf("Normal", "Shuffled"), if (st.queueInsertShuffled) 1 else 0) { v ->
        set { it.copy(queueInsertShuffled = v == 1) }
    }
    ChipRow("Play Next Insert Position", "\"Play next\" puts the songs after the current one in their order, or mixed", listOf("Normal", "Shuffled"), if (st.playNextShuffled) 1 else 0) { v ->
        set { it.copy(playNextShuffled = v == 1) }
    }
    Hint("In PowerSound the queue is the list that is playing, so songs you add join it directly. Open it from Library → Queue or the ⋮ menu.")
    RestoreRow { set { it.copy(queueInsertShuffled = d.queueInsertShuffled, playNextShuffled = d.playNextShuffled) } }
    }
}

/** page == SettingsPage.LIB_PLAYLISTS */
@Suppress("UNUSED_VARIABLE", "UNUSED_PARAMETER")
@Composable
private fun SettingsPage32(page: SettingsPage?, args: PageArgs) {
    val st = args.st
    val d = args.d
    val lib = args.lib
    fun set(change: (AppSettings) -> AppSettings) = SettingsStore.update(change)
    Column(Modifier.fillMaxWidth()) {
    ActionRow("Import System Library Playlists", "Copy the playlists saved in the phone's music library (new ones only)", onClick = lib.importSystemPlaylists)
    ActionRow("Import .m3u / .m3u8 Playlists", "Pick one or more playlist files; their songs are matched to your library", onClick = lib.importM3u)
    ActionRow("Export Playlists", "Save every playlist as an .m3u8 file in a folder you choose", onClick = lib.exportPlaylists)
    ChipRow("Playlist Insert Position", "Where songs added to a playlist go", listOf("Start", "End", "Shuffled"), st.playlistInsert) { v ->
        set { it.copy(playlistInsert = v) }
    }
    SwitchRow("Remove Playlist Entries On Track Deletion", "A deleted file also leaves every playlist", st.playlistRemoveDeleted) { v -> set { it.copy(playlistRemoveDeleted = v) } }
    SwitchRow("Don't Add Duplicates", "Songs already in a playlist are not added again", st.playlistNoDuplicates) { v -> set { it.copy(playlistNoDuplicates = v) } }
    Hint("The text encoding of .m3u files is set in Library → Scanner")
    RestoreRow {
        set { it.copy(playlistInsert = d.playlistInsert, playlistRemoveDeleted = d.playlistRemoveDeleted, playlistNoDuplicates = d.playlistNoDuplicates) }
    }
    }
}

/** page == SettingsPage.LIB_SHUFFLE */
@Suppress("UNUSED_VARIABLE", "UNUSED_PARAMETER")
@Composable
private fun SettingsPage33(page: SettingsPage?, args: PageArgs) {
    val st = args.st
    val d = args.d
    fun set(change: (AppSettings) -> AppSettings) = SettingsStore.update(change)
    Column(Modifier.fillMaxWidth()) {
    SwitchRow("No Reshuffle", "Shuffle on the list that is already playing shuffled keeps its order instead of mixing again", st.noReshuffle) { v -> set { it.copy(noReshuffle = v) } }
    SwitchRow("No Reshuffle For Large Lists", "The same, only for lists of 500 songs or more", st.noReshuffleLarge) { v -> set { it.copy(noReshuffleLarge = v) } }
    ChoiceRow(
        "Category Items Shuffle",
        listOf("Shuffle songs (all mixed)", "Shuffle categories (songs keep their order)", "Shuffle songs inside each category"),
        st.categoryShuffle,
        subtitlePrefix = "Shuffle button on Albums, Artists, Folders …: "
    ) { v -> set { it.copy(categoryShuffle = v) } }
    SwitchRow("Shuffle All Songs In Folder Hierarchy", "Shuffle in Folders Hierarchy also takes the songs of the subfolders", st.shuffleAllHier) { v -> set { it.copy(shuffleAllHier = v) } }
    Hint("The shuffle modes themselves (songs, categories …) are chosen with the shuffle button on the Player Screen")
    RestoreRow {
        set { it.copy(noReshuffle = d.noReshuffle, noReshuffleLarge = d.noReshuffleLarge, categoryShuffle = d.categoryShuffle, shuffleAllHier = d.shuffleAllHier) }
    }
    }
}

/** page == SettingsPage.LIB_SCANNER */
@Suppress("UNUSED_VARIABLE", "UNUSED_PARAMETER")
@Composable
private fun SettingsPage34(page: SettingsPage?, args: PageArgs) {
    val st = args.st
    val d = args.d
    fun set(change: (AppSettings) -> AppSettings) = SettingsStore.update(change)
    Column(Modifier.fillMaxWidth()) {
    var edit by remember { mutableIntStateOf(0) }
    var removeName by remember { mutableStateOf<String?>(null) }
    SwitchRow("Auto-scan", "Pick up new, changed and deleted music files by themselves", st.autoScan) { v -> set { it.copy(autoScan = v) } }
    SwitchRow("Rescan Immediately", "Rescan right after a change (otherwise PowerSound waits a little for copying to finish)", st.rescanImmediately) { v ->
        set { it.copy(rescanImmediately = v) }
    }

    SectionLabel("Tags")
    fun symbols(s: String) = if (s.isBlank()) "Disabled — tap to set, e.g.  ; / &" else "Split by:  $s"
    ActionRow("Symbols to Split Multiple Artists", symbols(st.splitArtists)) { edit = 1 }
    ActionRow("Symbols to Split Multiple Composers", symbols(st.splitComposers)) { edit = 2 }
    ActionRow("Symbols to Split Multiple Genres", symbols(st.splitGenres)) { edit = 3 }
    SectionLabel("Unsplit Artists")
    Hint("These names are never split, even if they contain a split symbol. Tap a name to remove it")
    st.unsplitArtists.sortedBy { it.lowercase() }.forEach { n -> PlainRow(n) { removeName = n } }
    PlainRow("Add", "Add an artist name that must stay whole") { edit = 4 }

    SectionLabel("Encoding")
    ChoiceRow(
        "Tag Encoding",
        com.powersound.player.data.TAG_ENCODINGS.map { it.first },
        st.tagEncoding.coerceIn(0, com.powersound.player.data.TAG_ENCODINGS.lastIndex),
        subtitlePrefix = "For old tags that are not Unicode: "
    ) { v -> set { it.copy(tagEncoding = v) } }
    SwitchRow("Always Use UTF-8 for .m3u", "If disabled, .m3u files are read with the Tag Encoding above (.m3u8 is always UTF-8)", st.m3uUtf8) { v -> set { it.copy(m3uUtf8 = v) } }
    RestoreRow {
        set {
            it.copy(
                autoScan = d.autoScan, rescanImmediately = d.rescanImmediately, splitArtists = d.splitArtists,
                splitComposers = d.splitComposers, splitGenres = d.splitGenres, unsplitArtists = d.unsplitArtists,
                tagEncoding = d.tagEncoding, m3uUtf8 = d.m3uUtf8
            )
        }
    }
    val hint = "Symbols separated by spaces, for example:  ;  /  &  feat."
    when (edit) {
        1 -> TextInputDialog("Split Multiple Artists", st.splitArtists, hint, { v -> set { it.copy(splitArtists = v.trim()) } }) { edit = 0 }
        2 -> TextInputDialog("Split Multiple Composers", st.splitComposers, hint, { v -> set { it.copy(splitComposers = v.trim()) } }) { edit = 0 }
        3 -> TextInputDialog("Split Multiple Genres", st.splitGenres, hint, { v -> set { it.copy(splitGenres = v.trim()) } }) { edit = 0 }
        4 -> TextInputDialog("Add Unsplit Artist", "", "Artist name, e.g. AC/DC", { v ->
            if (v.isNotBlank()) set { it.copy(unsplitArtists = it.unsplitArtists + v.trim()) }
        }) { edit = 0 }
    }
    removeName?.let { n ->
        ConfirmDialog("Remove \"$n\"?", "This name can then be split like any other.", onOk = { set { it.copy(unsplitArtists = it.unsplitArtists - n) } }) { removeName = null }
    }
    }
}

/** page == SettingsPage.HEADSET */
@Suppress("UNUSED_VARIABLE", "UNUSED_PARAMETER")
@Composable
private fun SettingsPage26(page: SettingsPage?, args: PageArgs) {
    val st = args.st
    val d = args.d
    fun set(change: (AppSettings) -> AppSettings) = SettingsStore.update(change)
    Column(Modifier.fillMaxWidth()) {
    var showLog by remember { mutableStateOf(false) }
    SectionLabel("Connection")
    SwitchRow("Resume On Wired Headset", "Resume playing when a wired headset is connected", st.resumeOnWired) { v -> set { it.copy(resumeOnWired = v) } }
    SwitchRow("Resume On Bluetooth", "Resume playing when a Bluetooth device is connected", st.resumeOnBluetooth) { v -> set { it.copy(resumeOnBluetooth = v) } }
    SwitchRow("Pause On Headset Disconnect", "Pause when a wired / Bluetooth headset or USB DAC is disconnected", st.pauseOnDisconnect) { v -> set { it.copy(pauseOnDisconnect = v) } }
    SwitchRow(
        "Pause On USB DAC connection",
        "Pause when a USB DAC is connected. This helps prevent loud noise while some USB DACs start up",
        st.pauseOnUsbDac
    ) { v -> set { it.copy(pauseOnUsbDac = v) } }
    Hint("Resume after calls and other play/resume options are in Audio → Audio Focus")

    SectionLabel("Buttons")
    SwitchRow("Respond To Buttons", "Enable headset / Bluetooth controls", st.respondButtons) { v -> set { it.copy(respondButtons = v) } }
    val modes = listOf("Single press", "Double/triple press for next/prev. track", "Long press for next track (when playing)")
    ChipRow("Wired Headset", "What the play button of a wired headset does", modes, st.wiredPressMode) { v -> set { it.copy(wiredPressMode = v) } }
    ChipRow("Bluetooth", "What the play button of a Bluetooth device does", modes, st.btPressMode) { v -> set { it.copy(btPressMode = v) } }
    Hint("Double press = next track, triple press = previous track. Many earbuds (like the Soundcore Liberty) send next/previous by themselves; those always work")
    SwitchRow(
        "Strict Resume/Pause/Stop",
        "Play only plays and Pause only pauses. Turn off for headsets/devices that send stray play/pause/stop commands, which are then treated as play/pause toggle",
        st.strictPlayPause
    ) { v -> set { it.copy(strictPlayPause = v) } }
    StepSliderRow(
        "Ignore Bluetooth Commands",
        if (st.ignoreBtSec == 0) "Off" else "Ignore all Bluetooth commands for ${st.ignoreBtSec} s after a connection",
        st.ignoreBtSec, 0, 20, "0", "20"
    ) { v -> set { it.copy(ignoreBtSec = v) } }
    SwitchRow("Ignore Repeat/Shuffle", "Ignore repeat and shuffle commands from Bluetooth devices and other apps completely", st.ignoreRepeatShuffle) { v ->
        set { it.copy(ignoreRepeatShuffle = v) }
    }
    SwitchRow("Beep", "Short beep when a double/triple or long press changed the track", st.buttonBeep) { v -> set { it.copy(buttonBeep = v) } }
    SwitchRow("Beep More", "Beep on all button commands", st.buttonBeepMore) { v -> set { it.copy(buttonBeepMore = v) } }
    SwitchRow("Vibrate", "Short vibration on button commands", st.buttonVibrate) { v -> set { it.copy(buttonVibrate = v) } }
    PlainRow("Last Processed Commands", "See which buttons PowerSound received and what it did") { showLog = true }
    RestoreRow {
        set {
            it.copy(
                resumeOnWired = d.resumeOnWired, resumeOnBluetooth = d.resumeOnBluetooth, pauseOnDisconnect = d.pauseOnDisconnect,
                pauseOnUsbDac = d.pauseOnUsbDac, respondButtons = d.respondButtons, wiredPressMode = d.wiredPressMode,
                btPressMode = d.btPressMode, strictPlayPause = d.strictPlayPause, ignoreBtSec = d.ignoreBtSec,
                ignoreRepeatShuffle = d.ignoreRepeatShuffle, buttonBeep = d.buttonBeep, buttonBeepMore = d.buttonBeepMore,
                buttonVibrate = d.buttonVibrate
            )
        }
    }
    if (showLog) {
        val lines = remember { com.powersound.player.ButtonLog.all() }
        TextDialog("Last Processed Commands", if (lines.isEmpty()) "No button commands since the app started." else lines.joinToString("\n")) { showLog = false }
    }
    }
}

/** page == SettingsPage.LOCK_SCREEN */
@Suppress("UNUSED_VARIABLE", "UNUSED_PARAMETER")
@Composable
private fun SettingsPage27(page: SettingsPage?, args: PageArgs) {
    val st = args.st
    val d = args.d
    val context = LocalContext.current
    fun set(change: (AppSettings) -> AppSettings) = SettingsStore.update(change)
    Column(Modifier.fillMaxWidth()) {
    SwitchRow(
        "Blur",
        "The album art on the lock screen and in the notification is blurred. Some smart watches and apps that use the album art also get the blurred image",
        st.lockBlur
    ) { v -> set { it.copy(lockBlur = v) } }
    SwitchRow(
        "Show Default Image",
        "Send a PowerSound placeholder image when a track has no album art (lock screen, notification, watches, car)",
        st.lockDefaultImage
    ) { v -> set { it.copy(lockDefaultImage = v) } }
    Hint("The media buttons of the lock screen are the notification buttons: Look and Feel → Notifications → Buttons")

    SectionLabel("PowerSound Lock Screen")
    SwitchRow(
        "Show On Lock Screen",
        "When the screen turns off while music is playing, PowerSound shows its player on top of the lock screen. Settings, delete, tag editing and similar actions still ask to unlock",
        st.showOnLockScreen
    ) { v -> set { it.copy(showOnLockScreen = v) } }
    val overlayOk = Build.VERSION.SDK_INT < 29 || Settings.canDrawOverlays(context)
    ActionRow(
        "Open App Settings",
        if (overlayOk) "Allowed. PowerSound can open itself when the screen turns off"
        else "Needed on this Android version: allow \"Display over other apps\" for PowerSound, otherwise it only stays on the lock screen when it was open",
        enabled = st.showOnLockScreen
    ) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
    RestoreRow { set { it.copy(lockBlur = d.lockBlur, lockDefaultImage = d.lockDefaultImage, showOnLockScreen = d.showOnLockScreen) } }
    }
}

/** page == SettingsPage.MISC */
@Suppress("UNUSED_VARIABLE", "UNUSED_PARAMETER")
@Composable
private fun SettingsPage28(page: SettingsPage?, args: PageArgs) {
    val st = args.st
    val d = args.d
    fun set(change: (AppSettings) -> AppSettings) = SettingsStore.update(change)
    Column(Modifier.fillMaxWidth()) {
    var showLog by remember { mutableStateOf(false) }
    var edit by remember { mutableIntStateOf(0) }
    SectionLabel("Scrobbling")
    SwitchRow("Scrobble via Official Last.fm app", "Send the playing tracks to the Last.fm app", st.scrobbleLastfm) { v -> set { it.copy(scrobbleLastfm = v) } }
    SwitchRow("Scrobble via Simple Scrobbler", "Scrobble to Last.fm / Libre.fm with the Simple Scrobbler app", st.scrobbleSimple) { v -> set { it.copy(scrobbleSimple = v) } }
    NoteBlock("Most other scrobbler apps work without any setting, through the media notification or Metachanged Intent below")

    SectionLabel("Tweaks")
    SwitchRow("Metachanged Intent", "Tell other apps (widgets, watches, scrobblers) which track is playing", st.metachangedIntent) { v -> set { it.copy(metachangedIntent = v) } }
    SwitchRow("Use Wakelock", "Keeps the phone awake enough to play. Turn on if music stops when the screen is off", st.useWakelock) { v -> set { it.copy(useWakelock = v) } }
    ActionRow(
        "Shutdown Intent",
        if (st.shutdownIntent.isBlank()) "Not set. Other apps (e.g. automation apps) can send this action to make PowerSound pause and keep its state"
        else "PowerSound pauses when it receives: ${st.shutdownIntent}"
    ) { edit = 1 }
    SwitchRow("Pause on Screen Off", "Experiment — pause the music when the screen turns off", st.pauseOnScreenOff) { v -> set { it.copy(pauseOnScreenOff = v) } }
    SwitchRow("Apply High Framerate", "Experiment — ask for the highest refresh rate of the screen (smoother lists and visualizations, uses more battery)", st.highFramerate) { v ->
        set { it.copy(highFramerate = v) }
    }
    SwitchRow("Stop music when the app is swiped away", "From the recent apps screen", st.stopOnSwipeAway) { v -> set { it.copy(stopOnSwipeAway = v) } }
    SwitchRow("Ask before deleting files", "Show a confirmation first", st.confirmDelete) { v -> set { it.copy(confirmDelete = v) } }

    SectionLabel("Network Streams")
    StepSliderRow(
        "Network Stream Timeout",
        if (st.streamTimeoutSec > 60) "Never" else "${st.streamTimeoutSec} s",
        st.streamTimeoutSec.coerceIn(5, 61), 5, 61, "Small", "Never"
    ) { v -> set { it.copy(streamTimeoutSec = v) } }
    SliderRow(
        "Network Stream Buffer",
        "%.1f MB".format(st.streamBufferKb / 1024f),
        st.streamBufferKb.toFloat(), 256f..8192f,
        subtitle = "A bigger buffer also makes streams take longer to start. Used the next time PowerSound starts"
    ) { v -> set { it.copy(streamBufferKb = ((v / 256f).roundToInt() * 256).coerceIn(256, 8192)) } }
    ActionRow("User Agent", if (st.userAgent.isBlank()) "Default (PowerSound Player)" else st.userAgent) { edit = 2 }

    SectionLabel("Errors")
    SwitchRow("Keep an error log", "Saves crash details on the phone", st.keepCrashLog) { v -> set { it.copy(keepCrashLog = v) } }
    ActionRow("View error log", "See, share or clear the last crash") { showLog = true }
    RestoreRow {
        set {
            it.copy(
                scrobbleLastfm = d.scrobbleLastfm, scrobbleSimple = d.scrobbleSimple, metachangedIntent = d.metachangedIntent,
                useWakelock = d.useWakelock, shutdownIntent = d.shutdownIntent, pauseOnScreenOff = d.pauseOnScreenOff,
                highFramerate = d.highFramerate, stopOnSwipeAway = d.stopOnSwipeAway, confirmDelete = d.confirmDelete,
                streamTimeoutSec = d.streamTimeoutSec, streamBufferKb = d.streamBufferKb, userAgent = d.userAgent,
                keepCrashLog = d.keepCrashLog
            )
        }
    }
    when (edit) {
        1 -> TextInputDialog("Shutdown Intent", st.shutdownIntent, "An intent action, e.g. com.example.SHUTDOWN. Leave empty to turn off", { v ->
            set { it.copy(shutdownIntent = v.trim()) }
        }) { edit = 0 }
        2 -> TextInputDialog("User Agent", st.userAgent, "User Agent header used for network streams. Leave empty for the default", { v ->
            set { it.copy(userAgent = v.trim()) }
        }) { edit = 0 }
    }
    if (showLog) ErrorLogDialog { showLog = false }
    }
}

/** page == SettingsPage.ABOUT */
@Suppress("UNUSED_VARIABLE", "UNUSED_PARAMETER")
@Composable
private fun SettingsPage29(page: SettingsPage?, args: PageArgs) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth()) {
    var dialog by remember { mutableIntStateOf(0) }
    val version = remember {
        runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: ""
    }
    Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) { PowerSoundLogo() }
    Hint("PowerSound Player $version")
    ActionRow("Version/Changelog", "Version $version — what changed in each version", onClick = args.onChangelog)
    ActionRow("Check for updates", "Look for a newer version on GitHub", onClick = args.onCheckUpdate)
    SectionLabel("Other")
    ActionRow("Get Support", "Report a problem or ask for a feature on GitHub") {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/jsean327-bot/PowerSound-Player/issues")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
    ActionRow("Open Source Licenses", "Libraries and services PowerSound uses") { dialog = 1 }
    ActionRow("Privacy Policy", "What PowerSound does with your data") { dialog = 2 }
    if (dialog == 1) TextDialog("Open Source Licenses", OPEN_SOURCE_TEXT) { dialog = 0 }
    if (dialog == 2) TextDialog("Privacy Policy", PRIVACY_TEXT) { dialog = 0 }
    }
}

/** A long, scrollable text with an OK button. */
@Composable
private fun TextDialog(title: String, text: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1B1B22),
        title = { Text(title, color = Color.White) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                Text(text, color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } }
    )
}

private const val OPEN_SOURCE_TEXT = """PowerSound Player is built with these open source libraries:

• Kotlin and Kotlin Coroutines — JetBrains, Apache License 2.0
• Android Jetpack (AndroidX Core, Activity, Lifecycle) — Google, Apache License 2.0
• Jetpack Compose and Material 3 — Google, Apache License 2.0
• AndroidX Media3 / ExoPlayer and Media3 Session — Google, Apache License 2.0
• Guava (used by Media3) — Google, Apache License 2.0

Online services (only when the matching setting is on):
• Album art search: iTunes Search API (Apple)
• Artist images: Deezer API
• Updates and changelog: GitHub releases

AutoEq (Jaakko Pasanen), MIT License: the built-in "Liberty 4 NC (AutoEq)" preset (measurement by DHRME) and the presets you import.

3D Surround uses head-related impulse responses derived from the SADIE II database (University of York; Subject 002, Neumann KU100), Apache License 2.0, as shipped with Google's Resonance Audio.

The Apache License 2.0 text: https://www.apache.org/licenses/LICENSE-2.0"""

private const val PRIVACY_TEXT = """PowerSound Player has no account, no ads and no analytics.

• Your music, playlists, ratings, play counts, equalizer presets and settings stay on your phone.
• Album art: when Download Album Art / Artist Images is on, the artist, album or track name is sent to the iTunes Search API or the Deezer API to find an image. Turn these off in Settings → Album Art.
• Updates: PowerSound asks GitHub for the latest version (no personal data is sent).
• Scrobbling and Metachanged Intent send the playing track only to apps on your phone, and only when you turn them on.
• Error log: crash details are saved on the phone only. They leave the phone only if you share them yourself."""
