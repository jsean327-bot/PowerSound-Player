package com.powersound.player

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.powersound.player.data.Detail
import com.powersound.player.ui.DetailScreen
import com.powersound.player.ui.LibraryScreen
import com.powersound.player.ui.MiniPlayer
import com.powersound.player.ui.NowPlayingScreen
import com.powersound.player.ui.PowerSoundTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PowerSoundTheme {
                PowerSoundApp()
            }
        }
    }
}

private fun audioPermission(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_AUDIO
    else Manifest.permission.READ_EXTERNAL_STORAGE

private fun permissionsToAsk(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

private fun hasAudioPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, audioPermission()) == PackageManager.PERMISSION_GRANTED

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PowerSoundApp(vm: PlayerViewModel = viewModel()) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasAudioPermission(context)) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        granted = hasAudioPermission(context)
        if (granted) vm.loadLibrary()
    }

    LaunchedEffect(Unit) {
        if (granted) vm.loadLibrary() else launcher.launch(permissionsToAsk())
    }

    if (!granted) {
        PermissionScreen { launcher.launch(permissionsToAsk()) }
        return
    }

    val library by vm.library.collectAsStateWithLifecycle()
    val player by vm.player.collectAsStateWithLifecycle()
    var detail by remember { mutableStateOf<Detail?>(null) }
    var showNowPlaying by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = detail != null && !showNowPlaying) { detail = null }
    BackHandler(enabled = showNowPlaying) { showNowPlaying = false }

    val currentId = player.nowPlaying?.songId

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    ),
                    title = {
                        val d = detail
                        Text(
                            text = d?.title ?: "PowerSound Player",
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (d == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    },
                    navigationIcon = {
                        if (detail != null) {
                            IconButton(onClick = { detail = null }) {
                                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                            }
                        }
                    },
                    actions = {
                        if (detail == null) {
                            IconButton(onClick = { vm.loadLibrary(force = true) }) {
                                Icon(Icons.Rounded.Refresh, contentDescription = "Rescan library")
                            }
                        }
                    }
                )
            },
            bottomBar = {
                MiniPlayer(
                    state = player,
                    onOpen = { showNowPlaying = true },
                    onTogglePlay = vm::togglePlay,
                    onNext = vm::next
                )
            }
        ) { padding ->
            val d = detail
            if (d != null) {
                DetailScreen(
                    detail = d,
                    currentSongId = currentId,
                    onPlay = { list, i -> vm.play(list, i) },
                    onShuffle = vm::shuffleAll,
                    modifier = Modifier.padding(padding)
                )
            } else {
                LibraryScreen(
                    library = library,
                    currentSongId = currentId,
                    onPlay = { list, i -> vm.play(list, i) },
                    onShuffle = vm::shuffleAll,
                    onOpenDetail = { detail = it },
                    modifier = Modifier.padding(padding)
                )
            }
        }

        AnimatedVisibility(
            visible = showNowPlaying && player.nowPlaying != null,
            enter = slideInVertically { it },
            exit = slideOutVertically { it }
        ) {
            NowPlayingScreen(
                state = player,
                onClose = { showNowPlaying = false },
                onTogglePlay = vm::togglePlay,
                onNext = vm::next,
                onPrevious = vm::previous,
                onSeek = vm::seekTo,
                onShuffle = vm::toggleShuffle,
                onRepeat = vm::cycleRepeat
            )
        }
    }
}

@Composable
private fun PermissionScreen(onRequest: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Rounded.LibraryMusic,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(72.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "PowerSound Player needs access to your music files to build your library.",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onRequest) { Text("Allow access") }
    }
}
