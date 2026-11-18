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
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.LibraryAdd
import kotlinx.coroutines.launch
import com.powersound.player.net.SmbStore
import com.powersound.player.net.SmbServer
import com.powersound.player.net.Smb
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.rounded.FolderShared
import androidx.compose.material.icons.rounded.Add
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
    onPlayVideo: (List<DlnaItem>, Int) -> Unit,
    /** Songs were added to / taken out of the library: read it again. */
    onLibraryChanged: () -> Unit = {}
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
    var failReason by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(server, folderId, reload) {
        val s = server
        items = null
        failed = false
        failReason = null
        if (s != null) {
            val shared = s.controlUrl.takeIf { it.startsWith("smb:") }?.let { SmbStore.get(context, it.removePrefix("smb:")) }
            val list = withContext(Dispatchers.IO) {
                if (shared != null) {
                    // a Windows shared folder: "0" is where it starts
                    runCatching { Smb.items(shared, if (folderId == "0") shared.root else folderId) }
                        .onFailure { e -> failReason = Smb.explain(e) }.getOrNull()
                } else Dlna.browse(s, folderId)
            }
            if (list == null) failed = true else items = list
        }
    }

    fun up() {
        if (path.isNotEmpty()) NetworkStore.path.value = path.dropLast(1) else NetworkStore.server.value = null
    }
    BackHandler(enabled = server != null) { up() }

    // Windows shared folders you added; adding and removing one
    val shares by SmbStore.servers.collectAsState()
    LaunchedEffect(Unit) { SmbStore.load(context) }
    var addShare by remember { mutableStateOf(false) }
    var removeShare by remember { mutableStateOf<SmbServer?>(null) }
    if (addShare) AddShareDialog(onDismiss = { addShare = false })
    removeShare?.let { sh ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { removeShare = null },
            containerColor = Color(0xFF1B1B22),
            title = { Text("Remove \"${sh.name}\"?", color = Color.White) },
            text = { Text("It is only taken out of PowerSound. Nothing changes on the computer.", color = Color.White.copy(alpha = 0.7f)) },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { SmbStore.remove(context, sh.id); removeShare = null }) { Text("Remove") } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { removeShare = null }) { Text("Cancel") } }
        )
    }

    // Add to Library: progress, and the folders already added
    val adding by com.powersound.player.net.NetLibrary.progress.collectAsState()
    val origins by com.powersound.player.net.NetLibrary.origins.collectAsState()
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { com.powersound.player.net.NetLibrary.songs(context) } }

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
                    } else {
                        if (audio.isNotEmpty()) {
                            PillButton(icon = Icons.Rounded.PlayArrow, text = "Play", onClick = { onPlayAudio(audio, 0, false) })
                            Spacer(Modifier.width(8.dp))
                            PillButton(icon = Icons.Rounded.Shuffle, text = "Shuffle", onClick = { onPlayAudio(audio, 0, true) })
                            Spacer(Modifier.width(8.dp))
                        }
                        // this folder (and its subfolders) into Folders, Albums, Artists, Favorites…
                        val origin = s.controlUrl + "|" + folderId
                        if (items != null && (audio.isNotEmpty() || list.any { it.kind == 0 })) {
                            val added = origin in origins
                            PillButton(
                                icon = if (added) Icons.Rounded.RemoveCircleOutline else Icons.Rounded.LibraryAdd,
                                text = if (added) "Remove from Library" else "Add to Library",
                                onClick = {
                                    if (adding != null) return@PillButton
                                    if (added) {
                                        com.powersound.player.net.NetLibrary.remove(context, origin) {
                                            android.widget.Toast.makeText(context, com.powersound.player.data.Lang.tr("Taken out of the library"), android.widget.Toast.LENGTH_SHORT).show()
                                            onLibraryChanged()
                                        }
                                    } else {
                                        val shared = s.controlUrl.takeIf { it.startsWith("smb:") }?.let { SmbStore.get(context, it.removePrefix("smb:")) }
                                        val label = (listOf("Network", s.name) + path.map { it.second }).joinToString("/")
                                        com.powersound.player.net.NetLibrary.add(
                                            context, origin, label, list,
                                            children = { f -> if (shared != null) runCatching { Smb.items(shared, f.id) }.getOrNull() else Dlna.browse(s, f.id) },
                                            smb = shared
                                        ) { n ->
                                            android.widget.Toast.makeText(
                                                context, com.powersound.player.data.Lang.tr("Added $n songs to the library"), android.widget.Toast.LENGTH_LONG
                                            ).show()
                                            onLibraryChanged()
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
        adding?.let { p ->
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = primary, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(p, color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text(
                        "Stop", color = primary, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                        modifier = Modifier.clickable { com.powersound.player.net.NetLibrary.cancel() }.padding(8.dp)
                    )
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
            // ---- Windows shared folders (SMB)
            item {
                Text(
                    "Windows shared folders",
                    color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 4.dp)
                )
            }
            itemsIndexed(shares, key = { _, it -> "smb:" + it.id }) { _, sh ->
                NetRow(
                    Icons.Rounded.FolderShared, Color(0xFF5B8DEF), sh.name,
                    "\\\\${sh.host}\\${sh.share}" + (if (sh.root.isNotEmpty()) "\\" + sh.root.replace('/', '\\') else "") +
                        (if (sh.user.isNotEmpty()) "  •  ${sh.user}" else ""),
                    false,
                    onLongClick = { removeShare = sh }
                ) {
                    NetworkStore.path.value = emptyList()
                    NetworkStore.server.value = DlnaServer(sh.name, "smb:" + sh.id, "smb://${sh.host}/${sh.share}")
                }
            }
            item {
                NetRow(Icons.Rounded.Add, primary, "Add shared folder", "\\\\PC\\Music, with its Windows user", false) { addShare = true }
            }
        } else {
            // ---- a folder of the server
            when {
                failed -> item {
                    Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            failReason?.let { "${s.name}:\n$it" } ?: "${s.name} didn't answer.\nIs it still on, and on the same Wi-Fi?",
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
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun NetRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    title: String,
    sub: String,
    current: Boolean,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (current) Color.White.copy(alpha = 0.10f) else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
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

/** Library → Network → Add shared folder: \\PC\Music and its Windows user; checked before it is kept. */
@Composable
private fun AddShareDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var address by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = Color(0xFF1B1B22),
        title = { Text("Add shared folder", color = Color.White) },
        text = {
            Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                Text(
                    "The address of a shared folder on the computer, like \\\\192.168.1.10\\Music. The computer's IP address works best " +
                        "(Windows: Settings → Network → Properties). User and password: the Windows account's (a Microsoft account: its e-mail). " +
                        "Leave them empty for a folder shared with everyone.",
                    color = Color.White.copy(alpha = 0.65f), fontSize = 12.sp
                )
                ShareField(address, { address = it; error = null }, "Address  (\\\\PC\\Music)")
                ShareField(user, { user = it; error = null }, "User")
                ShareField(password, { password = it; error = null }, "Password", secret = true)
                ShareField(name, { name = it }, "Name (optional)")
                if (busy) Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Connecting…", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                }
                error?.let { Text(it, color = Color(0xFFFF8A80), fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp)) }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(enabled = !busy, onClick = {
                val parts = SmbStore.parse(address)
                if (parts == null) {
                    error = "Write the computer and the shared folder, like \\\\192.168.1.10\\Music"
                    return@TextButton
                }
                val (host, share, root) = parts
                // DOMAIN\user or user@domain stay as written; "PC\user" gives the domain
                val dom = if (user.contains('\\')) user.substringBefore('\\') else ""
                val usr = if (user.contains('\\')) user.substringAfter('\\') else user.trim()
                val server = SmbServer(
                    id = System.currentTimeMillis().toString(36),
                    name = name.trim().ifEmpty { if (root.isNotEmpty()) root.substringAfterLast('/') else share },
                    host = host, share = share, user = usr, password = password, domain = dom, root = root
                )
                busy = true
                scope.launch {
                    val problem = withContext(Dispatchers.IO) {
                        Smb.test(server) ?: if (root.isNotEmpty()) runCatching { Smb.list(server, root); null }.getOrElse { Smb.explain(it) } else null
                    }
                    busy = false
                    if (problem != null) error = problem
                    else {
                        SmbStore.add(context, server)
                        onDismiss()
                    }
                }
            }) { Text("Add") }
        },
        dismissButton = { androidx.compose.material3.TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ShareField(value: String, onChange: (String) -> Unit, label: String, secret: Boolean = false) {
    androidx.compose.material3.OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (secret) androidx.compose.ui.text.input.PasswordVisualTransformation()
            else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = if (secret) androidx.compose.ui.text.input.KeyboardType.Password else androidx.compose.ui.text.input.KeyboardType.Uri
        ),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
    )
}
