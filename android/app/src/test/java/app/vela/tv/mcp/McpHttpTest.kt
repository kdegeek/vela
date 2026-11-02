package app.vela.tv.mcp

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import io.ktor.server.testing.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class McpHttpTest {
    private val token = McpCredential.generateToken()
    private val credential = McpCredential("test-desktop", token)
    private val auth = McpBearerAuthorization { listOf(credential) }
    private fun HttpRequestBuilder.authorized() { header(HttpHeaders.Host, "localhost"); header(HttpHeaders.Authorization, "Bearer $token") }
    private fun HttpRequestBuilder.protocol(body: String, modernMethod: String? = null, name: String? = null) {
        authorized(); contentType(ContentType.Application.Json)
        header(HttpHeaders.Accept, "application/json, text/event-stream")
        if (modernMethod != null) modernHeaders(modernMethod, name).forEach { (k,v) -> header(k,v) }
        setBody(body)
    }

    @Test fun `all HTTP verbs require local bearer authorization`() = testApplication {
        val f = McpFixture()
        application { mcpApi(f.service(this), auth) }
        for (method in listOf(HttpMethod.Get, HttpMethod.Post, HttpMethod.Delete, HttpMethod.Options, HttpMethod.Put)) {
            val response = client.request("/mcp") { this.method = method; header(HttpHeaders.Host, "localhost"); setBody("private-secret") }
            assertEquals(method.value, HttpStatusCode.Unauthorized, response.status)
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
            assertEquals("Bearer realm=\"vela-mcp\"", response.headers[HttpHeaders.WWWAuthenticate])
            assertFalse(response.bodyAsText().contains(token))
        }
        assertEquals(0, f.effects)
    }

    @Test fun `origin and host allowlists block browser and DNS rebinding traffic`() = testApplication {
        val f = McpFixture(); application { mcpApi(f.service(this), auth) }
        for ((key, value) in listOf(HttpHeaders.Origin to "https://evil.example", HttpHeaders.Origin to "null", HttpHeaders.Host to "evil.example",
            HttpHeaders.Host to "user@localhost", HttpHeaders.Host to "localhost/ignored", HttpHeaders.Host to "localhost#ignored")) {
            val response = client.post("/mcp") { protocol(rpc("ping", params = modernParams()), "ping"); headers.remove(key); header(key, value) }
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }
        assertEquals(0, f.effects)
    }

    @Test fun `legacy client initializes lists calls and deletes a session`() = testApplication {
        val f = McpFixture(); application { mcpApi(f.service(this), auth) }
        val params = Json.parseToJsonElement("""{"protocolVersion":"${McpProtocol.LEGACY}","capabilities":{},"clientInfo":{"name":"desktop","version":"1"}}""").jsonObject
        val init = client.post("/mcp") { protocol(rpc("initialize", params = params)) }
        assertEquals(HttpStatusCode.OK, init.status)
        val session = init.headers["MCP-Session-Id"]!!
        val ready = client.post("/mcp") { protocol(rpc("notifications/initialized", null)); header("MCP-Session-Id", session) }
        assertEquals(HttpStatusCode.Accepted, ready.status); assertEquals("", ready.bodyAsText())
        val list = client.post("/mcp") { protocol(rpc("tools/list")); header("MCP-Session-Id", session); header("MCP-Protocol-Version", McpProtocol.LEGACY) }
        assertTrue(list.bodyAsText().contains("inputSchema"))
        val action = client.post("/mcp") {
            protocol(rpc("tools/call", params = buildJsonObject { put("name", "remote"); put("arguments", f.args) }))
            header("MCP-Session-Id", session); header("MCP-Protocol-Version", McpProtocol.LEGACY)
        }
        assertEquals(HttpStatusCode.OK, action.status)
        assertEquals(ContentType.Application.Json, action.contentType()?.withoutParameters())
        assertTrue(action.bodyAsText().contains("observed")); assertEquals(1, f.effects)
        assertEquals(HttpStatusCode.NoContent, client.delete("/mcp") { authorized(); header("MCP-Session-Id", session) }.status)
        assertEquals(HttpStatusCode.NotFound, client.post("/mcp") { protocol(rpc("ping")); header("MCP-Session-Id", session) }.status)
    }

    @Test fun `modern tool call returns request-scoped SSE with no legacy session`() = testApplication {
        val f = McpFixture(); application { mcpApi(f.service(this), auth) }
        val response = client.post("/mcp") {
            protocol(rpc("tools/call", params = modernParams("name" to JsonPrimitive("remote"), "arguments" to f.args)), "tools/call", "remote")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(ContentType.Text.EventStream, response.contentType()?.withoutParameters())
        assertEquals("no", response.headers["X-Accel-Buffering"])
        assertNull(response.headers["MCP-Session-Id"])
        val data = response.bodyAsText().lineSequence().first { it.startsWith("data: ") }.removePrefix("data: ")
        val result = Json.parseToJsonElement(data).jsonObject.getValue("result").jsonObject
        assertEquals("complete", result.string("resultType")); assertEquals(1, f.effects)
    }

    @Test fun `sequential modern calls each deliver exactly one matching successful result`() = testApplication {
        val f = McpFixture(); application { mcpApi(f.service(this), auth) }
        repeat(2) { id ->
            val response = client.post("/mcp") {
                protocol(rpc("tools/call", JsonPrimitive(id), modernParams("name" to JsonPrimitive("remote"), "arguments" to f.args)), "tools/call", "remote")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            val events = body.lineSequence().filter { it.startsWith("data: ") }.toList()
            assertEquals("id=$id effects=${f.effects} body=$body", 1, events.size)
            val reply = Json.parseToJsonElement(events.single().removePrefix("data: ")).jsonObject
            assertEquals(JsonPrimitive(id), reply["id"])
            assertFalse(reply.getValue("result").jsonObject.getValue("isError").jsonPrimitive.boolean)
            assertEquals(id + 1, f.effects)
        }
    }

    @Test fun `content negotiation malformed JSON compression and large bodies fail before tools`() = testApplication {
        val f = McpFixture(); application { mcpApi(f.service(this), auth) }
        val malformed = client.post("/mcp") { protocol("private-secret") }
        assertEquals(HttpStatusCode.BadRequest, malformed.status)
        assertFalse(malformed.bodyAsText().contains("private-secret"))
        val large = client.post("/mcp") { protocol("x".repeat(McpProtocol.MAX_BODY_BYTES + 1)) }
        assertEquals(HttpStatusCode.PayloadTooLarge, large.status)
        val missingType = client.post("/mcp") { authorized(); header(HttpHeaders.Accept, "application/json, text/event-stream"); setBody("{}") }
        assertEquals(HttpStatusCode.UnsupportedMediaType, missingType.status)
        val unacceptable = client.post("/mcp") { authorized(); contentType(ContentType.Application.Json); header(HttpHeaders.Accept, "application/json, text/event-stream;q=0"); setBody("{}") }
        assertEquals(HttpStatusCode.NotAcceptable, unacceptable.status)
        val compressed = client.post("/mcp") { protocol("{}"); header(HttpHeaders.ContentEncoding, "gzip") }
        assertEquals(HttpStatusCode.UnsupportedMediaType, compressed.status)
        assertEquals(0, f.effects)
    }

    @Test fun `GET has no unrequested stream and unsupported modern methods use JSON-RPC 404`() = testApplication {
        val f = McpFixture(); application { mcpApi(f.service(this), auth) }
        assertEquals(HttpStatusCode.MethodNotAllowed, client.get("/mcp") { authorized() }.status)
        val response = client.post("/mcp") { protocol(rpc("unknown", params = modernParams()), "unknown") }
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals(-32601, Json.parseToJsonElement(response.bodyAsText()).jsonObject.errorCode())
    }

    @Test fun `actual chunked bytes and malformed UTF8 are rejected without echoing input`() = testApplication {
        val f = McpFixture(); application { mcpApi(f.service(this), auth) }
        val chunked = client.post("/mcp") {
            authorized(); header(HttpHeaders.Accept, "application/json, text/event-stream")
            setBody(object : OutgoingContent.WriteChannelContent() {
                override val contentType = ContentType.Application.Json
                override suspend fun writeTo(channel: ByteWriteChannel) { repeat(33) { channel.writeFully(ByteArray(8192) { 'x'.code.toByte() }) } }
            })
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, chunked.status)
        val invalidUtf8 = client.post("/mcp") {
            authorized(); header(HttpHeaders.Accept, "application/json, text/event-stream"); contentType(ContentType.Application.Json)
            setBody(byteArrayOf(0xc3.toByte(), 0x28))
        }
        assertEquals(HttpStatusCode.BadRequest, invalidUtf8.status)
        assertEquals(-32700, Json.parseToJsonElement(invalidUtf8.bodyAsText()).jsonObject.errorCode())
        assertEquals(0, f.effects)
    }

    @Test fun `duplicate credentials and mirrored protocol headers cannot authorize an action`() = testApplication {
        val f = McpFixture(); application { mcpApi(f.service(this), auth) }
        val duplicatedToken = client.post("/mcp") { protocol(rpc("ping", params = modernParams()), "ping"); header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.Unauthorized, duplicatedToken.status)
        val duplicatedMethod = client.post("/mcp") { protocol(rpc("ping", params = modernParams()), "ping"); header("Mcp-Method", "tools/call") }
        assertEquals(HttpStatusCode.BadRequest, duplicatedMethod.status)
        assertEquals(0, f.effects)
    }
}
