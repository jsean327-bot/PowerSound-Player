package com.powersound.player.net

import android.util.Base64
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.channels.NonWritableChannelException
import java.nio.channels.SeekableByteChannel
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * WebDAV (NAS, Nextcloud, ownCloud, phones' and computers' WebDAV servers): folder lists with PROPFIND, files read
 * with ordinary ranged GETs. Basic sign-in.
 */
object Dav {
    private const val TIMEOUT = 20_000

    fun base(s: FileServer): String = (if (s.secure) "https://" else "http://") + s.host +
        (if (s.port != (if (s.secure) 443 else 80)) ":${s.port}" else "")

    /** The address of [path] (each part of it %-encoded). */
    fun url(s: FileServer, path: String, folder: Boolean = false): String {
        val parts = path.trim('/').split('/').filter { it.isNotEmpty() }
        return base(s) + "/" + parts.joinToString("/") { android.net.Uri.encode(it) } + if (folder && parts.isNotEmpty()) "/" else ""
    }

    private fun auth(s: FileServer): String? = if (s.user.isBlank()) null
        else "Basic " + Base64.encodeToString("${s.user}:${s.password}".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    fun connect(s: FileServer, url: String, method: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = TIMEOUT
        c.readTimeout = TIMEOUT
        c.instanceFollowRedirects = true
        c.requestMethod = method
        auth(s)?.let { c.setRequestProperty("Authorization", it) }
        c.setRequestProperty("User-Agent", "PowerSound Player")
        return c
    }

    /**
     * A PROPFIND request: Android's HttpURLConnection only knows the usual methods, so this one is written to the
     * connection by hand (HTTP/1.1, with https when the server uses it). Returns the code and the answer's body.
     */
    fun propfind(s: FileServer, url: String, body: ByteArray): Pair<Int, ByteArray> {
        val u = URL(url)
        val port = if (u.port > 0) u.port else u.defaultPort
        val plain = java.net.Socket()
        plain.connect(java.net.InetSocketAddress(u.host, port), TIMEOUT)
        plain.soTimeout = TIMEOUT
        val sock = if (u.protocol == "https") {
            (javax.net.ssl.SSLSocketFactory.getDefault() as javax.net.ssl.SSLSocketFactory).createSocket(plain, u.host, port, true).also {
                (it as javax.net.ssl.SSLSocket).startHandshake()
                // the certificate must be for this server
                val ok = javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier().verify(u.host, it.session)
                if (!ok) throw javax.net.ssl.SSLPeerUnverifiedException("Certificate is not for ${u.host}")
            }
        } else plain
        sock.use { k ->
            val path = (u.path ?: "/").ifEmpty { "/" } + (u.query?.let { "?$it" } ?: "")
            val head = StringBuilder()
                .append("PROPFIND ").append(path).append(" HTTP/1.1\r\n")
                .append("Host: ").append(u.host).append(if (u.port > 0) ":${u.port}" else "").append("\r\n")
                .append("Depth: 1\r\n")
                .append("Content-Type: application/xml; charset=utf-8\r\n")
                .append("Content-Length: ").append(body.size).append("\r\n")
                .append("User-Agent: PowerSound Player\r\n")
                .append("Connection: close\r\n")
            auth(s)?.let { head.append("Authorization: ").append(it).append("\r\n") }
            head.append("\r\n")
            val out = k.getOutputStream()
            out.write(head.toString().toByteArray(Charsets.UTF_8))
            out.write(body)
            out.flush()
            val input = java.io.BufferedInputStream(k.getInputStream())
            fun line(): String {
                val b = java.io.ByteArrayOutputStream()
                while (true) {
                    val c = input.read()
                    if (c < 0 || c == '\n'.code) break
                    if (c != '\r'.code) b.write(c)
                }
                return b.toString("ISO-8859-1")
            }
            val status = line()
            val code = status.split(' ').getOrNull(1)?.toIntOrNull() ?: throw RemoteFailure("Not a web server")
            var length = -1L
            var chunked = false
            while (true) {
                val h = line()
                if (h.isEmpty()) break
                val name = h.substringBefore(':').trim().lowercase()
                val v = h.substringAfter(':', "").trim()
                if (name == "content-length") length = v.toLongOrNull() ?: -1
                if (name == "transfer-encoding" && v.contains("chunked", true)) chunked = true
            }
            val data = java.io.ByteArrayOutputStream()
            when {
                chunked -> while (true) {
                    val size = line().substringBefore(';').trim().toIntOrNull(16) ?: break
                    if (size == 0) break
                    val b = ByteArray(size)
                    var n = 0
                    while (n < size) {
                        val r = input.read(b, n, size - n)
                        if (r < 0) break
                        n += r
                    }
                    data.write(b, 0, n)
                    line()
                }
                length >= 0 -> {
                    val b = ByteArray(8192)
                    var left = length
                    while (left > 0) {
                        val r = input.read(b, 0, minOf(b.size.toLong(), left).toInt())
                        if (r < 0) break
                        data.write(b, 0, r)
                        left -= r
                    }
                }
                else -> input.copyTo(data)
            }
            return code to data.toByteArray()
        }
    }

    private fun fail(code: Int): Nothing = throw RemoteFailure(
        when (code) {
            401, 403 -> "Wrong user name or password"
            404 -> "There is no such folder on the server"
            405, 501 -> "This address isn't a WebDAV folder"
            else -> "The server answered $code"
        }
    )

    fun list(s: FileServer, path: String): List<SmbEntry> {
        val u = url(s, path, folder = true)
        val (code, body) = propfind(
            s, u,
            ("<?xml version=\"1.0\" encoding=\"utf-8\"?><d:propfind xmlns:d=\"DAV:\"><d:prop>" +
                "<d:resourcetype/><d:getcontentlength/></d:prop></d:propfind>").toByteArray()
        )
        if (code == 301 || code == 302 || code == 307 || code == 308) throw RemoteFailure("The server sends this folder elsewhere: write its full address")
        if (code != 207 && code != 200) fail(code)
        return parse(body.inputStream(), URL(u).path)
    }

    /** A PROPFIND answer: the folder's own entry is left out. */
    fun parse(input: InputStream, selfPath: String): List<SmbEntry> {
        val f = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val doc = input.use { f.newDocumentBuilder().parse(it) }
        val self = android.net.Uri.decode(selfPath).trimEnd('/')
        val out = ArrayList<SmbEntry>()
        val responses = doc.getElementsByTagNameNS("DAV:", "response")
        for (i in 0 until responses.length) {
            val r = responses.item(i) as Element
            val href = r.getElementsByTagNameNS("DAV:", "href").item(0)?.textContent?.trim() ?: continue
            val p = android.net.Uri.decode(runCatching { URL(URL("http://x/"), href).path }.getOrDefault(href)).trimEnd('/')
            if (p == self || p.isEmpty()) continue
            val folder = r.getElementsByTagNameNS("DAV:", "collection").length > 0
            val size = r.getElementsByTagNameNS("DAV:", "getcontentlength").item(0)?.textContent?.trim()?.toLongOrNull() ?: 0
            val name = p.substringAfterLast('/')
            if (name.isEmpty() || name.startsWith(".")) continue
            out += SmbEntry(name, folder, size)
        }
        return out.sortedWith(compareBy({ !it.folder }, { it.name.lowercase() }))
    }

    fun open(s: FileServer, path: String): DavFile {
        val u = url(s, path)
        val c = connect(s, u, "HEAD")
        val size = try {
            val code = c.responseCode
            if (code >= 400) fail(code)
            c.getHeaderField("Content-Length")?.toLongOrNull() ?: -1
        } finally {
            c.disconnect()
        }
        return DavFile(s, u, size)
    }
}

class DavFile(private val s: FileServer, private val url: String, private val length: Long) : RemoteFile {
    private var pos = 0L
    private var open = true
    private var conn: HttpURLConnection? = null
    private var input: InputStream? = null
    private var streamPos = -1L
    private val chunk = ByteArray(64 * 1024)

    private fun stop() {
        runCatching { input?.close() }
        conn?.disconnect()
        conn = null
        input = null
        streamPos = -1
    }

    override fun read(dst: ByteArray, off: Int, len: Int): Int {
        if (length in 0..pos) return -1
        if (input == null || streamPos != pos) {
            stop()
            val c = Dav.connect(s, url, "GET")
            if (pos > 0) c.setRequestProperty("Range", "bytes=$pos-")
            val code = c.responseCode
            if (code >= 400) {
                c.disconnect()
                if (code == 416) return -1
                throw RemoteFailure(if (code == 401 || code == 403) "Wrong user name or password" else "The server answered $code")
            }
            val stream = c.inputStream
            if (pos > 0 && code == 200) {
                // the server ignored the range: skip to the place
                var skip = pos
                while (skip > 0) {
                    val k = stream.skip(skip)
                    if (k <= 0) break
                    skip -= k
                }
            }
            conn = c
            input = stream
            streamPos = pos
        }
        val n = input!!.read(dst, off, len)
        if (n < 0) {
            stop()
            return -1
        }
        pos += n
        streamPos = pos
        return n
    }

    override fun read(dst: ByteBuffer): Int {
        val want = minOf(dst.remaining(), chunk.size)
        if (want == 0) return 0
        val n = read(chunk, 0, want)
        if (n > 0) dst.put(chunk, 0, n)
        return n
    }

    override fun write(src: ByteBuffer): Int = throw NonWritableChannelException()
    override fun position(): Long = pos
    override fun position(newPosition: Long): SeekableByteChannel {
        pos = newPosition.coerceAtLeast(0)
        return this
    }

    override fun size(): Long = if (length >= 0) length else Long.MAX_VALUE
    override fun truncate(size: Long): SeekableByteChannel = throw NonWritableChannelException()
    override fun isOpen(): Boolean = open
    override fun close() {
        if (!open) return
        open = false
        stop()
    }
}
