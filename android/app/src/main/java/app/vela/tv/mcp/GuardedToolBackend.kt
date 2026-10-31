package app.vela.tv.mcp

import app.vela.tv.agent.Tool
import app.vela.tv.agent.ToolCall
import app.vela.tv.agent.ToolExecutor
import app.vela.tv.agent.ToolArguments
import app.vela.tv.agent.ToolOutcome
import app.vela.tv.agent.ToolSpec
import app.vela.tv.guard.GuardHit
import app.vela.tv.integrations.ControlArbiter
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject

/** Identity is established by local authorization, never by clientInfo or model-supplied arguments. */
data class ExternalToolCall(val controller: String, val requestId: String, val name: String, val arguments: JsonObject)

/** Supported seam for TV/direct-integration tools. Implementations must enforce validation, guards and arbitration. */
interface ExternalToolBackend {
    val tools: List<ToolSpec>
    fun argumentError(name: String, arguments: JsonObject): String?
    suspend fun executeGuarded(call: ExternalToolCall): ToolOutcome
}

/** Reuses real Vela tools without constructing a Provider, Agent, or internal-model conversation. */
class GuardedToolBackend(
    tools: List<Tool>,
    private val control: ControlArbiter,
    private val approve: suspend (ExternalToolCall, GuardHit) -> Boolean,
    private val checkTimeoutMs: Long = 30_000,
    private val executionTimeoutMs: Long = 30_000,
    private val approvalTimeoutMs: Long = 60_000,
    private val admission: suspend (String, suspend () -> ToolOutcome) -> ToolOutcome = { owner, action -> control.withControl(owner, action) },
) : ExternalToolBackend {
    private val byName = tools.associateBy { it.spec.name }
    override val tools = tools.map { it.spec }

    init {
        require(byName.size == tools.size && tools.size <= 128)
        require(listOf(checkTimeoutMs, executionTimeoutMs, approvalTimeoutMs).all { it > 0 })
        require(this.tools.all { it.name.matches(Regex("[A-Za-z0-9_.-]{1,128}")) })
    }

    override fun argumentError(name: String, arguments: JsonObject): String? {
        val tool = byName[name] ?: return "Unknown tool."
        return ToolArguments.error(tool.spec, arguments)
    }

    override suspend fun executeGuarded(call: ExternalToolCall): ToolOutcome = admission(call.controller) {
        // The executor retains one prepared target and revalidates that target after approval.
        // Equal risk/reason text is never accepted as proof of target identity.
        withTimeout(checkTimeoutMs + executionTimeoutMs + approvalTimeoutMs) {
            ToolExecutor(byName.values.toList(), minOf(checkTimeoutMs, executionTimeoutMs)).execute(
                ToolCall(call.requestId, call.name, call.arguments),
                confirm = { _, hit -> withTimeout(approvalTimeoutMs) { approve(call, hit) } },
            )
        }
    }
}
