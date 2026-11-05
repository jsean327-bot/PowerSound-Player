package com.powersound.player.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.powersound.player.data.Category
import com.powersound.player.data.formatTime
import com.powersound.player.video.Video
import com.powersound.player.video.VideoActivity
import com.powersound.player.video.VideoFolder
import com.powersound.player.video.VideoStore

private const val ALL = "\u0000all"

/**
 * Library → Videos: the phone's videos, by folder, apart from the music. Videos are read only when this page
 * is opened (and the permission is asked only here).
 */
@Composable
fun VideosScreen(bottomInset: Dp, onBack: () -> Unit) {
    val context = LocalContext.current
    val primary = MaterialTheme.colorScheme.primary
    val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE
    fun granted(): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED ||
            (Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager())

    var allowed by remember { mutableStateOf(granted()) }
    var asked by rememberSaveable { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        allowed = ok || granted()
    }
    LaunchedEffect(Unit) {
        if (!allowed && !asked) {
            asked = true
            ask.launch(permission)
        }
    }

    // back from the player: progress bars and new files
    var resumed by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) resumed++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    var videos by remember { mutableStateOf<List<Video>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(allowed, reload, resumed) {
        if (allowed) {
            if (!granted()) allowed = false else videos = VideoStore.load(context)
        }
    }
    val folders = remember(videos) { videos?.let { VideoStore.folders(it) }.orEmpty() }

    var open by rememberSaveable { mutableStateOf<String?>(null) }
    var newestFirst by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = open != null) { open = null }

    val shown: VideoFolder? = when (open) {
        null -> null
        ALL -> VideoFolder("All videos", ALL, videos.orEmpty())
        else -> folders.firstOrNull { it.path == open }
    }
    val list = remember(shown, newestFirst) {
        shown?.videos?.let { v -> if (newestFirst) v.sortedByDescending { it.dateAdded } else v.sortedWith(VideoStore.naturalOrder) }.orEmpty()
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomInset + 20.dp)) {
        item {
            PageHeader(
                if (shown == null) "Library" else "Videos",
                shown?.name ?: Category.VIDEOS.label,
                if (shown == null) Category.VIDEOS.icon() else Icons.Rounded.Folder,
                Category.VIDEOS.color(),
                onBack = { if (open != null) open = null else onBack() }
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (shown != null) {
                        PillButton(
                            icon = Icons.Rounded.Sort,
                            text = if (newestFirst) "Newest first" else "By name",
                            onClick = { newestFirst = !newestFirst }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("${list.size} videos", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
                    } else if (allowed) {
                        PillButton(icon = Icons.Rounded.Refresh, text = "Refresh", onClick = { reload++ })
                        Spacer(Modifier.width(8.dp))
                        videos?.let { Text("${it.size} videos", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp) }
                    }
                }
            }
        }

        if (!allowed) {
            item {
                Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "PowerSound needs permission to see the videos on this phone.\nIt is used only for this page.",
                        color = Color.White.copy(alpha = 0.75f),
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(10.dp))
                    TextButton(onClick = {
                        ask.launch(permission)
                        // asked twice already: Android shows nothing more, so open the app's settings
                        if (asked && !granted()) runCatching {
                            context.startActivity(
                                android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                    .setData(android.net.Uri.fromParts("package", context.packageName, null))
                            )
                        }
                    }) { Text("Allow videos") }
                }
            }
            return@LazyColumn
        }

        if (videos == null) {
            item {
                Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = primary)
                }
            }
            return@LazyColumn
        }

        if (shown == null) {
            if (videos.isNullOrEmpty()) {
                item {
                    Text(
                        "No videos found on this phone.",
                        color = Color.White.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(32.dp)
                    )
                }
            } else {
                item { FolderRow(Icons.Rounded.VideoLibrary, "All videos", videos!!.size) { open = ALL } }
                items(folders, key = { it.path }) { f ->
                    FolderRow(Icons.Rounded.Folder, f.name, f.videos.size) { open = f.path }
                }
            }
        } else {
            items(list.size, key = { list[it].id }) { i ->
                VideoRow(list[i], resumed) { VideoActivity.open(context, list, i) }
            }
        }
    }
}

@Composable
private fun FolderRow(icon: androidx.compose.ui.graphics.vector.ImageVector, name: String, count: Int, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CategoryIcon(icon, Category.VIDEOS.color(), 44)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(name, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (count == 1) "1 video" else "$count videos", color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp)
        }
    }
}

@Composable
private fun VideoRow(v: Video, refresh: Int, onClick: () -> Unit) {
    val context = LocalContext.current
    val primary = MaterialTheme.colorScheme.primary
    val thumb by produceState(VideoStore.cachedThumb(v.id), v.id) { value = VideoStore.thumb(context, v) }
    val watched = remember(v.id, refresh) { VideoStore.progress(context, VideoStore.keyOf(v.uri)) }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .width(136.dp)
                .height(77.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.White.copy(alpha = 0.06f))
        ) {
            val bmp = thumb
            if (bmp != null) {
                Image(bmp.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(Icons.Rounded.Movie, contentDescription = null, tint = Color.White.copy(alpha = 0.3f), modifier = Modifier.align(Alignment.Center).size(30.dp))
            }
            if (v.durationMs > 0) {
                Text(
                    formatTime(v.durationMs),
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.Black.copy(alpha = 0.7f))
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                )
            }
            if (watched > 0f) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.25f))) {
                    Box(Modifier.fillMaxWidth(watched).fillMaxHeight().background(primary))
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(v.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(3.dp))
            Text(
                listOfNotNull(v.quality.takeIf { it.isNotEmpty() }, sizeText(v.size)).joinToString(" • "),
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 12.sp
            )
        }
    }
}

private fun sizeText(bytes: Long): String? = when {
    bytes <= 0 -> null
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    else -> "${bytes / (1L shl 20)} MB"
}
