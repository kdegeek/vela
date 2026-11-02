package app.vela.tv.launcher

import app.vela.tv.design.StatusTone
import app.vela.tv.routines.RoutineRunState
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ManagementDesignTest {
    @Test fun confirmationsNeverExecuteWorkWhenPrepared() = runTest {
        val actions = ManagementActions(this)
        var calls = 0
        actions.confirm("Switch profile?") { calls++ }
        actions.confirm("Delete profile?", danger = true) { calls++ }
        runCurrent()
        assertEquals(0, calls)
        assertFalse(actions.busy)
        assertEquals("", actions.message)
    }

    @Test fun busyActionsRemainSingleFlightUntilTheCurrentWorkFinishes() = runTest {
        val actions = ManagementActions(this)
        val finish = CompletableDeferred<Unit>()
        var calls = 0
        actions.run { calls++; finish.await() }
        actions.run { calls++ }
        assertTrue(actions.busy)
        runCurrent()
        assertEquals(1, calls)
        finish.complete(Unit)
        runCurrent()
        assertFalse(actions.busy)
        assertEquals("Request completed. Review the current state below.", actions.message)
    }

    @Test fun failureRetainsTheConflictGuidanceAndRequiresAnExplicitRetry() = runTest {
        val actions = ManagementActions(this)
        var attempts = 0
        actions.run { attempts++; error("Revision conflict.") }
        runCurrent()
        assertFalse(actions.busy)
        assertEquals(1, attempts)
        assertEquals("Revision conflict. Reload before reapplying a change; your draft is retained.", actions.message)
        actions.run { attempts++ }
        runCurrent()
        assertEquals(2, attempts)
    }

    @Test fun pageCancellationClearsBusyWithoutClaimingCompletion() = runTest {
        val pageJob = SupervisorJob()
        val actions = ManagementActions(CoroutineScope(coroutineContext + pageJob))
        actions.run { awaitCancellation() }
        runCurrent()
        pageJob.cancel()
        runCurrent()
        assertFalse(actions.busy)
        assertFalse(actions.message.contains("completed"))
    }

    @Test fun routineHistoryDoesNotPresentPendingOrInterruptedWorkAsSuccess() {
        RoutineRunState.entries.forEach { state ->
            assertEquals(state == RoutineRunState.SUCCEEDED, routineManagementTone(state) == StatusTone.Success)
        }
        assertEquals(StatusTone.Agent, routineManagementTone(RoutineRunState.RUNNING))
        assertEquals(StatusTone.Danger, routineManagementTone(RoutineRunState.FAILED))
        for (state in listOf(RoutineRunState.QUEUED, RoutineRunState.CANCELING, RoutineRunState.INTERRUPTED, RoutineRunState.MISSED)) {
            assertEquals(StatusTone.Caution, routineManagementTone(state))
        }
        assertEquals(StatusTone.Neutral, routineManagementTone(RoutineRunState.CANCELED))
    }
}
