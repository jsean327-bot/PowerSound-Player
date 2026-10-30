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

/** ReplayGain values (dB) read from the file's tags. */
data class ReplayGainInfo(val trackDb: Double?, val albumDb: Double?, val trackPeak: Double? = null, val albumPeak: Double? = null)

/**
 * Reads the tags embedded in a song file:
 *  • FLAC Vorbis comments (LYRICS, UNSYNCEDLYRICS, REPLAYGAIN_TRACK_GAIN …)
 *  • MP3 ID3v2: USLT (lyrics) and TXXX frames (LYRICS, REPLAYGAIN_* …)
 * Keys are returned upper-case.
 */
object TagReader {

    suspend fun read(context: Context, songId: Long): Map<String, String> = withContext(Dispatchers.IO) {
        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, songId)
        try {
            context.contentResolver.openInputStream(uri)?.use { readTags(it) } ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun readTags(input: InputStream): Map<String, String> {
        val head = ByteArray(10)
        if (!readFully(input, head, 10)) return emptyMap()
        return when {
            head[0] == 'f'.code.toByte() && head[1] == 'L'.code.toByte() &&
                head[2] == 'a'.code.toByte() && head[3] == 'C'.code.toByte() -> readFlac(input, head)
            head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte() -> readId3(input, head)
            else -> emptyMap()
        }
    }

    // ---------------------------------------------------------------- FLAC

    private fun readFlac(input: InputStream, head: ByteArray): Map<String, String> {
        // "fLaC" + first block header (4 bytes) + 2 bytes of its body are already read
        val pending = head.copyOfRange(4, 10)
        var first = true
        while (true) {
            val hdr = if (first) pending.copyOfRange(0, 4) else ByteArray(4).also { if (!readFully(input, it, 4)) return emptyMap() }
            val last = (hdr[0].toInt() and 0x80) != 0
            val type = hdr[0].toInt() and 0x7F
            val len = ((hdr[1].toInt() and 0xFF) shl 16) or ((hdr[2].toInt() and 0xFF) shl 8) or (hdr[3].toInt() and 0xFF)
            val already = if (first) 2 else 0
            if (type == 4) {
                if (len > 8 * 1024 * 1024) return emptyMap()
                val body = ByteArray(len)
                if (already > 0) System.arraycopy(pending, 4, body, 0, already)
                if (!readFully(input, body, len - already, already)) return emptyMap()
                return vorbisComments(body)
            } else {
                skipFully(input, (len - already).toLong())
            }
            first = false
            if (last) return emptyMap()
        }
    }

    private fun vorbisComments(b: ByteArray): Map<String, String> {
        val out = HashMap<String, String>()
        var p = 0
        fun le32(): Int {
            val v = (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8) or
                ((b[p + 2].toInt() and 0xFF) shl 16) or ((b[p + 3].toInt() and 0xFF) shl 24)
            p += 4
            return v
        }
        if (b.size < 8) return out
        val vendor = le32()
        p += vendor
        if (p + 4 > b.size) return out
        val count = le32()
        for (i in 0 until count) {
            if (p + 4 > b.size) break
            val n = le32()
            if (n < 0 || p + n > b.size) break
            val c = String(b, p, n, Charsets.UTF_8)
            p += n
            val eq = c.indexOf('=')
            if (eq <= 0) continue
            val key = c.substring(0, eq).uppercase()
            if (!out.containsKey(key)) out[key] = c.substring(eq + 1)
        }
        return out
    }

    // ---------------------------------------------------------------- ID3v2

    private fun readId3(input: InputStream, head: ByteArray): Map<String, String> {
        val out = HashMap<String, String>()
        val major = head[3].toInt()
        val size = syncSafe(head, 6)
        if (size <= 0 || size > 16 * 1024 * 1024) return out
        val tag = ByteArray(size)
        if (!readFully(input, tag, size)) return out
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
            when (id) {
                "USLT", "ULT" -> usltText(tag, start, len)?.let { if (!out.containsKey("LYRICS")) out["LYRICS"] = it }
                "TXXX", "TXX" -> txxx(tag, start, len)?.let { (k, v) -> if (!out.containsKey(k)) out[k] = v }
            }
            p = start + len
        }
        return out
    }

    private fun charsetOf(enc: Int): Charset = when (enc) {
        // Settings → Library → Scanner → Tag Encoding (ID3 "ISO-8859-1" tags often hold Arabic etc.)
        0 -> runCatching { Charset.forName(TAG_ENCODINGS[SettingsStore.value.tagEncoding.coerceIn(0, TAG_ENCODINGS.lastIndex)].second) }
            .getOrDefault(Charsets.ISO_8859_1)
        1 -> Charsets.UTF_16
        2 -> Charsets.UTF_16BE
        else -> Charsets.UTF_8
    }

    /** Skips a zero-terminated string starting at [p]; returns the index after the terminator. */
    private fun skipTerminated(b: ByteArray, p0: Int, end: Int, wide: Boolean): Int {
        var p = p0
        if (wide) {
            while (p + 1 < end && !(b[p].toInt() == 0 && b[p + 1].toInt() == 0)) p += 2
            return p + 2
        }
        while (p < end && b[p].toInt() != 0) p++
        return p + 1
    }

    private fun usltText(b: ByteArray, start: Int, len: Int): String? {
        if (len < 5) return null
        val enc = b[start].toInt()
        val end = start + len
        val wide = enc == 1 || enc == 2
        val p = skipTerminated(b, start + 4, end, wide) // encoding + 3-byte language + descriptor
        if (p >= end) return null
        return String(b, p, end - p, charsetOf(enc)).trimEnd('\u0000')
    }

    private fun txxx(b: ByteArray, start: Int, len: Int): Pair<String, String>? {
        if (len < 3) return null
        val enc = b[start].toInt()
        val end = start + len
        val wide = enc == 1 || enc == 2
        val descStart = start + 1
        val valueStart = skipTerminated(b, descStart, end, wide)
        val descEnd = valueStart - (if (wide) 2 else 1)
        if (valueStart >= end || descEnd < descStart) return null
        val cs = charsetOf(enc)
        val desc = String(b, descStart, descEnd - descStart, cs).trim().trimEnd('\u0000').uppercase()
        val value = String(b, valueStart, end - valueStart, cs).trimEnd('\u0000')
        return desc to value
    }

    private fun syncSafe(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0x7F) shl 21) or ((b[o + 1].toInt() and 0x7F) shl 14) or
            ((b[o + 2].toInt() and 0x7F) shl 7) or (b[o + 3].toInt() and 0x7F)

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

    // ---------------------------------------------------------------- helpers

    fun lyricsText(tags: Map<String, String>): String? =
        tags["LYRICS"] ?: tags["UNSYNCEDLYRICS"] ?: tags["UNSYNCED LYRICS"] ?: tags["USLT"]

    fun replayGain(tags: Map<String, String>): ReplayGainInfo {
        fun db(key: String): Double? = tags[key]?.trim()
            ?.replace("dB", "", ignoreCase = true)?.trim()?.replace(',', '.')?.toDoubleOrNull()
        return ReplayGainInfo(db("REPLAYGAIN_TRACK_GAIN"), db("REPLAYGAIN_ALBUM_GAIN"), db("REPLAYGAIN_TRACK_PEAK"), db("REPLAYGAIN_ALBUM_PEAK"))
    }
}

/** Lyrics embedded in the song file; plain text and LRC ("[01:23.45] line") are supported. */
object LyricsReader {

    private val cache = java.util.concurrent.ConcurrentHashMap<Long, Lyrics>()
    private val EMPTY = Lyrics(emptyList())

    /** Settings → Lyrics → Rescan Lyrics Tags. */
    fun clearCache() = cache.clear()

    suspend fun read(context: Context, songId: Long): Lyrics? = read(context, songId, "")

    /**
     * Lyrics of a song: the lyrics tag inside the file first, then (Settings → Lyrics → Scan LRC Files)
     * an .lrc file next to it with the same name.
     */
    suspend fun read(context: Context, songId: Long, path: String): Lyrics? {
        cache[songId]?.let { return it.takeIf { l -> l.lines.isNotEmpty() } }
        val fromTag = TagReader.lyricsText(TagReader.read(context, songId))?.takeIf { it.isNotBlank() }?.let { parse(it) }
        val result = fromTag ?: if (SettingsStore.value.scanLrc && path.isNotBlank()) readLrcFile(path) else null
        cache[songId] = result ?: EMPTY
        return result
    }

    suspend fun hasLocal(context: Context, songId: Long, path: String): Boolean = read(context, songId, path) != null

    private suspend fun readLrcFile(path: String): Lyrics? = withContext(Dispatchers.IO) {
        try {
            val audio = java.io.File(path)
            val dir = audio.parentFile ?: return@withContext null
            val base = audio.nameWithoutExtension
            val file = sequenceOf("$base.lrc", "$base.LRC", "$base.txt").map { java.io.File(dir, it) }.firstOrNull { it.canRead() }
                ?: dir.listFiles()?.firstOrNull { it.extension.equals("lrc", true) && it.nameWithoutExtension.equals(base, true) }
                ?: return@withContext null
            val text = decode(file.readBytes())
            text.takeIf { it.isNotBlank() }?.let { parse(it) }
        } catch (_: Exception) {
            null // no "All files access": Android hides non-music files from the app
        }
    }

    /** UTF-8 / UTF-16 with BOM; files that are not valid UTF-8 use Arabic Windows-1256 unless "Always Use UTF-8" is on. */
    fun decode(b: ByteArray): String {
        if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()) return String(b, 3, b.size - 3, Charsets.UTF_8)
        if (b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte()) return String(b, 2, b.size - 2, Charsets.UTF_16LE)
        if (b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte()) return String(b, 2, b.size - 2, Charsets.UTF_16BE)
        val strict = Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        return try {
            strict.decode(java.nio.ByteBuffer.wrap(b)).toString()
        } catch (_: java.nio.charset.CharacterCodingException) {
            if (SettingsStore.value.lyricsUtf8) String(b, Charsets.UTF_8)
            else String(b, runCatching { Charset.forName("windows-1256") }.getOrDefault(Charsets.ISO_8859_1))
        }
    }

    private val timeTag = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]""")
    private val metaLine = Regex("""^\[[a-zA-Z]+:.*]$""")

    fun parse(text: String): Lyrics {
        val out = ArrayList<LyricLine>()
        text.replace("\r\n", "\n").replace('\r', '\n').split('\n').forEach { rawLine ->
            val matches = timeTag.findAll(rawLine).toList()
            if (matches.isEmpty()) {
                if (!metaLine.matches(rawLine.trim())) out.add(LyricLine(null, rawLine.trim()))
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
}
