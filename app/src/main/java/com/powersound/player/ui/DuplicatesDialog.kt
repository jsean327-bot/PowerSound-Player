package com.powersound.player.ui

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.powersound.player.data.Song
import com.powersound.player.data.formatTime

/**
 * Settings → Library → Find Duplicates: each group is one song you have several times. The best copy
 * (lossless first, then the biggest file) is kept; the others are ticked for deleting — change the ticks as you like.
 */
@Composable
fun DuplicatesDialog(groups: List<List<Song>>, onDelete: (List<Song>) -> Unit, onDismiss: () -> Unit) {
    var marked by remember(groups) { mutableStateOf(groups.flatMap { it.drop(1) }.map { it.id }.toSet()) }
    val primary = MaterialTheme.colorScheme.primary
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .fillMaxWidth(0.95f)
                .clip(RoundedCornerShape(22.dp))
                .background(Color(0xFF1B1B22))
                .padding(16.dp)
        ) {
            Text("${groups.size} songs you have more than once", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text("Ticked files will be deleted. The best copy of each is kept.", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.heightIn(max = 480.dp)) {
                groups.forEach { g ->
                    item {
                        Row(Modifier.padding(top = 12.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Artwork(g.first().id, Modifier.size(36.dp), sizePx = 108, cornerDp = 6)
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(g.first().title, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(g.first().artist, color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    g.forEachIndexed { i, s ->
                        item {
                            val on = s.id in marked
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { marked = if (on) marked - s.id else marked + s.id },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(checked = on, onCheckedChange = { marked = if (on) marked - s.id else marked + s.id },
                                    colors = CheckboxDefaults.colors(checkedColor = Color(0xFFE05555)))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        "${s.formatLabel} • ${"%.1f".format(s.size / 1_048_576.0)} MB • ${formatTime(s.durationMs)}" + if (i == 0) "  (best)" else "",
                                        color = if (i == 0) primary else Color.White,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(s.folder, color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                val chosen = groups.flatten().filter { it.id in marked }
                TextButton(enabled = chosen.isNotEmpty(), onClick = { onDelete(chosen) }) { Text("Delete ${chosen.size}") }
            }
        }
    }
}
