package app.vela.tv.agent

import kotlinx.serialization.json.JsonObject

/** A tool the model may call: name, what it does, and a JSON Schema for its arguments. */
data class ToolSpec(val name: String, val description: String, val parameters: JsonObject)

/** One tool call the model asked for. [arguments] is the decoded JSON object (empty when the model sent none). */
data class ToolCall(val id: String, val name: String, val arguments: JsonObject)

/** The conversation, provider-neutral. Each provider converts it to its own wire format. */
sealed interface Msg {
    data class User(val text: String) : Msg
    data class Assistant(val text: String?, val calls: List<ToolCall> = emptyList()) : Msg
    data class ToolResult(val callId: String, val name: String, val content: String, val isError: Boolean = false) : Msg
}

/** What the model said back in one step. No [calls] means it is finished. */
data class Turn(val text: String?, val calls: List<ToolCall>)

/** A model endpoint with tool calling. */
interface Provider {
    suspend fun complete(system: String, history: List<Msg>, tools: List<ToolSpec>): Turn
}

/** The provider answered with an error, or something we couldn't read. [message] is safe to show the owner. */
class ProviderException(message: String, cause: Throwable? = null) : Exception(message, cause)
