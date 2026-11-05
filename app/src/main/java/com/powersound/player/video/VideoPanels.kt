@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.powersound.player.video

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DisplaySettings
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import com.powersound.player.data.SettingsStore
import com.powersound.player.data.formatTime
import kotlin.math.roundToInt

private val PanelBg = Color(0xF0101016)

/** A panel that slides in on the right like MX Player's; tapping outside closes it. */
@Composable
fun SidePanel(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth > maxHeight
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.35f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
        )
        Box(
            Modifier
                .align(if (wide) Alignment.CenterEnd else Alignment.BottomCenter)
                .then(if (wide) Modifier.fillMaxHeight().width((maxWidth * 0.55f).coerceAtMost(560.dp)) else Modifier.fillMaxWidth().heightIn(max = maxHeight * 0.75f))
                .background(PanelBg)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                .safeDrawingPadding()
        ) { content() }
    }
}

@Composable
private fun PanelTitle(title: String, onBack: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Spacer(Modifier.width(8.dp))
        }
        Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}

// ================================================================= ⋮ panel

/**
 * ⋮ : Aspect Ratio, Speed FF, Display Settings, Playlist, Network Stream, Information, Share, Cut, Bookmark,
 * Tutorial, and the Shortcuts (the tools strip) with which tools it shows.
 */
@Composable
fun MorePanel(
    activity: VideoActivity,
    onOpen: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val vp by VideoPrefsStore.prefs.collectAsState()
    val st by SettingsStore.settings.collectAsState()
    val primary = MaterialTheme.colorScheme.primary
    var page by remember { mutableStateOf("") }
    SidePanel(onDismiss) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            when (page) {
                "aspect" -> {
                    PanelTitle("Aspect Ratio") { page = "" }
                    ASPECTS.forEachIndexed { i, label ->
                        Radio(label, activity.aspect.intValue == i, primary) {
                            activity.aspect.intValue = i
                            if (vp.aspectAll) VideoPrefsStore.update { it.copy(aspect = i) }
                        }
                    }
                    if (activity.aspect.intValue == 7) {
                        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                            NumberField(vp.aspectW, "Width") { v -> VideoPrefsStore.update { it.copy(aspectW = v.coerceIn(1, 100)) } }
                            Text("  :  ", color = Color.White)
                            NumberField(vp.aspectH, "Height") { v -> VideoPrefsStore.update { it.copy(aspectH = v.coerceIn(1, 100)) } }
                        }
                    }
                    Check("Apply to all videos", vp.aspectAll, primary) { on ->
                        VideoPrefsStore.update { it.copy(aspectAll = on, aspect = activity.aspect.intValue) }
                    }
                }
                "speedff" -> {
                    PanelTitle("Speed FF (Long Press)") { page = "" }
                    listOf(15, 20, 25, 30, 40).forEach { v ->
                        Radio("${v / 10f}X${if (v == 20) " (Default)" else ""}", vp.speedFF == v, primary) {
                            VideoPrefsStore.update { it.copy(speedFF = v) }
                        }
                    }
                    Text(
                        "Press and hold the video to watch at this speed; let go to come back to normal.",
                        color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp, modifier = Modifier.padding(20.dp)
                    )
                }
                else -> {
                    Spacer(Modifier.height(12.dp))
                    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        GridButton(Icons.Rounded.AspectRatio, "Aspect Ratio") { page = "aspect" }
                        GridButton(null, "Speed FF\n(Long Press)", text = "${vp.speedFF / 10f}X") { page = "speedff" }
                        GridButton(Icons.Rounded.DisplaySettings, "Display Settings") { onOpen("display") }
                        GridButton(Icons.Rounded.PlaylistPlay, "Playlist") { onOpen("playlist") }
                        GridButton(Icons.Rounded.Language, "Network Stream") { onOpen("stream") }
                        GridButton(Icons.Rounded.Info, "Information") { onOpen("info") }
                        GridButton(Icons.Rounded.Share, "Share") { onDismiss(); activity.share() }
                        if (activity.isLocalFile()) GridButton(Icons.Rounded.ContentCut, "Cut") { onOpen("cut") }
                        GridButton(Icons.Rounded.Bookmark, "Bookmark") { onOpen("bookmarks") }
                        GridButton(Icons.Rounded.Lightbulb, "Tutorial") { onOpen("tutorial") }
                    }
                    Spacer(Modifier.height(12.dp))
                    SwitchLine("Shortcuts", vp.shortcuts) { on -> VideoPrefsStore.update { it.copy(shortcuts = on) } }
                    if (vp.shortcuts) {
                        TwoColumns(VIDEO_TOOL_NAMES.keys.toList()) { id ->
                            val on = id in st.videoTools
                            Check(VIDEO_TOOL_NAMES[id] ?: id, on, primary) {
                                SettingsStore.update { s ->
                                    s.copy(videoTools = if (on) s.videoTools - id
                                    else com.powersound.player.data.DEFAULT_VIDEO_TOOLS.filter { t -> t in s.videoTools || t == id })
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                }
            }
        }
    }
}

val ASPECTS = listOf("Default", "1:1", "3:4", "9:16", "16:9", "4:3", "21:9", "Custom")

/** Width / height of an aspect choice, or null for the video's own shape. */
fun aspectRatioOf(choice: Int, p: VideoPrefs): Float? = when (choice) {
    1 -> 1f
    2 -> 3f / 4f
    3 -> 9f / 16f
    4 -> 16f / 9f
    5 -> 4f / 3f
    6 -> 21f / 9f
    7 -> p.aspectW.toFloat() / p.aspectH.coerceAtLeast(1)
    else -> null
}

val VIDEO_TOOL_NAMES = linkedMapOf(
    "rotation" to "Screen Rotation", "speed" to "Playback Speed", "background" to "Background Play", "loop" to "Loop",
    "mute" to "Mute", "shuffle" to "Shuffle", "eq" to "Equalizer", "sleep" to "Sleep Timer", "ab" to "A - B Repeat",
    "night" to "Night Mode", "screenshot" to "Screenshot"
)

@Composable
private fun GridButton(icon: ImageVector?, label: String, text: String? = null, onClick: () -> Unit) {
    Column(
        Modifier.width(96.dp).padding(vertical = 8.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.size(56.dp).border(1.dp, Color.White.copy(alpha = 0.4f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (icon != null) Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
            else Text(text ?: "", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
        Spacer(Modifier.height(6.dp))
        Text(label, color = Color.White, fontSize = 12.sp, textAlign = TextAlign.Center, lineHeight = 14.sp)
    }
}

// ================================================================= small building blocks

@Composable
fun Radio(label: String, selected: Boolean, accent: Color, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick, colors = RadioButtonDefaults.colors(selectedColor = accent))
        Text(label, color = Color.White, fontSize = 15.sp)
    }
}

@Composable
fun Check(label: String, checked: Boolean, accent: Color, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = { onChange(it) }, colors = CheckboxDefaults.colors(checkedColor = accent))
        Text(label, color = Color.White, fontSize = 14.sp)
    }
}

@Composable
private fun SwitchLine(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, fontSize = 17.sp, modifier = Modifier.weight(1f))
        Switch(checked = on, onCheckedChange = onChange)
    }
}

@Composable
private fun <T> TwoColumns(items: List<T>, cell: @Composable (T) -> Unit) {
    Column(Modifier.padding(horizontal = 12.dp).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.06f)).padding(vertical = 6.dp)) {
        items.chunked(2).forEach { row ->
            Row {
                row.forEach { Box(Modifier.weight(1f)) { cell(it) } }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SliderLine(label: String, value: Float, range: ClosedFloatingPointRange<Float>, valueText: String, enabled: Boolean = true, onChange: (Float) -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White.copy(alpha = if (enabled) 0.9f else 0.4f), fontSize = 14.sp, modifier = Modifier.width(120.dp))
        Slider(
            value = value.coerceIn(range.start, range.endInclusive), onValueChange = onChange, valueRange = range, enabled = enabled,
            colors = SliderDefaults.colors(thumbColor = primary, activeTrackColor = primary), modifier = Modifier.weight(1f)
        )
        Text(valueText, color = Color.White, fontSize = 14.sp, modifier = Modifier.width(58.dp), textAlign = TextAlign.End)
    }
}

@Composable
private fun ChoiceLine(label: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White.copy(alpha = 0.9f), fontSize = 14.sp, modifier = Modifier.width(120.dp))
        Box(Modifier.weight(1f)) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { open = true }.padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(options.getOrElse(selected) { "" }, color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Text("▾", color = Color.White)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEachIndexed { i, o -> DropdownMenuItem(text = { Text(o) }, onClick = { open = false; onSelect(i) }) }
            }
        }
    }
}

/** Colors to choose from (the last ones see-through). */
private val SWATCHES = listOf<Long>(
    0xFFFFFFFF, 0xFFDDDDDD, 0xFF9E9E9E, 0xFF000000, 0xFFFFEB3B, 0xFFFFC107, 0xFFFF9800, 0xFFFF5252,
    0xFFE91E63, 0xFFAB47BC, 0xFF64B5F6, 0xFF2196F3, 0xFF26C6DA, 0xFF66BB6A, 0xFF8BC34A,
    0xCC000000, 0x99000000, 0x66000000, 0x33000000, 0x00000000
).map { it.toInt() }

@Composable
private fun ColorLine(label: String, color: Int, enabled: Boolean = true, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White.copy(alpha = if (enabled) 0.9f else 0.4f), fontSize = 14.sp, modifier = Modifier.weight(1f))
        Swatch(color, selected = false) { if (enabled) open = !open }
    }
    if (open) {
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SWATCHES.forEach { c -> Swatch(c, selected = c == color) { onPick(c); open = false } }
        }
    }
}

@Composable
private fun Swatch(color: Int, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .padding(vertical = 3.dp)
            .size(width = 44.dp, height = 28.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF555555))
            .border(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
    ) {
        // checkerboard hint for see-through colors
        Canvas(Modifier.fillMaxSize()) {
            val s = size.height / 2
            var x = 0f
            var row = 0
            while (x < size.width) {
                drawRect(Color(0xFF888888), topLeft = Offset(x, if (row % 2 == 0) 0f else s), size = androidx.compose.ui.geometry.Size(s, s))
                x += s
                row++
            }
            drawRect(Color(color))
        }
    }
}

@Composable
private fun NumberField(value: Int, label: String, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { t -> text = t.filter { it.isDigit() }.take(3); text.toIntOrNull()?.let(onChange) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.width(100.dp)
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 4.dp))
}

// ================================================================= Display Settings

private val DISPLAY_TABS = listOf("Style", "Screen", "Controls", "Navigation", "Text", "Layout")
val SUB_FONTS = listOf("Default", "Sans", "Serif", "Monospace", "Condensed", "Light", "Medium", "Black")

/** ⋮ → Display Settings, with MX Player's tabs. */
@Composable
fun DisplaySettingsPanel(onDismiss: () -> Unit) {
    val p by VideoPrefsStore.prefs.collectAsState()
    val primary = MaterialTheme.colorScheme.primary
    var tab by remember { mutableIntStateOf(0) }
    fun set(change: (VideoPrefs) -> VideoPrefs) = VideoPrefsStore.update(change)
    SidePanel(onDismiss) {
        Column(Modifier.fillMaxSize()) {
            ScrollableTabRow(selectedTabIndex = tab, containerColor = Color.Transparent, contentColor = Color.White, edgePadding = 8.dp) {
                DISPLAY_TABS.forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t, fontWeight = if (tab == i) FontWeight.Bold else FontWeight.Normal) })
                }
            }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                when (tab) {
                    0 -> {
                        ChoiceLine("Preset", listOf("Default", "Dark", "Light frame", "Colorful"), presetOf(p)) { i -> set { applyPreset(it, i) } }
                        ColorLine("Frame", p.frameColor) { c -> set { it.copy(frameColor = c) } }
                        ColorLine("Controls", p.controlColor) { c -> set { it.copy(controlColor = c) } }
                        ColorLine("Buttons background", p.controlBg) { c -> set { it.copy(controlBg = c) } }
                        ColorLine("Progress bar", if (p.progressColor == 0) primary.toArgb() else p.progressColor) { c -> set { it.copy(progressColor = c) } }
                        ChoiceLine("Progress bar style", listOf("Material", "Flat"), p.progressStyle) { i -> set { it.copy(progressStyle = i) } }
                        Check("Place progress bar below buttons", p.progressBelow, primary) { v -> set { it.copy(progressBelow = v) } }
                    }
                    1 -> {
                        ChoiceLine("Orientation", ROTATIONS.let { listOf("Use video orientation") + it.drop(1) }, p.orientation) { i -> set { it.copy(orientation = i) } }
                        ChoiceLine("Full Screen", listOf("Auto Switch", "On", "Off"), p.fullScreen) { i -> set { it.copy(fullScreen = i) } }
                        ChoiceLine("Soft buttons", listOf("Auto hide", "Hide", "Show"), p.softButtons) { i -> set { it.copy(softButtons = i) } }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.width(150.dp)) { Check("Brightness", p.brightnessOn, primary) { v -> set { it.copy(brightnessOn = v) } } }
                            Box(Modifier.weight(1f)) {
                                SliderLine("", p.brightnessLevel.toFloat(), 0f..15f, "${p.brightnessLevel}", enabled = p.brightnessOn) { v -> set { it.copy(brightnessLevel = v.roundToInt()) } }
                            }
                        }
                        SectionLabel("Always on screen")
                        Row {
                            Box(Modifier.weight(1f)) { Check("Elapsed time", p.osdElapsed, primary) { v -> set { it.copy(osdElapsed = v) } } }
                            Box(Modifier.weight(1f)) { Check("Battery/clock", p.osdClock, primary) { v -> set { it.copy(osdClock = v) } } }
                        }
                        SliderLine("Corner offset", p.osdOffset.toFloat(), 0f..48f, "${p.osdOffset}", enabled = p.osdElapsed || p.osdClock) { v -> set { it.copy(osdOffset = v.roundToInt()) } }
                        ColorLine("Text", p.osdTextColor) { c -> set { it.copy(osdTextColor = c) } }
                        Check("Background", p.osdBackground, primary) { v -> set { it.copy(osdBackground = v) } }
                        SectionLabel("Interface")
                        Check("Screen rotation button", p.rotationButton, primary) { v -> set { it.copy(rotationButton = v) } }
                        Check("Display battery/clock in title bar", p.titleClock, primary) { v -> set { it.copy(titleClock = v) } }
                        Check("Show Remaining Time", p.remainingTime, primary) { v -> set { it.copy(remainingTime = v) } }
                        Check("Keep Screen On (also when paused)", p.keepScreenOn, primary) { v -> set { it.copy(keepScreenOn = v) } }
                        Check("Pause playback if obstructed by another window", p.pauseObstructed, primary) { v -> set { it.copy(pauseObstructed = v) } }
                        Check("Show interface at the startup", p.showUiAtStart, primary) { v -> set { it.copy(showUiAtStart = v) } }
                    }
                    2 -> {
                        ChoiceLine(
                            "Touch action",
                            listOf("Show/hide interface", "Show interface → Pause/Resume", "Show interface + pause/resume", "Pause/resume"),
                            p.touchAction
                        ) { i -> set { it.copy(touchAction = i) } }
                        ChoiceLine("Lock mode", listOf("Lock", "Kids lock", "Kids lock (+Touch effects)"), p.lockMode) { i -> set { it.copy(lockMode = i) } }
                        SectionLabel("Gestures")
                        TwoColumns(
                            listOf(
                                "Seek position" to p.gSeek, "Zoom and Pan" to p.gZoomPan, "Video zoom" to p.gZoom, "Video pan" to p.gPan,
                                "Volume" to p.gVolume, "Brightness" to p.gBrightness, "Play/Pause (Double tap)" to p.gDoubleTapPlay,
                                "Video zoom (Double tap)" to p.gDoubleTapZoom, "FF/RW (Double tap)" to p.gDoubleTapSeek,
                                "Speed FF (Long press)" to p.gLongPressSpeed, "Playback Speed (middle swipe)" to p.gSpeed,
                                "Subtitle up/down" to p.gSubUpDown, "Subtitle zoom" to p.gSubZoom
                            )
                        ) { (label, on) ->
                            Check(label, on, primary) { v ->
                                set {
                                    when (label) {
                                        "Seek position" -> it.copy(gSeek = v)
                                        "Zoom and Pan" -> it.copy(gZoomPan = v)
                                        "Video zoom" -> it.copy(gZoom = v)
                                        "Video pan" -> it.copy(gPan = v)
                                        "Volume" -> it.copy(gVolume = v)
                                        "Brightness" -> it.copy(gBrightness = v)
                                        "Play/Pause (Double tap)" -> it.copy(gDoubleTapPlay = v)
                                        "Video zoom (Double tap)" -> it.copy(gDoubleTapZoom = v)
                                        "FF/RW (Double tap)" -> it.copy(gDoubleTapSeek = v)
                                        "Speed FF (Long press)" -> it.copy(gLongPressSpeed = v)
                                        "Playback Speed (middle swipe)" -> it.copy(gSpeed = v)
                                        "Subtitle up/down" -> it.copy(gSubUpDown = v)
                                        else -> it.copy(gSubZoom = v)
                                    }
                                }
                            }
                        }
                        SectionLabel("Quick Shortcuts")
                        val st by SettingsStore.settings.collectAsState()
                        TwoColumns(VIDEO_TOOL_NAMES.keys.toList()) { id ->
                            val on = id in st.videoTools
                            Check(VIDEO_TOOL_NAMES[id] ?: id, on, primary) {
                                SettingsStore.update { s ->
                                    s.copy(videoTools = if (on) s.videoTools - id
                                    else com.powersound.player.data.DEFAULT_VIDEO_TOOLS.filter { t -> t in s.videoTools || t == id })
                                }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.width(180.dp)) { Check("Interface auto hide", p.autoHide, primary) { v -> set { it.copy(autoHide = v) } } }
                            Box(Modifier.weight(1f)) {
                                SliderLine("", p.autoHideTenths.toFloat(), 10f..100f, "%.1f sec".format(p.autoHideTenths / 10f), enabled = p.autoHide) { v -> set { it.copy(autoHideTenths = v.roundToInt()) } }
                            }
                        }
                        Check("Show interface when locked screen is touched", p.showUiWhenLocked, primary) { v -> set { it.copy(showUiWhenLocked = v) } }
                    }
                    3 -> {
                        SliderLine("Seek Speed (sec/inch)", p.seekSpeed.toFloat(), 5f..120f, "${p.seekSpeed}") { v -> set { it.copy(seekSpeed = v.roundToInt()) } }
                        Check("Forward/backward moving button", p.moveButtons, primary) { v -> set { it.copy(moveButtons = v) } }
                        SliderLine("Move Interval (sec.)", p.moveInterval.toFloat(), 1f..60f, "${p.moveInterval}") { v -> set { it.copy(moveInterval = v.roundToInt()) } }
                        Check("Previous/next button", p.prevNextButtons, primary) { v -> set { it.copy(prevNextButtons = v) } }
                        Check("Display the current position while changing position", p.showSeekPosition, primary) { v -> set { it.copy(showSeekPosition = v) } }
                    }
                    4 -> {
                        ChoiceLine("Font", SUB_FONTS, p.subFont) { i -> set { it.copy(subFont = i) } }
                        SliderLine("Size", p.subSize.toFloat(), 10f..60f, "${p.subSize}") { v -> set { it.copy(subSize = v.roundToInt()) } }
                        SliderLine("Scale", p.subScale.toFloat(), 50f..200f, "${p.subScale}%") { v -> set { it.copy(subScale = (v / 5).roundToInt() * 5) } }
                        ColorLine("Color", p.subColor) { c -> set { it.copy(subColor = c) } }
                        Check("Bold", p.subBold, primary) { v -> set { it.copy(subBold = v) } }
                        Check("Background Color", p.subBgOn, primary) { v -> set { it.copy(subBgOn = v) } }
                        ColorLine("   Background", p.subBgColor, enabled = p.subBgOn) { c -> set { it.copy(subBgColor = c) } }
                        Check("Border", p.subBorderOn, primary) { v -> set { it.copy(subBorderOn = v, subShadow = if (v) false else it.subShadow) } }
                        ColorLine("   Border color", p.subBorderColor, enabled = p.subBorderOn) { c -> set { it.copy(subBorderColor = c) } }
                        Check("Shadow", p.subShadow, primary) { v -> set { it.copy(subShadow = v, subBorderOn = if (v) false else it.subBorderOn) } }
                        Check("Use styles from SSA/ASS subtitles", p.subSsaStyles, primary) { v -> set { it.copy(subSsaStyles = v) } }
                        Check("Use font sizes from the subtitles", p.subSsaSizes, primary) { v -> set { it.copy(subSsaSizes = v) } }
                        Text(
                            "Border and Shadow can't be used together (the subtitle renderer draws one edge style).",
                            color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp, modifier = Modifier.padding(16.dp)
                        )
                    }
                    5 -> {
                        ChoiceLine("Alignment", listOf("Left", "Center", "Right"), p.subAlign) { i -> set { it.copy(subAlign = i) } }
                        SliderLine("Bottom margins", p.subBottom.toFloat(), 0f..50f, "${p.subBottom}") { v -> set { it.copy(subBottom = v.roundToInt()) } }
                        Check("Background", p.subBoxOn, primary) { v -> set { it.copy(subBoxOn = v) } }
                        ColorLine("   Box color", p.subBoxColor, enabled = p.subBoxOn) { c -> set { it.copy(subBoxColor = c) } }
                        Check("Fit subtitles into video size", p.subFitVideo, primary) { v -> set { it.copy(subFitVideo = v) } }
                    }
                }
            }
        }
    }
}

private fun presetOf(p: VideoPrefs): Int = when {
    p.frameColor == 0xCC000000.toInt() && p.controlColor == 0xFFFFFFFF.toInt() && p.progressColor == 0 -> 0
    p.frameColor == 0xFF000000.toInt() -> 1
    p.frameColor == 0x33000000 -> 2
    else -> 3
}

private fun applyPreset(p: VideoPrefs, i: Int): VideoPrefs = when (i) {
    1 -> p.copy(frameColor = 0xFF000000.toInt(), controlColor = 0xFFDDDDDD.toInt(), controlBg = 0x00000000, progressColor = 0xFF9E9E9E.toInt())
    2 -> p.copy(frameColor = 0x33000000, controlColor = 0xFFFFFFFF.toInt(), controlBg = 0x66000000, progressColor = 0)
    3 -> p.copy(frameColor = 0x99000000.toInt(), controlColor = 0xFFFFEB3B.toInt(), controlBg = 0x66000000, progressColor = 0xFF26C6DA.toInt())
    else -> p.copy(frameColor = 0xCC000000.toInt(), controlColor = 0xFFFFFFFF.toInt(), controlBg = 0x73000000, progressColor = 0)
}

// ================================================================= Equalizer (MX style)

/** Equalizer with up/down bands, presets (Custom + the phone's), Reverb and Bass Boost. */
@Composable
fun EqualizerPanel(activity: VideoActivity, onDismiss: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val st = SettingsStore.value
    val vp by VideoPrefsStore.prefs.collectAsState()
    val eq = activity.equalizer
    var on by remember { mutableStateOf(st.videoEqOn) }
    var bass by remember { mutableIntStateOf(st.videoBass) }
    var preset by remember { mutableIntStateOf(-1) }
    val bands = remember { eq?.let { e -> (0 until e.numberOfBands.toInt()).toList() }.orEmpty() }
    val levels = remember {
        mutableStateListOf<Int>().apply { bands.forEach { b -> add(runCatching { eq!!.getBandLevel(b.toShort()).toInt() }.getOrDefault(0)) } }
    }
    val range = remember { runCatching { eq!!.bandLevelRange.let { it[0].toInt() to it[1].toInt() } }.getOrDefault(-1500 to 1500) }
    val presets = remember { eq?.let { e -> (0 until e.numberOfPresets.toInt()).map { it to e.getPresetName(it.toShort()) } }.orEmpty() }
    fun close() {
        activity.saveEqualizer(on, bass)
        onDismiss()
    }
    SidePanel({ close() }) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Equalizer", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(if (on) "On" else "Off", color = Color.White, fontSize = 16.sp)
                Spacer(Modifier.width(8.dp))
                Switch(checked = on, onCheckedChange = { on = it; runCatching { eq?.enabled = it } })
            }
            if (eq == null) {
                Text("This phone has no equalizer for videos.", color = Color.White.copy(alpha = 0.7f))
                return@Column
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (listOf(-1 to "Custom") + presets).forEach { (i, name) ->
                    val sel = preset == i
                    Text(
                        name,
                        color = if (sel) primary else Color.White,
                        fontSize = 15.sp,
                        modifier = Modifier
                            .border(1.5.dp, if (sel) primary else Color.White.copy(alpha = 0.7f), RoundedCornerShape(50))
                            .clip(RoundedCornerShape(50))
                            .clickable {
                                preset = i
                                if (i >= 0) runCatching {
                                    eq.usePreset(i.toShort())
                                    bands.forEachIndexed { k, b -> levels[k] = eq.getBandLevel(b.toShort()).toInt() }
                                }
                                on = true
                                runCatching { eq.enabled = true }
                            }
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                bands.forEachIndexed { k, b ->
                    val hz = runCatching { eq.getCenterFreq(b.toShort()) / 1000 }.getOrDefault(0)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${(levels[k] / 100f).roundToInt()} dB", color = Color.White, fontSize = 13.sp)
                        VerticalSlider(
                            value = (levels[k] - range.first).toFloat() / (range.second - range.first).coerceAtLeast(1),
                            accent = primary,
                            modifier = Modifier.width(44.dp).height(170.dp).padding(vertical = 6.dp)
                        ) { f ->
                            val level = (range.first + f * (range.second - range.first)).roundToInt()
                            levels[k] = level
                            preset = -1
                            runCatching { eq.setBandLevel(b.toShort(), level.toShort()) }
                            if (!on) { on = true; runCatching { eq.enabled = true } }
                        }
                        Text(if (hz >= 1000) "${hz} Hz" else "$hz Hz", color = Color.White, fontSize = 12.sp)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Reverb", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(110.dp))
                Box(Modifier.weight(1f)) {
                    ChoiceLine("", REVERBS, vp.reverb) { i -> activity.setReverb(i) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Bass Boost", color = Color.White, fontSize = 16.sp, modifier = Modifier.width(110.dp))
                Slider(
                    value = bass.toFloat(), onValueChange = { bass = it.roundToInt(); if (!on) on = true }, valueRange = 0f..1000f,
                    colors = SliderDefaults.colors(thumbColor = primary, activeTrackColor = primary), modifier = Modifier.weight(1f)
                )
                Text("${bass / 10}%", color = Color.White, fontSize = 14.sp, modifier = Modifier.width(48.dp), textAlign = TextAlign.End)
            }
            TextButton(onClick = {
                runCatching { bands.forEachIndexed { k, b -> eq.setBandLevel(b.toShort(), 0); levels[k] = 0 } }
                bass = 0
                preset = -1
                activity.setReverb(0)
            }) { Text("Reset") }
        }
    }
}

val REVERBS = listOf("None", "Small Room", "Medium Room", "Large Room", "Medium Hall", "Large Hall", "Plate")

/** An up/down slider (0 bottom … 1 top). */
@Composable
fun VerticalSlider(value: Float, accent: Color, modifier: Modifier = Modifier, onChange: (Float) -> Unit) {
    val shown by animateFloatAsState(value.coerceIn(0f, 1f), label = "band")
    Box(
        modifier
            .pointerInput(Unit) {
                detectTapGestures { o -> onChange((1f - o.y / size.height).coerceIn(0f, 1f)) }
            }
            .pointerInput(Unit) {
                detectVerticalDragGestures { change, _ ->
                    change.consume()
                    onChange((1f - change.position.y / size.height).coerceIn(0f, 1f))
                }
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val x = size.width / 2
            val r = 11.dp.toPx()
            val top = r
            val bottom = size.height - r
            val y = bottom - (bottom - top) * shown
            drawLine(Color.White.copy(alpha = 0.25f), Offset(x, top), Offset(x, bottom), strokeWidth = 4.dp.toPx())
            drawLine(accent, Offset(x, y), Offset(x, bottom), strokeWidth = 4.dp.toPx())
            drawCircle(accent, radius = r, center = Offset(x, y))
        }
    }
}

// ================================================================= small dialogs

@Composable
private fun DarkDialog(title: String, onDismiss: () -> Unit, confirm: @Composable () -> Unit = { TextButton(onClick = onDismiss) { Text("Close") } }, body: @Composable () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1B1B22),
        title = { Text(title, color = Color.White) },
        text = { Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) { body() } },
        confirmButton = confirm
    )
}

/** ⋮ → Playlist: the videos of this list; tap one to play it. */
@Composable
fun PlaylistDialog(player: Player, onDismiss: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    DarkDialog("Playlist", onDismiss) {
        val cur = player.currentMediaItemIndex
        for (i in 0 until player.mediaItemCount) {
            val title = player.getMediaItemAt(i).mediaMetadata.title?.toString() ?: "Video ${i + 1}"
            Text(
                "${i + 1}.  $title",
                color = if (i == cur) primary else Color.White,
                fontWeight = if (i == cur) FontWeight.Bold else FontWeight.Normal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { player.seekTo(i, 0L); onDismiss() }.padding(vertical = 9.dp, horizontal = 4.dp)
            )
        }
    }
}

/** ⋮ → Network Stream: type or paste a web address. */
@Composable
fun NetworkStreamDialog(onPlay: (String) -> Unit, onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // a link already copied (from an anime / movie app or the browser) is filled in
    var url by remember {
        mutableStateOf(
            runCatching {
                val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                VideoRequest.linkIn(cm?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString())
            }.getOrNull() ?: ""
        )
    }
    DarkDialog("Network Stream", onDismiss, confirm = {
        TextButton(enabled = url.startsWith("http"), onClick = { onPlay(url); onDismiss() }) { Text("Play") }
    }) {
        OutlinedTextField(value = url, onValueChange = { url = it.trim() }, singleLine = true, label = { Text("Address (http… .mp4, .m3u8, .mpd)") })
    }
}

@Composable
fun InfoDialog(rows: List<Pair<String, String>>, onDismiss: () -> Unit) {
    DarkDialog("Information", onDismiss) {
        rows.forEach { (k, v) ->
            Text(k, color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            Text(v.ifBlank { "—" }, color = Color.White, fontSize = 14.sp)
        }
    }
}

/** ⋮ → Bookmark: places saved in this video. */
@Composable
fun BookmarksDialog(activity: VideoActivity, onDismiss: () -> Unit) {
    var marks by remember { mutableStateOf(activity.bookmarks()) }
    DarkDialog("Bookmarks", onDismiss, confirm = {
        TextButton(onClick = { activity.addBookmark(); marks = activity.bookmarks() }) { Text("Add here") }
    }) {
        if (marks.isEmpty()) Text("No bookmarks yet. \"Add here\" saves this moment of the video.", color = Color.White.copy(alpha = 0.7f))
        marks.forEach { ms ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.BookmarkAdd, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    formatTime(ms),
                    color = Color.White,
                    fontSize = 16.sp,
                    modifier = Modifier.weight(1f).clickable { activity.playerOrNull()?.seekTo(ms); onDismiss() }.padding(12.dp)
                )
                Box(Modifier.size(40.dp).clip(CircleShape).clickable { activity.removeBookmark(ms); marks = activity.bookmarks() }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Delete, contentDescription = "Delete", tint = Color.White.copy(alpha = 0.7f))
                }
            }
        }
    }
}

/** ⋮ → Cut: choose the start and the end with the video's own position, then save. */
@Composable
fun CutDialog(activity: VideoActivity, onDismiss: () -> Unit) {
    val p = activity.playerOrNull() ?: return
    var start by remember { mutableLongStateOf(p.currentPosition) }
    var end by remember { mutableLongStateOf((p.currentPosition + 30_000).coerceAtMost(p.duration.coerceAtLeast(0L))) }
    var working by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var result by remember { mutableStateOf<String?>(null) }
    DarkDialog("Cut", onDismiss, confirm = {
        if (result != null) TextButton(onClick = onDismiss) { Text("Done") }
        else TextButton(enabled = !working && end > start + 500, onClick = {
            working = true
            activity.cut(start, end, { progress = it }) { msg -> working = false; result = msg }
        }) { Text("Save") }
    }) {
        Text("Saves this part as a new MP4 in Movies/PowerSound, with the same quality. It starts at the nearest key frame.", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Start  ${formatTime(start)}", color = Color.White, modifier = Modifier.weight(1f))
            TextButton(onClick = { start = p.currentPosition }) { Text("Set to now") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("End    ${formatTime(end)}", color = Color.White, modifier = Modifier.weight(1f))
            TextButton(onClick = { end = p.currentPosition }) { Text("Set to now") }
        }
        Text("Length ${formatTime((end - start).coerceAtLeast(0L))}", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
        if (working) {
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        }
        result?.let { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 10.dp)) }
    }
}

/** ⋮ → Tutorial: the gestures. */
@Composable
fun TutorialDialog(onDismiss: () -> Unit) {
    DarkDialog("Gestures", onDismiss) {
        listOf(
            "Tap" to "Show / hide the controls (Display Settings → Controls → Touch action)",
            "Double tap left / right" to "Back / forward (Navigation → Move Interval)",
            "Double tap middle" to "Play / pause",
            "Press and hold" to "Fast speed while you hold (⋮ → Speed FF)",
            "Slide sideways" to "Jump in the video",
            "Left side up / down" to "Brightness",
            "Right side up / down" to "Volume",
            "Middle up / down" to "Playback speed",
            "Two fingers" to "Zoom and move the picture",
            "Drag the subtitles up / down" to "Move them",
            "Pinch on the subtitles" to "Make them bigger or smaller",
            "Lock button" to "Lock the screen (Kids lock: hold the lock icon 2 seconds to unlock)"
        ).forEach { (g, what) ->
            Text(g, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
            Text(what, color = Color.White, fontSize = 13.sp)
        }
    }
}
