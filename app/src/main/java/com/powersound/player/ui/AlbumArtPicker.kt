package com.powersound.player.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.powersound.player.data.ArtworkLoader
import com.powersound.player.data.Lang
import com.powersound.player.data.SettingsStore
import com.powersound.player.data.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** One picture to choose: what it shows and its size ("600x600"). */
private class ArtChoice(val image: ImageBitmap, val bytes: ByteArray, val size: String)

private fun decode(bytes: ByteArray): ArtChoice? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= 320) sample *= 2
    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
    ArtChoice(bmp.asImageBitmap(), bytes, "${bounds.outWidth}x${bounds.outHeight}")
}.getOrNull()

/**
 * Select → Album Art (like Poweramp): the picture of the selected songs' albums. No album art, the picture inside the
 * file, the folder's cover.jpg, covers found on iTunes, or one from the gallery. Only PowerSound's display changes,
 * the music files stay as they are.
 */
@Composable
fun AlbumArtPicker(songs: List<Song>, onSettings: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val primary = MaterialTheme.colorScheme.primary
    val scope = rememberCoroutineScope()
    val first = songs.firstOrNull() ?: return
    var high by remember { mutableStateOf(SettingsStore.value.artHighRes) }
    var embedded by remember { mutableStateOf<ArtChoice?>(null) }
    var folder by remember { mutableStateOf<ArtChoice?>(null) }
    var urls by remember { mutableStateOf<List<String>?>(null) }
    val online = remember { mutableStateMapOf<String, ArtChoice?>() }
    var chosen by remember { mutableStateOf(ArtworkLoader.chosen(context, first)) }

    fun done(message: String) {
        android.widget.Toast.makeText(context, Lang.tr(message), android.widget.Toast.LENGTH_SHORT).show()
        onDismiss()
    }

    LaunchedEffect(first.id) {
        withContext(Dispatchers.IO) {
            embedded = ArtworkLoader.embeddedBytes(context, first.id)?.let { decode(it) }
            folder = ArtworkLoader.folderBytes(first)?.let { decode(it) }
        }
    }
    LaunchedEffect(first.id, high) {
        urls = null
        online.clear()
        val found = withContext(Dispatchers.IO) { runCatching { ArtworkLoader.candidates(first, high) }.getOrDefault(emptyList()) }
        urls = found
        val gate = Semaphore(3)
        withContext(Dispatchers.IO) {
            found.map { u -> async { gate.withPermit { online[u] = ArtworkLoader.fetch(u)?.let { decode(it) } } } }.awaitAll()
        }
    }

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            val bytes = withContext(Dispatchers.IO) { runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull() }
            if (bytes != null && decode(bytes) != null) {
                withContext(Dispatchers.IO) { ArtworkLoader.choose(context, songs, bytes) }
                done("Album art changed")
            } else done("This picture can't be used")
        }
    }

    BottomCard(onDismiss) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = high,
                onCheckedChange = { high = it },
                colors = CheckboxDefaults.colors(checkedColor = primary, uncheckedColor = Color.White.copy(alpha = 0.7f))
            )
            Text("High Resolution", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (urls == null) CircularProgressIndicator(color = primary, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(44.dp).clip(CircleShape).clickable { onDismiss(); onSettings() }, contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Settings, contentDescription = "Album Art settings", tint = Color.White)
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item(key = "none") {
                ArtTile(null, "No album art", chosen == "none", primary) {
                    scope.launch {
                        withContext(Dispatchers.IO) { ArtworkLoader.chooseNone(context, songs) }
                        chosen = "none"
                        done("No album art for this album")
                    }
                }
            }
            embedded?.let { e ->
                item(key = "embedded") {
                    ArtTile(e.image, "${e.size}\nEmbedded album art", chosen == "", primary) {
                        scope.launch {
                            withContext(Dispatchers.IO) { ArtworkLoader.chooseOwn(context, songs) }
                            done("Album art changed")
                        }
                    }
                }
            }
            folder?.let { f ->
                item(key = "folder") {
                    ArtTile(f.image, "${f.size}\nFolder image", false, primary) {
                        scope.launch {
                            withContext(Dispatchers.IO) { ArtworkLoader.choose(context, songs, f.bytes) }
                            done("Album art changed")
                        }
                    }
                }
            }
            items(urls.orEmpty(), key = { it }) { u ->
                val c: ArtChoice? = online[u]
                if (online.containsKey(u) && c == null) return@items
                ArtTile(c?.image, c?.size ?: "", false, primary, loading = c == null) {
                    val pick = c ?: return@ArtTile
                    scope.launch {
                        withContext(Dispatchers.IO) { ArtworkLoader.choose(context, songs, pick.bytes) }
                        done("Album art changed")
                    }
                }
            }
        }
        if (urls?.isEmpty() == true) {
            Text(
                "Nothing found online (check the internet, or the album and artist tags).",
                color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )
        }
        Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.End) {
            Text(
                "Pick From Gallery",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.14f))
                    .clickable { gallery.launch("image/*") }
                    .padding(horizontal = 22.dp, vertical = 12.dp)
            )
        }
    }
}

@Composable
private fun ArtTile(image: ImageBitmap?, label: String, current: Boolean, accent: Color, loading: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .then(if (current) Modifier.border(3.dp, accent, RoundedCornerShape(14.dp)) else Modifier)
            .clickable(enabled = !loading, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (image != null) {
            Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else if (loading) {
            CircularProgressIndicator(color = accent, strokeWidth = 2.dp, modifier = Modifier.size(26.dp))
        }
        if (label.isNotEmpty()) {
            Text(
                label,
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier
                    .align(if (image == null && !loading) Alignment.Center else Alignment.BottomCenter)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }
}
