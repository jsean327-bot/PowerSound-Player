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
    val durationMs: Long,
    val track: Int,
    val folder: String,
    val mimeType: String,
    val size: Long
) {
    val uri: Uri
        get() = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)

    val formatLabel: String
        get() = formatFromMime(mimeType)

    /** Track number without the disc prefix (MediaStore stores disc * 1000 + track). */
    val trackNumber: Int
        get() = track % 1000
}

data class Album(val id: Long, val name: String, val artist: String, val songs: List<Song>)

data class Artist(val name: String, val songs: List<Song>)

data class Folder(val path: String, val name: String, val songs: List<Song>)

/** A song list opened from the library (album, artist or folder). */
data class Detail(val title: String, val subtitle: String, val coverSongId: Long?, val songs: List<Song>)

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
