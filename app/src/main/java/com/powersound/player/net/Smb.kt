package com.powersound.player.net

import android.content.Context
import android.net.Uri
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import java.nio.ByteBuffer
import java.nio.channels.NonWritableChannelException
import java.nio.channels.SeekableByteChannel
import java.util.EnumSet
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** A Windows shared folder (SMB) added in Library → Network: \\host\share, with its user (empty = guest). */
data class SmbServer(
    val id: String,
    val name: String,
    val host: String,
    val share: String,
    val user: String = "",
    val password: String = "",
    val domain: String = "",
    /** A folder inside the share to start in ("" = the share itself). */
    val root: String = ""
)

/** The shared folders you added (kept on the phone only; not in Export Settings, because of the passwords). */
object SmbStore {
    private const val PREFS = "powersound_smb"
    val servers = MutableStateFlow<List<SmbServer>>(emptyList())
    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        servers.value = runCatching {
            val a = JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("v", null) ?: "[]")
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                SmbServer(o.getString("id"), o.optString("name"), o.getString("host"), o.getString("share"), o.optString("user"), o.optString("pass"), o.optString("dom"), o.optString("root"))
            }
        }.getOrDefault(emptyList())
    }

    private fun save(context: Context) {
        val a = JSONArray()
        servers.value.forEach { s ->
            a.put(JSONObject().put("id", s.id).put("name", s.name).put("host", s.host).put("share", s.share).put("user", s.user).put("pass", s.password).put("dom", s.domain).put("root", s.root))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("v", a.toString()).apply()
    }

    fun add(context: Context, s: SmbServer) {
        load(context)
        servers.value = servers.value.filterNot { it.id == s.id } + s
        save(context)
    }

    fun remove(context: Context, id: String) {
        load(context)
        servers.value = servers.value.filterNot { it.id == id }
        save(context)
        Smb.forget(id)
    }

    fun get(context: Context, id: String): SmbServer? {
        load(context)
        return servers.value.firstOrNull { it.id == id }
    }

    /** "\\192.168.1.10\Music\Arabic", "192.168.1.10/Music", "smb://PC/Music" → host, share, folder inside. */
    fun parse(address: String): Triple<String, String, String>? {
        val p = address.trim().removePrefix("smb:").replace('\\', '/').trim('/').split('/').filter { it.isNotBlank() }
        if (p.size < 2) return null
        return Triple(p[0], p[1], p.drop(2).joinToString("/"))
    }
}

/** One thing in a shared folder. */
data class SmbEntry(val name: String, val folder: Boolean, val size: Long)

/**
 * Reading Windows shared folders (SMB 2/3, with smbj). One connection per shared folder is kept open and reused;
 * it is opened again by itself when it was dropped (the PC slept, the Wi-Fi changed…).
 */
object Smb {
    private val client by lazy {
        SMBClient(
            SmbConfig.builder()
                .withTimeout(20, TimeUnit.SECONDS)
                .withSoTimeout(30, TimeUnit.SECONDS)
                .build()
        )
    }
    private val shares = HashMap<String, DiskShare>()

    @Synchronized
    private fun share(s: SmbServer, fresh: Boolean = false): DiskShare {
        val old = shares[s.id]
        if (old != null && !fresh && old.isConnected) return old
        runCatching { old?.close() }
        val connection = client.connect(s.host)
        val auth = if (s.user.isBlank()) AuthenticationContext.guest()
            else AuthenticationContext(s.user, s.password.toCharArray(), s.domain.ifBlank { null })
        val session = connection.authenticate(auth)
        val d = session.connectShare(s.share) as? DiskShare ?: throw IllegalStateException("\\\\${s.host}\\${s.share} is not a shared folder")
        shares[s.id] = d
        return d
    }

    @Synchronized
    fun forget(id: String) {
        runCatching { shares.remove(id)?.close() }
    }

    /** Runs [f] on the share, once more on a new connection if the old one had dropped. */
    private fun <T> withShare(s: SmbServer, f: (DiskShare) -> T): T = try {
        f(share(s))
    } catch (e: Exception) {
        f(share(s, fresh = true))
    }

    private fun win(path: String) = path.trim('/').replace('/', '\\')

    /** Checks that the shared folder opens with these details: null when it does, else what went wrong. */
    fun test(s: SmbServer): String? = runCatching { withShare(s) { it.list("") }; null }.getOrElse { e -> explain(e) }

    /** Folders first, then files, by name. Hidden and system entries ("." "..", "$RECYCLE.BIN") are left out. */
    fun list(s: SmbServer, path: String): List<SmbEntry> = withShare(s) { d ->
        d.list(win(path)).mapNotNull { f ->
            val name = f.fileName
            if (name == "." || name == ".." || name.startsWith("$")) return@mapNotNull null
            val attrs = f.fileAttributes
            if (attrs and FileAttributes.FILE_ATTRIBUTE_HIDDEN.value != 0L) return@mapNotNull null
            SmbEntry(name, attrs and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L, f.endOfFile)
        }.sortedWith(compareBy({ !it.folder }, { it.name.lowercase() }))
    }

    /** A file of the share to read anywhere in it (for the players and the Chromecast). */
    fun open(s: SmbServer, path: String): SmbChannel = withShare(s) { d ->
        val f = d.openFile(
            win(path),
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ, SMB2ShareAccess.FILE_SHARE_WRITE, SMB2ShareAccess.FILE_SHARE_DELETE),
            SMB2CreateDisposition.FILE_OPEN,
            null
        )
        SmbChannel(f, f.fileInformation.standardInformation.endOfFile)
    }

    private val AUDIO = setOf("mp3", "flac", "m4a", "aac", "ogg", "oga", "opus", "wav", "wma", "alac", "ape", "wv", "aif", "aiff", "mka", "dsf", "dff")
    private val VIDEO = setOf("mp4", "mkv", "avi", "mov", "webm", "m4v", "ts", "m2ts", "3gp", "wmv", "flv", "mpg", "mpeg")

    /** A folder of the share as the Network screen's items: folders, songs and videos (other files are left out). */
    fun items(s: SmbServer, path: String): List<DlnaItem> = list(s, path).mapNotNull { e ->
        val child = if (path.isEmpty()) e.name else "$path/${e.name}"
        if (e.folder) return@mapNotNull DlnaItem(child, e.name, 0)
        val ext = e.name.substringAfterLast('.', "").lowercase()
        val kind = when (ext) {
            in AUDIO -> 1
            in VIDEO -> 2
            else -> return@mapNotNull null
        }
        DlnaItem(
            id = child,
            title = e.name.substringBeforeLast('.'),
            kind = kind,
            url = uri(s, child).toString(),
            size = e.size,
            mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        )
    }

    /** What to tell the user about an SMB error. */
    fun explain(e: Throwable): String {
        val m = (e.message ?: e.javaClass.simpleName)
        return when {
            m.contains("STATUS_LOGON_FAILURE") || m.contains("STATUS_ACCESS_DENIED") -> "Wrong user name or password"
            m.contains("STATUS_BAD_NETWORK_NAME") -> "There is no shared folder with this name on that computer"
            m.contains("STATUS_ACCOUNT_RESTRICTION") || m.contains("STATUS_ACCOUNT_DISABLED") ->
                "This Windows account can't sign in from the network (an account without a password, for example)"
            e is java.net.UnknownHostException -> "Computer not found. Use its IP address (e.g. 192.168.1.10)"
            e is java.net.ConnectException || e is java.net.SocketTimeoutException || m.contains("timed out", true) ->
                "The computer didn't answer. Is it on, on the same Wi-Fi, with file sharing turned on?"
            else -> m
        }
    }

    // ---- smb://<shared folder id>/<path> addresses, for the players

    fun uri(s: SmbServer, path: String): Uri = Uri.Builder().scheme("smb").authority(s.id).path("/" + path.trim('/')).build()

    /** The shared folder and the path of an smb:// address made by [uri]. */
    fun resolve(context: Context, uri: Uri): Pair<SmbServer, String>? {
        if (uri.scheme != "smb") return null
        val s = SmbStore.get(context, uri.authority ?: return null) ?: return null
        return s to (uri.path ?: "").trim('/')
    }
}

/** An SMB file read like a local one (position, size, read), as the phone's media server and ExoPlayer need it. */
class SmbChannel(private val file: com.hierynomus.smbj.share.File, private val length: Long) : SeekableByteChannel {
    private var pos = 0L
    private var open = true
    private val chunk = ByteArray(64 * 1024)

    /** Reads up to [len] bytes at the current position into [dst]; -1 at the end. */
    fun read(dst: ByteArray, off: Int, len: Int): Int {
        if (pos >= length) return -1
        val n = file.read(dst, pos, off, minOf(len, 64 * 1024, (length - pos).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()))
        if (n > 0) pos += n
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
        pos = newPosition.coerceAtLeast(0L)
        return this
    }

    override fun size(): Long = length
    override fun truncate(size: Long): SeekableByteChannel = throw NonWritableChannelException()
    override fun isOpen(): Boolean = open
    override fun close() {
        if (open) {
            open = false
            runCatching { file.close() }
        }
    }
}
