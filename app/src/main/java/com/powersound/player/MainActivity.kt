package com.powersound.player

import android.Manifest
import android.app.Activity
import android.app.RecoverableSecurityException
import android.provider.MediaStore
import androidx.activity.result.IntentSenderRequest
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import com.powersound.player.ui.AddToPlaylistDialog
import com.powersound.player.ui.AlbumArtDialog
import com.powersound.player.ui.AudioInfoDialog
import com.powersound.player.ui.BookmarksScreen
import com.powersound.player.ui.ChoiceDialog
import com.powersound.player.ui.HintBubble
import com.powersound.player.ui.HintStore
import com.powersound.player.ui.InfoTagsDialog
import com.powersound.player.ui.LyricsSheet
import com.powersound.player.ui.SongMenuDialog
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.powersound.player.data.Category
import com.powersound.player.data.Kind
import com.powersound.player.data.MusicRepository
import com.powersound.player.data.Song
import com.powersound.player.data.Stats
import com.powersound.player.ui.AppBackground
import com.powersound.player.ui.ArtHeader
import com.powersound.player.ui.EqualizerScreen
import com.powersound.player.ui.FloatingBottomBar
import com.powersound.player.ui.GroupGridScreen
import com.powersound.player.ui.LibraryHome
import com.powersound.player.ui.MenuSheet
import com.powersound.player.ui.NowPlayingScreen
import com.powersound.player.ui.PageHeader
import com.powersound.player.ui.PowerSoundTheme
import com.powersound.player.ui.QueueSheet
import com.powersound.player.ui.SearchScreen
import com.powersound.player.ui.SleepTimerDialog
import com.powersound.player.ui.SongListScreen
import com.powersound.player.ui.SettingsScreen
import com.powersound.player.ui.SettingsPage
import com.powersound.player.ui.ChangelogDialog
import com.powersound.player.ui.HelpDialog
import com.powersound.player.ui.StreamsScreen
import com.powersound.player.ui.SaveNameDialog
import com.powersound.player.ui.TreeScreen
import com.powersound.player.ui.UpdateDialog
import com.powersound.player.ui.color
import com.powersound.player.ui.icon
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sign
import kotlin.coroutines.cancellation.CancellationException

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        com.powersound.player.data.SettingsStore.init(this)
        com.powersound.player.data.CrashLog.install(this)
        setContent {
            val appSettings by com.powersound.player.data.SettingsStore.settings.collectAsStateWithLifecycle()
            PowerSoundTheme(accentIndex = appSettings.accent) {
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
    val stats by vm.stats.collectAsStateWithLifecycle()
    val update by vm.update.collectAsStateWithLifecycle()

    var showNowPlaying by rememberSaveable { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }
    var songMenu by remember { mutableStateOf<Song?>(null) }
    var songMenuInfo by remember { mutableStateOf<String?>(null) }
    var lyricsFor by remember { mutableStateOf<Song?>(null) }
    var tagsFor by remember { mutableStateOf<Song?>(null) }
    var artFor by remember { mutableStateOf<Long?>(null) }
    var showAudioInfo by remember { mutableStateOf(false) }
    var showRepeatMenu by remember { mutableStateOf(false) }
    var showShuffleMenu by remember { mutableStateOf(false) }
    var playlistFor by remember { mutableStateOf<List<Long>?>(null) }
    var confirmDelete by remember { mutableStateOf<Song?>(null) }
    var pendingDeleteId by remember { mutableStateOf<Long?>(null) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var settingsPage by remember { mutableStateOf<SettingsPage?>(null) }
    var showChangelog by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    var backupMessage by remember { mutableStateOf<String?>(null) }
    val appSettings by com.powersound.player.data.SettingsStore.settings.collectAsStateWithLifecycle()

    // Settings → Export / Import: save or restore all settings and data as a file the user picks
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            backupMessage = runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(com.powersound.player.data.DataBackup.export(context).toByteArray()) }
                "Saved. Keep this file to restore your EQ, presets, playlists and settings later."
            }.getOrElse { "Couldn't save the file: ${it.message}" }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val text = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }.getOrNull()
            backupMessage = if (text != null && com.powersound.player.data.DataBackup.import(context, text)) "RESTART" else "This file is not a PowerSound backup."
        }
    }

    // Settings → Look and Feel: keep the screen on while the player is open
    val hostView = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(showNowPlaying, appSettings.keepScreenOn) {
        hostView.keepScreenOn = showNowPlaying && appSettings.keepScreenOn
        onDispose { hostView.keepScreenOn = false }
    }

    // Deleting a music file needs the user's OK from Android (Android 11+ shows its own dialog).
    val deleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val id = pendingDeleteId
        if (result.resultCode == Activity.RESULT_OK && id != null) vm.onSongDeleted(id)
        pendingDeleteId = null
    }
    fun deleteSong(song: Song) {
        pendingDeleteId = song.id
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val pi = MediaStore.createDeleteRequest(context.contentResolver, listOf(song.uri))
                deleteLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
            } else {
                context.contentResolver.delete(song.uri, null, null)
                vm.onSongDeleted(song.id)
            }
        } catch (e: SecurityException) {
            val rse = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) e as? RecoverableSecurityException else null
            if (rse != null) {
                deleteLauncher.launch(IntentSenderRequest.Builder(rse.userAction.actionIntent.intentSender).build())
            }
        } catch (_: Exception) {
        }
    }
    var barHeightPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val bottomInset: Dp = with(density) { barHeightPx.toDp() } + 4.dp

    val currentId = player.nowPlaying?.songId
    val tab = vm.tab

    // Back from the EQ / Search tabs goes to the Library tab.
    BackHandler(enabled = tab != Tab.LIBRARY && !showNowPlaying && !settingsOpen) { vm.tab = Tab.LIBRARY }

    val tabHolder = rememberSaveableStateHolder()
    val openSongMenu: (Song) -> Unit = { s -> songMenuInfo = null; songMenu = s }

    Box(Modifier.fillMaxSize()) {
        AppBackground(currentId)

        // Each tab keeps its scroll positions / search text while you switch tabs.
        tabHolder.SaveableStateProvider(tab.name) {
        when (tab) {
            Tab.LIBRARY -> LibraryHost(
                vm = vm,
                library = library,
                player = player,
                stats = stats,
                bottomInset = bottomInset,
                enabled = !showNowPlaying && !settingsOpen,
                onSongMenu = openSongMenu,
                onOpenQueue = { showQueue = true },
                onOpenSettings = { settingsPage = SettingsPage.LIBRARY; settingsOpen = true }
            )
            Tab.EQ -> EqualizerScreen(bottomInset)
            Tab.SEARCH -> SearchScreen(
                library = library,
                currentId = currentId,
                bottomInset = bottomInset,
                onPlay = { list, i -> vm.play(list, i) },
                onShuffle = vm::shuffleAll,
                onOpenGroup = { g, back -> vm.openGroup(g, back) },
                onSongMenu = openSongMenu
            )
        }
        }

        FloatingBottomBar(
            state = player,
            tab = tab,
            menuOpen = showMenu,
            onTab = { t ->
                if (t == tab && t == Tab.LIBRARY) {
                    // tapping Library again goes back to the top of the library
                    while (vm.pop()) { /* pop to root */ }
                }
                vm.tab = t
            },
            onMenu = { showMenu = true },
            onOpenPlayer = { showNowPlaying = true },
            onTogglePlay = vm::togglePlay,
            onNext = vm::next,
            onPrevious = vm::previous,
            position = vm.position,
            onOpenSource = { vm.openSourceList() },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .onSizeChanged { barHeightPx = it.height }
        )

        if (tab == Tab.LIBRARY && vm.stack.size > 1 && !showNowPlaying && HintStore.shouldShow("library")) {
            HintBubble(
                title = "Library",
                text = "Swipe the page in any direction to go back. Pinch to zoom the list. " +
                    "⋮ → list options for sorting and zoom. Long press a song for its menu.",
                onClose = { HintStore.dismiss("library") },
                modifier = Modifier
                    .align(Alignment.Center)
            )
        }
        if (player.nowPlaying != null && !showNowPlaying && HintStore.shouldShow("mini")) {
            HintBubble(
                title = "Mini player",
                text = "Tap to open the player. Swipe ◀ ▶ to change tracks. Long press for the track's list.",
                onClose = { HintStore.dismiss("mini") },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = bottomInset + 8.dp)
            )
        }

        AnimatedVisibility(
            visible = showNowPlaying && player.nowPlaying != null,
            enter = slideInVertically { it / 3 } + scaleIn(initialScale = 0.9f) + fadeIn(),
            exit = slideOutVertically { it / 2 } + fadeOut()
        ) {
            val np = player.nowPlaying
            val current = vm.song(np?.songId)
            NowPlayingScreen(
                state = player,
                position = vm.position,
                song = current,
                rating = stats.rating(np?.songId),
                playCount = np?.songId?.let { stats.plays[it] } ?: 0,
                vizMode = vm.vizMode,
                vizPreset = vm.vizPreset,
                onVizMode = vm::setVisualization,
                onVizPreset = vm::setVisualizationPreset,
                onClose = { showNowPlaying = false },
                onSourceList = { vm.openSourceList(); showNowPlaying = false },
                onTogglePlay = vm::togglePlay,
                onNext = vm::next,
                onPrevious = vm::previous,
                onSeek = vm::seekTo,
                onSeekBy = vm::seekBy,
                onPrevCategory = { vm.changeCategory(-1) },
                onNextCategory = { vm.changeCategory(+1) },
                onShuffle = vm::toggleShuffle,
                onShuffleMenu = { showShuffleMenu = true },
                onRepeat = vm::cycleRepeat,
                onRepeatMenu = { showRepeatMenu = true },
                onLike = { vm.like(np?.songId) },
                onDislike = { vm.dislike(np?.songId) },
                onQueue = { showQueue = true },
                onMore = {
                    current?.let { s ->
                        songMenuInfo = player.audioInfo
                        songMenu = s
                    }
                },
                onSleep = { showSleep = true },
                onLyrics = { lyricsFor = current },
                onAudioInfo = { showAudioInfo = true }
            )
        }
    }

    // ---- settings (full screen) ----
    if (settingsOpen) {
        BackHandler { if (settingsPage != null) settingsPage = null else settingsOpen = false }
        SettingsScreen(
            page = settingsPage,
            allFolders = vm.allFolders,
            onPage = { settingsPage = it },
            onClose = { settingsOpen = false; settingsPage = null },
            onRescan = { vm.loadLibrary(force = true) },
            onOpenEqualizer = { settingsOpen = false; settingsPage = null; showNowPlaying = false; vm.tab = Tab.EQ },
            onCheckUpdate = { vm.checkForUpdate(manual = true) },
            onChangelog = { showChangelog = true },
            onExport = { exportLauncher.launch("PowerSound-backup.json") },
            onImport = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }
        )
    }
    if (showChangelog) ChangelogDialog { showChangelog = false }
    if (showHelp) HelpDialog { showHelp = false }
    backupMessage?.let { msg ->
        if (msg == "RESTART") {
            AlertDialog(
                onDismissRequest = {},
                containerColor = Color(0xFF1B1B22),
                title = { Text("Restored", color = Color.White) },
                text = { Text("Your settings and data were restored. PowerSound will restart now.", color = Color.White.copy(alpha = 0.8f)) },
                confirmButton = {
                    TextButton(onClick = {
                        val restart = context.packageManager.getLaunchIntentForPackage(context.packageName)
                            ?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        if (restart != null) context.startActivity(restart)
                        android.os.Process.killProcess(android.os.Process.myPid())
                    }) { Text("Restart") }
                }
            )
        } else {
            AlertDialog(
                onDismissRequest = { backupMessage = null },
                containerColor = Color(0xFF1B1B22),
                title = { Text("Settings/Data", color = Color.White) },
                text = { Text(msg, color = Color.White.copy(alpha = 0.8f)) },
                confirmButton = { TextButton(onClick = { backupMessage = null }) { Text("OK") } }
            )
        }
    }

    // ---- sheets & dialogs ----
    if (showMenu) {
        val left = player.sleepEndsAt?.let { ((it - System.currentTimeMillis()) / 60_000L + 1).coerceAtLeast(1) }
        MenuSheet(
            onDismiss = { showMenu = false },
            onRescan = { vm.loadLibrary(force = true) },
            onSleep = { showSleep = true },
            onEqualizer = { showNowPlaying = false; vm.tab = Tab.EQ },
            onCheckUpdate = { vm.checkForUpdate(manual = true) },
            onSettings = { p -> settingsPage = p; settingsOpen = true },
            onChangelog = { showChangelog = true },
            onHelp = { showHelp = true },
            sleepLabel = left?.let { "$it min left" }
        )
    }
    if (showQueue) {
        QueueSheet(
            queue = player.queue,
            currentIndex = player.currentIndex,
            onPlayIndex = { vm.playQueueIndex(it) },
            onRemove = { vm.removeFromQueue(it) },
            onDismiss = { showQueue = false }
        )
    }
    if (showSleep) {
        SleepTimerDialog(
            remainingMinutes = player.sleepEndsAt?.let { ((it - System.currentTimeMillis()) / 60_000L + 1).toInt().coerceAtLeast(1) },
            onSet = { m, finish -> vm.setSleepTimer(m, finish) },
            onCancelTimer = { vm.cancelSleep() },
            onDismiss = { showSleep = false }
        )
    }
    if (showRepeatMenu) {
        ChoiceDialog(
            title = "When the list ends",
            options = RepeatMode.values().map { it.label },
            selected = player.repeat.ordinal,
            onPick = { vm.setRepeatMode(RepeatMode.values()[it]) },
            onDismiss = { showRepeatMenu = false }
        )
    }
    if (showShuffleMenu) {
        ChoiceDialog(
            title = "Shuffle",
            options = ShuffleMode.values().map { it.label },
            selected = player.shuffleMode.ordinal,
            onPick = { vm.setShuffleMode(ShuffleMode.values()[it]) },
            onDismiss = { showShuffleMenu = false }
        )
    }
    lyricsFor?.let { s ->
        LyricsSheet(song = s, position = vm.position, onSeek = vm::seekTo, onDismiss = { lyricsFor = null })
    }
    tagsFor?.let { s ->
        InfoTagsDialog(song = s, audioInfo = if (s.id == currentId) player.audioInfo else "", onDismiss = { tagsFor = null })
    }
    artFor?.let { id -> AlbumArtDialog(songId = id, onDismiss = { artFor = null }) }
    if (showAudioInfo) {
        AudioInfoDialog(
            song = vm.song(currentId),
            audioInfo = player.audioInfo,
            dspSummary = dspSummary(),
            onDismiss = { showAudioInfo = false }
        )
    }
    playlistFor?.let { ids -> AddToPlaylistDialog(songIds = ids, onDismiss = { playlistFor = null }) }
    confirmDelete?.let { s ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            containerColor = Color(0xFF1B1B22),
            title = { Text("Delete file?", color = Color.White) },
            text = { Text("\"${s.title}\" will be deleted from your phone.", color = Color.White.copy(alpha = 0.8f)) },
            confirmButton = { TextButton(onClick = { confirmDelete = null; deleteSong(s) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } }
        )
    }
    UpdateDialog(
        state = update,
        onDownload = vm::downloadUpdate,
        onInstall = vm::installUpdate,
        onDismiss = vm::dismissUpdate
    )
    songMenu?.let { s ->
        SongMenuDialog(
            song = s,
            rating = stats.rating(s.id),
            onDismiss = { songMenu = null },
            onLike = { vm.like(s.id) },
            onDislike = { vm.dislike(s.id) },
            onDelete = { if (appSettings.confirmDelete) confirmDelete = s else deleteSong(s) },
            onPlaylist = { playlistFor = listOf(s.id) },
            onBookmark = {
                if (s.id == currentId) vm.addBookmark(s.id) else com.powersound.player.data.LibraryStores.addBookmark(s.id, 0L)
            },
            onAlbumArt = { artFor = s.id },
            onInfo = { tagsFor = s },
            onLyrics = { lyricsFor = s },
            onArtist = { showNowPlaying = false; vm.goToArtist(s) },
            onAlbum = { showNowPlaying = false; vm.goToAlbum(s) },
            onFolder = { showNowPlaying = false; vm.goToFolder(s) },
            onGenre = { showNowPlaying = false; vm.goToGenre(s) },
            onPlayNext = { vm.playNext(listOf(s)) },
            onAddToQueue = { vm.addToQueue(listOf(s)) }
        )
    }
}

/**
 * The Library tab: a stack of pages. Going back works like the reference video —
 * in any direction:
 *  • swipe the page left or right anywhere,
 *  • pull it down when the list is at the top, or up when it is at the end,
 *  • or use the system back gesture / back button.
 * The page shrinks like a card following the finger and the previous page shows underneath.
 */
@Composable
private fun LibraryHost(
    vm: PlayerViewModel,
    library: LibraryState,
    player: PlayerUiState,
    stats: Stats,
    bottomInset: Dp,
    enabled: Boolean,
    onSongMenu: (Song) -> Unit,
    onOpenQueue: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val holder = rememberSaveableStateHolder()
    val scope = rememberCoroutineScope()
    val stack = vm.stack
    val canGoBack = enabled && stack.size > 1

    // 0 = page fully shown, 1 = page gone (previous page fully shown).
    val swipe = remember { Animatable(0f) }
    var dirX by remember { mutableFloatStateOf(1f) }
    var dirY by remember { mutableFloatStateOf(0f) }
    var pageWidth by remember { mutableFloatStateOf(1f) }
    var pageHeight by remember { mutableFloatStateOf(1f) }

    PredictiveBackHandler(enabled = canGoBack) { progress ->
        try {
            dirX = 1f
            dirY = 0f
            progress.collect { swipe.snapTo(it.progress) }
            swipe.animateTo(1f, tween(170))
            vm.pop()
            swipe.snapTo(0f)
        } catch (e: CancellationException) {
            scope.launch { swipe.animateTo(0f, tween(200)) }
            throw e
        }
    }

    fun finish(commit: Boolean) {
        scope.launch {
            if (commit) {
                swipe.animateTo(1f, tween(180))
                vm.pop()
                swipe.snapTo(0f)
            } else {
                swipe.animateTo(0f, tween(220))
            }
        }
    }

    // Vertical: whatever the list can't scroll any more (top or end) moves the page instead.
    val pullConnection = remember(canGoBack) {
        object : NestedScrollConnection {
            var pull = 0f

            fun show() {
                dirX = 0f
                dirY = sign(pull)
                val p = (abs(pull) / (pageHeight * 0.35f)).coerceIn(0f, 1f)
                scope.launch { swipe.snapTo(p) }
            }

            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (pull == 0f || source != NestedScrollSource.Drag) return Offset.Zero
                val next = pull + available.y
                return when {
                    next * pull <= 0f -> {
                        val used = -pull
                        pull = 0f
                        show()
                        Offset(0f, used)
                    }
                    abs(next) < abs(pull) -> {
                        pull = next
                        show()
                        Offset(0f, available.y)
                    }
                    else -> Offset.Zero
                }
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (!canGoBack || source != NestedScrollSource.Drag || available.y == 0f) return Offset.Zero
                pull += available.y
                show()
                return Offset(0f, available.y)
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (pull == 0f) return Velocity.Zero
                val flungSameWay = abs(available.y) > 2500f && sign(available.y) == sign(pull)
                val commit = swipe.value > 0.35f || flungSameWay
                pull = 0f
                finish(commit)
                return available
            }
        }
    }

    val p = swipe.value
    val bg = MaterialTheme.colorScheme.background
    // Only the top page is shown; while going back the page underneath is shown too.
    // Each page is keyed by its id so it keeps its scroll position when it moves.
    val visible = if (p > 0f && stack.size > 1) stack.takeLast(2) else listOf(stack.last())
    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged {
                pageWidth = it.width.toFloat().coerceAtLeast(1f)
                pageHeight = it.height.toFloat().coerceAtLeast(1f)
            }
    ) {
        visible.forEachIndexed { i, screen ->
            key(screen.id) {
                val isTop = i == visible.lastIndex
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val q = swipe.value
                            if (isTop) {
                                // the page you drag shrinks and fades out completely
                                scaleX = 1f - 0.16f * q
                                scaleY = 1f - 0.16f * q
                                translationX = dirX * 56.dp.toPx() * q
                                translationY = dirY * 90.dp.toPx() * q
                                alpha = (1f - q).coerceIn(0f, 1f)
                                shape = RoundedCornerShape(34.dp.toPx() * q)
                                clip = q > 0f
                            } else {
                                // the page behind starts faint and slightly small, then comes to full strength
                                scaleX = 0.94f + 0.06f * q
                                scaleY = 0.94f + 0.06f * q
                                alpha = (0.15f + 0.85f * q).coerceIn(0f, 1f)
                            }
                        }
                        .then(if (isTop && p > 0f) Modifier.background(bg) else Modifier)
                        .then(
                            if (isTop && canGoBack) Modifier
                                .nestedScroll(pullConnection)
                                .pointerInput(screen.id) {
                                    // Horizontal: swipe left or right anywhere on the page.
                                    var dx = 0f
                                    detectHorizontalDragGestures(
                                        onDragStart = { dx = 0f },
                                        onHorizontalDrag = { change, d ->
                                            change.consume()
                                            dx += d
                                            dirX = sign(dx)
                                            dirY = 0f
                                            val prog = (abs(dx) / (pageWidth * 0.45f)).coerceIn(0f, 1f)
                                            scope.launch { swipe.snapTo(prog) }
                                        },
                                        onDragEnd = { finish(swipe.value > 0.3f) },
                                        onDragCancel = { finish(false) }
                                    )
                                }
                            else Modifier
                        )
                ) {
                    holder.SaveableStateProvider(screen.id) {
                        ScreenContent(screen, vm, library, player, stats, bottomInset, onSongMenu, onOpenQueue, onOpenSettings)
                    }
                }
            }
        }
    }
}

@Composable
private fun ScreenContent(
    screen: Screen,
    vm: PlayerViewModel,
    library: LibraryState,
    player: PlayerUiState,
    stats: Stats,
    bottomInset: Dp,
    onSongMenu: (Song) -> Unit,
    onOpenQueue: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val currentId = player.nowPlaying?.songId
    val appSettings by com.powersound.player.data.SettingsStore.settings.collectAsStateWithLifecycle()
    val streams by com.powersound.player.data.LibraryStores.streams.collectAsStateWithLifecycle()
    val addQueue: (List<Song>) -> Unit = { vm.addToQueue(it) }
    val playlists by com.powersound.player.data.LibraryStores.playlists.collectAsStateWithLifecycle()
    val bookmarks by com.powersound.player.data.LibraryStores.bookmarks.collectAsStateWithLifecycle()
    var nameDialog by remember { mutableStateOf<Pair<String, (String) -> Unit>?>(null) }
    var playlistSongMenu by remember { mutableStateOf<Pair<Long, Song>?>(null) }

    when (screen) {
        Screen.Home -> {
            val queueEmpty = player.queue.isEmpty()
            val empty = remember(library.songs, stats, queueEmpty, playlists, bookmarks, streams, appSettings.longTrackMin) {
                Category.values().filter { cat ->
                    when {
                        cat.kind == Kind.QUEUE -> queueEmpty
                        cat == Category.PLAYLISTS -> playlists.isEmpty()
                        cat == Category.BOOKMARKS -> bookmarks.isEmpty()
                        cat == Category.STREAMS -> streams.isEmpty()
                        cat.kind == Kind.SONGS -> vm.songsFor(cat, stats).isEmpty()
                        else -> library.songs.isEmpty()
                    }
                }.toSet()
            }
            LibraryHome(
                loading = library.loading,
                isEmpty = { cat -> cat in empty },
                bottomInset = bottomInset,
                onOpen = { cat ->
                    if (cat.kind == Kind.QUEUE) onOpenQueue() else vm.push(Screen.Cat(cat))
                },
                onRescan = { vm.loadLibrary(force = true) },
                hidden = appSettings.hiddenCategories,
                onSettings = onOpenSettings
            )
        }

        is Screen.Cat -> {
            val cat = screen.cat
            val key = "cat:${cat.name}"
            when (cat.kind) {
                Kind.GROUPS -> {
                    val groups = if (cat == Category.PLAYLISTS) remember(playlists, library.songs) { vm.playlistGroups() } else library.groupsFor(cat)
                    GroupGridScreen(
                        title = cat.label,
                        icon = cat.icon(),
                        iconColor = cat.color(),
                        backLabel = "Library",
                        groups = groups,
                        bottomInset = bottomInset,
                        onBack = { vm.pop() },
                        onOpen = { g -> vm.push(Screen.Detail(g, cat.label)) },
                        onPlay = { vm.play(it, 0, sourceKey = key) },
                        onShuffle = { vm.shuffleAll(it, key) },
                        onAddToQueue = addQueue,
                        extraMenu = if (cat == Category.PLAYLISTS) listOf(
                            "New playlist" to { nameDialog = "New playlist" to { n: String -> com.powersound.player.data.LibraryStores.createPlaylist(n); Unit } }
                        ) else emptyList()
                    )
                }
                Kind.TREE -> {
                    val (subs, direct) = remember(library.songs) { MusicRepository.treeChildren(library.songs, "") }
                    TreeScreen(
                        title = cat.label,
                        backLabel = "Library",
                        folders = subs,
                        songs = direct,
                        currentId = currentId,
                        bottomInset = bottomInset,
                        onBack = { vm.pop() },
                        onOpenFolder = { g -> vm.push(Screen.Tree(g.key.removePrefix("tree:"), cat.label)) },
                        onPlay = { list, i -> vm.play(list, i, sourceKey = key) },
                        onShuffle = { vm.shuffleAll(it, key) },
                        onSongMenu = onSongMenu
                    )
                }
                Kind.STREAMS -> StreamsScreen(
                    streams = streams,
                    playingId = player.queue.getOrNull(player.currentIndex)?.mediaId,
                    bottomInset = bottomInset,
                    onBack = { vm.pop() },
                    onPlay = { vm.playStream(it) }
                )
                Kind.BOOKMARKS -> BookmarksScreen(
                    bookmarks = bookmarks,
                    songOf = { vm.song(it) },
                    currentId = currentId,
                    bottomInset = bottomInset,
                    onBack = { vm.pop() },
                    onPlay = { vm.playBookmark(it) },
                    onRemove = { com.powersound.player.data.LibraryStores.removeBookmark(it) }
                )
                else -> {
                    val songs = remember(library.songs, stats, cat) { vm.songsFor(cat, stats) }
                    SongListScreen(
                        songs = songs,
                        currentId = currentId,
                        bottomInset = bottomInset,
                        showIndex = cat == Category.ALL_SONGS,
                        emptyText = "Nothing here yet",
                        onPlay = { list, i -> vm.play(list, i, sourceKey = key) },
                        onShuffle = { vm.shuffleAll(it, key) },
                        onSongMenu = onSongMenu,
                        onAddToQueue = addQueue,
                        rowExtra = if (cat == Category.MOST_PLAYED) { s -> "${stats.plays[s.id] ?: 0}×" } else null
                    ) { pills ->
                        PageHeader("Library", cat.label, cat.icon(), cat.color(), { vm.pop() }) { pills() }
                    }
                }
            }
        }

        is Screen.Detail -> {
            // always show the current version (after a rescan or a playlist edit)
            val group = remember(screen.group.key, library, playlists) { vm.findGroup(screen.group.key) } ?: screen.group
            val isPlaylist = group.key.startsWith("playlist:")
            val playlistId = if (isPlaylist) group.key.removePrefix("playlist:").toLongOrNull() else null
            SongListScreen(
                songs = group.songs,
                currentId = currentId,
                bottomInset = bottomInset,
                showIndex = false,
                emptyText = if (isPlaylist) "This playlist is empty.\nAdd songs from the ⋮ menu of any song." else "No songs",
                onPlay = { list, i -> vm.play(list, i, sourceKey = group.key) },
                onShuffle = { vm.shuffleAll(it, group.key) },
                onSongMenu = { s -> if (playlistId != null) playlistSongMenu = playlistId to s else onSongMenu(s) },
                onAddToQueue = addQueue,
                extraMenu = if (playlistId != null) listOf(
                    "Rename playlist" to {
                        nameDialog = "Rename playlist" to { n: String -> com.powersound.player.data.LibraryStores.renamePlaylist(playlistId, n) }
                    },
                    "Delete playlist" to {
                        com.powersound.player.data.LibraryStores.deletePlaylist(playlistId)
                        vm.pop()
                        Unit
                    }
                ) else listOf("Add all to a playlist" to { nameDialog = "New playlist" to { n: String -> com.powersound.player.data.LibraryStores.createPlaylist(n, group.songs.map { it.id }); Unit } })
            ) { pills ->
                ArtHeader(group, screen.backLabel, { vm.pop() }) { pills() }
            }
        }

        is Screen.Tree -> {
            val (subs, direct) = remember(library.songs, screen.path) {
                MusicRepository.treeChildren(library.songs, screen.path)
            }
            TreeScreen(
                title = screen.path.substringAfterLast('/'),
                backLabel = screen.backLabel,
                folders = subs,
                songs = direct,
                currentId = currentId,
                bottomInset = bottomInset,
                onBack = { vm.pop() },
                onOpenFolder = { g ->
                    vm.push(Screen.Tree(g.key.removePrefix("tree:"), screen.path.substringAfterLast('/')))
                },
                onPlay = { list, i -> vm.play(list, i, sourceKey = "folder:${screen.path}") },
                onShuffle = { vm.shuffleAll(it, "folder:${screen.path}") },
                onSongMenu = onSongMenu
            )
        }
    }

    nameDialog?.let { (title, save) ->
        SaveNameDialog(title, onSave = save, onDismiss = { nameDialog = null })
    }
    playlistSongMenu?.let { (pid, s) ->
        ChoiceDialog(
            title = s.title,
            options = listOf("Remove from playlist", "Song menu…"),
            selected = -1,
            onPick = { i ->
                if (i == 0) com.powersound.player.data.LibraryStores.removeFromPlaylist(pid, s.id) else onSongMenu(s)
            },
            onDismiss = { playlistSongMenu = null }
        )
    }
}

private fun dspSummary(): String {
    val eq = com.powersound.player.audio.AudioEngine.settings.value
    val fx = com.powersound.player.audio.AudioEngine.fx.value
    val parts = ArrayList<String>()
    if (eq.enabled) parts.add("EQ ${eq.bands.size} bands (${eq.presetName})")
    if (eq.enabled && eq.toneEnabled && (eq.bassDb != 0.0 || eq.trebleDb != 0.0)) parts.add("Bass/Treble")
    if (eq.enabled && eq.limiter) parts.add("Limiter")
    if (fx.reverbEnabled) parts.add("Reverb (${fx.reverbPreset})")
    if (fx.tempoEnabled && fx.tempo != 1.0) parts.add("Tempo %.2fx".format(fx.tempo))
    if (fx.mono) parts.add("Mono")
    if (fx.stereoExpand > 0.0) parts.add("Stereo expand")
    if (fx.balance != 0.0) parts.add("Balance")
    if (fx.volume < 1.0) parts.add("Volume ${(fx.volume * 100).toInt()}%")
    return if (parts.isEmpty()) "Off — bit-exact pass-through" else parts.joinToString(" • ") + " • 64-bit float DSP with dither"
}

@Composable
private fun PermissionScreen(onRequest: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
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
            color = Color.White
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onRequest) { Text("Allow access") }
    }
}
