package com.powersound.player.data

import android.content.Context
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object MusicRepository {

    /** Reads every music file Android has indexed on the device. */
    suspend fun loadSongs(context: Context): List<Song> = withContext(Dispatchers.IO) {
        val songs = ArrayList<Song>()
        val hasRelativePath = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

        val projection = arrayListOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATA
        )
        if (hasRelativePath) projection.add(MediaStore.Audio.Media.RELATIVE_PATH)

        val cursor = try {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection.toTypedArray(),
                "${MediaStore.Audio.Media.IS_MUSIC} != 0",
                null,
                "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
            )
        } catch (e: Exception) {
            null
        }

        cursor?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumIdCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val durCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val trackCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
            val mimeCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val dataCol = c.getColumnIndex(MediaStore.Audio.Media.DATA)
            val relCol = if (hasRelativePath) c.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH) else -1

            while (c.moveToNext()) {
                val data = if (dataCol >= 0) c.getString(dataCol) else null
                val rel = if (relCol >= 0) c.getString(relCol) else null
                val folder = when {
                    !rel.isNullOrBlank() -> rel.trim('/')
                    !data.isNullOrBlank() -> File(data).parent ?: ""
                    else -> ""
                }.ifBlank { "Music" }

                val artist = c.getString(artistCol)
                    ?.takeUnless { it.isBlank() || it == "<unknown>" } ?: "Unknown artist"
                val album = c.getString(albumCol)
                    ?.takeUnless { it.isBlank() || it == "<unknown>" } ?: "Unknown album"
                val title = c.getString(titleCol)?.takeUnless { it.isBlank() }
                    ?: data?.let { File(it).nameWithoutExtension } ?: "Unknown"

                songs.add(
                    Song(
                        id = c.getLong(idCol),
                        title = title,
                        artist = artist,
                        album = album,
                        albumId = c.getLong(albumIdCol),
                        durationMs = c.getLong(durCol),
                        track = c.getInt(trackCol),
                        folder = folder,
                        mimeType = c.getString(mimeCol) ?: "",
                        size = c.getLong(sizeCol)
                    )
                )
            }
        }
        songs
    }

    fun groupAlbums(songs: List<Song>): List<Album> =
        songs.groupBy { it.albumId }.map { (id, list) ->
            val artists = list.map { it.artist }.distinct()
            Album(
                id = id,
                name = list.first().album,
                artist = if (artists.size == 1) artists.first() else "Various artists",
                songs = list.sortedWith(compareBy({ it.track }, { it.title.lowercase() }))
            )
        }.sortedBy { it.name.lowercase() }

    fun groupArtists(songs: List<Song>): List<Artist> =
        songs.groupBy { it.artist }.map { (name, list) ->
            Artist(name, list.sortedWith(compareBy({ it.album.lowercase() }, { it.track })))
        }.sortedBy { it.name.lowercase() }

    fun groupFolders(songs: List<Song>): List<Folder> =
        songs.groupBy { it.folder }.map { (path, list) ->
            Folder(
                path = path,
                name = path.substringAfterLast('/').ifBlank { path },
                songs = list.sortedBy { it.title.lowercase() }
            )
        }.sortedBy { it.path.lowercase() }
}
