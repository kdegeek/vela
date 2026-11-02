package app.vela.tv.mcp

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.server.response.ApplicationSendPipeline
import io.ktor.server.testing.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/** Hold the engine's content handoff so writer entry/failure can be tested without timing races. */
class McpStreamCleanupTest {
    @Test fun `cancelled writer entry cancels work even before its first write`() = testApplication {
        handedOffWriter { content, fixture, stopped ->
            val channel = ByteChannel(autoFlush = true)
            try {
                val writer = async(start = CoroutineStart.UNDISPATCHED) {
                    currentCoroutineContext().cancel()
                    content.writeTo(channel)
                }
                try { writer.await(); fail("Cancelled writer must not produce a reply") }
                catch (_: CancellationException) { }
                stopped.await()
                fixture.control.owner.first { it == null }
                assertEquals(1, fixture.effects)
                channel.close()
                assertNull(channel.readLine())
            } finally { channel.cancel() }
        }
    }

    @Test fun `failed first heartbeat cancels work after response handoff`() = testApplication {
        handedOffWriter { content, fixture, stopped ->
            val channel = ByteChannel(autoFlush = true)
            channel.cancel(IOException("test peer closed"))
            try { content.writeTo(channel); fail("Closed channel must reject the heartbeat") }
            catch (_: IOException) { }
            stopped.await()
            fixture.control.owner.first { it == null }
            assertEquals(1, fixture.effects)
        }
    }

    @Test fun `response setup failure cancels work without entering the writer`() = testApplication {
        val fixture = McpFixture()
        val entered = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        fixture.run = {
            try { entered.complete(Unit); awaitCancellation() }
            finally { stopped.complete(Unit) }
        }
        application {
            sendPipeline.intercept(ApplicationSendPipeline.Transform) {
                if (subject is OutgoingContent.WriteChannelContent) {
                    entered.await()
                    throw IOException("test response setup failed")
                }
            }
            mcpApi(fixture.service(this), McpBearerAuthorization { listOf(McpCredential("desktop", TOKEN)) })
        }
        withTimeout(5_000) {
            val response = client.post("/mcp") { modernCall(fixture) }
            assertEquals(HttpStatusCode.InternalServerError, response.status)
            stopped.await()
            fixture.control.owner.first { it == null }
            assertEquals(1, fixture.effects)
        }
    }

    private suspend fun ApplicationTestBuilder.handedOffWriter(
        block: suspend CoroutineScope.(OutgoingContent.WriteChannelContent, McpFixture, CompletableDeferred<Unit>) -> Unit,
    ) {
        val fixture = McpFixture()
        val entered = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val content = CompletableDeferred<OutgoingContent.WriteChannelContent>()
        fixture.run = {
            try { entered.complete(Unit); awaitCancellation() }
            finally { stopped.complete(Unit) }
        }
        application {
            sendPipeline.intercept(ApplicationSendPipeline.Transform) {
                val body = subject
                if (body is OutgoingContent.WriteChannelContent) {
                    content.complete(body)
                    proceedWith(TextContent("handed off", ContentType.Text.Plain))
                }
            }
            mcpApi(fixture.service(this), McpBearerAuthorization { listOf(McpCredential("desktop", TOKEN)) })
        }
        withTimeout(5_000) {
            val response = client.post("/mcp") { modernCall(fixture) }
            assertEquals("handed off", response.bodyAsText())
            entered.await()
            assertFalse("A normal content handoff must not cancel the invocation", stopped.isCompleted)
            block(content.await(), fixture, stopped)
        }
    }

    private fun HttpRequestBuilder.modernCall(fixture: McpFixture) {
        header(HttpHeaders.Host, "localhost")
        header(HttpHeaders.Authorization, "Bearer $TOKEN")
        contentType(ContentType.Application.Json)
        header(HttpHeaders.Accept, "application/json, text/event-stream")
        modernHeaders("tools/call", "remote").forEach { (key, value) -> header(key, value) }
        setBody(rpc("tools/call", params = modernParams("name" to JsonPrimitive("remote"), "arguments" to fixture.args)))
    }

    private companion object { val TOKEN = "t".repeat(43) }
}
