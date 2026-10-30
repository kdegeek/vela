package app.vela.tv.home

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class HomeAssistantTest {
    private val server = MockWebServer()
    private val statesJson = """[
        {"entity_id":"light.lamp","state":"on","attributes":{"friendly_name":"Lamp","brightness":120}},
        {"entity_id":"switch.fan","state":"off","attributes":{}},
        {"entity_id":"light.desk","state":"off","attributes":{"friendly_name":"Desk"}}]"""

    private fun ha(vararg replies: Pair<Int, String>): HomeAssistant {
        replies.forEach { (code, body) -> server.enqueue(MockResponse(code, Headers.headersOf("content-type", "application/json"), body)) }
        server.start()
        return HomeAssistant(server.url("/").toString().trimEnd('/') + "/", "secret-token")
    }

    @After fun stop() = server.close()

    @Test
    fun `states lists everything with bearer auth`() = runBlocking {
        val all = ha(200 to statesJson).states()
        val r = server.takeRequest()
        assertEquals("GET /api/states", "${r.method} ${r.target}")
        assertEquals("Bearer secret-token", r.headers["Authorization"])
        assertEquals(listOf("light.lamp", "switch.fan", "light.desk"), all.map { it.entityId })
        assertEquals("Lamp", all[0].friendlyName)
        assertNull(all[1].friendlyName)
        assertEquals(JsonPrimitive(120), all[0].attributes["brightness"])
    }

    @Test
    fun `states filters by domain prefix`() = runBlocking {
        assertEquals(listOf("light.lamp", "light.desk"), ha(200 to statesJson).states("light").map { it.entityId })
    }

    @Test
    fun `state returns the entity and null on 404`() = runBlocking {
        val c = ha(200 to """{"entity_id":"light.lamp","state":"on","attributes":{"friendly_name":"Lamp"}}""", 404 to """{"message":"Entity not found."}""")
        assertEquals(EntityState("light.lamp", "on", "Lamp", buildJsonObject { put("friendly_name", "Lamp") }), c.state("light.lamp"))
        assertEquals("/api/states/light.lamp", server.takeRequest().target)
        assertNull(c.state("light.nope"))
    }

    @Test
    fun `call posts the data and returns the changed states`() = runBlocking {
        val c = ha(200 to """[{"entity_id":"light.lamp","state":"off","attributes":{}}]""")
        val changed = c.call("light", "turn_off", buildJsonObject { put("entity_id", "light.lamp") })
        val r = server.takeRequest()
        assertEquals("POST /api/services/light/turn_off", "${r.method} ${r.target}")
        assertEquals("""{"entity_id":"light.lamp"}""", r.body!!.utf8())
        assertEquals("off", changed.single().state)
    }

    @Test
    fun `401 is an auth error without the token in the message`() {
        val c = ha(401 to "nope")
        val e = assertThrows(HomeAssistantAuthError::class.java) { runBlocking { c.states() } }
        assertFalse(e.message!!.contains("secret-token"))
    }

    @Test
    fun `other failures carry status and a short body but not the token`() {
        val c = ha(500 to "x".repeat(5000))
        val e = assertThrows(IOException::class.java) { runBlocking { c.call("light", "toggle", JsonObject(emptyMap())) } }
        assertTrue(e.message!!.contains("500"))
        assertTrue(e.message!!.length < 400)
        assertFalse(e.message!!.contains("secret-token"))
    }
}
