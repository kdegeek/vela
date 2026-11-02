package app.vela.tv.agent

import app.vela.tv.guard.GuardHit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout

/** One captured target/configuration. The callback must revalidate after any further suspension before dispatch. */
class PreparedToolAction(
    val hit: GuardHit?,
    private val valid: suspend () -> Boolean = { true },
    private val action: suspend () -> ToolOutcome,
) {
    private val used = AtomicBoolean(false)
    suspend fun execute(): ToolOutcome {
        check(used.compareAndSet(false, true)) { "Prepared actions may execute only once." }
        currentCoroutineContext().ensureActive()
        if (!valid()) throw ActionInvalidatedException()
        currentCoroutineContext().ensureActive()
        return action()
    }
}

class ActionInvalidatedException : Exception("The target or configuration changed. Read the current state and ask again.")

/** Model-independent validation/approval/execution. Caller already holds the ONE shared controller lease. */
class ToolExecutor(tools: List<Tool>, private val timeoutMs: Long = 30_000) {
    private val byName = tools.associateBy { it.spec.name }
    init { require(timeoutMs > 0 && byName.size == tools.size) }

    suspend fun execute(
        call: ToolCall,
        confirm: suspend (ToolCall, GuardHit) -> Boolean,
        events: (AgentEvent) -> Unit = {},
        stopped: () -> Unit = {},
    ): ToolOutcome {
        val tool = byName[call.name] ?: return ToolOutcome("There is no tool called ${call.name}.", true)
        ToolArguments.error(tool.spec, call.arguments)?.let { return ToolOutcome(it, true) }
        return try {
            val prepared = withTimeout(timeoutMs) { tool.prepare(call.arguments) }
            prepared.hit?.let { hit ->
                events(AgentEvent.Asking(call, hit))
                if (!confirm(call, hit)) {
                    stopped()
                    return ToolOutcome("The owner said no: this would ${hit.risk.label} (${hit.reason}). Don't try it another way.", true)
                }
            }
            currentCoroutineContext().ensureActive()
            events(AgentEvent.Calling(call))
            withTimeout(timeoutMs) { prepared.execute() }.let { it.copy(content = it.content.take(24_000)) }
        } catch (e: ActionInvalidatedException) {
            stopped()
            ToolOutcome(e.message!!, true)
        } catch (e: TimeoutCancellationException) {
            ToolOutcome("${call.name} timed out. Its effect is unknown; inspect the current state before acting again.", true)
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            // Integration exceptions can include URLs, credentials or private settings.
            ToolOutcome("${call.name} failed. Check the current state, connection and permissions.", true)
        }
    }
}
