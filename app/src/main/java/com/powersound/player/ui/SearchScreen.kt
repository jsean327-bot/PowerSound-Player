package com.powersound.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.powersound.player.LibraryState
import com.powersound.player.data.Group
import com.powersound.player.data.Song
import com.powersound.player.data.searchForm
import kotlinx.coroutines.delay

/** Search categories (Settings → Library → Search → Search Categories can hide any of them). */
val SEARCH_CATEGORIES = listOf("Songs", "Albums", "Artists", "Album Artists", "Folders", "Genres", "Composers")

/** A song with the search forms of its title (and file name) and of its artist and album. */
private class SongForms(val song: Song, val title: String, val other: String)

@Composable
fun SearchScreen(
    library: LibraryState,
    currentId: Long?,
    bottomInset: Dp,
    onPlay: (List<Song>, Int) -> Unit,
    onShuffle: (List<Song>) -> Unit,
    onOpenGroup: (Group, String) -> Unit,
    onSongMenu: (Song) -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    var filterName by rememberSaveable { mutableStateOf("All") }
    val st by com.powersound.player.data.SettingsStore.settings.collectAsState()
    val filters = remember(st.searchHidden) { listOf("All") + SEARCH_CATEGORIES.filter { it !in st.searchHidden } }
    val filter = filterName.takeIf { it in filters } ?: "All"
    fun on(cat: String) = cat !in st.searchHidden && (filter == "All" || filter == cat)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(150)
        runCatching { focus.requestFocus() }
    }

    // Arabic-aware: "اليسا" finds "إليسا", harakat and accents are ignored (see searchForm)
    val q = searchForm(query.trim())
    // search forms of the whole library, made once per library: typing only compares
    val songIndex = remember(library.songs, st.filenameAsTitle) {
        library.songs.map { s ->
            SongForms(
                s,
                searchForm(s.title) + if (st.filenameAsTitle) "\n" + searchForm(s.fileName) else "",
                searchForm(s.artist) + "\n" + searchForm(s.album)
            )
        }
    }
    val groupIndex = remember(library) {
        listOf(
            "Albums" to library.albums, "Artists" to library.artists, "Album Artists" to library.albumArtists,
            "Folders" to library.folders, "Genres" to library.genres, "Composers" to library.composers
        ).map { (type, list) -> Triple(type, list, list.map { searchForm(it.title) }) }
    }
    val songs = remember(q, filter, songIndex, st.searchTitlesOnly, st.searchHidden) {
        if (q.isEmpty() || !on("Songs")) emptyList()
        else songIndex.filter {
            // Settings → Library → Search → Search Track Titles Only
            it.title.contains(q) || (!st.searchTitlesOnly && it.other.contains(q))
        }.map { it.song }
    }
    val groups: List<Pair<String, Group>> = remember(q, filter, groupIndex, st.searchHidden) {
        if (q.isEmpty()) emptyList() else buildList {
            for ((type, list, forms) in groupIndex) {
                if (!on(type)) continue
                list.forEachIndexed { i, g -> if (forms[i].contains(q)) add(type to g) }
            }
        }
    }
    // every row needs its own key (two rows with one key stop the app)
    val groupKeys = remember(groups) { uniqueKeys(groups) { "g:${it.first}:${it.second.key}" } }
    val songKeys = remember(songs) { uniqueKeys(songs) { "s:${it.id}" } }
    // Settings → Library → Search → Play Tracks Only (off: also the songs of the found albums, artists, …)
    val playable = remember(songs, groups, st.searchTracksOnly) {
        if (st.searchTracksOnly) songs.ifEmpty { groups.flatMap { it.second.songs }.distinctBy { it.id } }
        else (songs + groups.flatMap { it.second.songs }).distinctBy { it.id }
    }

    val focusManager = LocalFocusManager.current
    val listState = rememberLazyListState()
    // scrolling the results puts the keyboard away
    if (listState.isScrollInProgress) LaunchedEffect(Unit) { focusManager.clearFocus() }

    Column(Modifier.fillMaxSize()) {
        // the search box and the filters stay on top while the results scroll
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 10.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(50))
                .background(Color.White.copy(alpha = 0.12f))
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.Search, contentDescription = null, tint = Color.White)
            Spacer(Modifier.width(10.dp))
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 18.sp),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focus),
                decorationBox = { inner ->
                    androidx.compose.foundation.layout.Box {
                        if (query.isEmpty()) Text("Search", color = Color.White.copy(alpha = 0.5f), fontSize = 18.sp)
                        inner()
                    }
                }
            )
            Icon(
                Icons.Rounded.Close,
                contentDescription = "Clear",
                tint = Color.White,
                modifier = Modifier.clickable { query = "" }
            )
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            itemsIndexed(filters) { _, label ->
                Text(
                    label,
                    color = if (filter == label) Color.Black else Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (filter == label) Color.White else Color.White.copy(alpha = 0.08f))
                        .clickable { filterName = label }
                        .padding(horizontal = 12.dp, vertical = 7.dp)
                )
            }
        }
        LazyColumn(
            Modifier
                .fillMaxWidth()
                .weight(1f),
            state = listState,
            contentPadding = PaddingValues(bottom = bottomInset + 12.dp)
        ) {
            item {
                ActionPills(
                    onShuffle = { onShuffle(playable) },
                    onPlay = { onPlay(playable, 0) },
                    onSearch = null,
                    onMore = null,
                    enabled = playable.isNotEmpty(),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp)
                )
            }
            if (q.isNotEmpty() && groups.isEmpty() && songs.isEmpty()) {
                item {
                    Text(
                        "Nothing found",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 14.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
            itemsIndexed(groups, key = { i, _ -> groupKeys[i] }) { _, (type, g) ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onOpenGroup(g, "Search") }
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Artwork(g.coverId, Modifier.size(56.dp), sizePx = 168, cornerDp = 10)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(g.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("$type • ${g.subtitle}", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            itemsIndexed(songs, key = { i, _ -> songKeys[i] }) { i, s ->
                SongRow(s, s.id == currentId, onClick = { onPlay(songs, i) }, onLongClick = { onSongMenu(s) })
            }
        }
    }
}
