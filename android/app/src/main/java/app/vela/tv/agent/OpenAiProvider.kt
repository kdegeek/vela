package app.vela.tv.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient

/**
 * Any OpenAI-compatible `/chat/completions` endpoint: OpenAI itself, OpenRouter, or a LAN Ollama/LM Studio/vLLM.
 * Uses `max_tokens` (not `max_completion_tokens`) because that is the spelling every server accepts.
 */
class OpenAiProvider(
    baseUrl: String,
    private val apiKey: String?,
    private val model: String,
    private val client: OkHttpClient = defaultHttpClient(),
    private val maxTokens: Int = 4096,
) : Provider {
    private val endpoint = baseUrl.trimEnd('/') + "/chat/completions"

    override suspend fun complete(system: String, history: List<Msg>, tools: List<ToolSpec>): Turn {
        val headers = if (apiKey.isNullOrBlank()) emptyMap() else mapOf("Authorization" to "Bearer $apiKey")
        val reply = client.postJson(endpoint, headers, requestBody(system, history, tools))
        reply.requireOk("The model server", apiKey)
        return parse(reply.parseObject("The model server"))
    }

    private fun requestBody(system: String, history: List<Msg>, tools: List<ToolSpec>) = buildJsonObject {
        put("model", model)
        put("max_tokens", maxTokens)
        putJsonArray("messages") {
            add(buildJsonObject { put("role", "system"); put("content", system) })
            history.forEach { add(message(it)) }
        }
        if (tools.isNotEmpty()) putJsonArray("tools") {
            tools.forEach { t ->
                add(buildJsonObject {
                    put("type", "function")
                    putJsonObject("function") {
                        put("name", t.name)
                        put("description", t.description)
                        put("parameters", t.parameters)
                    }
                })
            }
        }
    }

    private fun message(m: Msg): JsonObject = when (m) {
        is Msg.User -> buildJsonObject { put("role", "user"); put("content", m.text) }
        is Msg.Assistant -> buildJsonObject {
            put("role", "assistant")
            put("content", m.text?.let { JsonPrimitive(it) } ?: JsonNull)
            if (m.calls.isNotEmpty()) putJsonArray("tool_calls") {
                m.calls.forEach { c ->
                    add(buildJsonObject {
                        put("id", c.id)
                        put("type", "function")
                        putJsonObject("function") {
                            put("name", c.name)
                            put("arguments", c.arguments.toString())
                        }
                    })
                }
            }
        }
        is Msg.ToolResult -> buildJsonObject {
            put("role", "tool")
            put("tool_call_id", m.callId)
            put("content", m.content)
        }
    }

    private fun parse(root: JsonObject): Turn {
        val message = (root["choices"] as? JsonArray)?.firstOrNull()?.jsonObject?.get("message") as? JsonObject
            ?: throw ProviderException("The model server's reply had no answer in it")
        val reason = ((root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject)?.get("finish_reason") as? JsonPrimitive
        if (reason?.contentOrNull in setOf("length", "content_filter")) throw ProviderException("The model could not finish its answer (${reason?.contentOrNull}). Try a shorter request.")
        val text = (message["content"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        val calls = (message["tool_calls"] as? JsonArray).orEmpty().mapIndexed { i, el ->
            val call = el.jsonObject
            val fn = call["function"]?.jsonObject ?: throw ProviderException("The model server sent a malformed tool call")
            val name = (fn["name"] as? JsonPrimitive)?.contentOrNull
                ?: throw ProviderException("The model server sent a tool call with no name")
            ToolCall(
                id = (call["id"] as? JsonPrimitive)?.contentOrNull ?: "call_$i",
                name = name,
                arguments = decodeArguments((fn["arguments"] as? JsonPrimitive)?.contentOrNull),
            )
        }
        return Turn(text, calls)
    }

    /** Models sometimes emit empty or broken argument strings; hand the tool something it can reject instead of crashing. */
    private fun decodeArguments(raw: String?): JsonObject {
        if (raw.isNullOrBlank()) return JsonObject(emptyMap())
        return try {
            Json.parseToJsonElement(raw) as? JsonObject ?: rawArgs(raw)
        } catch (e: Exception) {
            rawArgs(raw)
        }
    }

    private fun rawArgs(raw: String) = buildJsonObject { put("_raw", raw) }
}
