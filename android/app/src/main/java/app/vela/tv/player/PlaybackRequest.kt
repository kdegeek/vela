package app.vela.tv.player

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

enum class PlaybackPhase { PREPARING, READY, PLAYING, FAILED, CANCELLED, STOPPED, TIMED_OUT }

data class PlaybackOutcome(val requestId: String, val itemId: String, val phase: PlaybackPhase, val message: String? = null)

/** A result belongs to this exact launch, never to whichever item happens to be playing later. */
class PlaybackRequest internal constructor(val itemId: String, val id: String = UUID.randomUUID().toString()) {
    private val mutableState = MutableStateFlow(PlaybackOutcome(id, itemId, PlaybackPhase.PREPARING))
    val state: StateFlow<PlaybackOutcome> = mutableState.asStateFlow()
    private val started = CompletableDeferred<PlaybackOutcome>()
    internal val cancelled: Boolean get() = state.value.phase in setOf(PlaybackPhase.CANCELLED, PlaybackPhase.TIMED_OUT)

    /** Success means observed isPlaying, not activity launch, negotiation or READY. Timeout cancels late playback. */
    suspend fun awaitStarted(timeoutMillis: Long = 20_000): PlaybackOutcome {
        require(timeoutMillis > 0)
        try {
            return withTimeoutOrNull(timeoutMillis) { started.await() } ?: run {
                publish(PlaybackPhase.TIMED_OUT, "Playback did not start before the deadline.")
                state.value
            }
        } catch (e: CancellationException) { cancel(); throw e }
    }

    fun cancel() { publish(PlaybackPhase.CANCELLED, "Playback request cancelled.") }

    @Synchronized internal fun publish(phase: PlaybackPhase, message: String? = null) {
        if (cancelled || mutableState.value.phase == PlaybackPhase.FAILED) return
        val outcome = PlaybackOutcome(id, itemId, phase, message)
        mutableState.value = outcome
        if (phase in setOf(PlaybackPhase.PLAYING, PlaybackPhase.FAILED, PlaybackPhase.CANCELLED, PlaybackPhase.STOPPED, PlaybackPhase.TIMED_OUT)) started.complete(outcome)
    }
}

/** Small process-local registry bridges an Intent with its awaiting tool; no credentials or persistent history. */
object PlaybackRequests {
    private val requests = LinkedHashMap<String, PlaybackRequest>()
    @Synchronized fun create(itemId: String): PlaybackRequest {
        require(itemId.isNotBlank())
        while (requests.size >= 64) {
            val expired = requests.entries.firstOrNull { it.value.state.value.phase !in setOf(PlaybackPhase.PREPARING, PlaybackPhase.READY) }
            check(expired != null) { "Too many pending playback requests." }
            requests.remove(expired.key)
        }
        return PlaybackRequest(itemId).also { requests[it.id] = it }
    }
    @Synchronized internal fun find(id: String?, itemId: String): PlaybackRequest? = requests[id]?.takeIf { it.itemId == itemId }
}
