package com.powersound.player

import android.app.Application
import android.content.ComponentName
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.powersound.player.data.Album
import com.powersound.player.data.Artist
import com.powersound.player.data.AudioInfo
import com.powersound.player.data.Folder
import com.powersound.player.data.MusicRepository
import com.powersound.player.data.Song
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

data class PlayerUiState(
    val nowPlaying: NowPlaying? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val audioInfo: String = ""
)

data class LibraryState(
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val songs: List<Song> = emptyList(),
    val albums: List<Album> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val folders: List<Folder> = emptyList()
)

class PlayerViewModel(app: Application) : AndroidViewModel(app) {

    private val _library = MutableStateFlow(LibraryState())
    val library: StateFlow<LibraryState> = _library.asStateFlow()

    private val _player = MutableStateFlow(PlayerUiState())
    val player: StateFlow<PlayerUiState> = _player.asStateFlow()

    private var songById: Map<Long, Song> = emptyMap()
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var infoJob: Job? = null
    private var infoForId: Long? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            refresh()
        }
    }

    init {
        com.powersound.player.audio.AudioEngine.init(app)
        connect()
        viewModelScope.launch {
            while (isActive) {
                val c = controller
                if (c != null && c.isPlaying) {
                    _player.value = _player.value.copy(
                        positionMs = c.currentPosition,
                        durationMs = durationOf(c)
                    )
                }
                delay(500)
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
                refresh()
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
                albums = MusicRepository.groupAlbums(songs),
                artists = MusicRepository.groupArtists(songs),
                folders = MusicRepository.groupFolders(songs)
            )
            infoForId = null // re-read audio info now that file sizes are known
            refresh()
        }
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
        _player.value = _player.value.copy(positionMs = positionMs)
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

    // ---- state ----

    private fun refresh() {
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
        _player.value = _player.value.copy(
            nowPlaying = nowPlaying,
            isPlaying = c.isPlaying,
            positionMs = c.currentPosition,
            durationMs = durationOf(c),
            shuffle = c.shuffleModeEnabled,
            repeatMode = c.repeatMode
        )
        updateAudioInfo(nowPlaying?.songId)
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
                .setAlbumArtist(artist)
                .build()
        )
        .build()
