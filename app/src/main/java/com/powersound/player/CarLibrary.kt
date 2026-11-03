package com.powersound.player

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.powersound.player.data.Group
import com.powersound.player.data.LibraryStores
import com.powersound.player.data.MusicRepository
import com.powersound.player.data.SettingsStore
import com.powersound.player.data.Song

/**
 * The library as Android Auto (and other car / watch apps) browse it:
 *
 *   PowerSound
 *   ├─ Folders / Albums / Artists / Playlists → their songs
 *   ├─ Recently added
 *   └─ All songs
 *
 * A song picked in a list plays with the rest of that list, like in the app.
 * Ids: "root", "cat:<name>", "g:<group key>", and "s:<list id>|<song id>" for songs.
 */
class CarLibrary(private val context: Context) {

    private var songs: List<Song> = emptyList()
    private var loadedAt = 0L
    private var groups: Map<String, List<Group>> = emptyMap()

    private val categories = listOf(
        "folders" to "Folders", "albums" to "Albums", "artists" to "Artists",
        "playlists" to "Playlists", "recent" to "Recently added", "songs" to "All songs"
    )

    /** Reads the library again when it is older than a minute (the same filters as the app). */
    private suspend fun ensure() {
        if (songs.isNotEmpty() && System.currentTimeMillis() - loadedAt < 60_000) return
        val st = SettingsStore.value
        songs = MusicRepository.loadSongs(context).filter { s ->
            s.durationMs >= st.minDurationSec * 1000L &&
                !(st.ignoreVideo && s.mimeType.startsWith("video/")) &&
                st.excludedFolders.none { ex -> s.folder == ex || s.folder.startsWith("$ex/") }
        }
        val byId = songs.associateBy { it.id }
        groups = mapOf(
            "folders" to MusicRepository.folders(songs),
            "albums" to MusicRepository.albums(songs),
            "artists" to MusicRepository.artists(songs),
            "playlists" to LibraryStores.playlists.value.map { p ->
                val list = p.songIds.mapNotNull { byId[it] }
                Group("playlist:${p.id}", p.name, if (list.size == 1) "1 song" else "${list.size} songs", list)
            }
        )
        loadedAt = System.currentTimeMillis()
    }

    fun root(): MediaItem = folder("root", "PowerSound", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)

    suspend fun children(parentId: String): List<MediaItem> {
        ensure()
        return when {
            parentId == "root" -> categories.map { (id, name) ->
                folder(
                    "cat:$id", name,
                    when (id) {
                        "albums" -> MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS
                        "artists" -> MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS
                        "playlists" -> MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS
                        else -> MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
                    }
                )
            }
            parentId.startsWith("cat:") -> {
                val cat = parentId.removePrefix("cat:")
                when (cat) {
                    "songs" -> songs.sortedBy { MusicRepository.sortKey(it.title) }.map { song(it, parentId) }
                    "recent" -> recent().map { song(it, parentId) }
                    else -> groups[cat].orEmpty().map { g ->
                        folder(
                            "g:${g.key}", g.title,
                            when (cat) {
                                "albums" -> MediaMetadata.MEDIA_TYPE_ALBUM
                                "artists" -> MediaMetadata.MEDIA_TYPE_ARTIST
                                "playlists" -> MediaMetadata.MEDIA_TYPE_PLAYLIST
                                else -> MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
                            },
                            g.subtitle
                        )
                    }
                }
            }
            parentId.startsWith("g:") -> group(parentId.removePrefix("g:"))?.songs.orEmpty().map { song(it, parentId) }
            else -> emptyList()
        }
    }

    suspend fun item(id: String): MediaItem? {
        if (id == "root") return root()
        ensure()
        categories.firstOrNull { "cat:${it.first}" == id }?.let { return folder(id, it.second, MediaMetadata.MEDIA_TYPE_FOLDER_MIXED) }
        if (id.startsWith("g:")) return group(id.removePrefix("g:"))?.let { folder(id, it.title, MediaMetadata.MEDIA_TYPE_FOLDER_MIXED, it.subtitle) }
        val songId = songIdOf(id) ?: return null
        return songs.firstOrNull { it.id == songId }?.let { song(it, id.substringAfter("s:").substringBefore('|')) }
    }

    /**
     * What to play for a song picked in the car: the whole list it was picked in, starting at that song.
     * Null when [id] isn't a car song id.
     */
    suspend fun playlistFor(id: String): Pair<List<MediaItem>, Int>? {
        if (!id.startsWith("s:")) return null
        ensure()
        val listId = id.removePrefix("s:").substringBefore('|')
        val songId = songIdOf(id) ?: return null
        val list = when {
            listId == "cat:songs" -> songs.sortedBy { MusicRepository.sortKey(it.title) }
            listId == "cat:recent" -> recent()
            listId.startsWith("g:") -> group(listId.removePrefix("g:"))?.songs.orEmpty()
            else -> emptyList()
        }.ifEmpty { songs.filter { it.id == songId } }
        if (list.isEmpty()) return null
        return list.map { playable(it) } to list.indexOfFirst { it.id == songId }.coerceAtLeast(0)
    }

    /** The song id inside a car id ("s:<list>|<id>"), or a plain numeric id. */
    fun songIdOf(id: String): Long? = if (id.startsWith("s:")) id.substringAfterLast('|').toLongOrNull() else id.toLongOrNull()

    private fun recent(): List<Song> = songs.sortedByDescending { it.dateAdded }.take(100)

    private fun group(key: String): Group? = groups.values.asSequence().flatten().firstOrNull { it.key == key }

    private fun folder(id: String, title: String, type: Int, subtitle: String? = null): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(type)
                    .build()
            )
            .build()

    private fun song(s: Song, listId: String): MediaItem =
        MediaItem.Builder()
            .setMediaId("s:$listId|${s.id}")
            .setMediaMetadata(meta(s).buildUpon().setIsBrowsable(false).setIsPlayable(true).build())
            .build()

    /** The item the player gets: the same as the app's own queue items. */
    fun playable(s: Song): MediaItem {
        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, s.id)
        return MediaItem.Builder()
            .setMediaId(s.id.toString())
            .setUri(uri)
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
            .setMediaMetadata(meta(s).buildUpon().setArtworkUri(android.net.Uri.parse("$ART_SCHEME:${s.id}")).build())
            .build()
    }

    private fun meta(s: Song): MediaMetadata =
        MediaMetadata.Builder()
            .setTitle(s.title)
            .setArtist(s.artist)
            .setAlbumTitle(s.album)
            .setAlbumArtist(s.albumArtist)
            .setTrackNumber(s.trackNumber)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .build()
}
