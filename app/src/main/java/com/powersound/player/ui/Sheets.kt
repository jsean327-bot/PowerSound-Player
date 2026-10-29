package com.powersound.player.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.powersound.player.QueueEntry
import com.powersound.player.data.Song
import com.powersound.player.data.formatTime

private val SheetBg = Color(0xFF17171F)

@Composable
private fun SheetItem(icon: ImageVector, text: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = Color.White)
        Spacer(Modifier.width(16.dp))
        Text(text, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** PowerSound logo: equalizer bars + wordmark. */
@Composable
fun PowerSoundLogo() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(30.dp)) {
            val heights = listOf(0.4f, 0.8f, 1f, 0.6f)
            val bw = size.width / 7f
            heights.forEachIndexed { i, h ->
                val bh = size.height * h
                drawRoundRect(
                    if (i % 2 == 0) AccentLight else Accent,
                    topLeft = Offset(bw * i * 2, (size.height - bh) / 2f),
                    size = Size(bw, bh),
                    cornerRadius = CornerRadius(bw / 2f)
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Text("POWERSOUND", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MenuSheet(
    onDismiss: () -> Unit,
    onRescan: () -> Unit,
    onSleep: () -> Unit,
    onEqualizer: () -> Unit,
    onCheckUpdate: () -> Unit,
    sleepLabel: String?
) {
    val context = LocalContext.current
    val version = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: ""
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = SheetBg) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            PowerSoundLogo()
            Text("Player $version", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
            Spacer(Modifier.height(12.dp))
            SheetItem(Icons.Rounded.Equalizer, "Equalizer") { onDismiss(); onEqualizer() }
            SheetItem(Icons.Rounded.Timer, if (sleepLabel != null) "Sleep timer ($sleepLabel)" else "Sleep timer") { onDismiss(); onSleep() }
            SheetItem(Icons.Rounded.Refresh, "Rescan library") { onDismiss(); onRescan() }
            SheetItem(Icons.Rounded.SystemUpdate, "Check for updates") { onDismiss(); onCheckUpdate() }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(
    queue: List<QueueEntry>,
    currentIndex: Int,
    onPlayIndex: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (currentIndex - 2).coerceAtLeast(0))
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = SheetBg) {
        Text(
            "Queue • ${queue.size}",
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
        )
        if (queue.isEmpty()) {
            Text("The queue is empty", color = Color.White.copy(alpha = 0.6f), modifier = Modifier.padding(20.dp))
        }
        LazyColumn(state = state, modifier = Modifier.heightIn(max = 520.dp)) {
            items(queue, key = { it.index }) { e ->
                val current = e.index == currentIndex
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (current) Color.White.copy(alpha = 0.10f) else Color.Transparent)
                        .clickable { onPlayIndex(e.index) }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Artwork(e.songId, Modifier.size(44.dp), sizePx = 144, cornerDp = 8)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            e.title,
                            color = if (current) MaterialTheme.colorScheme.primary else Color.White,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(e.artist, color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { onRemove(e.index) }) {
                        Icon(Icons.Rounded.Close, contentDescription = "Remove", tint = Color.White.copy(alpha = 0.6f))
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongActionsSheet(
    song: Song,
    onDismiss: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onGoAlbum: () -> Unit,
    onGoArtist: () -> Unit,
    onGoFolder: () -> Unit,
    info: String? = null
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = SheetBg) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(song.id, Modifier.size(56.dp), sizePx = 168, cornerDp = 10)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(song.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${song.artist} - ${song.album}", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    info?.takeIf { it.isNotBlank() } ?: "${song.formatLabel} • ${formatTime(song.durationMs)}",
                    color = Accent,
                    fontSize = 11.sp
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        SheetItem(Icons.Rounded.PlaylistPlay, "Play next") { onDismiss(); onPlayNext() }
        SheetItem(Icons.Rounded.PlaylistAdd, "Add to queue") { onDismiss(); onAddToQueue() }
        SheetItem(Icons.Rounded.Album, "Go to album") { onDismiss(); onGoAlbum() }
        SheetItem(Icons.Rounded.Mic, "Go to artist") { onDismiss(); onGoArtist() }
        SheetItem(Icons.Rounded.Folder, "Go to folder") { onDismiss(); onGoFolder() }
        Spacer(Modifier.height(24.dp))
    }
}
