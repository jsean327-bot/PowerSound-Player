package com.powersound.player.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.rounded.AddToQueue
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.rounded.Add
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.runtime.produceState
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.powersound.player.data.Category
import com.powersound.player.data.Group
import com.powersound.player.data.Song
import com.powersound.player.data.formatTime
import com.powersound.player.data.letterKey
import kotlinx.coroutines.launch

// ================================================================= Library home

@Composable
fun LibraryHome(
    loading: Boolean,
    isEmpty: (Category) -> Boolean,
    bottomInset: Dp,
    onOpen: (Category) -> Unit,
    onRescan: () -> Unit,
    hidden: Set<String> = emptySet(),
    onSettings: () -> Unit = {}
) {
    var menu by remember { mutableStateOf(false) }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = bottomInset + 12.dp)
    ) {
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Library", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.weight(1f))
                if (loading) {
                    CircularProgressIndicator(Modifier.padding(end = 12.dp).width(20.dp).height(20.dp), strokeWidth = 2.dp)
                }
                Box {
                    PillButton(icon = Icons.Rounded.MoreVert, onClick = { menu = true })
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Rescan library") }, onClick = { menu = false; onRescan() })
                        DropdownMenuItem(text = { Text("Library settings") }, onClick = { menu = false; onSettings() })
                    }
                }
            }
        }
        items(Category.values().filter { it.name !in hidden }, key = { it.name }) { cat ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(cat) }
                    .padding(horizontal = 16.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CategoryIcon(cat.icon(), cat.color())
                Spacer(Modifier.width(16.dp))
                Text(cat.label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                if (isEmpty(cat)) {
                    Spacer(Modifier.width(8.dp))
                    Text("empty", fontSize = 10.sp, color = Color.White.copy(alpha = 0.5f))
                }
            }
        }
    }
}

// ================================================================= grid of groups

@Composable
fun GroupGridScreen(
    title: String,
    icon: ImageVector,
    iconColor: Color,
    backLabel: String,
    groups: List<Group>,
    bottomInset: Dp,
    onBack: () -> Unit,
    onOpen: (Group) -> Unit,
    onPlay: (List<Song>) -> Unit,
    onShuffle: (List<Song>) -> Unit,
    onAddToQueue: (List<Song>) -> Unit,
    extraMenu: List<Pair<String, () -> Unit>> = emptyList(),
    /** Multi-select of albums / folders / artists… (null: none). */
    actions: ListActions? = null,
    /** ⋮ menu (Poweramp's): Rescan, Select Folders, Add to Launcher, library settings. */
    menu: ListMenuActions? = null
) {
    val state = rememberLazyGridState()
    val st by com.powersound.player.data.SettingsStore.settings.collectAsState()
    val listPrefs by com.powersound.player.data.ListPrefs.all.collectAsState()
    val pref = listPrefs[title] ?: com.powersound.player.data.ListPref()
    fun setPref(change: (com.powersound.player.data.ListPref) -> com.powersound.player.data.ListPref) =
        com.powersound.player.data.ListPrefs.update(title, change)
    val view = pref.view
    val columns = com.powersound.player.data.gridColumns(view)
    val isGrid = view in setOf(0, 7, 8, 9)
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    var query by rememberSaveable { mutableStateOf<String?>(null) }
    var sheet by remember { mutableStateOf<String?>(null) }

    // List Options → By date added/modified: the files' own dates (read in the background)
    val fileDates by produceState<Map<String, Long>>(emptyMap(), groups, pref.sort) {
        if (pref.sort == 4) value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            groups.associate { g ->
                g.key to g.songs.maxOfOrNull { s -> runCatching { java.io.File(s.path).lastModified() }.getOrDefault(0L) }.let { it ?: 0L }
            }
        }
    }
    val favs by com.powersound.player.data.Favorites.state.collectAsState()
    val shown = remember(groups, query, pref.sort, pref.reverse, fileDates, favs.pinned) {
        val q = query?.trim()?.lowercase().orEmpty()
        val list = if (q.isEmpty()) groups else groups.filter { it.title.lowercase().contains(q) || it.subtitle.lowercase().contains(q) }
        fun pathOf(g: Group) = if (g.key.startsWith("folder:")) g.key.removePrefix("folder:") else g.songs.firstOrNull()?.path ?: g.title
        val sorted = when (pref.sort) {
            1 -> list.sortedBy { pathOf(it) }
            2 -> list.sortedBy { pathOf(it).lowercase() }
            3 -> list.sortedByDescending { g -> g.songs.maxOfOrNull { it.dateAdded } ?: 0L }
            4 -> list.sortedByDescending { fileDates[it.key] ?: 0L }
            5 -> list.sortedByDescending { it.songs.size }
            else -> list
        }
        val ordered = if (pref.reverse) sorted.reversed() else sorted
        // pinned ones (Select → 📌) always at the top, in the list's order
        val (pinned, rest) = ordered.partition { favs.isPinned(it.key) }
        pinned + rest
    }
    val pinnedCount = remember(shown, favs.pinned) { shown.count { favs.isPinned(it.key) } }
    val allSongs = remember(shown) { shown.flatMap { it.songs } }
    val keys = remember(shown) { shown.map { letterKey(com.powersound.player.data.MusicRepository.sortKey(it.title)) } }
    val lazyKeys = remember(shown) { uniqueKeys(shown) { it.key } }
    val headerItems = if (query != null) 2 else 1
    val scrolled by remember { derivedStateOf { state.firstVisibleItemIndex > 0 } }

    // ---- multi-select of tiles: the keys of the chosen groups (null = not selecting)
    var selected by remember { mutableStateOf<Set<String>?>(null) }
    val selecting = selected != null && actions != null
    fun toggle(key: String) {
        val s = selected ?: return
        selected = if (key in s) s - key else s + key
    }
    androidx.activity.compose.BackHandler(enabled = selecting) { selected = null }

    val pills: @Composable (Modifier) -> Unit = { m ->
        Box(m) {
            ActionPills(
                // Settings → Library → Shuffle → Category Items Shuffle
                onShuffle = {
                    when (st.categoryShuffle) {
                        1 -> onPlay(shown.shuffled().flatMap { it.songs })
                        2 -> onPlay(shown.flatMap { it.songs.shuffled() })
                        else -> onShuffle(allSongs)
                    }
                },
                onPlay = { onPlay(allSongs) },
                onSearch = { query = if (query == null) "" else null },
                onMore = { sheet = "menu" },
                enabled = allSongs.isNotEmpty(),
                searchActive = query != null,
                onSelect = if (actions != null && shown.isNotEmpty()) ({ selected = emptySet() }) else null
            )
        }
    }

    // pinch: grids get bigger / smaller tiles, lists bigger / smaller rows
    val gridOrder = listOf(9, 8, 0, 7)
    val listOrder = listOf(6, 5, 4, 3, 2, 1)
    fun zoom(bigger: Boolean) {
        val order = if (isGrid) gridOrder else listOrder
        val i = order.indexOf(view).coerceAtLeast(0)
        val next = order[(if (bigger) i - 1 else i + 1).coerceIn(0, order.size - 1)]
        if (next != view) setPref { it.copy(view = next) }
    }

    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = state,
            modifier = Modifier
                .fillMaxSize()
                .pinchToZoom(view, onZoomIn = { zoom(true) }, onZoomOut = { zoom(false) }),
            contentPadding = PaddingValues(start = if (isGrid) 10.dp else 0.dp, end = 26.dp, bottom = bottomInset + 70.dp),
            horizontalArrangement = Arrangement.spacedBy(if (isGrid) (if (columns >= 4) 6.dp else 10.dp) else 0.dp),
            verticalArrangement = Arrangement.spacedBy(if (isGrid) (if (columns >= 4) 6.dp else 10.dp) else 0.dp)
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                PageHeader(backLabel, title, icon, iconColor, onBack) { if (st.headerButtons) pills(Modifier) }
            }
            if (query != null) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    FilterField(query ?: "", { query = it }, { query = null })
                }
            }
            itemsIndexed(shown, key = { i, _ -> lazyKeys[i] }) { _, g ->
                val checked = if (selecting) selected?.contains(g.key) == true else null
                val longClick: (() -> Unit)? = if (actions != null) ({ if (selecting) toggle(g.key) else selected = setOf(g.key) }) else null
                val click = { if (selecting) toggle(g.key) else onOpen(g) }
                val pin = favs.isPinned(g.key)
                val fav = favs.isFav(g.key)
                if (isGrid) {
                    Box {
                        GroupTile(
                            g.title, if (pref.titlesOnly || view == 0 || view == 7) "" else g.subtitle, g.coverId, g.key,
                            checked = checked, onLongClick = longClick, small = columns >= 3, onClick = click
                        )
                        if (pin || fav) GroupBadges(pin, fav, Modifier.align(Alignment.TopStart).padding(6.dp))
                    }
                } else {
                    GroupRow(g, view, pref.titlesOnly, checked, longClick, click, pin, fav)
                }
            }
        }

        if (pref.sort == 0 && !pref.reverse && st.azScroll && pinnedCount == 0) {
            FastScroller(
                itemKeys = keys,
                onJump = { i -> scope.launch { state.scrollToItem(i + headerItems) } },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .statusBarsPadding()
                    .padding(top = 90.dp, bottom = bottomInset + 10.dp)
                    .fillMaxHeight()
            )
        }

        val sel = selected
        if (selecting && sel != null && actions != null) {
            val chosenGroups = shown.filter { it.key in sel }
            val chosen = chosenGroups.flatMap { it.songs }
            fun done(f: (List<Song>) -> Unit) { if (chosen.isNotEmpty()) { f(chosen); selected = null } }
            SelectionBar(
                count = chosen.size,
                allSelected = shown.isNotEmpty() && shown.all { it.key in sel },
                deleteRemoves = actions.deleteRemoves,
                bottomInset = bottomInset,
                label = "${chosenGroups.size} selected • ${chosen.size} songs",
                onAll = { selected = if (shown.all { it.key in sel }) emptySet() else shown.map { it.key }.toSet() },
                onClose = { selected = null },
                favorite = chosenGroups.isNotEmpty() && chosenGroups.all { favs.isFav(it.key) },
                onFavorite = {
                    if (chosenGroups.isNotEmpty()) {
                        val now = com.powersound.player.data.Favorites.toggleGroups(chosenGroups.map { it.key })
                        android.widget.Toast.makeText(
                            context, if (now) "Added to favorites ♥" else "Removed from favorites", android.widget.Toast.LENGTH_SHORT
                        ).show()
                        selected = null
                    }
                },
                pinned = chosenGroups.isNotEmpty() && chosenGroups.all { favs.isPinned(it.key) },
                onPin = {
                    if (chosenGroups.isNotEmpty()) {
                        val now = com.powersound.player.data.Favorites.togglePins(chosenGroups.map { it.key })
                        android.widget.Toast.makeText(
                            context, if (now) "Pinned to the top" else "Unpinned", android.widget.Toast.LENGTH_SHORT
                        ).show()
                        selected = null
                    }
                },
                onPlay = { done { onPlay(it) } },
                onPlayNext = { done(actions.playNext) },
                onQueue = { done(actions.addToQueue) },
                onPlaylist = { done(actions.addToPlaylist) },
                onTags = actions.editTags?.let { edit -> { done(edit) } },
                onDelete = { done(actions.delete) }
            )
        } else {
            BottomPills(st.bottomButtons, scrolled || !st.headerButtons, bottomInset) { pills(Modifier) }
        }
    }

    // ---- ⋮ menu and its cards (like Poweramp)
    when (sheet) {
        "menu" -> ListMenuCard(
            title = title,
            onSelectFolders = menu?.allFolders?.let { { sheet = "folders" } },
            onRescan = { menu?.onRescan?.invoke() },
            onAddToLauncher = menu?.category?.let { { sheet = "launcher" } },
            onListOptions = { sheet = "options" },
            extra = listOf("Add all to queue" to { onAddToQueue(allSongs) }) + extraMenu,
            onDismiss = { if (sheet == "menu") sheet = null }
        )
        "options" -> ListOptionsCard(
            title = title,
            pref = pref,
            onChange = { setPref(it) },
            onSettings = { menu?.onSettings?.invoke() },
            onDismiss = { sheet = null }
        )
        "launcher" -> AddToLauncherCard(
            onAdd = { play, open, shuffle ->
                val ok = pinListShortcut(context, title, menu?.category ?: "", null, play, open, shuffle)
                if (!ok) android.widget.Toast.makeText(context, "This home screen doesn't accept app shortcuts", android.widget.Toast.LENGTH_SHORT).show()
            },
            onDismiss = { sheet = null }
        )
        "folders" -> FoldersSelectionCard(
            allFolders = menu?.allFolders?.invoke().orEmpty(),
            excluded = st.excludedFolders,
            onSave = { out ->
                com.powersound.player.data.SettingsStore.update { it.copy(excludedFolders = out) }
                menu?.onRescan?.invoke()
            },
            onDismiss = { sheet = null }
        )
    }
}

/** What the ⋮ menu of a list can do (given by the app screen). */
class ListMenuActions(
    val onRescan: () -> Unit,
    /** Library folders (for Select Folders), or null to hide it. */
    val allFolders: (() -> List<String>)?,
    /** The Category name (for Add to Launcher), or null to hide it. */
    val category: String?,
    val onSettings: () -> Unit
)

/** A group as a row (List views of List Options). */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun GroupRow(
    g: Group,
    view: Int,
    titlesOnly: Boolean,
    checked: Boolean?,
    onLongClick: (() -> Unit)?,
    onClick: () -> Unit,
    pinned: Boolean = false,
    favorite: Boolean = false
) {
    val art = when (view) { 6 -> 72; 5 -> 56; 4 -> 44; 2 -> 40; else -> 0 }
    val oneLine = view == 2 || view == 1 || titlesOnly
    val primary = MaterialTheme.colorScheme.primary
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (checked == true) primary.copy(alpha = 0.18f) else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = if (view == 1 || view == 4) 5.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (checked != null) {
            Icon(
                if (checked) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (checked) primary else Color.White.copy(alpha = 0.6f),
                modifier = Modifier.padding(end = 10.dp).size(24.dp)
            )
        }
        if (art > 0) {
            val person = g.key.startsWith("artist:") || g.key.startsWith("albumartist:") || g.key.startsWith("composer:")
            Box(Modifier.size(art.dp).clip(if (person) CircleShape else RoundedCornerShape(10.dp))) {
                if (person) ArtistArtwork(g.title, g.coverId, Modifier.fillMaxSize(), sizePx = art * 3)
                else Artwork(g.coverId, Modifier.fillMaxSize(), sizePx = art * 3, cornerDp = 0)
            }
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                g.title,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = if (view == 1) 13.sp else 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!oneLine) {
                Text(g.subtitle, color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (pinned || favorite) GroupBadges(pinned, favorite, Modifier.padding(start = 8.dp))
        Text("${g.songs.size}", color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp))
    }
}

/** Small 📌 / ♥ marks on a pinned / favorite album, artist or folder. */
@Composable
private fun GroupBadges(pinned: Boolean, favorite: Boolean, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (pinned) MarkBadge(Icons.Rounded.PushPin, MaterialTheme.colorScheme.primary)
        if (favorite) MarkBadge(Icons.Rounded.Favorite, Color(0xFFE85D8A))
    }
}

@Composable
private fun MarkBadge(icon: ImageVector, tint: Color) {
    Box(
        Modifier.size(22.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
    }
}

/** Settings → Library → Lists → Bottom Buttons: 0 off, 1 semi-transparent, 2 on. Shown while scrolled, or always without header buttons. */
@Composable
fun BottomPills(mode: Int, visible: Boolean, bottomInset: Dp, content: @Composable () -> Unit) {
    if (mode == 0) return
    FloatingPills(visible = visible, bottomInset = bottomInset) {
        Box(Modifier.alpha(if (mode == 1) 0.55f else 1f)) { content() }
    }
}

@Composable
fun FloatingPills(visible: Boolean, bottomInset: Dp, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 12.dp, end = 12.dp, bottom = bottomInset + 6.dp)
                .followBottomBar(bottomInset)
        ) {
            androidx.compose.runtime.CompositionLocalProvider(LocalFloatingPills provides true) { content() }
        }
    }
}

/**
 * While the bottom bar slides away (scrolling down a list), floating things go down with it to the bottom of
 * the screen, like Poweramp; they come back up with the bar.
 */
@Composable
fun Modifier.followBottomBar(bottomInset: Dp): Modifier {
    val hide = LocalNavHide.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val navBars = androidx.compose.foundation.layout.WindowInsets.navigationBars
        .asPaddingValues().calculateBottomPadding()
    val distance = with(density) { (bottomInset - navBars - 4.dp).coerceAtLeast(0.dp).toPx() }
    return this.graphicsLayer { translationY = hide.value * distance }
}

@Composable
fun FilterField(value: String, onChange: (String) -> Unit, onClose: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.10f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Rounded.Search, contentDescription = null, tint = Color.White.copy(alpha = 0.7f))
        Spacer(Modifier.width(10.dp))
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) Text("Filter", color = Color.White.copy(alpha = 0.45f), fontSize = 16.sp)
                    inner()
                }
            }
        )
        Icon(
            Icons.Rounded.Close,
            contentDescription = "Close",
            tint = Color.White.copy(alpha = 0.8f),
            modifier = Modifier.clickable(onClick = onClose)
        )
    }
}

// ================================================================= song lists

/** What a song list can do with several selected songs (multi-select). */
data class ListActions(
    val playNext: (List<Song>) -> Unit,
    val addToQueue: (List<Song>) -> Unit,
    val addToPlaylist: (List<Song>) -> Unit,
    /** Deletes the files; in a playlist it only takes the songs out of the playlist ([deleteRemoves]). */
    val delete: (List<Song>) -> Unit,
    val deleteRemoves: Boolean = false,
    /** Multi-select → Tags: edit the tags of all the selected songs at once. */
    val editTags: ((List<Song>) -> Unit)? = null,
    /** Long press: the song menu, with a "Select" item that starts multi-select in this list. */
    val menuWithSelect: (Song, () -> Unit) -> Unit
)

/**
 * A list of songs with any header on top (category header or album-art header).
 * Has filter, A–Z scroller (optional) and the floating action pills.
 */
@Composable
fun SongListScreen(
    songs: List<Song>,
    currentId: Long?,
    bottomInset: Dp,
    showIndex: Boolean,
    emptyText: String,
    onPlay: (List<Song>, Int) -> Unit,
    onShuffle: (List<Song>) -> Unit,
    onSongMenu: (Song) -> Unit,
    onAddToQueue: (List<Song>) -> Unit,
    extraMenu: List<Pair<String, () -> Unit>> = emptyList(),
    rowExtra: ((Song) -> String?)? = null,
    /** "album", "folder", "albumartist" or "" (for the Settings → Library → Lists labels). */
    listKind: String = "",
    /** Multi-select (null: the list has none). */
    actions: ListActions? = null,
    header: @Composable (pills: @Composable () -> Unit) -> Unit
) {
    val state = rememberLazyListState()
    val st by com.powersound.player.data.SettingsStore.settings.collectAsState()
    var zoom by rememberSaveable { mutableIntStateOf(1) }
    var sort by rememberSaveable { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    val songFavs by com.powersound.player.data.Favorites.state.collectAsState()
    val songContext = androidx.compose.ui.platform.LocalContext.current
    val shown = remember(songs, query, sort) {
        val q = query?.trim()?.lowercase().orEmpty()
        val list = if (q.isEmpty()) songs else songs.filter {
            it.title.lowercase().contains(q) || it.artist.lowercase().contains(q) || it.album.lowercase().contains(q)
        }
        when (sort) {
            1 -> list.sortedBy { it.title.lowercase() }
            2 -> list.sortedWith(compareBy({ it.artist.lowercase() }, { it.album.lowercase() }, { it.track }))
            3 -> list.sortedWith(compareBy({ it.album.lowercase() }, { it.track }))
            4 -> list.sortedByDescending { it.durationMs }
            5 -> list.sortedByDescending { it.dateAdded }
            6 -> list.sortedByDescending { it.year }
            else -> list
        }
    }
    val indexOn = showIndex && st.azScroll && (sort == 0 || sort == 1)
    val keys = remember(shown, indexOn) { if (indexOn) shown.map { letterKey(it.title) } else emptyList() }
    // a playlist may hold the same song twice
    val lazyKeys = remember(shown) { uniqueKeys(shown) { it.id } }
    val headerItems = if (query != null) 2 else 1
    val scrolled by remember { derivedStateOf { state.firstVisibleItemIndex > 0 } }

    // ---- multi-select: the ids of the selected songs (null = not selecting) ----
    var selected by remember { mutableStateOf<Set<Long>?>(null) }
    val selecting = selected != null && actions != null
    fun toggle(id: Long) {
        val s = selected ?: return
        selected = if (id in s) s - id else s + id
    }
    androidx.activity.compose.BackHandler(enabled = selecting) { selected = null }

    val pills: @Composable () -> Unit = {
        Box {
            ActionPills(
                onShuffle = { onShuffle(shown) },
                onPlay = { onPlay(shown, 0) },
                onSearch = { query = if (query == null) "" else null },
                onMore = { menu = true },
                enabled = shown.isNotEmpty(),
                searchActive = query != null,
                onSelect = if (actions != null && shown.isNotEmpty()) ({ selected = emptySet() }) else null
            )
            Box(Modifier.align(Alignment.TopEnd)) {
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (actions != null && shown.isNotEmpty()) {
                        DropdownMenuItem(text = { Text("Select songs") }, onClick = { menu = false; selected = emptySet() })
                    }
                    DropdownMenuItem(text = { Text("Add all to queue") }, onClick = { menu = false; onAddToQueue(shown) })
                    extraMenu.forEach { (label, action) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = { menu = false; action() })
                    }
                    MenuHeader("List options • sort")
                    listOf("Default order", "Title", "Artist", "Album", "Longest first", "Recently added", "Newest year").forEachIndexed { i, label ->
                        DropdownMenuItem(
                            text = { Text(if (sort == i) "✓  $label" else "     $label") },
                            onClick = { sort = i; menu = false }
                        )
                    }
                    MenuHeader("Zoom (or pinch the list)")
                    DropdownMenuItem(text = { Text("Bigger rows") }, onClick = { if (zoom < 2) zoom++; menu = false })
                    DropdownMenuItem(text = { Text("Smaller rows") }, onClick = { if (zoom > 0) zoom--; menu = false })
                }
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = state,
            modifier = Modifier
                .fillMaxSize()
                .pinchToZoom(zoom, onZoomIn = { if (zoom < 2) zoom++ }, onZoomOut = { if (zoom > 0) zoom-- }),
            contentPadding = PaddingValues(end = if (indexOn) 18.dp else 0.dp, bottom = bottomInset + 70.dp)
        ) {
            item { header(if (st.headerButtons) pills else NoPills) }
            if (query != null) {
                item { FilterField(query ?: "", { query = it }, { query = null }) }
            }
            if (shown.isEmpty()) {
                item {
                    Text(
                        emptyText,
                        color = Color.White.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp)
                    )
                }
            }
            itemsIndexed(shown, key = { i, _ -> lazyKeys[i] }) { i, s ->
                SongRow(
                    song = s,
                    isCurrent = s.id == currentId,
                    onClick = { if (selecting) toggle(s.id) else onPlay(shown, i) },
                    onLongClick = {
                        when {
                            selecting -> toggle(s.id)
                            actions != null -> actions.menuWithSelect(s) { selected = setOf(s.id) }
                            else -> onSongMenu(s)
                        }
                    },
                    zoom = zoom,
                    extra = rowExtra?.invoke(s),
                    listKind = listKind,
                    checked = if (selecting) selected?.contains(s.id) == true else null
                )
            }
        }

        if (indexOn && shown.size > 20) {
            FastScroller(
                itemKeys = keys,
                onJump = { i -> scope.launch { state.scrollToItem(i + headerItems) } },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .statusBarsPadding()
                    .padding(top = 90.dp, bottom = bottomInset + 10.dp)
                    .fillMaxHeight()
            )
        }

        val sel = selected
        if (selecting && sel != null && actions != null) {
            // the chosen songs in list order
            val chosen = shown.filter { it.id in sel }
            fun done(f: (List<Song>) -> Unit) { if (chosen.isNotEmpty()) { f(chosen); selected = null } }
            SelectionBar(
                count = chosen.size,
                allSelected = shown.isNotEmpty() && shown.all { it.id in sel },
                deleteRemoves = actions.deleteRemoves,
                bottomInset = bottomInset,
                onAll = { selected = if (shown.all { it.id in sel }) emptySet() else shown.map { it.id }.toSet() },
                onClose = { selected = null },
                favorite = chosen.isNotEmpty() && chosen.all { songFavs.isFav(it.id) },
                onFavorite = {
                    if (chosen.isNotEmpty()) {
                        val now = com.powersound.player.data.Favorites.toggleSongs(chosen.map { it.id })
                        android.widget.Toast.makeText(
                            songContext, if (now) "Added to Favorite Songs ♥" else "Removed from Favorite Songs", android.widget.Toast.LENGTH_SHORT
                        ).show()
                        selected = null
                    }
                },
                onPlay = { done { onPlay(it, 0) } },
                onPlayNext = { done(actions.playNext) },
                onQueue = { done(actions.addToQueue) },
                onPlaylist = { done(actions.addToPlaylist) },
                onTags = actions.editTags?.let { edit -> { done(edit) } },
                onDelete = { done(actions.delete) }
            )
        } else {
            BottomPills(st.bottomButtons, scrolled || !st.headerButtons, bottomInset) { pills() }
        }
    }
}

/** Multi-select bar at the bottom of a song list: what to do with the selected songs. */
@Composable
private fun SelectionBar(
    count: Int,
    allSelected: Boolean,
    deleteRemoves: Boolean,
    bottomInset: Dp,
    /** "3 albums • 42 songs" (null: "N songs selected"). */
    label: String? = null,
    onAll: () -> Unit,
    onClose: () -> Unit,
    onPlay: () -> Unit,
    onPlayNext: () -> Unit,
    onQueue: () -> Unit,
    onPlaylist: () -> Unit,
    onTags: (() -> Unit)?,
    onDelete: () -> Unit,
    /** ♥: favorite / unfavorite the selection (null: hidden). */
    favorite: Boolean = false,
    onFavorite: (() -> Unit)? = null,
    /** 📌: pin / unpin at the top of the list (null: hidden). */
    pinned: Boolean = false,
    onPin: (() -> Unit)? = null
) {
    val primary = MaterialTheme.colorScheme.primary
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 10.dp, end = 10.dp, bottom = bottomInset + 6.dp)
                .followBottomBar(bottomInset)
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(Color(0xFF1E1E27).copy(alpha = 0.97f))
                .border(1.dp, PillBorder, RoundedCornerShape(22.dp))
                .padding(horizontal = 6.dp, vertical = 6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SelIcon(Icons.Rounded.Close, "Done", true, onClose)
                Text(
                    label ?: if (count == 1) "1 song selected" else "$count songs selected",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    modifier = Modifier.weight(1f)
                )
                if (onFavorite != null) {
                    SelIcon(if (favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (favorite) "Unfavorite" else "Favorite", count > 0, onFavorite)
                }
                if (onPin != null) {
                    SelIcon(if (pinned) Icons.Rounded.PushPin else Icons.Outlined.PushPin, if (pinned) "Unpin" else "Pin to top", count > 0, onPin)
                }
                Text(
                    if (allSelected) "None" else "All",
                    color = primary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable(onClick = onAll)
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                val on = count > 0
                SelAction(Icons.Rounded.PlayArrow, "Play", on, onPlay)
                SelAction(Icons.Rounded.PlaylistPlay, "Play next", on, onPlayNext)
                SelAction(Icons.Rounded.AddToQueue, "Queue", on, onQueue)
                SelAction(Icons.Rounded.PlaylistAdd, "Playlist", on, onPlaylist)
                if (onTags != null) SelAction(Icons.Rounded.Edit, "Tags", on, onTags)
                SelAction(
                    if (deleteRemoves) Icons.Rounded.RemoveCircleOutline else Icons.Rounded.Delete,
                    if (deleteRemoves) "Remove" else "Delete",
                    on,
                    onDelete
                )
            }
        }
    }
}

@Composable
private fun SelIcon(icon: ImageVector, text: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = text, tint = Color.White)
    }
}

@Composable
private fun SelAction(icon: ImageVector, text: String, enabled: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .alpha(if (enabled) 1f else 0.4f),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = text, tint = Color.White, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(2.dp))
        Text(text, color = Color.White.copy(alpha = 0.85f), fontSize = 11.sp, maxLines = 1)
    }
}

/** Header with the big cover art (album / folder / artist page). */
@Composable
fun ArtHeader(group: Group, backLabel: String, onBack: () -> Unit, pills: @Composable () -> Unit) {
    val bg = MaterialTheme.colorScheme.background
    val st by com.powersound.player.data.SettingsStore.settings.collectAsState()
    Box(
        Modifier
            .fillMaxWidth()
            .height(340.dp)
    ) {
        Artwork(
            songId = group.coverId,
            modifier = Modifier.fillMaxSize(),
            sizePx = 1024,
            cornerDp = 0,
            placeholder = { BarsPlaceholder() }
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.25f),
                        0.45f to Color.Transparent,
                        1f to bg.copy(alpha = 0.85f)
                    )
                )
        )
        Box(
            Modifier
                .statusBarsPadding()
                .padding(10.dp)
        ) { BackChip(backLabel, onBack) }
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = 10.dp, end = 10.dp, bottom = 10.dp)
        ) {
            OverlayChip(group.title, big = true)
            Spacer(Modifier.height(4.dp))
            OverlayChip(group.subtitle)
            Spacer(Modifier.height(4.dp))
            // Settings → Library → Lists → Headers With Meta
            if (st.headersMeta) {
                OverlayChip("♪ ${group.songs.size} | ${formatTime(group.totalMs)}")
            }
            Spacer(Modifier.height(10.dp))
            pills()
        }
    }
}

private val NoPills: @Composable () -> Unit = {}

// ================================================================= folders hierarchy

@Composable
fun TreeScreen(
    title: String,
    backLabel: String,
    folders: List<Group>,
    songs: List<Song>,
    currentId: Long?,
    bottomInset: Dp,
    onBack: () -> Unit,
    onOpenFolder: (Group) -> Unit,
    onPlay: (List<Song>, Int) -> Unit,
    onShuffle: (List<Song>) -> Unit,
    onSongMenu: (Song) -> Unit
) {
    val state = rememberLazyGridState()
    val st by com.powersound.player.data.SettingsStore.settings.collectAsState()
    val all = remember(folders, songs) { songs + folders.flatMap { it.songs } }
    // Settings → Library → Shuffle → Shuffle All Songs In Folder Hierarchy (off: only this folder's own songs)
    val shuffleList = if (st.shuffleAllHier || songs.isEmpty()) all else songs
    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            state = state,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 10.dp, end = 10.dp, bottom = bottomInset + 70.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                PageHeader(backLabel, title, Icons.Rounded.Folder, Category.FOLDERS_HIERARCHY.color(), onBack) {
                    ActionPills(
                        onShuffle = { onShuffle(shuffleList) },
                        onPlay = { onPlay(all, 0) },
                        onSearch = null,
                        onMore = null,
                        enabled = all.isNotEmpty()
                    )
                }
            }
            itemsIndexed(folders, key = { _, g -> g.key }) { _, g ->
                GroupTile(g.title, g.subtitle, g.coverId) { onOpenFolder(g) }
            }
            itemsIndexed(songs, key = { _, s -> "song:${s.id}" }, span = { _, _ -> GridItemSpan(maxLineSpan) }) { i, s ->
                SongRow(s, s.id == currentId, onClick = { onPlay(songs, i) }, onLongClick = { onSongMenu(s) }, listKind = "folder")
            }
        }
    }
}


@Composable
private fun MenuHeader(text: String) {
    Text(
        text,
        color = MaterialTheme.colorScheme.primary,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 4.dp)
    )
}

/** Bookmarks: a song and the moment you saved. Tap to play from there, long press to remove. */
@Composable
fun BookmarksScreen(
    bookmarks: List<com.powersound.player.data.Bookmark>,
    songOf: (Long) -> Song?,
    currentId: Long?,
    bottomInset: Dp,
    onBack: () -> Unit,
    onPlay: (com.powersound.player.data.Bookmark) -> Unit,
    onRemove: (com.powersound.player.data.Bookmark) -> Unit
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomInset + 20.dp)) {
        item {
            PageHeader("Library", Category.BOOKMARKS.label, Category.BOOKMARKS.icon(), Category.BOOKMARKS.color(), onBack) {
                Text(
                    "Add a bookmark from the ⋮ menu on the player. Tap to play from that moment, long press to remove.",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 12.sp
                )
            }
        }
        if (bookmarks.isEmpty()) {
            item {
                Text("No bookmarks yet", color = Color.White.copy(alpha = 0.6f), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(32.dp))
            }
        }
        items(bookmarks, key = { "${it.songId}:${it.createdAt}" }) { b ->
            val song = songOf(b.songId)
            if (song != null) {
                SongRow(
                    song = song,
                    isCurrent = song.id == currentId,
                    onClick = { onPlay(b) },
                    onLongClick = { onRemove(b) },
                    extra = "at ${formatTime(b.positionMs)}"
                )
            }
        }
    }
}

/** Internet radio and other stream addresses. Tap to play, long press to edit or delete. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun StreamsScreen(
    streams: List<com.powersound.player.data.StreamItem>,
    playingId: String?,
    bottomInset: Dp,
    onBack: () -> Unit,
    onPlay: (com.powersound.player.data.StreamItem) -> Unit
) {
    var editing by remember { mutableStateOf<com.powersound.player.data.StreamItem?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomInset + 20.dp)) {
        item {
            PageHeader("Library", Category.STREAMS.label, Category.STREAMS.icon(), Category.STREAMS.color(), onBack) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PillButton(
                        icon = Icons.Rounded.Add,
                        text = "Add stream",
                        onClick = { editing = com.powersound.player.data.StreamItem(System.currentTimeMillis(), "", "") }
                    )
                }
            }
        }
        if (streams.isEmpty()) {
            item {
                Text(
                    "No streams yet.\nTap \"Add stream\" and paste a radio address (http… .mp3, .aac or .m3u8).",
                    color = Color.White.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(32.dp)
                )
            }
        }
        items(streams, key = { it.id }) { st ->
            val current = playingId == "stream:${st.id}"
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 2.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (current) Color.White.copy(alpha = 0.10f) else Color.Transparent)
                    .combinedClickable(onClick = { onPlay(st) }, onLongClick = { editing = st })
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CategoryIcon(Category.STREAMS.icon(), Category.STREAMS.color(), 44)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(st.name, color = if (current) MaterialTheme.colorScheme.primary else Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(st.url, color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }

    editing?.let { item ->
        var name by remember(item.id) { mutableStateOf(item.name) }
        var url by remember(item.id) { mutableStateOf(item.url) }
        val isNew = streams.none { it.id == item.id }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { editing = null },
            containerColor = Color(0xFF1B1B22),
            title = { Text(if (isNew) "Add stream" else "Edit stream", color = Color.White) },
            text = {
                Column {
                    androidx.compose.material3.OutlinedTextField(value = name, onValueChange = { name = it.take(40) }, singleLine = true, label = { Text("Name") })
                    Spacer(Modifier.height(8.dp))
                    androidx.compose.material3.OutlinedTextField(value = url, onValueChange = { url = it.trim() }, singleLine = true, label = { Text("Address (http…)") })
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    enabled = url.startsWith("http"),
                    onClick = {
                        com.powersound.player.data.LibraryStores.saveStream(item.copy(name = name.ifBlank { url }, url = url))
                        editing = null
                    }
                ) { Text("Save") }
            },
            dismissButton = {
                Row {
                    if (!isNew) androidx.compose.material3.TextButton(onClick = {
                        com.powersound.player.data.LibraryStores.deleteStream(item.id)
                        editing = null
                    }) { Text("Delete") }
                    androidx.compose.material3.TextButton(onClick = { editing = null }) { Text("Cancel") }
                }
            }
        )
    }
}
