package com.powersound.player.data

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.nio.charset.Charset

/** One line of lyrics; [timeMs] is set when the lyrics are synced (LRC). */
data class LyricLine(val timeMs: Long?, val text: String)

data class Lyrics(val lines: List<LyricLine>) {
    val synced: Boolean get() = lines.any { it.timeMs != null }
}

/**
 * Reads lyrics embedded in the song file:
 *  • FLAC / OGG-style Vorbis comments: LYRICS= or UNSYNCEDLYRICS=
 *  • MP3 ID3v2: USLT frame
 * Both plain text and LRC ("[01:23.45] line") are supported.
 */
object LyricsReader {

    suspend fun read(context: Context, songId: Long): Lyrics? = withContext(Dispatchers.IO) {
        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, songId)
        val raw = try {
            context.contentResolver.openInputStream(uri)?.use { readEmbedded(it) }
        } catch (_: Exception) {
            null
        }
        raw?.takeIf { it.isNotBlank() }?.let { parse(it) }
    }

    /** Public for testing: returns the raw lyrics text found in the stream, or null. */
    fun readEmbedded(input: InputStream): String? {
        val head = ByteArray(10)
        if (!readFully(input, head, 10)) return null
        return when {
            head[0] == 'f'.code.toByte() && head[1] == 'L'.code.toByte() &&
                head[2] == 'a'.code.toByte() && head[3] == 'C'.code.toByte() -> readFlac(input, head)
            head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte() -> readId3(input, head)
            else -> null
        }
    }

    // ---------------------------------------------------------------- FLAC

    private fun readFlac(input: InputStream, head: ByteArray): String? {
        // we already consumed "fLaC" + 6 bytes of the first block header/data
        var pending = head.copyOfRange(4, 10) // 4-byte block header + 2 bytes of body
        var first = true
        while (true) {
            val hdr = if (first) pending.copyOfRange(0, 4) else ByteArray(4).also { if (!readFully(input, it, 4)) return null }
            val last = (hdr[0].toInt() and 0x80) != 0
            val type = hdr[0].toInt() and 0x7F
            val len = ((hdr[1].toInt() and 0xFF) shl 16) or ((hdr[2].toInt() and 0xFF) shl 8) or (hdr[3].toInt() and 0xFF)
            val already = if (first) 2 else 0
            if (type == 4) {
                if (len > 8 * 1024 * 1024) return null
                val body = ByteArray(len)
                if (already > 0) System.arraycopy(pending, 4, body, 0, already)
                if (!readFully(input, body, len - already, already)) return null
                return vorbisLyrics(body)
            } else {
                skipFully(input, (len - already).toLong())
            }
            first = false
            pending = ByteArray(0)
            if (last) return null
        }
    }

    private fun vorbisLyrics(b: ByteArray): String? {
        var p = 0
        fun le32(): Int {
            val v = (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8) or
                ((b[p + 2].toInt() and 0xFF) shl 16) or ((b[p + 3].toInt() and 0xFF) shl 24)
            p += 4
            return v
        }
        if (b.size < 8) return null
        val vendor = le32()
        p += vendor
        if (p + 4 > b.size) return null
        val count = le32()
        var plain: String? = null
        for (i in 0 until count) {
            if (p + 4 > b.size) break
            val n = le32()
            if (n < 0 || p + n > b.size) break
            val c = String(b, p, n, Charsets.UTF_8)
            p += n
            val eq = c.indexOf('=')
            if (eq <= 0) continue
            val key = c.substring(0, eq).uppercase()
            if (key == "LYRICS") return c.substring(eq + 1)
            if (key == "UNSYNCEDLYRICS" || key == "UNSYNCED LYRICS") plain = c.substring(eq + 1)
        }
        return plain
    }

    // ---------------------------------------------------------------- ID3v2

    private fun readId3(input: InputStream, head: ByteArray): String? {
        val major = head[3].toInt()
        val size = syncSafe(head, 6)
        if (size <= 0 || size > 16 * 1024 * 1024) return null
        val tag = ByteArray(size)
        if (!readFully(input, tag, size)) return null
        var p = 0
        val frameHeader = if (major == 2) 6 else 10
        while (p + frameHeader <= tag.size) {
            val id: String
            val len: Int
            if (major == 2) {
                id = String(tag, p, 3, Charsets.ISO_8859_1)
                len = ((tag[p + 3].toInt() and 0xFF) shl 16) or ((tag[p + 4].toInt() and 0xFF) shl 8) or (tag[p + 5].toInt() and 0xFF)
            } else {
                id = String(tag, p, 4, Charsets.ISO_8859_1)
                len = if (major >= 4) syncSafe(tag, p + 4) else
                    ((tag[p + 4].toInt() and 0xFF) shl 24) or ((tag[p + 5].toInt() and 0xFF) shl 16) or
                        ((tag[p + 6].toInt() and 0xFF) shl 8) or (tag[p + 7].toInt() and 0xFF)
            }
            if (id[0] == '\u0000' || len <= 0 || p + frameHeader + len > tag.size) break
            val start = p + frameHeader
            if (id == "USLT" || id == "ULT") return usltText(tag, start, len)
            if (id == "TXXX" || id == "TXX") txxxLyrics(tag, start, len)?.let { return it }
            p = start + len
        }
        return null
    }

    private fun usltText(b: ByteArray, start: Int, len: Int): String? {
        if (len < 5) return null
        val enc = b[start].toInt()
        var p = start + 4 // encoding + 3-byte language
        val end = start + len
        // skip the content descriptor (terminated string)
        val wide = enc == 1 || enc == 2
        if (wide) {
            while (p + 1 < end && !(b[p].toInt() == 0 && b[p + 1].toInt() == 0)) p += 2
            p += 2
        } else {
            while (p < end && b[p].toInt() != 0) p++
            p += 1
        }
        if (p >= end) return null
        val cs: Charset = when (enc) {
            0 -> Charsets.ISO_8859_1
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            else -> Charsets.UTF_8
        }
        return String(b, p, end - p, cs).trimEnd('\u0000')
    }

    /** TXXX frame with description LYRICS / UNSYNCEDLYRICS / USLT (written by some taggers). */
    private fun txxxLyrics(b: ByteArray, start: Int, len: Int): String? {
        if (len < 3) return null
        val enc = b[start].toInt()
        val end = start + len
        val wide = enc == 1 || enc == 2
        var p = start + 1
        val descStart = p
        if (wide) {
            while (p + 1 < end && !(b[p].toInt() == 0 && b[p + 1].toInt() == 0)) p += 2
        } else {
            while (p < end && b[p].toInt() != 0) p++
        }
        val cs: Charset = when (enc) {
            0 -> Charsets.ISO_8859_1
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            else -> Charsets.UTF_8
        }
        val desc = String(b, descStart, p - descStart, cs).trim().uppercase()
        p += if (wide) 2 else 1
        if (p >= end) return null
        if (desc != "LYRICS" && desc != "UNSYNCEDLYRICS" && desc != "UNSYNCED LYRICS" && desc != "USLT") return null
        return String(b, p, end - p, cs).trimEnd('\u0000')
    }

    private fun syncSafe(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0x7F) shl 21) or ((b[o + 1].toInt() and 0x7F) shl 14) or
            ((b[o + 2].toInt() and 0x7F) shl 7) or (b[o + 3].toInt() and 0x7F)

    // ---------------------------------------------------------------- LRC

    private val timeTag = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]""")

    fun parse(text: String): Lyrics {
        val out = ArrayList<LyricLine>()
        text.replace("\r\n", "\n").replace('\r', '\n').split('\n').forEach { rawLine ->
            val matches = timeTag.findAll(rawLine).toList()
            if (matches.isEmpty()) {
                // skip LRC metadata lines like [ar:Artist]
                if (!Regex("""^\[[a-zA-Z]+:.*]$""").matches(rawLine.trim())) out.add(LyricLine(null, rawLine.trim()))
            } else {
                val lineText = rawLine.substring(matches.last().range.last + 1).trim()
                matches.forEach { m ->
                    val min = m.groupValues[1].toLong()
                    val sec = m.groupValues[2].toLong()
                    val frac = m.groupValues[3]
                    val ms = when (frac.length) {
                        0 -> 0L
                        1 -> frac.toLong() * 100
                        2 -> frac.toLong() * 10
                        else -> frac.take(3).toLong()
                    }
                    out.add(LyricLine(min * 60_000 + sec * 1000 + ms, lineText))
                }
            }
        }
        val lines = if (out.any { it.timeMs != null }) out.filter { it.timeMs != null }.sortedBy { it.timeMs } else out
        return Lyrics(lines.dropWhile { it.text.isEmpty() && it.timeMs == null })
    }

    // ---------------------------------------------------------------- io

    private fun readFully(input: InputStream, buf: ByteArray, len: Int, offset: Int = 0): Boolean {
        var done = 0
        while (done < len) {
            val n = input.read(buf, offset + done, len - done)
            if (n < 0) return false
            done += n
        }
        return true
    }

    private fun skipFully(input: InputStream, count: Long) {
        var left = count
        while (left > 0) {
            val s = input.skip(left)
            if (s <= 0) {
                if (input.read() < 0) return
                left -= 1
            } else left -= s
        }
    }
}
