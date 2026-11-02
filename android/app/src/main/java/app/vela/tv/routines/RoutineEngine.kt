package app.vela.tv.routines

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.UUID

data class RoutineTaskRequest(val runId: String, val routineId: String, val name: String, val task: String, val ownerProfileId: String? = null)

sealed interface RoutineTaskResult {
    /** No task was started. In particular, an active owner task was not canceled or replaced. */
    data object Busy : RoutineTaskResult
    data class Completed(val detail: String? = null) : RoutineTaskResult
    data class Failed(val detail: String) : RoutineTaskResult
}

/**
 * Must atomically arbitrate with EVERY owner task entry point, return Busy without effects if occupied,
 * and await the normal guarded agent task through completion. Cancellation must cancel only this run
 * (including its approval), await its cleanup, and never stop a newer owner task. Do not wrap Runner.ask.
 */
fun interface RoutineTaskExecutor {
    suspend fun tryExecute(request: RoutineTaskRequest): RoutineTaskResult
}

/**
 * Single-process, single-instance owner of [storage]. All public operations are concurrency-safe.
 * Use IO for disk access. No timer is hidden here: call onWake from the durable Android job, startup,
 * owner-task completion, and after mutations. State and cursors are committed before dispatch.
 */
class RoutineEngine private constructor(
    private val storage: RoutineStorage,
    private val executor: RoutineTaskExecutor,
    private val now: () -> Long,
    private val newId: () -> String,
    initial: RoutineSnapshot,
) {
    private val mutex = Mutex()
    private val pump = Mutex()
    private val active = mutableMapOf<String, Deferred<RoutineTaskResult>>()
    private val mutableState = MutableStateFlow(initial)
    val state: StateFlow<RoutineSnapshot> = mutableState.asStateFlow()

    companion object {
        const val TASK_TIMEOUT_MILLIS = 15 * 60 * 1000L
        const val ON_TIME_GRACE_MILLIS = 60_000L

        /** Open once per app process; recovery is not safe while another engine owns this file. */
        fun open(
            storage: RoutineStorage, executor: RoutineTaskExecutor,
            now: () -> Long = System::currentTimeMillis,
            newId: () -> String = { UUID.randomUUID().toString() },
        ): RoutineEngine {
            val loaded = storage.read().also(RoutineDocuments::validate)
            val at = now()
            val recovered = loaded.copy(history = loaded.history.map {
                if (it.state == RoutineRunState.RUNNING || it.state == RoutineRunState.CANCELING) it.copy(
                    state = RoutineRunState.INTERRUPTED, finishedAt = at,
                    detail = "Process stopped during execution. Effects may have occurred; inspect before restarting.",
                ) else it
            })
            val engine = RoutineEngine(storage, executor, now, newId, loaded)
            if (recovered != loaded) engine.commit(recovered)
            return engine
        }
    }

    suspend fun create(spec: RoutineSpec): Routine = mutex.withLock {
        val frozen = freeze(spec).validated()
        require(state.value.routines.size < RoutineDocuments.MAX_ROUTINES) { "At most 100 routines." }
        val at = now()
        val routine = Routine(newId(), 1, frozen, at, at, initialTime(frozen.schedule, at))
        commit(state.value.copy(routines = state.value.routines + routine))
        routine
    }

    /** Any edit invalidates queued/running snapshots. Schedule edits re-arm; name/task edits do not. */
    suspend fun update(id: String, expectedRevision: Long, spec: RoutineSpec): Routine = mutex.withLock {
        val old = requireRoutine(id, expectedRevision)
        val frozen = freeze(spec).validated()
        val at = now()
        val updated = old.copy(
            revision = old.revision + 1, spec = frozen, updatedAt = at,
            nextRunAt = if (old.spec.schedule != frozen.schedule) initialTime(frozen.schedule, at) else old.nextRunAt,
        )
        val next = cancelRuns(state.value, id, at, "Routine edited.")
        commit(next.copy(routines = next.routines.map { if (it.id == id) updated else it }))
        signalCancellation(id)
        updated
    }

    suspend fun setEnabled(id: String, expectedRevision: Long, enabled: Boolean): Routine = mutex.withLock {
        val old = requireRoutine(id, expectedRevision)
        if (old.spec.enabled == enabled) return@withLock old
        val at = now()
        val updated = old.copy(revision = old.revision + 1, spec = old.spec.copy(enabled = enabled), updatedAt = at)
        val next = if (enabled) state.value else cancelRuns(state.value, id, at, "Routine disabled.")
        commit(next.copy(routines = next.routines.map { if (it.id == id) updated else it }))
        if (!enabled) signalCancellation(id)
        updated
    }

    /** History survives deletion and retains the name/task that was actually submitted. */
    suspend fun delete(id: String, expectedRevision: Long) = mutex.withLock {
        requireRoutine(id, expectedRevision)
        val next = cancelRuns(state.value, id, now(), "Routine deleted.")
        commit(next.copy(routines = next.routines.filterNot { it.id == id }))
        signalCancellation(id)
    }

    /** Explicit manual execution is allowed for disabled and exhausted routines. */
    suspend fun runNow(id: String, requestKey: String): RoutineRun = mutex.withLock {
        validateKey(requestKey)
        state.value.history.firstOrNull { it.routineId == id && it.requestKey == requestKey }?.let {
            require(it.source == RoutineRunSource.MANUAL) { "Request key already belongs to another operation." }
            return@withLock it
        }
        val routine = requireRoutine(id)
        require(state.value.history.none { it.routineId == id && !it.state.terminal }) { "Routine already has a pending run. Cancel or restart it." }
        val run = newRun(routine, now(), RoutineRunSource.MANUAL, requestKey)
        commit(state.value.copy(history = state.value.history + run))
        run
    }

    /** Cancel the old attempt, then enqueue a fresh guarded task using the current routine definition. */
    suspend fun restartRun(runId: String, requestKey: String): RoutineRun = mutex.withLock {
        validateKey(requestKey)
        val old = state.value.history.firstOrNull { it.id == runId } ?: error("Run no longer exists.")
        state.value.history.firstOrNull { it.routineId == old.routineId && it.requestKey == requestKey }?.let {
            require(it.restartedFrom == runId) { "Request key already belongs to another operation." }
            return@withLock it
        }
        val routine = requireRoutine(old.routineId)
        val at = now()
        val next = cancelRuns(state.value, routine.id, at, "Restart requested.")
        val run = newRun(routine, at, RoutineRunSource.RESTART, requestKey).copy(restartedFrom = old.id)
        commit(next.copy(history = next.history + run))
        signalCancellation(routine.id)
        run
    }

    suspend fun cancelRun(runId: String): RoutineRun = mutex.withLock {
        val old = state.value.history.firstOrNull { it.id == runId } ?: error("Run no longer exists.")
        if (old.state.terminal || old.state == RoutineRunState.CANCELING) return@withLock old
        val canceled = canceled(old, now(), "Stopped by the owner.")
        commit(state.value.copy(history = state.value.history.map { if (it.id == runId) canceled else it }))
        active[runId]?.cancel()
        canceled
    }

    /** Consumes due slots atomically, coalesces backlog, then drains without replacing owner work. */
    suspend fun onWake() {
        mutex.withLock {
            // A previous completion write may have failed. Recover its durable claim without replay.
            val at = now()
            val recovered = state.value.copy(history = state.value.history.map {
                if ((it.state == RoutineRunState.RUNNING || it.state == RoutineRunState.CANCELING) && it.id !in active)
                    it.copy(state = RoutineRunState.INTERRUPTED, finishedAt = at,
                        detail = "Execution result could not be saved. Inspect effects before restarting.")
                else it
            })
            if (recovered != state.value) commit(recovered)
            collectDue(at)
        }
        if (!pump.tryLock()) return
        try {
            supervisorScope {
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val claimed = mutex.withLock {
                        expireQueued(now())
                        val run = state.value.history.firstOrNull { it.state == RoutineRunState.QUEUED }
                            ?: return@withLock null
                        val job = async(start = CoroutineStart.LAZY) {
                            withTimeout(TASK_TIMEOUT_MILLIS) {
                                executor.tryExecute(RoutineTaskRequest(run.id, run.routineId, run.name, run.task, run.ownerProfileId))
                            }
                        }
                        // No callback, including Busy arbitration, happens until this claim is durable.
                        try {
                            commit(state.value.copy(history = state.value.history.map {
                                if (it.id == run.id) it.copy(state = RoutineRunState.RUNNING, startedAt = now()) else it
                            }))
                        } catch (e: Exception) {
                            job.cancel()
                            throw e
                        }
                        active[run.id] = job
                        job.start()
                        run to job
                    } ?: break
                    val (run, job) = claimed
                    var outcome: RoutineTaskResult? = null
                    var interrupted = false
                    var timedOut = false
                    try {
                        outcome = job.await()
                    } catch (_: TimeoutCancellationException) {
                        timedOut = true
                    } catch (_: CancellationException) {
                        interrupted = true
                    } catch (_: Exception) {
                        outcome = RoutineTaskResult.Failed("Agent task failed. Inspect activity before retrying.")
                    } finally {
                        // A canceled parent must not leave cleanup running concurrently with the next task.
                        withContext(NonCancellable) {
                            job.cancel(); job.join()
                            mutex.withLock {
                                active.remove(run.id)
                                val current = state.value.history.first { it.id == run.id }
                                val at = now()
                                val updated = when {
                                    current.state == RoutineRunState.CANCELING -> current.copy(
                                        state = RoutineRunState.CANCELED, finishedAt = at,
                                    )
                                    timedOut -> current.copy(state = RoutineRunState.FAILED, finishedAt = at,
                                        detail = "Task exceeded 15 minutes. Effects may have occurred; inspect before retrying.")
                                    interrupted -> current.copy(state = RoutineRunState.INTERRUPTED, finishedAt = at,
                                        detail = "Execution interrupted. Effects may have occurred; inspect before restarting.")
                                    outcome is RoutineTaskResult.Busy -> current.copy(
                                        state = RoutineRunState.QUEUED, startedAt = null,
                                    )
                                    outcome is RoutineTaskResult.Completed -> current.copy(
                                        state = RoutineRunState.SUCCEEDED, finishedAt = at, detail = outcome.detail?.take(1_000),
                                    )
                                    else -> current.copy(state = RoutineRunState.FAILED, finishedAt = at,
                                        detail = (outcome as? RoutineTaskResult.Failed)?.detail?.take(1_000))
                                }
                                commit(state.value.copy(history = state.value.history.map { if (it.id == run.id) updated else it }))
                            }
                        }
                    }
                    currentCoroutineContext().ensureActive()
                    if (outcome is RoutineTaskResult.Busy) break
                }
            }
        } finally { pump.unlock() }
    }

    private fun collectDue(at: Long) {
        var next = state.value
        for (routine in next.routines) {
            val due = routine.nextRunAt ?: continue
            if (!routine.spec.enabled || due > at) continue
            val latest = maxOf(due, ScheduleTimes.latestAtOrBefore(routine.spec.schedule, at) ?: due)
            val lateLimit = if (routine.spec.missedRunPolicy == MissedRunPolicy.SKIP) ON_TIME_GRACE_MILLIS else routine.spec.maxLatenessMillis
            val reason = when {
                at - latest > lateLimit -> "Missed the allowed late-run window; older occurrences were coalesced."
                next.history.any { it.routineId == routine.id && !it.state.terminal } -> "Skipped overlapping occurrence; another run is pending."
                else -> null
            }
            val run = newRun(routine, latest, RoutineRunSource.SCHEDULE, null, at).let {
                if (reason == null) it else it.copy(state = RoutineRunState.MISSED, finishedAt = at, detail = reason)
            }
            next = next.copy(
                routines = next.routines.map {
                    if (it.id == routine.id) it.copy(nextRunAt = ScheduleTimes.nextAfter(it.spec.schedule, at)) else it
                },
                history = next.history + run,
            )
        }
        if (next != state.value) commit(next)
        expireQueued(at)
    }

    private fun expireQueued(at: Long) {
        val next = state.value.copy(history = state.value.history.map {
            if (it.state == RoutineRunState.QUEUED && at > it.expiresAt) it.copy(
                state = RoutineRunState.MISSED, finishedAt = at, detail = "Expired while waiting for an idle agent.",
            ) else it
        })
        if (next != state.value) commit(next)
    }

    private fun newRun(
        routine: Routine, scheduledAt: Long, source: RoutineRunSource, key: String?, at: Long = now(),
    ) = RoutineRun(
        id = newId(), routineId = routine.id, revision = routine.revision,
        name = routine.spec.name, task = routine.spec.task, source = source,
        scheduledAt = scheduledAt, queuedAt = at,
        expiresAt = if (source == RoutineRunSource.SCHEDULE) maxOf(at, scheduledAt + (
            if (routine.spec.missedRunPolicy == MissedRunPolicy.SKIP) ON_TIME_GRACE_MILLIS else routine.spec.maxLatenessMillis
        )) else at + routine.spec.maxLatenessMillis,
        requestKey = key, ownerProfileId = routine.spec.ownerProfileId,
    )

    private fun cancelRuns(snapshot: RoutineSnapshot, id: String, at: Long, detail: String) = snapshot.copy(
        history = snapshot.history.map { if (it.routineId == id) canceled(it, at, detail) else it },
    )

    private fun canceled(run: RoutineRun, at: Long, detail: String): RoutineRun = when (run.state) {
        RoutineRunState.QUEUED -> run.copy(state = RoutineRunState.CANCELED, finishedAt = at, detail = detail)
        RoutineRunState.RUNNING -> run.copy(state = RoutineRunState.CANCELING, detail = "$detail Effects may have occurred.")
        else -> run
    }

    private fun signalCancellation(id: String) {
        state.value.history.filter { it.routineId == id && it.state == RoutineRunState.CANCELING }
            .forEach { active[it.id]?.cancel() }
    }

    private fun requireRoutine(id: String, revision: Long? = null): Routine {
        val routine = state.value.routines.firstOrNull { it.id == id } ?: error("Routine no longer exists.")
        require(revision == null || revision == routine.revision) { "Routine changed; reload before editing." }
        return routine
    }

    private fun validateKey(key: String) = require(key.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "Supply a request key of 1–128 letters, digits, - or _." }

    private fun initialTime(schedule: RoutineSchedule, at: Long): Long? =
        if (schedule is RoutineSchedule.Once) schedule.at else ScheduleTimes.nextAfter(schedule, at - 1)

    private fun freeze(spec: RoutineSpec) = spec.copy(schedule = when (val schedule = spec.schedule) {
        is RoutineSchedule.Weekly -> schedule.copy(days = schedule.days.toSet())
        else -> schedule
    })

    /** Commit-before-publish: an I/O failure cannot publish a change or start an unrecorded task. */
    private fun commit(snapshot: RoutineSnapshot) {
        val terminalToKeep = snapshot.history.filter { it.state.terminal }.sortedBy { it.finishedAt }
            .takeLast(RoutineDocuments.MAX_TERMINAL_HISTORY).map { it.id }.toSet()
        val bounded = snapshot.copy(history = snapshot.history.filter { !it.state.terminal || it.id in terminalToKeep })
        RoutineDocuments.validate(bounded)
        storage.write(bounded)
        // AtomicFile may log a failed rename without throwing. Never dispatch a claim
        // or publish a terminal result until the durable replacement is observed.
        if (storage.read() != bounded) throw java.io.IOException("Routine commit could not be verified.")
        mutableState.value = bounded
    }
}
