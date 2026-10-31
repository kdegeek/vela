package app.vela.tv.mcp

import kotlinx.serialization.json.*

object McpProtocol {
    const val CURRENT = "2026-07-28"
    const val LEGACY = "2025-11-25"
    const val VERSION_META = "io.modelcontextprotocol/protocolVersion"
    const val CAPABILITIES_META = "io.modelcontextprotocol/clientCapabilities"
    const val SERVER_META = "io.modelcontextprotocol/serverInfo"
    const val MAX_BODY_BYTES = 262_144
    const val BUSY = -31000 // Application-defined, outside the JSON-RPC and MCP reserved ranges.
    val supportedVersions = listOf(CURRENT, LEGACY)
    val serverInfo = buildJsonObject { put("name", "vela-tv"); put("version", "0.1.0") }
    val capabilities = buildJsonObject { put("tools", buildJsonObject { put("listChanged", false) }) }

    data class Message(val id: JsonPrimitive?, val method: String, val params: JsonObject) {
        val modern: Boolean get() = (params["_meta"] as? JsonObject)?.containsKey(VERSION_META) == true
    }

    fun parse(body: String): Message {
        if (body.toByteArray(Charsets.UTF_8).size > MAX_BODY_BYTES) throw McpFault(-32600, "Request too large.", 413)
        val value = try {
            checkStructure(body)
            Json.parseToJsonElement(body)
        } catch (e: Exception) { throw McpFault(-32700, "Parse error.", 400) }
        val obj = value as? JsonObject ?: throw McpFault(-32600, "Expected one JSON-RPC object; batching is unsupported.", 400)
        val id = obj["id"] as? JsonPrimitive
        val validId = id?.takeIf { validId(it) }
        if (obj["jsonrpc"] != JsonPrimitive("2.0") || obj.keys.any { it !in setOf("jsonrpc", "id", "method", "params") } ||
            ("id" in obj && validId == null)) throw McpFault(-32600, "Invalid JSON-RPC request.", 400, validId)
        val method = (obj["method"] as? JsonPrimitive)?.takeIf { it.isString && it.content.length in 1..128 }?.content
            ?: throw McpFault(-32600, "Invalid JSON-RPC method.", 400, validId)
        val params = if ("params" in obj) obj["params"] as? JsonObject
            ?: throw McpFault(-32602, "Params must be an object.", 400, validId) else JsonObject(emptyMap())
        if ("_meta" in params && params["_meta"] !is JsonObject) throw McpFault(-32602, "Metadata must be an object.", 400, validId)
        return Message(validId, method, params)
    }

    fun validId(id: JsonPrimitive): Boolean = id != JsonNull &&
        if (id.isString) id.content.length in 1..128 else id.content.matches(Regex("-?(0|[1-9][0-9]*)")) &&
            id.content.toLongOrNull()?.let { it in -9_007_199_254_740_991L..9_007_199_254_740_991L } == true

    fun result(id: JsonPrimitive, result: JsonObject, modern: Boolean): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0"); put("id", id)
        put("result", if (modern) buildJsonObject {
            result.forEach { (key, value) -> put(key, value) }
            put("resultType", "complete")
            put("_meta", buildJsonObject { put(SERVER_META, serverInfo) })
        } else result)
    }

    /** Bound nesting before recursive deserialization, and reject duplicate keys (including escaped spellings). */
    private fun checkStructure(body: String) {
        data class Frame(val objectFrame: Boolean, val keys: MutableSet<String> = mutableSetOf())
        val stack = ArrayList<Frame>()
        var i = 0
        while (i < body.length) {
            when (body[i]) {
                '{', '[' -> {
                    stack += Frame(body[i] == '{')
                    require(stack.size <= 32)
                }
                '}', ']' -> { require(stack.isNotEmpty()); stack.removeAt(stack.lastIndex) }
                '"' -> {
                    val start = i++
                    while (i < body.length && body[i] != '"') {
                        if (body[i] == '\\') i++
                        i++
                    }
                    require(i < body.length)
                    var next = i + 1
                    while (next < body.length && body[next].isWhitespace()) next++
                    if (next < body.length && body[next] == ':') {
                        val frame = stack.lastOrNull()
                        require(frame?.objectFrame == true)
                        val key = Json.parseToJsonElement(body.substring(start, i + 1)).jsonPrimitive.content
                        require(frame.keys.add(key))
                    }
                }
            }
            i++
        }
        require(stack.isEmpty())
    }
}

internal class McpFault(
    val code: Int,
    override val message: String,
    val status: Int = 400,
    val id: JsonPrimitive? = null,
    val data: JsonObject? = null,
) : Exception(message) {
    fun response(requestId: JsonPrimitive? = id, modern: Boolean = false) = buildJsonObject {
        put("jsonrpc", "2.0")
        if (requestId != null) put("id", requestId) else if (!modern) put("id", JsonNull)
        put("error", buildJsonObject { put("code", code); put("message", message); data?.let { put("data", it) } })
    }
}

internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
internal fun JsonObject.only(vararg keys: String) {
    if (this.keys.any { it != "_meta" && it !in keys }) throw McpFault(-32602, "Unsupported request parameter.")
}
