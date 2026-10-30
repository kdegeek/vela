package app.vela.tv.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.OkHttpClient

/** Anthropic Messages API. The system prompt and tool list are cache-marked so each agent step reuses them. */
class AnthropicProvider(
    private val apiKey: String,
    private val model: String = "claude-sonnet-5-5",
    baseUrl: String = "https://api.anthropic.com",
    private val client: OkHttpClient = defaultHttpClient(),
    private val maxTokens: Int = 4096,
) : Provider {
    private val endpoint = baseUrl.trimEnd('/') + "/v1/messages"

    override suspend fun complete(system: String, history: List<Msg>, tools: List<ToolSpec>): Turn {
        val headers = mapOf("x-api-key" to apiKey, "anthropic-version" to "2023-06-01")
        val reply = client.postJson(endpoint, headers, requestBody(system, history, tools))
        reply.requireOk("Anthropic")
        return parse(reply.parseObject("Anthropic"))
    }

    private fun ephemeral() = buildJsonObject { put("type", "ephemeral") }

    private fun requestBody(system: String, history: List<Msg>, tools: List<ToolSpec>) = buildJsonObject {
        put("model", model)
        put("max_tokens", maxTokens)
        putJsonArray("system") {
            add(buildJsonObject {
                put("type", "text")
                put("text", system)
                put("cache_control", ephemeral())
            })
        }
        if (tools.isNotEmpty()) putJsonArray("tools") {
            tools.forEachIndexed { i, t ->
                add(buildJsonObject {
                    put("name", t.name)
                    put("description", t.description)
                    put("input_schema", t.parameters)
                    if (i == tools.lastIndex) put("cache_control", ephemeral())
                })
            }
        }
        putJsonArray("messages") { messages(history).forEach { (role, blocks) ->
            add(buildJsonObject { put("role", role); put("content", JsonArray(blocks)) })
        } }
    }

    /** Anthropic wants strictly alternating roles, so adjacent same-role messages are folded into one. */
    private fun messages(history: List<Msg>): List<Pair<String, List<JsonObject>>> {
        val out = mutableListOf<Pair<String, MutableList<JsonObject>>>()
        fun add(role: String, blocks: List<JsonObject>) {
            if (blocks.isEmpty()) return
            if (out.lastOrNull()?.first == role) out.last().second += blocks else out += role to blocks.toMutableList()
        }
        history.forEach { m ->
            when (m) {
                is Msg.User -> add("user", listOf(textBlock(m.text)))
                is Msg.Assistant -> add("assistant", buildList {
                    if (!m.text.isNullOrBlank()) add(textBlock(m.text))
                    m.calls.forEach { c ->
                        add(buildJsonObject {
                            put("type", "tool_use")
                            put("id", c.id)
                            put("name", c.name)
                            put("input", c.arguments)
                        })
                    }
                })
                is Msg.ToolResult -> add("user", listOf(buildJsonObject {
                    put("type", "tool_result")
                    put("tool_use_id", m.callId)
                    put("content", m.content)
                    if (m.isError) put("is_error", true)
                }))
            }
        }
        return out
    }

    private fun textBlock(text: String) = buildJsonObject { put("type", "text"); put("text", text) }

    private fun parse(root: JsonObject): Turn {
        val blocks = root["content"] as? JsonArray ?: throw ProviderException("Anthropic's reply had no content")
        val text = StringBuilder()
        val calls = mutableListOf<ToolCall>()
        blocks.forEach { el ->
            val b = el.jsonObject
            when ((b["type"] as? JsonPrimitive)?.contentOrNull) {
                "text" -> (b["text"] as? JsonPrimitive)?.contentOrNull?.let(text::append)
                "tool_use" -> calls += ToolCall(
                    id = (b["id"] as? JsonPrimitive)?.contentOrNull
                        ?: throw ProviderException("Anthropic sent a tool call with no id"),
                    name = (b["name"] as? JsonPrimitive)?.contentOrNull
                        ?: throw ProviderException("Anthropic sent a tool call with no name"),
                    arguments = b["input"] as? JsonObject ?: JsonObject(emptyMap()),
                )
            }
        }
        return Turn(text.toString().takeIf { it.isNotBlank() }, calls)
    }
}
