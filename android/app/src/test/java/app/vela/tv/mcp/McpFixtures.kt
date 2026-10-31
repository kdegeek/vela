package app.vela.tv.mcp

import app.vela.tv.agent.Tool
import app.vela.tv.agent.ToolOutcome
import app.vela.tv.agent.ToolSpec
import app.vela.tv.guard.GuardHit
import app.vela.tv.integrations.ControlArbiter
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.*

internal class McpFixture {
    val control = ControlArbiter()
    var checks = 0
    var effects = 0
    var approvals = 0
    var check: suspend () -> GuardHit? = { null }
    var approval: suspend () -> Boolean = { true }
    var run: suspend () -> ToolOutcome = { ToolOutcome("observed") }
    val tool = object : Tool {
        override val spec = ToolSpec("remote", "Press one button", Json.parseToJsonElement("""{
            "type":"object","additionalProperties":false,
            "properties":{"key":{"type":"string","enum":["up","select"]},"times":{"type":"integer"}},"required":["key"]
        }""").jsonObject)
        override suspend fun check(args: JsonObject): GuardHit? { checks++; return check() }
        override suspend fun run(args: JsonObject): ToolOutcome { effects++; return run() }
    }
    fun backend(approvalTimeoutMs: Long = 60_000) = GuardedToolBackend(listOf(tool), control, { _, _ -> approvals++; approval() }, approvalTimeoutMs = approvalTimeoutMs)
    fun service(scope: CoroutineScope) = McpService(backend(), scope)
    val principal = McpPrincipal("desktop", "credential-1", null)
    val args = buildJsonObject { put("key", "select") }
}

internal fun rpc(method: String, id: JsonPrimitive? = JsonPrimitive(1), params: JsonObject = JsonObject(emptyMap())) = buildJsonObject {
    put("jsonrpc", "2.0"); id?.let { put("id", it) }; put("method", method); put("params", params)
}.toString()

internal fun modernParams(vararg entries: Pair<String, JsonElement>) = buildJsonObject {
    entries.forEach { (k, v) -> put(k, v) }
    put("_meta", buildJsonObject { put(McpProtocol.VERSION_META, McpProtocol.CURRENT); put(McpProtocol.CAPABILITIES_META, JsonObject(emptyMap())) })
}

internal fun modernHeaders(method: String, name: String? = null) = buildMap {
    put("MCP-Protocol-Version", McpProtocol.CURRENT); put("Mcp-Method", method); name?.let { put("Mcp-Name", it) }
}

internal fun McpService.open(principal: McpPrincipal): String {
    val init = prepare(principal, rpc("initialize", params = Json.parseToJsonElement("""{
        "protocolVersion":"${McpProtocol.LEGACY}","capabilities":{},"clientInfo":{"name":"test","version":"1"}
    }""").jsonObject), emptyMap()) as McpService.Reply
    check(init.status == 200) { init.toString() }
    val session = checkNotNull(init.sessionId)
    val ready = prepare(principal, rpc("notifications/initialized", null), mapOf("MCP-Session-Id" to session)) as McpService.Reply
    check(ready.status == 202)
    return session
}

internal fun McpService.modernCall(principal: McpPrincipal, id: Int = 1, args: JsonObject = buildJsonObject { put("key", "select") }): McpService.Exchange =
    prepare(principal, rpc("tools/call", JsonPrimitive(id), modernParams("name" to JsonPrimitive("remote"), "arguments" to args)), modernHeaders("tools/call", "remote"))

internal fun JsonObject.errorCode() = getValue("error").jsonObject.getValue("code").jsonPrimitive.int
