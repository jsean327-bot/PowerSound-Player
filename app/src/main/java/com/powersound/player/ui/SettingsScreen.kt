package com.powersound.player.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Info
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.powersound.player.data.AppSettings
import com.powersound.player.data.ArtworkLoader
import com.powersound.player.data.Category
import com.powersound.player.data.CrashLog
import com.powersound.player.data.SettingsStore
import com.powersound.player.data.Updater
import kotlin.math.roundToInt

/** Sections of the Settings screen. */
enum class SettingsPage(val title: String, val subtitle: String, val icon: ImageVector, val color: Color) {
    LOOK("Look and Feel", "Accent colour, tips, screen", Icons.Rounded.Palette, Color(0xFF8E8CF0)),
    AUDIO("Audio", "ReplayGain, fade on pause, equalizer", Icons.Rounded.VolumeUp, Color(0xFFE0708A)),
    VISUALIZATION("Visualization", "Faded controls, preset changes", Icons.Rounded.GraphicEq, Color(0xFFB07CFF)),
    BACKGROUND("Background", "Blur, intensity, saturation", Icons.Rounded.Wallpaper, Color(0xFF4FB6A0)),
    ALBUM_ART("Album Art", "Quality, cache", Icons.Rounded.Album, Color(0xFF6FBF73)),
    LIBRARY("Library", "Rescan, music folders, categories", Icons.Rounded.LibraryMusic, Color(0xFF4F8DF7)),
    HEADSET("Headset/Bluetooth", "Pause and resume on connection", Icons.Rounded.Headphones, Color(0xFFC9C9D6)),
    LOCK_SCREEN("Lock Screen", "Media controls on the lock screen", Icons.Rounded.Lock, Color(0xFFE59A4F)),
    MISC("Misc", "Remember position, swipe away, error log", Icons.Rounded.MoreHoriz, Color(0xFF4FB6C9)),
    ABOUT("About", "Version, changelog, updates", Icons.Rounded.Info, Color(0xFF9A9AB0))
}

/** Everything searchable: (page, item title). */
private val SEARCH_INDEX: List<Pair<SettingsPage, String>> = listOf(
    SettingsPage.LOOK to "Accent colour", SettingsPage.LOOK to "Keep screen on in the player", SettingsPage.LOOK to "Show tips again",
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
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    fun set(change: (AppSettings) -> AppSettings) = SettingsStore.update(change)

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // ---- top bar: ← title 🔍 ✕ ----
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (searching) { searching = false; query = "" } else if (page != null) onPage(null) else onClose() }) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            if (searching) {
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(color = Color.White, fontSize = 18.sp),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner ->
                        Box {
                            if (query.isEmpty()) Text("Search settings", color = Color.White.copy(alpha = 0.45f), fontSize = 18.sp)
                            inner()
                        }
                    }
                )
            } else {
                Text(page?.title ?: "Settings", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = { searching = true }) { Icon(Icons.Rounded.Search, contentDescription = "Search", tint = Color.White) }
            }
            IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, contentDescription = "Close", tint = Color.White) }
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
                        NavRow(p.icon, p.color, t, p.title) { searching = false; query = ""; onPage(p) }
                    }
                    if (q.isNotEmpty() && hits.isEmpty()) Hint("Nothing found")
                }

                page == null -> {
                    SectionLabel("Settings")
                    SettingsPage.values().forEach { p -> NavRow(p.icon, p.color, p.title, p.subtitle) { onPage(p) } }
                    SectionLabel("Other")
                    NavRow(Icons.Rounded.Upload, Color(0xFF9A9AB0), "Export Settings/Data", "Save EQ, presets, playlists, bookmarks and settings to a file", onExport)
                    NavRow(Icons.Rounded.Download, Color(0xFF9A9AB0), "Import Settings/Data", "Restore from a file saved with Export", onImport)
                    SwitchRow("Keep an error log", "If the app crashes, save the details so you can send them", st.keepCrashLog) { v -> set { it.copy(keepCrashLog = v) } }
                }

                page == SettingsPage.LOOK -> {
                    SectionLabel("Accent colour")
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ACCENTS.forEachIndexed { i, (name, color) ->
                            Box(
                                Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(color)
                                    .border(2.dp, if (st.accent == i) Color.White else Color.Transparent, CircleShape)
                                    .clickable { set { it.copy(accent = i) } },
                                contentAlignment = Alignment.Center
                            ) {
                                if (st.accent == i) Icon(Icons.Rounded.Check, contentDescription = name, tint = Color.Black, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                    SectionLabel("Player screen")
                    SwitchRow("Keep screen on in the player", "The screen stays on while the player is open", st.keepScreenOn) { v -> set { it.copy(keepScreenOn = v) } }
                    SectionLabel("Help")
                    ActionRow("Show tips again", "The first-time tips appear again on each screen") { HintStore.resetAll() }
                    Hint("The app language is English. More languages can be added later.")
                }

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
                    ActionRow("Equalizer, effects and reverb", "Open the sound tab", onOpenEqualizer)
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
                    ActionRow("Rescan library", "Look for new, changed and deleted songs", onRescan)
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
                    val context = LocalContext.current
                    val version = remember {
                        runCatching {
                            @Suppress("DEPRECATION")
                            context.packageManager.getPackageInfo(context.packageName, 0).versionName
                        }.getOrNull() ?: ""
                    }
                    Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) { PowerSoundLogo() }
                    Hint("PowerSound Player $version")
                    ActionRow("Check for updates", "Look for a newer version on GitHub", onCheckUpdate)
                    ActionRow("Changelog", "What changed in each version", onChangelog)
                    ActionRow("Show tips again", "The first-time tips appear again") { HintStore.resetAll() }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- building blocks

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        color = MaterialTheme.colorScheme.primary,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 4.dp)
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        color = Color.White.copy(alpha = 0.5f),
        fontSize = 12.sp,
        modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp)
    )
}

@Composable
private fun NavRow(icon: ImageVector, color: Color, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp)
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 15.sp)
            Text(subtitle, color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary))
    }
}

@Composable
private fun ActionRow(title: String, subtitle: String, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp)
    ) {
        Text(title, color = Color.White, fontSize = 15.sp)
        Text(subtitle, color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp)
    }
}

@Composable
private fun ChoiceRow(title: String, options: List<String>, selected: Int, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ActionRow(title, options.getOrElse(selected) { options.first() }) { open = true }
    if (open) ChoiceDialog(title, options, selected, onPick) { open = false }
}

@Composable
private fun SliderRow(
    title: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean = true,
    onChange: (Float) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp)) {
        Row {
            Text(title, color = if (enabled) Color.White else Color.White.copy(alpha = 0.4f), fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text(valueText, color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            enabled = enabled,
            colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary, activeTrackColor = MaterialTheme.colorScheme.primary)
        )
    }
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
        "Player" to "Swipe the cover to change songs (further = change album/folder). Swipe down or tap the cover for the song's list. Swipe up for lyrics.",
        "Seeking" to "Long press the progress area, move your finger, let go. Tap ⏪ ⏩ to jump 10 seconds.",
        "Categories" to "Long press ⏮ ⏭ for the previous / next album or folder.",
        "Buttons" to "Long press the 🎵, 🔁 and 🔀 buttons for more options. Tap the info line under the time to change it, long press for Audio Info.",
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
