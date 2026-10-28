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
import com.powersound.player.data.LONG_TRACK_MS
import com.powersound.player.data.MusicRepository
import com.powersound.player.data.Song
import com.powersound.player.data.Stats
import com.powersound.player.data.StatsStore
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

data class QueueEntry(val index: Int, val songId: Long?, val title: String, val artist: String)

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
    val sleepEndsAt: Long? = null
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

    private val _position = MutableStateFlow(PlaybackPos())
    val position: StateFlow<PlaybackPos> = _position.asStateFlow()

    val stats: StateFlow<Stats> get() = StatsStore.stats

    // ---- navigation ----
    val stack = mutableStateListOf<Screen>(Screen.Home)
    var tab by mutableStateOf(Tab.LIBRARY)

    private var songById: Map<Long, Song> = emptyMap()
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var infoJob: Job? = null
    private var infoForId: Long? = null
    private var lastRecordedId: Long? = null
    private var sleepJob: Job? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            val queueChanged = events.contains(Player.EVENT_TIMELINE_CHANGED) ||
                events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)
            refresh(queueChanged)
        }
    }

    init {
        AudioEngine.init(app)
        StatsStore.init(app)
        connect()
        viewModelScope.launch {
            while (isActive) {
                val c = controller
                if (c != null && c.isPlaying) {
                    _position.value = PlaybackPos(c.currentPosition, durationOf(c))
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
                refresh(true)
            }
        }, ContextCompat.getMainExecutor(app))
    }

    fun loadLibrary(force: Boolean = false) {
        val current = _library.value
        if (current.loading || (current.loaded && !force)) return
        _library.value = current.copy(loading = true)
        viewModelScope.launch {
            val songs = MusicRepository.loadSongs(getApplication())
            songById = songs.associateBy { it.id }
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
        }
    }

    fun song(id: Long?): Song? = id?.let { songById[it] }

    /** Songs shown for a song-list category of the library. */
    fun songsFor(cat: Category, stats: Stats): List<Song> {
        val all = _library.value.songs
        return when (cat) {
            Category.ALL_SONGS -> all
            Category.MOST_PLAYED -> all.filter { (stats.plays[it.id] ?: 0) > 0 }
                .sortedByDescending { stats.plays[it.id] ?: 0 }.take(100)
            Category.TOP_RATED -> all.filter { stats.rating(it.id) > 0 }
            Category.LOW_RATED -> all.filter { stats.rating(it.id) < 0 }
            Category.RECENTLY_PLAYED -> all.filter { stats.lastPlayed.containsKey(it.id) }
                .sortedByDescending { stats.lastPlayed[it.id] ?: 0L }.take(100)
            Category.RECENTLY_ADDED -> all.sortedByDescending { it.dateAdded }.take(100)
            Category.LONG -> all.filter { it.durationMs >= LONG_TRACK_MS }.sortedByDescending { it.durationMs }
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
        _library.value.albums.firstOrNull { it.key == "album:${song.albumId}" }?.let { openGroup(it, "Albums") }
    }

    fun goToArtist(song: Song) {
        _library.value.artists.firstOrNull { it.key == "artist:${song.artist}" }?.let { openGroup(it, "Artists") }
    }

    fun goToFolder(song: Song) {
        _library.value.folders.firstOrNull { it.key == "folder:${song.folder}" }?.let { openGroup(it, "Folders") }
    }

    // ---- playback controls ----

    fun play(songs: List<Song>, startIndex: Int, shuffle: Boolean = false) {
        val c = controller ?: return
        if (songs.isEmpty()) return
        val index = startIndex.coerceIn(0, songs.lastIndex)
        c.shuffleModeEnabled = shuffle
        c.setMediaItems(songs.map { it.toMediaItem() }, index, 0L)
        c.prepare()
        c.play()
    }

    fun shuffleAll(songs: List<Song>) {
        if (songs.isEmpty()) return
        play(songs, songs.indices.random(), shuffle = true)
    }

    fun playNext(songs: List<Song>) {
        val c = controller ?: return
        if (songs.isEmpty()) return
        if (c.mediaItemCount == 0) {
            play(songs, 0); return
        }
        c.addMediaItems(c.currentMediaItemIndex + 1, songs.map { it.toMediaItem() })
    }

    fun addToQueue(songs: List<Song>) {
        val c = controller ?: return
        if (songs.isEmpty()) return
        if (c.mediaItemCount == 0) {
            play(songs, 0); return
        }
        c.addMediaItems(songs.map { it.toMediaItem() })
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

    fun togglePlay() {
        val c = controller ?: return
        if (c.isPlaying) {
            c.pause()
        } else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            c.play()
        }
    }

    fun next() {
        controller?.seekToNextMediaItem()
    }

    fun previous() {
        val c = controller ?: return
        // Like most players: restart the song if more than 3 seconds in
        if (c.currentPosition > 3000 || !c.hasPreviousMediaItem()) c.seekTo(0) else c.seekToPreviousMediaItem()
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
        val c = controller ?: return
        c.shuffleModeEnabled = !c.shuffleModeEnabled
    }

    fun cycleRepeat() {
        val c = controller ?: return
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
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

    // ---- sleep timer ----

    fun setSleepTimer(minutes: Int) = startSleep(minutes * 60_000L)

    fun sleepAtEndOfTrack() {
        val c = controller ?: return
        val remaining = (durationOf(c) - c.currentPosition).coerceAtLeast(1_000L)
        startSleep(remaining)
    }

    fun cancelSleep() {
        sleepJob?.cancel()
        sleepJob = null
        _player.value = _player.value.copy(sleepEndsAt = null)
    }

    private fun startSleep(ms: Long) {
        sleepJob?.cancel()
        _player.value = _player.value.copy(sleepEndsAt = System.currentTimeMillis() + ms)
        sleepJob = viewModelScope.launch {
            delay(ms)
            controller?.pause()
            _player.value = _player.value.copy(sleepEndsAt = null)
        }
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
                    artist = mi.mediaMetadata.artist?.toString() ?: ""
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
        if (id != null && c.isPlaying && id != lastRecordedId) {
            lastRecordedId = id
            StatsStore.recordPlay(id)
        }
        updateAudioInfo(id)
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
        controller?.removeListener(listener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null
        super.onCleared()
    }
}

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
                .build()
        )
        .build()
