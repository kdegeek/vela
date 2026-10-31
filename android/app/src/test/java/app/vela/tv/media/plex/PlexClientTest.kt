package app.vela.tv.media.plex

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
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

class PlexClientTest {
    private val server = MockWebServer()
    private val secret = "plex-test-private-token"
    @After fun close() = server.close()
    private fun response(body: String) = MockResponse.Builder().body(body).build()
    private fun root(name: String = "Media room") = response("""{"MediaContainer":{"machineIdentifier":"machine-123","friendlyName":"$name","version":"1.42.0"}}""")
    private fun row(id: Int = 1, extra: String = "") = """{"ratingKey":"$id","title":"Title $id","type":"movie"$extra}"""
    private fun rows(vararg items: String, offset: Int? = 0, total: Int? = null) = response("""{"MediaContainer":{"size":${items.size},${offset?.let { "\"offset\":$it," }.orEmpty()}${total?.let { "\"totalSize\":$it," }.orEmpty()}"Metadata":[${items.joinToString(",")}]}}""")
    private fun client(http: OkHttpClient = OkHttpClient()): PlexClient {
        server.start()
        return PlexClient(server.url("/proxy/").toString(), secret, "vela-tv", http)
    }
    private fun failure(code: PlexError, block: suspend () -> Unit) = runBlocking {
        val e = assertThrows(PlexException::class.java) { runBlocking { block() } }
        assertEquals(code, e.code)
        assertFalse(e.toString().contains(secret)); assertNull(e.cause)
    }

    @Test fun `identity library and paginated browse use header auth without query secrets`() = runBlocking {
        server.enqueue(root())
        server.enqueue(response("""{"MediaContainer":{"size":1,"Directory":[{"key":"1","title":"Movies","type":"movie"}]}}"""))
        server.enqueue(rows(row(1), row(2), total = 3))
        server.enqueue(rows(row(3), offset = 2, total = 3))
        val c = client()
        val connection = c.checkConnection()
        assertEquals("machine-123", connection.server.machineIdentifier)
        assertEquals("Movies", connection.libraries.items.single().title)
        val first = c.browse("1", PlexPageRequest(0, 2))
        assertEquals(2, first.nextOffset)
        val last = c.browse("1", PlexPageRequest(first.nextOffset!!, 2))
        assertEquals("3", last.items.single().id); assertNull(last.nextOffset)
        assertEquals("machine-123", last.items.single().serverId)
        assertFalse(c.toString().contains(secret)); assertFalse(connection.toString().contains(secret))
        repeat(4) { index ->
            val request = server.takeRequest()
            assertEquals(secret, request.headers["X-Plex-Token"])
            assertEquals("vela-tv", request.headers["X-Plex-Client-Identifier"])
            assertEquals("application/json", request.headers["Accept"])
            assertFalse(request.url.toString().contains(secret))
            assertEquals(listOf("/proxy/", "/proxy/library/sections/all", "/proxy/library/sections/1/all", "/proxy/library/sections/1/all")[index], request.url.encodedPath)
        }
    }
    @Test fun `search filter and recently added sort are safely encoded`() = runBlocking {
        server.enqueue(root()); server.enqueue(rows()); server.enqueue(rows())
        val c = client()
        c.search("2", "A&B? title"); c.recent("2")
        server.takeRequest()
        val search = server.takeRequest().url
        assertEquals("A&B? title", search.queryParameter("title")); assertEquals(1, search.querySize)
        assertEquals("addedAt:desc", server.takeRequest().url.queryParameter("sort"))
    }
    @Test fun `resume and on deck page only discovered documented hub keys`() = runBlocking {
        server.enqueue(root())
        server.enqueue(response("""{"MediaContainer":{"Hub":[{"hubIdentifier":"home.continue","key":"/hubs/home/continueWatching"}]}}"""))
        server.enqueue(rows(row(10, ",\"duration\":10000,\"viewOffset\":3000,\"librarySectionID\":2,\"contentRating\":\"PG\""), total = 2))
        server.enqueue(response("""{"MediaContainer":{"Hub":[{"hubIdentifier":"home.onDeck","key":"/hubs/home/onDeck"}]}}"""))
        server.enqueue(rows(row(20), offset = 1, total = 2))
        val c = client()
        val resume = c.resume(PlexPageRequest(0, 1))
        assertEquals(1, resume.nextOffset); assertEquals(0.3f, resume.items.single().progress!!, 0.001f)
        assertEquals(3000, resume.items.single().resumePositionMs)
        assertEquals("2", resume.items.single().libraryId)
        assertEquals("20", c.onDeck(PlexPageRequest(1, 1)).items.single().id)
        val requests = List(5) { server.takeRequest() }
        assertEquals("/proxy/hubs/home/continueWatching", requests[2].url.encodedPath)
        assertEquals("1", requests[4].headers["X-Plex-Container-Start"])
    }
    @Test fun `missing hubs are empty and absolute or credential hub routes are rejected`() = runBlocking {
        server.enqueue(root()); server.enqueue(response("""{"MediaContainer":{"Hub":[]}}"""))
        val c = client(); assertTrue(c.onDeck().items.isEmpty())
        for (route in listOf("https://evil/hubs/home/onDeck", "/hubs/home/onDeck?X-Plex-Token=$secret", "/hubs/../onDeck", "/hubs/home/onDeck?type=1&x=1")) {
            server.enqueue(response("""{"MediaContainer":{"Hub":[{"hubIdentifier":"home.ondeck","key":"$route"}]}}"""))
            failure(PlexError.UNSUPPORTED_HUB) { c.onDeck() }
        }
        assertEquals(6, server.requestCount)
    }
    @Test fun `auth and malformed errors never echo response body token url or parser cause`() {
        server.enqueue(MockResponse(401, Headers.headersOf(), "echo $secret"))
        val c = client(); failure(PlexError.AUTH) { c.identity() }
        server.enqueue(response("invalid json $secret")); failure(PlexError.INVALID_RESPONSE) { c.identity() }
        assertEquals(2, server.requestCount)
    }
    @Test fun `redirect even within origin is refused and never forwards token`() {
        MockWebServer().use { other ->
            other.start()
            server.enqueue(MockResponse(302, Headers.headersOf("Location", other.url("/steal?secret=$secret").toString()), secret))
            val c = client(); failure(PlexError.REDIRECT) { c.identity() }
            assertEquals(1, server.requestCount); assertEquals(0, other.requestCount)
        }
    }
    @Test fun `body caps apply to content length chunked json and images`() = runBlocking {
        server.enqueue(MockResponse.Builder().chunkedBody("x".repeat(1024 * 1024 + 1), 4096).build())
        val c = client(); failure(PlexError.BODY_LIMIT) { c.identity() }
        server.enqueue(MockResponse.Builder().body("x".repeat(1024 * 1024 + 1)).build())
        failure(PlexError.BODY_LIMIT) { c.identity() }
        server.enqueue(root()); server.enqueue(rows(row(1, ",\"thumb\":\"/library/metadata/1/thumb/123\""), offset = null))
        val item = c.item("1")
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "image/jpeg").chunkedBody("x".repeat(512 * 1024 + 1), 4096).build())
        failure(PlexError.BODY_LIMIT) { c.artwork(item) }
    }
    @Test fun `metadata redaction rejects unsafe artwork and read uses private auth`() = runBlocking {
        server.enqueue(root("echo $secret"))
        server.enqueue(rows(row(1, ",\"summary\":\"echo $secret\",\"thumb\":\"https://evil/steal?X-Plex-Token=$secret\",\"art\":\"/library/metadata/1/art/123\"")))
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "image/png").body("png-test-bytes").build())
        val c = client(); val item = c.browse("1").items.single()
        assertFalse(c.identity().toString().contains(secret)); assertFalse(item.toString().contains(secret)); assertNull(item.thumb)
        assertNull(c.artwork(item)); assertEquals("png-test-bytes", c.artwork(item, backdrop = true)!!.toString(Charsets.UTF_8))
        server.takeRequest(); server.takeRequest(); val art = server.takeRequest()
        assertEquals("/proxy/library/metadata/1/art/123", art.url.encodedPath)
        assertEquals(secret, art.headers["X-Plex-Token"]); assertNull(art.url.query)
    }
    @Test fun `invalid ids queries tokens and client ids cannot reach network`() {
        val c = client()
        for (id in listOf("../1", "https://evil", "1?X-Plex-Token=$secret", "", "1/2")) assertThrows(IllegalArgumentException::class.java) { runBlocking { c.browse(id) } }
        for (q in listOf(" ", secret, "x\ny", "x".repeat(501))) assertThrows(IllegalArgumentException::class.java) { runBlocking { c.search("1", q) } }
        assertThrows(IllegalArgumentException::class.java) { PlexClient(server.url("/").toString(), "a\nb", "tv") }
        assertThrows(IllegalArgumentException::class.java) { PlexClient(server.url("/").toString(), secret, "bad\nid") }
        assertEquals(0, server.requestCount)
    }
    @Test fun `unpaginated response never invents a continuation or repeats first page`() = runBlocking {
        server.enqueue(root()); server.enqueue(rows(row(1), row(2), offset = null)); server.enqueue(rows(row(1), offset = null))
        val c = client(); val page = c.browse("1", PlexPageRequest(0, 1))
        assertEquals(1, page.items.size); assertNull(page.nextOffset); assertTrue(page.truncated)
        failure(PlexError.INVALID_RESPONSE) { c.browse("1", PlexPageRequest(1, 1)) }
    }
    @Test fun `short paginated page advances by actual count and repeated offsets fail`() = runBlocking {
        server.enqueue(root()); server.enqueue(rows(row(1), offset = 0, total = 3)); server.enqueue(rows(row(1), offset = 0, total = 3))
        val c = client(); assertEquals(1, c.browse("1", PlexPageRequest(0, 20)).nextOffset)
        failure(PlexError.INVALID_RESPONSE) { c.browse("1", PlexPageRequest(1, 20)) }
    }
    @Test fun `inconsistent pagination unknown metadata and changed identity fail safely`() = runBlocking {
        server.enqueue(root()); val c = client(); c.identity()
        for (body in listOf(
            """{"size":1,"offset":0,"totalSize":0,"Metadata":[${row()}]}""",
            """{"size":1,"offset":0,"Metadata":[]}""",
            """{"size":0,"offset":-1}""",
            """{"size":0,"totalSize":1.5}""",
            """{"size":1,"Metadata":[{"ratingKey":"/bad"}]}"""
        )) {
            server.enqueue(response("""{"MediaContainer":$body}""")); failure(PlexError.INVALID_RESPONSE) { c.browse("1") }
        }
        server.enqueue(response("""{"MediaContainer":{"machineIdentifier":"changed","Metadata":[]}}"""))
        failure(PlexError.IDENTITY_CHANGED) { c.browse("1") }
    }
    @Test fun `complete position resets resume and zero duration has no progress`() = runBlocking {
        server.enqueue(root()); server.enqueue(rows(row(1, ",\"duration\":100,\"viewOffset\":200"), row(2, ",\"duration\":0,\"viewOffset\":20")))
        val items = client().browse("1").items
        assertEquals(0L, items[0].resumePositionMs); assertEquals(1f, items[0].progress!!, 0f); assertNull(items[1].progress)
    }
    @Test fun `slow body stays off caller dispatcher and coroutine cancellation closes HTTP call`() = runBlocking {
        val headers = CountDownLatch(1); val canceled = CountDownLatch(1)
        val http = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun responseHeadersEnd(call: Call, response: Response) { headers.countDown() }
            override fun callFailed(call: Call, ioe: IOException) { canceled.countDown() }
        }).build()
        server.enqueue(MockResponse.Builder().body("""{"MediaContainer":{"machineIdentifier":"machine-123"}}""").bodyDelay(5, TimeUnit.SECONDS).build())
        val c = client(http)
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { caller ->
            val load = async(caller) { c.identity() }
            try {
                assertTrue(headers.await(2, TimeUnit.SECONDS))
                assertEquals("responsive", withTimeout(500) { withContext(caller) { "responsive" } })
                load.cancelAndJoin(); assertTrue(canceled.await(2, TimeUnit.SECONDS))
            } finally { load.cancelAndJoin() }
        }
    }
    @Test fun `503 retry-after zero is not transparently replayed`() {
        server.enqueue(MockResponse(503, Headers.headersOf("Retry-After", "0"), secret)); server.enqueue(root())
        val c = client(); failure(PlexError.HTTP) { c.identity() }; assertEquals(1, server.requestCount)
    }

    @Test fun `permission is rechecked before each HTTP read including after identity`() = runBlocking {
        server.enqueue(root()); server.start()
        var checks = 0
        val c = PlexClient(server.url("/").toString(), secret, "tv", beforeRead = {
            checks++; check(checks == 1) { "Profile changed." }
        })
        assertThrows(IllegalStateException::class.java) { runBlocking { c.browse("1") } }
        assertEquals(2, checks); assertEquals(1, server.requestCount)
    }
    @Test fun `excessive JSON depth and duplicate item keys fail without parser or tile crashes`() = runBlocking {
        server.enqueue(root()); val c = client(); c.identity()
        server.enqueue(response("[".repeat(40) + "0" + "]".repeat(40)))
        failure(PlexError.INVALID_RESPONSE) { c.browse("1") }
        server.enqueue(rows(row(1), row(1)))
        failure(PlexError.INVALID_RESPONSE) { c.browse("1") }
    }
    @Test fun `pagination headers must agree with body and can supply otherwise absent offset`() = runBlocking {
        server.enqueue(root())
        server.enqueue(MockResponse.Builder().addHeader("X-Plex-Container-Start", "2").addHeader("X-Plex-Container-Total-Size", "3")
            .body("""{"MediaContainer":{"size":1,"Metadata":[${row(3)}]}}""").build())
        val c = client(); val page = c.browse("1", PlexPageRequest(2, 1))
        assertEquals("3", page.items.single().id); assertNull(page.nextOffset); assertEquals(3, page.total)
        server.enqueue(MockResponse.Builder().addHeader("X-Plex-Container-Start", "1")
            .body("""{"MediaContainer":{"size":0,"offset":0}}""").build())
        failure(PlexError.INVALID_RESPONSE) { c.browse("1", PlexPageRequest(1, 1)) }
    }
    @Test fun `credentials cannot be embedded in address paths and unsafe HTTP interceptors are refused`() {
        server.start()
        assertThrows(IllegalArgumentException::class.java) { PlexClient(server.url("/$secret/").toString(), secret, "tv") }
        assertThrows(IllegalArgumentException::class.java) { PlexClient("http://plex\n.evil", secret, "tv") }
        val loggingClient = OkHttpClient.Builder().addInterceptor { chain -> chain.proceed(chain.request()) }.build()
        assertThrows(IllegalArgumentException::class.java) { PlexClient(server.url("/").toString(), secret, "tv", loggingClient) }
        assertEquals(0, server.requestCount)
    }

    @Test fun `compressed JSON response is capped after decompression`() {
        val bytes = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(bytes).use { it.write("x".repeat(1024 * 1024 + 1).toByteArray()) }
        server.enqueue(MockResponse.Builder().addHeader("Content-Encoding", "gzip").body(okio.Buffer().write(bytes.toByteArray())).build())
        val c = client(); failure(PlexError.BODY_LIMIT) { c.identity() }
    }
}
