package app.vela.tv.media

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Response
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class JellyfinTransportTest {
    private val server = MockWebServer()
    @After fun close() = server.close()

    @Test fun `delayed response body does not block the caller dispatcher and cancellation closes the call`() = runBlocking {
        val headers = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val http = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun responseHeadersEnd(call: Call, response: Response) { headers.countDown() }
            override fun callFailed(call: Call, ioe: IOException) { cancelled.countDown() }
        }).build()
        server.enqueue(MockResponse.Builder().body("""{"Id":"movie","Type":"Movie"}""").bodyDelay(2, TimeUnit.SECONDS).build())
        server.start()
        val client = JellyfinClient(server.url("/").toString(), "key", "viewer", "tv", http)
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { caller ->
            val load = async(caller) { client.item("movie") }
            try {
                assertTrue(headers.await(2, TimeUnit.SECONDS))
                // Models the Android Main dispatcher: another task must run while the body is still pending.
                assertEquals("responsive", withTimeout(500) { withContext(caller) { "responsive" } })
                load.cancelAndJoin()
                assertTrue(cancelled.await(2, TimeUnit.SECONDS))
            } finally { load.cancelAndJoin() }
        }
    }

    @Test fun `503 retry-after zero never transparently replays a session POST`() {
        server.enqueue(MockResponse(503, Headers.headersOf("Retry-After", "0"), ""))
        server.enqueue(MockResponse(204, Headers.headersOf(), ""))
        server.start()
        val client = JellyfinClient(server.url("/").toString(), "key", "viewer", "tv")
        assertThrows(IOException::class.java) { runBlocking { client.reportStart("movie", 0) } }
        assertEquals(1, server.requestCount)
    }
}
