package app.vela.tv.mcp

import app.vela.tv.agent.ToolOutcome
import app.vela.tv.guard.GuardHit
import app.vela.tv.guard.Risk
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

/** A real loopback socket proves CIO's disconnect propagation; no emulator, LAN listener or physical device is used. */
class McpCioTransportTest {
    @Test fun `application loopback listener returns from start and can stop and reopen`() = runBlocking {
        val listener = LoopbackMcpListener(0)
        val f = McpFixture()
        val service = f.service(this)
        val authorization = McpBearerAuthorization { emptyList() }
        try {
            withTimeout(3_000) { listener.start(service, authorization) }
            withTimeout(3_000) { listener.stop() }
            withTimeout(3_000) { listener.start(service, authorization) }
        } finally {
            withContext(NonCancellable) { listener.stop() }
            service.close()
        }
    }
    @Test fun `closing a modern SSE stream cancels an outstanding approval and releases control`() = runBlocking {
        val f = McpFixture(); f.check = { GuardHit(Risk.PURCHASE, "Buy movie") }
        val entered = CompletableDeferred<Unit>(); val stopped = CompletableDeferred<Unit>()
        f.approval = { try { entered.complete(Unit); awaitCancellation() } finally { stopped.complete(Unit) } }
        val token = McpCredential.generateToken()
        val service = f.service(this)
        val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) {
            mcpApi(service, McpBearerAuthorization { listOf(McpCredential("desktop", token)) })
        }.start(wait = false)
        val port = server.engine.resolvedConnectors().single().port
        val client = HttpClient(ClientCIO)
        try {
            withTimeout(10_000) {
                client.preparePost("http://127.0.0.1:$port/mcp") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    header(HttpHeaders.Accept, "application/json, text/event-stream")
                    modernHeaders("tools/call", "remote").forEach { (k, v) -> header(k, v) }
                    setBody(rpc("tools/call", params = modernParams("name" to JsonPrimitive("remote"), "arguments" to f.args)))
                }.execute { response ->
                    assertEquals(HttpStatusCode.OK, response.status)
                    val channel = response.bodyAsChannel()
                    assertEquals(": keep-alive", channel.readLine())
                    entered.await()
                    channel.cancel()
                }
                stopped.await()
                // The approval's finally runs before the enclosing lease's finally.
                f.control.owner.first { it == null }
            }
            assertEquals(0, f.effects)
        } finally { client.close(); service.close(); server.stop(0, 1_000) }
    }

    @Test fun `sequential modern socket calls deliver one matching result per effect`() = runBlocking {
        val f = McpFixture()
        val token = McpCredential.generateToken()
        val service = f.service(this)
        val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) {
            mcpApi(service, McpBearerAuthorization { listOf(McpCredential("desktop", token)) })
        }.start(wait = false)
        val url = "http://127.0.0.1:${server.engine.resolvedConnectors().single().port}/mcp"
        val client = HttpClient(ClientCIO)
        try {
            withTimeout(10_000) {
                repeat(16) { id ->
                    val response = client.post(url) { modernCall(token, id, f.args) }
                    assertEquals(HttpStatusCode.OK, response.status)
                    assertEquals(ContentType.Text.EventStream, response.contentType()?.withoutParameters())
                    val body = response.bodyAsText()
                    val events = body.lineSequence().filter { it.startsWith("data: ") }.toList()
                    assertEquals("id=$id effects=${f.effects} body=$body", 1, events.size)
                    val reply = Json.parseToJsonElement(events.single().removePrefix("data: ")).jsonObject
                    assertEquals(JsonPrimitive(id), reply["id"])
                    val result = reply.getValue("result").jsonObject
                    assertEquals("complete", result.string("resultType"))
                    assertFalse(result.getValue("isError").jsonPrimitive.boolean)
                    assertEquals("observed", result.getValue("content").jsonArray.single().jsonObject.string("text"))
                    assertEquals(id + 1, f.effects)
                    assertNull(f.control.owner.value)
                }
            }
        } finally { client.close(); service.close(); server.stop(0, 1_000) }
    }

    @Test fun `local Stop drains a socket invocation without replay or a successful result`() = cancelledSocketCall(revoke = false)

    @Test fun `credential revocation denies new socket calls while cancelled work drains`() = cancelledSocketCall(revoke = true)

    private fun cancelledSocketCall(revoke: Boolean) = runBlocking {
        val f = McpFixture()
        val entered = CompletableDeferred<Unit>()
        val draining = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        f.run = {
            try { entered.complete(Unit); awaitCancellation() }
            finally { withContext(NonCancellable) { draining.complete(Unit); releaseCleanup.await() } }
        }
        val token = McpCredential.generateToken()
        val credential = McpCredential("desktop", token)
        val credentials = AtomicReference(listOf(credential))
        val service = f.service(this)
        val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) {
            mcpApi(service, McpBearerAuthorization { credentials.get() })
        }.start(wait = false)
        val url = "http://127.0.0.1:${server.engine.resolvedConnectors().single().port}/mcp"
        val client = HttpClient(ClientCIO)
        try {
            withTimeout(10_000) {
                client.preparePost(url) { modernCall(token, 0, f.args) }.execute { response ->
                    assertEquals(HttpStatusCode.OK, response.status)
                    val channel = response.bodyAsChannel()
                    assertEquals(": keep-alive", channel.readLine())
                    entered.await()
                    if (revoke) credentials.set(emptyList())
                    service.cancelAll()
                    draining.await()
                    assertNotNull("Control remains owned during cancellation cleanup", f.control.owner.value)
                    val competing = client.post(url) { modernCall(token, 1, f.args) }
                    if (revoke) assertEquals(HttpStatusCode.Unauthorized, competing.status)
                    else {
                        val event = competing.bodyAsText().lineSequence().single { it.startsWith("data: ") }
                        assertEquals(McpProtocol.BUSY, Json.parseToJsonElement(event.removePrefix("data: ")).jsonObject.errorCode())
                    }
                    assertEquals(1, f.effects)
                    releaseCleanup.complete(Unit)
                    f.control.owner.first { it == null }
                    // Read to EOF: no result event may be emitted for the cancelled invocation.
                    while (true) {
                        val line = channel.readLine() ?: break
                        assertFalse(line, line.startsWith("data: "))
                    }
                }
                credentials.set(listOf(credential))
                f.run = { ToolOutcome("after-drain") }
                val recovered = client.post(url) { modernCall(token, 2, f.args) }
                val events = recovered.bodyAsText().lineSequence().filter { it.startsWith("data: ") }.toList()
                assertEquals(1, events.size)
                val reply = Json.parseToJsonElement(events.single().removePrefix("data: ")).jsonObject
                assertEquals(JsonPrimitive(2), reply["id"])
                assertEquals("after-drain", reply.getValue("result").jsonObject.getValue("content").jsonArray.single().jsonObject.string("text"))
                assertEquals(2, f.effects)
            }
        } finally {
            releaseCleanup.complete(Unit)
            client.close(); service.close(); server.stop(0, 1_000)
        }
    }

    private fun HttpRequestBuilder.modernCall(token: String, id: Int, args: JsonObject) {
        header(HttpHeaders.Authorization, "Bearer $token")
        contentType(ContentType.Application.Json)
        header(HttpHeaders.Accept, "application/json, text/event-stream")
        modernHeaders("tools/call", "remote").forEach { (k, v) -> header(k, v) }
        setBody(rpc("tools/call", JsonPrimitive(id), modernParams("name" to JsonPrimitive("remote"), "arguments" to args)))
    }
}
