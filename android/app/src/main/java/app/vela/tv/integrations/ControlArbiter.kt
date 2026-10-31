package app.vela.tv.integrations

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ControlBusyException : Exception("Another controller is using Vela. Stop it or wait before trying again.")

/** One application-wide instance must cover Runner, phone actions, and external tool calls. No queued stale actions. */
class ControlArbiter {
    private val transition = Any()
    private var lease: Any? = null
    private val mutableOwner = MutableStateFlow<String?>(null)
    val owner: StateFlow<String?> = mutableOwner.asStateFlow()

    suspend fun <T> withControl(owner: String, block: suspend () -> T): T {
        require(owner.isNotBlank() && owner.length <= 200)
        currentCoroutineContext().ensureActive()
        val acquired = Any()
        synchronized(transition) {
            if (lease != null) throw ControlBusyException()
            lease = acquired
            mutableOwner.value = owner
        }
        try {
            currentCoroutineContext().ensureActive()
            return block()
        } finally {
            // Retain exclusivity until the coroutine (including any cancellation cleanup) actually exits.
            synchronized(transition) {
                check(lease === acquired)
                // Make acquisition possible BEFORE notifying immediate idle collectors.
                // The same monitor prevents another thread's owner from being overwritten.
                lease = null
                mutableOwner.value = null
            }
        }
    }
}
