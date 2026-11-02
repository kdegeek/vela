package app.vela.tv.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import app.vela.tv.media.JellyfinClient
import app.vela.tv.media.JellyfinStream
import java.io.IOException

/** Media3 adapter; HTTP origin, redirect and range handling is exercised by JellyfinStreamTest. */
@UnstableApi
internal class JellyfinDataSource(private val client: JellyfinClient) : BaseDataSource(true) {
    private var stream: JellyfinStream? = null
    private var opened = false
    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val source = JellyfinStream(client, dataSpec.uri.toString(), dataSpec.position, dataSpec.length)
        stream = source
        opened = true
        transferStarted(dataSpec)
        return source.remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val read = stream?.read(buffer, offset, length) ?: throw IOException("Media stream is closed.")
        if (read > 0) bytesTransferred(read)
        return if (read < 0) C.RESULT_END_OF_INPUT else read
    }

    override fun getUri(): Uri? = stream?.let { Uri.parse(it.url.toString()) }
    override fun getResponseHeaders(): Map<String, List<String>> = stream?.headers.orEmpty()
    override fun close() {
        try { stream?.close() } finally {
            stream = null
            if (opened) { opened = false; transferEnded() }
        }
    }

    class Factory(private val client: JellyfinClient) : DataSource.Factory {
        override fun createDataSource(): DataSource = JellyfinDataSource(client)
    }
}
