package app.vela.tv.routines

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class RoutineEngineTest {
    private class Store : RoutineStorage {
        var text: String? = null
        var failWrites = false
        var silentlyDropWrites = false
        var writes = 0
        var failOnWrite: Int? = null
        override fun read() = text?.let(RoutineDocuments::decode) ?: RoutineSnapshot()
        override fun write(snapshot: RoutineSnapshot) {
            check(!failWrites && ++writes != failOnWrite) { "Disk full" }
            if (silentlyDropWrites) return
            text = RoutineDocuments.encode(snapshot)
        }
    }

    private class Fixture {
        val store = Store()
        var now = Instant.parse("2026-10-31T12:00:00Z").toEpochMilli()
        var serial = 0
        val requests = mutableListOf<RoutineTaskRequest>()
        var action: suspend (RoutineTaskRequest) -> RoutineTaskResult = { RoutineTaskResult.Completed("Done") }
        fun open() = RoutineEngine.open(store, { request -> requests.add(request); action(request) }, { now }, { "id-${++serial}" })
        var engine = open()
        fun spec(schedule: RoutineSchedule = RoutineSchedule.Once(now), enabled: Boolean = true) =
            RoutineSpec("Evening", "Find a comedy and ask before buying anything", schedule, enabled)
    }

    @Test fun `one shot cursor history and manual idempotency survive restart`() = runTest {
        val f = Fixture()
        val r = f.engine.create(f.spec())
        f.engine.onWake()
        assertEquals(RoutineRunState.SUCCEEDED, f.store.read().history.single().state)
        assertNull(f.engine.state.value.routines.single().nextRunAt)
        f.engine = f.open()
        repeat(3) { f.engine.onWake() }
        assertEquals(1, f.requests.size)
        val manual = f.engine.runNow(r.id, "phone-request-1")
        f.engine.onWake()
        f.engine = f.open()
        assertEquals(manual.id, f.engine.runNow(r.id, "phone-request-1").id)
        f.engine.onWake()
        assertEquals(2, f.requests.size)
    }

    @Test fun `silently dropped durable claim never invokes external effects`() = runTest {
        val f = Fixture()
        val routine = f.engine.create(f.spec(enabled = false))
        f.engine.runNow(routine.id, "manual")
        f.store.silentlyDropWrites = true
        assertTrue(runCatching { f.engine.onWake() }.isFailure)
        assertTrue(f.requests.isEmpty())
        assertEquals(RoutineRunState.QUEUED, f.engine.state.value.history.single().state)
        assertEquals(RoutineRunState.QUEUED, f.store.read().history.single().state)
        f.store.silentlyDropWrites = false
        f.engine = f.open()
        f.engine.onWake()
        assertEquals(1, f.requests.size)
    }

    @Test fun `silently dropped result keeps durable claim and cannot replay after reopen`() = runTest {
        val f = Fixture()
        val routine = f.engine.create(f.spec(enabled = false))
        f.engine.runNow(routine.id, "manual")
        f.action = { f.store.silentlyDropWrites = true; RoutineTaskResult.Completed() }
        assertTrue(runCatching { f.engine.onWake() }.isFailure)
        assertEquals(1, f.requests.size)
        assertEquals(RoutineRunState.RUNNING, f.store.read().history.single().state)
        f.store.silentlyDropWrites = false
        f.engine = f.open()
        f.engine.onWake()
        assertEquals(1, f.requests.size)
        assertEquals(RoutineRunState.INTERRUPTED, f.engine.state.value.history.single().state)
    }

    @Test fun `competing wakes and manual triggers execute one normal task`() = runTest {
        val f = Fixture()
        val gate = CompletableDeferred<Unit>()
        f.action = { gate.await(); RoutineTaskResult.Completed() }
        val r = f.engine.create(f.spec())
        val wakes = List(20) { launch { f.engine.onWake() } }
        runCurrent()
        assertEquals(1, f.requests.size)
        assertEquals(1, f.store.read().history.size)
        assertTrue(runCatching { f.engine.runNow(r.id, "another") }.isFailure)
        gate.complete(Unit)
        wakes.joinAll()
        assertEquals(RoutineRunState.SUCCEEDED, f.engine.state.value.history.single().state)
    }

    @Test fun `owner busy leaves a durable queue and does not consume another task`() = runTest {
        val f = Fixture()
        var ownerBusy = true
        var effects = 0
        f.action = {
            if (ownerBusy) RoutineTaskResult.Busy else { effects++; RoutineTaskResult.Completed() }
        }
        f.engine.create(f.spec())
        f.engine.onWake()
        assertEquals(0, effects)
        assertEquals(RoutineRunState.QUEUED, f.store.read().history.single().state)
        assertNull(f.store.read().history.single().startedAt)
        f.engine = f.open()
        ownerBusy = false
        f.engine.onWake()
        assertEquals(1, effects)
        assertEquals(RoutineRunState.SUCCEEDED, f.store.read().history.single().state)
    }

    @Test fun `process death in claimed task is interrupted and never auto replayed`() = runTest {
        val f = Fixture()
        f.action = { awaitCancellation() }
        f.engine.create(f.spec())
        val wake = launch { f.engine.onWake() }
        runCurrent()
        // Preserve exactly what a killed process left on disk, without its finally block.
        val diskAtDeath = f.store.text
        wake.cancelAndJoin()
        f.store.text = diskAtDeath
        f.engine = f.open()
        f.action = { RoutineTaskResult.Completed() }
        f.engine.onWake()
        val interrupted = f.engine.state.value.history.single()
        assertEquals(RoutineRunState.INTERRUPTED, interrupted.state)
        assertEquals(1, f.requests.size)
        val retry = f.engine.restartRun(interrupted.id, "reviewed-retry")
        f.engine.onWake()
        assertEquals(2, f.requests.size)
        assertEquals(RoutineRunState.SUCCEEDED, f.engine.state.value.history.last().state)
        assertEquals(retry.id, f.engine.restartRun(interrupted.id, "reviewed-retry").id)
    }

    @Test fun `queued task survives process restart`() = runTest {
        val f = Fixture()
        val r = f.engine.create(f.spec(enabled = false))
        val run = f.engine.runNow(r.id, "manual")
        f.engine = f.open()
        f.engine.onWake()
        assertEquals(run.id, f.requests.single().runId)
        assertEquals(r.spec.task, f.requests.single().task)
    }

    @Test fun `restart waits for cancellation cleanup and old completion cannot overwrite new run`() = runTest {
        val f = Fixture()
        val cleanup = CompletableDeferred<Unit>()
        var invocations = 0
        f.action = {
            if (++invocations == 1) {
                try { awaitCancellation() } finally { withContext(NonCancellable) { cleanup.await() } }
            } else RoutineTaskResult.Completed("new run")
        }
        val r = f.engine.create(f.spec(enabled = false))
        val old = f.engine.runNow(r.id, "first")
        val wake = launch { f.engine.onWake() }
        runCurrent()
        val replacement = f.engine.restartRun(old.id, "replacement")
        runCurrent()
        assertEquals(1, invocations)
        assertEquals(RoutineRunState.CANCELING, f.engine.state.value.history.first().state)
        assertEquals(RoutineRunState.QUEUED, f.engine.state.value.history.last().state)
        cleanup.complete(Unit)
        wake.join()
        assertEquals(2, invocations)
        assertEquals(listOf(RoutineRunState.CANCELED, RoutineRunState.SUCCEEDED), f.engine.state.value.history.map { it.state })
        assertEquals(replacement.id, f.engine.state.value.history.last().id)
    }

    @Test fun `canceling a queued run prevents dispatch and is idempotent`() = runTest {
        val f = Fixture()
        val r = f.engine.create(f.spec(enabled = false))
        val run = f.engine.runNow(r.id, "manual")
        assertEquals(RoutineRunState.CANCELED, f.engine.cancelRun(run.id).state)
        assertEquals(RoutineRunState.CANCELED, f.engine.cancelRun(run.id).state)
        f.engine.onWake()
        assertTrue(f.requests.isEmpty())
    }

    @Test fun `editing invalidates snapshots and revision conflict leaves state unchanged`() = runTest {
        val f = Fixture()
        val r = f.engine.create(f.spec(enabled = false))
        f.engine.runNow(r.id, "old-task")
        val edited = f.engine.update(r.id, r.revision, r.spec.copy(task = "New task"))
        assertEquals(2L, edited.revision)
        assertTrue(runCatching { f.engine.update(r.id, r.revision, r.spec) }.isFailure)
        f.engine.onWake()
        assertTrue(f.requests.isEmpty())
        f.engine.runNow(r.id, "new-task")
        f.engine.onWake()
        assertEquals("New task", f.requests.single().task)
        assertEquals(r.spec.task, f.engine.state.value.history.first().task)
    }

    @Test fun `disable cancels active task delete preserves history and no scheduled reenable replay`() = runTest {
        val f = Fixture()
        f.action = { awaitCancellation() }
        val r = f.engine.create(f.spec())
        val wake = launch { f.engine.onWake() }
        runCurrent()
        val off = f.engine.setEnabled(r.id, 1, false)
        wake.join()
        assertEquals(RoutineRunState.CANCELED, f.engine.state.value.history.single().state)
        val on = f.engine.setEnabled(r.id, off.revision, true)
        f.engine.onWake()
        assertEquals(1, f.requests.size)
        f.engine.delete(r.id, on.revision)
        assertTrue(f.engine.state.value.routines.isEmpty())
        assertEquals(1, f.store.read().history.size)
    }

    @Test fun `deletion of running routine signals only its task cancellation`() = runTest {
        val f = Fixture()
        f.action = { awaitCancellation() }
        val r = f.engine.create(f.spec())
        val wake = launch { f.engine.onWake() }
        runCurrent()
        f.engine.delete(r.id, 1)
        wake.join()
        assertTrue(f.engine.state.value.routines.isEmpty())
        assertEquals(RoutineRunState.CANCELED, f.engine.state.value.history.single().state)
    }

    @Test fun `skip records a miss and run latest coalesces years of downtime`() = runTest {
        val f = Fixture()
        val old = f.now - 365 * 24 * 60 * 60 * 1000L
        val skip = f.engine.create(f.spec(RoutineSchedule.Once(old)).copy(missedRunPolicy = MissedRunPolicy.SKIP))
        val repeat = f.engine.create(f.spec(RoutineSchedule.Interval(old, 900_000)))
        // Force a backlog using a process persisted before the long outage.
        val before = f.store.read()
        f.store.write(before.copy(routines = before.routines.map { if (it.id == repeat.id) it.copy(nextRunAt = old) else it }))
        f.engine = f.open()
        f.engine.onWake()
        assertEquals(1, f.requests.size)
        assertEquals(repeat.id, f.requests.single().routineId)
        assertEquals(RoutineRunState.MISSED, f.engine.state.value.history.first { it.routineId == skip.id }.state)
        assertEquals(f.now + 900_000, f.engine.state.value.routines.first { it.id == repeat.id }.nextRunAt)
        f.engine.onWake()
        assertEquals(2, f.engine.state.value.history.size)
    }

    @Test fun `late window expires pending work while owner remains busy`() = runTest {
        val f = Fixture()
        f.action = { RoutineTaskResult.Busy }
        f.engine.create(f.spec().copy(maxLatenessMillis = 60_000))
        f.engine.onWake()
        f.now += 60_001
        f.engine.onWake()
        assertEquals(1, f.requests.size)
        assertEquals(RoutineRunState.MISSED, f.engine.state.value.history.single().state)
    }

    @Test fun `overlap consumes scheduled slot without a second concurrent run`() = runTest {
        val f = Fixture()
        f.action = { awaitCancellation() }
        val r = f.engine.create(f.spec(RoutineSchedule.Interval(f.now, 900_000)))
        val wake = launch { f.engine.onWake() }
        runCurrent()
        f.now += 900_000
        f.engine.onWake()
        assertEquals(1, f.requests.size)
        assertEquals(RoutineRunState.MISSED, f.engine.state.value.history.last().state)
        f.engine.cancelRun(f.engine.state.value.history.first().id)
        wake.join()
        assertEquals(f.now + 900_000, f.engine.state.value.routines.first { it.id == r.id }.nextRunAt)
    }

    @Test fun `storage failure cannot publish creation or dispatch an unrecorded task`() = runTest {
        val f = Fixture()
        f.store.failWrites = true
        assertTrue(runCatching { f.engine.create(f.spec()) }.isFailure)
        assertTrue(f.engine.state.value.routines.isEmpty())
        f.store.failWrites = false
        f.engine.create(f.spec())
        f.store.failWrites = true
        assertTrue(runCatching { f.engine.onWake() }.isFailure)
        assertTrue(f.requests.isEmpty())
        assertTrue(f.engine.state.value.history.isEmpty())
        f.store.failWrites = false
        f.engine.onWake()
        assertEquals(1, f.requests.size)
    }

    @Test fun `canceled wake records interruption and never retries the claimed run`() = runTest {
        val f = Fixture()
        f.action = { awaitCancellation() }
        f.engine.create(f.spec())
        val wake = launch { f.engine.onWake() }
        runCurrent()
        wake.cancelAndJoin()
        assertEquals(RoutineRunState.INTERRUPTED, f.store.read().history.single().state)
        f.engine.onWake()
        assertEquals(1, f.requests.size)
    }

    @Test fun `timeout and callback failure are durable terminal outcomes`() = runTest {
        val f = Fixture()
        f.action = { awaitCancellation() }
        val r = f.engine.create(f.spec(enabled = false))
        f.engine.runNow(r.id, "timeout")
        val wake = launch { f.engine.onWake() }
        runCurrent()
        advanceTimeBy(RoutineEngine.TASK_TIMEOUT_MILLIS)
        wake.join()
        assertEquals(RoutineRunState.FAILED, f.store.read().history.single().state)
        assertTrue(f.store.read().history.single().detail!!.contains("Effects may have occurred"))
        f.action = { error("Sensitive provider response") }
        f.engine.runNow(r.id, "failure")
        f.engine.onWake()
        assertEquals(RoutineRunState.FAILED, f.store.read().history.last().state)
        assertFalse(f.store.text!!.contains("Sensitive"))
    }

    @Test fun `clock moving backward does not repeat consumed occurrence`() = runTest {
        val f = Fixture()
        f.engine.create(f.spec(RoutineSchedule.Interval(f.now, 900_000)))
        f.engine.onWake()
        f.now -= 3_600_000
        f.engine.onWake()
        f.now += 3_600_000
        f.engine.onWake()
        assertEquals(1, f.requests.size)
    }

    @Test fun `manual duplicate competing submissions share durable request key`() = runTest {
        val f = Fixture()
        val r = f.engine.create(f.spec(enabled = false))
        val runs = List(20) { async { f.engine.runNow(r.id, "same-key") } }.awaitAll()
        assertEquals(1, runs.map { it.id }.distinct().size)
        f.engine.onWake()
        assertEquals(1, f.requests.size)
    }

    @Test fun `parallel background wakes share one persisted schedule claim`() = runBlocking {
        val f = Fixture()
        f.action = { delay(10); RoutineTaskResult.Completed() }
        f.engine.create(f.spec())
        withContext(Dispatchers.Default) {
            List(64) { async { f.engine.onWake() } }.awaitAll()
        }
        assertEquals(1, f.requests.size)
        assertEquals(RoutineRunState.SUCCEEDED, f.store.read().history.single().state)
    }

    @Test fun `schedule edit rearms an exhausted one shot at the new time`() = runTest {
        val f = Fixture()
        val r = f.engine.create(f.spec())
        f.engine.onWake()
        val future = f.now + 3_600_000
        f.engine.update(r.id, 1, r.spec.copy(schedule = RoutineSchedule.Once(future)))
        f.engine.onWake()
        assertEquals(1, f.requests.size)
        f.now = future
        f.engine.onWake()
        assertEquals(2, f.requests.size)
    }

    @Test fun `enable preserves missed cursor and coalesces latest occurrence`() = runTest {
        val f = Fixture()
        val r = f.engine.create(f.spec(RoutineSchedule.Interval(f.now, 900_000), enabled = false))
        f.now += 3_600_000
        f.engine.onWake()
        assertTrue(f.requests.isEmpty())
        f.engine.setEnabled(r.id, 1, true)
        f.engine.onWake()
        assertEquals(1, f.requests.size)
        assertEquals(f.now, f.engine.state.value.history.single().scheduledAt)
    }

    @Test fun `request key cannot identify a different operation`() = runTest {
        val f = Fixture()
        val r = f.engine.create(f.spec(enabled = false))
        val run = f.engine.runNow(r.id, "first")
        assertTrue(runCatching { f.engine.restartRun(run.id, "first") }.isFailure)
        f.engine.restartRun(run.id, "restart")
        assertTrue(runCatching { f.engine.runNow(r.id, "restart") }.isFailure)
    }

    @Test fun `invalid documents fail closed and oversized inputs cannot create routines`() = runTest {
        assertTrue(runCatching { RoutineDocuments.decode("{\"version\":2}") }.isFailure)
        assertTrue(runCatching { RoutineDocuments.decode("not json") }.isFailure)
        val f = Fixture()
        assertTrue(runCatching { f.engine.create(f.spec().copy(task = "x".repeat(8_001))) }.isFailure)
        assertTrue(runCatching { f.engine.create(f.spec().copy(maxLatenessMillis = 0)) }.isFailure)
        val r = f.engine.create(f.spec(enabled = false))
        assertTrue(runCatching { f.engine.runNow(r.id, "has spaces") }.isFailure)
    }

    @Test fun `history is bounded without evicting queued work`() = runTest {
        val f = Fixture()
        val waiting = f.engine.create(f.spec(enabled = false))
        val other = f.engine.create(f.spec(enabled = false))
        val queued = f.engine.runNow(waiting.id, "waiting")
        // Existing validated terminal records exercise pruning without hundreds of executions.
        val base = f.store.read()
        val finished = (1..500).map { index -> queued.copy(
            id = "old-$index", routineId = other.id, requestKey = "old-$index",
            state = RoutineRunState.SUCCEEDED, finishedAt = f.now - 1,
        ) }
        f.store.write(base.copy(history = listOf(queued) + finished))
        f.engine = f.open()
        f.engine.runNow(other.id, "new")
        f.engine.onWake()
        assertEquals(500, f.engine.state.value.history.size)
        assertTrue(f.engine.state.value.history.any { it.id == queued.id })
        assertEquals(2, f.requests.size)
    }

    @Test fun `claim write failure leaves queued work and never invokes executor`() = runTest {
        val f = Fixture()
        val r = f.engine.create(f.spec(enabled = false))
        f.engine.runNow(r.id, "queued")
        f.store.failOnWrite = f.store.writes + 1
        assertTrue(runCatching { f.engine.onWake() }.isFailure)
        assertTrue(f.requests.isEmpty())
        assertEquals(RoutineRunState.QUEUED, f.store.read().history.single().state)
        f.store.failOnWrite = null
        f.engine.onWake()
        assertEquals(1, f.requests.size)
    }

    @Test fun `completion write failure recovers interrupted without repeating effects`() = runTest {
        val f = Fixture()
        val r = f.engine.create(f.spec(enabled = false))
        f.engine.runNow(r.id, "queued")
        f.action = {
            f.store.failOnWrite = f.store.writes + 1
            RoutineTaskResult.Completed()
        }
        assertTrue(runCatching { f.engine.onWake() }.isFailure)
        assertEquals(RoutineRunState.RUNNING, f.store.read().history.single().state)
        f.store.failOnWrite = null
        f.engine.onWake()
        assertEquals(RoutineRunState.INTERRUPTED, f.store.read().history.single().state)
        assertEquals(1, f.requests.size)
    }
}
