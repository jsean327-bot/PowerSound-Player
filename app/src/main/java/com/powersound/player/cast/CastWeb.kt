package com.powersound.player.cast

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** A web video the phone fetches for the Chromecast, with the headers its site wants (Referer, User-Agent, Cookie…). */
class ProxyTarget(val url: String, val headers: Map<String, String>)

/** What a web address turned out to be (asked before it is sent to the TV). */
class WebProbe(
    /** An HLS playlist (.m3u8). */
    val hls: Boolean,
    /** HLS whose pieces are fragmented MP4 (the Chromecast must be told), not MPEG-TS. */
    val fmp4: Boolean,
    val mime: String
)

/**
 * Casting web videos through the phone, and subtitles for the TV.
 *
 * The Chromecast fetches a video by itself, without the headers the app that found the video sends (Referer,
 * User-Agent, Cookie). Many sites refuse that. So the phone fetches the video with those headers and hands it on —
 * for HLS it also rewrites the playlists so every piece comes through the phone too.
 */
object CastWeb {
    const val BROWSER_UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    const val HLS_MIME = "application/x-mpegURL"

    // ---------------------------------------------------------------- fetching

    /** Opens [url] with [headers] (and a byte range), following redirects. The caller disconnects it. */
    fun open(url: String, headers: Map<String, String>, range: String? = null, head: Boolean = false): HttpURLConnection {
        var at = url
        repeat(8) {
            val c = URL(at).openConnection() as HttpURLConnection
            c.connectTimeout = 15_000
            c.readTimeout = 25_000
            c.instanceFollowRedirects = false
            c.requestMethod = if (head) "HEAD" else "GET"
            var ua = false
            for ((k, v) in headers) {
                if (k.equals("Range", true) || k.equals("Accept-Encoding", true) || k.equals("Host", true)) continue
                if (k.equals("User-Agent", true)) ua = true
                c.setRequestProperty(k, v)
            }
            if (!ua) c.setRequestProperty("User-Agent", BROWSER_UA)
            // as it is on the server: sizes and ranges stay true
            c.setRequestProperty("Accept-Encoding", "identity")
            if (range != null) c.setRequestProperty("Range", range)
            val code = c.responseCode
            if (code in 300..399 && code != 304) {
                val to = c.getHeaderField("Location")
                c.disconnect()
                if (to.isNullOrBlank()) throw java.io.IOException("Redirect without an address")
                at = URL(URL(at), to).toString()
            } else return c
        }
        throw java.io.IOException("Too many redirects")
    }

    fun looksLikeHls(contentType: String?, url: String): Boolean {
        val t = contentType.orEmpty().lowercase()
        return t.contains("mpegurl") || url.substringBefore('?').substringBefore('#').endsWith(".m3u8", true)
    }

    /** True when the stream starts with "#EXTM3U" (the stream is left where it was). */
    fun startsAsHls(input: BufferedInputStream): Boolean {
        input.mark(16)
        val b = ByteArray(10)
        var n = 0
        while (n < b.size) {
            val r = input.read(b, n, b.size - n)
            if (r <= 0) break
            n += r
        }
        input.reset()
        val s = String(b, 0, n, Charsets.ISO_8859_1).removePrefix("ï»¿")
        return s.startsWith("#EXTM3U")
    }

    fun readText(input: InputStream, max: Int = 4 * 1024 * 1024): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (out.size() < max) {
            val n = input.read(buf)
            if (n <= 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** Asks the site what [url] is. Null when it could not be reached (wrong headers, offline…). */
    fun probe(url: String, headers: Map<String, String>): WebProbe? = runCatching {
        val c = open(url, headers, range = "bytes=0-65535")
        try {
            if (c.responseCode >= 400) return null
            val type = c.contentType?.substringBefore(';')?.trim().orEmpty()
            val final = c.url.toString()
            val input = BufferedInputStream(c.inputStream)
            if (looksLikeHls(type, final) || startsAsHls(input)) {
                val text = String(readText(input, 512 * 1024), Charsets.UTF_8)
                var fmp4 = text.contains("#EXT-X-MAP")
                if (!fmp4 && text.contains("#EXT-X-STREAM-INF")) {
                    // a list of qualities: look inside the first one
                    val first = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
                    if (first != null) {
                        val child = open(URL(URL(final), first).toString(), headers)
                        try {
                            if (child.responseCode < 400) {
                                fmp4 = String(readText(child.inputStream, 256 * 1024), Charsets.UTF_8).contains("#EXT-X-MAP")
                            }
                        } finally {
                            child.disconnect()
                        }
                    }
                }
                WebProbe(hls = true, fmp4 = fmp4, mime = HLS_MIME)
            } else {
                val path = final.substringBefore('?').lowercase()
                val mime = when {
                    looksLikeDash(type, final) || startsAsDash(input) -> DASH_MIME
                    type.startsWith("video/") -> type
                    path.endsWith(".mpd") -> "application/dash+xml"
                    path.endsWith(".webm") -> "video/webm"
                    path.endsWith(".mkv") -> "video/x-matroska"
                    else -> "video/mp4"
                }
                WebProbe(hls = false, fmp4 = false, mime = mime)
            }
        } finally {
            c.disconnect()
        }
    }.getOrNull()

    // ---------------------------------------------------------------- HLS playlists

    private val URI_ATTR = Regex("URI=\"([^\"]+)\"")

    /**
     * An HLS playlist with every address in it (qualities, pieces, keys, sound and subtitle lists) changed by [wrap],
     * which gets the full address. [base] is the playlist's own address (relative ones are counted from it).
     */
    fun rewriteHls(text: String, base: String, wrap: (String) -> String): String {
        fun full(u: String): String? {
            if (u.startsWith("data:", true)) return null
            return runCatching { URL(URL(base), u).toString() }.getOrNull()
        }
        val out = StringBuilder(text.length + 1024)
        for (raw in text.removePrefix("﻿").lineSequence()) {
            val line = raw.trimEnd('\r')
            val t = line.trim()
            when {
                t.isEmpty() -> out.append(line)
                t.startsWith("#") -> out.append(URI_ATTR.replace(line) { m ->
                    val f = full(m.groupValues[1])
                    if (f == null) m.value else "URI=\"${wrap(f)}\""
                })
                else -> out.append(full(t)?.let(wrap) ?: line)
            }
            out.append('\n')
        }
        return out.toString()
    }

    // ---------------------------------------------------------------- DASH manifests (.mpd)

    const val DASH_MIME = "application/dash+xml"

    fun looksLikeDash(contentType: String?, url: String): Boolean {
        val t = contentType.orEmpty().lowercase()
        return t.contains("dash+xml") || url.substringBefore('?').substringBefore('#').endsWith(".mpd", true)
    }

    /** True when the stream is an XML file with an <MPD> in its first bytes (the stream is left where it was). */
    fun startsAsDash(input: BufferedInputStream): Boolean {
        input.mark(2048)
        val b = ByteArray(2048)
        var n = 0
        while (n < b.size) {
            val r = input.read(b, n, b.size - n)
            if (r <= 0) break
            n += r
        }
        input.reset()
        val s = String(b, 0, n, Charsets.ISO_8859_1).removePrefix("ï»¿").trimStart()
        return s.startsWith("<") && s.contains("<MPD")
    }

    private val DASH_ATTR = Regex("\\b(media|initialization|sourceURL|index)=\"([^\"]*)\"")
    private val DASH_TEXT = Regex("<(BaseURL|Location)([^>]*)>([^<]*)</\\1>")

    private fun unXml(s: String) = s.replace("&quot;", "\"").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
    private fun xml(s: String) = s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")

    /**
     * A DASH manifest whose every address goes through the phone: [wrap] turns a full web address into the phone's
     * address of it, keeping its path, so the Chromecast can still put the pieces' names together ($Number$, …)
     * after it. [base] is the manifest's own address: the top <BaseURL> (or a new one) is made full from it, so the
     * relative names under it come to the phone too.
     */
    fun rewriteDash(text: String, base: String, wrap: (String) -> String): String {
        fun absolute(u: String) = u.startsWith("http://", true) || u.startsWith("https://", true)
        fun full(u: String): String = runCatching { URL(URL(base), u).toString() }.getOrDefault(u)
        val period = text.indexOf("<Period").let { if (it < 0) text.length else it }
        var topBase = false
        // <BaseURL> / <Location>: the full ones go through the phone; the relative ones before the first <Period>
        // (the manifest's own) are counted from the manifest's address first
        var out = DASH_TEXT.replace(text) { m ->
            val v = unXml(m.groupValues[3].trim())
            val top = m.range.first < period
            if (top && m.groupValues[1] == "BaseURL") topBase = true
            val f = when {
                absolute(v) -> v
                top || m.groupValues[1] == "Location" -> full(v)
                else -> return@replace m.value
            }
            "<${m.groupValues[1]}${m.groupValues[2]}>${xml(wrap(f))}</${m.groupValues[1]}>"
        }
        out = DASH_ATTR.replace(out) { m ->
            val v = unXml(m.groupValues[2])
            if (absolute(v)) "${m.groupValues[1]}=\"${xml(wrap(v))}\"" else m.value
        }
        if (!topBase) {
            // no address of its own: the pieces are counted from the manifest's folder → that folder through the phone
            val start = Regex("<MPD\\b[^>]*>").find(out)
            if (start != null) {
                val dir = full(".")
                out = out.substring(0, start.range.last + 1) + "\n<BaseURL>${xml(wrap(dir))}</BaseURL>" + out.substring(start.range.last + 1)
            }
        }
        return out
    }

    // ---------------------------------------------------------------- subtitles → WebVTT (what the Chromecast shows)

    /** Text of a subtitle file: UTF-8 / UTF-16 by their marks, else UTF-8 when it is valid, else Arabic Windows-1256. */
    fun decodeText(b: ByteArray): String {
        if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()) return String(b, 3, b.size - 3, Charsets.UTF_8)
        if (b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte()) return String(b, 2, b.size - 2, Charsets.UTF_16LE)
        if (b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte()) return String(b, 2, b.size - 2, Charsets.UTF_16BE)
        return runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(b)).toString()
        }.getOrElse {
            runCatching { String(b, Charset.forName("windows-1256")) }.getOrElse { String(b, Charsets.ISO_8859_1) }
        }
    }

    /** A subtitle file (SRT, WebVTT, ASS / SSA) as WebVTT. Null when it is none of them. */
    fun toVtt(bytes: ByteArray): String? {
        val text = decodeText(bytes).replace("\r\n", "\n").replace('\r', '\n')
        val head = text.trimStart()
        return when {
            head.startsWith("WEBVTT") -> head
            head.startsWith("[Script Info]", true) || text.contains("\nDialogue:") -> assToVtt(text)
            SRT_TIME.containsMatchIn(text) -> srtToVtt(text)
            else -> null
        }
    }

    private val SRT_TIME = Regex("(\\d{1,2}):(\\d{2}):(\\d{2})[,.](\\d{1,3})\\s*-->\\s*(\\d{1,2}):(\\d{2}):(\\d{2})[,.](\\d{1,3})")
    private val ASS_TAG = Regex("\\{[^}]*\\}")

    private fun stamp(h: String, m: String, s: String, frac: String, fracDigits: Int = 3): String {
        val ms = (frac.padEnd(fracDigits, '0').take(fracDigits).toIntOrNull() ?: 0).let { if (fracDigits == 2) it * 10 else it }
        return "%02d:%s:%s.%03d".format(h.toIntOrNull() ?: 0, m, s, ms)
    }

    fun srtToVtt(text: String): String {
        val out = StringBuilder("WEBVTT\n\n")
        for (block in text.trim().split(Regex("\n\\s*\n"))) {
            val lines = block.lines()
            val ti = lines.indexOfFirst { SRT_TIME.containsMatchIn(it) }
            if (ti < 0) continue
            val m = SRT_TIME.find(lines[ti]) ?: continue
            val g = m.groupValues
            val body = lines.drop(ti + 1).joinToString("\n") { ASS_TAG.replace(it, "").trimEnd() }.trim()
            if (body.isEmpty()) continue
            out.append(stamp(g[1], g[2], g[3], g[4])).append(" --> ").append(stamp(g[5], g[6], g[7], g[8])).append('\n')
            out.append(body).append("\n\n")
        }
        return out.toString()
    }

    fun assToVtt(text: String): String {
        val out = StringBuilder("WEBVTT\n\n")
        // "Format:" of [Events] tells where Start, End and Text are; Text is always last
        var start = 1
        var end = 2
        var count = 10
        var events = false
        val cues = ArrayList<Triple<String, String, String>>()
        val time = Regex("(\\d+):(\\d{2}):(\\d{2})[.:](\\d{1,3})")
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("[")) {
                events = line.equals("[Events]", true)
                continue
            }
            if (!events) continue
            if (line.startsWith("Format:", true)) {
                val f = line.substringAfter(':').split(',').map { it.trim().lowercase() }
                start = f.indexOf("start").takeIf { it >= 0 } ?: start
                end = f.indexOf("end").takeIf { it >= 0 } ?: end
                count = f.size
            } else if (line.startsWith("Dialogue:", true)) {
                val p = line.substringAfter(':').split(",", limit = count)
                if (p.size < count) continue
                val a = time.find(p[start].trim()) ?: continue
                val b = time.find(p[end].trim()) ?: continue
                val body = ASS_TAG.replace(p[count - 1], "")
                    .replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " ")
                    .lines().joinToString("\n") { it.trim() }.trim()
                if (body.isEmpty()) continue
                val (ah, am, asec, af) = a.destructured
                val (bh, bm, bsec, bf) = b.destructured
                cues += Triple(stamp(ah, am, asec, af, af.length.coerceIn(2, 3)), stamp(bh, bm, bsec, bf, bf.length.coerceIn(2, 3)), body)
            }
        }
        for ((a, b, body) in cues.sortedBy { it.first }) {
            out.append(a).append(" --> ").append(b).append('\n').append(body).append("\n\n")
        }
        return out.toString()
    }
}
