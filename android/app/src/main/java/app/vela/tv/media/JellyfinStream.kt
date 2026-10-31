package app.vela.tv.media

import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import okhttp3.Request
import okhttp3.Response

/** Progressive source with validated ranges. No Android dependency, so real HTTP seek failures are testable. */
internal class JellyfinStream(client: JellyfinClient, address: String, position: Long, length: Long) : Closeable {
    private var response: Response? = null
    private var input: InputStream? = null
    val url = client.mediaUrl(address)
    val headers: Map<String, List<String>> get() = response?.headers?.toMultimap().orEmpty()
    var remaining: Long = -1
        private set

    init {
        require(position >= 0 && (length == -1L || length > 0))
        require(length == -1L || position <= Long.MAX_VALUE - length) { "Media range overflow." }
        try {
            val request = Request.Builder().url(url).header("Authorization", client.authHeader())
                .header("Accept-Encoding", "identity").apply {
                    if (position != 0L || length != -1L) header("Range", "bytes=$position-${if (length != -1L) position + length - 1 else ""}")
                }.build()
            val res = client.mediaHttp.newCall(request).execute()
            response = res
            if (res.code == 416 && res.header("Content-Range") == "bytes */$position") {
                remaining = 0
            } else {
                if (res.code == 401 || res.code == 403) throw JellyfinAuthError("Jellyfin rejected media access (${res.code}).")
                if (!res.isSuccessful) throw IOException("Jellyfin media request failed (${res.code}).")
                if (res.code == 206) {
                    val returnedStart = res.header("Content-Range")?.let { Regex("bytes (\\d+)-\\d+/(?:\\d+|\\*)").matchEntire(it)?.groupValues?.get(1)?.toLongOrNull() }
                    if (returnedStart != position) throw IOException("Jellyfin returned an incorrect media range.")
                }
                input = res.body.byteStream()
                val skip = if (res.code == 200) position else 0
                skipExactly(skip)
                val available = res.body.contentLength().let { if (it >= 0) (it - skip).coerceAtLeast(0) else -1 }
                remaining = if (length != -1L) length else available
            }
        } catch (e: Exception) { close(); throw e }
    }

    private fun skipExactly(count: Long) {
        var left = count
        val scratch = ByteArray(8192)
        while (left > 0) {
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException()
            val read = input!!.read(scratch, 0, minOf(left, scratch.size.toLong()).toInt())
            if (read < 0) throw EOFException("Jellyfin ignored the seek range and the requested position is beyond the stream.")
            left -= read
        }
    }

    fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return -1
        val count = input?.read(buffer, offset, if (remaining >= 0) minOf(length.toLong(), remaining).toInt() else length)
            ?: throw IOException("Media stream is closed.")
        if (count < 0) {
            if (remaining > 0) throw EOFException("Jellyfin media stream ended early.")
            return -1
        }
        if (remaining >= 0) remaining -= count
        return count
    }

    override fun close() { input = null; response?.close(); response = null }
}
