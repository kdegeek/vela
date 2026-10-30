package app.vela.tv.agent

import app.vela.tv.guard.GuardHit
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject

/** What a tool returns to the model. Errors go back to the model too, so it can try another way. */
data class ToolOutcome(val content: String, val isError: Boolean = false)

interface Tool {
    val spec: ToolSpec

    /** Says whether this call needs the owner's OK first (see [app.vela.tv.guard.Guard]). */
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

/**
 * The loop: send the conversation, run the tools the model asks for, send the results back, until the model answers
 * without calling a tool or [maxSteps] model calls have been made. Guarded calls wait for [confirm].
 */
class Agent(
    private val provider: Provider,
    private val tools: List<Tool>,
    private val system: String,
    private val confirm: suspend (ToolCall, GuardHit) -> Boolean,
    private val maxSteps: Int = 40,
) {
    private val byName = tools.associateBy { it.spec.name }

    /** Runs [task] after [history] and returns the conversation including this task, for the next follow-up. */
    suspend fun run(task: String, history: List<Msg> = emptyList(), events: (AgentEvent) -> Unit = {}): List<Msg> {
        val msgs = history.toMutableList()
        msgs += Msg.User(task)
        val specs = tools.map { it.spec }
        repeat(maxSteps) {
            val turn = try {
                provider.complete(system, msgs, specs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                events(AgentEvent.Failed(e.message ?: e.toString()))
                return msgs
            }
            msgs += Msg.Assistant(turn.text, turn.calls)
            if (turn.calls.isEmpty()) {
                events(AgentEvent.Done(turn.text))
                return msgs
            }
            turn.text?.takeIf { it.isNotBlank() }?.let { events(AgentEvent.Said(it)) }
            for (call in turn.calls) {
                val outcome = execute(call, events)
                events(AgentEvent.Returned(call, outcome))
                msgs += Msg.ToolResult(call.id, call.name, outcome.content, outcome.isError)
            }
        }
        events(AgentEvent.Failed("Stopped after $maxSteps steps without finishing."))
        return msgs
    }

    private suspend fun execute(call: ToolCall, events: (AgentEvent) -> Unit): ToolOutcome {
        val tool = byName[call.name] ?: return ToolOutcome("There is no tool called ${call.name}.", isError = true)
        return try {
            val hit = tool.check(call.arguments)
            if (hit != null) {
                events(AgentEvent.Asking(call, hit))
                if (!confirm(call, hit)) {
                    return ToolOutcome("The owner said no: this would ${hit.risk.label} (${hit.reason}). Don't try it another way; tell them.", isError = true)
                }
            }
            events(AgentEvent.Calling(call))
            tool.run(call.arguments)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolOutcome("${call.name} failed: ${e.message ?: e}", isError = true)
        }
    }
}
