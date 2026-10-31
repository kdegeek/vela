package app.vela.tv.mcp

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class McpPropertiesTest {
    @Test fun `generated parser attacks reject without echoing input and preserve the next valid request`() {
        repeat(32) { case ->
            val seed = 0x2FC0000 + case
            val random = Random(seed)
            val secret = "private-payload-$seed"
            val key = "field-$seed-界"
            val escapedKey = key.map { "\\u" + it.code.toString(16).padStart(4, '0') }.joinToString("")
            val id = when (case % 4) {
                0 -> JsonPrimitive(9_007_199_254_740_991L - random.nextLong(100))
                1 -> JsonPrimitive(-9_007_199_254_740_991L + random.nextLong(100))
                2 -> JsonPrimitive("id-$seed \\\"{}[]界")
                else -> JsonPrimitive("s".repeat(128))
            }
            val valid = rpc("ping", id, buildJsonObject { put("quoted", "$secret \" } ] { [ \\"); put(key, seed) })
            val unsafeId = 9_007_199_254_740_991L + random.nextLong(1, 10_000)
            val depth = random.nextInt(33, 81)
            val attacks = listOf(
                """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"arguments":{${JsonPrimitive(key)}:1,"$escapedKey":2},"private":"$secret"}}""",
                rpc("ping", JsonPrimitive(unsafeId), buildJsonObject { put("private", secret) }),
                valid.replace("\"id\":$id", "\"id\":$id,\"\\u0069d\":$id"),
                """{"jsonrpc":"2.0","id":1,"method":"ping","params":{"private":${"[".repeat(depth)}"$secret"${"]".repeat(depth)}}}""",
                rpc("ping", id, buildJsonObject { put("private", secret + "界".repeat(90_000 + case)) }),
            )
            for ((mutation, body) in attacks.withIndex()) {
                val label = "seed=$seed mutation=$mutation depth=$depth"
                val fault = assertThrows(label, McpFault::class.java) { McpProtocol.parse(body) }
                assertTrue(label, fault.status == 400 || fault.status == 413)
                assertFalse(label, fault.response().toString().contains(secret))
                val recovered = McpProtocol.parse(valid)
                assertEquals(label, id, recovered.id)
                assertEquals(label, "ping", recovered.method)
                assertEquals(label, seed, recovered.params.getValue(key).jsonPrimitive.int)
                assertEquals(label, "$secret \" } ] { [ \\", recovered.params.getValue("quoted").jsonPrimitive.content)
            }
        }
    }

    @Test fun `generated hostile HTTP bodies cannot bypass revocable auth or poison a subsequent tool call`() = testApplication {
        val fixture = McpFixture()
        // Synthetic test-only credentials; no random entropy or owner credential is needed for this property.
        val token = "t".repeat(43)
        val credential = McpCredential("properties", token)
        var credentials = listOf(credential)
        application { mcpApi(fixture.service(this), McpBearerAuthorization { credentials }) }

        fun HttpRequestBuilder.protocol(body: String, bearer: String?) {
            header(HttpHeaders.Host, "localhost")
            bearer?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Accept, "application/json, text/event-stream")
            modernHeaders("tools/call", "remote").forEach { (key, value) -> header(key, value) }
            setBody(body)
        }

        repeat(12) { case ->
            val seed = 0xA077000 + case
            val random = Random(seed)
            val secret = "private-http-$seed"
            val id = JsonPrimitive("request-$seed")
            val params = modernParams("name" to JsonPrimitive("remote"), "arguments" to fixture.args)
            val valid = rpc("tools/call", id, params)
            val hostile = when (case % 4) {
                0 -> valid.replace("\"key\":\"select\"", "\"key\":\"select\",\"\\u006bey\":\"$secret\"")
                1 -> valid.replace("\"arguments\":${fixture.args}", "\"arguments\":{\"key\":\"select\",\"times\":\"$secret\"}")
                2 -> {
                    val depth = random.nextInt(33, 81)
                    valid.replace("\"arguments\":${fixture.args}", "\"arguments\":{\"key\":${"[".repeat(depth)}\"$secret\"${"]".repeat(depth)}}")
                }
                else -> valid + " ".repeat(McpProtocol.MAX_BODY_BYTES + random.nextInt(1, 100)) + secret
            }
            val label = "seed=$seed mutation=${case % 4}"
            val effectsBefore = fixture.effects
            val checksBefore = fixture.checks
            val denied = client.post("/mcp") { protocol(hostile, if (random.nextBoolean()) null else "x" + token.drop(1)) }
            assertEquals(label, HttpStatusCode.Unauthorized, denied.status)
            assertEquals(label, "no-store", denied.headers[HttpHeaders.CacheControl])
            assertEquals(label, checksBefore, fixture.checks)
            assertEquals(label, effectsBefore, fixture.effects)

            val rejected = client.post("/mcp") { protocol(hostile, token) }
            assertTrue(label, rejected.status == HttpStatusCode.BadRequest || rejected.status == HttpStatusCode.PayloadTooLarge)
            assertFalse(label, rejected.bodyAsText().contains(secret))
            assertEquals(label, checksBefore, fixture.checks)
            assertEquals(label, effectsBefore, fixture.effects)
            assertNull(label, fixture.control.owner.value)

            // Reuse exactly the ID from the rejected request: rejection must not reserve it or consume a lease.
            val recovered = client.post("/mcp") { protocol(valid, token) }
            assertEquals(label, HttpStatusCode.OK, recovered.status)
            val recoveredBody = recovered.bodyAsText()
            assertTrue("$label type=${recovered.contentType()} body=$recoveredBody", recoveredBody.lineSequence().any { it.startsWith("data: ") })
            val data = recoveredBody.lineSequence().single { it.startsWith("data: ") }.removePrefix("data: ")
            val response = Json.parseToJsonElement(data).jsonObject
            assertEquals(label, id, response["id"])
            assertFalse(label, response.getValue("result").jsonObject.getValue("isError").jsonPrimitive.boolean)
            assertEquals(label, effectsBefore + 1, fixture.effects)
            assertNull(label, fixture.control.owner.value)

            credentials = emptyList()
            val revoked = client.post("/mcp") { protocol(valid, token) }
            assertEquals(label, HttpStatusCode.Unauthorized, revoked.status)
            assertEquals(label, effectsBefore + 1, fixture.effects)
            credentials = listOf(credential)
        }
    }
}
