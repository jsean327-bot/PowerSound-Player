package com.powersound.player.net

import java.io.ByteArrayInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.URL
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node

/** A media server on the home network (a PC, a NAS, a router with a disk): its name and where it is asked. */
data class DlnaServer(val name: String, val controlUrl: String, val location: String, val iconUrl: String? = null)

/** One thing in a server's folder: a folder, a song, a video (pictures are skipped). */
data class DlnaItem(
    val id: String,
    val title: String,
    /** 0 folder, 1 audio, 2 video. */
    val kind: Int,
    val url: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val artUrl: String? = null,
    val durationMs: Long = 0L,
    val size: Long = 0L,
    val mime: String? = null,
    val childCount: Int = -1
)

/**
 * Library → Network: music and videos from DLNA / UPnP media servers on the Wi-Fi (Windows "media streaming", a NAS,
 * a router with a USB disk, Plex / Jellyfin / Kodi…). The servers are found by themselves (SSDP), their folders are
 * read (ContentDirectory "Browse"), and every file is a plain web address the players — and the Chromecast — can open.
 */
object Dlna {
    private const val SEARCH_TARGET = "urn:schemas-upnp-org:service:ContentDirectory:1"

    // ---------------------------------------------------------------- finding servers

    /**
     * Asks the network who is a media server and waits [waitMs] for answers. [bind] is the phone's Wi-Fi address
     * (the question must leave through the Wi-Fi). Returns the description addresses ("LOCATION") that answered.
     */
    fun search(bind: InetAddress?, waitMs: Int = 3500, to: InetSocketAddress = InetSocketAddress("239.255.255.250", 1900)): Set<String> {
        val found = LinkedHashSet<String>()
        val socket = runCatching { if (bind != null) DatagramSocket(InetSocketAddress(bind, 0)) else DatagramSocket() }.getOrNull() ?: return found
        socket.use { s ->
            val ask = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $SEARCH_TARGET\r\n\r\n")
                .toByteArray(Charsets.US_ASCII)
            val end = System.currentTimeMillis() + waitMs
            var sent = 0
            var nextSend = 0L
            val buf = ByteArray(4096)
            while (true) {
                val now = System.currentTimeMillis()
                if (now >= end) break
                // the question is repeated (UDP gets lost): at 0, 0.7 and 1.4 s
                if (sent < 3 && now >= nextSend) {
                    runCatching { s.send(DatagramPacket(ask, ask.size, to)) }
                    sent++
                    nextSend = now + 700
                }
                s.soTimeout = (minOf(end, if (sent < 3) nextSend else end) - now).toInt().coerceAtLeast(1)
                val p = DatagramPacket(buf, buf.size)
                try {
                    s.receive(p)
                } catch (e: SocketTimeoutException) {
                    continue
                } catch (e: Exception) {
                    break
                }
                val text = String(p.data, 0, p.length, Charsets.ISO_8859_1)
                text.lineSequence().firstOrNull { it.startsWith("location:", true) }
                    ?.substringAfter(':')?.trim()?.takeIf { it.startsWith("http", true) }?.let { found += it }
            }
        }
        return found
    }

    /** Reads a server's description (its name and where its folders are asked). Null when it has no media folders. */
    fun describe(location: String): DlnaServer? = runCatching {
        val doc = parse(get(location) ?: return null) ?: return null
        val root = doc.documentElement
        val base = first(root, "URLBase")?.textContent?.trim()?.takeIf { it.isNotEmpty() } ?: location
        val device = first(root, "device") ?: return null
        val name = first(device, "friendlyName")?.textContent?.trim().orEmpty().ifEmpty { URL(location).host }
        val service = all(root, "service").firstOrNull { first(it, "serviceType")?.textContent?.contains("ContentDirectory") == true }
            ?: return null
        val control = first(service, "controlURL")?.textContent?.trim() ?: return null
        val icon = all(device, "icon").mapNotNull { first(it, "url")?.textContent?.trim() }.firstOrNull { it.isNotEmpty() }
        DlnaServer(
            name = name,
            controlUrl = URL(URL(base), control).toString(),
            location = location,
            iconUrl = icon?.let { runCatching { URL(URL(base), it).toString() }.getOrNull() }
        )
    }.getOrNull()

    // ---------------------------------------------------------------- reading a folder

    /** What is inside the folder [id] ("0" is the top) — everything, asked in pages. Null when the server refused. */
    fun browse(server: DlnaServer, id: String, max: Int = 5000): List<DlnaItem>? {
        val out = ArrayList<DlnaItem>()
        var start = 0
        while (out.size < max) {
            val page = browsePage(server, id, start, 200) ?: return if (start == 0) null else out
            out += page.first
            start += page.second
            if (page.second == 0 || start >= page.third) break
        }
        return out
    }

    /** One page: the items, how many entries the server returned, how many there are in all. */
    private fun browsePage(server: DlnaServer, id: String, start: Int, count: Int): Triple<List<DlnaItem>, Int, Int>? = runCatching {
        val body = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:Browse xmlns:u="$SEARCH_TARGET"><ObjectID>${esc(id)}</ObjectID><BrowseFlag>BrowseDirectChildren</BrowseFlag><Filter>*</Filter><StartingIndex>$start</StartingIndex><RequestedCount>$count</RequestedCount><SortCriteria></SortCriteria></u:Browse></s:Body></s:Envelope>"""
        val c = URL(server.controlUrl).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8000
            c.readTimeout = 20000
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            c.setRequestProperty("SOAPACTION", "\"$SEARCH_TARGET#Browse\"")
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (c.responseCode >= 400) return null
            val doc = parse(c.inputStream.use { it.readBytes() }) ?: return null
            val result = first(doc.documentElement, "Result")?.textContent ?: return null
            val returned = first(doc.documentElement, "NumberReturned")?.textContent?.trim()?.toIntOrNull() ?: 0
            val total = first(doc.documentElement, "TotalMatches")?.textContent?.trim()?.toIntOrNull() ?: 0
            Triple(items(result, server.controlUrl), returned, if (total > 0) total else start + returned)
        } finally {
            c.disconnect()
        }
    }.getOrNull()

    /** The folder's list (DIDL-Lite text) as items. [base] is used for addresses written without their host. */
    fun items(didl: String, base: String): List<DlnaItem> {
        val doc = parse(didl.toByteArray(Charsets.UTF_8)) ?: return emptyList()
        val out = ArrayList<DlnaItem>()
        var n = doc.documentElement.firstChild
        while (n != null) {
            if (n is Element) {
                val tag = local(n)
                val title = first(n, "title")?.textContent?.trim().orEmpty().ifEmpty { "?" }
                val id = n.getAttribute("id")
                if (tag == "container") {
                    out += DlnaItem(id, title, 0, childCount = n.getAttribute("childCount").toIntOrNull() ?: -1)
                } else if (tag == "item") {
                    val cls = first(n, "class")?.textContent.orEmpty().lowercase()
                    val res = all(n, "res").filter { it.textContent.trim().isNotEmpty() }
                    fun type(r: Element) = r.getAttribute("protocolInfo").split(':').getOrNull(2).orEmpty().lowercase()
                    val audio = cls.contains("audioitem") || (!cls.contains("videoitem") && res.any { type(it).startsWith("audio/") })
                    val video = cls.contains("videoitem") || (!audio && res.any { type(it).startsWith("video/") })
                    if (audio || video) {
                        // the original file first: not a copy the server would convert (smaller, worse)
                        val want = if (audio) "audio/" else "video/"
                        val best = res.filter { type(it).startsWith(want) || type(it).isEmpty() || type(it) == "*" }
                            .sortedWith(
                                compareBy<Element> { if (it.getAttribute("protocolInfo").contains("DLNA.ORG_CI=1")) 1 else 0 }
                                    .thenByDescending { it.getAttribute("size").toLongOrNull() ?: 0L }
                            ).firstOrNull() ?: res.firstOrNull()
                        if (best != null) {
                            out += DlnaItem(
                                id = id,
                                title = title,
                                kind = if (audio) 1 else 2,
                                url = runCatching { URL(URL(base), best.textContent.trim()).toString() }.getOrNull() ?: best.textContent.trim(),
                                artist = (first(n, "artist") ?: first(n, "creator"))?.textContent?.trim()?.takeIf { it.isNotEmpty() },
                                album = first(n, "album")?.textContent?.trim()?.takeIf { it.isNotEmpty() },
                                artUrl = first(n, "albumArtURI")?.textContent?.trim()?.takeIf { it.isNotEmpty() }
                                    ?.let { runCatching { URL(URL(base), it).toString() }.getOrNull() },
                                durationMs = duration(best.getAttribute("duration")),
                                size = best.getAttribute("size").toLongOrNull() ?: 0L,
                                mime = type(best).takeIf { it.contains('/') }
                            )
                        }
                    }
                }
            }
            n = n.nextSibling
        }
        return out
    }

    /** "1:02:03.500" / "0:03:21" → ms. */
    fun duration(text: String): Long {
        val p = text.trim().split(':')
        if (p.size < 2) return 0L
        val s = p.last().toDoubleOrNull() ?: return 0L
        val m = p[p.size - 2].toLongOrNull() ?: return 0L
        val h = if (p.size >= 3) p[p.size - 3].toLongOrNull() ?: 0L else 0L
        return ((h * 3600 + m * 60) * 1000 + s * 1000).toLong()
    }

    // ---------------------------------------------------------------- small helpers

    private fun get(url: String): ByteArray? = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 6000
            c.readTimeout = 10000
            if (c.responseCode >= 400) null else c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }.getOrNull()

    private fun parse(bytes: ByteArray) = runCatching {
        val f = DocumentBuilderFactory.newInstance()
        f.isNamespaceAware = true
        runCatching { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        f.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    }.getOrNull()

    private fun local(e: Node): String = (e.localName ?: e.nodeName).substringAfter(':')

    /** Every element named [name] under [e] (whatever its namespace), in order. */
    private fun all(e: Element, name: String): List<Element> {
        val out = ArrayList<Element>()
        fun walk(n: Node) {
            var c = n.firstChild
            while (c != null) {
                if (c is Element) {
                    if (local(c) == name) out += c
                    walk(c)
                }
                c = c.nextSibling
            }
        }
        walk(e)
        return out
    }

    private fun first(e: Element, name: String): Element? = all(e, name).firstOrNull()

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
