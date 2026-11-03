package com.powersound.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.powersound.player.audio.AudioEngine
import com.powersound.player.audio.BUILT_IN_PRESETS
import com.powersound.player.data.SettingsStore
import com.powersound.player.data.Song

/**
 * Song menu → EQ Preset: an equalizer preset for this song, its album or its artist. When such a song plays,
 * the preset comes on by itself; other songs get back the preset that was on before.
 * (A song's own preset wins over its album's, the album's over the artist's.)
 */
@Composable
fun EqAssignDialog(song: Song, albumKey: String?, onDismiss: () -> Unit) {
    val st by SettingsStore.settings.collectAsState()
    val custom by AudioEngine.customPresets.collectAsState()
    val scopes = listOfNotNull(
        "This song" to "song:${song.id}",
        albumKey?.let { "This album" to it },
        "Artist: ${song.artist}" to "artist:${song.artist}"
    )
    var scope by remember { mutableIntStateOf(0) }
    val key = scopes[scope.coerceIn(0, scopes.lastIndex)].second
    val names = listOf("None") + (BUILT_IN_PRESETS + custom).map { it.name }.distinct()
    val chosen = st.eqAssign[key] ?: "None"
    val primary = MaterialTheme.colorScheme.primary
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1B1B22),
        title = { Text("EQ preset", color = Color.White) },
        text = {
            Column {
                Text(song.title, color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    scopes.forEachIndexed { i, (label, k) ->
                        val on = i == scope
                        Text(
                            label + (if (st.eqAssign.containsKey(k)) " •" else ""),
                            color = if (on) Color.Black else Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .clip(RoundedCornerShape(50))
                                .background(if (on) Color.White else Color.White.copy(alpha = 0.10f))
                                .clickable { scope = i }
                                .padding(horizontal = 10.dp, vertical = 7.dp)
                        )
                    }
                }
                Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
                    names.forEach { n ->
                        val on = n == chosen
                        Text(
                            if (n == "None") "None (keep the current equalizer)" else n,
                            color = if (on) primary else Color.White,
                            fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    SettingsStore.update { s ->
                                        s.copy(eqAssign = if (n == "None") s.eqAssign - key else s.eqAssign + (key to n))
                                    }
                                }
                                .padding(vertical = 10.dp)
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}
