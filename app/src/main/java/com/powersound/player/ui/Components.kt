package com.powersound.player.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.AddCircle
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlayCircleFilled
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material.icons.rounded.Timelapse
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.powersound.player.data.ArtworkLoader
import com.powersound.player.data.Category
import com.powersound.player.data.Song
import com.powersound.player.data.formatTime
import kotlin.math.roundToInt

val ChipBg = Color.Black.copy(alpha = 0.55f)
val PillBg = Color(0xFF15151C).copy(alpha = 0.80f)
val PillBorder = Color.White.copy(alpha = 0.14f)

// ---------------------------------------------------------------- artwork

@Composable
fun Artwork(
    songId: Long?,
    modifier: Modifier = Modifier,
    sizePx: Int = 192,
    cornerDp: Int = 8,
    placeholder: @Composable BoxScope.() -> Unit = { NotePlaceholder() }
) {
    val context = LocalContext.current
    val gen by ArtworkLoader.generation.collectAsState()
    val st by com.powersound.player.data.SettingsStore.settings.collectAsState()
    val bitmap by produceState<ImageBitmap?>(
        initialValue = songId?.let { ArtworkLoader.cached(it, sizePx) },
        songId, sizePx, gen, st.artForceDefault, st.art24bit
    ) {
        value = songId?.let { ArtworkLoader.load(context, it, sizePx) }
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(com.powersound.player.ui.cornerDp(cornerDp).dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        val b = bitmap
        if (b != null) {
            // Settings → Album Art → Aspect Ratio: default (crop near-square covers), keep aspect ratio, square
            val ratio = b.width.toFloat() / b.height.coerceAtLeast(1)
            val fit = when (st.artAspect) {
                1 -> true
                2 -> false
                else -> ratio < 0.8f || ratio > 1.25f
            }
            Image(
                bitmap = b,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = if (fit) ContentScale.Fit else ContentScale.Crop
            )
        } else {
            placeholder()
        }
    }
}

/** Artist / album artist / composer picture (Settings → Album Art → Download Artist Images), else the album art. */
@Composable
fun ArtistArtwork(
    name: String,
    fallbackSongId: Long?,
    modifier: Modifier = Modifier,
    sizePx: Int = 384,
    cornerDp: Int = 0,
    placeholder: @Composable BoxScope.() -> Unit = { BarsPlaceholder() }
) {
    val context = LocalContext.current
    val gen by ArtworkLoader.generation.collectAsState()
    val st by com.powersound.player.data.SettingsStore.settings.collectAsState()
    val img by produceState<ImageBitmap?>(null, name, gen, st.artDownloadArtist, st.artForceDefault) {
        value = ArtworkLoader.loadArtist(context, name, sizePx)
    }
    val i = img
    if (i != null) {
        Box(modifier.clip(RoundedCornerShape(com.powersound.player.ui.cornerDp(cornerDp).dp))) {
            Image(bitmap = i, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
    } else {
        Artwork(fallbackSongId, modifier, sizePx = sizePx, cornerDp = cornerDp, placeholder = placeholder)
    }
}

@Composable
fun BoxScope.NotePlaceholder() {
    Icon(
        imageVector = Icons.Rounded.MusicNote,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
        modifier = Modifier
            .align(Alignment.Center)
            .fillMaxSize(0.45f)
    )
}

/** Big empty-tile placeholder: the PowerSound equalizer bars. */
@Composable
fun BoxScope.BarsPlaceholder() {
    val c = Color.White.copy(alpha = 0.16f)
    Canvas(
        Modifier
            .align(Alignment.Center)
            .fillMaxSize(0.5f)
    ) {
        val heights = listOf(0.35f, 0.7f, 1f, 0.55f)
        val bw = size.width / 7f
        heights.forEachIndexed { i, h ->
            val bh = size.height * h
            drawRoundRect(
                c,
                topLeft = Offset(bw * (i * 2 - 0.5f + 0.5f), (size.height - bh) / 2f),
                size = Size(bw, bh),
                cornerRadius = CornerRadius(bw / 2f)
            )
        }
    }
}

/**
 * Blurred cover of the current song (Settings → Background).
 * [forList] = behind lists / lyrics: always dimmed a bit more so text stays readable.
 */
@Composable
fun AppBackground(songId: Long?, forList: Boolean = false, modifier: Modifier = Modifier.fillMaxSize()) {
    val context = LocalContext.current
    val st by com.powersound.player.data.SettingsStore.settings.collectAsState()
    val gen by ArtworkLoader.generation.collectAsState()
    // Background Details: 0 = one solid colour … 10 = detailed
    val detailPx = listOf(1, 2, 3, 4, 6, 8, 12, 16, 24, 32, 48)[st.bgDetails.coerceIn(0, 10)]
    val bitmap by produceState<ImageBitmap?>(null, songId, st.bgEnabled, detailPx, gen) {
        value = if (st.bgEnabled && songId != null) {
            ArtworkLoader.load(context, songId, 192)?.let { img ->
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    val src = img.asAndroidBitmap()
                    android.graphics.Bitmap.createScaledBitmap(src, detailPx, detailPx, true).asImageBitmap()
                }
            }
        } else null
    }
    val bg = MaterialTheme.colorScheme.background
    // Saturation 0–300 %, Intensity 0–200 % (above 100 % the image is also brightened)
    val filter = remember(st.bgSaturation, st.bgIntensity) {
        val m = androidx.compose.ui.graphics.ColorMatrix().apply { setToSaturation(st.bgSaturation) }
        val bright = st.bgIntensity.coerceAtLeast(1f)
        if (bright > 1f) {
            val b = androidx.compose.ui.graphics.ColorMatrix(
                floatArrayOf(bright, 0f, 0f, 0f, 0f, 0f, bright, 0f, 0f, 0f, 0f, 0f, bright, 0f, 0f, 0f, 0f, 0f, 1f, 0f)
            )
            m.timesAssign(b)
        }
        androidx.compose.ui.graphics.ColorFilter.colorMatrix(m)
    }
    Box(modifier.background(bg)) {
        val b = bitmap
        if (b != null && st.bgEnabled) {
            Image(
                bitmap = b,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = st.bgIntensity.coerceIn(0f, 1f),
                colorFilter = filter,
                filterQuality = androidx.compose.ui.graphics.FilterQuality.High,
                modifier = Modifier
                    .fillMaxSize()
                    .blur((st.bgBlurStep * 16).dp)
            )
        }
        if (forList) Box(Modifier.fillMaxSize().background(bg.copy(alpha = 0.45f)))
        // Background Gradient (0–8) in the chosen colour
        val g = st.bgGradient.coerceIn(0, 8) / 8f
        if (g > 0f && (!forList || st.bgGradientLists)) {
            val c = Color(st.bgGradientColor)
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(c.copy(alpha = 0.25f * g), c.copy(alpha = 0.6f * g), c.copy(alpha = 0.95f * g))))
            )
        }
    }
}

// ---------------------------------------------------------------- chips & pills

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OverlayChip(
    text: String,
    big: Boolean = false,
    modifier: Modifier = Modifier,
    background: Boolean = true,
    marquee: Boolean = false,
    sizeSp: Int? = null
) {
    Text(
        text = text,
        color = Color.White,
        fontWeight = FontWeight.Bold,
        fontSize = (sizeSp ?: if (big) 15 else 11).sp,
        maxLines = 1,
        overflow = if (marquee) TextOverflow.Clip else TextOverflow.Ellipsis,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(if (background) ChipBg else Color.Transparent)
            .padding(horizontal = if (big) 10.dp else 8.dp, vertical = if (big) 3.dp else 2.dp)
            .then(if (marquee) Modifier.basicMarquee(iterations = Int.MAX_VALUE) else Modifier)
    )
}

@Composable
fun PillButton(
    icon: ImageVector? = null,
    text: String? = null,
    onClick: () -> Unit,
    active: Boolean = false,
    enabled: Boolean = true,
    size: Int = 40
) {
    val colors = MaterialTheme.colorScheme
    val content = when {
        !enabled -> Color.White.copy(alpha = 0.3f)
        active -> colors.primary
        else -> Color.White
    }
    Row(
        modifier = Modifier
            .height(size.dp)
            .then(if (text == null) Modifier.width(size.dp) else Modifier)
            .clip(RoundedCornerShape(50))
            .background(if (active) colors.primary.copy(alpha = 0.18f) else PillBg)
            .border(1.dp, PillBorder, RoundedCornerShape(50))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = if (text == null) 0.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (icon != null) Icon(icon, contentDescription = text, tint = content, modifier = Modifier.size((size * 0.52f).dp))
        if (icon != null && text != null) Spacer(Modifier.width(6.dp))
        if (text != null) Text(text, color = content, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    }
}

/** Shuffle • Play • Search • … row used at the top of every list (and floating when scrolled). */
@Composable
fun ActionPills(
    onShuffle: () -> Unit,
    onPlay: () -> Unit,
    onSearch: (() -> Unit)?,
    onMore: (() -> Unit)?,
    enabled: Boolean = true,
    searchActive: Boolean = false,
    modifier: Modifier = Modifier
) {
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        PillButton(icon = Icons.Rounded.Shuffle, onClick = onShuffle, enabled = enabled)
        PillButton(icon = Icons.Rounded.PlayArrow, onClick = onPlay, enabled = enabled)
        if (onSearch != null) PillButton(icon = Icons.Rounded.Search, onClick = onSearch, active = searchActive)
        Spacer(Modifier.weight(1f))
        if (onMore != null) PillButton(icon = Icons.Rounded.MoreVert, onClick = onMore)
    }
}

@Composable
fun BackChip(label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(ChipBg)
            .border(1.dp, PillBorder, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(start = 8.dp, end = 12.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Rounded.ArrowUpward, contentDescription = "Back", tint = Color.White, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1)
    }
}

// ---------------------------------------------------------------- categories

fun Category.icon(): ImageVector = when (this) {
    Category.ALL_SONGS -> Icons.Rounded.MusicNote
    Category.FOLDERS -> Icons.Rounded.Folder
    Category.FOLDERS_HIERARCHY -> Icons.Rounded.AccountTree
    Category.ALBUMS -> Icons.Rounded.Album
    Category.ARTISTS -> Icons.Rounded.Mic
    Category.ALBUM_ARTISTS -> Icons.Rounded.RecordVoiceOver
    Category.GENRES -> Icons.Rounded.Category
    Category.YEARS -> Icons.Rounded.DateRange
    Category.COMPOSERS -> Icons.Rounded.Edit
    Category.PLAYLISTS -> Icons.Rounded.PlaylistPlay
    Category.STREAMS -> Icons.Rounded.Radio
    Category.QUEUE -> Icons.Rounded.QueueMusic
    Category.BOOKMARKS -> Icons.Rounded.Bookmark
    Category.MOST_PLAYED -> Icons.Rounded.PlayCircleFilled
    Category.TOP_RATED -> Icons.Rounded.ThumbUp
    Category.LOW_RATED -> Icons.Rounded.ThumbDown
    Category.RECENTLY_PLAYED -> Icons.Rounded.History
    Category.RECENTLY_ADDED -> Icons.Rounded.AddCircle
    Category.LONG -> Icons.Rounded.Timelapse
}

fun Category.color(): Color = when (this) {
    Category.ALL_SONGS -> Color(0xFF7C8CF8)
    Category.FOLDERS, Category.FOLDERS_HIERARCHY -> Color(0xFF4F8DF7)
    Category.ALBUMS -> Color(0xFF8B7CF6)
    Category.ARTISTS, Category.ALBUM_ARTISTS -> Color(0xFF9A9AB0)
    Category.GENRES -> Color(0xFFC06BD8)
    Category.YEARS -> Color(0xFF4FB6C9)
    Category.COMPOSERS -> Color(0xFF3FAE6A)
    Category.PLAYLISTS -> Color(0xFFD08A5B)
    Category.STREAMS -> Color(0xFF6FBF73)
    Category.QUEUE -> Color(0xFFC9A63F)
    Category.BOOKMARKS -> Color(0xFF5B7FD0)
    Category.MOST_PLAYED -> Color(0xFFB98BF0)
    Category.TOP_RATED -> Color(0xFF7FA0C0)
    Category.LOW_RATED -> Color(0xFF8A8A8A)
    Category.RECENTLY_PLAYED -> Color(0xFF6D7CF0)
    Category.RECENTLY_ADDED -> Color(0xFF5DAE6C)
    Category.LONG -> Color(0xFF8E6FE0)
}

@Composable
fun CategoryIcon(icon: ImageVector, color: Color, size: Int = 34) {
    Box(
        Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.28f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size((size * 0.58f).dp))
    }
}

// ---------------------------------------------------------------- rows

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongRow(
    song: Song,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    zoom: Int = 1,
    extra: String? = null,
    /** "album", "folder" or "albumartist": the list the row is shown in (Settings → Library → Lists). */
    listKind: String = ""
) {
    val artDp = when (zoom) { 0 -> 42; 2 -> 78; else -> 56 }
    val titleSp = when (zoom) { 0 -> 13; 2 -> 17; else -> 15 }
    val colors = MaterialTheme.colorScheme
    val st by com.powersound.player.data.SettingsStore.settings.collectAsState()
    // Settings → Library → Lists / Player Screen → Animate Long Labels Everywhere
    // Settings → Library → Lists → Show Track Number: 1 separate number in album / folder lists, 2 in the meta line, 3 in the title
    val num = song.trackNumber
    val separateNumber = st.trackNumberMode == 1 && num > 0 && (listKind == "album" || listKind == "folder")
    val shownTitle = (if (st.filenameAsTitle) song.fileName else song.title).let { t ->
        if (st.trackNumberMode == 3 && num > 0) "$num. $t" else t
    }
    // Album Artist Label for Tracks / for Album Artist Tracks; Hide Unknown Album / Artist
    val useAlbumArtist = if (listKind == "albumartist") st.albumArtistAATracks else st.albumArtistTracks
    val artistLabel = (if (useAlbumArtist) song.albumArtist else song.artist)
        .takeUnless { st.hideUnknownArtist && it == "Unknown artist" }
    val albumLabel = song.album.takeUnless { st.hideUnknownAlbum && it == "Unknown album" }
    val subLine = listOfNotNull(artistLabel, albumLabel).joinToString(" - ")
    val metaPrefix = if (st.trackNumberMode == 2 && num > 0) "#$num  " else ""
    val marquee = st.marqueeEverywhere && isCurrent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (isCurrent) Color.White.copy(alpha = 0.10f) else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (separateNumber) {
            Text(
                num.toString(),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = if (isCurrent) colors.primary else Color.White.copy(alpha = 0.7f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.width(28.dp)
            )
            Spacer(Modifier.width(4.dp))
        }
        Artwork(songId = song.id, modifier = Modifier.size(artDp.dp), sizePx = if (zoom == 2) 240 else 168, cornerDp = 10)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = shownTitle,
                fontSize = titleSp.sp,
                fontWeight = FontWeight.Bold,
                color = if (isCurrent) colors.primary else Color.White,
                maxLines = 1,
                overflow = if (marquee) TextOverflow.Clip else TextOverflow.Ellipsis,
                modifier = if (marquee) Modifier.basicMarquee(iterations = Int.MAX_VALUE) else Modifier
            )
            if (subLine.isNotEmpty()) Text(
                text = subLine,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White.copy(alpha = 0.80f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "$metaPrefix♪ ${formatTime(song.durationMs)} | ${song.formatLabel.lowercase()}" + (extra?.let { "  •  $it" } ?: ""),
                fontSize = 10.sp,
                color = Color.White.copy(alpha = 0.55f),
                maxLines = 1
            )
        }
    }
}

// ---------------------------------------------------------------- A–Z fast scroller

/**
 * Letter bar on the right edge. Touch or drag on it to jump; a big bubble shows the letter.
 * [itemKeys] holds the letter of every item, in list order.
 */
@Composable
fun FastScroller(
    itemKeys: List<String>,
    onJump: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val letters = remember(itemKeys) {
        buildList {
            add("#")
            addAll(('A'..'Z').map { it.toString() })
            if (itemKeys.contains("ع")) add("ع")
        }
    }
    val jump by rememberUpdatedState(onJump)
    var active by remember { mutableStateOf<String?>(null) }
    var fingerY by remember { mutableFloatStateOf(0f) }
    var heightPx by remember { mutableIntStateOf(1) }

    fun indexFor(letter: String): Int {
        val pos = letters.indexOf(letter)
        for (i in pos until letters.size) {
            val idx = itemKeys.indexOf(letters[i])
            if (idx >= 0) return idx
        }
        for (i in pos downTo 0) {
            val idx = itemKeys.indexOf(letters[i])
            if (idx >= 0) return idx
        }
        return -1
    }

    fun pick(y: Float) {
        fingerY = y
        val i = ((y / heightPx) * letters.size).toInt().coerceIn(0, letters.lastIndex)
        val l = letters[i]
        if (l != active) {
            active = l
            val target = indexFor(l)
            if (target >= 0) jump(target)
        }
    }

    Box(modifier.width(110.dp)) {
        Column(
            Modifier
                .align(Alignment.CenterEnd)
                .width(22.dp)
                .fillMaxHeight()
                .onSizeChanged { heightPx = it.height.coerceAtLeast(1) }
                .pointerInput(letters, itemKeys) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        pick(down.position.y)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            if (!change.pressed) break
                            change.consume()
                            pick(change.position.y)
                        }
                        active = null
                    }
                },
            verticalArrangement = Arrangement.SpaceEvenly,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            letters.forEach { l ->
                Text(
                    l,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (l == active) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.55f)
                )
            }
        }
        val a = active
        if (a != null) {
            Box(
                Modifier
                    .offset { IntOffset(0, (fingerY - 36.dp.toPx()).roundToInt().coerceAtLeast(0)) }
                    .size(72.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xFF2A2A33).copy(alpha = 0.95f)),
                contentAlignment = Alignment.Center
            ) {
                Text(a, color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** Big header used by the category pages ("↑ Library", icon + title, action pills). */
@Composable
fun PageHeader(
    backLabel: String,
    title: String,
    icon: ImageVector,
    iconColor: Color,
    onBack: () -> Unit,
    actions: @Composable () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 6.dp)
    ) {
        BackChip(backLabel, onBack)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            CategoryIcon(icon, iconColor, size = 40)
            Spacer(Modifier.width(12.dp))
            Text(
                title,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.height(12.dp))
        actions()
    }
}

/** Square tile with cover art and the title/subtitle chips, like the Folders and Albums grids. */
@Composable
fun GroupTile(title: String, subtitle: String, coverId: Long?, key: String = "", onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
    ) {
        val person = key.startsWith("artist:") || key.startsWith("albumartist:") || key.startsWith("composer:")
        if (person) {
            ArtistArtwork(title, coverId, Modifier.fillMaxSize())
        } else {
            Artwork(
                songId = coverId,
                modifier = Modifier.fillMaxSize(),
                sizePx = 384,
                cornerDp = 0,
                placeholder = { BarsPlaceholder() }
            )
        }
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp)
        ) {
            OverlayChip(title, big = true)
            Spacer(Modifier.height(3.dp))
            OverlayChip(subtitle)
        }
    }
}


/**
 * Pinch with two fingers: spread = zoom in, pinch = zoom out. One step per gesture.
 * Single-finger scrolling is not affected.
 */
fun Modifier.pinchToZoom(key: Any, onZoomIn: () -> Unit, onZoomOut: () -> Unit): Modifier =
    pointerInput(key) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial)
            var startDist = 0f
            var fired = false
            while (true) {
                val e = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                val pressed = e.changes.filter { it.pressed }
                if (pressed.isEmpty()) break
                if (pressed.size >= 2) {
                    val d = (pressed[0].position - pressed[1].position).getDistance()
                    if (startDist == 0f) startDist = d
                    e.changes.forEach { it.consume() }
                    if (!fired && startDist > 0f) {
                        val ratio = d / startDist
                        if (ratio > 1.3f) {
                            onZoomIn(); fired = true
                        } else if (ratio < 0.77f) {
                            onZoomOut(); fired = true
                        }
                    }
                }
            }
        }
    }
