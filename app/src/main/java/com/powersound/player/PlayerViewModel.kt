package com.powersound.player

import android.app.Application
import android.content.ComponentName
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.powersound.player.audio.AudioEngine
import com.powersound.player.data.AudioInfo
import com.powersound.player.data.Category
import com.powersound.player.data.Group
import com.powersound.player.data.LibraryStores
import com.powersound.player.data.LONG_TRACK_MS
import com.powersound.player.data.MusicRepository
import com.powersound.player.data.Song
import com.powersound.player.data.Stats
import com.powersound.player.data.StatsStore
import com.powersound.player.data.Updater
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class NowPlaying(
    val songId: Long?,
    val title: String,
    val artist: String,
    val album: String
)

/** Position is kept apart from the rest so only the progress bars redraw 4x per second. */
data class PlaybackPos(val positionMs: Long = 0, val durationMs: Long = 0)

/** The 6 "what happens at the end" modes, cycled by the repeat button. */
enum class RepeatMode(val label: String) {
    ADVANCE("Play All Categories"),
    SONG_REPEAT("Play the current song and repeat"),
    LIST_REPEAT("Play the current category and repeat"),
    ADVANCE_REPEAT("Play all categories and repeat"),
    SONG_STOP("Play the current song and stop"),
    LIST_STOP("Play the current category and stop");

    fun next(): RepeatMode = values()[(ordinal + 1) % values().size]
}

enum class ShuffleMode(val label: String) {
    OFF("Shuffle off"),
    SONGS("Shuffle songs"),
    CATEGORIES("Shuffle categories"),
    ALL("Shuffle songs and categories");

    val songs: Boolean get() = this == SONGS || this == ALL
    val categories: Boolean get() = this == CATEGORIES || this == ALL
}

data class QueueEntry(val index: Int, val songId: Long?, val title: String, val artist: String, val mediaId: String = "")

data class PlayerUiState(
    val nowPlaying: NowPlaying? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val audioInfo: String = "",
    val queue: List<QueueEntry> = emptyList(),
    val currentIndex: Int = -1,
    val sleepEndsAt: Long? = null,
    val repeat: RepeatMode = RepeatMode.LIST_STOP,
    val shuffleMode: ShuffleMode = ShuffleMode.OFF,
    /** Library key of the list the current queue came from (album:…, folder:…, cat:ALL_SONGS…). */
    val sourceKey: String? = null
)

data class LibraryState(
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val songs: List<Song> = emptyList(),
    val folders: List<Group> = emptyList(),
    val albums: List<Group> = emptyList(),
    val artists: List<Group> = emptyList(),
    val albumArtists: List<Group> = emptyList(),
    val genres: List<Group> = emptyList(),
    val years: List<Group> = emptyList(),
    val composers: List<Group> = emptyList()
) {
    fun groupsFor(cat: Category): List<Group> = when (cat) {
        Category.FOLDERS -> folders
        Category.ALBUMS -> albums
        Category.ARTISTS -> artists
        Category.ALBUM_ARTISTS -> albumArtists
        Category.GENRES -> genres
        Category.YEARS -> years
        Category.COMPOSERS -> composers
        else -> emptyList()
    }
}

enum class Tab { LIBRARY, EQ, SEARCH }

sealed class UpdateState {
    data object Idle : UpdateState()
    data object Checking : UpdateState()
    data object UpToDate : UpdateState()
    data class Available(val info: Updater.UpdateInfo) : UpdateState()
    data class Downloading(val info: Updater.UpdateInfo, val progress: Float) : UpdateState()
    data class Ready(val info: Updater.UpdateInfo, val file: File, val needsPermission: Boolean) : UpdateState()
    data class Failed(val message: String) : UpdateState()
}

/** Pages of the Library tab, kept as a back stack. */
sealed class Screen(val id: String) {
    data object Home : Screen("home")
    data class Cat(val cat: Category) : Screen("cat:" + cat.name)
    data class Detail(val group: Group, val backLabel: String) : Screen("detail:" + group.key)
    data class Tree(val path: String, val backLabel: String) : Screen("tree:" + path)
}

class PlayerViewModel(app: Application) : AndroidViewModel(app) {

    private val _library = MutableStateFlow(LibraryState())
    val library: StateFlow<LibraryState> = _library.asStateFlow()

    private val _player = MutableStateFlow(PlayerUiState())
    val player: StateFlow<PlayerUiState> = _player.asStateFlow()

    private val _update = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val update: StateFlow<UpdateState> = _update.asStateFlow()

    private val _position = MutableStateFlow(PlaybackPos())
    val position: StateFlow<PlaybackPos> = _position.asStateFlow()

    val stats: StateFlow<Stats> get() = StatsStore.stats

    // ---- navigation ----
    // the app always opens on Library → Folders (back goes to the Library page)
    val stack = mutableStateListOf<Screen>(Screen.Home, Screen.Cat(Category.FOLDERS))
    var tab by mutableStateOf(Tab.LIBRARY)

    // ---- visualization (remembered between openings of the player) ----
    var vizMode by mutableStateOf(com.powersound.player.ui.VizMode.OFF)
        private set
    var vizPreset by mutableStateOf(com.powersound.player.ui.VizPreset.SPECTRUM_CENTERED)
        private set

    fun setVisualization(mode: com.powersound.player.ui.VizMode) {
        vizMode = mode
        modePrefs.edit().putString("vizMode", mode.name).apply()
    }

    fun setVisualizationPreset(p: com.powersound.player.ui.VizPreset) {
        vizPreset = p
        modePrefs.edit().putString("vizPreset", p.name).apply()
    }

    private var songById: Map<Long, Song> = emptyMap()
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var infoJob: Job? = null
    private var infoForId: Long? = null
    private var lastRecordedId: Long? = null
    private var sleepJob: Job? = null
    private var stopAtSongEnd = false
    private var tick = 0
    private var gainForId: Long? = null
    private var gainJob: Job? = null
    private var fadeJob: Job? = null

    /** Folders found on the phone before the Settings → Library filters (for the folder picker). */
    var allFolders: List<String> = emptyList()
        private set
    private val modePrefs = app.getSharedPreferences("powersound_player", android.content.Context.MODE_PRIVATE)

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            lastRecordedId = null // a new play of a song counts again (Count As Played)
            if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) return
            val c = controller ?: return
            // "current song and stop", or the sleep timer waiting for the song to finish
            if (_player.value.repeat == RepeatMode.SONG_STOP || stopAtSongEnd) {
                PlayerCommands.pauseNow = true // the next song already began: stop at once, no fade
                c.pause()
                if (stopAtSongEnd) {
                    stopAtSongEnd = false
                    _player.value = _player.value.copy(sleepEndsAt = null)
                }
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState != Player.STATE_ENDED) return
            if (stopAtSongEnd) {
                stopAtSongEnd = false
                _player.value = _player.value.copy(sleepEndsAt = null)
                return
            }
            // "Play all categories": the list is over, continue with the next album / folder / …
            when (_player.value.repeat) {
                RepeatMode.ADVANCE -> changeCategory(+1, wrap = false)
                RepeatMode.ADVANCE_REPEAT -> changeCategory(+1, wrap = true)
                else -> Unit
            }
        }

        override fun onEvents(player: Player, events: Player.Events) {
            val queueChanged = events.contains(Player.EVENT_TIMELINE_CHANGED) ||
                events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)
            refresh(queueChanged)
            if (queueChanged || events.contains(Player.EVENT_IS_PLAYING_CHANGED)) scheduleQueueSave()
        }
    }

    init {
        AudioEngine.init(app)
        StatsStore.init(app)
        LibraryStores.init(app)
        com.powersound.player.data.LoudnessStore.init(app)
        com.powersound.player.data.SettingsStore.init(app)
        com.powersound.player.data.CrashLog.install(app)
        com.powersound.player.ui.HintStore.init(app)
        // notification buttons that need the app (Settings → Notifications → Buttons)
        viewModelScope.launch {
            PlayerCommands.flow.collect { cmd ->
                when (cmd) {
                    "prevCat" -> changeCategory(-1)
                    "nextCat" -> changeCategory(+1)
                    "repeat" -> cycleRepeat()
                    "shuffle" -> toggleShuffle()
                    // from the large widget: an exact mode
                    else -> when {
                        cmd.startsWith("repeat:") ->
                            runCatching { RepeatMode.valueOf(cmd.substringAfter(':')) }.getOrNull()?.let { setRepeatMode(it) }
                        cmd.startsWith("shuffle:") ->
                            runCatching { ShuffleMode.valueOf(cmd.substringAfter(':')) }.getOrNull()?.let { setShuffleMode(it) }
                    }
                }
            }
        }
        vizMode = runCatching { com.powersound.player.ui.VizMode.valueOf(modePrefs.getString("vizMode", null) ?: "") }
            .getOrDefault(com.powersound.player.ui.VizMode.OFF)
        vizPreset = runCatching { com.powersound.player.ui.VizPreset.valueOf(modePrefs.getString("vizPreset", null) ?: "") }
            .getOrDefault(com.powersound.player.ui.VizPreset.SPECTRUM_CENTERED)
        _player.value = _player.value.copy(
            repeat = runCatching { RepeatMode.valueOf(modePrefs.getString("repeat", null) ?: "") }.getOrDefault(RepeatMode.LIST_STOP),
            shuffleMode = runCatching { ShuffleMode.valueOf(modePrefs.getString("shuffle", null) ?: "") }.getOrDefault(ShuffleMode.OFF)
        )
        connect()
        checkForUpdate(manual = false)
        registerAutoScan()
        // Tempo from the Effects page is applied to the player (pitch is kept).
        viewModelScope.launch {
            AudioEngine.fx.collect { applyTempo() }
        }
        // React to Settings: library filters reload the library, ReplayGain mode re-reads the song's gain.
        viewModelScope.launch {
            var last: com.powersound.player.data.AppSettings? = null
            com.powersound.player.data.SettingsStore.settings.collect { st ->
                val prev = last
                last = st
                if (prev == null) return@collect
                if (prev.minDurationSec != st.minDurationSec || prev.excludedFolders != st.excludedFolders ||
                    prev.ignoreVideo != st.ignoreVideo || prev.joinAlbums != st.joinAlbums || prev.albumArtistAlbums != st.albumArtistAlbums ||
                    prev.splitArtists != st.splitArtists || prev.splitComposers != st.splitComposers || prev.splitGenres != st.splitGenres ||
                    prev.unsplitArtists != st.unsplitArtists || prev.showUnsplit != st.showUnsplit || prev.keepArticles != st.keepArticles
                ) {
                    if (_library.value.loaded) loadLibrary(force = true)
                }
                if (prev.replayGain != st.replayGain || prev.rgSource != st.rgSource || prev.replayGainPreamp != st.replayGainPreamp ||
                    prev.replayGainNoTagDb != st.replayGainNoTagDb || prev.eqAssign != st.eqAssign
                ) {
                    gainForId = null
                    applySongSettings(_player.value.nowPlaying?.songId)
                }
            }
        }
        viewModelScope.launch {
            while (isActive) {
                val c = controller
                // no crossfade into the next song when this one must be the last
                PlayerCommands.stopAfterCurrent = _player.value.repeat == RepeatMode.SONG_STOP || stopAtSongEnd
                if (c != null && c.isPlaying) {
                    _position.value = PlaybackPos(c.currentPosition, durationOf(c))
                    tick++
                    if (tick % 20 == 0) rememberPosition(c) // every ~5 s
                    countPlay(c)
                }
                delay(250)
            }
        }
    }

    private fun connect() {
        val app = getApplication<Application>()
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val future = MediaController.Builder(app, token).buildAsync()
        controllerFuture = future
        future.addListener({
            val c = try {
                future.get()
            } catch (_: Exception) {
                null
            }
            if (c != null) {
                controller = c
                c.addListener(listener)
                applyTempo()
                applyModes()
                refresh(true)
                tryRestoreQueue()
            }
        }, ContextCompat.getMainExecutor(app))
    }

    fun loadLibrary(force: Boolean = false) {
        val current = _library.value
        if (current.loading || (current.loaded && !force)) return
        _library.value = current.copy(loading = true)
        viewModelScope.launch { buildLibrary() }
    }

    /** Reads the library from Android and rebuilds every list (waits until done). */
    private suspend fun buildLibrary() {
        val raw = MusicRepository.loadSongs(getApplication())
        allFolders = raw.map { it.folder }.distinct().sortedBy { it.lowercase() }
        val st = com.powersound.player.data.SettingsStore.value
        val songs = raw.filter { s ->
            s.durationMs >= st.minDurationSec * 1000L &&
                !(st.ignoreVideo && s.mimeType.startsWith("video/")) &&
                st.excludedFolders.none { ex -> s.folder == ex || s.folder.startsWith("$ex/") }
        }
        songById = songs.associateBy { it.id }
        val byId = songById
        com.powersound.player.data.ArtworkLoader.songInfo = { byId[it] }
        _library.value = LibraryState(
            loading = false,
            loaded = true,
            songs = songs,
            folders = MusicRepository.folders(songs),
            albums = MusicRepository.albums(songs),
            artists = MusicRepository.artists(songs),
            albumArtists = MusicRepository.albumArtists(songs),
            genres = MusicRepository.genres(songs),
            years = MusicRepository.years(songs),
            composers = MusicRepository.composers(songs)
        )
        infoForId = null
        refresh(true)
        tryRestoreQueue()
    }

    // ---- Song menu → EQ Preset: a preset for a song, its album or its artist ----

    /** The preset that was on before an assigned one took over (it comes back for other songs). */
    private var presetBeforeAssign: String? = null

    /** The album key of a song (for "album:…" assignments). */
    fun albumKeyOf(song: Song): String? = _library.value.albums.firstOrNull { g -> g.songs.any { it.id == song.id } }?.key

    /** (assignment key, preset name) for the song, else its album, else its artist; null when none. */
    fun assignedPreset(song: Song): Pair<String, String>? {
        val map = com.powersound.player.data.SettingsStore.value.eqAssign
        if (map.isEmpty()) return null
        return listOfNotNull("song:${song.id}", albumKeyOf(song), "artist:${song.artist}")
            .firstNotNullOfOrNull { k -> map[k]?.let { k to it } }
    }

    private fun findPreset(name: String) =
        (com.powersound.player.audio.BUILT_IN_PRESETS + AudioEngine.customPresets.value).firstOrNull { it.name == name }

    private fun applyEqAssignment(songId: Long?) {
        val song = song(songId)
        val target = song?.let { assignedPreset(it)?.second }
        val current = AudioEngine.settings.value.presetName
        if (target != null) {
            if (presetBeforeAssign == null) presetBeforeAssign = current
            if (current != target) findPreset(target)?.let { AudioEngine.applyPreset(it) }
        } else {
            val back = presetBeforeAssign ?: return
            presetBeforeAssign = null
            if (current != back) findPreset(back)?.let { AudioEngine.applyPreset(it) }
        }
    }

    // ---- Replay Gain → Scan Loudness ----

    private var loudnessJob: Job? = null

    /** Measures every song without a measurement (keeps going while the app is open; stop with [stopLoudnessScan]). */
    fun scanLoudness() {
        if (loudnessJob?.isActive == true) return
        val songs = _library.value.songs
        loudnessJob = viewModelScope.launch {
            com.powersound.player.data.LoudnessStore.scan(getApplication(), songs)
            // the playing song gets its new gain
            gainForId = null
            applySongSettings(_player.value.nowPlaying?.songId)
        }
    }

    fun stopLoudnessScan() {
        loudnessJob?.cancel()
        loudnessJob = null
    }

    // ---- Info/Tags → Edit ----

    /**
     * Writes new tags into [song]'s file (after Android allowed it), reloads the library and updates the song
     * wherever it is in the queue. [onDone] gets null when it worked, else the reason it didn't.
     */
    fun saveTags(song: Song, edits: com.powersound.player.data.TagEdits, onDone: (String?) -> Unit) =
        saveTagsBatch(listOf(song to edits), { _, _ -> }) { _, errors -> onDone(errors.firstOrNull()) }

    /**
     * Writes tags into several files (multi-select → Tags, Fix Arabic Names). [onProgress] gets (done, total);
     * [onDone] gets how many were saved and the reasons of the ones that weren't.
     */
    fun saveTagsBatch(
        items: List<Pair<Song, com.powersound.player.data.TagEdits>>,
        onProgress: (Int, Int) -> Unit,
        onDone: (saved: Int, errors: List<String>) -> Unit
    ) {
        viewModelScope.launch {
            val c = controller
            val keys = items.map { it.first.id.toString() }.toSet()
            val isCurrent = c != null && c.currentMediaItem?.mediaId in keys
            val wasPlaying = isCurrent && c?.playWhenReady == true
            val pos = c?.currentPosition ?: 0L
            // the playing song pauses while its file is written
            if (wasPlaying) c?.pause()
            val errors = ArrayList<String>()
            val saved = ArrayList<Song>()
            var pictures = false
            items.forEachIndexed { i, (song, edits) ->
                onProgress(i, items.size)
                val error = com.powersound.player.data.TagEditor.save(getApplication(), song, edits, rescan = false)
                if (error == null) {
                    saved.add(song)
                    if (edits.changesPictures) pictures = true
                } else {
                    errors.add(if (items.size > 1) "${song.title}: $error" else error)
                }
            }
            onProgress(items.size, items.size)
            if (saved.isNotEmpty()) {
                com.powersound.player.data.TagEditor.rescan(getApplication(), saved)
                if (pictures) com.powersound.player.data.ArtworkLoader.clear()
                buildLibrary()
                if (c != null) {
                    val savedKeys = saved.map { it.id.toString() }.toSet()
                    for (i in 0 until c.mediaItemCount) {
                        val id = c.getMediaItemAt(i).mediaId
                        if (id in savedKeys) songById[id.toLong()]?.let { c.replaceMediaItem(i, it.toMediaItem()) }
                    }
                    if (isCurrent && c.currentMediaItem?.mediaId in savedKeys) {
                        // the file changed under the player: open it again at the same place
                        val index = c.currentMediaItemIndex
                        c.stop()
                        c.prepare()
                        c.seekTo(index, pos)
                    }
                }
            }
            if (wasPlaying) c?.play()
            onDone(saved.size, errors)
        }
    }

    // ---- "Open with PowerSound" (a file from a file manager, WhatsApp …) ----

    /**
     * Plays a file another app asked PowerSound to open. A song of the library plays inside its folder;
     * anything else plays on its own.
     */
    fun playExternal(uri: android.net.Uri, onPlaying: () -> Unit) {
        viewModelScope.launch {
            restored = true // the last queue must not replace the file that was opened
            // right after the app starts, the connection to the player may still be on its way
            kotlinx.coroutines.withTimeoutOrNull(5000) { while (controller == null) delay(50) }
            val song = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { findSong(uri) }
            if (song != null) {
                val folder = _library.value.folders.firstOrNull { it.key == "folder:${song.folder}" }
                val list = folder?.songs ?: listOf(song)
                play(list, list.indexOfFirst { it.id == song.id }.coerceAtLeast(0), sourceKey = folder?.key)
            } else {
                val c = controller ?: return@launch
                val name = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { displayName(uri) } ?: "Audio file"
                val item = MediaItem.Builder()
                    .setMediaId("file:$uri")
                    .setUri(uri)
                    .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(name.substringBeforeLast('.')).setArtist("").build())
                    .build()
                c.setMediaItem(item)
                c.prepare()
                c.play()
                _player.value = _player.value.copy(sourceKey = null)
            }
            onPlaying()
        }
    }

    private fun displayName(uri: android.net.Uri): String? = runCatching {
        if (uri.scheme == "file") return@runCatching uri.lastPathSegment
        getApplication<Application>().contentResolver
            .query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()

    /** The library song behind a file address, when there is one. */
    private fun findSong(uri: android.net.Uri): Song? {
        val songs = _library.value.songs
        // content://media/external/audio/media/123
        if (uri.scheme == "content" && uri.authority == "media") {
            uri.lastPathSegment?.toLongOrNull()?.let { id -> songById[id]?.let { return it } }
        }
        if (uri.scheme == "file") {
            val path = uri.path ?: return null
            return songs.firstOrNull { it.path == path }
        }
        // a file manager's address: try the path it may give, then the name and size of the file
        val resolver = getApplication<Application>().contentResolver
        @Suppress("DEPRECATION")
        val path = runCatching {
            resolver.query(uri, arrayOf(android.provider.MediaStore.MediaColumns.DATA), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()
        if (!path.isNullOrBlank()) songs.firstOrNull { it.path == path }?.let { return it }
        // Files app: content://com.android.externalstorage.documents/document/primary:Music/x.flac
        runCatching {
            if (android.provider.DocumentsContract.isDocumentUri(getApplication(), uri)) {
                val doc = android.provider.DocumentsContract.getDocumentId(uri)
                if (doc.startsWith("primary:")) {
                    val p = "/storage/emulated/0/" + doc.removePrefix("primary:")
                    songs.firstOrNull { it.path == p }?.let { return it }
                }
            }
        }
        var name: String? = null
        var size = -1L
        runCatching {
            resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) {
                        name = c.getString(0)
                        if (!c.isNull(1)) size = c.getLong(1)
                    }
                }
        }
        val n = name ?: return null
        val matches = songs.filter { it.path.substringAfterLast('/') == n && (size < 0 || it.size == size) }
        return matches.singleOrNull()
    }

    // ---- last queue (restored when the app starts; Settings → Audio Focus → Resume On Start / Reopen) ----

    private var restored = false
    private var saveJob: Job? = null

    private fun scheduleQueueSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(1500)
            val c = controller ?: return@launch
            if (c.mediaItemCount == 0) return@launch
            // casting: the Chromecast holds only part of the list; the phone's whole list stays saved
            if (c.deviceInfo.playbackType == androidx.media3.common.DeviceInfo.PLAYBACK_TYPE_REMOTE) return@launch
            val ids = (0 until minOf(c.mediaItemCount, 3000)).mapNotNull { c.getMediaItemAt(it).mediaId.toLongOrNull() }
            if (ids.size != c.mediaItemCount) return@launch // streams are not restored
            modePrefs.edit()
                .putString("queueIds", ids.joinToString(","))
                .putInt("queueIndex", c.currentMediaItemIndex)
                .putLong("queuePos", c.currentPosition)
                .putString("queueSource", _player.value.sourceKey ?: "")
                .apply()
        }
    }

    private fun tryRestoreQueue() {
        if (restored) return
        val c = controller ?: return
        if (!_library.value.loaded) return
        restored = true
        // a file was opened from another app: it plays instead of the last queue
        if (MainActivity.openedUri.value != null) return
        if (c.mediaItemCount > 0) return // the service is still playing from before
        val ids = modePrefs.getString("queueIds", null)?.split(',')?.mapNotNull { it.toLongOrNull() } ?: return
        val songs = ids.mapNotNull { songById[it] }
        if (songs.isEmpty()) return
        val savedIndex = modePrefs.getInt("queueIndex", 0)
        val currentId = ids.getOrNull(savedIndex)
        val index = songs.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
        val pos = if (songs.getOrNull(index)?.id == currentId) modePrefs.getLong("queuePos", 0L) else 0L
        c.setMediaItems(songs.map { it.toMediaItem() }, index, pos)
        c.prepare()
        _player.value = _player.value.copy(sourceKey = modePrefs.getString("queueSource", "")?.ifBlank { null })
        if (com.powersound.player.data.SettingsStore.value.resumeOnStart) c.play()
    }

    /** The app came back to the front while the process was still alive (Resume On Reopen). */
    fun onAppReopened() {
        val c = controller ?: return
        val st = com.powersound.player.data.SettingsStore.value
        if (st.resumeOnStart && st.resumeOnReopen && c.mediaItemCount > 0 && !c.isPlaying) togglePlay()
    }

    fun song(id: Long?): Song? = id?.let { songById[it] }

    /** Songs shown for a song-list category of the library. */
    fun songsFor(cat: Category, stats: Stats): List<Song> {
        val all = _library.value.songs
        return when (cat) {
            Category.ALL_SONGS -> all
            Category.MOST_PLAYED -> all.filter { (stats.plays[it.id] ?: 0) > 0 }
                .sortedByDescending { stats.plays[it.id] ?: 0 }.take(100)
            Category.TOP_RATED -> all.filter { stats.isTop(it.id) }
            Category.LOW_RATED -> all.filter { stats.isLow(it.id) }
            Category.RECENTLY_PLAYED -> all.filter { stats.lastPlayed.containsKey(it.id) }
                .sortedByDescending { stats.lastPlayed[it.id] ?: 0L }.take(100)
            Category.RECENTLY_ADDED -> all.sortedByDescending { it.dateAdded }.take(100)
            Category.LONG -> {
                val min = com.powersound.player.data.SettingsStore.value.longTrackMin * 60_000L
                all.filter { it.durationMs >= min }.sortedByDescending { it.durationMs }
            }
            else -> emptyList()
        }
    }

    // ---- navigation ----

    fun push(screen: Screen) {
        if (stack.lastOrNull()?.id != screen.id) stack.add(screen)
    }

    fun pop(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    fun openGroup(group: Group, backLabel: String) {
        tab = Tab.LIBRARY
        push(Screen.Detail(group, backLabel))
    }

    fun goToAlbum(song: Song) {
        _library.value.albums.firstOrNull { g -> g.songs.any { it.id == song.id } }?.let { openGroup(it, "Albums") }
    }

    fun goToArtist(song: Song) {
        val lib = _library.value
        (lib.artists.firstOrNull { it.key == "artist:${song.artist}" } ?: lib.artists.firstOrNull { g -> g.songs.any { it.id == song.id } })
            ?.let { openGroup(it, "Artists") }
    }

    fun goToFolder(song: Song) {
        val st = com.powersound.player.data.SettingsStore.value
        val flat = _library.value.folders.firstOrNull { it.key == "folder:${song.folder}" }
        if (!st.navigateToFolders || flat == null) {
            // Folders Hierarchy: open the song's folder inside the tree
            tab = Tab.LIBRARY
            push(Screen.Tree(song.folder, "Folders Hierarchy"))
            return
        }
        openGroup(flat, "Folders")
    }

    // ---- playback controls ----

    fun play(
        songs: List<Song>,
        startIndex: Int,
        shuffle: Boolean = false,
        sourceKey: String? = null,
        startPositionMs: Long = 0L
    ) {
        val c = controller ?: return
        if (songs.isEmpty()) return
        val index = startIndex.coerceIn(0, songs.lastIndex)
        // Settings → Library → Lists → Click Restarts Track (off: tapping the playing song only keeps it playing)
        val st = com.powersound.player.data.SettingsStore.value
        if (!st.clickRestarts && !shuffle && startPositionMs == 0L && c.mediaItemCount > 0 &&
            c.currentMediaItem?.mediaId == songs[index].id.toString() && sourceKey == _player.value.sourceKey
        ) {
            if (!c.isPlaying) c.play()
            return
        }
        if (shuffle && !_player.value.shuffleMode.songs) setShuffleMode(ShuffleMode.SONGS)
        c.shuffleModeEnabled = _player.value.shuffleMode.songs
        c.setMediaItems(songs.map { it.toMediaItem() }, index, startPositionMs)
        c.prepare()
        c.play()
        _player.value = _player.value.copy(sourceKey = sourceKey)
    }

    /** A song tapped in a list (Settings → Library → Lists → List Item Action). */
    fun tapSong(songs: List<Song>, index: Int, sourceKey: String?, openPlayer: () -> Unit) {
        val st = com.powersound.player.data.SettingsStore.value
        val song = songs.getOrNull(index) ?: return
        // Settings → Library → Lists → Click Restarts Track (off): the song that is playing goes on from where it is,
        // whichever list it is tapped in
        val c = controller
        if (!st.clickRestarts && st.listItemAction != 2 && c != null && c.mediaItemCount > 0 &&
            c.currentMediaItem?.mediaId == song.id.toString()
        ) {
            if (!c.isPlaying) togglePlay()
            if (st.listItemAction == 0) openPlayer()
            return
        }
        when (st.listItemAction) {
            2 -> addToQueue(listOf(song))
            else -> {
                play(songs, index, sourceKey = sourceKey)
                if (st.listItemAction == 0) openPlayer()
            }
        }
    }

    // ---- categories ("Play all categories", long-press prev/next) ----

    /** All groups of the category the current queue came from, and the index of the current one. */
    private fun sourceGroups(): Pair<List<Group>, Int>? {
        val key = _player.value.sourceKey ?: return null
        val lib = _library.value
        val list = when (key.substringBefore(':')) {
            "folder" -> lib.folders
            "album" -> lib.albums
            "artist" -> lib.artists
            "albumartist" -> lib.albumArtists
            "genre" -> lib.genres
            "year" -> lib.years
            "composer" -> lib.composers
            "playlist" -> playlistGroups()
            else -> return null
        }
        val i = list.indexOfFirst { it.key == key }
        return if (i < 0 || list.isEmpty()) null else list to i
    }

    /** Finds the current version of a library group (after a rescan or a playlist edit). */
    fun findGroup(key: String): Group? {
        val lib = _library.value
        val list = when (key.substringBefore(':')) {
            "folder" -> lib.folders
            "album" -> lib.albums
            "artist" -> lib.artists
            "albumartist" -> lib.albumArtists
            "genre" -> lib.genres
            "year" -> lib.years
            "composer" -> lib.composers
            "playlist" -> playlistGroups()
            else -> return null
        }
        return list.firstOrNull { it.key == key }
    }

    /** Plays the next (+1) or previous (-1) album / folder / … Returns false when there is none. */
    fun changeCategory(dir: Int, wrap: Boolean = true): Boolean {
        val (list, i) = sourceGroups() ?: return false
        val target = if (_player.value.shuffleMode.categories && list.size > 1) {
            list.indices.filter { it != i }.random()
        } else {
            val n = i + dir
            when {
                n in list.indices -> n
                wrap -> (n + list.size) % list.size
                else -> return false
            }
        }
        val g = list[target]
        if (g.songs.isEmpty()) return false
        val start = if (_player.value.shuffleMode.songs) g.songs.indices.random() else 0
        play(g.songs, start, sourceKey = g.key)
        return true
    }

    /** The library page of the list that is playing (for "track source list"). */
    fun sourceScreen(): Screen? {
        val key = _player.value.sourceKey
        if (key != null && key.startsWith("cat:")) {
            val cat = runCatching { Category.valueOf(key.removePrefix("cat:")) }.getOrNull()
            if (cat != null) return Screen.Cat(cat)
        }
        sourceGroups()?.let { (list, i) ->
            val label = when (key?.substringBefore(':')) {
                "folder" -> "Folders"; "album" -> "Albums"; "artist" -> "Artists"
                "albumartist" -> "Album Artists"; "genre" -> "Genres"; "year" -> "Years"
                "composer" -> "Composers"; "playlist" -> "Playlists"; else -> "Library"
            }
            return Screen.Detail(list[i], label)
        }
        val song = song(_player.value.nowPlaying?.songId) ?: return null
        val album = _library.value.albums.firstOrNull { g -> g.songs.any { it.id == song.id } } ?: return null
        return Screen.Detail(album, "Albums")
    }

    fun openSourceList() {
        val s = sourceScreen() ?: return
        tab = Tab.LIBRARY
        push(s)
    }

    // ---- playlists & bookmarks ----

    fun playlistGroups(): List<Group> = LibraryStores.playlists.value.map { p ->
        val songs = p.songIds.mapNotNull { songById[it] }
        Group("playlist:${p.id}", p.name, if (songs.size == 1) "1 song" else "${songs.size} songs", songs)
    }

    fun addBookmark(songId: Long?) {
        songId ?: return
        LibraryStores.addBookmark(songId, controller?.currentPosition ?: 0L)
    }

    fun playBookmark(b: com.powersound.player.data.Bookmark) {
        val s = song(b.songId) ?: return
        play(listOf(s), 0, startPositionMs = b.positionMs)
    }

    fun goToGenre(song: Song) {
        val lib = _library.value
        (lib.genres.firstOrNull { it.key == "genre:${song.genre.ifBlank { "Unknown genre" }}" } ?: lib.genres.firstOrNull { g -> g.songs.any { it.id == song.id } })
            ?.let { openGroup(it, "Genres") }
    }

    /** After song files were deleted: drop them from the queue and rescan. */
    fun onSongsDeleted(songIds: Collection<Long>) {
        if (songIds.isEmpty()) return
        val keys = songIds.map { it.toString() }.toSet()
        val c = controller
        if (c != null) {
            for (i in c.mediaItemCount - 1 downTo 0) {
                if (c.getMediaItemAt(i).mediaId in keys) c.removeMediaItem(i)
            }
        }
        // Settings → Library → Playlists → Remove Playlist Entries On Track Deletion
        if (com.powersound.player.data.SettingsStore.value.playlistRemoveDeleted) songIds.forEach { LibraryStores.removeSongEverywhere(it) }
        loadLibrary(force = true)
    }

    fun shuffleAll(songs: List<Song>, sourceKey: String? = null) {
        if (songs.isEmpty()) return
        // Settings → Library → Shuffle → No Reshuffle (For Large Lists): the same list keeps its shuffle order
        val st = com.powersound.player.data.SettingsStore.value
        val c = controller
        if (c != null && sourceKey != null && sourceKey == _player.value.sourceKey && c.shuffleModeEnabled &&
            c.mediaItemCount == songs.size && (st.noReshuffle || (st.noReshuffleLarge && songs.size >= LARGE_LIST))
        ) {
            if (!c.isPlaying) c.play()
            return
        }
        play(songs, songs.indices.random(), shuffle = true, sourceKey = sourceKey)
    }

    fun playNext(songs: List<Song>) {
        val c = controller ?: return
        if (songs.isEmpty()) return
        if (c.mediaItemCount == 0) {
            play(songs, 0); return
        }
        // Settings → Library → Queue → Play Next Insert Position: Shuffled mixes the inserted songs
        val list = if (com.powersound.player.data.SettingsStore.value.playNextShuffled) songs.shuffled() else songs
        c.addMediaItems(c.currentMediaItemIndex + 1, list.map { it.toMediaItem() })
    }

    fun addToQueue(songs: List<Song>) {
        val c = controller ?: return
        if (songs.isEmpty()) return
        if (c.mediaItemCount == 0) {
            play(songs, 0); return
        }
        val list = if (com.powersound.player.data.SettingsStore.value.queueInsertShuffled) songs.shuffled() else songs
        c.addMediaItems(list.map { it.toMediaItem() })
    }

    fun playQueueIndex(index: Int) {
        val c = controller ?: return
        if (index !in 0 until c.mediaItemCount) return
        c.seekToDefaultPosition(index)
        c.prepare()
        c.play()
    }

    fun removeFromQueue(index: Int) {
        val c = controller ?: return
        if (index in 0 until c.mediaItemCount) c.removeMediaItem(index)
    }

    /** Queue → drag a song to another place. */
    fun moveInQueue(from: Int, to: Int) {
        val c = controller ?: return
        val n = c.mediaItemCount
        if (from == to || from !in 0 until n || to !in 0 until n) return
        c.moveMediaItem(from, to)
    }

    fun togglePlay() {
        val c = controller ?: return
        if (c.isPlaying) {
            rememberPosition(c)
            c.pause() // the playback service fades out first (Settings → Audio → Fade Play/Pause)
        } else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            c.play() // …and fades back in
        }
    }

    // ---- per-song settings: ReplayGain and remembered position ----

    private fun applySongSettings(songId: Long?) {
        if (songId == gainForId) return
        gainForId = songId
        gainJob?.cancel()
        applyEqAssignment(songId)
        val st = com.powersound.player.data.SettingsStore.value
        if (songId == null || st.replayGain == 0) {
            AudioEngine.setReplayGain(0.0)
        } else {
            gainJob = viewModelScope.launch {
                val rg = com.powersound.player.data.TagReader.replayGain(
                    com.powersound.player.data.TagReader.read(getApplication(), songId)
                )
                // Settings → Audio → Replay Gain: source album / track, optional clipping prevention by the peak tag
                val album = st.rgSource == 0
                var db = if (album) rg.albumDb ?: rg.trackDb else rg.trackDb ?: rg.albumDb
                var peak = if (album) rg.albumPeak ?: rg.trackPeak else rg.trackPeak ?: rg.albumPeak
                // no Replay Gain tags: the loudness PowerSound measured itself (Replay Gain → Scan Loudness)
                if (db == null) {
                    val s = song(songId)
                    if (s != null) {
                        val albumSongs = _library.value.albums.firstOrNull { g -> g.songs.any { it.id == songId } }?.songs
                        com.powersound.player.data.LoudnessStore.gainDb(s, albumSongs, album)?.let { (g, p) -> db = g; peak = p }
                    }
                }
                val gain0 = db
                var gain = if (gain0 != null) gain0 + st.replayGainPreamp else st.replayGainNoTagDb
                val pk = peak
                if (st.replayGain == 2 && db != null && pk != null && pk > 0.0) {
                    val maxGain = -20.0 * kotlin.math.log10(pk)
                    if (gain > maxGain) gain = maxGain
                }
                AudioEngine.setReplayGain(gain)
            }
        }
        // Settings → Misc: long songs continue where you left them
        val c = controller ?: return
        val song = song(songId) ?: return
        if (st.rememberPosition && song.durationMs >= st.longTrackMin * 60_000L && c.currentPosition < 3000) {
            val saved = com.powersound.player.data.SettingsStore.savedPosition(song.id)
            if (saved in 5_000 until (song.durationMs - 15_000)) c.seekTo(saved)
        }
    }

    private fun rememberPosition(c: MediaController) {
        if (!com.powersound.player.data.SettingsStore.value.rememberPosition) return
        val id = c.currentMediaItem?.mediaId?.toLongOrNull() ?: return
        val song = song(id) ?: return
        if (song.durationMs < com.powersound.player.data.SettingsStore.value.longTrackMin * 60_000L) return
        val pos = c.currentPosition
        com.powersound.player.data.SettingsStore.savePosition(id, if (pos > song.durationMs - 15_000) 0L else pos)
    }

    // ---- streams ----

    fun playStream(stream: com.powersound.player.data.StreamItem) {
        val c = controller ?: return
        val uri = android.net.Uri.parse(stream.url.trim())
        val item = MediaItem.Builder()
            .setMediaId("stream:${stream.id}")
            .setUri(uri)
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
            .setMediaMetadata(MediaMetadata.Builder().setTitle(stream.name).setArtist("Stream").build())
            .build()
        c.setMediaItem(item)
        c.prepare()
        c.play()
        _player.value = _player.value.copy(sourceKey = "cat:STREAMS")
    }

    fun next() {
        controller?.seekToNextMediaItem()
    }

    fun previous() {
        val c = controller ?: return
        // Settings → Player Screen: "<< Button Resets Current Track" — first press rewinds, second changes track
        val resets = com.powersound.player.data.SettingsStore.value.prevResets
        if ((resets && c.currentPosition > 3000) || !c.hasPreviousMediaItem()) c.seekTo(0) else c.seekToPreviousMediaItem()
    }

    fun seekTo(positionMs: Long) {
        val c = controller ?: return
        c.seekTo(positionMs)
        _position.value = PlaybackPos(positionMs, durationOf(c))
    }

    fun seekBy(deltaMs: Long) {
        val c = controller ?: return
        val d = durationOf(c)
        val target = (c.currentPosition + deltaMs).coerceIn(0L, if (d > 0) d else Long.MAX_VALUE)
        seekTo(target)
    }

    fun toggleShuffle() {
        setShuffleMode(if (_player.value.shuffleMode == ShuffleMode.OFF) ShuffleMode.SONGS else ShuffleMode.OFF)
    }

    fun setShuffleMode(mode: ShuffleMode) {
        _player.value = _player.value.copy(shuffleMode = mode)
        modePrefs.edit().putString("shuffle", mode.name).apply()
        applyModes()
        PlaybackService.instance?.refreshWidget()
    }

    /** Cycles the 6 end-of-list modes and returns the new one (for the toast). */
    fun cycleRepeat(): RepeatMode {
        val next = _player.value.repeat.next()
        setRepeatMode(next)
        return next
    }

    fun setRepeatMode(mode: RepeatMode) {
        _player.value = _player.value.copy(repeat = mode)
        modePrefs.edit().putString("repeat", mode.name).apply()
        applyModes()
        PlaybackService.instance?.refreshWidget()
    }

    private fun applyModes() {
        val c = controller ?: return
        val st = _player.value
        c.repeatMode = when (st.repeat) {
            RepeatMode.SONG_REPEAT -> Player.REPEAT_MODE_ONE
            RepeatMode.LIST_REPEAT -> Player.REPEAT_MODE_ALL
            else -> Player.REPEAT_MODE_OFF
        }
        c.shuffleModeEnabled = st.shuffleMode.songs
    }

    // ---- ratings ----

    fun like(songId: Long?) {
        songId ?: return
        StatsStore.setRating(songId, if (StatsStore.stats.value.rating(songId) == 1) 0 else 1)
    }

    fun dislike(songId: Long?) {
        songId ?: return
        StatsStore.setRating(songId, if (StatsStore.stats.value.rating(songId) == -1) 0 else -1)
    }

    /** 1..5 stars; tapping the same number again clears the rating. 0 clears too. */
    fun setStars(songId: Long?, stars: Int) {
        songId ?: return
        val now = StatsStore.stats.value.rating(songId)
        StatsStore.setRating(songId, if (stars <= 0 || now == 10 + stars) 0 else 10 + stars.coerceIn(1, 5))
    }

    // ---- sleep timer ----

    fun setSleepTimer(minutes: Int, finishSong: Boolean = false) = startSleep(minutes * 60_000L, finishSong)

    fun sleepAtEndOfTrack() {
        val c = controller ?: return
        val remaining = (durationOf(c) - c.currentPosition).coerceAtLeast(1_000L)
        startSleep(remaining)
    }

    fun cancelSleep() {
        sleepJob?.cancel()
        sleepJob = null
        stopAtSongEnd = false
        endSleepFade()
        _player.value = _player.value.copy(sleepEndsAt = null)
    }

    private var sleepFading: Boolean
        get() = PlayerCommands.sleepFading
        set(v) { PlayerCommands.sleepFading = v }

    /** Puts the volume back after the sleep timer lowered it (timer cancelled, or the music has stopped). */
    private fun endSleepFade() {
        fadeJob?.cancel()
        if (sleepFading) {
            sleepFading = false
            controller?.volume = 1f
        }
    }

    /** Fade over the last [windowMs] milliseconds, then pause. A squared curve sounds even to the ear. */
    private suspend fun sleepFadeOut(windowMs: Long) {
        val c = controller ?: return
        val steps = (windowMs / 250L).coerceIn(8L, 600L).toInt()
        sleepFading = true
        for (i in 1..steps) {
            delay(windowMs / steps)
            val f = 1f - i / steps.toFloat()
            if (c.isPlaying) c.volume = f * f
        }
    }

    /**
     * Sleep timer. With "Fade out slowly" (on by default) the music gets quieter over the last 2 minutes
     * (a third of the time for short timers) instead of stopping all at once. With "Play last song to end" the
     * last 20 seconds of that song fade instead.
     */
    private fun startSleep(ms: Long, finishSong: Boolean = false) {
        sleepJob?.cancel()
        stopAtSongEnd = false
        endSleepFade()
        val fade = com.powersound.player.data.SettingsStore.value.sleepFade
        _player.value = _player.value.copy(sleepEndsAt = System.currentTimeMillis() + ms)
        sleepJob = viewModelScope.launch {
            val window = if (fade && !finishSong) minOf(120_000L, ms / 3) else 0L
            delay(ms - window)
            if (window > 0) sleepFadeOut(window)
            if (finishSong && controller?.isPlaying == true) {
                // let the current song play to its end, then stop
                stopAtSongEnd = true
                if (fade) {
                    fadeJob = viewModelScope.launch {
                        val c = controller ?: return@launch
                        // wait for the last 20 seconds of the song, then fade with what is left
                        while (stopAtSongEnd) {
                            val left = durationOf(c) - c.currentPosition
                            if (left in 1L..20_000L) {
                                sleepFading = true
                                if (c.isPlaying) c.volume = (left / 20_000f).let { it * it }
                            }
                            delay(200)
                        }
                        endSleepFade()
                    }
                }
            } else {
                controller?.pause()
                _player.value = _player.value.copy(sleepEndsAt = null)
                // the pause has its own short fade; then the volume is normal for next time
                delay(com.powersound.player.data.SettingsStore.value.fadeMs + 300L)
                endSleepFade()
            }
        }
    }

    private fun applyTempo() {
        val c = controller ?: return
        val fx = AudioEngine.fx.value
        val speed = if (fx.tempoEnabled) fx.tempo.toFloat() else 1f
        if (c.playbackParameters.speed != speed) c.setPlaybackSpeed(speed)
    }

    // ---- in-app updates ----

    /** Automatic check when the app opens (silent if nothing new), or from the menu. */
    fun checkForUpdate(manual: Boolean) {
        val cur = _update.value
        if (cur is UpdateState.Checking || cur is UpdateState.Downloading) return
        if (manual) _update.value = UpdateState.Checking
        viewModelScope.launch {
            val result = runCatching { Updater.check(getApplication()) }
            _update.value = when {
                result.isFailure -> if (manual) UpdateState.Failed(
                    "Couldn't reach GitHub. Check your internet connection and try again."
                ) else UpdateState.Idle
                result.getOrNull() != null -> UpdateState.Available(result.getOrNull()!!)
                manual -> UpdateState.UpToDate
                else -> UpdateState.Idle
            }
        }
    }

    fun downloadUpdate() {
        val info = when (val cur = _update.value) {
            is UpdateState.Available -> cur.info
            is UpdateState.Ready -> cur.info
            else -> return
        }
        _update.value = UpdateState.Downloading(info, 0f)
        viewModelScope.launch {
            try {
                var last = -1
                val file = Updater.download(getApplication(), info) { p ->
                    val pct = (p * 100).toInt()
                    if (pct != last) {
                        last = pct
                        _update.value = UpdateState.Downloading(info, p)
                    }
                }
                _update.value = UpdateState.Ready(info, file, needsPermission = false)
                installUpdate()
            } catch (e: Exception) {
                _update.value = UpdateState.Failed("Download failed: ${e.message ?: "unknown error"}")
            }
        }
    }

    fun installUpdate() {
        val cur = _update.value as? UpdateState.Ready ?: return
        val started = try {
            Updater.install(getApplication(), cur.file)
        } catch (e: Exception) {
            _update.value = UpdateState.Failed("Couldn't open the installer: ${e.message ?: ""}")
            return
        }
        _update.value = cur.copy(needsPermission = !started)
    }

    fun dismissUpdate() {
        if (_update.value !is UpdateState.Downloading) _update.value = UpdateState.Idle
    }

    // ---- Settings → Library: scanner, full rescan, stats, playlists ----

    private var scanJob: Job? = null
    private val mediaObserver = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            val st = com.powersound.player.data.SettingsStore.value
            if (!st.autoScan || !_library.value.loaded) return
            // wait until the phone finished copying files (Rescan Immediately: a short wait)
            scanJob?.cancel()
            scanJob = viewModelScope.launch {
                delay(if (st.rescanImmediately) 1_500L else 20_000L)
                loadLibrary(force = true)
            }
        }
    }

    /** Settings → Library → Scanner → Auto-scan: new or removed music files are picked up by themselves. */
    private fun registerAutoScan() {
        runCatching {
            getApplication<Application>().contentResolver.registerContentObserver(
                android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, mediaObserver
            )
        }
    }

    /** Settings → Library → Full Rescan: the phone reads the files' tags again, every cache is cleared, the library reloads. */
    fun fullRescan(onDone: () -> Unit = {}) {
        val app = getApplication<Application>()
        com.powersound.player.data.ArtworkLoader.clear()
        com.powersound.player.data.LyricsReader.clearCache()
        infoForId = null
        gainForId = null
        viewModelScope.launch {
            val paths = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val known = _library.value.songs.map { it.path }.filter { it.isNotBlank() }
                val inFolders = allFolders.flatMap { f ->
                    runCatching { File(f).listFiles()?.filter { it.isFile }?.map { it.absolutePath } }.getOrNull() ?: emptyList()
                }
                (known + inFolders).distinct()
            }
            if (paths.isNotEmpty()) {
                kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont ->
                    var left = paths.size
                    android.media.MediaScannerConnection.scanFile(app, paths.toTypedArray(), null) { _, _ ->
                        synchronized(this@PlayerViewModel) {
                            left--
                            if (left == 0 && cont.isActive) cont.resumeWith(Result.success(Unit))
                        }
                    }
                }
            }
            loadLibrary(force = true)
            onDone()
        }
    }

    /** Settings → Library → Playlists → Import System Library Playlists. Returns how many playlists were added. */
    @Suppress("DEPRECATION")
    suspend fun importSystemPlaylists(): Int = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val resolver = getApplication<Application>().contentResolver
        val existing = LibraryStores.playlists.value.map { it.name.lowercase() }.toSet()
        var added = 0
        runCatching {
            resolver.query(
                android.provider.MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI,
                arrayOf(android.provider.MediaStore.Audio.Playlists._ID, android.provider.MediaStore.Audio.Playlists.NAME),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val plId = c.getLong(0)
                    val name = c.getString(1) ?: continue
                    if (name.lowercase() in existing) continue
                    val ids = ArrayList<Long>()
                    resolver.query(
                        android.provider.MediaStore.Audio.Playlists.Members.getContentUri("external", plId),
                        arrayOf(android.provider.MediaStore.Audio.Playlists.Members.AUDIO_ID),
                        null, null, android.provider.MediaStore.Audio.Playlists.Members.PLAY_ORDER
                    )?.use { m -> while (m.moveToNext()) m.getLong(0).takeIf { songById.containsKey(it) }?.let { ids.add(it) } }
                    if (ids.isNotEmpty()) {
                        val pl = LibraryStores.createPlaylist(name)
                        LibraryStores.replacePlaylistSongs(pl.id, ids)
                        added++
                    }
                }
            }
        }
        added
    }

    /** Settings → Library → Playlists → Import .m3u / .m3u8. Returns the number of songs found. */
    suspend fun importM3u(uri: android.net.Uri): Int = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val app = getApplication<Application>()
        val name = runCatching {
            app.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull() ?: "Playlist"
        val bytes = runCatching { app.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull() ?: return@withContext 0
        LibraryStores.importM3u(name.substringBeforeLast('.'), bytes, name.endsWith(".m3u8", ignoreCase = true), _library.value.songs)
    }

    /** Settings → Library → Playlists → Export Playlists: one .m3u8 file per playlist in the chosen folder. */
    suspend fun exportPlaylists(tree: android.net.Uri): Int = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val resolver = getApplication<Application>().contentResolver
        var n = 0
        runCatching {
            val parent = android.provider.DocumentsContract.buildDocumentUriUsingTree(
                tree, android.provider.DocumentsContract.getTreeDocumentId(tree)
            )
            for (p in LibraryStores.playlists.value) {
                val safe = p.name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                val doc = android.provider.DocumentsContract.createDocument(resolver, parent, "audio/x-mpegurl", "$safe.m3u8") ?: continue
                resolver.openOutputStream(doc)?.use { it.write(LibraryStores.toM3u8(p) { id -> songById[id] }.toByteArray(Charsets.UTF_8)) }
                n++
            }
        }
        n
    }

    // ---- state ----

    private fun refresh(queueChanged: Boolean) {
        val c = controller ?: return
        val item = c.currentMediaItem
        val nowPlaying = item?.let {
            val md = it.mediaMetadata
            NowPlaying(
                songId = it.mediaId.toLongOrNull(),
                title = md.title?.toString() ?: "Unknown",
                artist = md.artist?.toString() ?: "Unknown artist",
                album = md.albumTitle?.toString() ?: ""
            )
        }
        val queue = if (queueChanged) {
            (0 until c.mediaItemCount).map { i ->
                val mi = c.getMediaItemAt(i)
                QueueEntry(
                    index = i,
                    songId = mi.mediaId.toLongOrNull(),
                    title = mi.mediaMetadata.title?.toString() ?: "Unknown",
                    artist = mi.mediaMetadata.artist?.toString() ?: "",
                    mediaId = mi.mediaId
                )
            }
        } else _player.value.queue

        _player.value = _player.value.copy(
            nowPlaying = nowPlaying,
            isPlaying = c.isPlaying,
            positionMs = c.currentPosition,
            durationMs = durationOf(c),
            shuffle = c.shuffleModeEnabled,
            repeatMode = c.repeatMode,
            queue = queue,
            currentIndex = c.currentMediaItemIndex
        )
        _position.value = PlaybackPos(c.currentPosition, durationOf(c))

        val id = nowPlaying?.songId
        countPlay(c)
        updateAudioInfo(id)
        applySongSettings(id)
        prefetchWaveforms(c, id)
    }

    private var waveForId: Long? = null

    /** The waveform of the song that starts now and of the next one are read in the background, so they show at once. */
    private fun prefetchWaveforms(c: MediaController, id: Long?) {
        if (id == null || id == waveForId) return
        waveForId = id
        val app = getApplication<Application>()
        com.powersound.player.data.Waveform.prefetch(app, id)
        val next = c.nextMediaItemIndex
        if (next != androidx.media3.common.C.INDEX_UNSET) {
            c.getMediaItemAt(next).mediaId.toLongOrNull()?.let { com.powersound.player.data.Waveform.prefetch(app, it) }
        }
    }

    /** Settings → Library → Count As Played: the play counts once this part of the song has been heard. */
    private fun countPlay(c: MediaController) {
        val id = c.currentMediaItem?.mediaId?.toLongOrNull() ?: return
        if (!c.isPlaying || id == lastRecordedId) return
        val pct = com.powersound.player.data.SettingsStore.value.countPlayedPct
        val dur = durationOf(c)
        if (pct > 0 && (dur <= 0 || c.currentPosition < dur * pct / 100)) return
        lastRecordedId = id
        StatsStore.recordPlay(id)
    }

    private fun durationOf(c: MediaController): Long {
        val d = c.duration
        if (d > 0) return d
        val id = c.currentMediaItem?.mediaId?.toLongOrNull()
        return id?.let { songById[it]?.durationMs } ?: 0L
    }

    private fun updateAudioInfo(songId: Long?) {
        if (songId == infoForId) return
        infoForId = songId
        infoJob?.cancel()
        if (songId == null) {
            _player.value = _player.value.copy(audioInfo = "")
            return
        }
        val song = songById[songId]
        infoJob = viewModelScope.launch {
            val info = AudioInfo.read(
                getApplication(),
                songId,
                song?.size ?: 0L,
                song?.durationMs ?: 0L
            )
            _player.value = _player.value.copy(audioInfo = info)
        }
    }

    override fun onCleared() {
        endSleepFade() // never leave the music quiet because the app closed mid-fade
        runCatching { getApplication<Application>().contentResolver.unregisterContentObserver(mediaObserver) }
        controller?.removeListener(listener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null
        super.onCleared()
    }
}

/** Settings → Library → Shuffle → No Reshuffle For Large Lists. */
private const val LARGE_LIST = 500

private fun Song.toMediaItem(): MediaItem =
    MediaItem.Builder()
        .setMediaId(id.toString())
        .setUri(uri)
        .setRequestMetadata(
            MediaItem.RequestMetadata.Builder().setMediaUri(uri).build()
        )
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setAlbumArtist(albumArtist)
                .setTrackNumber(trackNumber)
                .build()
        )
        .build()
