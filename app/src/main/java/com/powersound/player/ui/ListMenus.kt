package com.powersound.player.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.rounded.AddToHomeScreen
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.ArrowRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderCopy
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import com.powersound.player.ui.Text
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.powersound.player.MainActivity
import com.powersound.player.R
import com.powersound.player.data.GROUP_VIEWS
import com.powersound.player.data.ListPref

private val SheetBg = Color(0xFF242428)

/** A Poweramp-style card at the bottom of the screen; tapping outside closes it. */
@Composable
fun BottomCard(onDismiss: () -> Unit, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
            )
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .fillMaxWidth()
                    .heightIn(max = maxHeight * 0.85f)
                    .clip(RoundedCornerShape(30.dp))
                    .background(SheetBg)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                    .padding(vertical = 14.dp)
            ) { content() }
        }
    }
}

@Composable
private fun CardTitle(text: String, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 28.dp, end = 16.dp, top = 6.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

@Composable
private fun CardButton(text: String, filled: Boolean = false, onClick: () -> Unit) {
    Text(
        text,
        color = Color.White,
        fontSize = 17.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (filled) Color.White.copy(alpha = 0.18f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 26.dp, vertical = 14.dp)
    )
}

@Composable
private fun MenuItemRow(icon: ImageVector, text: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 28.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(24.dp))
        Text(text, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * ⋮ next to Select on a list (Folders, Albums…): Select Folders, Rescan, Add to Launcher, List Options — the
 * same as Poweramp's.
 */
@Composable
fun ListMenuCard(
    title: String,
    onSelectFolders: (() -> Unit)?,
    onRescan: () -> Unit,
    onAddToLauncher: (() -> Unit)?,
    onListOptions: () -> Unit,
    extra: List<Pair<String, () -> Unit>>,
    onDismiss: () -> Unit,
    /** An album / folder page: its cover, name and "♪ 8 | 34:15" on top (like Poweramp), instead of the plain title. */
    header: ListHeader? = null,
    rescanLabel: String = "Rescan"
) {
    BottomCard(onDismiss) {
        if (header != null) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(songId = header.coverId, modifier = Modifier.size(84.dp), sizePx = 240, cornerDp = 12)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(header.title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 2)
                    if (header.subtitle.isNotEmpty()) Text(header.subtitle, color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp, maxLines = 1)
                    Text(header.info, color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, maxLines = 1)
                }
            }
        } else CardTitle(title)
        Box(Modifier.fillMaxWidth().padding(horizontal = 28.dp).height(1.dp).background(Color.White.copy(alpha = 0.08f)))
        Spacer(Modifier.height(6.dp))
        if (onSelectFolders != null) MenuItemRow(Icons.Rounded.FolderCopy, "Select Folders") { onDismiss(); onSelectFolders() }
        MenuItemRow(Icons.Rounded.Refresh, rescanLabel) { onDismiss(); onRescan() }
        if (onAddToLauncher != null) MenuItemRow(Icons.Rounded.AddToHomeScreen, "Add to Launcher") { onDismiss(); onAddToLauncher() }
        extra.forEach { (label, action) -> MenuItemRow(Icons.Rounded.PlaylistAdd, label) { onDismiss(); action() } }
        MenuItemRow(Icons.Rounded.Tune, "List Options") { onDismiss(); onListOptions() }
    }
}

/** The top of an album / folder page's ⋮ card. */
class ListHeader(val title: String, val subtitle: String, val coverId: Long?, val info: String)

// ================================================================= List Options

/** Sorts of a list of songs (like Poweramp's "List Options: Folder Songs"); the index is kept in [ListPref.sort]. */
val SONG_SORTS = listOf(
    "By track #" to null,
    "By disc and track #" to null,
    "By title" to null,
    "By filename" to null,
    "By path" to null,
    "By artist" to null,
    "By album" to null,
    "By year" to null,
    "By year/album" to null,
    "By date added to Library" to null,
    "By date added/modified" to "Filesystem date/time",
    "By rating" to null,
    "By duration" to null,
    "By time last played" to null,
    "By play count" to null
)

/** How big the rows of a list of songs are (pinch-to-zoom changes it too). */
val SONG_VIEWS = listOf("List - compact", "List", "List - large")

/** ⋮ → List Options of a list of songs: List Memory, Sort (+ Reverse), Show titles only, View As. */
@Composable
fun SongListOptionsCard(
    title: String,
    pref: ListPref,
    /** The list has its own order (an album's tracks, a playlist, Most Played…): "Default order" is offered first. */
    defaultOrder: String?,
    onChange: ((ListPref) -> ListPref) -> Unit,
    onSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary
    BottomCard(onDismiss) {
        CardTitle("List Options: $title") {
            Box(Modifier.size(44.dp).clip(CircleShape).clickable { onDismiss(); onSettings() }, contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Settings, contentDescription = "Library settings", tint = Color.White)
            }
        }
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            Label("List Memory")
            CheckLine("List Position", pref.memory, primary, sub = "The list opens where you left it") { v -> onChange { it.copy(memory = v) } }
            Label("Sort")
            // sort 0 = the list's own order (when it has one), then SONG_SORTS from 1
            if (defaultOrder != null) Radio(defaultOrder, null, pref.sort == 0, primary) { onChange { it.copy(sort = 0) } }
            SONG_SORTS.forEachIndexed { i, (label, sub) ->
                Radio(label, sub, pref.sort == i + 1, primary) { onChange { it.copy(sort = i + 1) } }
            }
            CheckLine("Reverse", pref.reverse, primary) { v -> onChange { it.copy(reverse = v) } }
            Label("Layout")
            CheckLine("Show titles only", pref.titlesOnly, primary) { v -> onChange { it.copy(titlesOnly = v) } }
            Label("View As")
            Row(Modifier.padding(start = 28.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Info, contentDescription = null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Pinch-to-zoom for zooming", color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp)
            }
            SONG_VIEWS.forEachIndexed { i, label ->
                Radio(label, null, pref.view.coerceIn(0, 2) == i, primary) { onChange { it.copy(view = i) } }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.End) {
            CardButton("Close", filled = true, onClick = onDismiss)
        }
    }
}

private val GROUP_SORTS = listOf(
    "By name" to null,
    "By path (case sensitive)" to null,
    "By path" to null,
    "By date added to Library" to null,
    "By date added/modified" to "Filesystem date/time",
    "By number of tracks" to null
)

/** ⋮ → List Options: Sort (+ Reverse), Show titles only, View As. */
@Composable
fun ListOptionsCard(
    title: String,
    pref: ListPref,
    onChange: ((ListPref) -> ListPref) -> Unit,
    onSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary
    BottomCard(onDismiss) {
        CardTitle("List Options: $title") {
            Box(Modifier.size(44.dp).clip(CircleShape).clickable { onDismiss(); onSettings() }, contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Settings, contentDescription = "Library settings", tint = Color.White)
            }
        }
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            Label("Sort")
            GROUP_SORTS.forEachIndexed { i, (label, sub) ->
                Radio(label, sub, pref.sort == i, primary) { onChange { it.copy(sort = i) } }
            }
            CheckLine("Reverse", pref.reverse, primary) { v -> onChange { it.copy(reverse = v) } }
            Label("Layout")
            CheckLine("Show titles only", pref.titlesOnly, primary) { v -> onChange { it.copy(titlesOnly = v) } }
            Label("View As")
            Row(Modifier.padding(start = 28.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Info, contentDescription = null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Pinch-to-zoom for zooming", color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp)
            }
            GROUP_VIEWS.chunked(2).forEachIndexed { row, pair ->
                Row {
                    pair.forEachIndexed { k, label ->
                        val i = row * 2 + k
                        Box(Modifier.weight(1f)) { Radio(label, null, pref.view == i, primary, compact = true) { onChange { it.copy(view = i) } } }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.End) {
            CardButton("Close", filled = true, onClick = onDismiss)
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, color = Color.White.copy(alpha = 0.6f), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 28.dp, top = 12.dp, bottom = 4.dp))
}

@Composable
private fun Radio(label: String, sub: String?, selected: Boolean, accent: Color, compact: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = if (compact) 14.dp else 18.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick, colors = RadioButtonDefaults.colors(selectedColor = Color.White, unselectedColor = Color.White))
        Column {
            Text(label, color = Color.White, fontSize = if (compact) 15.sp else 17.sp, fontWeight = FontWeight.SemiBold)
            if (sub != null) Text(sub, color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
        }
    }
}

@Composable
private fun CheckLine(label: String, checked: Boolean, accent: Color, sub: String? = null, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(start = 18.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked, onCheckedChange = { onChange(it) },
            colors = CheckboxDefaults.colors(checkedColor = Color.White, checkmarkColor = Color.Black, uncheckedColor = Color.White)
        )
        Column {
            Text(label, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            if (sub != null) Text(sub, color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
        }
    }
}

// ================================================================= Add to Launcher

/** ⋮ → Add to Launcher: a home-screen icon that opens and / or plays this list. */
@Composable
fun AddToLauncherCard(onAdd: (play: Boolean, open: Boolean, shuffle: Boolean) -> Unit, onDismiss: () -> Unit) {
    var play by remember { mutableStateOf(true) }
    var open by remember { mutableStateOf(true) }
    var shuffle by remember { mutableStateOf(false) }
    val primary = MaterialTheme.colorScheme.primary
    BottomCard(onDismiss) {
        CardTitle("Add to Launcher")
        CheckLine("Play the list", play, primary, "The icon also starts target list playback") { play = it }
        CheckLine("Open the list", open, primary) { open = it }
        CheckLine("Shuffle", shuffle, primary, "The list is played in the shuffle mode") { shuffle = it }
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            CardButton("Select All") { play = true; open = true; shuffle = true }
            Spacer(Modifier.weight(1f))
            CardButton("Cancel", onClick = onDismiss)
            CardButton("Add", filled = true) {
                onDismiss()
                onAdd(play, open || !play, shuffle)
            }
        }
    }
}

/** What a launcher icon asks for (read by MainActivity). */
const val ACTION_OPEN_LIST = "com.powersound.player.OPEN_LIST"
const val EXTRA_LIST_CAT = "powersound.list.cat"
const val EXTRA_LIST_GROUP = "powersound.list.group"
const val EXTRA_LIST_PLAY = "powersound.list.play"
const val EXTRA_LIST_OPEN = "powersound.list.open"
const val EXTRA_LIST_SHUFFLE = "powersound.list.shuffle"

/** Asks the launcher to pin an icon for a list. Returns false when the launcher can't. */
fun pinListShortcut(
    context: Context,
    label: String,
    category: String,
    groupKey: String?,
    play: Boolean,
    open: Boolean,
    shuffle: Boolean
): Boolean {
    if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) return false
    val intent = Intent(context, MainActivity::class.java)
        .setAction(ACTION_OPEN_LIST)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        .putExtra(EXTRA_LIST_CAT, category)
        .putExtra(EXTRA_LIST_PLAY, play)
        .putExtra(EXTRA_LIST_OPEN, open)
        .putExtra(EXTRA_LIST_SHUFFLE, shuffle)
    if (groupKey != null) intent.putExtra(EXTRA_LIST_GROUP, groupKey)
    val id = "list:" + category + ":" + (groupKey ?: "") + ":" + play + open + shuffle
    val info = ShortcutInfoCompat.Builder(context, id.take(200))
        .setShortLabel(label.take(24).ifBlank { "PowerSound" })
        .setLongLabel(label.take(60).ifBlank { "PowerSound" })
        .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
        .setIntent(intent)
        .build()
    return runCatching { ShortcutManagerCompat.requestPinShortcut(context, info, null) }.getOrDefault(false)
}

// ================================================================= Folders Selection

private class FolderNode(val name: String, val path: String) {
    val children = sortedMapOf<String, FolderNode>(String.CASE_INSENSITIVE_ORDER)
    /** Library folders inside this one (itself included when it holds songs). */
    val folders = ArrayList<String>()
}

private fun buildTree(all: List<String>): List<FolderNode> {
    val root = FolderNode("", "")
    for (f in all) {
        var node = root
        val parts = f.trim('/').split('/').filter { it.isNotEmpty() }
        var path = ""
        for (p in parts) {
            path = if (path.isEmpty()) p else "$path/$p"
            node = node.children.getOrPut(p) { FolderNode(p, path) }
            node.folders.add(f)
        }
    }
    return root.children.values.toList()
}

/**
 * ⋮ → Select Folders: which folders the music library reads. Tick or untick a folder (with everything in it),
 * open it with ▸ to choose inside; Save and Scan reads the library again.
 */
@Composable
fun FoldersSelectionCard(allFolders: List<String>, excluded: Set<String>, onSave: (Set<String>) -> Unit, onDismiss: () -> Unit) {
    var out by remember { mutableStateOf(excluded.toSet()) }
    val open = remember { mutableStateOf(setOf<String>()) }
    val tree = remember(allFolders) { buildTree(allFolders) }
    BottomCard(onDismiss) {
        CardTitle("Folders Selection")
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            if (tree.isEmpty()) Text("No music folders found yet. Rescan first.", color = Color.White.copy(alpha = 0.7f), modifier = Modifier.padding(28.dp))
            tree.forEach { FolderLine(it, 0, out, open) { changed -> out = changed } }
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.08f)))
        Row(Modifier.fillMaxWidth().clickable { out = if (out.isEmpty()) allFolders.toSet() else emptySet() }.padding(18.dp), horizontalArrangement = Arrangement.Center) {
            Text(if (out.isEmpty()) "Unselect All" else "Select All", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.08f)))
        Row(Modifier.fillMaxWidth().clickable { onDismiss(); onSave(out) }.padding(18.dp), horizontalArrangement = Arrangement.Center) {
            Text("Save and Scan", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun FolderLine(
    node: FolderNode,
    depth: Int,
    out: Set<String>,
    open: androidx.compose.runtime.MutableState<Set<String>>,
    onChange: (Set<String>) -> Unit
) {
    val outCount = node.folders.count { it in out }
    val state = when (outCount) {
        0 -> ToggleableState.On
        node.folders.size -> ToggleableState.Off
        else -> ToggleableState.Indeterminate
    }
    val expanded = node.path in open.value
    val hasChildren = node.children.isNotEmpty()
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = hasChildren) { open.value = if (expanded) open.value - node.path else open.value + node.path }
            .padding(start = (16 + depth * 22).dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (expanded) Icons.Rounded.ArrowDropDown else Icons.Rounded.ArrowRight,
            contentDescription = null,
            tint = if (hasChildren) Color.White else Color.Transparent,
            modifier = Modifier.size(30.dp)
        )
        Icon(Icons.Rounded.Folder, contentDescription = null, tint = Color.White, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(node.name, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text("${node.folders.size} folder${if (node.folders.size == 1) "" else "s"}", color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp)
        }
        TriStateCheckbox(
            state = state,
            onClick = { onChange(if (state == ToggleableState.On) out + node.folders else out - node.folders.toSet()) },
            colors = CheckboxDefaults.colors(checkedColor = Color.White, checkmarkColor = Color.Black, uncheckedColor = Color.White)
        )
    }
    if (expanded) node.children.values.forEach { FolderLine(it, depth + 1, out, open, onChange) }
}
