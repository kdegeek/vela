package app.vela.tv.web

import io.ktor.http.*
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.Application
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondText
import io.ktor.server.routing.*
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/** Android operations behind the paired HTTP boundary. No routes bypass this authorization wrapper. */
interface PhoneBackend {
    val pairingToken: String
    fun html(): String
    suspend fun state(): String
    suspend fun settings(text: String)
    suspend fun ask(text: String): String
    suspend fun answer(answer: PhoneProtocol.Answer): Boolean
    suspend fun remote(key: String)
    suspend fun connections(): String
    suspend fun stop()
    suspend fun clear()
    suspend fun layout(): String
    suspend fun layout(text: String)
}

fun Application.phoneApi(backend: PhoneBackend) {
    routing {
        get("/") {
            call.response.headers.append("Cache-Control", "no-store")
            call.response.headers.append("X-Content-Type-Options", "nosniff")
            call.response.headers.append("Referrer-Policy", "no-referrer")
            call.response.headers.append("Content-Security-Policy", "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; frame-ancestors 'none'; base-uri 'none'; form-action 'self'")
            call.respondText(backend.html(), ContentType.Text.Html)
        }
        get("/api/state") { paired(call, backend) { backend.state() } }
        post("/api/settings") { paired(call, backend) { backend.settings(body(call)); "{}" } }
        post("/api/ask") { paired(call, backend) {
            val id = backend.ask(PhoneProtocol.task(body(call)))
            buildJsonObject { put("taskId", id) }.toString()
        } }
        post("/api/answer") { paired(call, backend) {
            if (backend.answer(PhoneProtocol.answer(body(call)))) "{}" else {
                call.respondText("That prompt has expired. Refresh before answering.", status = HttpStatusCode.Conflict); null
            }
        } }
        post("/api/remote") { paired(call, backend) { backend.remote(PhoneProtocol.remote(body(call))); "{}" } }
        post("/api/connections/test") { paired(call, backend) { backend.connections() } }
        post("/api/stop") { paired(call, backend) { backend.stop(); "{}" } }
        post("/api/history/clear") { paired(call, backend) { backend.clear(); "{}" } }
        get("/api/layout") { paired(call, backend) { backend.layout() } }
        put("/api/layout") { paired(call, backend) { backend.layout(body(call)); "{}" } }
    }
}
private suspend fun paired(call: ApplicationCall, backend: PhoneBackend, block: suspend () -> String?) {
    call.response.headers.append("Cache-Control", "no-store")
    call.response.headers.append("X-Content-Type-Options", "nosniff")
    if (!MessageDigest.isEqual(call.request.headers["X-Vela-Token"].orEmpty().toByteArray(), backend.pairingToken.toByteArray())) {
        call.respondText("Scan the code on the TV to pair this phone.", status = HttpStatusCode.Unauthorized); return
    }
    try { block()?.let { call.respondText(it, ContentType.Application.Json) } }
    catch (e: TimeoutCancellationException) { call.respondText("Request timed out.", status = HttpStatusCode.RequestTimeout) }
    catch (e: CancellationException) { throw e }
    catch (e: Exception) { call.respondText("Invalid request. Check the fields and connection settings.", status = HttpStatusCode.BadRequest) }
}
/** Limits actual bytes, including chunked requests. Never echo a parser error containing a submitted secret. */
private suspend fun body(call: ApplicationCall): String = withTimeout(10_000) {
    require((call.request.headers["Content-Length"]?.toLongOrNull() ?: 0) <= PhoneProtocol.MAX_BODY_BYTES) { "Request is too large." }
    val input = call.receiveChannel()
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = input.readAvailable(buffer, 0, buffer.size)
        if (count == -1) break
        require(output.size() + count <= PhoneProtocol.MAX_BODY_BYTES) { "Request is too large." }
        output.write(buffer, 0, count)
    }
    output.toString(Charsets.UTF_8.name())
}
