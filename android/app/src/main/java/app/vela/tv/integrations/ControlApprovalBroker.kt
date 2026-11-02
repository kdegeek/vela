package app.vela.tv.integrations

import app.vela.tv.guard.GuardHit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

data class ControlApprovalPrompt(val id: String, val controller: String, val question: String, val since: Long)

/** Model-independent approval state for Runner's TV/paired-phone presentation adapter. Never expose answer over MCP. */
class ControlApprovalBroker(
    private val timeoutMs: Long = 60_000,
    private val uptimeMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val lock = Any()
    private val mutablePending = MutableStateFlow<ControlApprovalPrompt?>(null)
    val pending: StateFlow<ControlApprovalPrompt?> = mutablePending.asStateFlow()
    private var answer: CompletableDeferred<Boolean>? = null

    init { require(timeoutMs > 0) }

    suspend fun confirm(controller: String, hit: GuardHit): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        val prompt = ControlApprovalPrompt(UUID.randomUUID().toString(), controller,
            "Vela wants to ${hit.risk.label}: ${hit.reason}", uptimeMs())
        synchronized(lock) {
            if (answer != null) throw ControlBusyException()
            answer = deferred
            mutablePending.value = prompt
        }
        return try { withTimeoutOrNull(timeoutMs) { deferred.await() } ?: false } finally {
            synchronized(lock) {
                if (mutablePending.value?.id == prompt.id) {
                    mutablePending.value = null
                    answer = null
                }
                deferred.cancel()
            }
        }
    }

    /** The trusted TV/phone adapter must pass the ID it displayed; old answers cannot approve a newer action. */
    fun answer(allow: Boolean, promptId: String): Boolean = synchronized(lock) {
        if (mutablePending.value?.id != promptId) false else answer?.complete(allow) ?: false
    }
}
