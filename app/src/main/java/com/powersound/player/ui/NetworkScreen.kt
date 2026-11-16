package com.powersound.player.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import com.powersound.player.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.powersound.player.cast.MediaServer
import com.powersound.player.data.Category
import com.powersound.player.data.formatTime
import com.powersound.player.net.Dlna
import com.powersound.player.net.DlnaItem
import com.powersound.player.net.DlnaServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray

/** Where you are in Library → Network; kept while the app runs, so coming back from the player lands in the same folder. */
object NetworkStore {
    private const val PREFS = "powersound_network"

    /** Servers found (now, or remembered from last time). */
    val servers = MutableStateFlow<List<DlnaServer>>(emptyList())

    /** The open server and the folders walked into: id and name of each. */
    val server = MutableStateFlow<DlnaServer?>(null)
    val path = MutableStateFlow<List<Pair<String, String>>>(emptyList())

    /** Description addresses of the servers seen before: asked again first, so they show at once. */
    fun remembered(context: Context): List<String> = runCatching {
        val a = JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("servers", null) ?: "[]")
        (0 until a.length()).map { a.getString(it) }
    }.getOrDefault(emptyList())

    fun remember(context: Context, list: List<DlnaServer>) {
        val a = JSONArray()
        list.forEach { a.put(it.location) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("servers", a.toString()).apply()
    }
}

/**
 * Library → Network: media servers on the Wi-Fi (DLNA / UPnP), their folders, and the songs and videos in them.
 * A song plays with every song of its folder; a video opens in the video player with the folder's other videos.
 */
@Composable
fun NetworkScreen(
    bottomInset: Dp,
    playingId: String?,
    onBack: () -> Unit,
    onPlayAudio: (List<DlnaItem>, Int, Boolean) -> Unit,
    onPlayVideo: (List<DlnaItem>, Int) -> Unit
) {
    val context = LocalContext.current
    val primary = MaterialTheme.colorScheme.primary
    val servers by NetworkStore.servers.collectAsState()
    val server by NetworkStore.server.collectAsState()
    val path by NetworkStore.path.collectAsState()

    var searching by remember { mutableStateOf(false) }
    var search by remember { mutableIntStateOf(0) }
    var noWifi by remember { mutableStateOf(false) }

    // ---- look for servers: the remembered ones first, then whoever answers on the Wi-Fi
    LaunchedEffect(search) {
        searching = true
        val wifi = withContext(Dispatchers.IO) { MediaServer.localAddress() }
        noWifi = wifi == null
        val found = withContext(Dispatchers.IO) {
            val known = NetworkStore.remembered(context)
            val first = known.mapNotNull { Dlna.describe(it) }
            if (first.isNotEmpty() && NetworkStore.servers.value.isEmpty()) NetworkStore.servers.value = first
            // some phones only hear the answers while this lock is held
            val lock = runCatching {
                (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager)
                    .createMulticastLock("PowerSound:dlna").apply { setReferenceCounted(false); acquire() }
            }.getOrNull()
            val locations = try {
                Dlna.search(wifi)
            } finally {
                runCatching { lock?.release() }
            }
            val fresh = locations.filter { l -> first.none { it.location == l } }.mapNotNull { Dlna.describe(it) }
            (first + fresh).distinctBy { it.controlUrl }.sortedBy { it.name.lowercase() }
        }
        NetworkStore.servers.value = found
        if (found.isNotEmpty()) NetworkStore.remember(context, found)
        searching = false
    }

    // ---- the open folder
    val folderId = path.lastOrNull()?.first ?: "0"
    var items by remember { mutableStateOf<List<DlnaItem>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(server, folderId, reload) {
        val s = server
        items = null
        failed = false
        if (s != null) {
            val list = withContext(Dispatchers.IO) { Dlna.browse(s, folderId) }
            if (list == null) failed = true else items = list
        }
    }

    fun up() {
        if (path.isNotEmpty()) NetworkStore.path.value = path.dropLast(1) else NetworkStore.server.value = null
    }
    BackHandler(enabled = server != null) { up() }

    val list = items.orEmpty()
    val audio = remember(items) { list.filter { it.kind == 1 } }
    val video = remember(items) { list.filter { it.kind == 2 } }
    val s = server

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomInset + 20.dp)) {
        item {
            PageHeader(
                if (s == null) "Library" else path.dropLast(1).lastOrNull()?.second ?: if (path.isEmpty()) Category.NETWORK.label else s.name,
                if (s == null) Category.NETWORK.label else path.lastOrNull()?.second ?: s.name,
                Category.NETWORK.icon(), Category.NETWORK.color(),
                onBack = { if (s == null) onBack() else up() }
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (s == null) {
                        PillButton(icon = Icons.Rounded.Refresh, text = if (searching) "Searching…" else "Search again", onClick = { if (!searching) search++ })
                    } else if (audio.isNotEmpty()) {
                        PillButton(icon = Icons.Rounded.PlayArrow, text = "Play", onClick = { onPlayAudio(audio, 0, false) })
                        Spacer(Modifier.width(8.dp))
                        PillButton(icon = Icons.Rounded.Shuffle, text = "Shuffle", onClick = { onPlayAudio(audio, 0, true) })
                    }
                }
            }
        }

        if (s == null) {
            // ---- the servers
            if (servers.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (searching) {
                        CircularProgressIndicator(color = primary, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(1.dp))
                        Text("Looking for media servers on the Wi-Fi…", color = Color.White.copy(alpha = 0.7f), modifier = Modifier.padding(top = 14.dp))
                    } else {
                        Text(
                            if (noWifi) "The phone is not on Wi-Fi.\nConnect to the same Wi-Fi as the computer, then tap Search again."
                            else "No media server answered.\n\n" +
                                "A media server (DLNA) shares a computer's music and videos with the home network:\n\n" +
                                "• Windows: Control Panel → Network and Sharing Center → Media streaming options → Turn on media streaming. " +
                                "It shares the Music and Videos folders.\n" +
                                "• A router or NAS with a disk: turn on \"Media server\" / \"DLNA\" in its settings.\n" +
                                "• Plex, Jellyfin, Kodi, Serviio: turn on their DLNA option.\n\n" +
                                "The phone and the server have to be on the same Wi-Fi. Then tap Search again.",
                            color = Color.White.copy(alpha = 0.65f),
                            fontSize = 13.sp,
                            textAlign = TextAlign.Start
                        )
                    }
                }
            }
            itemsIndexed(servers, key = { _, it -> it.controlUrl }) { _, sv ->
                NetRow(Category.NETWORK.icon(), Category.NETWORK.color(), sv.name, runCatching { java.net.URL(sv.location).host }.getOrDefault(""), false) {
                    NetworkStore.path.value = emptyList()
                    NetworkStore.server.value = sv
                }
            }
            if (servers.isNotEmpty() && searching) item {
                Text("Searching…", color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
            }
        } else {
            // ---- a folder of the server
            when {
                failed -> item {
                    Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "${s.name} didn't answer.\nIs it still on, and on the same Wi-Fi?",
                            color = Color.White.copy(alpha = 0.7f), textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.size(12.dp))
                        PillButton(icon = Icons.Rounded.Refresh, text = "Try again", onClick = { reload++ })
                    }
                }
                items == null -> item {
                    Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = primary, modifier = Modifier.size(28.dp))
                    }
                }
                list.isEmpty() -> item {
                    Text(
                        "No folders, music or videos here.",
                        color = Color.White.copy(alpha = 0.6f), textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(32.dp)
                    )
                }
            }
            itemsIndexed(list, key = { i, e -> "$i:${e.id}" }) { _, e ->
                when (e.kind) {
                    0 -> NetRow(
                        Icons.Rounded.Folder, Color(0xFFD9A441), e.title,
                        if (e.childCount >= 0) "${e.childCount} ${if (e.childCount == 1) "item" else "items"}" else "Folder", false
                    ) { NetworkStore.path.value = path + (e.id to e.title) }
                    1 -> NetRow(
                        Icons.Rounded.MusicNote, primary, e.title,
                        listOfNotNull(
                            e.artist, e.album, e.durationMs.takeIf { d -> d > 0 }?.let { d -> formatTime(d) },
                            e.mime?.substringAfter('/')?.removePrefix("x-")?.uppercase()
                        ).joinToString(" • "),
                        playingId == netId(e)
                    ) { onPlayAudio(audio, audio.indexOf(e).coerceAtLeast(0), false) }
                    else -> NetRow(
                        Icons.Rounded.Movie, Color(0xFFE0645C), e.title,
                        listOfNotNull(
                            e.durationMs.takeIf { d -> d > 0 }?.let { d -> formatTime(d) },
                            e.size.takeIf { z -> z > 0 }?.let { z -> if (z >= 1L shl 30) "%.1f GB".format(z / (1L shl 30).toDouble()) else "${z shr 20} MB" },
                            e.mime?.substringAfter('/')?.removePrefix("x-")?.uppercase()
                        ).joinToString(" • ").ifEmpty { "Video" },
                        false
                    ) { onPlayVideo(video, video.indexOf(e).coerceAtLeast(0)) }
                }
            }
        }
    }
}

/** The player's id of a network song (so the list can mark the one that is playing). */
fun netId(item: DlnaItem): String = "net:" + (item.url ?: item.id).hashCode()

@Composable
private fun NetRow(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, title: String, sub: String, current: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (current) Color.White.copy(alpha = 0.10f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CategoryIcon(icon, tint, 44)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = if (current) MaterialTheme.colorScheme.primary else Color.White,
                fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            if (sub.isNotEmpty()) Text(sub, color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
