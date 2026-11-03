package com.powersound.player.data

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile

/**
 * What the tag editor changes. A null field is left as it is; an empty one is removed from the file.
 */
class TagEdits(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val genre: String? = null,
    val year: String? = null,
    val track: String? = null,
    val composer: String? = null,
    /** New front cover (JPEG or PNG bytes), or null to keep the pictures as they are. */
    val cover: ByteArray? = null,
    /** Removes every picture (ignored when [cover] is set). */
    val removeCover: Boolean = false
) {
    val coverMime: String
        get() = cover?.let { if (it.size > 4 && it[0] == 0x89.toByte() && it[1] == 'P'.code.toByte()) "image/png" else "image/jpeg" }
            ?: "image/jpeg"

    val changesPictures: Boolean get() = cover != null || removeCover
}

/**
 * Writes tags into FLAC (Vorbis comments + PICTURE) and MP3 (ID3v2.3 / 2.4) files.
 *
 * Only the tag part of the file is rebuilt: the audio is copied byte for byte, and every tag field or frame the
 * editor doesn't touch (lyrics, ReplayGain, comments …) is kept exactly as it was. Nothing here knows about Android,
 * so it is tested on real files on a computer.
 */
object TagWriter {

    class Unsupported(message: String) : Exception(message)

    private const val MAX_BLOCK = 0xFFFFFF

    /** "flac", "mp3" or null when the file is something this writer does not handle. */
    fun formatOf(file: File): String? = RandomAccessFile(file, "r").use { raf ->
        val head = ByteArray(10)
        if (raf.length() < 10) return null
        raf.readFully(head)
        when {
            head.startsWith("fLaC") -> "flac"
            head.startsWith("ID3") -> {
                val end = 10L + syncSafe(head, 6) + (if ((head[5].toInt() and 0x10) != 0) 10 else 0)
                if (end + 4 <= raf.length()) {
                    val m = ByteArray(4)
                    raf.seek(end)
                    raf.readFully(m)
                    if (m.startsWith("fLaC")) "flac" else "mp3"
                } else null
            }
            (head[0].toInt() and 0xFF) == 0xFF && (head[1].toInt() and 0xE0) == 0xE0 -> "mp3" // MPEG frame, no tag yet
            else -> null
        }
    }

    /**
     * Writes [src] with the new tags to [dst] (a different file). Throws [Unsupported] when the file can't be
     * edited safely; [src] is never changed. [expected] ("flac" / "mp3", from the file's type) must match what
     * the file really holds, so a tag is never written in the wrong format.
     */
    fun rewrite(src: File, dst: File, edits: TagEdits, expected: String? = null) {
        val format = formatOf(src)
        if (expected != null && format != null && format != expected) {
            throw Unsupported("This file's content doesn't match its type, so it was left as it is")
        }
        when (format) {
            "flac" -> rewriteFlac(src, dst, edits)
            "mp3" -> rewriteMp3(src, dst, edits)
            else -> throw Unsupported("This file type can't be edited yet (FLAC and MP3 can)")
        }
    }

    // ================================================================ FLAC

    private fun rewriteFlac(src: File, dst: File, e: TagEdits) {
        RandomAccessFile(src, "r").use { raf ->
            // a few programs put an ID3 tag in front of FLAC: it is kept as it is
            val head = ByteArray(10)
            raf.readFully(head)
            val prefix = if (head.startsWith("ID3")) 10L + syncSafe(head, 6) + (if ((head[5].toInt() and 0x10) != 0) 10 else 0) else 0L
            raf.seek(prefix)
            val magic = ByteArray(4)
            raf.readFully(magic)
            if (!magic.startsWith("fLaC")) throw Unsupported("Not a FLAC file")

            val blocks = ArrayList<Pair<Int, ByteArray>>()
            var pos = prefix + 4
            var last = false
            while (!last) {
                if (pos + 4 > raf.length()) throw Unsupported("The FLAC file is damaged")
                raf.seek(pos)
                val h = ByteArray(4)
                raf.readFully(h)
                last = (h[0].toInt() and 0x80) != 0
                val type = h[0].toInt() and 0x7F
                val len = ((h[1].toInt() and 0xFF) shl 16) or ((h[2].toInt() and 0xFF) shl 8) or (h[3].toInt() and 0xFF)
                if (type == 127 || pos + 4 + len > raf.length()) throw Unsupported("The FLAC file is damaged")
                val body = ByteArray(len)
                raf.readFully(body)
                blocks.add(type to body)
                pos += 4 + len
            }
            val audioStart = pos
            if (blocks.isEmpty() || blocks[0].first != 0) throw Unsupported("The FLAC file is damaged")
            val oldMeta = audioStart - (prefix + 4)

            // STREAMINFO and the other blocks stay; comments are rebuilt; padding is made again at the end
            val out = ArrayList<Pair<Int, ByteArray>>()
            var comments: ByteArray? = null
            for ((type, body) in blocks) {
                when (type) {
                    1 -> Unit
                    4 -> if (comments == null) comments = body
                    6 -> if (!e.changesPictures) out.add(type to body)
                    else -> out.add(type to body)
                }
            }
            out.add(1.coerceAtMost(out.size), 4 to vorbisComments(comments, e))
            e.cover?.let { out.add(6 to flacPicture(it, e.coverMime)) }
            for ((_, body) in out) if (body.size > MAX_BLOCK) throw Unsupported("A tag is too big for FLAC")

            val used = out.sumOf { 4L + it.second.size }
            // keep the audio where it was when the new tags fit, else leave 8 KB of room for next time
            val padding = if (used + 4 <= oldMeta && oldMeta - used - 4 <= MAX_BLOCK) (oldMeta - used - 4).toInt() else 8192

            dst.outputStream().buffered(1 shl 16).use { o ->
                if (prefix > 0) copyRange(raf, 0, prefix, o)
                o.write("fLaC".toByteArray(Charsets.ISO_8859_1))
                for ((type, body) in out) {
                    o.write(byteArrayOf(type.toByte(), (body.size shr 16).toByte(), (body.size shr 8).toByte(), body.size.toByte()))
                    o.write(body)
                }
                o.write(byteArrayOf((0x80 or 1).toByte(), (padding shr 16).toByte(), (padding shr 8).toByte(), padding.toByte()))
                o.write(ByteArray(padding))
                copyRange(raf, audioStart, raf.length(), o)
            }
            val expected = prefix + 4 + used + 4 + padding + (raf.length() - audioStart)
            if (dst.length() != expected) throw IllegalStateException("Writing the new file failed")
        }
    }

    private class VorbisEdit(val keys: List<String>, val value: String?)

    private fun vorbisComments(old: ByteArray?, e: TagEdits): ByteArray {
        var vendor = "PowerSound".toByteArray(Charsets.UTF_8)
        val entries = ArrayList<ByteArray>()
        if (old != null && old.size >= 8) {
            var p = 0
            fun le32(): Int {
                if (p + 4 > old.size) throw Unsupported("The FLAC tags are damaged")
                val v = (old[p].toInt() and 0xFF) or ((old[p + 1].toInt() and 0xFF) shl 8) or
                    ((old[p + 2].toInt() and 0xFF) shl 16) or ((old[p + 3].toInt() and 0xFF) shl 24)
                p += 4
                return v
            }
            val vl = le32()
            if (vl < 0 || p + vl > old.size) throw Unsupported("The FLAC tags are damaged")
            vendor = old.copyOfRange(p, p + vl)
            p += vl
            val count = le32()
            for (i in 0 until count) {
                val n = le32()
                if (n < 0 || p + n > old.size) throw Unsupported("The FLAC tags are damaged")
                entries.add(old.copyOfRange(p, p + n))
                p += n
            }
        }
        fun keyOf(b: ByteArray): String {
            val eq = b.indexOf('='.code.toByte())
            return if (eq <= 0) "" else String(b, 0, eq, Charsets.ISO_8859_1).uppercase()
        }
        val edits = listOf(
            VorbisEdit(listOf("TITLE"), e.title),
            VorbisEdit(listOf("ARTIST"), e.artist),
            VorbisEdit(listOf("ALBUM"), e.album),
            VorbisEdit(listOf("ALBUMARTIST", "ALBUM ARTIST", "ALBUM_ARTIST"), e.albumArtist),
            VorbisEdit(listOf("GENRE"), e.genre),
            VorbisEdit(listOf("DATE", "YEAR"), e.year),
            VorbisEdit(listOf("TRACKNUMBER"), e.track),
            VorbisEdit(listOf("COMPOSER"), e.composer)
        )
        for (ed in edits) {
            val value = ed.value ?: continue
            val at = entries.indexOfFirst { keyOf(it) in ed.keys }
            entries.removeAll { keyOf(it) in ed.keys }
            if (value.isNotBlank()) {
                val entry = (ed.keys[0] + "=" + value.trim()).toByteArray(Charsets.UTF_8)
                if (at >= 0) entries.add(at.coerceAtMost(entries.size), entry) else entries.add(entry)
            }
        }
        val b = ByteArrayOutputStream()
        b.writeLe32(vendor.size)
        b.write(vendor)
        b.writeLe32(entries.size)
        for (en in entries) {
            b.writeLe32(en.size)
            b.write(en)
        }
        return b.toByteArray()
    }

    private fun flacPicture(data: ByteArray, mime: String): ByteArray {
        val (w, h) = imageSize(data)
        val m = mime.toByteArray(Charsets.ISO_8859_1)
        val b = ByteArrayOutputStream()
        b.writeBe32(3) // front cover
        b.writeBe32(m.size)
        b.write(m)
        b.writeBe32(0) // no description
        b.writeBe32(w)
        b.writeBe32(h)
        b.writeBe32(24)
        b.writeBe32(0)
        b.writeBe32(data.size)
        b.write(data)
        return b.toByteArray()
    }

    // ================================================================ MP3 (ID3v2)

    private class Frame(val id: String, val bytes: ByteArray)

    private fun rewriteMp3(src: File, dst: File, e: TagEdits) {
        RandomAccessFile(src, "r").use { raf ->
            val head = ByteArray(10)
            raf.readFully(head)
            var major = 3
            var tagEnd = 0L
            val frames = ArrayList<Frame>()
            if (head.startsWith("ID3")) {
                major = head[3].toInt()
                val flags = head[5].toInt() and 0xFF
                val size = syncSafe(head, 6)
                if (major !in 3..4) throw Unsupported("This MP3 has an old tag version (ID3v2.$major) that can't be edited yet")
                if ((flags and 0x80) != 0) throw Unsupported("This MP3 tag uses unsynchronisation, which can't be edited yet")
                tagEnd = 10L + size + (if (major == 4 && (flags and 0x10) != 0) 10 else 0)
                if (10L + size > raf.length()) throw Unsupported("The MP3 tag is damaged")
                val body = ByteArray(size)
                raf.readFully(body)
                var p = 0
                if ((flags and 0x40) != 0 && size >= 4) {
                    // extended header: skipped (it only holds optional extras)
                    p = if (major == 3) be32(body, 0) + 4 else syncSafe(body, 0)
                    if (p < 0 || p > size) throw Unsupported("The MP3 tag is damaged")
                }
                fun isId(at: Int) = at + 4 <= size && (0..3).all { val c = body[at + it].toInt().toChar(); c in 'A'..'Z' || c in '0'..'9' }
                // a frame may end at the end of the tag, at the padding, or right before another frame
                fun endsWell(at: Int) = at == size || (at < size && body[at].toInt() == 0) || isId(at)
                while (p + 10 <= size && isId(p)) {
                    val id = String(body, p, 4, Charsets.ISO_8859_1)
                    // v2.4 sizes are "sync safe"; some programs write plain sizes anyway: take the one that fits
                    val safe = syncSafe(body, p + 4)
                    val plain = be32(body, p + 4)
                    val len = when {
                        major == 3 -> plain
                        (0..3).none { (body[p + 4 + it].toInt() and 0x80) != 0 } && endsWell(p + 10 + safe) -> safe
                        plain >= 0 && endsWell(p + 10 + plain) -> plain
                        else -> safe
                    }
                    if (len < 0 || p + 10 + len > size) break
                    frames.add(Frame(id, body.copyOfRange(p, p + 10 + len)))
                    p += 10 + len
                }
                // whatever is left must be padding: if not, the tag has parts we can't read, so it is not touched
                for (i in p until size) {
                    if (body[i].toInt() != 0) throw Unsupported("This MP3 tag has parts that can't be read safely, so it was left as it is")
                }
            }

            val yearIds = setOf("TYER", "TDRC", "TDAT", "TIME")
            fun replace(ids: Set<String>, newId: String, value: String?) {
                if (value == null) return
                val at = frames.indexOfFirst { it.id in ids }
                frames.removeAll { it.id in ids }
                if (value.isNotBlank()) {
                    val f = Frame(newId, frameBytes(newId, textFrame(value.trim(), major), major))
                    if (at >= 0) frames.add(at.coerceAtMost(frames.size), f) else frames.add(f)
                }
            }
            replace(setOf("TIT2"), "TIT2", e.title)
            replace(setOf("TPE1"), "TPE1", e.artist)
            replace(setOf("TALB"), "TALB", e.album)
            replace(setOf("TPE2"), "TPE2", e.albumArtist)
            replace(setOf("TCON"), "TCON", e.genre)
            replace(yearIds, if (major == 4) "TDRC" else "TYER", e.year)
            replace(setOf("TRCK"), "TRCK", e.track)
            replace(setOf("TCOM"), "TCOM", e.composer)
            if (e.changesPictures) frames.removeAll { it.id == "APIC" }
            e.cover?.let { frames.add(Frame("APIC", frameBytes("APIC", apic(it, e.coverMime), major))) }

            val content = ByteArrayOutputStream().apply { frames.forEach { write(it.bytes) } }.toByteArray()
            val padding = if (tagEnd > 0 && 10L + content.size <= tagEnd) (tagEnd - 10 - content.size).toInt() else 2048
            val tagSize = content.size + padding
            if (tagSize > 0x0FFFFFFF) throw Unsupported("The tags are too big")

            dst.outputStream().buffered(1 shl 16).use { o ->
                o.write(byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), major.toByte(), 0, 0))
                o.write(syncSafeBytes(tagSize))
                o.write(content)
                o.write(ByteArray(padding))
                copyRange(raf, tagEnd, raf.length(), o)
            }
            val expected = 10L + tagSize + (raf.length() - tagEnd)
            if (dst.length() != expected) throw IllegalStateException("Writing the new file failed")
        }
    }

    /** Text frame body: UTF-8 in v2.4; Latin-1 when possible, else UTF-16 with BOM in v2.3. */
    private fun textFrame(text: String, major: Int): ByteArray {
        val b = ByteArrayOutputStream()
        when {
            major == 4 -> { b.write(3); b.write(text.toByteArray(Charsets.UTF_8)) }
            text.all { it.code <= 0xFF } -> { b.write(0); b.write(text.toByteArray(Charsets.ISO_8859_1)) }
            else -> { b.write(1); b.write(0xFF); b.write(0xFE); b.write(text.toByteArray(Charsets.UTF_16LE)) }
        }
        return b.toByteArray()
    }

    private fun apic(data: ByteArray, mime: String): ByteArray {
        val b = ByteArrayOutputStream()
        b.write(0) // Latin-1 description
        b.write(mime.toByteArray(Charsets.ISO_8859_1))
        b.write(0)
        b.write(3) // front cover
        b.write(0) // empty description
        b.write(data)
        return b.toByteArray()
    }

    private fun frameBytes(id: String, body: ByteArray, major: Int): ByteArray {
        val b = ByteArrayOutputStream()
        b.write(id.toByteArray(Charsets.ISO_8859_1))
        if (major == 4) b.write(syncSafeBytes(body.size)) else b.writeBe32(body.size)
        b.write(0)
        b.write(0)
        b.write(body)
        return b.toByteArray()
    }

    // ================================================================ helpers

    /** Width and height of a JPEG or PNG (0, 0 when unknown). */
    fun imageSize(d: ByteArray): Pair<Int, Int> {
        if (d.size > 24 && d[0] == 0x89.toByte() && d[1] == 'P'.code.toByte()) return be32(d, 16) to be32(d, 20)
        if (d.size > 4 && (d[0].toInt() and 0xFF) == 0xFF && (d[1].toInt() and 0xFF) == 0xD8) {
            var p = 2
            while (p + 9 < d.size) {
                if ((d[p].toInt() and 0xFF) != 0xFF) { p++; continue }
                val marker = d[p + 1].toInt() and 0xFF
                if (marker == 0xFF) { p++; continue }
                val len = ((d[p + 2].toInt() and 0xFF) shl 8) or (d[p + 3].toInt() and 0xFF)
                if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                    val h = ((d[p + 5].toInt() and 0xFF) shl 8) or (d[p + 6].toInt() and 0xFF)
                    val w = ((d[p + 7].toInt() and 0xFF) shl 8) or (d[p + 8].toInt() and 0xFF)
                    return w to h
                }
                p += 2 + len
            }
        }
        return 0 to 0
    }

    private fun ByteArray.startsWith(s: String): Boolean =
        size >= s.length && s.indices.all { this[it] == s[it].code.toByte() }

    private fun syncSafe(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0x7F) shl 21) or ((b[o + 1].toInt() and 0x7F) shl 14) or
            ((b[o + 2].toInt() and 0x7F) shl 7) or (b[o + 3].toInt() and 0x7F)

    private fun syncSafeBytes(v: Int): ByteArray =
        byteArrayOf(((v shr 21) and 0x7F).toByte(), ((v shr 14) and 0x7F).toByte(), ((v shr 7) and 0x7F).toByte(), (v and 0x7F).toByte())

    private fun be32(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)

    private fun ByteArrayOutputStream.writeBe32(v: Int) {
        write((v ushr 24) and 0xFF); write((v ushr 16) and 0xFF); write((v ushr 8) and 0xFF); write(v and 0xFF)
    }

    private fun ByteArrayOutputStream.writeLe32(v: Int) {
        write(v and 0xFF); write((v ushr 8) and 0xFF); write((v ushr 16) and 0xFF); write((v ushr 24) and 0xFF)
    }

    private fun copyRange(raf: RandomAccessFile, from: Long, to: Long, o: java.io.OutputStream) {
        raf.seek(from)
        val buf = ByteArray(1 shl 16)
        var left = to - from
        while (left > 0) {
            val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) throw IllegalStateException("The file ended too early")
            o.write(buf, 0, n)
            left -= n
        }
    }
}
