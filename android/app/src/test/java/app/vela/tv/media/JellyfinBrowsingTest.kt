package app.vela.tv.media

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class JellyfinBrowsingTest {
    private val server = MockWebServer()
    private fun response(body: String) = MockResponse(200, okhttp3.Headers.headersOf("Content-Type", "application/json"), body)
    private fun start(dispatch: (RecordedRequest) -> String): JellyfinClient {
        server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = response(dispatch(request)) }
        server.start()
        return JellyfinClient(server.url("/jellyfin/").toString(), "key", "viewer", "tv")
    }
    @After fun close() = server.close()

    @Test fun `default episodes reaches beyond 100 even when the server caps pages`() = runBlocking {
        val client = start { request ->
            val start = request.url.queryParameter("startIndex")!!.toInt()
            val count = minOf(30, 237 - start)
            """{"TotalRecordCount":237,"Items":[${(start until start + count).joinToString { """{"Id":"e$it","Name":"Episode $it","Type":"Episode","SeriesId":"s","ParentIndexNumber":1,"IndexNumber":${it + 1}}""" }}]}"""
        }
        val episodes = client.episodes("s")
        assertEquals(237, episodes.size)
        assertEquals("e236", episodes.last().id)
        assertEquals(237, episodes.last().episodeNumber)
        assertEquals(8, server.requestCount)
        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/jellyfin/Shows/s/Episodes", request.url.encodedPath)
        assertEquals("false", request.url.queryParameter("isMissing"))
    }

    @Test fun `repeated pages fail instead of trapping the title sheet forever`() {
        val c = start { """{"TotalRecordCount":200,"Items":[{"Id":"same","Type":"Episode"}]}""" }
        assertThrows(IOException::class.java) { runBlocking { c.episodes("s") } }
        assertEquals(2, server.requestCount)
    }

    @Test fun `explicit episode limit remains a cap`() = runBlocking {
        val c = start { """{"TotalRecordCount":200,"Items":[{"Id":"e","Type":"Episode"}]}""" }
        assertEquals(1, c.episodes("s", 1).size)
        assertEquals(1, server.requestCount)
    }

    @Test fun `empty stale total terminates pagination`() = runBlocking {
        val c = start { """{"TotalRecordCount":200,"Items":[]}""" }
        assertNull(c.episodesPage("s", 10).nextStartIndex)
        assertTrue(c.episodes("s").isEmpty())
    }

    @Test fun `browse search and season pages retain independent cursors and filters`() = runBlocking {
        val c = start { """{"TotalRecordCount":81,"Items":[{"Id":"one"}]}""" }
        assertEquals(21, c.browse("library", 20, 25).nextStartIndex)
        assertEquals(41, c.searchPage("a & b", 40, 10).nextStartIndex)
        c.episodesPage("show", 10, 15, "season")
        c.nextUp("show", 2, 8)
        val browse = server.takeRequest().url
        assertEquals("library", browse.queryParameter("parentId"))
        assertEquals("SortName", browse.queryParameter("sortBy"))
        val search = server.takeRequest().url
        assertEquals("a & b", search.queryParameter("searchTerm"))
        assertEquals("40", search.queryParameter("startIndex"))
        assertEquals("season", server.takeRequest().url.queryParameter("seasonId"))
        assertEquals("/jellyfin/Shows/NextUp", server.takeRequest().url.encodedPath)
    }

    @Test fun `next episode is immediate server order rather than an arbitrary unwatched episode`() = runBlocking {
        val c = start { """{"Items":[{"Id":"current","Type":"Episode"},{"Id":"next","Type":"Episode","UserData":{"Played":true}}]}""" }
        assertEquals("next", c.nextEpisode(JellyfinItem("current", "Episode", "Episode", seriesId = "show"))?.id)
        assertEquals("current", server.takeRequest().url.queryParameter("startItemId"))
        assertNull(c.nextEpisode(JellyfinItem("movie", "Movie", "Movie")))
        assertEquals(1, server.requestCount)
    }

    @Test fun `invalid pagination is rejected without an HTTP request`() {
        val c = start { "{}" }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { c.browse(startIndex = -1) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { c.episodesPage("s", limit = 501) } }
        assertEquals(0, server.requestCount)
    }
}
