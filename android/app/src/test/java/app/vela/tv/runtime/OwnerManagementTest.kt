package app.vela.tv.runtime

import app.vela.tv.agent.TaskRecord
import app.vela.tv.integrations.ControlBusyException
import app.vela.tv.memory.MemoryKind
import app.vela.tv.profile.*
import app.vela.tv.routines.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OwnerManagementTest {
    @Test fun `profile switch acquires immediately after the canceled external lease drains`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val f = RuntimeFixture(backgroundScope)
            val initial = f.management()
            val other = initial.createProfile(initial.profiles().revision, ProfileDraft("Other"))
            val external = launch { f.control.withControl("external") { awaitCancellation() } }
            runCurrent()
            val management = OwnerManagement(f.control, f.running,
                stopAndDrain = { external.cancel(); f.control.owner.first { it == null } },
                invalidateContext = {}, household = { f.store }, engine = { f.engine },
                bindings = f.bindings, wake = {}, external = f.external)
            val switching = async { management.switchProfile(other.revision, other.value.id) }
            runCurrent()
            assertEquals(other.value.id, switching.await().value.id)
            assertEquals(other.value.id, f.store.snapshot().activeProfileId)
            external.join()
            assertNull(f.control.owner.value)
        } finally { Dispatchers.resetMain() }
    }
    @Test fun `profile switch drains and all household writes invalidate while busy writes do nothing`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val f = RuntimeFixture(backgroundScope); val m = f.management()
            val initial = m.profiles()
            f.running.value = true
            try { m.createProfile(initial.revision, ProfileDraft("Blocked")); fail() } catch (_: ControlBusyException) { }
            assertEquals(1, m.profiles().profiles.size)
            f.running.value = false
            val created = m.createProfile(initial.revision, ProfileDraft("Other"))
            m.switchProfile(created.revision, created.value.id)
            assertEquals(1, f.stops); assertEquals(2, f.clears)
            val saved = m.saveMemory(m.profiles().revision, created.value.id, MemoryDraft(MemoryKind.FACT, "Owner saved this"))
            assertEquals(3, f.clears)
            assertFalse(m.profiles().toString().contains("Owner saved this"))
            try { m.deleteMemory(saved.revision - 1, created.value.id, saved.value.id); fail() }
            catch (e: HouseholdException) { assertEquals(HouseholdError.CONFLICT, e.code) }
            assertEquals(1, m.memories(created.value.id).total)
        } finally { Dispatchers.resetMain() }
    }
    @Test fun `routine policy binding survives reload denies another profile and retains policy across enable`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val f = RuntimeFixture(backgroundScope); val m = f.management()
            val routine = m.createRoutine(RoutineSpec("Evening", "Read status", RoutineSchedule.Once(2_000_000)))
            val run = m.runRoutine(routine.id, "gesture-1")
            val disk = RoutineBindings({ f.bindingBytes }, { f.bindingBytes = it })
            assertTrue(disk.permits(run, f.store.snapshot()))
            val other = m.createProfile(m.profiles().revision, ProfileDraft("Other"))
            m.switchProfile(other.revision, other.value.id)
            assertFalse(disk.permits(run, f.store.snapshot()))
            val disabled = m.enableRoutine(routine.id, routine.revision, false)
            assertFalse(f.bindings.permits(run.copy(revision = disabled.revision), f.store.snapshot()))
            // Explicit edit is the only operation which rebinds to the newly selected person.
            val edited = m.updateRoutine(routine.id, disabled.revision, routine.spec)
            val fresh = m.runRoutine(routine.id, "gesture-2")
            assertEquals(edited.revision, fresh.revision)
            assertTrue(f.bindings.permits(fresh, f.store.snapshot()))
        } finally { Dispatchers.resetMain() }
    }
    @Test fun `saved routines cannot submit under a different profile and unbound legacy work pauses`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val f = RuntimeFixture(backgroundScope); val m = f.management()
            val owner = f.store.snapshot().activeProfile
            val routine = m.createRoutine(RoutineSpec("Bound", "Observe status", RoutineSchedule.Once(2_000_000), ownerProfileId = "untrusted"))
            assertEquals(owner.id, routine.spec.ownerProfileId)
            val run = m.runRoutine(routine.id, "deliberate")
            assertEquals(owner.id, run.ownerProfileId)
            var submissions = 0
            val executor = RoutineSubmission({ request ->
                requireRoutineProfile(request, run, f.store.snapshot(), f.bindings)
                submissions++; "accepted"
            }, { TaskRecord(it, run.task, "complete", 0, 1) }, { true })
            val request = RoutineTaskRequest(run.id, run.routineId, run.name, run.task, run.ownerProfileId)
            assertTrue(executor.tryExecute(request) is RoutineTaskResult.Completed)
            val other = m.createProfile(m.profiles().revision, ProfileDraft("Other"))
            m.switchProfile(other.revision, other.value.id)
            val denied = executor.tryExecute(request)
            assertTrue(denied is RoutineTaskResult.Failed)
            assertTrue((denied as RoutineTaskResult.Failed).detail.contains("intended profile"))
            assertEquals(1, submissions)
            val legacy = f.engine.create(RoutineSpec("Legacy", "Unbound", RoutineSchedule.Once(2_000_000)))
            val queued = f.engine.runNow(legacy.id, "legacy")
            pauseUnboundRoutines(f.engine)
            assertFalse(f.engine.state.value.routines.single { it.id == legacy.id }.spec.enabled)
            assertEquals(RoutineRunState.CANCELED, f.engine.state.value.history.single { it.id == queued.id }.state)
            val legacyDocument = RoutineDocuments.encode(f.engine.state.value).replace(",\"ownerProfileId\":null", "")
            val reload = RoutineDocuments.decode(legacyDocument)
            assertNull(reload.routines.single { it.id == legacy.id }.spec.ownerProfileId)
            assertEquals(owner.id, reload.history.single { it.id == run.id }.ownerProfileId)
        } finally { Dispatchers.resetMain() }
    }
    @Test fun `routine submission awaits outcome and cancellation drains exactly its reservation`() = runTest {
        var reserved = 0; val canceled = mutableListOf<String>(); val finish = CompletableDeferred<TaskRecord>()
        val admission = UnconfinedTestDispatcher(testScheduler)
        val adapter = RoutineSubmission({ reserved++; "routine-task" }, { finish.await() }, { canceled += it; true }, admission)
        val request = RoutineTaskRequest("run", "routine", "Name", "Task")
        val work = async { adapter.tryExecute(request) }; runCurrent()
        assertEquals(1, reserved); assertTrue(canceled.isEmpty())
        work.cancelAndJoin()
        assertEquals(listOf("routine-task"), canceled)
        val busy = RoutineSubmission({ null }, { error("No await") }, { error("Must not cancel another owner") }, admission)
        assertEquals(RoutineTaskResult.Busy, busy.tryExecute(request))
        val complete = RoutineSubmission({ "id" }, { TaskRecord(it, "Task", "complete", 0, 1, "Observed") }, { true }, admission)
        assertEquals(RoutineTaskResult.Completed("Observed"), complete.tryExecute(request))
    }
    @Test fun `cancellation during reservation return still drains acquired task`() = runTest {
        val dispatched = ArrayDeque<Runnable>()
        val admission = object : CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { dispatched.add(block) }
        }
        val canceled = mutableListOf<String>()
        var reserved = false
        var awaited = false
        val adapter = RoutineSubmission({ reserved = true; "reserved-on-main" }, {
            awaited = true; awaitCancellation()
        }, { canceled += it; true }, admission)
        val work = launch { adapter.tryExecute(RoutineTaskRequest("run", "routine", "Name", "Task")) }
        runCurrent()
        assertFalse(reserved)
        dispatched.removeFirst().run()
        assertTrue(reserved)
        work.cancel()
        runCurrent()
        work.join()
        assertFalse(awaited)
        assertEquals(listOf("reserved-on-main"), canceled)
    }
    @Test fun `large valid routine bindings reopen with the same bound used for saving`() = runTest {
        val f = RuntimeFixture(backgroundScope); f.management()
        val packages = (1..100).map { "app." + "a".repeat(247) + it.toString().padStart(4, '0') }
        val original = f.store.snapshot().activeProfile
        val profile = f.store.editProfile(0, original.id, original.draft().copy(
            restrictions = ProfileRestrictions(allowedAppPackages = packages),
        )).value
        repeat(100) { index ->
            val routine = f.engine.create(RoutineSpec("Routine $index", "Read status", RoutineSchedule.Once(2_000_000), ownerProfileId = profile.id))
            f.bindings.bind(routine, profile)
        }
        val bytes = f.bindingBytes!!
        assertTrue(bytes.size > 1024 * 1024)
        assertTrue(bytes.size <= RoutineBindings.MAX_DOCUMENT_BYTES)
        val reopened = RoutineBindings({ bytes.also { check(it.size <= RoutineBindings.MAX_DOCUMENT_BYTES) } }, { error("No writes during reopen") })
        reopened.validate()
        val run = f.engine.runNow(f.engine.state.value.routines.last().id, "manual")
        assertTrue(reopened.permits(run, f.store.snapshot()))
    }
}
