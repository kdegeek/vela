package app.vela.tv.agent

import app.vela.tv.guard.GuardHit
import app.vela.tv.integrations.ControlArbiter
import app.vela.tv.integrations.ControlApprovalBroker
import app.vela.tv.integrations.ControlBusyException
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

/** Main-dispatcher confined. All submission entry points share the same reservation and drain chain. */
class AgentSession(
    private val scope: CoroutineScope,
    private val agentFactory: (suspend (ToolCall, GuardHit) -> Boolean) -> Agent?,
    private val now: () -> Long = System::currentTimeMillis,
    uptime: () -> Long = { System.nanoTime() / 1_000_000 },
    val control: ControlArbiter = ControlArbiter(),
    val approvals: ControlApprovalBroker = ControlApprovalBroker(uptimeMs = uptime),
    private val contextKey: () -> Any? = { null },
) {
    private val _lines = MutableStateFlow<List<Line>>(emptyList())
    val lines: StateFlow<List<Line>> = _lines
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running
    val pending = approvals.pending
    private val _tasks = MutableStateFlow<List<TaskRecord>>(emptyList())
    val tasks: StateFlow<List<TaskRecord>> = _tasks
    private var job: Job? = null
    private var activeId: String? = null
    private enum class ReservationKind { TASK, CONTROLLER }
    private var reservationKind: ReservationKind? = null
    private val jobs = mutableMapOf<String, Job>()
    private val results = mutableMapOf<String, CompletableDeferred<TaskRecord>>()
    private var generation = 0L
    private var history = emptyList<Msg>()
    private var historyKey: Any? = null
    private var lastAt = 0L

    /** Replaces internal tasks, waits for their cleanup, and returns Busy for external/direct controllers. */
    fun ask(text: String): String = start(text, replace = true)!!

    /** Atomic non-replacing reservation. Null means Busy; no owner task or external controller was canceled. */
    fun submit(text: String): String? = start(text, replace = false)

    @OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
    private fun start(text: String, replace: Boolean): String? {
        val task = text.trim()
        require(task.isNotBlank() && task.length <= 8_000) { "Ask in 1–8000 characters." }
        if (!replace && (_running.value || control.owner.value != null)) return null
        if (reservationKind == ReservationKind.CONTROLLER || (control.owner.value != null && !_running.value)) throw ControlBusyException()
        val oldJob = job
        oldJob?.cancel()
        val owner = ++generation
        val id = UUID.randomUUID().toString()
        val at = now()
        _tasks.value = (_tasks.value.map {
            if (it.state == "running") it.copy(state = "canceled", finishedAt = at, detail = "Replaced by a new request.") else it
        } + TaskRecord(id, task, "running", at)).takeLast(30)
        reservationKind = ReservationKind.TASK
        _running.value = true
        activeId = id
        add("you", task)
        val result = CompletableDeferred<TaskRecord>()
        results[id] = result
        results.keys.filter { it !in _tasks.value.map(TaskRecord::id) && results[it]?.isCompleted == true }.forEach { results.remove(it) }
        // ATOMIC guarantees a canceled-before-start replacement still drains its predecessor in finally-safe code.
        job = scope.launch(start = CoroutineStart.ATOMIC) {
            var state = "complete"
            var detail: String? = null
            try {
                withContext(NonCancellable) { oldJob?.join() }
                currentCoroutineContext().ensureActive()
                if (owner != generation) return@launch
                control.withControl("task:$id") {
                    val key = contextKey()
                    if (key != historyKey || now() - lastAt > 15 * 60 * 1000) history = emptyList()
                    historyKey = key
                    val agent = agentFactory { call, hit -> confirm(call, hit, "task:$id") }
                    if (agent == null) {
                        state = "failed"
                        detail = "Set up an AI provider from Settings → Connect phone."
                        add("error", detail.orEmpty())
                    } else {
                        val next = withTimeout(15 * 60 * 1000L) {
                            agent.run(task, if (replace) history else emptyList()) { event ->
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
                        if (owner == generation && key == contextKey()) history = if (replace) next else emptyList()
                    }
                }
            } catch (e: TimeoutCancellationException) {
                state = "failed"; detail = "Task reached its 15 minute time limit."
                if (owner == generation) add("error", detail.orEmpty())
            } catch (e: CancellationException) {
                state = "canceled"; detail = "Stopped."
                throw e
            } catch (e: Exception) {
                state = "failed"; detail = "Could not run the task. Check connections and profile permissions."
                if (owner == generation) add("error", detail.orEmpty())
            } finally {
                val terminal = TaskRecord(id, task, state, at, now(), detail?.take(1000))
                result.complete(terminal)
                jobs.remove(id)
                if (owner == generation) {
                    lastAt = now()
                    reservationKind = null
                    activeId = null
                    _running.value = false
                    _tasks.value = _tasks.value.map { if (it.id == id) terminal else it }
                }
            }
        }.also { if (!it.isCompleted) jobs[id] = it }
        return id
    }

    /** Await exactly this reservation; cancellation of a scheduler cannot cancel a newer owner task. */
    suspend fun await(taskId: String): TaskRecord = (results[taskId] ?: error("Unknown task ID.")).await()

    suspend fun cancelAndJoin(taskId: String): Boolean {
        val target = jobs[taskId] ?: return false
        if (activeId == taskId) stop() else target.cancel()
        withContext(NonCancellable) { target.join() }
        return true
    }

    fun stop() {
        job?.cancel()
        ++generation
        val draining = job
        _tasks.value = _tasks.value.map { if (it.state == "running") it.copy(state = "canceled", finishedAt = now(), detail = "Stopped.") else it }
        // Busy stays true until cleanup has finished. A rapid replacement still chains through draining.
        val owner = generation
        scope.launch {
            draining?.join()
            if (generation == owner) { reservationKind = null; activeId = null; _running.value = false }
        }
        add("error", "Stopped.")
    }

    suspend fun stopAndJoin() {
        val draining = job
        stop()
        val owner = generation
        withContext(NonCancellable) { draining?.join() }
        if (owner == generation) { reservationKind = null; activeId = null; _running.value = false }
    }

    fun clear() { stop(); history = emptyList(); historyKey = null; _lines.value = emptyList(); _tasks.value = emptyList() }

    fun answer(allow: Boolean, promptId: String? = null): Boolean =
        (promptId ?: pending.value?.id)?.let { approvals.answer(allow, it) } ?: false

    suspend fun confirm(call: ToolCall, hit: GuardHit, controller: String = control.owner.value ?: "owner"): Boolean {
        currentCoroutineContext().ensureActive()
        return approvals.confirm(controller, hit).also { add("step", if (it) "Allowed." else "Not allowed.") }
    }

    /** Non-replacing external/UI execution; already-owned nested tools must call ToolExecutor directly. */
    suspend fun <T> withController(controller: String, block: suspend () -> T): T = coroutineScope {
        if (_running.value || control.owner.value != null) throw ControlBusyException()
        val owner = ++generation
        reservationKind = ReservationKind.CONTROLLER
        _running.value = true
        val current = currentCoroutineContext()[Job]!!
        job = current
        try { control.withControl(controller) { coroutineScope { block() } } } finally {
            if (owner == generation) { reservationKind = null; activeId = null; _running.value = false }
        }
    }

    private fun add(kind: String, text: String) { _lines.value = (_lines.value + Line(kind, text.take(4000))).takeLast(100) }
}
