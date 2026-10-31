package app.vela.tv.web

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import org.junit.Assert.*
import org.junit.Test

class PhoneApiTest {
    private class Backend : PhoneBackend {
        override val pairingToken = "test-pairing-token"
        var effects = 0
        var task = ""
        override fun html() = "<!doctype html><title>Vela</title>"
        override suspend fun state() = "{}"
        override suspend fun settings(text: String) { effects++ }
        override suspend fun ask(text: String): String { effects++; task = text; return "task-1" }
        override suspend fun answer(answer: PhoneProtocol.Answer) = answer.promptId == "current"
        override suspend fun remote(key: String) { effects++ }
        override suspend fun connections() = "{}"
        override suspend fun stop() { effects++ }
        override suspend fun clear() { effects++ }
        override suspend fun layout() = "{}"
        override suspend fun layout(text: String) { effects++ }
    }
    @Test fun `every API route rejects an unpaired client before any effects`() = testApplication {
        val backend = Backend()
        application { phoneApi(backend) }
        val routes = listOf(HttpMethod.Get to "/api/state", HttpMethod.Get to "/api/layout") +
            listOf("settings","ask","answer","remote","connections/test","stop","history/clear").map { HttpMethod.Post to "/api/$it" } +
            (HttpMethod.Put to "/api/layout")
        for ((verb,path) in routes) {
            val response = client.request(path) { method = verb; setBody("{}") }
            assertEquals(path, HttpStatusCode.Unauthorized, response.status)
            assertEquals("no-store", response.headers["Cache-Control"])
        }
        assertEquals(0, backend.effects)
    }
    @Test fun `paired task submit validates and returns a task id`() = testApplication {
        val backend = Backend(); application { phoneApi(backend) }
        val response = client.post("/api/ask") { header("X-Vela-Token",backend.pairingToken); setBody("""{"text":" hello "}""") }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("hello",backend.task)
        assertTrue(response.bodyAsText().contains("task-1"))
    }
    @Test fun `malformed or oversized requests cannot start tasks or leak input`() = testApplication {
        val backend = Backend(); application { phoneApi(backend) }
        for (body in listOf("bad-private-secret", "[]", """{"text":true}""", """{"text":"${"x".repeat(PhoneProtocol.MAX_BODY_BYTES)}"}""")) {
            val response = client.post("/api/ask") { header("X-Vela-Token",backend.pairingToken); setBody(body) }
            assertEquals(HttpStatusCode.BadRequest,response.status)
            assertFalse(response.bodyAsText().contains("private-secret"))
        }
        assertEquals(0,backend.effects)
    }
    @Test fun `stale answers get conflict and missing prompt ids get bad request`() = testApplication {
        val backend = Backend(); application { phoneApi(backend) }
        val stale = client.post("/api/answer") { header("X-Vela-Token",backend.pairingToken); setBody("""{"allow":true,"promptId":"old"}""") }
        assertEquals(HttpStatusCode.Conflict,stale.status)
        val missing = client.post("/api/answer") { header("X-Vela-Token",backend.pairingToken); setBody("""{"allow":true}""") }
        assertEquals(HttpStatusCode.BadRequest,missing.status)
        val current = client.post("/api/answer") { header("X-Vela-Token",backend.pairingToken); setBody("""{"allow":false,"promptId":"current"}""") }
        assertEquals(HttpStatusCode.OK,current.status)
    }
    @Test fun `public shell is not cached or embeddable and contains no pairing secret`() = testApplication {
        val backend = Backend(); application { phoneApi(backend) }
        val response = client.get("/")
        assertEquals(HttpStatusCode.OK,response.status)
        assertEquals("no-store",response.headers["Cache-Control"])
        assertTrue(response.headers["Content-Security-Policy"]!!.contains("frame-ancestors 'none'"))
        assertFalse(response.bodyAsText().contains(backend.pairingToken))
    }
}
