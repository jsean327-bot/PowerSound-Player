package com.powersound.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.powersound.player.data.Group
import com.powersound.player.data.SettingsStore

/**
 * Album / folder page → ⋮ → Choose Cover: every song's cover in the group; the one you tap becomes the
 * group's cover in the lists and on its page. Only PowerSound's display changes, not the files.
 */
@Composable
fun CoverPickerDialog(group: Group, onDismiss: () -> Unit) {
    val st by SettingsStore.settings.collectAsState()
    val chosen = st.groupCovers[group.key]
    val current = group.coverId
    val primary = MaterialTheme.colorScheme.primary
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1B1B22),
        title = { Text("Choose the cover", color = Color.White) },
        text = {
            Column {
                Text("Tap the picture that should stand for \"${group.title}\"", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.heightIn(max = 420.dp).padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(group.songs.distinctBy { it.id }.take(150), key = { it.id }) { s ->
                        val on = s.id == current
                        Column(
                            Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .then(if (on) Modifier.border(3.dp, primary, RoundedCornerShape(12.dp)) else Modifier)
                                .clickable {
                                    SettingsStore.update { it.copy(groupCovers = it.groupCovers + (group.key to s.id)) }
                                    onDismiss()
                                }
                                .background(Color.White.copy(alpha = 0.05f))
                                .padding(4.dp)
                        ) {
                            Artwork(s.id, Modifier.fillMaxWidth().aspectRatio(1f), sizePx = 240, cornerDp = 8)
                            Text(s.title, color = if (on) primary else Color.White, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = {
            if (chosen != null) TextButton(onClick = {
                SettingsStore.update { it.copy(groupCovers = it.groupCovers - group.key) }
                onDismiss()
            }) { Text("Default") }
        }
    )
}
