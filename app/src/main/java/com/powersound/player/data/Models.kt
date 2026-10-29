package com.powersound.player.data

import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val albumArtist: String,
    val durationMs: Long,
    val track: Int,
    val folder: String,
    val mimeType: String,
    val size: Long,
    val year: Int,
    val composer: String,
    val genre: String,
    val dateAdded: Long,
    /** Full path of the file (may be empty on some phones). */
    val path: String = ""
) {
    /** File name without extension, for "Filename as title". */
    val fileName: String
        get() = path.substringAfterLast('/').substringBeforeLast('.').ifBlank { title }

    val uri: Uri
        get() = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)

    val formatLabel: String
        get() = formatFromMime(mimeType)

    /** Track number without the disc prefix (MediaStore stores disc * 1000 + track). */
    val trackNumber: Int
        get() = track % 1000
}

/** Any list of songs shown as one tile or page: a folder, album, artist, genre, year... */
data class Group(
    val key: String,
    val title: String,
    val subtitle: String,
    val songs: List<Song>
) {
    val coverId: Long? get() = songs.firstOrNull()?.id
    val totalMs: Long get() = songs.sumOf { it.durationMs }
}

enum class Kind { SONGS, GROUPS, TREE, QUEUE, BOOKMARKS, STREAMS }

enum class Category(val label: String, val kind: Kind) {
    ALL_SONGS("All Songs", Kind.SONGS),
    FOLDERS("Folders", Kind.GROUPS),
    FOLDERS_HIERARCHY("Folders Hierarchy", Kind.TREE),
    ALBUMS("Albums", Kind.GROUPS),
    ARTISTS("Artists", Kind.GROUPS),
    ALBUM_ARTISTS("Album Artists", Kind.GROUPS),
    GENRES("Genres", Kind.GROUPS),
    YEARS("Years", Kind.GROUPS),
    COMPOSERS("Composers", Kind.GROUPS),
    PLAYLISTS("Playlists", Kind.GROUPS),
    STREAMS("Streams", Kind.STREAMS),
    QUEUE("Queue", Kind.QUEUE),
    BOOKMARKS("Bookmarks", Kind.BOOKMARKS),
    MOST_PLAYED("Most Played", Kind.SONGS),
    TOP_RATED("Top Rated", Kind.SONGS),
    LOW_RATED("Low Rated", Kind.SONGS),
    RECENTLY_PLAYED("Recently Played", Kind.SONGS),
    RECENTLY_ADDED("Recently Added", Kind.SONGS),
    LONG("Long", Kind.SONGS)
}

/** Songs of 10 minutes or more count as "Long". */
const val LONG_TRACK_MS = 10 * 60 * 1000L

fun formatFromMime(mime: String?): String {
    val m = mime?.lowercase() ?: return ""
    return when {
        m.contains("flac") -> "FLAC"
        m.contains("alac") -> "ALAC"
        m.contains("mpeg") || m.contains("mp3") -> "MP3"
        m.contains("mp4a") || m.contains("aac") -> "AAC"
        m.contains("mp4") || m.contains("m4a") -> "M4A"
        m.contains("opus") -> "OPUS"
        m.contains("ogg") || m.contains("vorbis") -> "OGG"
        m.contains("wav") || m.contains("raw") -> "WAV"
        m.contains("ape") -> "APE"
        m.contains("dsd") || m.contains("dsf") -> "DSD"
        m.contains("wma") || m.contains("ms-wma") -> "WMA"
        m.contains("aiff") -> "AIFF"
        else -> m.substringAfter('/').uppercase().take(5)
    }
}

fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** Letter used by the A–Z fast scroller. */
fun letterKey(text: String): String {
    val c = text.trim().firstOrNull() ?: return "#"
    val up = c.uppercaseChar()
    return when {
        up in 'A'..'Z' -> up.toString()
        c in '؀'..'ۿ' -> "ع"
        else -> "#"
    }
}
