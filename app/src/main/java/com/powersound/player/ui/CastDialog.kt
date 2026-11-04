package com.powersound.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material.icons.rounded.VolumeDown
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastMediaControlIntent
import com.powersound.player.cast.CAST_RECEIVER_ID
import com.powersound.player.cast.CastControl
import kotlinx.coroutines.delay

/** One Chromecast found on the Wi-Fi. */
private data class CastTarget(val id: String, val name: String, val about: String, val connecting: Boolean, val selected: Boolean)

/**
 * Player screen → Cast button (or Menu → Cast). Lists the Chromecasts on the same Wi-Fi; tapping one moves the
 * music there. While casting it shows the device, its volume, and "Stop casting".
 */
@Composable
fun CastDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val primary = MaterialTheme.colorScheme.primary
    val castContext = remember { CastControl.context(context) }
    val device by CastControl.device.collectAsState()

    if (castContext == null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = Color(0xFF1B1B22),
            title = { Text("Cast", color = Color.White) },
            text = {
                Text(
                    "Casting needs Google Play services, and this phone doesn't have them (or they need an update).",
                    color = Color.White.copy(alpha = 0.8f)
                )
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } }
        )
        return
    }

    val router = remember { MediaRouter.getInstance(context) }
    val selector = remember {
        MediaRouteSelector.Builder()
            .addControlCategory(CastMediaControlIntent.categoryForCast(CAST_RECEIVER_ID))
            .build()
    }
    var targets by remember { mutableStateOf(emptyList<CastTarget>()) }
    var searching by remember { mutableStateOf(true) }

    DisposableEffect(router) {
        fun refresh() {
            targets = router.routes
                .filter { !it.isDefault && it.isEnabled && it.matchesSelector(selector) }
                .map {
                    CastTarget(
                        id = it.id,
                        name = it.name,
                        about = it.description?.toString() ?: "",
                        connecting = it.connectionState == MediaRouter.RouteInfo.CONNECTION_STATE_CONNECTING,
                        selected = it.isSelected
                    )
                }
        }
        val callback = object : MediaRouter.Callback() {
            override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
            override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
            override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
            override fun onRouteSelected(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
            override fun onRouteUnselected(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
        }
        router.addCallback(
            selector, callback,
            MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN or MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY
        )
        refresh()
        onDispose { router.removeCallback(callback) }
    }
    LaunchedEffect(Unit) {
        delay(8000)
        searching = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1B1B22),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (device != null) Icons.Rounded.CastConnected else Icons.Rounded.Cast, contentDescription = null, tint = primary)
                Spacer(Modifier.width(10.dp))
                Text(if (device != null) "Playing on $device" else "Cast to a device", color = Color.White, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
                if (device != null) {
                    var volume by remember { mutableFloatStateOf(CastControl.volume(context)) }
                    Text("Volume on the device", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.VolumeDown, contentDescription = null, tint = Color.White.copy(alpha = 0.7f))
                        Slider(
                            value = volume,
                            onValueChange = { volume = it; CastControl.setVolume(context, it) },
                            modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
                            colors = SliderDefaults.colors(thumbColor = primary, activeTrackColor = primary)
                        )
                        Icon(Icons.Rounded.VolumeUp, contentDescription = null, tint = Color.White.copy(alpha = 0.7f))
                    }
                    Text(
                        "The phone's volume buttons work too. The Chromecast plays the original file, " +
                            "so the equalizer and effects are not heard there.",
                        color = Color.White.copy(alpha = 0.55f),
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.height(12.dp))
                }
                val others = targets.filter { !it.selected }
                if (device == null || others.isNotEmpty()) {
                    Text(
                        if (device == null) "Chromecasts on your Wi-Fi" else "Move to another device",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 13.sp
                    )
                    Spacer(Modifier.height(6.dp))
                }
                others.forEach { t ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White.copy(alpha = 0.06f))
                            .clickable(enabled = !t.connecting) {
                                router.routes.firstOrNull { it.id == t.id }?.select()
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Cast, contentDescription = null, tint = Color.White)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(t.name, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (t.connecting) Text("Connecting…", color = primary, fontSize = 12.sp)
                            else if (t.about.isNotBlank()) Text(t.about, color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (t.connecting) CircularProgressIndicator(Modifier.size(18.dp), color = primary, strokeWidth = 2.dp)
                    }
                }
                if (device == null && others.isEmpty()) {
                    Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (searching) CircularProgressIndicator(Modifier.size(18.dp), color = primary, strokeWidth = 2.dp)
                        Text(
                            if (searching) "Looking for Chromecasts…"
                            else "No Chromecast found. Check that the phone and the Chromecast are on the same Wi-Fi.",
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 13.sp
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (device != null) TextButton(onClick = { CastControl.stop(context); onDismiss() }) { Text("Stop casting") }
            else TextButton(onClick = onDismiss) { Text("Close") }
        },
        dismissButton = {
            if (device != null) TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}
