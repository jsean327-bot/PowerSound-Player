package com.powersound.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.powersound.player.data.Stats
import com.powersound.player.data.StatsStore
import java.util.Calendar

/** One ranked line: name, how many plays, a cover. */
private class Top(val name: String, val sub: String, val plays: Int, val coverId: Long?)

/**
 * Menu → Statistics: how much you listened this month / this year / ever, your top artists, songs and albums,
 * and what your library holds. Month and year count from the version that added this page.
 */
@Composable
fun StatsScreen(songs: List<Song>, stats: Stats, onDismiss: () -> Unit) {
    var period by remember { mutableIntStateOf(0) }  // 0 month, 1 year, 2 ever
    val log = remember { StatsStore.playLog() }
    val byId = remember(songs) { songs.associateBy { it.id } }
    val plays: Map<Long, Int> = remember(period, log, stats) {
        if (period == 2) stats.plays
        else {
            val c = Calendar.getInstance()
            c.set(Calendar.DAY_OF_MONTH, 1)
            if (period == 1) c.set(Calendar.MONTH, Calendar.JANUARY)
            c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
            val from = c.timeInMillis
            log.filter { it.first >= from }.groupingBy { it.second }.eachCount()
        }
    }
    val known = plays.filterKeys { it in byId }
    val totalPlays = known.values.sum()
    val minutes = known.entries.sumOf { (id, n) -> (byId[id]?.durationMs ?: 0L) * n } / 60_000L
    fun tops(group: (Song) -> String, sub: (Song) -> String): List<Top> =
        known.entries.groupBy { group(byId[it.key]!!) }
            .map { (name, e) ->
                val best = e.maxByOrNull { it.value }!!.key
                Top(name, sub(byId[best]!!), e.sumOf { it.value }, best)
            }
            .sortedByDescending { it.plays }.take(10)
    val topArtists = remember(known) { tops({ it.artist }, { "" }) }
    val topAlbums = remember(known) { tops({ it.album + "\u0001" + it.albumArtist }, { it.albumArtist }).map { Top(it.name.substringBefore('\u0001'), it.sub, it.plays, it.coverId) }.take(5) }
    val topSongs = remember(known) {
        known.entries.sortedByDescending { it.value }.take(10).map { (id, n) -> byId[id]!!.let { Top(it.title, it.artist, n, id) } }
    }
    val lossless = songs.count { it.formatLabel == "FLAC" || it.formatLabel == "WAV" || it.formatLabel == "ALAC" }
    val libHours = songs.sumOf { it.durationMs } / 3_600_000L
    val primary = MaterialTheme.colorScheme.primary

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color(0xFF0E0E13))) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Statistics", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, contentDescription = "Close", tint = Color.White) }
                    }
                    Row(Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("This month", "This year", "All time").forEachIndexed { i, label ->
                            Text(
                                label,
                                color = if (i == period) Color.Black else Color.White,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(if (i == period) Color.White else Color.White.copy(alpha = 0.10f))
                                    .clickable { period = i }
                                    .padding(horizontal = 14.dp, vertical = 8.dp)
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        BigNumber(if (minutes >= 120) "${minutes / 60} h" else "$minutes min", "listened", primary, Modifier.weight(1f))
                        BigNumber("$totalPlays", "plays", primary, Modifier.weight(1f))
                        BigNumber("${known.size}", "songs", primary, Modifier.weight(1f))
                    }
                    if (totalPlays == 0) {
                        Text(
                            if (period == 2) "Nothing played yet." else "Nothing counted yet for this time. Plays are counted from this version on.",
                            color = Color.White.copy(alpha = 0.6f),
                            modifier = Modifier.padding(vertical = 20.dp)
                        )
                    }
                }
                if (topArtists.isNotEmpty()) item { Section("Top artists") }
                topArtists.forEachIndexed { i, t -> item { TopRow(i + 1, t, primary, maxOf(1, topArtists.first().plays)) } }
                if (topSongs.isNotEmpty()) item { Section("Top songs") }
                topSongs.forEachIndexed { i, t -> item { TopRow(i + 1, t, primary, maxOf(1, topSongs.first().plays)) } }
                if (topAlbums.isNotEmpty()) item { Section("Top albums") }
                topAlbums.forEachIndexed { i, t -> item { TopRow(i + 1, t, primary, maxOf(1, topAlbums.first().plays)) } }
                item {
                    Section("Your library")
                    Text(
                        "${songs.size} songs • ${songs.map { it.artist }.distinct().size} artists • $libHours hours of music\n" +
                            "Lossless (FLAC / WAV / ALAC): $lossless songs" +
                            (if (songs.isNotEmpty()) " (${lossless * 100 / songs.size}%)" else ""),
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 14.sp
                    )
                    Spacer(Modifier.height(40.dp))
                }
            }
        }
    }
}

@Composable
private fun BigNumber(value: String, label: String, color: Color, modifier: Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = 0.07f))
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(value, color = color, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(label, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
    }
}

@Composable
private fun Section(title: String) {
    Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 22.dp, bottom = 6.dp))
}

@Composable
private fun TopRow(rank: Int, t: Top, color: Color, max: Int) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("$rank", color = Color.White.copy(alpha = 0.6f), fontWeight = FontWeight.Bold, modifier = Modifier.width(26.dp))
        Artwork(t.coverId, Modifier.size(44.dp), sizePx = 144, cornerDp = 8)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(t.name, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (t.sub.isNotBlank()) Text(t.sub, color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // a bar for how much compared with number 1
            Box(
                Modifier
                    .padding(top = 4.dp)
                    .fillMaxWidth(t.plays / max.toFloat())
                    .height(4.dp)
                    .clip(RoundedCornerShape(50))
                    .background(color.copy(alpha = 0.7f))
            )
        }
        Spacer(Modifier.width(10.dp))
        Text("${t.plays}×", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
    }
}
