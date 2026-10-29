package com.powersound.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.powersound.player.PlaybackPos
import com.powersound.player.PlayerUiState
import kotlinx.coroutines.flow.StateFlow
import com.powersound.player.Tab

/** The floating card at the bottom: mini player + Library / EQ / Search / Menu. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FloatingBottomBar(
    state: PlayerUiState,
    tab: Tab,
    menuOpen: Boolean,
    onTab: (Tab) -> Unit,
    onMenu: () -> Unit,
    onOpenPlayer: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    position: StateFlow<PlaybackPos>,
    onOpenSource: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val openSource by rememberUpdatedState(onOpenSource)
    val open by rememberUpdatedState(onOpenPlayer)
    val next by rememberUpdatedState(onNext)
    val prev by rememberUpdatedState(onPrevious)

    val st by com.powersound.player.data.SettingsStore.settings.collectAsState()
    // Settings → Skin → Transparent Navbar / Offset Navbar
    val barAlpha = when (st.navbarStyle) { 1 -> 0.62f; 2 -> 0f; else -> 0.96f }
    val corner = cornerDp(26).dp
    Column(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = if (st.offsetNavbar) 18.dp else 8.dp, vertical = if (st.offsetNavbar) 12.dp else 6.dp)
            .clip(RoundedCornerShape(corner))
            .background(Color(0xFF1B1B23).copy(alpha = barAlpha))
            .border(1.dp, Color.White.copy(alpha = if (st.navbarStyle == 2) 0f else 0.06f), RoundedCornerShape(corner))
    ) {
        val np = state.nowPlaying
        if (np != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .combinedClickable(onClick = { open() }, onLongClick = { openSource() })
                    .pointerInput(Unit) {
                        var total = 0f
                        detectVerticalDragGestures(
                            onDragStart = { total = 0f },
                            onDragEnd = { if (total < -40f) open() },
                            onVerticalDrag = { change, dy -> change.consume(); total += dy }
                        )
                    }
                    .pointerInput(Unit) {
                        var total = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { total = 0f },
                            onDragEnd = {
                                if (total < -80f) next() else if (total > 80f) prev()
                            },
                            onHorizontalDrag = { change, dx -> change.consume(); total += dx }
                        )
                    }
                    .padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (st.artAnimation) {
                    // Settings → Player Screen → Album Art Animation (also for the bottom bar)
                    androidx.compose.animation.Crossfade(np.songId, animationSpec = androidx.compose.animation.core.tween(animMs(320)), label = "miniArt") { id ->
                        Artwork(id, Modifier.size(42.dp), sizePx = 144, cornerDp = 8)
                    }
                } else {
                    Artwork(np.songId, Modifier.size(42.dp), sizePx = 144, cornerDp = 8)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        np.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1,
                        overflow = if (st.marqueeEverywhere) TextOverflow.Clip else TextOverflow.Ellipsis,
                        modifier = if (st.marqueeEverywhere) Modifier.basicMarquee(iterations = Int.MAX_VALUE) else Modifier
                    )
                    Text(
                        listOf(np.artist, np.album).filter { it.isNotBlank() }.joinToString(" - "),
                        color = Color.White.copy(alpha = 0.75f),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = onTogglePlay) {
                    Icon(
                        if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = "Play/Pause",
                        tint = Color.White,
                        modifier = Modifier.size(30.dp)
                    )
                }
            }
            ProgressLine(position)
        }
        NavRow(tab, menuOpen, onTab, onMenu)
    }
}

@Composable
private fun ProgressLine(position: StateFlow<PlaybackPos>) {
    val pos by position.collectAsState()
    val fraction = if (pos.durationMs > 0) (pos.positionMs.toFloat() / pos.durationMs).coerceIn(0f, 1f) else 0f
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .height(5.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.12f))
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.85f))
                )
            }
}

@Composable
private fun NavRow(tab: Tab, menuOpen: Boolean, onTab: (Tab) -> Unit, onMenu: () -> Unit) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            NavItem(Icons.Rounded.GridView, "Library", !menuOpen && tab == Tab.LIBRARY) { onTab(Tab.LIBRARY) }
            EqNavItem(!menuOpen && tab == Tab.EQ) { onTab(Tab.EQ) }
            NavItem(Icons.Rounded.Search, "Search", !menuOpen && tab == Tab.SEARCH) { onTab(Tab.SEARCH) }
            NavItem(Icons.Rounded.Menu, "Menu", menuOpen, onMenu)
        }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.NavItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .weight(1f)
            .height(48.dp)
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(50))
            .background(if (selected) Color.White.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = label, tint = if (selected) Color.White else Color.White.copy(alpha = 0.75f), modifier = Modifier.size(28.dp))
    }
}


/** Equalizer tab button: tap opens the tab, long press gives quick on/off switches. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun androidx.compose.foundation.layout.RowScope.EqNavItem(selected: Boolean, onClick: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val eq by com.powersound.player.audio.AudioEngine.settings.collectAsState()
    val fx by com.powersound.player.audio.AudioEngine.fx.collectAsState()
    Box(
        Modifier
            .weight(1f)
            .height(48.dp)
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(50))
            .background(if (selected) Color.White.copy(alpha = 0.12f) else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = { menu = true }),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Rounded.Equalizer, contentDescription = "Equalizer", tint = if (selected) Color.White else Color.White.copy(alpha = 0.75f), modifier = Modifier.size(28.dp))
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text((if (eq.enabled) "✓  " else "     ") + "Equalizer") },
                onClick = { com.powersound.player.audio.AudioEngine.update { it.copy(enabled = !it.enabled) } }
            )
            DropdownMenuItem(
                text = { Text((if (eq.toneEnabled) "✓  " else "     ") + "Bass & Treble") },
                onClick = { com.powersound.player.audio.AudioEngine.update { it.copy(toneEnabled = !it.toneEnabled) } }
            )
            DropdownMenuItem(
                text = { Text((if (eq.limiter) "✓  " else "     ") + "Limiter") },
                onClick = { com.powersound.player.audio.AudioEngine.update { it.copy(limiter = !it.limiter) } }
            )
            DropdownMenuItem(
                text = { Text((if (fx.reverbEnabled) "✓  " else "     ") + "Reverb") },
                onClick = { com.powersound.player.audio.AudioEngine.updateFx { it.copy(reverbEnabled = !it.reverbEnabled) } }
            )
            DropdownMenuItem(
                text = { Text((if (fx.tempoEnabled) "✓  " else "     ") + "Tempo") },
                onClick = { com.powersound.player.audio.AudioEngine.updateFx { it.copy(tempoEnabled = !it.tempoEnabled) } }
            )
        }
    }
}
