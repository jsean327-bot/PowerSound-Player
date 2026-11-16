package com.powersound.player.cast

import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileChannel
import java.security.SecureRandom
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** One file the server can send: its bytes (a channel that can jump to any position), size and type. */
class ServedFile(val channel: FileChannel, val size: Long, val mime: String, private val closer: () -> Unit) {
    fun close() = runCatching { closer() }
}

/**
 * A tiny web server inside the app, for casting: the Chromecast fetches the songs (and covers) from the phone
 * over the home Wi-Fi. Only devices that know the random key in the address get anything.
 *
 * Supports `Range` requests, which the Chromecast uses to start in the middle of a song and to jump.
 *
 * [openSong] gives the file of a song id; [cover] gives a JPEG of its cover (or null).
 *
 * For videos it can also fetch a web video for the Chromecast with the headers its site wants ([proxyUrl]) and
 * give subtitles as WebVTT ([textUrl]).
 */
class MediaServer(
    private val openSong: (Long) -> ServedFile?,
    private val cover: (Long) -> ByteArray?
) {
    private val key: String = ByteArray(12).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    @Volatile
    private var server: ServerSocket? = null
    private var pool: ExecutorService? = null

    private val proxies = java.util.concurrent.ConcurrentHashMap<Long, ProxyTarget>()
    private val texts = java.util.concurrent.ConcurrentHashMap<Long, () -> ByteArray?>()
    private val ids = java.util.concurrent.atomic.AtomicLong(0)

    /** Address through the phone of a web video: the phone fetches it with [target]'s headers. Null without Wi-Fi. */
    fun proxyUrl(target: ProxyTarget): String? {
        val id = ids.incrementAndGet()
        proxies[id] = target
        return base()?.let { "$it/p/$id" }
    }

    /** Address of a text made when asked (a subtitle as WebVTT). Null without Wi-Fi. */
    fun textUrl(text: () -> ByteArray?): String? {
        val id = ids.incrementAndGet()
        texts[id] = text
        return base()?.let { "$it/t/$id.vtt" }
    }

    val port: Int get() = server?.localPort ?: 0
    val running: Boolean get() = server?.isClosed == false

    @Synchronized
    fun start(): Boolean {
        if (running) return true
        return runCatching {
            val s = ServerSocket(0, 64)
            val p = Executors.newCachedThreadPool { r -> Thread(r, "PowerSound-cast").apply { isDaemon = true } }
            server = s
            pool = p
            p.execute {
                while (!s.isClosed) {
                    val client = runCatching { s.accept() }.getOrNull() ?: break
                    runCatching { p.execute { serve(client) } }.onFailure { runCatching { client.close() } }
                }
            }
            true
        }.getOrDefault(false)
    }

    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
        pool?.shutdownNow()
        pool = null
    }

    /** Address of a song for the Chromecast, or null when the phone has no Wi-Fi / hotspot address. */
    fun songUrl(id: Long): String? = base()?.let { "$it/s/$id" }

    fun coverUrl(id: Long): String? = base()?.let { "$it/a/$id.jpg" }

    private fun base(): String? {
        if (!running) return null
        val host = localAddress()?.hostAddress ?: return null
        return "http://$host:$port/$key"
    }

    // ---------------------------------------------------------------- one request

    private fun serve(socket: Socket) {
        socket.use { s ->
            runCatching {
                s.soTimeout = 30_000
                s.tcpNoDelay = true
                val input = BufferedInputStream(s.getInputStream())
                val out = s.getOutputStream()
                val requestLine = readLine(input) ?: return
                val headers = HashMap<String, String>()
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    val colon = line.indexOf(':')
                    if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
                }
                val parts = requestLine.split(' ')
                if (parts.size < 2) return
                val method = parts[0].uppercase()
                val head = method == "HEAD"
                if (method == "OPTIONS") {
                    status(out, 204, "No Content", listOf("Content-Length" to "0")); return
                }
                if (method != "GET" && !head) {
                    status(out, 405, "Method Not Allowed", listOf("Content-Length" to "0")); return
                }
                // /<key>/s/<id>  or  /<key>/a/<id>.jpg
                val path = parts[1].substringBefore('?').trim('/').split('/')
                if (path.size != 3 || path[0] != key) {
                    status(out, 404, "Not Found", listOf("Content-Length" to "0")); return
                }
                val id = path[2].substringBefore('.').toLongOrNull()
                if (id == null) {
                    status(out, 404, "Not Found", listOf("Content-Length" to "0")); return
                }
                when (path[1]) {
                    "s" -> sendSong(out, id, headers["range"], head)
                    // a web video through the phone: /p/<id> itself, /x/<id>?u=<address> a piece of it (HLS)
                    "p", "x" -> {
                        val target = proxies[id]
                        val url = if (path[1] == "p") target?.url else query(parts[1], "u")
                        if (target == null || url == null) status(out, 404, "Not Found", listOf("Content-Length" to "0"))
                        else sendProxy(out, id, target, url, headers["range"], head)
                    }
                    "t" -> {
                        val vtt = runCatching { texts[id]?.invoke() }.getOrNull()
                        if (vtt == null) status(out, 404, "Not Found", listOf("Content-Length" to "0"))
                        else {
                            status(out, 200, "OK", listOf("Content-Type" to "text/vtt; charset=utf-8", "Content-Length" to vtt.size.toString()))
                            if (!head) out.write(vtt)
                        }
                    }
                    "a" -> {
                        val jpg = cover(id)
                        if (jpg == null) status(out, 404, "Not Found", listOf("Content-Length" to "0"))
                        else {
                            status(out, 200, "OK", listOf("Content-Type" to "image/jpeg", "Content-Length" to jpg.size.toString()))
                            if (!head) out.write(jpg)
                        }
                    }
                    else -> status(out, 404, "Not Found", listOf("Content-Length" to "0"))
                }
                out.flush()
            }
        }
    }

    private fun sendSong(out: OutputStream, id: Long, range: String?, head: Boolean) {
        val f = openSong(id)
        if (f == null) {
            status(out, 404, "Not Found", listOf("Content-Length" to "0")); return
        }
        try {
            val size = f.size
            var start = 0L
            var end = size - 1
            var partial = false
            if (range != null && range.startsWith("bytes=")) {
                val spec = range.removePrefix("bytes=").substringBefore(',').trim()
                val a = spec.substringBefore('-').trim()
                val b = spec.substringAfter('-', "").trim()
                if (a.isEmpty() && b.isNotEmpty()) {
                    // "bytes=-500": the last 500 bytes
                    start = (size - (b.toLongOrNull() ?: 0L)).coerceAtLeast(0L)
                } else {
                    start = a.toLongOrNull() ?: 0L
                    if (b.isNotEmpty()) end = minOf(b.toLongOrNull() ?: end, size - 1)
                }
                partial = true
                if (start >= size || start > end) {
                    status(out, 416, "Range Not Satisfiable", listOf("Content-Range" to "bytes */$size", "Content-Length" to "0"))
                    return
                }
            }
            val length = end - start + 1
            val common = listOf(
                "Content-Type" to f.mime,
                "Accept-Ranges" to "bytes",
                "Content-Length" to length.toString()
            )
            if (partial) status(out, 206, "Partial Content", common + ("Content-Range" to "bytes $start-$end/$size"))
            else status(out, 200, "OK", common)
            if (head) return
            f.channel.position(start)
            val buf = java.nio.ByteBuffer.allocate(64 * 1024)
            var left = length
            while (left > 0) {
                buf.clear()
                if (left < buf.capacity()) buf.limit(left.toInt())
                val n = f.channel.read(buf)
                if (n <= 0) break
                out.write(buf.array(), 0, n)
                left -= n
            }
        } finally {
            f.close()
        }
    }

    private fun query(target: String, name: String): String? =
        target.substringAfter('?', "").split('&').firstOrNull { it.startsWith("$name=") }
            ?.substringAfter('=')?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull() }

    /** Fetches [url] with the site's headers and hands it to the Chromecast; HLS playlists are rewritten on the way. */
    private fun sendProxy(out: OutputStream, id: Long, target: ProxyTarget, url: String, range: String?, head: Boolean) {
        val c = try {
            // HEAD is asked as one byte with GET: many video sites answer HEAD with an error
            CastWeb.open(url, target.headers, if (head) "bytes=0-0" else range)
        } catch (e: Exception) {
            status(out, 502, "Bad Gateway", listOf("Content-Length" to "0")); return
        }
        try {
            val code = c.responseCode
            val type = c.contentType
            if (code >= 400) {
                status(out, code, c.responseMessage ?: "Error", listOf("Content-Length" to "0")); return
            }
            if (head) {
                val h = ArrayList<Pair<String, String>>()
                h += "Content-Type" to (if (CastWeb.looksLikeHls(type, c.url.toString())) CastWeb.HLS_MIME else type ?: "application/octet-stream")
                val total = c.getHeaderField("Content-Range")?.substringAfter('/', "")?.toLongOrNull()
                    ?: c.getHeaderField("Content-Length")?.toLongOrNull()?.takeIf { code == 200 }
                if (total != null) h += "Content-Length" to total.toString()
                h += "Accept-Ranges" to "bytes"
                status(out, 200, "OK", h); return
            }
            val input = java.io.BufferedInputStream(c.inputStream, 64 * 1024)
            val final = c.url.toString()
            if (CastWeb.looksLikeHls(type, final) || CastWeb.startsAsHls(input)) {
                // relative to this playlist's own address (…/<key>/p/<id> or …/<key>/x/<id>): …/<key>/x/<id>?u=…
                val text = CastWeb.rewriteHls(String(CastWeb.readText(input), Charsets.UTF_8), final) { u ->
                    "../x/$id?u=" + java.net.URLEncoder.encode(u, "UTF-8")
                }.toByteArray(Charsets.UTF_8)
                status(
                    out, 200, "OK",
                    listOf("Content-Type" to CastWeb.HLS_MIME, "Content-Length" to text.size.toString(), "Cache-Control" to "no-cache")
                )
                out.write(text)
                return
            }
            val h = ArrayList<Pair<String, String>>()
            h += "Content-Type" to (type ?: "application/octet-stream")
            c.getHeaderField("Content-Length")?.let { h += "Content-Length" to it }
            c.getHeaderField("Content-Range")?.let { h += "Content-Range" to it }
            h += "Accept-Ranges" to (c.getHeaderField("Accept-Ranges") ?: "bytes")
            status(out, code, if (code == 206) "Partial Content" else "OK", h)
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
            }
        } finally {
            runCatching { c.disconnect() }
        }
    }

    private fun status(out: OutputStream, code: Int, text: String, headers: List<Pair<String, String>>) {
        val sb = StringBuilder("HTTP/1.1 $code $text\r\n")
        for ((k, v) in headers) sb.append(k).append(": ").append(v).append("\r\n")
        sb.append("Access-Control-Allow-Origin: *\r\n")
        sb.append("Access-Control-Allow-Headers: Range, Content-Type\r\n")
        sb.append("Access-Control-Expose-Headers: Content-Length, Content-Range, Accept-Ranges\r\n")
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length > 8192) return null
            sb.append(c.toChar())
        }
    }

    companion object {
        /** The phone's address on the Wi-Fi (or its own hotspot). */
        fun localAddress(): InetAddress? = runCatching {
            val all = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            fun v4(n: NetworkInterface) = n.inetAddresses.toList().firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            // Wi-Fi first, then the hotspot, then anything on the local network
            all.filter { it.name.startsWith("wlan") }.firstNotNullOfOrNull { v4(it) }
                ?: all.filter { it.name.startsWith("swlan") || it.name.startsWith("ap") }.firstNotNullOfOrNull { v4(it) }
                ?: all.mapNotNull { v4(it) }.firstOrNull { it.isSiteLocalAddress }
        }.getOrNull()
    }
}
