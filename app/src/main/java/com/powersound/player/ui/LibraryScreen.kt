package com.powersound.player.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.runtime.derivedStateOf
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
    extraMenu: List<Pair<String, () -> Unit>> = emptyList()
) {
    val state = rememberLazyGridState()
    var columns by rememberSaveable { mutableIntStateOf(2) }
    var sort by rememberSaveable { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    val shown = remember(groups, query, sort) {
        val q = query?.trim()?.lowercase().orEmpty()
        val list = if (q.isEmpty()) groups else groups.filter { it.title.lowercase().contains(q) || it.subtitle.lowercase().contains(q) }
        when (sort) {
            1 -> list.reversed()
            2 -> list.sortedByDescending { it.songs.size }
            3 -> list.sortedBy { it.songs.size }
            else -> list
        }
    }
    val allSongs = remember(shown) { shown.flatMap { it.songs } }
    val keys = remember(shown) { shown.map { letterKey(it.title) } }
    val headerItems = if (query != null) 2 else 1
    val scrolled by remember { derivedStateOf { state.firstVisibleItemIndex > 0 } }

    val pills: @Composable (Modifier) -> Unit = { m ->
        Box(m) {
            ActionPills(
                onShuffle = { onShuffle(allSongs) },
                onPlay = { onPlay(allSongs) },
                onSearch = { query = if (query == null) "" else null },
                onMore = { menu = true },
                enabled = allSongs.isNotEmpty(),
                searchActive = query != null
            )
            Box(Modifier.align(Alignment.TopEnd)) {
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Add all to queue") }, onClick = { menu = false; onAddToQueue(allSongs) })
                    extraMenu.forEach { (label, action) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = { menu = false; action() })
                    }
                    MenuHeader("List options • sort")
                    listOf("Name A–Z", "Name Z–A", "Most songs", "Fewest songs").forEachIndexed { i, label ->
                        DropdownMenuItem(
                            text = { Text(if (sort == i) "✓  $label" else "     $label") },
                            onClick = { sort = i; menu = false }
                        )
                    }
                    MenuHeader("Zoom (or pinch the list)")
                    DropdownMenuItem(text = { Text("Bigger tiles") }, onClick = { if (columns > 1) columns--; menu = false })
                    DropdownMenuItem(text = { Text("Smaller tiles") }, onClick = { if (columns < 4) columns++; menu = false })
                }
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = state,
            modifier = Modifier
                .fillMaxSize()
                .pinchToZoom(columns, onZoomIn = { if (columns > 1) columns-- }, onZoomOut = { if (columns < 4) columns++ }),
            contentPadding = PaddingValues(start = 10.dp, end = 26.dp, bottom = bottomInset + 70.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                PageHeader(backLabel, title, icon, iconColor, onBack) { pills(Modifier) }
            }
            if (query != null) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    FilterField(query ?: "", { query = it }, { query = null })
                }
            }
            itemsIndexed(shown, key = { _, g -> g.key }) { _, g ->
                GroupTile(g.title, g.subtitle, g.coverId) { onOpen(g) }
            }
        }

        if (sort == 0) {
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

        FloatingPills(visible = scrolled, bottomInset = bottomInset) { pills(Modifier) }
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
                .padding(start = 12.dp, end = 34.dp, bottom = bottomInset + 6.dp)
        ) {
            content()
        }
    }
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
    header: @Composable (pills: @Composable () -> Unit) -> Unit
) {
    val state = rememberLazyListState()
    var zoom by rememberSaveable { mutableIntStateOf(1) }
    var sort by rememberSaveable { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
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
    val indexOn = showIndex && (sort == 0 || sort == 1)
    val keys = remember(shown, indexOn) { if (indexOn) shown.map { letterKey(it.title) } else emptyList() }
    val headerItems = if (query != null) 2 else 1
    val scrolled by remember { derivedStateOf { state.firstVisibleItemIndex > 0 } }

    val pills: @Composable () -> Unit = {
        Box {
            ActionPills(
                onShuffle = { onShuffle(shown) },
                onPlay = { onPlay(shown, 0) },
                onSearch = { query = if (query == null) "" else null },
                onMore = { menu = true },
                enabled = shown.isNotEmpty(),
                searchActive = query != null
            )
            Box(Modifier.align(Alignment.TopEnd)) {
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
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
            item { header(pills) }
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
            itemsIndexed(shown, key = { _, s -> s.id }) { i, s ->
                SongRow(
                    song = s,
                    isCurrent = s.id == currentId,
                    onClick = { onPlay(shown, i) },
                    onLongClick = { onSongMenu(s) },
                    zoom = zoom,
                    extra = rowExtra?.invoke(s)
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

        FloatingPills(visible = scrolled, bottomInset = bottomInset) {
            Box(Modifier.padding(end = 0.dp)) { pills() }
        }
    }
}

/** Header with the big cover art (album / folder / artist page). */
@Composable
fun ArtHeader(group: Group, backLabel: String, onBack: () -> Unit, pills: @Composable () -> Unit) {
    val bg = MaterialTheme.colorScheme.background
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
            OverlayChip("♪ ${group.songs.size} | ${formatTime(group.totalMs)}")
            Spacer(Modifier.height(10.dp))
            pills()
        }
    }
}

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
    val all = remember(folders, songs) { songs + folders.flatMap { it.songs } }
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
                        onShuffle = { onShuffle(all) },
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
                SongRow(s, s.id == currentId, onClick = { onPlay(songs, i) }, onLongClick = { onSongMenu(s) })
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
