package app.vela.tv.web

import app.vela.tv.DeviceStatus
import app.vela.tv.VelaApp
import app.vela.tv.agent.Line
import app.vela.tv.agent.Msg
import app.vela.tv.agent.TaskRecord
import app.vela.tv.control.HandsService
import app.vela.tv.layout.Layouts
import app.vela.tv.settings.VelaSettings
import io.ktor.server.cio.CIO
import io.ktor.server.engine.*
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.net.Inet4Address
import java.net.NetworkInterface

/** Android adapter for the paired HTTP API. Main-thread task/remote operations are serialized with TV input. */
class PhoneServer(private val app: VelaApp) {
    private var server: EmbeddedServer<*, *>? = null
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    @Serializable private data class State(
        val settings: VelaSettings, val agentReady: Boolean, val running: Boolean,
        val pending: String?, val pendingId: String?, val lines: List<Line>, val tasks: List<TaskRecord>, val device: DeviceStatus,
    )
    private val backend = object : PhoneBackend {
        override val pairingToken get() = app.settings.pairingToken
        override fun html() = app.assets.open("phone.html").bufferedReader().use { it.readText() }
        override suspend fun state(): String = withContext(Dispatchers.Main.immediate) {
            val s = app.settings.settings.value
            val approval = app.runner.session.pending.value
            json.encodeToString(State.serializer(), State(s.redacted(), s.agentReady, app.runner.running.value,
                approval?.question, approval?.id, app.runner.lines.value, app.runner.tasks.value, DeviceStatus.read(app)))
        }
        override suspend fun settings(text: String) = withContext(Dispatchers.IO) {
            val edit = json.decodeFromString(VelaSettings.serializer(), text)
            app.settings.update(app.settings.settings.value.merge(edit).validated())
        }
        override suspend fun ask(text: String): String = withContext(Dispatchers.Main.immediate) { app.runner.ask(text) }
        override suspend fun answer(answer: PhoneProtocol.Answer): Boolean = withContext(Dispatchers.Main.immediate) { app.runner.answer(answer.allow, answer.promptId) }
        override suspend fun remote(key: String) { withContext(Dispatchers.Main.immediate) {
            require(!app.runner.running.value) { "Stop the agent before using the phone remote." }
            val hands = HandsService.current ?: error("Enable Vela agent in Accessibility settings.")
            when (key) { "home" -> hands.goHome(); "back" -> hands.back(); "select" -> hands.dpad("center"); else -> hands.dpad(key) }
        } }
        override suspend fun connections(): String {
            val s = app.settings.settings.value
            val tests = coroutineScope {
                listOf(
                    async { testConnection("AI") { val p = s.makeProvider() ?: error("Not configured"); p.complete("Reply briefly. No actions.", listOf(Msg.User("Reply with Ready.")), emptyList()); "Connected" } },
                    async { testConnection("Jellyfin") { val client = s.jellyfin(app.settings.deviceId) ?: error("Needs address, token and user id"); "Connected · ${client.latest(1).size} recent titles checked" } },
                    async { testConnection("Home Assistant") { val client = s.homeAssistant() ?: error("Not configured"); "Connected · ${client.states().size} entities" } },
                ).awaitAll()
            }
            return buildJsonObject { tests.forEach { (name, result) -> put(name, result) } }.toString()
        }
        override suspend fun stop() = withContext(Dispatchers.Main.immediate) { app.runner.stop() }
        override suspend fun clear() = withContext(Dispatchers.Main.immediate) { app.runner.clear() }
        override suspend fun layout() = Layouts.encode(app.settings.layout.value)
        override suspend fun layout(text: String) { withContext(Dispatchers.IO) { app.settings.setLayout(Layouts.decode(text).getOrThrow()) } }
    }
    fun start() {
        if (server == null) server = embeddedServer(CIO, port = PORT, host = "0.0.0.0") { phoneApi(backend) }.also { it.start(wait = false) }
    }
    fun stop() { server?.stop(500, 1000); server = null }
    private suspend fun testConnection(name: String, block: suspend () -> String): Pair<String, String> = name to try {
        withTimeout(20_000) { block() }
    } catch (e: TimeoutCancellationException) { "Timed out" }
      catch (e: CancellationException) { throw e }
      catch (e: Exception) { e.message?.take(200) ?: "Connection failed" }
    fun pairingUrl(): String? = lanAddress()?.let { "http://$it:$PORT/#t=${app.settings.pairingToken}" }
    fun shortUrl(): String? = lanAddress()?.let { "http://$it:$PORT" }
    private fun lanAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }.flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>().firstOrNull { it.isSiteLocalAddress }?.hostAddress
    }.getOrNull()
    companion object { const val PORT = 8765 }
}
