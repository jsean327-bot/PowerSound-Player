package com.powersound.player.ui

import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.powersound.player.data.Song
import com.powersound.player.data.TagEdits
import com.powersound.player.data.TagEditor
import kotlinx.coroutines.launch

private val EditorBg = Color(0xFF1B1B22)

/**
 * Info/Tags → Edit: title, artist, album, album artist, genre, year, track, composer and the cover.
 * Only what you change is written; everything else in the file stays as it was.
 */
@Composable
fun TagEditorDialog(song: Song, saving: Boolean, onSave: (TagEdits) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val editable = remember(song) { TagEditor.canEdit(song) }
    val labels = listOf("Title", "Artist", "Album", "Album artist", "Genre", "Year", "Track", "Composer")
    // the tags as they are now ("Unknown …" is only a label of the app, not a tag)
    val start = remember(song) {
        listOf(
            song.title,
            song.artist.takeUnless { it == "Unknown artist" } ?: "",
            song.album.takeUnless { it == "Unknown album" } ?: "",
            if (song.hasAlbumArtist) song.albumArtist else "",
            song.genre,
            if (song.year > 0) song.year.toString() else "",
            if (song.trackNumber > 0) song.trackNumber.toString() else "",
            song.composer
        )
    }
    val values = remember(song) { mutableStateListOf<String>().apply { addAll(start) } }
    var newCover by remember(song) { mutableStateOf<ByteArray?>(null) }
    var removeCover by remember(song) { mutableStateOf(false) }
    var loadingCover by remember { mutableStateOf(false) }
    val pickCover = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            loadingCover = true
            scope.launch {
                val bytes = TagEditor.coverFrom(context, uri)
                loadingCover = false
                if (bytes != null) {
                    newCover = bytes
                    removeCover = false
                } else {
                    Toast.makeText(context, "This picture can't be used", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    val preview = remember(newCover) { newCover?.let { b -> BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap() } }
    val changed = newCover != null || removeCover || labels.indices.any { values[it].trim() != start[it].trim() }

    fun edits(): TagEdits {
        fun field(i: Int): String? = values[i].trim().takeIf { it != start[i].trim() }
        return TagEdits(
            title = field(0), artist = field(1), album = field(2), albumArtist = field(3),
            genre = field(4), year = field(5), track = field(6), composer = field(7),
            cover = newCover, removeCover = removeCover
        )
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .fillMaxWidth(0.94f)
                .clip(RoundedCornerShape(22.dp))
                .background(EditorBg)
                .padding(18.dp)
        ) {
            Text("Edit tags", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Text(
                song.path.substringAfterLast('/').ifBlank { song.title },
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(12.dp))
            if (!editable) {
                Text(
                    "Editing tags works for FLAC and MP3 files for now. This file is ${song.formatLabel.ifBlank { "another type" }}.",
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 14.sp
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
            } else {

                // ---- cover ----
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(96.dp).clip(RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                        val p = preview
                        when {
                            p != null -> Image(p, contentDescription = "New cover", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            removeCover -> Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
                                Text("No cover", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                            }
                            else -> Artwork(song.id, Modifier.fillMaxSize(), sizePx = 288, cornerDp = 12)
                        }
                        if (loadingCover) CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        TextButton(enabled = !saving, onClick = { pickCover.launch("image/*") }) { Text("Change cover") }
                        TextButton(enabled = !saving, onClick = { removeCover = true; newCover = null }) { Text("Remove cover") }
                    }
                }
                Spacer(Modifier.height(6.dp))

                // ---- text tags ----
                Column(
                    Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                ) {
                    labels.forEachIndexed { i, label ->
                        val number = i == 5 || i == 6
                        OutlinedTextField(
                            value = values[i],
                            onValueChange = { v ->
                                values[i] = when (i) {
                                    5 -> v.filter { it.isDigit() }.take(4)
                                    6 -> v.filter { it.isDigit() || it == '/' }.take(7)
                                    else -> v.take(250)
                                }
                            },
                            label = { Text(label) },
                            singleLine = true,
                            enabled = !saving,
                            keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp)
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    if (saving) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Saving…", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, modifier = Modifier.weight(1f))
                    }
                    TextButton(enabled = !saving, onClick = onDismiss) { Text("Cancel") }
                    TextButton(enabled = !saving && changed && !loadingCover, onClick = { onSave(edits()) }) { Text("Save") }
                }
            }
        }
    }
}
