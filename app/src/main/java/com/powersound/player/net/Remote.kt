package com.powersound.player.net

import android.content.Context
import android.net.Uri
import java.nio.channels.SeekableByteChannel
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** A file on a network server read like a local one (Windows shared folder, FTP, WebDAV). */
interface RemoteFile : SeekableByteChannel {
    /** Reads up to [len] bytes at the current position into [dst]; -1 at the end. */
    fun read(dst: ByteArray, off: Int, len: Int): Int
}

/** An FTP or WebDAV server added in Library → Network ([kind] "ftp" or "dav"). */
data class FileServer(
    val id: String,
    val kind: String,
    val name: String,
    val host: String,
    val port: Int,
    /** WebDAV over https. */
    val secure: Boolean,
    /** The folder to start in ("" = the top). */
    val root: String,
    val user: String = "",
    val password: String = ""
) {
    val label: String
        get() = when (kind) {
            "ftp" -> "ftp://$host" + (if (port != 21) ":$port" else "") + "/" + root
            else -> (if (secure) "https://" else "http://") + host + (if (port != (if (secure) 443 else 80)) ":$port" else "") + "/" + root
        }
}

/** The FTP and WebDAV servers you added (on the phone only; not in Export Settings, because of the passwords). */
object FileServerStore {
    private const val PREFS = "powersound_fileservers"
    val servers = MutableStateFlow<List<FileServer>>(emptyList())
    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        servers.value = runCatching {
            val a = JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("v", null) ?: "[]")
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                FileServer(
                    o.getString("id"), o.getString("kind"), o.optString("name"), o.getString("host"), o.optInt("port"),
                    o.optBoolean("tls"), o.optString("root"), o.optString("user"), o.optString("pass")
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun save(context: Context) {
        val a = JSONArray()
        servers.value.forEach { s ->
            a.put(
                JSONObject().put("id", s.id).put("kind", s.kind).put("name", s.name).put("host", s.host).put("port", s.port)
                    .put("tls", s.secure).put("root", s.root).put("user", s.user).put("pass", s.password)
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("v", a.toString()).apply()
    }

    fun add(context: Context, s: FileServer) {
        load(context)
        servers.value = servers.value.filterNot { it.id == s.id } + s
        save(context)
    }

    fun remove(context: Context, id: String) {
        load(context)
        servers.value = servers.value.filterNot { it.id == id }
        save(context)
        Ftp.forget(id)
    }

    fun get(context: Context, id: String): FileServer? {
        load(context)
        return servers.value.firstOrNull { it.id == id }
    }

    /**
     * "ftp://192.168.1.5:2121/Music", "192.168.1.5/Music" (FTP), "https://nas.local/dav/Music", "http://pc:8080/" →
     * the server's kind, address, port, https, folder. Null when it can't be read.
     */
    fun parse(address: String, ftpDefault: Boolean): FileServer? {
        var a = address.trim()
        if (a.isEmpty()) return null
        val kind: String
        var secure = false
        when {
            a.startsWith("ftp://", true) -> { kind = "ftp"; a = a.substring(6) }
            a.startsWith("https://", true) || a.startsWith("davs://", true) -> { kind = "dav"; secure = true; a = a.substringAfter("://") }
            a.startsWith("http://", true) || a.startsWith("dav://", true) -> { kind = "dav"; a = a.substringAfter("://") }
            else -> kind = if (ftpDefault) "ftp" else "dav"
        }
        val hostPort = a.substringBefore('/')
        val root = a.substringAfter('/', "").trim('/')
        val host = if (hostPort.startsWith("[")) hostPort.substringBefore(']') + "]" else hostPort.substringBefore(':')
        if (host.isBlank()) return null
        val port = hostPort.substringAfterLast(':', "").takeIf { !hostPort.endsWith("]") }?.toIntOrNull()
            ?: if (kind == "ftp") 21 else if (secure) 443 else 80
        return FileServer("", kind, "", host, port, secure, Uri.decode(root))
    }
}

/**
 * The network files the players read: smb://… (Windows shared folders), psftp://… (FTP), psdav://… (WebDAV).
 * psftp / psdav addresses hold the server's id, not its password: <scheme>://<server id>/<path>.
 */
object Remote {
    private const val FTP = "psftp"
    private const val DAV = "psdav"

    fun isRemote(uri: Uri?): Boolean = uri?.scheme.let { it == "smb" || it == FTP || it == DAV }

    fun isRemote(address: String): Boolean = address.startsWith("smb:") || address.startsWith("$FTP:") || address.startsWith("$DAV:")

    fun uri(s: FileServer, path: String): Uri =
        Uri.Builder().scheme(if (s.kind == "ftp") FTP else DAV).authority(s.id).path("/" + path.trim('/')).build()

    /** The server and the path of a psftp:// / psdav:// address. */
    fun resolve(context: Context, uri: Uri): Pair<FileServer, String>? {
        if (uri.scheme != FTP && uri.scheme != DAV) return null
        val s = FileServerStore.get(context, uri.authority ?: return null) ?: return null
        return s to (uri.path ?: "").trim('/')
    }

    /** Opens a network file to read anywhere in it (null: not a network address, or its server was removed). */
    fun open(context: Context, uri: Uri): RemoteFile? {
        Smb.resolve(context, uri)?.let { (s, p) -> return Smb.open(s, p) }
        val (s, p) = resolve(context, uri) ?: return null
        return if (s.kind == "ftp") Ftp.open(s, p) else Dav.open(s, p)
    }

    /** The file name's MIME type (network files have no other). */
    fun mime(uri: Uri): String? =
        android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension((uri.path ?: "").substringAfterLast('.', "").lowercase())

    fun list(s: FileServer, path: String): List<SmbEntry> = if (s.kind == "ftp") Ftp.list(s, path) else Dav.list(s, path)

    /** Checks the server and its folder: null when they open, else what went wrong. */
    fun test(s: FileServer): String? = runCatching { list(s, s.root); null }.getOrElse { explain(it) }

    /** A folder of the server as the Network screen's items (folders, songs, videos). */
    fun items(s: FileServer, path: String): List<DlnaItem> = list(s, path).mapNotNull { e ->
        val child = if (path.isEmpty()) e.name else "$path/${e.name}"
        if (e.folder) return@mapNotNull DlnaItem(child, e.name, 0)
        val ext = e.name.substringAfterLast('.', "").lowercase()
        val kind = when (ext) {
            in Smb.AUDIO -> 1
            in Smb.VIDEO -> 2
            else -> return@mapNotNull null
        }
        DlnaItem(
            id = child, title = e.name.substringBeforeLast('.'), kind = kind, url = uri(s, child).toString(), size = e.size,
            mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        )
    }

    fun explain(e: Throwable): String {
        val m = e.message ?: e.javaClass.simpleName
        return when {
            e is RemoteFailure -> m
            e is java.net.UnknownHostException -> "Server not found. Use its IP address (e.g. 192.168.1.10)"
            e is javax.net.ssl.SSLException -> "The server's https certificate isn't trusted by Android (a self-made certificate?). Try http:// on the home network"
            e is java.net.ConnectException || e is java.net.SocketTimeoutException || m.contains("timed out", true) ->
                "The server didn't answer. Is it on, on the same Wi-Fi, and is the port right?"
            else -> m
        }
    }

    // ---- network files played without being in the library, sent to the Chromecast through the phone

    private val castable = HashMap<Long, Uri>()

    /** A number for the phone's media server to send [uri] to the Chromecast with ([fileFor] opens it). */
    @Synchronized
    fun castId(uri: Uri): Long {
        val id = -(0x3000_0000_0000_0000L + (uri.toString().hashCode().toLong() and 0xffffffffL))
        castable[id] = uri
        return id
    }

    @Synchronized
    fun castUri(id: Long): Uri? = castable[id]
}

/** A server's own answer to show as it is ("Wrong user name or password"…). */
class RemoteFailure(message: String) : Exception(message)
