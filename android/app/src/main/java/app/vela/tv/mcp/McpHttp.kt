package app.vela.tv.mcp

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Loopback only by default. Network access requires an explicit host allowlist and real TLS at this Ktor socket. */
data class McpHttpPolicy(
    val allowedHosts: Set<String> = setOf("localhost", "127.0.0.1", "::1", "[::1]"),
    val allowedOrigins: Set<String> = emptySet(),
    val allowTlsNetwork: Boolean = false,
    val bodyTimeoutMs: Long = 10_000,
) {
    init { require(allowedHosts.isNotEmpty() && bodyTimeoutMs > 0) }
}

/** Mounts routes only; never creates a listener or changes Android settings. No public discovery or credential route. */
fun Application.mcpApi(
    service: McpService,
    authorization: McpBearerAuthorization,
    policy: McpHttpPolicy = McpHttpPolicy(),
    path: String = "/mcp",
) {
    require(path.startsWith('/') && '?' !in path && '#' !in path)
    val readers = Semaphore(16)
    monitor.subscribe(ApplicationStopped) { service.close() }
    routing {
        route(path) {
            // A single wrapper authenticates every HTTP verb, including unsupported methods and session deletion.
            handle {
                call.response.headers.append(HttpHeaders.CacheControl, "no-store")
                call.response.headers.append("X-Content-Type-Options", "nosniff")
                val host = try {
                    val authority = call.request.headers[HttpHeaders.Host] ?: ""
                    val uri = URI("http://$authority")
                    if (uri.rawAuthority != authority || uri.rawUserInfo != null || !uri.rawPath.isNullOrEmpty() ||
                        uri.rawQuery != null || uri.rawFragment != null || uri.port !in -1..65535) null else uri.host?.lowercase()
                } catch (e: Exception) { null }
                val remote = call.request.local.remoteHost
                val loopback = remote in setOf("127.0.0.1", "::1", "0:0:0:0:0:0:0:1", "localhost")
                val origin = call.request.headers[HttpHeaders.Origin]
                if (host !in policy.allowedHosts || (!loopback && (!policy.allowTlsNetwork || call.request.local.scheme != "https")) ||
                    (origin != null && origin !in policy.allowedOrigins)) {
                    call.respondText("External control origin or host is not allowed.", status = HttpStatusCode.Forbidden)
                    return@handle
                }
                val authHeaders = call.request.headers.getAll(HttpHeaders.Authorization)
                val principal = authorization.authenticate(authHeaders?.singleOrNull())
                if (principal == null) {
                    call.response.headers.append(HttpHeaders.WWWAuthenticate, "Bearer realm=\"vela-mcp\"")
                    call.respondText("Local MCP authorization is required.", status = HttpStatusCode.Unauthorized)
                    return@handle
                }
                if (listOf("Host", "Origin", "MCP-Protocol-Version", "MCP-Session-Id", "Mcp-Method", "Mcp-Name").any {
                    (call.request.headers.getAll(it)?.size ?: 0) > 1
                }) {
                    call.respondText("Duplicate HTTP header.", status = HttpStatusCode.BadRequest); return@handle
                }
                when (call.request.local.method) {
                    HttpMethod.Post -> {
                        if (!readers.tryAcquire()) {
                            call.respondText("Too many requests.", status = HttpStatusCode.TooManyRequests); return@handle
                        }
                        val exchange = try {
                            validateHeaders(call)
                            service.prepare(principal, readBody(call, policy.bodyTimeoutMs), call.request.headers.entries().associate { it.key to it.value.joinToString(",") })
                        } catch (e: McpFault) { McpService.Reply(e.status, e.response(modern = call.request.headers["MCP-Protocol-Version"] == McpProtocol.CURRENT))
                        } catch (e: TimeoutCancellationException) { McpService.Reply(408)
                        } catch (e: CancellationException) { throw e
                        } catch (e: Exception) { McpService.Reply(400, McpFault(-32600, "Invalid HTTP request.").response())
                        } finally { readers.release() }
                        when (exchange) {
                            is McpService.Reply -> call.reply(exchange)
                            is McpService.Invocation -> if (exchange.modern) call.streamInvocation(exchange) else {
                                // Legacy disconnects do not cancel work; notifications/cancelled, local Stop, deletion or timeout do.
                                val result = exchange.await()
                                if (result == null) call.respond(HttpStatusCode.Accepted) else call.respondText(result.toString(), ContentType.Application.Json)
                            }
                        }
                    }
                    HttpMethod.Delete -> call.reply(service.terminate(principal, call.request.headers["MCP-Session-Id"], call.request.headers["MCP-Protocol-Version"]))
                    else -> {
                        call.response.headers.append(HttpHeaders.Allow, "POST, DELETE")
                        call.respond(HttpStatusCode.MethodNotAllowed)
                    }
                }
            }
        }
    }
}

private fun validateHeaders(call: ApplicationCall) {
    // Duplicate protocol/security headers are ambiguous even when proxies merge or select a different value.
    for (name in listOf("Host", "Origin", "Authorization", "MCP-Protocol-Version", "MCP-Session-Id", "Mcp-Method", "Mcp-Name", "Content-Type", "Content-Length")) {
        if ((call.request.headers.getAll(name)?.size ?: 0) > 1) throw McpFault(-32600, "Duplicate HTTP header.")
    }
    val type = call.request.headers[HttpHeaders.ContentType]?.let { ContentType.parse(it).withoutParameters() }
    if (type != ContentType.Application.Json) throw McpFault(-32600, "Use Content-Type application/json.", 415)
    if (call.request.headers[HttpHeaders.ContentEncoding]?.lowercase()?.let { it != "identity" } == true) {
        throw McpFault(-32600, "Encoded request bodies are unsupported.", 415)
    }
    val accept = call.request.headers.getAll(HttpHeaders.Accept).orEmpty().joinToString(",").let { parseHeaderValue(it) }
    fun accepts(value: String) = accept.any { it.value.equals(value, true) &&
        (it.params.firstOrNull { p -> p.name.equals("q", true) }?.value?.toDoubleOrNull() ?: 1.0) > 0 }
    if (!accepts("application/json") || !accepts("text/event-stream")) throw McpFault(-32600, "Accept must include application/json and text/event-stream.", 406)
}

private suspend fun readBody(call: ApplicationCall, timeoutMs: Long): String = withTimeout(timeoutMs) {
    val length = call.request.headers[HttpHeaders.ContentLength]
    if (length != null && (length.toLongOrNull()?.let { it in 0..McpProtocol.MAX_BODY_BYTES.toLong() } != true)) {
        throw McpFault(-32600, "Request body exceeds the byte limit.", 413)
    }
    val input = call.receiveChannel()
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = input.readAvailable(buffer, 0, buffer.size)
        if (count == -1) break
        if (out.size() + count > McpProtocol.MAX_BODY_BYTES) throw McpFault(-32600, "Request body exceeds the byte limit.", 413)
        out.write(buffer, 0, count)
    }
    try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(out.toByteArray())).toString()
    } catch (e: Exception) { throw McpFault(-32700, "Body is not valid UTF-8.") }
}

private suspend fun ApplicationCall.reply(reply: McpService.Reply) {
    reply.sessionId?.let { response.headers.append("MCP-Session-Id", it) }
    if (reply.body == null) respond(HttpStatusCode.fromValue(reply.status))
    else respondText(reply.body.toString(), ContentType.Application.Json, HttpStatusCode.fromValue(reply.status))
}

private suspend fun ApplicationCall.streamInvocation(invocation: McpService.Invocation) {
    try {
        response.headers.append("X-Accel-Buffering", "no")
        respondBytesWriter(ContentType.Text.EventStream.withCharset(Charsets.UTF_8), HttpStatusCode.OK) {
            try {
                currentCoroutineContext().ensureActive()
                // Heartbeat writes make disconnect observable even while a guard/approval callback is suspended.
                while (!invocation.result.isCompleted) {
                    writeStringUtf8(": keep-alive\n\n"); flush()
                    withTimeoutOrNull(1_000) { invocation.result.join() }
                }
                invocation.await()?.let {
                    currentCoroutineContext().ensureActive()
                    writeStringUtf8("event: message\ndata: $it\n\n"); flush()
                }
            } finally {
                // The writer can outlive respondBytesWriter. It owns cleanup through the final flush,
                // including cancellation or a failed write when a modern client disconnects.
                invocation.cancel()
            }
        }
    } catch (failure: Throwable) {
        // Response setup can fail or be cancelled before the writer block is entered.
        invocation.cancel()
        throw failure
    }
}
