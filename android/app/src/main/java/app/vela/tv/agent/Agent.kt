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
    /** Capture immutable execution identity before asking; never store it in a shared Tool instance. */
    suspend fun prepare(args: JsonObject): PreparedToolAction {
        val hit = check(args)
        // Legacy immutable-argument tools still recheck classification. Mutable targets MUST override and bind identity.
        return PreparedToolAction(hit, { check(args) == hit }) { run(args) }
    }
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
    private val validateContext: () -> Unit = {},
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
            validateContext()
            val turn = try {
                withTimeout(120_000) { provider.complete(system, ConversationWindow.trim(msgs), tools.map { it.spec }) }
            } catch (e: TimeoutCancellationException) {
                events(AgentEvent.Failed("The model took too long. Check the connection and try again.")); return ConversationWindow.trim(msgs)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                events(AgentEvent.Failed(e.message ?: "The model request failed.")); return ConversationWindow.trim(msgs)
            }
            validateContext()
            if (turn.calls.size > 12 || turn.calls.any { it.id.isBlank() || it.name.isBlank() || it.id in seenIds } ||
                turn.calls.map { it.id }.distinct().size != turn.calls.size) {
                events(AgentEvent.Failed("The model returned an invalid batch of tool calls. No actions in that batch ran.")); return ConversationWindow.trim(msgs)
            }
            if (turn.calls.isEmpty() && turn.text.isNullOrBlank()) {
                events(AgentEvent.Failed("The model returned an empty answer. Try again or choose another model.")); return ConversationWindow.trim(msgs)
            }
            val batch = turn.calls.map { it.name to it.arguments }
            repeats = if (batch == previousBatch) repeats + 1 else 0
            previousBatch = batch
            if (repeats >= 4) {
                events(AgentEvent.Failed("Stopped because the agent kept repeating the same actions. Try a more specific request.")); return ConversationWindow.trim(msgs)
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
                validateContext()
                val outcome = if (declined) ToolOutcome("Skipped: this run's action was declined or invalidated.", true) else {
                    execute(call, events) { declined = true }
                }
                val bounded = outcome.copy(content = outcome.content.take(24_000))
                events(AgentEvent.Returned(call, bounded))
                msgs += Msg.ToolResult(call.id, call.name, bounded.content, bounded.isError)
            }
            if (declined) {
                events(AgentEvent.Failed("Action stopped. The task has ended.")); return ConversationWindow.trim(msgs)
            }
            val window = ConversationWindow.trim(msgs)
            msgs.clear()
            msgs.addAll(window)
        }
        events(AgentEvent.Failed("Stopped after $maxSteps steps without finishing."))
        return ConversationWindow.trim(msgs)
    }

    private suspend fun execute(call: ToolCall, events: (AgentEvent) -> Unit, declined: () -> Unit): ToolOutcome =
        ToolExecutor(tools, toolTimeoutMs).execute(call, confirm, events, declined)

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
        require(maxChars > 0 && maxTurns > 0)
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
        // Arguments are executable data: never truncate them. Omit the entire oversized exchange instead.
        val goal = retained.first() as Msg.User
        val marker = Msg.Assistant("Earlier steps omitted to fit context. Read the current device state before acting.")
        return if (goal.toString().length + marker.toString().length <= maxChars) listOf(goal, marker)
        else listOf(goal).also { require(goal.toString().length <= maxChars) { "Context budget cannot hold the owner's goal." } }
    }
}
