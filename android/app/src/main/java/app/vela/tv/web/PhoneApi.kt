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
    val management: app.vela.tv.runtime.OwnerManagement? get() = null
    suspend fun apps(): String = "{\"apps\":[]}"
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
        managementRoutes(backend)
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
internal suspend fun paired(call: ApplicationCall, backend: PhoneBackend, block: suspend () -> String?) {
    call.response.headers.append("Cache-Control", "no-store")
    call.response.headers.append("X-Content-Type-Options", "nosniff")
    if (backend.pairingToken.isBlank() || call.request.headers.getAll("X-Vela-Token")?.size != 1 ||
        !MessageDigest.isEqual(call.request.headers["X-Vela-Token"].orEmpty().toByteArray(), backend.pairingToken.toByteArray())) {
        call.respondText("Scan the code on the TV to pair this phone.", status = HttpStatusCode.Unauthorized); return
    }
    try { block()?.let { call.respondText(it, ContentType.Application.Json) } }
    catch (e: TimeoutCancellationException) { call.respondText("Request timed out.", status = HttpStatusCode.RequestTimeout) }
    catch (e: CancellationException) { throw e }
    catch (e: app.vela.tv.integrations.ControlBusyException) { call.respondText("Another controller is active. Stop it or wait.", status = HttpStatusCode.Conflict) }
    catch (e: app.vela.tv.runtime.RoutineProfileException) { call.respondText("Routine profile or policy changed. Edit it from its intended profile.", status = HttpStatusCode.Conflict) }
    catch (e: app.vela.tv.runtime.ManagementConflictException) { call.respondText("This item changed or has pending work. Reload before trying again.", status = HttpStatusCode.Conflict) }
    catch (e: app.vela.tv.runtime.ManagementNotFoundException) { call.respondText("The requested item was not found.", status = HttpStatusCode.NotFound) }
    catch (e: app.vela.tv.runtime.RuntimeUnavailableException) { call.respondText("Storage or runtime is unavailable. Retry from Settings.", status = HttpStatusCode.ServiceUnavailable) }
    catch (e: app.vela.tv.profile.HouseholdException) {
        val status = when (e.code) {
            app.vela.tv.profile.HouseholdError.NOT_FOUND -> HttpStatusCode.NotFound
            app.vela.tv.profile.HouseholdError.CONFLICT, app.vela.tv.profile.HouseholdError.ACTIVE_PROFILE,
            app.vela.tv.profile.HouseholdError.LAST_PROFILE, app.vela.tv.profile.HouseholdError.PROFILE_HAS_MEMORY -> HttpStatusCode.Conflict
            app.vela.tv.profile.HouseholdError.STORAGE_FAILURE, app.vela.tv.profile.HouseholdError.CORRUPT_STORAGE,
            app.vela.tv.profile.HouseholdError.UNSUPPORTED_VERSION -> HttpStatusCode.ServiceUnavailable
            else -> HttpStatusCode.BadRequest
        }
        call.respondText("Household request could not be completed (${e.code.name.lowercase()}). Reload before retrying.", status = status)
    }
    catch (e: java.io.IOException) { call.respondText("Storage or connection is unavailable.", status = HttpStatusCode.ServiceUnavailable) }
    catch (e: Exception) { call.respondText("Invalid request. Check the fields and connection settings.", status = HttpStatusCode.BadRequest) }
}
/** Limits actual bytes, including chunked requests. Never echo a parser error containing a submitted secret. */
internal suspend fun body(call: ApplicationCall): String = withTimeout(10_000) {
    call.request.headers["Content-Length"]?.let { require(it.matches(Regex("0|[1-9][0-9]*")) && it.toLongOrNull()?.let { n -> n <= PhoneProtocol.MAX_BODY_BYTES } == true) }
    require(call.request.headers["Content-Encoding"].let { it == null || it.equals("identity", true) })
    val input = call.receiveChannel()
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = input.readAvailable(buffer, 0, buffer.size)
        if (count == -1) break
        require(output.size() + count <= PhoneProtocol.MAX_BODY_BYTES) { "Request is too large." }
        output.write(buffer, 0, count)
    }
    try {
        Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(output.toByteArray())).toString()
    } catch (_: java.nio.charset.CharacterCodingException) { throw IllegalArgumentException("Invalid UTF-8 request.") }
}
