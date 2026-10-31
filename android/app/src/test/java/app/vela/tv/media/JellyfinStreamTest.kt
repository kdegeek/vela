package app.vela.tv.media

import java.io.EOFException
import java.io.IOException
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class JellyfinStreamTest {
    private val server = MockWebServer()
    private fun client(): JellyfinClient { server.start(); return JellyfinClient(server.url("/jellyfin").toString(), "secret", "viewer", "tv") }
    @After fun close() = server.close()
    private fun JellyfinStream.text(): String {
        val bytes = ByteArray(100)
        val count = read(bytes, 0, bytes.size)
        return if (count < 0) "" else String(bytes, 0, count)
    }

    @Test fun `seek reads correct bytes even when a server ignores Range`() {
        val c = client()
        server.enqueue(MockResponse(200, Headers.headersOf(), "abcdefghij"))
        JellyfinStream(c, "/Videos/movie/stream", 4, 3).use {
            assertEquals("efg", it.text())
            assertEquals("", it.text())
        }
        val req = server.takeRequest()
        assertEquals("bytes=4-6", req.headers["Range"])
        assertEquals("identity", req.headers["Accept-Encoding"])
        assertTrue(req.headers["Authorization"]!!.contains("secret"))
        assertFalse(req.target.contains("secret"))
    }

    @Test fun `partial response must start at the requested byte offset`() {
        val c = client()
        server.enqueue(MockResponse(206, Headers.headersOf("Content-Range", "bytes 4-6/10"), "efg"))
        JellyfinStream(c, "/Videos/movie/stream", 4, -1).use { assertEquals("efg", it.text()) }
        server.enqueue(MockResponse(206, Headers.headersOf("Content-Range", "bytes 0-2/10"), "abc"))
        assertThrows(IOException::class.java) { JellyfinStream(c, "/Videos/movie/stream", 4, -1) }
    }

    @Test fun `seek exactly to end is EOF but past the end is an error`() {
        val c = client()
        server.enqueue(MockResponse(416, Headers.headersOf("Content-Range", "bytes */10"), ""))
        JellyfinStream(c, "/Videos/movie/stream", 10, -1).use { assertEquals("", it.text()) }
        server.enqueue(MockResponse(416, Headers.headersOf("Content-Range", "bytes */10"), ""))
        assertThrows(IOException::class.java) { JellyfinStream(c, "/Videos/movie/stream", 11, -1) }
    }

    @Test fun `truncated explicit range fails instead of silently completing a title`() {
        val c = client()
        server.enqueue(MockResponse(200, Headers.headersOf(), "abc"))
        JellyfinStream(c, "/Videos/movie/stream", 0, 5).use {
            assertEquals("abc", it.text())
            assertThrows(EOFException::class.java) { it.text() }
        }
    }

    @Test fun `redirect and cross-origin subtitle URLs cannot receive a token`() {
        val c = client()
        server.enqueue(MockResponse(302, Headers.headersOf("Location", "https://untrusted.invalid/"), ""))
        assertThrows(IOException::class.java) { JellyfinStream(c, "/Videos/movie/stream", 0, -1) }
        for (address in listOf("https://untrusted.invalid/a", "//untrusted.invalid/a", server.url("/other-app/a").toString(), "../other-app/a")) {
            assertThrows(JellyfinPlaybackError::class.java) { JellyfinStream(c, address, 0, -1) }
        }
        assertEquals(1, server.requestCount)
    }
}
