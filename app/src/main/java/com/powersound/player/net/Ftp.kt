package com.powersound.player.net

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.channels.NonWritableChannelException
import java.nio.channels.SeekableByteChannel

/**
 * A small FTP client (passive mode, binary): folder lists and files read from any place (REST + RETR), which is
 * all the players need. Plain FTP only (no FTPS), as home servers and phones' "FTP server" apps use.
 */
object Ftp {
    private const val TIMEOUT = 20_000

    /** One open connection per server for the folder lists. */
    private val lists = HashMap<String, Conn>()

    class Conn(private val s: FileServer) {
        private val socket = Socket()
        private val reader: BufferedReader
        private val out: OutputStream
        /** The server lists folders the modern way (MLSD: exact names, sizes and kinds). */
        var mlsd = false
            private set

        init {
            socket.connect(InetSocketAddress(s.host, s.port), TIMEOUT)
            socket.soTimeout = TIMEOUT
            reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            out = socket.getOutputStream()
            expect(reply(), 2)
            val u = cmd("USER " + s.user.ifBlank { "anonymous" })
            if (u.first == 331 || u.first == 332) {
                val p = cmd("PASS " + if (s.user.isBlank()) "anonymous@" else s.password)
                if (p.first == 530) throw RemoteFailure("Wrong user name or password")
                expect(p, 2)
            } else if (u.first == 530) throw RemoteFailure("Wrong user name or password") else expect(u, 2)
            val feat = cmd("FEAT")
            mlsd = feat.first / 100 == 2 && feat.second.contains("MLSD", true)
            if (feat.second.contains("UTF8", true)) cmd("OPTS UTF8 ON")
            expect(cmd("TYPE I"), 2)
        }

        val isOpen: Boolean get() = socket.isConnected && !socket.isClosed

        /** Reads one (maybe many-line) answer: its code and its text. */
        fun reply(): Pair<Int, String> {
            val first = reader.readLine() ?: throw java.io.EOFException("The FTP server closed the connection")
            val code = first.take(3).toIntOrNull() ?: throw RemoteFailure("Not an FTP server")
            val text = StringBuilder(first)
            if (first.length > 3 && first[3] == '-') {
                while (true) {
                    val l = reader.readLine() ?: break
                    text.append('\n').append(l)
                    if (l.length >= 4 && l.startsWith(first.take(3)) && l[3] == ' ') break
                }
            }
            return code to text.toString()
        }

        fun cmd(line: String): Pair<Int, String> {
            out.write((line + "\r\n").toByteArray(Charsets.UTF_8))
            out.flush()
            return reply()
        }

        private fun expect(r: Pair<Int, String>, cls: Int) {
            if (r.first / 100 != cls) throw RemoteFailure("FTP: " + r.second.substringBefore('\n').drop(4).ifBlank { r.second })
        }

        /** A passive data connection (EPSV, else PASV). */
        fun data(): Socket {
            val e = cmd("EPSV")
            val addr = if (e.first == 229) {
                val port = Regex("\\(\\|\\|\\|(\\d+)\\|\\)").find(e.second)?.groupValues?.get(1)?.toInt()
                    ?: throw RemoteFailure("FTP: bad passive answer")
                InetSocketAddress(socket.inetAddress, port)
            } else {
                val p = cmd("PASV")
                expect(p, 2)
                val n = Regex("(\\d+),(\\d+),(\\d+),(\\d+),(\\d+),(\\d+)").find(p.second)?.groupValues?.drop(1)?.map { it.toInt() }
                    ?: throw RemoteFailure("FTP: bad passive answer")
                // the server's own address (some give 0.0.0.0 or an address inside their own network)
                InetSocketAddress(socket.inetAddress, n[4] * 256 + n[5])
            }
            val d = Socket()
            d.connect(addr, TIMEOUT)
            d.soTimeout = TIMEOUT
            return d
        }

        /** Starts a transfer on [d] ("RETR x" / "MLSD x"): true when the server began it. */
        fun start(command: String) {
            val r = cmd(command)
            if (r.first == 550) throw RemoteFailure("Not found on the FTP server: " + command.substringAfter(' '))
            if (r.first / 100 != 1) throw RemoteFailure("FTP: " + r.second.substringBefore('\n').drop(4))
        }

        /** The end of a transfer (226), read after its data connection closed. */
        fun finish() {
            runCatching { reply() }
        }

        fun size(path: String): Long {
            val r = cmd("SIZE " + path)
            return if (r.first == 213) r.second.drop(4).trim().toLongOrNull() ?: -1 else -1
        }

        fun close() {
            runCatching { cmd("QUIT") }
            runCatching { socket.close() }
        }

        /** Closes at once, without waiting for the server (in the middle of a transfer). */
        fun drop() {
            runCatching { socket.close() }
        }
    }

    private fun full(s: FileServer, path: String) = "/" + path.trim('/')

    @Synchronized
    fun forget(id: String) {
        lists.remove(id)?.close()
    }

    private fun <T> withList(s: FileServer, f: (Conn) -> T): T {
        synchronized(this) {
            val c = lists[s.id]?.takeIf { it.isOpen } ?: Conn(s).also { lists[s.id] = it }
            return try {
                f(c)
            } catch (e: java.io.IOException) {
                // the old connection had dropped: once more on a new one
                runCatching { c.close() }
                val n = Conn(s)
                lists[s.id] = n
                f(n)
            }
        }
    }

    /** A folder: folders first, then files, by name (hidden "." entries left out). */
    fun list(s: FileServer, path: String): List<SmbEntry> = withList(s) { c ->
        val target = full(s, path)
        val d = c.data()
        val lines = try {
            c.start((if (c.mlsd) "MLSD " else "LIST -a ") + target)
            d.getInputStream().bufferedReader(Charsets.UTF_8).readLines()
        } finally {
            runCatching { d.close() }
        }
        c.finish()
        lines.mapNotNull { if (c.mlsd) mlsdLine(it) else listLine(it) }
            .filter { it.name != "." && it.name != ".." && !it.name.startsWith(".") }
            .sortedWith(compareBy({ !it.folder }, { it.name.lowercase() }))
    }

    /** "type=file;size=1234;modify=…; Song.flac" */
    fun mlsdLine(l: String): SmbEntry? {
        val sp = l.indexOf(' ')
        if (sp < 0) return null
        val facts = l.substring(0, sp).split(';').mapNotNull { f -> f.indexOf('=').takeIf { it > 0 }?.let { f.substring(0, it).lowercase() to f.substring(it + 1) } }.toMap()
        val name = l.substring(sp + 1)
        val type = facts["type"]?.lowercase() ?: return null
        if (type == "cdir" || type == "pdir") return null
        return SmbEntry(name, type == "dir" || type.startsWith("os.unix=slink"), facts["size"]?.toLongOrNull() ?: 0)
    }

    /** Unix "drwxr-xr-x 1 u g 4096 Jan 1 10:00 Name" or Windows "01-02-24  10:00AM  <DIR>  Name". */
    fun listLine(l: String): SmbEntry? {
        val unix = Regex("^([dl-])[rwxsStT-]{9}\\S*\\s+\\d+\\s+\\S+\\s+\\S+\\s+(\\d+)\\s+\\S+\\s+\\S+\\s+\\S+\\s(.+)$").find(l)
        if (unix != null) {
            val (t, size, rawName) = unix.destructured
            val name = if (t == "l") rawName.substringBefore(" -> ") else rawName
            return SmbEntry(name, t == "d" || t == "l", size.toLongOrNull() ?: 0)
        }
        val dos = Regex("^\\d{2}-\\d{2}-\\d{2,4}\\s+\\d{1,2}:\\d{2}(?:AM|PM)?\\s+(<DIR>|\\d+)\\s+(.+)$", RegexOption.IGNORE_CASE).find(l)
        if (dos != null) {
            val (kind, name) = dos.destructured
            return SmbEntry(name, kind.equals("<DIR>", true), kind.toLongOrNull() ?: 0)
        }
        return null
    }

    /** A file to read anywhere in it: its own connection, a new transfer from the place asked for after a jump. */
    fun open(s: FileServer, path: String): FtpFile {
        val c = Conn(s)
        val p = full(s, path)
        val size = c.size(p)
        return FtpFile(s, c, p, size)
    }
}

class FtpFile(private val server: FileServer, private var c: Ftp.Conn, private val path: String, private val length: Long) : RemoteFile {
    private var pos = 0L
    private var open = true
    private var data: Socket? = null
    private var input: InputStream? = null
    private var streamPos = -1L
    private var fresh = true
    private val chunk = ByteArray(64 * 1024)

    /**
     * A jump to another place: FTP can't move a transfer, and stopping one (ABOR) is answered differently by each
     * server, so the connection is closed and a new one starts from the new place (quick on the home network).
     */
    private fun restart() {
        runCatching { data?.close() }
        data = null
        input = null
        streamPos = -1
        if (!fresh) {
            c.drop()
            c = Ftp.Conn(server)
        }
        fresh = false
    }

    override fun read(dst: ByteArray, off: Int, len: Int): Int {
        if (length in 0..pos) return -1
        if (input == null || streamPos != pos) {
            restart()
            val d = c.data()
            try {
                if (pos > 0) {
                    val r = c.cmd("REST $pos")
                    if (r.first / 100 != 3) throw RemoteFailure("The FTP server can't start a file in the middle")
                }
                c.start("RETR $path")
            } catch (e: Exception) {
                runCatching { d.close() }
                throw e
            }
            data = d
            input = d.getInputStream()
            streamPos = pos
        }
        val n = input!!.read(dst, off, len)
        if (n < 0) {
            // the end of the transfer
            runCatching { data?.close() }
            data = null
            input = null
            c.finish()
            streamPos = -1
            fresh = true // the connection is free again for a next transfer
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
        val busy = data != null
        runCatching { data?.close() }
        if (busy) c.drop() else c.close()
    }
}
