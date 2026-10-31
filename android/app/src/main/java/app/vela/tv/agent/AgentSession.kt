package app.vela.tv.agent

import app.vela.tv.guard.GuardHit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable data class Line(val kind: String, val text: String)
@Serializable data class TaskRecord(
    val id: String, val task: String, val state: String, val startedAt: Long,
    val finishedAt: Long? = null, val detail: String? = null,
)
data class PendingApproval(val id: String, val question: String, val since: Long)

/** Main-dispatcher confined. Generation ownership prevents an old run's finally block clearing a newer run. */
class AgentSession(
    private val scope: CoroutineScope,
    private val agentFactory: (suspend (ToolCall, GuardHit) -> Boolean) -> Agent?,
    private val now: () -> Long = System::currentTimeMillis,
    private val uptime: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val _lines = MutableStateFlow<List<Line>>(emptyList())
    val lines: StateFlow<List<Line>> = _lines
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running
    private val _pending = MutableStateFlow<PendingApproval?>(null)
    val pending: StateFlow<PendingApproval?> = _pending
    private val _tasks = MutableStateFlow<List<TaskRecord>>(emptyList())
    val tasks: StateFlow<List<TaskRecord>> = _tasks
    private var job: Job? = null
    private var answer: CompletableDeferred<Boolean>? = null
    private var generation = 0L
    private var history = emptyList<Msg>()
    private var lastAt = 0L

    fun ask(text: String): String {
        val task = text.trim()
        require(task.isNotBlank() && task.length <= 8_000) { "Ask in 1–8000 characters." }
        val oldJob = job
        oldJob?.cancel()
        val owner = ++generation
        clearApproval()
        val id = UUID.randomUUID().toString()
        val at = now()
        _tasks.value = (_tasks.value.map {
            if (it.state == "running") it.copy(state = "canceled", finishedAt = at, detail = "Replaced by a new request.") else it
        } + TaskRecord(id, task, "running", at)).takeLast(30)
        _running.value = true
        add("you", task)
        job = scope.launch {
            oldJob?.join()
            if (owner != generation) return@launch
            if (now() - lastAt > 15 * 60 * 1000) history = emptyList()
            var state = "complete"
            var detail: String? = null
            try {
                val agent = agentFactory(::confirm)
                if (agent == null) {
                    state = "failed"
                    detail = "Set up an AI provider from Settings → Connect phone."
                    add("error", detail.orEmpty())
                } else {
                    val result = withTimeout(15 * 60 * 1000L) {
                        agent.run(task, history) { event ->
                            if (owner == generation) when (event) {
                                is AgentEvent.Said -> add("said", event.text)
                                is AgentEvent.Calling -> add("step", event.call.name.replace('_', ' '))
                                is AgentEvent.Returned -> if (event.outcome.isError) add("step", event.outcome.content.take(500))
                                is AgentEvent.Asking -> Unit
                                is AgentEvent.Done -> { detail = event.text; add("said", event.text ?: "Done.") }
                                is AgentEvent.Failed -> { state = "failed"; detail = event.message; add("error", event.message) }
                            }
                        }
                    }
                    if (owner == generation) history = result
                }
            } catch (e: TimeoutCancellationException) {
                state = "failed"; detail = "Task reached its 15 minute time limit."
                if (owner == generation) add("error", detail.orEmpty())
            } catch (e: CancellationException) {
                state = "canceled"; detail = "Stopped."
                throw e
            } catch (e: Exception) {
                state = "failed"; detail = e.message ?: "Could not start the task."
                if (owner == generation) add("error", detail.orEmpty())
            } finally {
                if (owner == generation) {
                    lastAt = now()
                    _running.value = false
                    clearApproval()
                    _tasks.value = _tasks.value.map { if (it.id == id) it.copy(state = state, detail = detail?.take(1000), finishedAt = now()) else it }
                }
            }
        }
        return id
    }

    fun stop() {
        job?.cancel()
        ++generation
        clearApproval()
        _running.value = false
        _tasks.value = _tasks.value.map { if (it.state == "running") it.copy(state = "canceled", finishedAt = now(), detail = "Stopped.") else it }
        add("error", "Stopped.")
    }

    fun clear() { stop(); history = emptyList(); _lines.value = emptyList(); _tasks.value = emptyList() }

    /** Phone answers must name the prompt they displayed; stale responses cannot approve the next prompt. */
    fun answer(allow: Boolean, promptId: String? = null): Boolean {
        val pending = _pending.value ?: return false
        if (promptId != null && promptId != pending.id) return false
        return answer?.complete(allow) ?: false
    }

    private suspend fun confirm(call: ToolCall, hit: GuardHit): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        answer = deferred
        val prompt = PendingApproval(UUID.randomUUID().toString(), "Vela wants to ${hit.risk.label}: ${hit.reason}", uptime())
        _pending.value = prompt
        return try {
            (withTimeoutOrNull(60_000) { deferred.await() } ?: false).also { add("step", if (it) "Allowed." else "Not allowed.") }
        } finally {
            if (_pending.value?.id == prompt.id) clearApproval()
        }
    }

    private fun clearApproval() { answer?.cancel(); answer = null; _pending.value = null }
    private fun add(kind: String, text: String) { _lines.value = (_lines.value + Line(kind, text.take(4000))).takeLast(100) }
}
