package app.vela.tv.media

import app.vela.tv.media.JellyfinClient.ImageType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class JellyfinClientTest {
    private val server = MockWebServer()
    private val routes = mutableMapOf<String, MockResponse>()

    private fun reply(body: String, code: Int = 200) = MockResponse(code, okhttp3.Headers.headersOf("content-type", "application/json"), body)

    /** Routes by "METHOD /path" (query ignored); anything else answers 204. */
    private fun start(): String {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                routes["${request.method} ${request.url.encodedPath}"] ?: MockResponse(204, okhttp3.Headers.headersOf(), "")
        }
        server.start()
        return server.url("/").toString()
    }

    private fun client(token: String? = "key", userId: String? = "u1") = JellyfinClient(start(), token, userId, "dev1")
    private fun take() = server.takeRequest()
    private fun JsonObject.long(k: String) = getValue(k).jsonPrimitive.long

    @After fun stop() = server.close()

    @Test
    fun `authenticate sends the MediaBrowser header and returns token and user`() = runBlocking {
        routes["POST /Users/AuthenticateByName"] = reply("""{"AccessToken":"tok","User":{"Id":"u1"}}""")
        val c = client(token = null, userId = null)
        assertEquals("tok" to "u1", c.authenticate("vela", "pw"))
        val r = take()
        assertEquals("""MediaBrowser Client="Vela", Device="Vela", DeviceId="dev1", Version="0.1.0"""", r.headers["Authorization"])
        assertEquals(Json.parseToJsonElement("""{"Username":"vela","Pw":"pw"}"""), Json.parseToJsonElement(r.body!!.utf8()))
        assertTrue(c.authHeader().endsWith("""Token="tok""""))
    }

    @Test
    fun `auth header carries the token`() {
        val c = JellyfinClient("http://jf", "t", "u", "d")
        assertEquals("""MediaBrowser Client="Vela", Device="Vela", DeviceId="d", Version="0.1.0", Token="t"""", c.authHeader())
    }

    private fun assertAuthError(code: Int) {
        routes["GET /UserItems/Resume"] = reply("", code)
        val c = client()
        assertThrows(JellyfinAuthError::class.java) { runBlocking { c.resume() } }
    }

    @Test fun `401 is an auth error`() = assertAuthError(401)

    @Test fun `403 is an auth error`() = assertAuthError(403)

    @Test
    fun `other failures are plain IOExceptions`() {
        routes["GET /UserItems/Resume"] = reply("", 500)
        val c = client()
        val e = assertThrows(java.io.IOException::class.java) { runBlocking { c.resume() } }
        assertFalse(e is JellyfinAuthError)
    }

    @Test
    fun `resume maps items, sends the query and remembers image tags`() = runBlocking {
        routes["GET /UserItems/Resume"] = reply(
            """{"Items":[{"Id":"i1","Name":"Sintel","Type":"Movie","ProductionYear":2010,"RunTimeTicks":6000000000,
            "UserData":{"PlaybackPositionTicks":3000000000},"Genres":["Fantasy"],"BackdropImageTags":["b"],"ImageTags":{"Primary":"p"},"Container":"mkv"},
            {"Id":"e1","Name":"Pilot","Type":"Episode","SeriesName":"Show"}]}""",
        )
        val c = client()
        val items = c.resume(5)
        val q = take().url
        assertEquals("/UserItems/Resume", q.encodedPath)
        assertEquals(mapOf("userId" to "u1", "limit" to "5", "mediaTypes" to "Video", "fields" to "Overview,Genres", "enableImageTypes" to "Backdrop,Primary"),
            q.queryParameterNames.associateWith { q.queryParameter(it) })
        assertEquals(JellyfinItem("i1", "Sintel", "Movie", 2010, 6_000_000_000, 3_000_000_000, listOf("Fantasy"), null, hasBackdrop = true, hasPrimary = true), items[0])
        assertEquals("Show", items[1].seriesName)
        assertEquals("Episode", items[1].type)
        assertFalse(items[1].hasBackdrop)
        assertTrue(c.imageUrl("i1", ImageType.Backdrop, 3840).let { it.contains("/Items/i1/Images/Backdrop/0?") && it.contains("maxWidth=3840") && it.contains("tag=b") })
        assertTrue(c.imageUrl("i1", ImageType.Primary, 704).contains("/Items/i1/Images/Primary?maxWidth=704&quality=90&tag=p"))
    }

    @Test
    fun `resume needs a signed-in user`() {
        val c = client(userId = null)
        assertThrows(JellyfinAuthError::class.java) { runBlocking { c.resume() } }
    }

    @Test
    fun `progress math matches the daemon card`() {
        val item = JellyfinItem("i1", "X", runTimeTicks = 6_000_000_000, positionTicks = 3_000_000_000)
        assertEquals(0.5, item.progress!!, 1e-9)
        assertEquals(5, item.remainingMinutes)
        assertEquals(10, item.runtimeMinutes)
        val fresh = JellyfinItem("i2", "Y", runTimeTicks = 6_000_000_000)
        assertNull(fresh.progress)
        assertNull(fresh.remainingMinutes)
        assertEquals(1.0, JellyfinItem("i3", "Z", runTimeTicks = 10, positionTicks = 20).progress!!, 1e-9)
        assertNull(JellyfinItem("i4", "W").runtimeMinutes)
    }

    @Test
    fun `streamUrl carries the api key`() {
        assertEquals("http://jf/Videos/i1/stream?static=true&api_key=key", JellyfinClient("http://jf/", "key", "u", "d").streamUrl("i1"))
    }

    @Test
    fun `reportStart and reportProgress post the playback state`() = runBlocking {
        val c = client()
        c.reportStart("i1", 10)
        c.reportProgress("i1", 20, paused = true)
        val a = take()
        val b = take()
        assertEquals("POST /Sessions/Playing", "${a.method} ${a.url.encodedPath}")
        assertEquals("DirectPlay", Json.parseToJsonElement(a.body!!.utf8()).jsonObject.getValue("PlayMethod").jsonPrimitive.content)
        assertEquals("POST /Sessions/Playing/Progress", "${b.method} ${b.url.encodedPath}")
        assertTrue(Json.parseToJsonElement(b.body!!.utf8()).jsonObject.getValue("IsPaused").jsonPrimitive.boolean)
    }

    // An API key has no user, so Jellyfin drops the session reports' user data: the resume point is written directly.
    private suspend fun stopAt(position: Long, run: Long?): List<RecordedRequest> {
        routes["GET /Items/i1"] = reply(if (run == null) "{}" else """{"RunTimeTicks":$run}""")
        client().reportStopped("i1", position)
        return List(3) { take() }
    }

    @Test
    fun `reportStopped reports then writes the resume position`() = runBlocking {
        val calls = stopAt(1_800_000_000, 6_000_000_000)
        assertEquals(
            listOf("POST /Sessions/Playing/Stopped", "GET /Items/i1?userId=u1", "POST /UserItems/i1/UserData?userId=u1"),
            calls.map { "${it.method} ${it.target}" },
        )
        assertEquals(1_800_000_000L, Json.parseToJsonElement(calls[2].body!!.utf8()).jsonObject.long("PlaybackPositionTicks"))
    }

    @Test
    fun `past 90 percent marks it played`() = runBlocking {
        val body = Json.parseToJsonElement(stopAt(5_700_000_000, 6_000_000_000)[2].body!!.utf8()).jsonObject
        assertEquals(setOf("Played", "PlaybackPositionTicks"), body.keys)
        assertTrue(body.getValue("Played").jsonPrimitive.boolean)
        assertEquals(0L, body.long("PlaybackPositionTicks"))
    }

    @Test
    fun `under 5 percent clears the position`() = runBlocking {
        val body = Json.parseToJsonElement(stopAt(60_000_000, 6_000_000_000)[2].body!!.utf8()).jsonObject
        assertEquals(setOf("PlaybackPositionTicks"), body.keys)
        assertEquals(0L, body.long("PlaybackPositionTicks"))
    }

    @Test
    fun `unknown runtime still writes the position`() = runBlocking {
        val body = Json.parseToJsonElement(stopAt(42, null)[2].body!!.utf8()).jsonObject
        assertEquals(42L, body.long("PlaybackPositionTicks"))
    }

    @Test
    fun `latest resolves the library name case-insensitively`() = runBlocking {
        routes["GET /UserViews"] = reply("""{"Items":[{"Id":"l1","Name":"Movies"},{"Id":"l2","Name":"Anime"}]}""")
        routes["GET /Items/Latest"] = reply("""[{"Id":"i1","Name":"Akira","Type":"Movie"}]""")
        val items = client().latest(8, "anime")
        assertEquals(listOf("i1"), items.map { it.id })
        assertEquals("/UserViews", take().url.encodedPath)
        val q = take().url
        assertEquals("/Items/Latest", q.encodedPath)
        assertEquals("l2", q.queryParameter("parentId"))
        assertEquals("8", q.queryParameter("limit"))
        assertEquals("u1", q.queryParameter("userId"))
        assertEquals("Overview,Genres", q.queryParameter("fields"))
        assertEquals("Backdrop,Primary", q.queryParameter("enableImageTypes"))
    }

    @Test
    fun `latest without a library skips the lookup`() = runBlocking {
        routes["GET /Items/Latest"] = reply("[]")
        assertEquals(emptyList<JellyfinItem>(), client().latest(3, null))
        assertNull(take().url.queryParameter("parentId"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `latest with an unknown library lists the available ones`() {
        routes["GET /UserViews"] = reply("""{"Items":[{"Id":"l1","Name":"Movies"},{"Id":"l2","Name":"Anime"}]}""")
        val c = client()
        val e = assertThrows(IllegalArgumentException::class.java) { runBlocking { c.latest(5, "Music") } }
        assertTrue(e.message!!, e.message!!.contains("Movies") && e.message!!.contains("Anime"))
    }

    @Test
    fun `search sends the term and maps series names`() = runBlocking {
        routes["GET /Items"] = reply("""{"Items":[{"Id":"e1","Name":"Pilot","Type":"Episode","SeriesName":"Lost"}]}""")
        val items = client().search("lost & found", 7)
        val q = take().url
        assertEquals("lost & found", q.queryParameter("searchTerm"))
        assertEquals("true", q.queryParameter("recursive"))
        assertEquals("Movie,Series,Episode", q.queryParameter("includeItemTypes"))
        assertEquals("7", q.queryParameter("limit"))
        assertEquals("u1", q.queryParameter("userId"))
        assertEquals("Lost", items.single().seriesName)
    }
}
