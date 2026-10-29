package com.powersound.player

import android.Manifest
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
import com.powersound.player.ui.SongActionsSheet
import com.powersound.player.ui.SongListScreen
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
    var barHeightPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val bottomInset: Dp = with(density) { barHeightPx.toDp() } + 4.dp

    val currentId = player.nowPlaying?.songId
    val tab = vm.tab

    // Back from the EQ / Search tabs goes to the Library tab.
    BackHandler(enabled = tab != Tab.LIBRARY && !showNowPlaying) { vm.tab = Tab.LIBRARY }

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
                enabled = !showNowPlaying,
                onSongMenu = openSongMenu,
                onOpenQueue = { showQueue = true }
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
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .onSizeChanged { barHeightPx = it.height }
        )

        AnimatedVisibility(
            visible = showNowPlaying && player.nowPlaying != null,
            enter = slideInVertically { it / 3 } + scaleIn(initialScale = 0.9f) + fadeIn(),
            exit = slideOutVertically { it / 2 } + fadeOut()
        ) {
            val np = player.nowPlaying
            NowPlayingScreen(
                state = player,
                position = vm.position,
                rating = stats.rating(np?.songId),
                sleepActive = player.sleepEndsAt != null,
                onClose = { showNowPlaying = false },
                onTogglePlay = vm::togglePlay,
                onNext = vm::next,
                onPrevious = vm::previous,
                onSeek = vm::seekTo,
                onSeekBy = vm::seekBy,
                onShuffle = vm::toggleShuffle,
                onRepeat = vm::cycleRepeat,
                onLike = { vm.like(np?.songId) },
                onDislike = { vm.dislike(np?.songId) },
                onQueue = { showQueue = true },
                onMore = {
                    vm.song(np?.songId)?.let { s ->
                        songMenuInfo = player.audioInfo
                        songMenu = s
                    }
                },
                onSleep = { showSleep = true }
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
            active = player.sleepEndsAt != null,
            onSet = { vm.setSleepTimer(it) },
            onEndOfTrack = { vm.sleepAtEndOfTrack() },
            onCancelTimer = { vm.cancelSleep() },
            onDismiss = { showSleep = false }
        )
    }
    UpdateDialog(
        state = update,
        onDownload = vm::downloadUpdate,
        onInstall = vm::installUpdate,
        onDismiss = vm::dismissUpdate
    )
    songMenu?.let { s ->
        SongActionsSheet(
            song = s,
            info = songMenuInfo,
            onDismiss = { songMenu = null },
            onPlayNext = { vm.playNext(listOf(s)) },
            onAddToQueue = { vm.addToQueue(listOf(s)) },
            onGoAlbum = { showNowPlaying = false; vm.goToAlbum(s) },
            onGoArtist = { showNowPlaying = false; vm.goToArtist(s) },
            onGoFolder = { showNowPlaying = false; vm.goToFolder(s) }
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
    onOpenQueue: () -> Unit
) {
    val holder = rememberSaveableStateHolder()
    val scope = rememberCoroutineScope()
    val stack = vm.stack
    val canGoBack = enabled && stack.size > 1

    var systemBack by remember { mutableFloatStateOf(0f) }
    val swipe = remember { Animatable(0f) }
    var dirX by remember { mutableFloatStateOf(1f) }
    var dirY by remember { mutableFloatStateOf(0f) }
    var pageWidth by remember { mutableFloatStateOf(1f) }
    var pageHeight by remember { mutableFloatStateOf(1f) }

    PredictiveBackHandler(enabled = canGoBack) { progress ->
        try {
            dirX = 1f
            dirY = 0f
            progress.collect { systemBack = it.progress }
            vm.pop()
            systemBack = 0f
        } catch (e: CancellationException) {
            systemBack = 0f
            throw e
        }
    }

    fun finish(commit: Boolean) {
        scope.launch {
            if (commit) {
                swipe.animateTo(1f, tween(160))
                vm.pop()
                swipe.snapTo(0f)
            } else {
                swipe.animateTo(0f, tween(200))
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

    val p = max(systemBack, swipe.value)
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
                            val q = if (isTop) max(systemBack, swipe.value) else 0f
                            scaleX = 1f - 0.14f * q
                            scaleY = 1f - 0.14f * q
                            translationX = dirX * 56.dp.toPx() * q
                            translationY = dirY * 90.dp.toPx() * q
                            alpha = 1f - 0.3f * q
                            shape = RoundedCornerShape(34.dp.toPx() * q)
                            clip = q > 0f
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
                        ScreenContent(screen, vm, library, player, stats, bottomInset, onSongMenu, onOpenQueue)
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
    onOpenQueue: () -> Unit
) {
    val currentId = player.nowPlaying?.songId
    val play: (List<Song>, Int) -> Unit = { list, i -> vm.play(list, i) }
    val shuffle: (List<Song>) -> Unit = { vm.shuffleAll(it) }
    val addQueue: (List<Song>) -> Unit = { vm.addToQueue(it) }

    when (screen) {
        Screen.Home -> {
            val queueEmpty = player.queue.isEmpty()
            val empty = remember(library.songs, stats, queueEmpty) {
                Category.values().filter { cat ->
                    when (cat.kind) {
                        Kind.QUEUE -> queueEmpty
                        Kind.SONGS -> vm.songsFor(cat, stats).isEmpty()
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
            onRescan = { vm.loadLibrary(force = true) }
        )
        }

        is Screen.Cat -> {
            val cat = screen.cat
            when (cat.kind) {
                Kind.GROUPS -> GroupGridScreen(
                    title = cat.label,
                    icon = cat.icon(),
                    iconColor = cat.color(),
                    backLabel = "Library",
                    groups = library.groupsFor(cat),
                    bottomInset = bottomInset,
                    onBack = { vm.pop() },
                    onOpen = { g -> vm.push(Screen.Detail(g, cat.label)) },
                    onPlay = { play(it, 0) },
                    onShuffle = shuffle,
                    onAddToQueue = addQueue
                )
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
                        onPlay = play,
                        onShuffle = shuffle,
                        onSongMenu = onSongMenu
                    )
                }
                else -> {
                    val songs = remember(library.songs, stats, cat) { vm.songsFor(cat, stats) }
                    SongListScreen(
                        songs = songs,
                        currentId = currentId,
                        bottomInset = bottomInset,
                        showIndex = cat == Category.ALL_SONGS,
                        emptyText = "Nothing here yet",
                        onPlay = play,
                        onShuffle = shuffle,
                        onSongMenu = onSongMenu,
                        onAddToQueue = addQueue
                    ) { pills ->
                        PageHeader("Library", cat.label, cat.icon(), cat.color(), { vm.pop() }) { pills() }
                    }
                }
            }
        }

        is Screen.Detail -> SongListScreen(
            songs = screen.group.songs,
            currentId = currentId,
            bottomInset = bottomInset,
            showIndex = false,
            emptyText = "No songs",
            onPlay = play,
            onShuffle = shuffle,
            onSongMenu = onSongMenu,
            onAddToQueue = addQueue
        ) { pills ->
            ArtHeader(screen.group, screen.backLabel, { vm.pop() }) { pills() }
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
                onPlay = play,
                onShuffle = shuffle,
                onSongMenu = onSongMenu
            )
        }
    }
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
