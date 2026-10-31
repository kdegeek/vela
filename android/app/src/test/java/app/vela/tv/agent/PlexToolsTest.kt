package app.vela.tv.agent

import app.vela.tv.media.plex.PlexClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class PlexToolsTest {
    private fun response(body: String) = MockResponse(200, okhttp3.Headers.headersOf("Content-Type", "application/json"), body)

    @Test fun `agent discovers a library ID then searches without owner-supplied IDs or exposed credentials`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(response("""{"MediaContainer":{"machineIdentifier":"fixture-server","friendlyName":"Fixture"}}"""))
            server.enqueue(response("""{"MediaContainer":{"size":1,"offset":0,"totalSize":2,"Directory":[{"key":"1","title":"Movies","type":"movie"}]}}"""))
            server.enqueue(response("""{"MediaContainer":{"size":1,"offset":0,"totalSize":1,"Metadata":[{"ratingKey":"12","title":"Fixture film","type":"movie","librarySectionID":"1","duration":10000,"viewOffset":5000}]}}"""))
            val client = PlexClient(server.url("/").toString(), "fixture-secret", "test-device")
            val executor = ToolExecutor(PlexTools { client }.all())
            val libraries = executor.execute(ToolCall("libraries", "plex_libraries", buildJsonObject { put("limit", 1) }), { _, _ -> false })
            assertFalse(libraries.isError)
            val page = Json.parseToJsonElement(libraries.content).jsonObject
            val library = page.getValue("items").jsonArray.single().jsonObject.getValue("library_id").jsonPrimitive.content
            assertEquals("1", library)
            assertEquals(1, page.getValue("next_offset").jsonPrimitive.int)
            val found = executor.execute(ToolCall("search", "plex_search", buildJsonObject { put("library_id", library); put("query", "Fixture") }), { _, _ -> false })
            assertFalse(found.isError)
            val item = Json.parseToJsonElement(found.content).jsonObject.getValue("items").jsonArray.single().jsonObject
            assertEquals("12", item.getValue("item_id").jsonPrimitive.content)
            assertEquals(5000, item.getValue("resume_ms").jsonPrimitive.int)
            assertFalse(found.content.contains("fixture-secret"))
            assertEquals(3, server.requestCount)
            repeat(3) { assertEquals("fixture-secret", server.takeRequest().headers["X-Plex-Token"]) }
        }
    }

    @Test fun `invalid Plex page bounds fail before an HTTP read`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val executor = ToolExecutor(PlexTools { PlexClient(server.url("/").toString(), "fixture-secret", "test-device") }.all())
            for ((id, args) in listOf("""{"limit":0}""", """{"limit":21}""", """{"offset":-1}""", """{"offset":1000001}""", """{"limit":1.5}""").withIndex()) {
                assertTrue(executor.execute(ToolCall("invalid-$id", "plex_libraries", Json.parseToJsonElement(args).jsonObject), { _, _ -> false }).isError)
            }
            assertEquals(0, server.requestCount)
        }
    }
}
