package com.powersound.player.data

import java.nio.charset.Charset

/**
 * Settings → Library → Scanner → Fix Arabic Names.
 *
 * Many old Arabic MP3s keep their tags in the Arabic Windows code page (1256) without saying so; Android reads
 * those bytes as Latin letters and the names show as "ÇáíÓÇ". Turning the letters back into their bytes and
 * reading them as Windows-1256 gives the real name ("اليسا"). Only text that clearly becomes Arabic is changed.
 */
object ArabicFix {

    private val cp1256: Charset? = runCatching { Charset.forName("windows-1256") }.getOrNull()

    /** The repaired text, or null when [s] doesn't look like broken Arabic. */
    fun fix(s: String): String? {
        val cs = cp1256 ?: return null
        if (s.none { it.code in 0xC0..0xFF }) return null
        // real Unicode text (or already Arabic) is never touched
        if (s.any { it.code > 0xFF }) return null
        val fixed = String(s.toByteArray(Charsets.ISO_8859_1), cs)
        // one byte = one letter in both code pages: look only at the letters that were not plain English
        val high = s.indices.filter { s[it].code >= 0x80 }
        val arabic = high.count { i -> fixed.getOrNull(i)?.let { it in '؀'..'ۿ' || it == '‌' || it == '‍' } == true }
        return if (fixed.length == s.length && arabic >= 2 && arabic * 10 >= high.size * 7) fixed.trim() else null
    }

    /** The tag changes that repair [song], or null when nothing is broken. */
    fun editsFor(song: Song): TagEdits? {
        fun f(v: String, skip: Boolean = false) = if (skip || v.isBlank()) null else fix(v)
        val title = f(song.title)
        val artist = f(song.artist, song.artist == "Unknown artist")
        val album = f(song.album, song.album == "Unknown album")
        val albumArtist = f(song.albumArtist, !song.hasAlbumArtist)
        val genre = f(song.genre)
        val composer = f(song.composer)
        if (listOf(title, artist, album, albumArtist, genre, composer).all { it == null }) return null
        return TagEdits(title = title, artist = artist, album = album, albumArtist = albumArtist, genre = genre, composer = composer)
    }
}
