package app.vela.tv.agent

import app.vela.tv.guard.GuardHit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import kotlin.coroutines.coroutineContext

data class ToolOutcome(val content: String, val isError: Boolean = false)
interface Tool {
    val spec: ToolSpec
    suspend fun check(args: JsonObject): GuardHit? = null
    suspend fun run(args: JsonObject): ToolOutcome
}
sealed interface AgentEvent {
    data class Said(val text: String) : AgentEvent
    data class Calling(val call: ToolCall) : AgentEvent
    data class Returned(val call: ToolCall, val outcome: ToolOutcome) : AgentEvent
    data class Asking(val call: ToolCall, val hit: GuardHit) : AgentEvent
    data class Done(val text: String?) : AgentEvent
    data class Failed(val message: String) : AgentEvent
}

/** Serial tool execution is intentional: each screen action can invalidate the next action's target. */
class Agent(
    private val provider: Provider,
    private val tools: List<Tool>,
    private val system: String,
    private val confirm: suspend (ToolCall, GuardHit) -> Boolean,
    private val maxSteps: Int = 40,
    private val toolTimeoutMs: Long = 30_000,
) {
    private val byName = tools.associateBy { it.spec.name }
    init {
        require(maxSteps in 1..100)
        require(toolTimeoutMs > 0)
        require(byName.size == tools.size) { "Tool names must be unique" }
    }

    suspend fun run(task: String, history: List<Msg> = emptyList(), events: (AgentEvent) -> Unit = {}): List<Msg> {
        require(task.isNotBlank() && task.length <= 8_000) { "Ask in 1–8000 characters." }
        val msgs = ConversationWindow.trim(history).toMutableList()
        msgs += Msg.User(task)
        val seenIds = msgs.filterIsInstance<Msg.Assistant>().flatMap { it.calls }.map { it.id }.toMutableSet()
        var previousBatch: List<Pair<String, JsonObject>>? = null
        var repeats = 0
        repeat(maxSteps) {
            coroutineContext.ensureActive()
            val turn = try {
                withTimeout(120_000) { provider.complete(system, ConversationWindow.trim(msgs), tools.map { it.spec }) }
            } catch (e: TimeoutCancellationException) {
                events(AgentEvent.Failed("The model took too long. Check the connection and try again.")); return msgs
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                events(AgentEvent.Failed(e.message ?: "The model request failed.")); return msgs
            }
            if (turn.calls.size > 12 || turn.calls.any { it.id.isBlank() || it.name.isBlank() || it.id in seenIds } ||
                turn.calls.map { it.id }.distinct().size != turn.calls.size) {
                events(AgentEvent.Failed("The model returned an invalid batch of tool calls. No actions in that batch ran.")); return msgs
            }
            if (turn.calls.isEmpty() && turn.text.isNullOrBlank()) {
                events(AgentEvent.Failed("The model returned an empty answer. Try again or choose another model.")); return msgs
            }
            val batch = turn.calls.map { it.name to it.arguments }
            repeats = if (batch == previousBatch) repeats + 1 else 0
            previousBatch = batch
            if (repeats >= 4) {
                events(AgentEvent.Failed("Stopped because the agent kept repeating the same actions. Try a more specific request.")); return msgs
            }
            seenIds += turn.calls.map { it.id }
            msgs += Msg.Assistant(turn.text?.take(16_000), turn.calls)
            if (turn.calls.isEmpty()) {
                events(AgentEvent.Done(turn.text)); return ConversationWindow.trim(msgs)
            }
            turn.text?.takeIf { it.isNotBlank() }?.let { events(AgentEvent.Said(it.take(4_000))) }
            var declined = false
            for (call in turn.calls) {
                coroutineContext.ensureActive()
                val outcome = if (declined) ToolOutcome("Skipped: the owner declined this run's action.", true) else {
                    execute(call, events) { declined = true }
                }
                val bounded = outcome.copy(content = outcome.content.take(24_000))
                events(AgentEvent.Returned(call, bounded))
                msgs += Msg.ToolResult(call.id, call.name, bounded.content, bounded.isError)
            }
            if (declined) {
                events(AgentEvent.Failed("Action declined. The task has stopped.")); return ConversationWindow.trim(msgs)
            }
        }
        events(AgentEvent.Failed("Stopped after $maxSteps steps without finishing."))
        return ConversationWindow.trim(msgs)
    }

    private suspend fun execute(call: ToolCall, events: (AgentEvent) -> Unit, declined: () -> Unit): ToolOutcome {
        val tool = byName[call.name] ?: return ToolOutcome("There is no tool called ${call.name}.", true)
        ToolArguments.error(tool.spec, call.arguments)?.let { return ToolOutcome(it, true) }
        return try {
            val hit = withTimeout(toolTimeoutMs) { tool.check(call.arguments) }
            if (hit != null) {
                events(AgentEvent.Asking(call, hit))
                if (!confirm(call, hit)) {
                    declined()
                    return ToolOutcome("The owner said no: this would ${hit.risk.label} (${hit.reason}). Don't try it another way.", true)
                }
            }
            events(AgentEvent.Calling(call))
            withTimeout(toolTimeoutMs) { tool.run(call.arguments) }
        } catch (e: TimeoutCancellationException) {
            ToolOutcome("${call.name} timed out. Its effect is unknown; inspect the current state before acting again.", true)
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) { ToolOutcome("${call.name} failed: ${e.message ?: e.javaClass.simpleName}", true) }
    }
}

/** Validate the subset of JSON Schema used by Vela before checking or executing an action. */
internal object ToolArguments {
    fun error(spec: ToolSpec, args: JsonObject): String? {
        if (args.toString().length > 64_000) return "Arguments are too large."
        if ("_raw" in args) return "Arguments must be a valid JSON object."
        val props = spec.parameters["properties"] as? JsonObject ?: return null
        val required = (spec.parameters["required"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }
        if (required.any { it !in args }) return "Missing required argument: ${required.first { it !in args }}"
        if (args.keys.any { it !in props }) return "Unknown argument: ${args.keys.first { it !in props }}"
        for ((key, value) in args) {
            val schema = props[key] as? JsonObject ?: continue
            val p = value as? JsonPrimitive
            val valid = when ((schema["type"] as? JsonPrimitive)?.content) {
                "string" -> p?.isString == true && p.content.length <= 32_000 && (key !in required || p.content.isNotBlank())
                "integer" -> p?.isString == false && p.intOrNull != null
                "boolean" -> p?.isString == false && p.booleanOrNull != null
                "object" -> value is JsonObject
                "array" -> value is JsonArray
                else -> false
            }
            if (!valid) return "Invalid $key: expected ${schema["type"]}."
            val choices = schema["enum"] as? JsonArray
            if (choices != null && value !in choices) return "Invalid $key. Choose one of $choices."
        }
        return null
    }
}

/** Drop only whole user turns. Never split an assistant call from its results. */
object ConversationWindow {
    fun trim(history: List<Msg>, maxChars: Int = 100_000, maxTurns: Int = 8): List<Msg> {
        if (history.isEmpty()) return emptyList()
        val starts = history.indices.filter { history[it] is Msg.User }
        if (starts.isEmpty()) return emptyList()
        var start = starts[maxOf(0, starts.size - maxTurns)]
        while (history.drop(start).sumOf { it.toString().length.toLong() } > maxChars && start != starts.last()) {
            start = starts.first { it > start }
        }
        val retained = history.drop(start)
        if (retained.sumOf { it.toString().length.toLong() } <= maxChars) return retained
        // One long task can exceed the budget by itself. Evict complete assistant/tool exchanges, keeping the goal.
        val rounds = retained.indices.filter { retained[it] is Msg.Assistant }
        for (round in rounds) {
            val candidate = listOf(retained.first(), Msg.Assistant("Earlier steps omitted to fit context. Read the current device state before acting.")) + retained.drop(round)
            if (candidate.sumOf { it.toString().length.toLong() } <= maxChars) return candidate
        }
        // An oversized latest result still preserves its call/result pairing while limiting observations.
        return (listOf(retained.first()) + retained.drop(rounds.lastOrNull() ?: retained.size)).map { msg -> when (msg) {
            is Msg.ToolResult -> msg.copy(content = msg.content.take(1000))
            is Msg.Assistant -> msg.copy(text = msg.text?.take(1000))
            else -> msg
        } }
    }
}
