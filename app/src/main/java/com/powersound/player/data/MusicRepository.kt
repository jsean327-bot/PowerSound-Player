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
        val q = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val r = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

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
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.COMPOSER,
            MediaStore.Audio.Media.DATE_ADDED
        )
        if (q) projection.add(MediaStore.Audio.Media.RELATIVE_PATH)
        if (r) {
            projection.add("album_artist")
            projection.add("genre")
        }

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
            val yearCol = c.getColumnIndex(MediaStore.Audio.Media.YEAR)
            val composerCol = c.getColumnIndex(MediaStore.Audio.Media.COMPOSER)
            val addedCol = c.getColumnIndex(MediaStore.Audio.Media.DATE_ADDED)
            val relCol = if (q) c.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH) else -1
            val albumArtistCol = if (r) c.getColumnIndex("album_artist") else -1
            val genreCol = if (r) c.getColumnIndex("genre") else -1

            while (c.moveToNext()) {
                val data = if (dataCol >= 0) c.getString(dataCol) else null
                val rel = if (relCol >= 0) c.getString(relCol) else null
                val folder = when {
                    !rel.isNullOrBlank() -> rel.trim('/')
                    !data.isNullOrBlank() -> File(data).parent ?: ""
                    else -> ""
                }.ifBlank { "Music" }

                val artist = clean(c.getString(artistCol)) ?: "Unknown artist"
                val album = clean(c.getString(albumCol)) ?: "Unknown album"
                val title = clean(c.getString(titleCol))
                    ?: data?.let { File(it).nameWithoutExtension } ?: "Unknown"
                val albumArtist = (if (albumArtistCol >= 0) clean(c.getString(albumArtistCol)) else null) ?: artist

                songs.add(
                    Song(
                        id = c.getLong(idCol),
                        title = title,
                        artist = artist,
                        album = album,
                        albumId = c.getLong(albumIdCol),
                        albumArtist = albumArtist,
                        durationMs = c.getLong(durCol),
                        track = c.getInt(trackCol),
                        folder = folder,
                        mimeType = c.getString(mimeCol) ?: "",
                        size = c.getLong(sizeCol),
                        year = if (yearCol >= 0) c.getInt(yearCol) else 0,
                        composer = (if (composerCol >= 0) clean(c.getString(composerCol)) else null) ?: "",
                        genre = (if (genreCol >= 0) clean(c.getString(genreCol)) else null) ?: "",
                        dateAdded = if (addedCol >= 0) c.getLong(addedCol) else 0L,
                        path = data ?: ""
                    )
                )
            }
        }
        songs
    }

    private fun clean(s: String?): String? = s?.trim()?.takeUnless { it.isEmpty() || it == "<unknown>" }

    private fun songsLabel(n: Int) = if (n == 1) "1 song" else "$n songs"

    private fun artistsLabel(list: List<Song>): String {
        val a = list.map { it.artist }.distinct()
        return if (a.size == 1) a.first() else "Various artists"
    }

    private val byTrack = compareBy<Song>({ it.album.lowercase() }, { it.track }, { it.title.lowercase() })

    // ---------------------------------------------------------------- Settings → Library helpers

    /** Sort key: "The Beatles" sorts as "beatles" unless "Don't Ignore Articles For Sort" is on. */
    fun sortKey(title: String): String {
        val t = title.trim().lowercase()
        if (SettingsStore.value.keepArticles) return t
        for (a in listOf("the ", "a ", "an ")) if (t.startsWith(a) && t.length > a.length) return t.substring(a.length)
        return t
    }

    /** Splits "A; B" into names by the symbols of Settings → Scanner; names in Unsplit Artists stay whole. */
    fun splitNames(value: String, symbols: String, unsplit: Set<String> = emptySet()): List<String> {
        val v = value.trim()
        if (symbols.isBlank() || v.isEmpty() || unsplit.any { it.equals(v, ignoreCase = true) }) return listOf(v)
        // protect the unsplit names inside a longer value
        var work = v
        val keep = unsplit.filter { it.isNotBlank() && work.contains(it, ignoreCase = true) }
        keep.forEachIndexed { i, u -> work = work.replace(u, "\u0000$i\u0000", ignoreCase = true) }
        val parts = work.split(*symbols.split(' ').filter { it.isNotEmpty() }.flatMap { tok -> if (tok.length > 1 && !tok.all { !it.isLetterOrDigit() }) listOf(tok) else tok.map { it.toString() } }.toTypedArray())
            .map { p ->
                var r = p
                keep.forEachIndexed { i, u -> r = r.replace("\u0000$i\u0000", u) }
                r.trim()
            }
            .filter { it.isNotEmpty() }
        return parts.ifEmpty { listOf(v) }
    }

    /** Groups songs by one or more names each (split artists / composers / genres). */
    private fun multiGroup(
        songs: List<Song>,
        prefix: String,
        nameOf: (Song) -> String,
        symbols: String,
        unsplit: Set<String>,
        subtitle: (List<Song>) -> String
    ): List<Group> {
        val map = LinkedHashMap<String, ArrayList<Song>>()
        val showCombined = SettingsStore.value.showUnsplit
        for (s in songs) {
            val full = nameOf(s)
            val names = splitNames(full, symbols, unsplit)
            for (n in names) map.getOrPut(n) { ArrayList() }.add(s)
            if (showCombined && names.size > 1) map.getOrPut(full) { ArrayList() }.add(s)
        }
        return map.map { (name, list) -> Group("$prefix:$name", name, subtitle(list), list.sortedWith(byTrack)) }
            .sortedBy { sortKey(it.title) }
    }

    fun folders(songs: List<Song>): List<Group> =
        songs.groupBy { it.folder }.map { (path, list) ->
            Group(
                key = "folder:$path",
                title = path.substringAfterLast('/').ifBlank { path },
                subtitle = artistsLabel(list),
                songs = list.sortedWith(byTrack)
            )
        }.sortedBy { sortKey(it.title) }

    /**
     * Albums. "Join Albums" (on): tracks with the same album name and album artist are one album, even when
     * Android gave them different album ids; tracks without an album artist tag join by name.
     */
    fun albums(songs: List<Song>): List<Group> {
        val st = SettingsStore.value
        val grouped: Map<Any, List<Song>> = if (st.joinAlbums) {
            songs.groupBy { s -> s.album.lowercase().trim() + "\u0001" + (if (s.albumArtist != s.artist) s.albumArtist.lowercase() else "") }
        } else songs.groupBy { it.albumId }
        return grouped.map { (_, list) ->
            val first = list.first()
            // Album Artist Label for Albums: album artist (and sort by it) instead of the track artist
            val names = if (st.albumArtistAlbums) list.map { it.albumArtist }.distinct() else list.map { it.artist }.distinct()
            Group(
                key = "album:${first.albumId}",
                title = first.album,
                subtitle = if (names.size == 1) names.first() else "Various artists",
                songs = list.sortedWith(compareBy({ it.track }, { it.title.lowercase() }))
            )
        }.let { list ->
            if (st.albumArtistAlbums) list.sortedWith(compareBy({ sortKey(it.subtitle) }, { sortKey(it.title) }))
            else list.sortedBy { sortKey(it.title) }
        }
    }

    fun artists(songs: List<Song>): List<Group> {
        val st = SettingsStore.value
        return multiGroup(songs, "artist", { it.artist }, st.splitArtists, st.unsplitArtists) { songsLabel(it.size) }
    }

    fun albumArtists(songs: List<Song>): List<Group> {
        val st = SettingsStore.value
        return multiGroup(songs, "albumartist", { it.albumArtist }, st.splitArtists, st.unsplitArtists) { list ->
            val albums = list.map { it.albumId }.distinct().size
            if (albums == 1) songsLabel(list.size) else "$albums albums"
        }
    }

    fun genres(songs: List<Song>): List<Group> {
        val st = SettingsStore.value
        return multiGroup(songs, "genre", { it.genre.ifBlank { "Unknown genre" } }, st.splitGenres, emptySet()) { songsLabel(it.size) }
    }

    fun years(songs: List<Song>): List<Group> =
        songs.groupBy { it.year }.map { (year, list) ->
            Group(
                "year:$year",
                if (year > 0) year.toString() else "Unknown year",
                songsLabel(list.size),
                list.sortedWith(byTrack)
            )
        }.sortedByDescending { it.songs.first().year }

    fun composers(songs: List<Song>): List<Group> {
        val st = SettingsStore.value
        return multiGroup(songs, "composer", { it.composer.ifBlank { "Unknown composer" } }, st.splitComposers, emptySet()) { songsLabel(it.size) }
    }

    /**
     * Folder tree: sub-folders of [path] (each with every song below it) and the songs
     * stored directly in [path]. An empty path is the top level.
     */
    fun treeChildren(songs: List<Song>, path: String): Pair<List<Group>, List<Song>> {
        val subs = LinkedHashMap<String, ArrayList<Song>>()
        val direct = ArrayList<Song>()
        for (s in songs) {
            val f = s.folder
            val rest = when {
                path.isEmpty() -> f
                f == path -> {
                    direct.add(s); continue
                }
                f.startsWith("$path/") -> f.removePrefix("$path/")
                else -> continue
            }
            val seg = rest.substringBefore('/')
            val sub = if (path.isEmpty()) seg else "$path/$seg"
            subs.getOrPut(sub) { ArrayList() }.add(s)
        }
        val groups = subs.map { (sub, list) ->
            Group("tree:$sub", sub.substringAfterLast('/'), songsLabel(list.size), list.sortedWith(byTrack))
        }.sortedBy { sortKey(it.title) }
        return groups to direct.sortedBy { sortKey(it.title) }
    }
}
