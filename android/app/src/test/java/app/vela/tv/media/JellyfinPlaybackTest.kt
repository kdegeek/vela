package app.vela.tv.media

import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class JellyfinPlaybackTest {
    private val server = MockWebServer()
    private val capabilities = PlaybackCapabilities(setOf("h264"), setOf("aac"))
    private val movie = JellyfinItem("movie", "Movie", "Movie", runTimeTicks = 600_000_000, positionTicks = 100_000_000)
    private fun response(body: String, code: Int = 200) = MockResponse(code, okhttp3.Headers.headersOf("Content-Type", "application/json"), body)
    private fun client(): JellyfinClient { server.start(); return JellyfinClient(server.url("/jellyfin/").toString(), "secret", "viewer", "tv") }
    private fun compatible() = """{"PlaySessionId":"play-id","MediaSources":[{"Id":"version","Container":"mkv","SupportsDirectPlay":true}]}"""
    @After fun close() = server.close()

    @Test fun `negotiation carries resume and user and binds reports to the selected source session`() = runBlocking {
        val c = client()
        server.enqueue(response(compatible()))
        val playback = c.playback(movie, capabilities)
        assertEquals(10_000L, playback.startPositionMs)
        val negotiation = server.takeRequest()
        assertEquals("POST /jellyfin/Items/movie/PlaybackInfo", "${negotiation.method} ${negotiation.url.encodedPath}")
        val body = Json.parseToJsonElement(negotiation.body!!.utf8()).jsonObject
        assertEquals("viewer", body.getValue("UserId").jsonPrimitive.content)
        assertEquals(100_000_000L, body.getValue("StartTimeTicks").jsonPrimitive.long)
        assertFalse(body.getValue("EnableTranscoding").jsonPrimitive.boolean)
        assertFalse(body.getValue("EnableDirectStream").jsonPrimitive.boolean)
        assertFalse(playback.url.contains("secret"))
        assertTrue(playback.url.contains("mediaSourceId=version"))
        repeat(3) { server.enqueue(response("", 204)) }
        c.reportStart(playback, 10, true, false)
        c.reportProgress(playback, 20, true, true, subtitleIndex = -1)
        for (i in 0..1) {
            val report = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
            assertEquals("play-id", report.getValue("PlaySessionId").jsonPrimitive.content)
            assertEquals("version", report.getValue("MediaSourceId").jsonPrimitive.content)
            assertTrue(report.getValue("IsPaused").jsonPrimitive.boolean)
            assertFalse(report.containsKey("AudioStreamIndex"))
        }
        val checkpoint = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        assertEquals(20L, checkpoint.getValue("PlaybackPositionTicks").jsonPrimitive.long)
        assertFalse(checkpoint.containsKey("Played"))
        assertNotNull(java.time.Instant.parse(checkpoint.getValue("LastPlayedDate").jsonPrimitive.content))
    }

    @Test fun `containers and missing items never request a video stream`() {
        val c = client()
        for (type in listOf("Series", "Season", "Folder", null)) assertThrows(JellyfinPlaybackError::class.java) {
            runBlocking { c.playback(JellyfinItem("container", "Container", type), capabilities) }
        }
        assertThrows(JellyfinPlaybackError::class.java) { runBlocking { c.playback(movie.copy(missing = true), capabilities) } }
        assertEquals(0, server.requestCount)
    }

    @Test fun `transcoding-only and live sources fail explicitly instead of guessing a static URL`() {
        val c = client()
        for (body in listOf("""{"ErrorCode":"NoCompatibleStream"}""", """{"MediaSources":[{"Id":"x","SupportsTranscoding":true}]}""",
            """{"MediaSources":[{"Id":"x","SupportsDirectPlay":true,"RequiresOpening":true}]}""")) {
            server.enqueue(response(body))
            assertThrows(JellyfinPlaybackError::class.java) { runBlocking { c.playback(movie, capabilities) } }
        }
    }

    @Test fun `external and extracted subtitles use the configured base path and strip old credentials`() = runBlocking {
        val c = client()
        server.enqueue(response("""{"MediaSources":[{"Id":"v","SupportsDirectPlay":true,"DefaultSubtitleStreamIndex":2,"MediaStreams":[
            {"Index":2,"Type":"Subtitle","Codec":"srt","DeliveryMethod":"External","Language":"eng","DeliveryUrl":"/Videos/movie/v/Subtitles/2/Stream.srt?api_key=old"},
            {"Index":3,"Type":"Subtitle","Codec":"pgssub","IsExternal":true,"DeliveryUrl":"/unsupported"},
            {"Index":4,"Type":"Subtitle","Codec":"vtt","IsExternal":true,"DeliveryUrl":"https://untrusted.invalid/sub.vtt"}
        ]}]}"""))
        val sub = c.playback(movie, capabilities).subtitles.single()
        assertEquals(2, sub.index)
        assertTrue(sub.url.contains("/jellyfin/Videos/movie/"))
        assertFalse(sub.url.contains("api_key"))
        assertEquals("application/x-subrip", sub.mimeType)
    }

    @Test fun `natural completion alone clears resume and sets played while failed completion preserves it`() = runBlocking {
        val c = client()
        server.enqueue(response(compatible()))
        val playback = c.playback(movie, capabilities)
        server.takeRequest()
        repeat(4) { server.enqueue(response("", 204)) }
        c.reportStopped(playback, 550_000_000, completed = true, failed = false)
        server.takeRequest()
        val finished = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        assertEquals(0L, finished.getValue("PlaybackPositionTicks").jsonPrimitive.long)
        assertTrue(finished.getValue("Played").jsonPrimitive.boolean)
        c.reportStopped(playback, 500_000_000, completed = true, failed = true)
        assertTrue(Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject.getValue("Failed").jsonPrimitive.boolean)
        val failed = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        assertEquals(500_000_000L, failed.getValue("PlaybackPositionTicks").jsonPrimitive.long)
        assertFalse(failed.containsKey("Played"))
    }

    @Test fun `a failed session report still attempts the configured user resume write`() = runBlocking {
        val c = client()
        server.enqueue(response("", 503)); server.enqueue(response("", 204))
        assertThrows(IOException::class.java) { runBlocking { c.reportStopped("movie", 42) } }
        assertEquals(2, server.requestCount)
        server.takeRequest()
        assertEquals("/jellyfin/UserItems/movie/UserData", server.takeRequest().url.encodedPath)
    }

    @Test fun `invalid resume timestamps restart while rewatches and unknown runtime retain resume`() {
        assertEquals(0L, movie.copy(positionTicks = Long.MAX_VALUE).resumePositionMs)
        assertEquals(0L, movie.copy(positionTicks = -1).resumePositionMs)
        assertEquals(10_000L, movie.copy(played = true).resumePositionMs)
        assertEquals(10_000L, movie.copy(runTimeTicks = null).resumePositionMs)
        assertTrue(millisecondsToTicks(Long.MAX_VALUE) > 0)
        assertEquals(0L, millisecondsToTicks(-1))
    }

    @Test fun `API redirects are refused before credentials can leave the configured server`() {
        val c = client()
        server.enqueue(MockResponse(302, okhttp3.Headers.headersOf("Location", "https://untrusted.invalid/"), ""))
        assertThrows(IOException::class.java) { runBlocking { c.item("movie") } }
        assertEquals(1, server.requestCount)
    }

    @Test fun `oversized API bodies fail with a bounded readable error`() {
        val c = client()
        server.enqueue(response("x".repeat(8 * 1024 * 1024 + 1)))
        val error = assertThrows(IOException::class.java) { runBlocking { c.item("movie") } }
        assertTrue(error.message!!.contains("too large"))
    }

    @Test fun `auth failure does not trigger another resume write`() {
        val c = client()
        server.enqueue(response("", 401))
        assertThrows(JellyfinAuthError::class.java) { runBlocking { c.reportStopped("movie", 42) } }
        assertEquals(1, server.requestCount)
    }

    @Test fun `failed final user data is retried without resending stopped`() = runBlocking {
        val c = client()
        server.enqueue(response("", 204)); server.enqueue(response("", 503)); server.enqueue(response("", 204))
        assertThrows(IOException::class.java) { runBlocking { c.reportStopped("movie", 123) } }
        server.takeRequest()
        val original = server.takeRequest()
        ResumeCheckpoints.retryPending()
        val retried = server.takeRequest()
        assertEquals(original.target, retried.target)
        assertEquals(original.body!!.utf8(), retried.body!!.utf8())
        assertEquals("/jellyfin/UserItems/movie/UserData", retried.url.encodedPath)
        assertEquals(3, server.requestCount)
    }
}
