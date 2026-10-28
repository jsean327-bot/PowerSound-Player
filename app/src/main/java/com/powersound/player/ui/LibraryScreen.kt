package com.powersound.player.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.powersound.player.LibraryState
import com.powersound.player.data.Detail
import com.powersound.player.data.Song

private val TABS = listOf("Songs", "Albums", "Artists", "Folders")

@Composable
fun LibraryScreen(
    library: LibraryState,
    currentSongId: Long?,
    onPlay: (List<Song>, Int) -> Unit,
    onShuffle: (List<Song>) -> Unit,
    onOpenDetail: (Detail) -> Unit,
    modifier: Modifier = Modifier
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }

    Column(modifier.fillMaxSize()) {
        TabRow(
            selectedTabIndex = tab,
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.primary
        ) {
            TABS.forEachIndexed { i, title ->
                Tab(
                    selected = tab == i,
                    onClick = { tab = i },
                    text = { Text(title) },
                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        when {
            library.loading && library.songs.isEmpty() -> CenterBox {
                CircularProgressIndicator()
            }
            library.loaded && library.songs.isEmpty() -> CenterBox {
                Text(
                    "No music found on this device.\nCopy your FLAC files to the Music folder.",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            else -> when (tab) {
                0 -> SongsTab(library.songs, currentSongId, onPlay, onShuffle)
                1 -> AlbumsTab(library, onOpenDetail)
                2 -> ArtistsTab(library, onOpenDetail)
                else -> FoldersTab(library, onOpenDetail)
            }
        }
    }
}

@Composable
private fun CenterBox(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun SongsTab(
    songs: List<Song>,
    currentSongId: Long?,
    onPlay: (List<Song>, Int) -> Unit,
    onShuffle: (List<Song>) -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(songs, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) songs else songs.filter {
            it.title.lowercase().contains(q) ||
                it.artist.lowercase().contains(q) ||
                it.album.lowercase().contains(q)
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 8.dp)) {
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                singleLine = true,
                placeholder = { Text("Search songs, artists, albums") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Clear")
                        }
                    }
                }
            )
        }
        item {
            PlayButtons(
                count = filtered.size,
                onPlay = { onPlay(filtered, 0) },
                onShuffle = { onShuffle(filtered) }
            )
        }
        itemsIndexed(filtered, key = { _, s -> s.id }) { index, song ->
            SongRow(song = song, isCurrent = song.id == currentSongId) { onPlay(filtered, index) }
        }
    }
}

@Composable
fun PlayButtons(count: Int, onPlay: () -> Unit, onShuffle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Button(
            onClick = onPlay,
            enabled = count > 0,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            Icon(Icons.Rounded.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text("Play")
        }
        OutlinedButton(onClick = onShuffle, enabled = count > 0) {
            Icon(Icons.Rounded.Shuffle, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text("Shuffle")
        }
        Spacer(Modifier.weight(1f))
        Text(
            "$count songs",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun AlbumsTab(library: LibraryState, onOpenDetail: (Detail) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        items(library.albums, key = { it.id }) { album ->
            Column(
                Modifier.clickable {
                    onOpenDetail(
                        Detail(album.name, album.artist, album.songs.firstOrNull()?.id, album.songs)
                    )
                }
            ) {
                Artwork(
                    songId = album.songs.firstOrNull()?.id,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                    sizePx = 384,
                    cornerDp = 10
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    album.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${album.artist} • ${album.songs.size}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ArtistsTab(library: LibraryState, onOpenDetail: (Detail) -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(library.artists, key = { it.name }) { artist ->
            GroupRow(
                icon = Icons.Rounded.Person,
                title = artist.name,
                subtitle = "${artist.songs.size} songs"
            ) {
                onOpenDetail(Detail(artist.name, "Artist", artist.songs.firstOrNull()?.id, artist.songs))
            }
        }
    }
}

@Composable
private fun FoldersTab(library: LibraryState, onOpenDetail: (Detail) -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(library.folders, key = { it.path }) { folder ->
            GroupRow(
                icon = Icons.Rounded.Folder,
                title = folder.name,
                subtitle = "${folder.path} • ${folder.songs.size} songs"
            ) {
                onOpenDetail(Detail(folder.name, folder.path, folder.songs.firstOrNull()?.id, folder.songs))
            }
        }
    }
}

@Composable
private fun GroupRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .padding(0.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun DetailScreen(
    detail: Detail,
    currentSongId: Long?,
    onPlay: (List<Song>, Int) -> Unit,
    onShuffle: (List<Song>) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(modifier.fillMaxSize()) {
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Artwork(detail.coverSongId, Modifier.size(110.dp), sizePx = 384, cornerDp = 10)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        detail.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        detail.subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        item {
            PlayButtons(
                count = detail.songs.size,
                onPlay = { onPlay(detail.songs, 0) },
                onShuffle = { onShuffle(detail.songs) }
            )
        }
        itemsIndexed(detail.songs, key = { _, s -> s.id }) { index, song ->
            SongRow(song, song.id == currentSongId) { onPlay(detail.songs, index) }
        }
    }
}
