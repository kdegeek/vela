package app.vela.tv.web

import app.vela.tv.runtime.RuntimeFixture
import app.vela.tv.runtime.OwnerManagement
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ManagementApiTest {
    private class Backend(override val management: OwnerManagement) : PhoneBackend {
        override val pairingToken = "paired-token"
        override fun html() = ""
        override suspend fun state() = "{}"
        override suspend fun settings(text: String) { }
        override suspend fun ask(text: String) = "id"
        override suspend fun answer(answer: PhoneProtocol.Answer) = false
        override suspend fun remote(key: String) { }
        override suspend fun connections() = "{}"
        override suspend fun stop() { }
        override suspend fun clear() { }
        override suspend fun layout() = "{}"
        override suspend fun layout(text: String) { }
    }
    @Test fun `management routes are paired bounded typed revisioned and never poll memory`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val fixture = RuntimeFixture(backgroundScope); val management = fixture.management()
            testApplication {
                application { phoneApi(Backend(management)) }
                val routes = listOf(
                    HttpMethod.Get to "/api/profiles", HttpMethod.Post to "/api/profiles", HttpMethod.Put to "/api/profiles/id",
                    HttpMethod.Post to "/api/profiles/active", HttpMethod.Delete to "/api/profiles/id",
                    HttpMethod.Get to "/api/profiles/id/memories", HttpMethod.Post to "/api/profiles/id/memories",
                    HttpMethod.Put to "/api/profiles/id/memories/m", HttpMethod.Delete to "/api/profiles/id/memories/m",
                    HttpMethod.Post to "/api/profiles/id/memories/clear", HttpMethod.Get to "/api/profiles/id/export",
                    HttpMethod.Get to "/api/routines", HttpMethod.Post to "/api/routines", HttpMethod.Put to "/api/routines/id",
                    HttpMethod.Delete to "/api/routines/id", HttpMethod.Post to "/api/routines/id/enabled", HttpMethod.Post to "/api/routines/id/run",
                    HttpMethod.Post to "/api/routine-runs/id/cancel", HttpMethod.Post to "/api/routine-runs/id/restart",
                    HttpMethod.Get to "/api/external-control", HttpMethod.Post to "/api/external-control/enable", HttpMethod.Post to "/api/external-control/revoke",
                    HttpMethod.Get to "/api/apps", HttpMethod.Post to "/api/household/reload",
                )
                for ((method, path) in routes) {
                    val response = client.request(path) { this.method = method; setBody("{}") }
                    assertEquals(path, HttpStatusCode.Unauthorized, response.status)
                    assertEquals("no-store", response.headers["Cache-Control"])
                }
                suspend fun create(body: String) = client.post("/api/profiles") { header("X-Vela-Token", "paired-token"); setBody(body) }
                for (revision in listOf("\"0\"", "0.0", "true", "-1", "9223372036854775808")) {
                    assertEquals(HttpStatusCode.BadRequest, create("""{"revision":$revision,"profile":{"displayName":"Other"}}""").status)
                }
                assertEquals(HttpStatusCode.BadRequest, create("""{"revision":0,"revision":0,"profile":{"displayName":"Other"}}""").status)
                assertEquals(HttpStatusCode.BadRequest, create("""{"revision":0,"profile":{"displayName":"Other","restrictions":{"allowAgentActions":"true"}}}""").status)
                assertEquals(HttpStatusCode.BadRequest, create("x".repeat(PhoneProtocol.MAX_BODY_BYTES + 1)).status)
                assertEquals(HttpStatusCode.OK, create("""{"revision":0,"profile":{"displayName":"Other"}}""").status)
                assertEquals(HttpStatusCode.Conflict, create("""{"revision":0,"profile":{"displayName":"Stale"}}""").status)
                val profileId = management.profiles().activeProfileId
                val save = client.post("/api/profiles/$profileId/memories") {
                    header("X-Vela-Token", "paired-token"); setBody("""{"revision":1,"kind":"fact","text":"private owner fact","shareWithAgent":false}""")
                }
                assertEquals(save.bodyAsText(), HttpStatusCode.OK, save.status)
                val profiles = client.get("/api/profiles") { header("X-Vela-Token", "paired-token") }
                assertFalse(profiles.bodyAsText().contains("private owner fact")); assertFalse(profiles.bodyAsText().contains("memories"))
                val malformed = client.post("/api/external-control/enable") { header("X-Vela-Token", "paired-token"); setBody("""{"ownerConfirmed":"true"}""") }
                assertEquals(HttpStatusCode.BadRequest, malformed.status)
                val forged = client.post("/api/routines") {
                    header("X-Vela-Token", "paired-token")
                    setBody("""{"spec":{"name":"Forged","task":"Observe","schedule":{"type":"once","at":2000000},"ownerProfileId":"other"}}""")
                }
                assertEquals(HttpStatusCode.BadRequest, forged.status)
                val routine = client.post("/api/routines") {
                    header("X-Vela-Token", "paired-token")
                    setBody("""{"spec":{"name":"Valid","task":"Observe","schedule":{"type":"once","at":2000000}}}""")
                }
                assertEquals(routine.bodyAsText(), HttpStatusCode.OK, routine.status)
                assertEquals(profileId, Json.parseToJsonElement(routine.bodyAsText()).jsonObject["spec"]!!.jsonObject["ownerProfileId"]!!.jsonPrimitive.content)
                val missing = client.get("/api/profiles/missing/memories") { header("X-Vela-Token", "paired-token") }
                assertEquals(HttpStatusCode.NotFound, missing.status)
                val conflict = client.get("/api/profiles/$profileId/memories?expectedRevision=0") { header("X-Vela-Token", "paired-token") }
                assertEquals(HttpStatusCode.Conflict, conflict.status)
                val badUtf8 = client.post("/api/profiles") { header("X-Vela-Token", "paired-token"); setBody(byteArrayOf(0xc3.toByte(), 0x28)) }
                assertEquals(HttpStatusCode.BadRequest, badUtf8.status)
                fixture.running.value = true
                assertEquals(HttpStatusCode.Conflict, create("""{"revision":2,"profile":{"displayName":"Busy"}}""").status)
                fixture.running.value = false; fixture.failHouseholdWrites = true
                val storage = create("""{"revision":2,"profile":{"displayName":"Storage"}}""")
                assertEquals(HttpStatusCode.ServiceUnavailable, storage.status)
                assertFalse(storage.bodyAsText().contains("private-storage-cause"))
                val unavailable = client.get("/api/profiles") { header("X-Vela-Token", "paired-token") }
                assertEquals(HttpStatusCode.ServiceUnavailable, unavailable.status)
            }
        } finally { Dispatchers.resetMain() }
    }
}
