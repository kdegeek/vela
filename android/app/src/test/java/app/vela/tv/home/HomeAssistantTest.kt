package app.vela.tv.home

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okio.ForwardingSource
import okio.Buffer
import okio.buffer
import java.util.concurrent.Executors
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

    @Test fun `a transient 503 cannot replay a home toggle`() = runBlocking {
        server.start()
        server.enqueue(MockResponse.Builder().code(503).addHeader("Retry-After", "0").body("busy").build())
        server.enqueue(MockResponse.Builder().body("[]").build())
        val c = HomeAssistant(server.url("/").toString(), "secret-token")
        val result = runCatching { c.call("light", "toggle", buildJsonObject { put("entity_id", "light.lamp") }) }
        assertTrue(result.exceptionOrNull() is IOException)
        assertEquals(1, server.requestCount)
    }
    @Test fun `home error redaction precedes truncation`() {
        val c = ha(500 to ("x".repeat(195) + "secret-token trailing"))
        val error = assertThrows(IOException::class.java) { runBlocking { c.call("light", "toggle", JsonObject(emptyMap())) } }
        assertFalse(error.message!!, error.message!!.contains("secre"))
    }

    @Test fun `response bodies are consumed away from the caller UI dispatcher`() = runBlocking {
        server.start()
        server.enqueue(MockResponse.Builder().body(statesJson).build())
        val observed = CompletableDeferred<String>()
        val http = OkHttpClient.Builder().addNetworkInterceptor { chain ->
            val response = chain.proceed(chain.request())
            val body = response.body
            val source = object : ForwardingSource(body.source()) {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    observed.complete(Thread.currentThread().name)
                    return super.read(sink, byteCount)
                }
            }.buffer()
            response.newBuilder().body(object : ResponseBody() {
                override fun contentType() = body.contentType()
                override fun contentLength() = body.contentLength()
                override fun source() = source
            }).build()
        }.build()
        Executors.newSingleThreadExecutor { r -> Thread(r, "vela-test-ui") }.asCoroutineDispatcher().use { ui ->
            val result = withContext(ui) { HomeAssistant(server.url("/").toString(), "secret-token", http).states() }
            assertEquals(3, result.size)
            assertFalse(observed.await().contains("vela-test-ui"))
        }
    }
}
