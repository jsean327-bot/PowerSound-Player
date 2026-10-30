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

    companion object {
        /** Set when the app is opened from the media notification (Settings → Notifications → Navigate to the List). */
        val openedFromNotification = kotlinx.coroutines.flow.MutableStateFlow(0L)
        /** Bumped when the app comes back to the front (Settings → Audio Focus → Resume On Reopen). */
        val reopened = kotlinx.coroutines.flow.MutableStateFlow(0L)
        const val EXTRA_FROM_NOTIFICATION = "powersound.fromNotification"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        com.powersound.player.data.SettingsStore.init(this)
        applySystemBars(com.powersound.player.data.SettingsStore.value)
        super.onCreate(savedInstanceState)
        com.powersound.player.data.CrashLog.install(this)
        handleIntent(intent)
        setContent {
            val appSettings by com.powersound.player.data.SettingsStore.settings.collectAsStateWithLifecycle()
            // Settings → Look and Feel: orientation, status bar, navigation bar
            LaunchedEffect(appSettings.orientation) {
                requestedOrientation = when (appSettings.orientation) {
                    1 -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                    2 -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
            }
            LaunchedEffect(appSettings.hideStatusBar, appSettings.androidNavNoBg) {
                applySystemBars(appSettings)
            }
            // Settings → Lock Screen → Show On Lock Screen
            LaunchedEffect(appSettings.showOnLockScreen) {
                if (android.os.Build.VERSION.SDK_INT >= 27) setShowWhenLocked(appSettings.showOnLockScreen)
            }
            // Settings → Misc → Apply High Framerate: ask for the fastest refresh rate of the screen
            LaunchedEffect(appSettings.highFramerate) {
                runCatching {
                    val attrs = window.attributes
                    if (appSettings.highFramerate) {
                        @Suppress("DEPRECATION")
                        val disp = windowManager.defaultDisplay
                        val cur = disp.mode
                        val best = disp.supportedModes
                            .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
                            .maxByOrNull { it.refreshRate }
                        if (best != null) attrs.preferredDisplayModeId = best.modeId
                    } else attrs.preferredDisplayModeId = 0
                    window.attributes = attrs
                }
            }
            PowerSoundTheme(settings = appSettings) {
                PowerSoundApp()
            }
        }
    }

    override fun onRestart() {
        super.onRestart()
        reopened.value = System.currentTimeMillis()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: android.content.Intent?) {
        if (intent?.getBooleanExtra(EXTRA_FROM_NOTIFICATION, false) == true) {
            openedFromNotification.value = System.currentTimeMillis()
            intent.removeExtra(EXTRA_FROM_NOTIFICATION)
        }
    }

    private fun applySystemBars(st: com.powersound.player.data.AppSettings) {
        // Settings → Skin → Android Navigation Bar: default = soft dark background, or none
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(if (st.androidNavNoBg) android.graphics.Color.TRANSPARENT else 0x66000000)
        )
        val controller = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        if (st.hideStatusBar) {
            controller.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(androidx.core.view.WindowInsetsCompat.Type.statusBars())
        } else {
            controller.show(androidx.core.view.WindowInsetsCompat.Type.statusBars())
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

    // Settings → Look and Feel → Start at Library (off: the app opens on the Player Screen)
    val ui = remember { AppUi() }
    val showNowPlayingState = rememberSaveable { mutableStateOf(!com.powersound.player.data.SettingsStore.value.startAtLibrary) }
    var showNowPlaying by showNowPlayingState
    var showMenu by ui::showMenu
    var showQueue by ui::showQueue
    var showSleep by ui::showSleep
    var songMenu by ui::songMenu
    var songMenuInfo by ui::songMenuInfo
    var lyricsFor by ui::lyricsFor
    var tagsFor by ui::tagsFor
    var artFor by ui::artFor
    var showAudioInfo by ui::showAudioInfo
    var showRepeatMenu by ui::showRepeatMenu
    var showShuffleMenu by ui::showShuffleMenu
    var playlistFor by ui::playlistFor
    var confirmDelete by ui::confirmDelete
    var pendingDeleteId by remember { mutableStateOf<Long?>(null) }
    val settingsOpenState = rememberSaveable { mutableStateOf(false) }
    var settingsOpen by settingsOpenState
    var settingsPage by ui::settingsPage
    var showChangelog by ui::showChangelog
    var showHelp by ui::showHelp
    var backupMessage by ui::backupMessage
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
    // Settings → Audio → Equalizer → Import AutoEq Presets ("ParametricEQ.txt" files, or a zip of them)
    val autoEqLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val presets = ArrayList<com.powersound.player.audio.EqPreset>()
        uris.forEach { uri ->
            runCatching {
                val name = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "AutoEq"
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@runCatching
                if (name.endsWith(".zip", ignoreCase = true)) {
                    java.util.zip.ZipInputStream(bytes.inputStream()).use { zip ->
                        var e = zip.nextEntry
                        while (e != null && presets.size < 500) {
                            if (!e.isDirectory && e.name.endsWith("ParametricEQ.txt", ignoreCase = true)) {
                                val text = zip.readBytes().decodeToString()
                                val title = e.name.substringAfterLast('/').removeSuffix(".txt").removeSuffix(" ParametricEQ")
                                com.powersound.player.audio.parseAutoEq("AutoEq: $title", text)?.let { presets.add(it) }
                            }
                            e = zip.nextEntry
                        }
                    }
                } else {
                    val title = name.removeSuffix(".txt").removeSuffix(" ParametricEQ")
                    com.powersound.player.audio.parseAutoEq("AutoEq: $title", bytes.decodeToString())?.let { presets.add(it) }
                }
            }
        }
        val n = com.powersound.player.audio.AudioEngine.importAutoEq(presets)
        backupMessage = if (n > 0) "Imported $n AutoEq preset(s). Pick them in the Equalizer → Preset list."
        else "No AutoEq presets found. Choose a \"ParametricEQ.txt\" file (or a zip with them)."
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val text = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }.getOrNull()
            backupMessage = if (text != null && com.powersound.player.data.DataBackup.import(context, text)) "RESTART" else "This file is not a PowerSound backup."
        }
    }

    // Settings → Library → Playlists: import .m3u files, export all playlists to a folder
    val appScope = rememberCoroutineScope()
    val m3uLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        appScope.launch {
            var lists = 0
            var songs = 0
            uris.forEach { u -> val n = vm.importM3u(u); if (n > 0) { lists++; songs += n } }
            backupMessage = if (lists > 0) "Imported $lists playlist(s) with $songs song(s)."
            else "No songs of these playlists were found in your library."
        }
    }
    val exportPlLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree == null) return@rememberLauncherForActivityResult
        appScope.launch {
            val n = vm.exportPlaylists(tree)
            backupMessage = if (n > 0) "Exported $n playlist(s) as .m3u8 files." else "Nothing was exported."
        }
    }
    val libraryActions = com.powersound.player.ui.LibraryActions(
        counts = "Folders: ${library.folders.size}, Playlists: ${com.powersound.player.data.LibraryStores.playlists.collectAsStateWithLifecycle().value.size}, Songs: ${library.songs.size}",
        fullRescan = { vm.fullRescan { backupMessage = "Full rescan finished: ${vm.library.value.songs.size} songs." } },
        resetStats = { com.powersound.player.data.StatsStore.resetPlays() },
        importSystemPlaylists = {
            appScope.launch {
                val n = vm.importSystemPlaylists()
                backupMessage = if (n > 0) "Imported $n playlist(s) from the phone's music library." else "No new playlists were found in the phone's music library."
            }
        },
        exportPlaylists = { exportPlLauncher.launch(null) },
        importM3u = { m3uLauncher.launch(arrayOf("audio/x-mpegurl", "audio/mpegurl", "application/vnd.apple.mpegurl", "application/x-mpegurl", "*/*")) }
    )

    // Settings → Look and Feel: always keep the screen on in the app
    val hostView = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(appSettings.keepScreenOn) {
        hostView.keepScreenOn = appSettings.keepScreenOn
        onDispose { hostView.keepScreenOn = false }
    }
    // Nothing to show on the Player Screen at startup → stay in the library
    val hasSong by androidx.compose.runtime.rememberUpdatedState(player.nowPlaying != null)
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(2500)
        if (!hasSong) showNowPlaying = false
    }
    val reopenedAt by MainActivity.reopened.collectAsStateWithLifecycle()
    LaunchedEffect(reopenedAt) { if (reopenedAt != 0L) vm.onAppReopened() }
    // Opened from the notification: the Player Screen, or the track's list (Settings → Notifications)
    val fromNotification by MainActivity.openedFromNotification.collectAsStateWithLifecycle()
    LaunchedEffect(fromNotification) {
        if (fromNotification == 0L) return@LaunchedEffect
        settingsOpen = false
        if (appSettings.notifNavigateToList) {
            vm.openSourceList(); showNowPlaying = false
        } else if (player.nowPlaying != null) {
            showNowPlaying = true
        }
    }
    fun shareSong(s: Song) {
        runCatching {
            val send = android.content.Intent(android.content.Intent.ACTION_SEND)
                .setType(s.mimeType.ifBlank { "audio/*" })
                .putExtra(android.content.Intent.EXTRA_STREAM, s.uri)
                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(android.content.Intent.createChooser(send, "Share").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
    // Settings → Player Screen → Line2 Press / Menu Button Long Press
    fun goTo(s: Song, where: Int) {
        showNowPlaying = false
        when (where) {
            1 -> vm.goToArtist(s)
            2 -> vm.goToAlbum(s)
            3 -> vm.goToFolder(s)
            4 -> vm.goToGenre(s)
        }
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

    // Settings → Library → Lists → Static Navbar (off: the bar slides away while you scroll down a list)
    var navHidden by remember { mutableStateOf(false) }
    val staticNav = appSettings.staticNavbar
    LaunchedEffect(staticNav, tab, showNowPlaying) { if (staticNav) navHidden = false }
    val navHide by androidx.compose.animation.core.animateFloatAsState(
        if (navHidden && !staticNav) 1f else 0f, tween(com.powersound.player.ui.animMs(220)), label = "navHide"
    )
    val navScroll = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (consumed.y < -6f) navHidden = true else if (consumed.y > 6f || available.y > 0f) navHidden = false
                return Offset.Zero
            }
        }
    }

    Box(Modifier.fillMaxSize().nestedScroll(navScroll)) {
        // Settings → Background → List Background
        AppBackground(if (appSettings.bgList) currentId else null, forList = true)
        // Settings → Visualization → Visualization in Library
        if (appSettings.vizInLibrary && appSettings.vizOnPlayer && vm.vizMode != com.powersound.player.ui.VizMode.OFF && player.isPlaying && !showNowPlaying) {
            com.powersound.player.ui.VisualizerView(
                preset = vm.vizPreset,
                accent = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = 0.35f }
            )
        }

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
                onOpenSettings = { settingsPage = SettingsPage.LIBRARY; settingsOpen = true },
                onOpenPlayer = { showNowPlaying = true }
            )
            Tab.EQ -> EqualizerScreen(bottomInset)
            Tab.SEARCH -> SearchScreen(
                library = library,
                currentId = currentId,
                bottomInset = bottomInset,
                onPlay = { list, i -> vm.tapSong(list, i, null) { showNowPlaying = true } },
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
                .graphicsLayer { translationY = navHide * (barHeightPx + 60f) }
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

        val npMs = com.powersound.player.ui.animMs(320)
        AnimatedVisibility(
            visible = showNowPlaying && player.nowPlaying != null,
            enter = slideInVertically(tween(npMs)) { it / 3 } + scaleIn(tween(npMs), initialScale = 0.9f) + fadeIn(tween(npMs)),
            exit = slideOutVertically(tween(npMs)) { it / 2 } + fadeOut(tween(npMs))
        ) {
            val np = player.nowPlaying
            val current = vm.song(np?.songId)
            NowPlayingScreen(
                state = player,
                position = vm.position,
                song = current,
                rating = stats.rating(np?.songId),
                stars = stats.stars(np?.songId),
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
                onStars = { n -> vm.setStars(np?.songId, n) },
                onQueue = { showQueue = true },
                onMore = {
                    current?.let { s ->
                        songMenuInfo = player.audioInfo
                        songMenu = s
                    }
                },
                onMenuAction = { action ->
                    current?.let { s ->
                        when (action) {
                            1 -> if (appSettings.confirmDelete) confirmDelete = s else deleteSong(s)
                            2 -> playlistFor = listOf(s.id)
                            3 -> tagsFor = s
                            4 -> artFor = s.id
                            5 -> goTo(s, 1)
                            6 -> goTo(s, 2)
                            7 -> goTo(s, 3)
                            8 -> goTo(s, 4)
                            9 -> shareSong(s)
                            10 -> vm.like(s.id)
                            11 -> vm.dislike(s.id)
                        }
                    }
                },
                onLine2 = { where -> current?.let { goTo(it, where) } },
                onSleep = { showSleep = true },
                onLyrics = { lyricsFor = current },
                onLyricsApp = { current?.let { com.powersound.player.ui.searchLyrics(context, it) } },
                onAudioInfo = { showAudioInfo = true },
                onEqualizer = { showNowPlaying = false; vm.tab = Tab.EQ },
                onBookmark = { np?.songId?.let { vm.addBookmark(it) } },
                onAddToPlaylist = { np?.songId?.let { playlistFor = listOf(it) } }
            )
        }
    }

    // ---- settings (full screen) ----
    if (settingsOpen) {
        BackHandler { if (settingsPage != null) settingsPage = settingsPage?.parent else settingsOpen = false }
        SettingsScreen(
            page = settingsPage,
            allFolders = vm.allFolders,
            onPage = { settingsPage = it },
            onClose = { settingsOpen = false; settingsPage = null },
            onRescan = { vm.loadLibrary(force = true) },
            onAudioInfo = { showAudioInfo = true },
            onImportAutoEq = { autoEqLauncher.launch(arrayOf("text/plain", "application/zip", "application/octet-stream", "*/*")) },
            onCheckUpdate = { vm.checkForUpdate(manual = true) },
            onChangelog = { showChangelog = true },
            onExport = { exportLauncher.launch("PowerSound-backup.json") },
            onImport = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
            lib = libraryActions
        )
    }
    AppDialogs(ui, vm, player, stats, update, currentId, appSettings, showNowPlayingState, settingsOpenState) { deleteSong(it) }
}

/** Everything that shows UI state the dialogs/sheets need (kept here so PowerSoundApp stays small). */
private class AppUi {
    var showMenu: Boolean by mutableStateOf(false)
    var showQueue: Boolean by mutableStateOf(false)
    var showSleep: Boolean by mutableStateOf(false)
    var songMenu: Song? by mutableStateOf(null)
    var songMenuInfo: String? by mutableStateOf(null)
    var lyricsFor: Song? by mutableStateOf(null)
    var tagsFor: Song? by mutableStateOf(null)
    var artFor: Long? by mutableStateOf(null)
    var showAudioInfo: Boolean by mutableStateOf(false)
    var showRepeatMenu: Boolean by mutableStateOf(false)
    var showShuffleMenu: Boolean by mutableStateOf(false)
    var playlistFor: List<Long>? by mutableStateOf(null)
    var confirmDelete: Song? by mutableStateOf(null)
    var settingsPage: SettingsPage? by mutableStateOf(null)
    var showChangelog: Boolean by mutableStateOf(false)
    var showHelp: Boolean by mutableStateOf(false)
    var backupMessage: String? by mutableStateOf(null)
}

/** Sheets and dialogs of the main screen (split out of PowerSoundApp to keep its method small). */
@Composable
private fun AppDialogs(
    ui: AppUi,
    vm: PlayerViewModel,
    player: PlayerUiState,
    stats: Stats,
    update: UpdateState,
    currentId: Long?,
    appSettings: com.powersound.player.data.AppSettings,
    showNowPlayingState: androidx.compose.runtime.MutableState<Boolean>,
    settingsOpenState: androidx.compose.runtime.MutableState<Boolean>,
    deleteSong: (Song) -> Unit
) {
    val context = LocalContext.current
    var showNowPlaying by showNowPlayingState
    var settingsOpen by settingsOpenState
    var showMenu by ui::showMenu
    var showQueue by ui::showQueue
    var showSleep by ui::showSleep
    var songMenu by ui::songMenu
    var songMenuInfo by ui::songMenuInfo
    var lyricsFor by ui::lyricsFor
    var tagsFor by ui::tagsFor
    var artFor by ui::artFor
    var showAudioInfo by ui::showAudioInfo
    var showRepeatMenu by ui::showRepeatMenu
    var showShuffleMenu by ui::showShuffleMenu
    var playlistFor by ui::playlistFor
    var confirmDelete by ui::confirmDelete
    var settingsPage by ui::settingsPage
    var showChangelog by ui::showChangelog
    var showHelp by ui::showHelp
    var backupMessage by ui::backupMessage
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
                title = { Text("PowerSound", color = Color.White) },
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
        LyricsSheet(
            song = s, position = vm.position, onSeek = vm::seekTo,
            onSearch = { com.powersound.player.ui.searchLyrics(context, s) },
            onDismiss = { lyricsFor = null }
        )
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
            stars = stats.stars(s.id),
            onStars = { n -> vm.setStars(s.id, n) },
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
            // Settings → Lyrics → Lyrics Item In The Track Menu
            onLyrics = { if (appSettings.lyricsMenuItem == 1) com.powersound.player.ui.searchLyrics(context, s) else lyricsFor = s },
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
    onOpenSettings: () -> Unit,
    onOpenPlayer: () -> Unit
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
            swipe.animateTo(1f, tween(com.powersound.player.ui.animMs(170)))
            vm.pop()
            swipe.snapTo(0f)
        } catch (e: CancellationException) {
            scope.launch { swipe.animateTo(0f, tween(com.powersound.player.ui.animMs(200))) }
            throw e
        }
    }

    fun finish(commit: Boolean) {
        scope.launch {
            if (commit) {
                swipe.animateTo(1f, tween(com.powersound.player.ui.animMs(180)))
                vm.pop()
                swipe.snapTo(0f)
            } else {
                swipe.animateTo(0f, tween(com.powersound.player.ui.animMs(220)))
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
                        ScreenContent(screen, vm, library, player, stats, bottomInset, onSongMenu, onOpenQueue, onOpenSettings, onOpenPlayer)
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
    onOpenSettings: () -> Unit,
    onOpenPlayer: () -> Unit
) {
    val currentId = player.nowPlaying?.songId
    // Settings → Library → Lists → List Item Action
    val tap: (List<Song>, Int, String?) -> Unit = { list, i, k -> vm.tapSong(list, i, k, onOpenPlayer) }
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
                    // Settings → Library → Lists → Folders Hierarchy Immediate Root: skip folders that only hold one folder
                    val (subs, direct) = remember(library.songs, appSettings.hierImmediateRoot) {
                        var r = MusicRepository.treeChildren(library.songs, "")
                        var guard = 0
                        while (appSettings.hierImmediateRoot && r.first.size == 1 && r.second.isEmpty() && guard++ < 30) {
                            r = MusicRepository.treeChildren(library.songs, r.first[0].key.removePrefix("tree:"))
                        }
                        r
                    }
                    TreeScreen(
                        title = cat.label,
                        backLabel = "Library",
                        folders = subs,
                        songs = direct,
                        currentId = currentId,
                        bottomInset = bottomInset,
                        onBack = { vm.pop() },
                        onOpenFolder = { g -> vm.push(Screen.Tree(g.key.removePrefix("tree:"), cat.label)) },
                        onPlay = { list, i -> tap(list, i, key) },
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
                        onPlay = { list, i -> tap(list, i, key) },
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
                onPlay = { list, i -> tap(list, i, group.key) },
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
                ) else listOf("Add all to a playlist" to { nameDialog = "New playlist" to { n: String -> com.powersound.player.data.LibraryStores.createPlaylist(n, group.songs.map { it.id }); Unit } }),
                listKind = when (group.key.substringBefore(':')) {
                    "album" -> "album"; "folder" -> "folder"; "albumartist" -> "albumartist"; else -> ""
                }
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
                onPlay = { list, i -> tap(list, i, "folder:${screen.path}") },
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
