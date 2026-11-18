package com.powersound.player.net

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/** Plays smb:// addresses (Library → Network → Windows shared folders) straight from the shared folder. */
@OptIn(UnstableApi::class)
class SmbDataSource(private val context: Context) : BaseDataSource(true) {
    private var channel: SmbChannel? = null
    private var uri: Uri? = null
    private var left = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val (server, path) = Smb.resolve(context, dataSpec.uri)
            ?: throw DataSourceException(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
        val ch = try {
            Smb.open(server, path)
        } catch (e: Exception) {
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        }
        channel = ch
        if (dataSpec.position > ch.size()) throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        ch.position(dataSpec.position)
        left = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else ch.size() - dataSpec.position
        opened = true
        transferStarted(dataSpec)
        return left
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (left == 0L) return C.RESULT_END_OF_INPUT
        val ch = channel ?: return C.RESULT_END_OF_INPUT
        val n = try {
            ch.read(buffer, offset, minOf(length.toLong(), left).toInt())
        } catch (e: Exception) {
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        }
        if (n < 0) return C.RESULT_END_OF_INPUT
        left -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        runCatching { channel?.close() }
        channel = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }
}

/**
 * The players' sources: smb:// from the shared folder ([SmbDataSource]), everything else as before
 * (the phone's files, web addresses with their headers…).
 */
@OptIn(UnstableApi::class)
class NetDataSourceFactory(private val context: Context, private val inner: DataSource.Factory) : DataSource.Factory {
    override fun createDataSource(): DataSource = Router(context.applicationContext, inner.createDataSource())

    private class Router(private val context: Context, private val inner: DataSource) : DataSource {
        private var current: DataSource? = null
        private val listeners = ArrayList<TransferListener>()

        override fun addTransferListener(transferListener: TransferListener) {
            listeners += transferListener
            inner.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            val ds = if (dataSpec.uri.scheme == "smb") SmbDataSource(context).also { s -> listeners.forEach { s.addTransferListener(it) } } else inner
            current = ds
            return ds.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            current?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT

        override fun getUri(): Uri? = current?.uri

        override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders ?: emptyMap()

        override fun close() {
            try {
                current?.close()
            } finally {
                current = null
            }
        }
    }
}
