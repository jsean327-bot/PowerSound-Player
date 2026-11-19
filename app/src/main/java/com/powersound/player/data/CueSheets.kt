package com.powersound.player.data

import android.content.Context
import androidx.media3.common.MediaItem
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import org.json.JSONArray
import org.json.JSONObject

/**
 * Albums ripped as one long file with a CUE sheet ("Album.flac" + "Album.cue", or a CUESHEET tag inside the FLAC):
 * the library shows the album's own songs instead of the one long file, and each song plays only its part of it.
 *
 * A CUE song has a negative id that holds the long file's MediaStore id ([parentOf]), and [Song.startMs] / [Song.endMs].
 */
object CueSheets {
    private const val BASE = 4_000_000_000_000_000_000L
    private const val SPAN = 10_000_000_000_000_000L
    private const val FILE = "cue.json"

    fun idOf(parent: Long, track: Int): Long = -(BASE + parent * 1000 + track.coerceIn(1, 999))

    /** The long file's MediaStore id for a CUE song's id, or null for any other id. */
    fun parentOf(id: Long): Long? {
        if (id >= 0) return null
        val v = -id - BASE
        if (v < 0 || v >= SPAN || v % 1000 == 0L) return null
        return v / 1000
    }

    fun isCue(id: Long) = parentOf(id) != null

    /** The MediaStore id whose file holds this song (its own id for ordinary songs). */
    fun fileId(id: Long): Long = parentOf(id) ?: id

    /** Plays only the song's part of the long file. */
    fun MediaItem.Builder.clipTo(song: Song): MediaItem.Builder {
        if (song.startMs <= 0 && song.endMs <= 0) return this
        val c = MediaItem.ClippingConfiguration.Builder().setStartPositionMs(song.startMs.coerceAtLeast(0))
        if (song.endMs > song.startMs) c.setEndPositionMs(song.endMs)
        return setClippingConfiguration(c.build())
    }

    // ---------------------------------------------------------------- the library

    private class Parsed(val stamp: String, val sheet: Sheet?)

    private val parsed = HashMap<String, Parsed>()

    @Volatile
    private var saved: Map<Long, Song>? = null

    /** A CUE song by id (for the player's saved queue, before the library is loaded). */
    fun song(context: Context, id: Long): Song? {
        if (!isCue(id)) return null
        val m = saved ?: load(context).also { saved = it }
        return m[id]
    }

    /**
     * The library with each long file that has a CUE sheet replaced by the songs of the sheet. Files without one,
     * or whose sheet can't be read, stay as they are.
     */
    fun expand(context: Context, songs: List<Song>): List<Song> {
        val out = ArrayList<Song>(songs.size)
        val made = ArrayList<Song>()
        val byDir = songs.filter { it.id >= 0 && it.path.isNotEmpty() }.groupBy { File(it.path).parent ?: "" }
        val claimed = HashMap<Long, List<Song>>()
        // 1. .cue files next to the songs
        for ((dir, inDir) in byDir) {
            if (dir.isEmpty()) continue
            val cues = runCatching { File(dir).listFiles { f -> f.isFile && f.name.endsWith(".cue", true) } }.getOrNull() ?: continue
            for (cue in cues) {
                val sheet = cached(cue.path, "${cue.length()}:${cue.lastModified()}") { parse(readText(cue)) } ?: continue
                for ((fileName, tracks) in sheet.files) {
                    if (tracks.size < 2) continue
                    val parent = match(fileName, inDir) ?: continue
                    if (parent.id in claimed) continue
                    claimed[parent.id] = build(parent, sheet, tracks)
                }
            }
        }
        // 2. a CUESHEET inside a long FLAC (foobar2000, EAC…)
        for (s in songs) {
            if (s.id < 0 || s.id in claimed || s.durationMs < 8 * 60_000 || !s.path.endsWith(".flac", true)) continue
            val f = File(s.path)
            val sheet = cached(s.path, "${f.length()}:${f.lastModified()}") { embedded(f) } ?: continue
            val tracks = sheet.files.values.maxByOrNull { it.size } ?: continue
            if (tracks.size >= 2) claimed[s.id] = build(s, sheet, tracks)
        }
        for (s in songs) {
            val parts = claimed[s.id]
            if (parts == null) out += s else {
                out += parts
                made += parts
            }
        }
        if (made.map { it.id }.toSet() != saved?.keys) save(context, made)
        return out
    }

    private fun cached(path: String, stamp: String, read: () -> Sheet?): Sheet? {
        synchronized(parsed) { parsed[path]?.takeIf { it.stamp == stamp }?.let { return it.sheet } }
        val s = runCatching(read).getOrNull()
        synchronized(parsed) { parsed[path] = Parsed(stamp, s) }
        return s
    }

    /** The library's file a sheet's FILE line means: the same name, or the same name with another ending (.wav → .flac). */
    private fun match(name: String, inDir: List<Song>): Song? {
        val n = name.replace('\\', '/').substringAfterLast('/')
        inDir.firstOrNull { File(it.path).name.equals(n, true) }?.let { return it }
        val base = n.substringBeforeLast('.')
        return inDir.firstOrNull { File(it.path).name.substringBeforeLast('.').equals(base, true) }
    }

    private fun build(parent: Song, sheet: Sheet, tracks: List<Track>): List<Song> {
        val sorted = tracks.sortedBy { it.startMs }
        return sorted.mapIndexed { i, t ->
            val end = sorted.getOrNull(i + 1)?.startMs ?: parent.durationMs
            val dur = (end - t.startMs).coerceAtLeast(0)
            val albumArtist = sheet.performer?.takeIf { it.isNotBlank() }
            parent.copy(
                id = idOf(parent.id, t.number),
                title = t.title?.takeIf { it.isNotBlank() } ?: "Track %02d".format(t.number),
                artist = t.performer?.takeIf { it.isNotBlank() } ?: albumArtist ?: parent.artist,
                album = sheet.title?.takeIf { it.isNotBlank() } ?: parent.album,
                albumArtist = albumArtist ?: parent.albumArtist,
                hasAlbumArtist = albumArtist != null || parent.hasAlbumArtist,
                durationMs = dur,
                track = (parent.track / 1000) * 1000 + t.number,
                year = sheet.year ?: parent.year,
                genre = sheet.genre?.takeIf { it.isNotBlank() } ?: parent.genre,
                size = if (parent.durationMs > 0) parent.size * dur / parent.durationMs else 0,
                startMs = t.startMs,
                endMs = if (i == sorted.lastIndex) 0 else end
            )
        }
    }

    // ---------------------------------------------------------------- saved CUE songs (for the player's service)

    private fun load(context: Context): Map<Long, Song> = runCatching {
        val a = JSONArray(File(context.filesDir, FILE).takeIf { it.exists() }?.readText() ?: "[]")
        (0 until a.length()).associate { i ->
            val o = a.getJSONObject(i)
            val s = Song(
                id = o.getLong("id"), title = o.optString("t"), artist = o.optString("a"), album = o.optString("al"),
                albumId = o.optLong("aid"), albumArtist = o.optString("aa"), durationMs = o.optLong("d"), track = o.optInt("n"),
                folder = o.optString("f"), mimeType = o.optString("m"), size = o.optLong("s"), year = o.optInt("y"),
                composer = "", genre = o.optString("g"), dateAdded = o.optLong("da"), path = o.optString("p"),
                hasAlbumArtist = o.optBoolean("haa"), startMs = o.optLong("st"), endMs = o.optLong("en")
            )
            s.id to s
        }
    }.getOrDefault(emptyMap())

    private fun save(context: Context, list: List<Song>) {
        val a = JSONArray()
        list.forEach { s ->
            a.put(
                JSONObject().put("id", s.id).put("t", s.title).put("a", s.artist).put("al", s.album).put("aid", s.albumId)
                    .put("aa", s.albumArtist).put("d", s.durationMs).put("n", s.track).put("f", s.folder).put("m", s.mimeType)
                    .put("s", s.size).put("y", s.year).put("g", s.genre).put("da", s.dateAdded).put("p", s.path)
                    .put("haa", s.hasAlbumArtist).put("st", s.startMs).put("en", s.endMs)
            )
        }
        runCatching {
            val tmp = File(context.filesDir, "$FILE.tmp")
            tmp.writeText(a.toString())
            tmp.renameTo(File(context.filesDir, FILE))
        }
        saved = list.associateBy { it.id }
    }

    // ---------------------------------------------------------------- reading a sheet

    class Track(val number: Int, val title: String?, val performer: String?, val startMs: Long)

    class Sheet(
        val title: String?,
        val performer: String?,
        val year: Int?,
        val genre: String?,
        /** FILE name → its tracks, in the sheet's order. */
        val files: Map<String, List<Track>>
    )

    /** A .cue file's text: UTF-8 when it is valid UTF-8, else the old Windows code page (Arabic or Western). */
    fun readText(f: File): String = decode(f.readBytes())

    fun decode(bytes: ByteArray): String {
        var b = bytes
        if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()) b = b.copyOfRange(3, b.size)
        val utf8 = runCatching {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(b)).toString()
        }.getOrNull()
        if (utf8 != null) return utf8
        // Arabic words are runs of high bytes; Western accents are single high bytes between plain letters
        var runs = 0
        var high = 0
        var inRun = false
        for (x in b) {
            val h = x.toInt() and 0xff >= 0x80
            if (h) {
                high++
                if (!inRun) runs++
            }
            inRun = h
        }
        val cp = if (runs > 0 && high.toDouble() / runs >= 2.5) "windows-1256" else "windows-1252"
        return String(b, runCatching { Charset.forName(cp) }.getOrDefault(Charsets.ISO_8859_1))
    }

    /** "mm:ss:ff" (75 frames a second) → milliseconds. */
    private fun time(s: String): Long? {
        val p = s.trim().split(':')
        if (p.size != 3) return null
        val m = p[0].toLongOrNull() ?: return null
        val sec = p[1].toLongOrNull() ?: return null
        val fr = p[2].toLongOrNull() ?: return null
        return (m * 60 + sec) * 1000 + fr * 1000 / 75
    }

    /** The rest of a line: a "quoted string" or the plain words. */
    private fun value(rest: String): String {
        val r = rest.trim()
        if (r.startsWith("\"")) {
            val end = r.indexOf('"', 1)
            return if (end > 0) r.substring(1, end) else r.substring(1)
        }
        return r
    }

    fun parse(text: String): Sheet? {
        var title: String? = null
        var performer: String? = null
        var year: Int? = null
        var genre: String? = null
        val files = LinkedHashMap<String, MutableList<Track>>()
        var file: String? = null
        var num = -1
        var tTitle: String? = null
        var tPerformer: String? = null
        var tStart: Long? = null
        var tStart0: Long? = null

        fun close() {
            val f = file
            val start = tStart ?: tStart0
            if (num > 0 && f != null && start != null) files.getOrPut(f) { ArrayList() } += Track(num, tTitle, tPerformer, start)
            num = -1
            tTitle = null
            tPerformer = null
            tStart = null
            tStart0 = null
        }

        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val cmd = line.substringBefore(' ').uppercase()
            val rest = line.substringAfter(' ', "")
            when (cmd) {
                "FILE" -> {
                    close()
                    // FILE "name with spaces.flac" WAVE
                    val r = rest.trim()
                    file = if (r.startsWith("\"")) value(r) else r.substringBeforeLast(' ').ifBlank { r }
                }
                "TRACK" -> {
                    close()
                    val parts = rest.trim().split(Regex("\\s+"))
                    if (parts.getOrNull(1)?.uppercase() == "AUDIO") num = parts[0].toIntOrNull() ?: -1
                }
                "TITLE" -> if (num > 0) tTitle = value(rest) else if (file == null || files.isEmpty()) title = value(rest)
                "PERFORMER" -> if (num > 0) tPerformer = value(rest) else if (file == null || files.isEmpty()) performer = value(rest)
                "INDEX" -> if (num > 0) {
                    val parts = rest.trim().split(Regex("\\s+"))
                    val t = parts.getOrNull(1)?.let { time(it) }
                    when (parts.getOrNull(0)?.toIntOrNull()) {
                        1 -> tStart = t
                        0 -> tStart0 = t
                    }
                }
                "REM" -> {
                    val key = rest.trim().substringBefore(' ').uppercase()
                    val v = value(rest.trim().substringAfter(' ', ""))
                    when (key) {
                        "DATE" -> year = Regex("\\d{4}").find(v)?.value?.toIntOrNull()
                        "GENRE" -> genre = v
                    }
                }
            }
        }
        close()
        if (files.isEmpty()) return null
        return Sheet(title, performer, year, genre, files)
    }

    // ---------------------------------------------------------------- a CUESHEET inside a FLAC

    /** The FLAC's CUESHEET tag (as a .cue text), or its CUE sheet block (track numbers and times only). */
    fun embedded(f: File): Sheet? = RandomAccessFile(f, "r").use { r ->
        val magic = ByteArray(4)
        r.readFully(magic)
        if (String(magic, Charsets.ISO_8859_1) != "fLaC") return null
        var rate = 0
        var tags: Map<String, String> = emptyMap()
        var block: List<Pair<Int, Long>>? = null
        while (true) {
            val head = r.read()
            if (head < 0) break
            val type = head and 0x7f
            val len = (r.read() shl 16) or (r.read() shl 8) or r.read()
            val at = r.filePointer
            when (type) {
                0 -> {
                    val b = ByteArray(minOf(len, 34))
                    r.readFully(b)
                    rate = ((b[10].toInt() and 0xff) shl 12) or ((b[11].toInt() and 0xff) shl 4) or ((b[12].toInt() and 0xff) shr 4)
                }
                4 -> if (len in 8..(4 shl 20)) {
                    val b = ByteArray(len)
                    r.readFully(b)
                    tags = vorbis(b)
                }
                5 -> if (len in 396..65536) {
                    val b = ByteArray(len)
                    r.readFully(b)
                    block = cueBlock(b)
                }
            }
            r.seek(at + len)
            if (head and 0x80 != 0) break
        }
        tags["CUESHEET"]?.let { text -> parse(text)?.let { return it } }
        val b = block ?: return null
        if (rate <= 0) return null
        val tracks = b.map { (n, samples) -> Track(n, tags["TITLE[$n]"], tags["PERFORMER[$n]"], samples * 1000 / rate) }
        return Sheet(tags["ALBUM"], tags["ALBUMARTIST"] ?: tags["ARTIST"], tags["DATE"]?.take(4)?.toIntOrNull(), tags["GENRE"], mapOf(f.name to tracks))
    }

    /** Vorbis comments: KEY=value (keys in capitals; the first of each). */
    private fun vorbis(b: ByteArray): Map<String, String> {
        val buf = ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val out = HashMap<String, String>()
        runCatching {
            val vendor = buf.int
            buf.position(buf.position() + vendor)
            val n = buf.int
            repeat(n) {
                val l = buf.int
                if (l < 0 || l > buf.remaining()) return out
                val s = String(b, buf.position(), l, Charsets.UTF_8)
                buf.position(buf.position() + l)
                val eq = s.indexOf('=')
                if (eq > 0) out.putIfAbsent(s.substring(0, eq).uppercase(), s.substring(eq + 1))
            }
        }
        return out
    }

    /** FLAC's CUE sheet block: each audio track's number and its INDEX 01 in samples. */
    private fun cueBlock(b: ByteArray): List<Pair<Int, Long>> {
        val buf = ByteBuffer.wrap(b) // big-endian
        buf.position(128 + 8 + 1 + 258)
        val count = buf.get().toInt() and 0xff
        val out = ArrayList<Pair<Int, Long>>()
        repeat(count) {
            if (buf.remaining() < 36) return out
            val offset = buf.long
            val number = buf.get().toInt() and 0xff
            buf.position(buf.position() + 12) // ISRC
            val flags = buf.get().toInt() and 0xff
            buf.position(buf.position() + 13)
            val indices = buf.get().toInt() and 0xff
            var start: Long? = null
            repeat(indices) {
                if (buf.remaining() < 12) return out
                val o = buf.long
                val idx = buf.get().toInt() and 0xff
                buf.position(buf.position() + 3)
                if (idx == 1) start = offset + o
            }
            val audio = flags and 0x80 == 0
            if (number in 1..99 && audio) start?.let { out += number to it }
        }
        return out
    }
}
