package com.powersound.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.TextSnippet
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.powersound.player.PlaybackPos
import com.powersound.player.data.LibraryStores
import com.powersound.player.data.Lyrics
import com.powersound.player.data.LyricsReader
import com.powersound.player.data.Song
import com.powersound.player.data.formatTime
import kotlinx.coroutines.flow.StateFlow
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

private val CardBg = Color(0xFF1B1B22)

// ================================================================= song menu (⋮ on the cover)

@Composable
fun SongMenuDialog(
    song: Song,
    rating: Int,
    onDismiss: () -> Unit,
    onLike: () -> Unit,
    onDislike: () -> Unit,
    onDelete: () -> Unit,
    onPlaylist: () -> Unit,
    onBookmark: () -> Unit,
    onAlbumArt: () -> Unit,
    onInfo: () -> Unit,
    onLyrics: () -> Unit,
    onArtist: () -> Unit,
    onAlbum: () -> Unit,
    onFolder: () -> Unit,
    onGenre: () -> Unit,
    onPlayNext: (() -> Unit)? = null,
    onAddToQueue: (() -> Unit)? = null,
    stars: Int = 0,
    onStars: (Int) -> Unit = {}
) {
    val ratingType = com.powersound.player.data.SettingsStore.value.ratingType
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(CardBg)
                .padding(vertical = 12.dp)
        ) {
            Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(song.id, Modifier.size(76.dp), sizePx = 228, cornerDp = 10)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(song.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${song.artist} - ${song.album}", color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("♪ ${formatTime(song.durationMs)} | ${song.formatLabel.lowercase()}", color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp)
                    Spacer(Modifier.height(4.dp))
                    // Settings → Player Screen → Rating Type
                    if (ratingType == 1) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            MiniRound(if (rating == 1) Icons.Rounded.ThumbUp else Icons.Outlined.ThumbUp, rating == 1, onLike)
                            MiniRound(if (rating == -1) Icons.Rounded.ThumbDown else Icons.Outlined.ThumbDown, rating == -1, onDislike)
                        }
                    } else if (ratingType >= 2) {
                        Row {
                            for (i in 1..5) {
                                Icon(
                                    if (i <= stars) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                                    contentDescription = "$i stars",
                                    tint = if (i <= stars) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.7f),
                                    modifier = Modifier
                                        .size(30.dp)
                                        .clip(CircleShape)
                                        .clickable { onStars(i) }
                                        .padding(3.dp)
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            fun act(f: () -> Unit): () -> Unit = { onDismiss(); f() }
            if (onPlayNext != null && onAddToQueue != null) {
                MenuRow(Icons.Rounded.PlaylistPlay, "Play next", act(onPlayNext), Icons.Rounded.Add, "Add to queue", act(onAddToQueue))
            }
            // Settings → Library → Lists → Delete Action
            if (com.powersound.player.data.SettingsStore.value.deleteAction) MenuRow(Icons.Rounded.Delete, "Delete", act(onDelete))
            MenuRow(Icons.Rounded.PlaylistAdd, "Playlist", act(onPlaylist), Icons.Rounded.Bookmark, "Bookmark", act(onBookmark))
            MenuRow(Icons.Rounded.Image, "Album Art", act(onAlbumArt))
            MenuRow(Icons.Rounded.Info, "Info/Tags", act(onInfo), Icons.Rounded.TextSnippet, "Lyrics", act(onLyrics))
            MenuRow(Icons.Rounded.Mic, "Artist", act(onArtist), Icons.Rounded.Album, "Album", act(onAlbum))
            MenuRow(Icons.Rounded.Folder, "Folder", act(onFolder), Icons.Rounded.Category, "Genre", act(onGenre))
        }
    }
}

@Composable
private fun MiniRound(icon: ImageVector, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.10f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = if (active) MaterialTheme.colorScheme.primary else Color.White, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun MenuRow(
    icon: ImageVector, text: String, onClick: () -> Unit,
    icon2: ImageVector? = null, text2: String? = null, onClick2: (() -> Unit)? = null
) {
    Row(Modifier.fillMaxWidth()) {
        MenuCell(icon, text, onClick, Modifier.weight(1f))
        if (icon2 != null && text2 != null && onClick2 != null) MenuCell(icon2, text2, onClick2, Modifier.weight(1f))
        else Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun MenuCell(icon: ImageVector, text: String, onClick: () -> Unit, modifier: Modifier) {
    Row(
        modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ================================================================= sleep timer (dial)

@Composable
fun SleepTimerDialog(
    remainingMinutes: Int?,
    onSet: (minutes: Int, finishSong: Boolean) -> Unit,
    onCancelTimer: () -> Unit,
    onDismiss: () -> Unit
) {
    var minutes by remember { mutableIntStateOf(remainingMinutes ?: 0) }
    var finishSong by remember { mutableStateOf(false) }
    val max = 180
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardBg,
        title = {
            Text(
                "Sleep Timer: " + (remainingMinutes?.let { "$it min left" } ?: "disabled"),
                color = Color.White,
                fontSize = 16.sp
            )
        },
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (minutes == 0) "Disabled" else if (minutes >= 60) "%d h %02d min".format(minutes / 60, minutes % 60) else "$minutes min",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
                Spacer(Modifier.height(10.dp))
                PowerKnob(
                    label = "Turn to set",
                    valueText = "0 – 3 h",
                    fraction = minutes / max.toFloat(),
                    bipolar = false,
                    enabled = true,
                    sizeDp = 130,
                    onChange = { f -> minutes = (f * max).roundToInt() },
                    onReset = { minutes = 0 }
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(15, 30, 60, 90).forEach { m ->
                        Text(
                            "$m",
                            color = if (minutes == m) Color.Black else Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(if (minutes == m) Color.White else Color.White.copy(alpha = 0.10f))
                                .clickable { minutes = m }
                                .padding(horizontal = 12.dp, vertical = 5.dp)
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { finishSong = !finishSong },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = finishSong,
                        onCheckedChange = { finishSong = it },
                        colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary)
                    )
                    Text("Play last song to end", color = Color.White, fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (minutes > 0) onSet(minutes, finishSong) else onCancelTimer()
                onDismiss()
            }) { Text("OK") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onCancelTimer(); onDismiss() }) { Text("Disable") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

// ================================================================= info dialogs

@Composable
fun InfoTagsDialog(song: Song, audioInfo: String, onDismiss: () -> Unit) {
    val rows = listOf(
        "Title" to song.title,
        "Artist" to song.artist,
        "Album" to song.album,
        "Album artist" to song.albumArtist,
        "Genre" to song.genre.ifBlank { "—" },
        "Year" to if (song.year > 0) song.year.toString() else "—",
        "Track" to if (song.trackNumber > 0) song.trackNumber.toString() else "—",
        "Composer" to song.composer.ifBlank { "—" },
        "Duration" to formatTime(song.durationMs),
        "Format" to song.formatLabel,
        "Audio" to audioInfo.ifBlank { "—" },
        "File size" to "%.1f MB".format(song.size / 1_048_576.0),
        "Folder" to song.folder,
        "Added" to if (song.dateAdded > 0) DateFormat.getDateInstance().format(Date(song.dateAdded * 1000)) else "—"
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardBg,
        title = { Text("Info / Tags", color = Color.White) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                rows.forEach { (k, v) ->
                    Row(Modifier.padding(vertical = 4.dp)) {
                        Text(k, color = Color.White.copy(alpha = 0.55f), fontSize = 13.sp, modifier = Modifier.width(96.dp))
                        Text(v, color = Color.White, fontSize = 13.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
fun AudioInfoDialog(song: Song?, audioInfo: String, dspSummary: String, onDismiss: () -> Unit) {
    val engine = com.powersound.player.audio.AudioEngine
    val st = com.powersound.player.data.SettingsStore.value
    val eq = engine.settings.value
    val fx = engine.fx.value
    fun khz(r: Int) = if (r % 1000 == 0) "${r / 1000} kHz" else "%.1f kHz".format(r / 1000.0)
    // the audio path, step by step (like Settings → Audio → Audio Info)
    val steps = buildList {
        add("Track" to listOfNotNull(audioInfo.ifBlank { song?.formatLabel }, song?.let { "%.1f MB • %s".format(it.size / 1_048_576.0, formatTime(it.durationMs)) }).joinToString("\n"))
        add("Decoder" to (engine.decoderName.ifBlank { "Android MediaCodec" } + "\n" + engine.inputEncoding.ifBlank { "PCM" }))
        if (st.perfectBitPerfect) {
            add("DSP" to "Perfect Bit Perfect: no processing")
        } else {
            val dsp = ArrayList<String>()
            if (engine.inputRate > 0) dsp.add("${khz(engine.inputRate)} • 64-bit floating point")
            if (eq.enabled && !engine.eqBlocked) {
                val parametric = eq.bands.any { it.type != com.powersound.player.audio.BandType.PEAK }
                dsp.add("Equalizer: ${if (parametric) "parametric" else "graphic"}, ${eq.bands.size} bands (${eq.presetName})")
                if (eq.preampDb != 0.0) dsp.add("Preamp %+.1f dB".format(eq.preampDb))
                if (eq.toneEnabled && (eq.bassDb != 0.0 || eq.trebleDb != 0.0)) {
                    dsp.add("Tone: Bass ${eq.bassFreq.toInt()} Hz %+.1f dB, Treble %.1f kHz %+.1f dB".format(eq.bassDb, eq.trebleFreq / 1000, eq.trebleDb))
                }
                if (eq.limiter) dsp.add("Look-ahead limiter (only above 0 dB)")
                if (eq.autoHeadroom && engine.headroomDb < -0.05) dsp.add("Headroom %.1f dB".format(engine.headroomDb))
            } else if (engine.eqBlocked) dsp.add("Equalizer off for this output")
            if (engine.replayGainDb != 0.0) dsp.add("Replay Gain %+.1f dB".format(engine.replayGainDb))
            val effects = listOfNotNull(
                if (fx.spatialEnabled) "3D Surround (around ${(fx.spatialAmount * 100).toInt()}%, room ${(fx.spatialRoom * 100).toInt()}%)" else null,
                if (fx.reverbEnabled) "Reverb (${fx.reverbPreset})" else null,
                if (fx.tempoEnabled && fx.tempo != 1.0) "Tempo %.2fx".format(fx.tempo) else null,
                if (fx.mono) "Mono" else null,
                if (fx.stereoExpand > 0.0) "Stereo expand" else null,
                if (fx.balance != 0.0) "Balance %+d%%".format((fx.balance * 100).toInt()) else null,
                if (fx.volume < 1.0) "Volume ${(fx.volume * 100).toInt()}%" else null
            )
            if (effects.isNotEmpty()) dsp.add("Effects: " + effects.joinToString(", "))
            add("DSP" to dsp.joinToString("\n").ifBlank { "Nothing switched on — bit-exact pass-through" })
            val fade = listOf("No fading", "Non-gapless tracks", "All songs", "Shuffled songs")[st.autoFade] + " / " +
                listOf("No fading", "Short crossfade", "Crossfade")[st.manualFade]
            add("Crossfade" to fade)
            if (engine.outputRate > 0 && engine.outputRate != engine.inputRate) {
                add(
                    "Resampler" to "${khz(engine.inputRate)} → ${khz(engine.outputRate)} (the output's own rate), " +
                        if (st.resamplerType == 1) "very high quality, 160 dB" else "high quality, 130 dB"
                )
            } else add("Resampler" to "No resampling")
            if (engine.outputEncoding == "16-bit") add("Dither" to com.powersound.player.audio.Ditherer.NAMES.getOrElse(st.dither) { "" })
        }
        add(
            "Output" to "${engine.currentOutput}\nAndroid AudioTrack • ${engine.outputEncoding.ifBlank { "32-bit float" }}" +
                if (engine.outputRate > 0) " • ${khz(engine.outputRate)}" else ""
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardBg,
        title = { Text("Audio Info", color = Color.White) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                steps.forEachIndexed { i, (title, body) ->
                    Row {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(18.dp)) {
                            Box(Modifier.size(10.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                            if (i < steps.lastIndex) Box(Modifier.width(2.dp).height(44.dp).background(Color.White.copy(alpha = 0.25f)))
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.padding(bottom = 8.dp)) {
                            Text(title, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text(body, color = Color.White, fontSize = 12.sp)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
fun AlbumArtDialog(songId: Long, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Artwork(
            songId,
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clickable(onClick = onDismiss),
            sizePx = 1400,
            cornerDp = 18,
            placeholder = { BarsPlaceholder() }
        )
    }
}

// ================================================================= lyrics

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricsSheet(song: Song, position: StateFlow<PlaybackPos>, onSeek: (Long) -> Unit, onSearch: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val st by com.powersound.player.data.SettingsStore.settings.collectAsState()
    val lyrics by produceState<Lyrics?>(null, song.id) { value = LyricsReader.read(context, song.id, song.path) ?: Lyrics(emptyList()) }
    val pos by position.collectAsState()
    // Settings → Lyrics → Keep Screen On
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(st.lyricsKeepScreenOn) {
        val before = view.keepScreenOn
        if (st.lyricsKeepScreenOn) view.keepScreenOn = true
        onDispose { view.keepScreenOn = before }
    }
    // Settings → Lyrics → Lyrics Size, plus pinch to zoom
    var zoom by remember { mutableStateOf(1f) }
    val baseSp = when (st.lyricsSize) { 0 -> 13f; 2 -> 20f; else -> 16f } * zoom
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = CardBg) {
      Box {
        // Settings → Background → Lyrics Background
        if (st.bgLyrics) AppBackground(song.id, forList = true, modifier = Modifier.matchParentSize())
      Column {
        Text(
            song.title,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            modifier = Modifier.padding(horizontal = 20.dp)
        )
        Text(song.artist, color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp))
        Spacer(Modifier.height(10.dp))
        val l = lyrics
        when {
            l == null -> Box(Modifier.fillMaxWidth().padding(30.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            l.lines.isEmpty() -> Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "No lyrics found for this track.\nLyrics inside FLAC (LYRICS tag) / MP3 (USLT) files, or an .lrc file with the same name, are shown here.",
                    color = Color.White.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center
                )
                if (st.lyricsApp != 0) {
                    Spacer(Modifier.height(14.dp))
                    TextButton(onClick = { onSearch(); onDismiss() }) {
                        Text("Search lyrics: ${com.powersound.player.data.LYRICS_APPS[st.lyricsApp.coerceIn(0, com.powersound.player.data.LYRICS_APPS.lastIndex)]}")
                    }
                }
            }
            else -> {
                // Settings → Lyrics → Lyrics Time Offset (positive = earlier)
                val t = pos.positionMs + st.lyricsOffsetMs - com.powersound.player.audio.AudioEngine.outputDelayMs
                val current = if (l.synced) l.lines.indexOfLast { (it.timeMs ?: 0L) <= t } else -1
                val state = rememberLazyListState()
                LaunchedEffect(current) {
                    if (current > 2) state.animateScrollToItem(current - 2)
                }
                LazyColumn(
                    state = state,
                    modifier = Modifier
                        .heightIn(max = 520.dp)
                        .pointerInput(Unit) {
                            detectTransformGestures { _, _, z, _ -> zoom = (zoom * z).coerceIn(0.6f, 2.2f) }
                        },
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 30.dp)
                ) {
                    itemsIndexed(l.lines) { i, line ->
                        Text(
                            line.text.ifEmpty { " " },
                            color = when {
                                !l.synced -> Color.White
                                i == current -> MaterialTheme.colorScheme.primary
                                else -> Color.White.copy(alpha = 0.55f)
                            },
                            fontSize = (if (i == current) baseSp * 1.18f else baseSp).sp,
                            fontWeight = if (i == current) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(if (line.timeMs != null) Modifier.clickable { onSeek((line.timeMs - st.lyricsOffsetMs).coerceAtLeast(0L)) } else Modifier)
                                .padding(vertical = 5.dp)
                        )
                    }
                }
            }
        }
      }
      }
    }
}

/** Settings → Lyrics → Preferred Lyrics App: search the track's lyrics outside the app. */
fun searchLyrics(context: android.content.Context, song: Song) {
    val st = com.powersound.player.data.SettingsStore.value
    val q = "${song.artist} ${song.title}".trim()
    val enc = android.net.Uri.encode(q)
    val intent = when (st.lyricsApp) {
        1 -> android.content.Intent(
            android.content.Intent.ACTION_VIEW,
            android.net.Uri.parse(
                st.lyricsCustomUrl.replace("%ARTIST%", android.net.Uri.encode(song.artist)).replace("%TITLE%", android.net.Uri.encode(song.title)).replace(" ", "+")
            )
        )
        2 -> android.content.Intent(android.content.Intent.ACTION_WEB_SEARCH).putExtra(android.app.SearchManager.QUERY, "$q lyrics")
        3 -> android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://www.google.com/search?q=$enc+lyrics"))
        4 -> android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://www.musixmatch.com/search/$enc"))
        5 -> android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://genius.com/search?q=$enc"))
        else -> return
    }.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: Exception) {
        // no app for the web search: fall back to the browser
        runCatching {
            context.startActivity(
                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://www.google.com/search?q=$enc+lyrics"))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}

// ================================================================= playlists

@Composable
fun AddToPlaylistDialog(songIds: List<Long>, onDismiss: () -> Unit) {
    val playlists by LibraryStores.playlists.collectAsState()
    var creating by remember { mutableStateOf(false) }
    if (creating) {
        SaveNameDialog("New playlist", onSave = { LibraryStores.createPlaylist(it, songIds) }, onDismiss = { creating = false; onDismiss() })
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardBg,
        title = { Text("Add to playlist", color = Color.White) },
        text = {
            LazyColumn(Modifier.heightIn(max = 380.dp)) {
                item {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { creating = true }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Text("New playlist", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }
                }
                items(playlists, key = { it.id }) { p ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { LibraryStores.addToPlaylist(p.id, songIds); onDismiss() }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.PlaylistPlay, contentDescription = null, tint = Color.White)
                        Spacer(Modifier.width(12.dp))
                        Text(p.name, color = Color.White, modifier = Modifier.weight(1f))
                        Text("${p.songIds.size}", color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ================================================================= generic choice list

@Composable
fun ChoiceDialog(title: String, options: List<String>, selected: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardBg,
        title = { Text(title, color = Color.White) },
        text = {
            Column {
                options.forEachIndexed { i, o ->
                    Text(
                        o,
                        color = if (i == selected) MaterialTheme.colorScheme.primary else Color.White,
                        fontWeight = if (i == selected) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(i); onDismiss() }
                            .padding(vertical = 11.dp)
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}
