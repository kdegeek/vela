package app.vela.tv.web

import app.vela.tv.VelaApp
import app.vela.tv.agent.Line
import app.vela.tv.layout.Layouts
import app.vela.tv.settings.VelaSettings
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.MessageDigest

/**
 * The phone page: settings (AI provider, keys, Jellyfin, Home Assistant) and a chat box, served on the LAN. Typing an
 * API key with a remote is miserable; a phone makes it easy. Every API call must carry the pairing token shown on the TV
 * (inside the QR code), because the page can change keys and drive the agent.
 */
class PhoneServer(private val app: VelaApp) {
    private var server: EmbeddedServer<*, *>? = null
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    private data class State(
        val settings: VelaSettings,
        val agentReady: Boolean,
        val running: Boolean,
        val pending: String?,
        val lines: List<Line>,
    )

    fun start() {
        if (server != null) return
        server = embeddedServer(CIO, port = PORT, host = "0.0.0.0") {
            routing {
                get("/") {
                    val html = app.assets.open("phone.html").bufferedReader().use { it.readText() }
                    call.respondText(html, ContentType.Text.Html)
                }
                get("/api/state") {
                    if (!authorized(call)) return@get
                    val s = app.settings.settings.value
                    val st = State(s.redacted(), s.agentReady, app.runner.running.value, app.runner.pending.value, app.runner.lines.value)
                    call.respondText(json.encodeToString(State.serializer(), st), ContentType.Application.Json)
                }
                post("/api/settings") {
                    if (!authorized(call)) return@post
                    val edit = runCatching { json.decodeFromString(VelaSettings.serializer(), call.receiveText()) }.getOrElse {
                        call.respondText("Those settings don't look right: ${it.message}", status = HttpStatusCode.BadRequest)
                        return@post
                    }
                    app.settings.update(app.settings.settings.value.merge(edit))
                    call.respondText("{}", ContentType.Application.Json)
                }
                post("/api/ask") {
                    if (!authorized(call)) return@post
                    val text = json.parseToJsonElement(call.receiveText()).jsonObject["text"]?.jsonPrimitive?.content.orEmpty()
                    app.scope.launch { app.runner.ask(text) }
                    call.respondText("{}", ContentType.Application.Json)
                }
                post("/api/answer") {
                    if (!authorized(call)) return@post
                    val allow = json.parseToJsonElement(call.receiveText()).jsonObject["allow"]?.jsonPrimitive?.boolean ?: false
                    app.runner.answer(allow)
                    call.respondText("{}", ContentType.Application.Json)
                }
                post("/api/stop") {
                    if (!authorized(call)) return@post
                    app.scope.launch { app.runner.stop() }
                    call.respondText("{}", ContentType.Application.Json)
                }
                get("/api/layout") {
                    if (!authorized(call)) return@get
                    call.respondText(Layouts.encode(app.settings.layout.value), ContentType.Application.Json)
                }
                put("/api/layout") {
                    if (!authorized(call)) return@put
                    Layouts.decode(call.receiveText()).fold(
                        { app.settings.setLayout(it); call.respondText("{}", ContentType.Application.Json) },
                        { call.respondText("That layout isn't valid: ${it.message}", status = HttpStatusCode.BadRequest) },
                    )
                }
            }
        }.also { it.start(wait = false) }
    }

    private suspend fun authorized(call: ApplicationCall): Boolean {
        val given = call.request.headers["X-Vela-Token"].orEmpty().toByteArray()
        val ok = MessageDigest.isEqual(given, app.settings.pairingToken.toByteArray())
        if (!ok) call.respondText("Scan the code on the TV to pair this phone.", status = HttpStatusCode.Unauthorized)
        return ok
    }

    /** The page's address with the pairing token, for the QR code on the TV. Null without a LAN address. */
    fun pairingUrl(): String? = lanAddress()?.let { "http://$it:$PORT/#t=${app.settings.pairingToken}" }

    fun shortUrl(): String? = lanAddress()?.let { "http://$it:$PORT" }

    private fun lanAddress(): String? = NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .firstOrNull { it.isSiteLocalAddress }
        ?.hostAddress

    companion object {
        const val PORT = 8765
    }
}
