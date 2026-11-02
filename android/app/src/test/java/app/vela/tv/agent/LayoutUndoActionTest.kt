package app.vela.tv.agent

import app.vela.tv.guard.Risk
import app.vela.tv.guard.TileGuards
import app.vela.tv.layout.*
import app.vela.tv.settings.LayoutHistory
import app.vela.tv.settings.LayoutUndo
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LayoutUndoActionTest {
    private val a = layout("a")
    private val b = layout("b")
    private val c = layout("c")
    private val guarded = HomeLayout(rows = listOf(RowSpec("garage", "Garage", Source.HomeEntities(listOf("cover.garage")))))
    private val call = ToolCall("undo", "layout_undo", buildJsonObject {})

    @Test fun successfulUndoRestoresOnlyTheCapturedLayoutAndConsumesHistory() = runTest {
        val env = Environment(a)
        env.history.setLayout(b)
        val result = ToolExecutor(listOf(env.tool())).execute(call, { _, _ -> error("Unexpected approval") })
        assertFalse(result.isError)
        assertEquals(a, env.history.layout.value)
        assertNull(env.history.layoutUndo.value)
        assertEquals(listOf(Layouts.encode(b), Layouts.encode(a)), env.writes)
    }

    @Test fun missingHistoryReturnsAnErrorWithoutWritingOrApproving() = runTest {
        val env = Environment(a)
        val result = ToolExecutor(listOf(env.tool())).execute(call, { _, _ -> error("Unexpected approval") })
        assertTrue(result.isError)
        assertTrue(result.content.contains("no previous Home layout"))
        assertTrue(env.writes.isEmpty())
        assertEquals(a, env.history.layout.value)
    }

    @Test fun restoringSecurityActionsRequiresApprovalForThePreviousDocument() = runTest {
        val env = Environment(guarded)
        env.history.setLayout(b)
        var approvals = 0
        val result = ToolExecutor(listOf(env.tool())).execute(call, { _, hit ->
            approvals++
            assertEquals(Risk.SECURITY, hit.risk)
            true
        })
        assertFalse(result.isError)
        assertEquals(1, approvals)
        assertEquals(guarded, env.history.layout.value)
        assertNull(env.history.layoutUndo.value)
    }

    @Test fun denialLeavesTheExactChangeAndItsUndoAvailable() = runTest {
        val env = Environment(guarded)
        env.history.setLayout(b)
        val receipt = env.history.layoutUndo.value
        val result = ToolExecutor(listOf(env.tool())).execute(call, { _, _ -> false })
        assertTrue(result.isError)
        assertEquals(b, env.history.layout.value)
        assertSame(receipt, env.history.layoutUndo.value)
        assertEquals(1, env.writes.size)
    }

    @Test fun newerLayoutDuringApprovalCannotInheritTheOldUndo() = runTest {
        val env = Environment(guarded)
        env.history.setLayout(b)
        var stopped = false
        val result = ToolExecutor(listOf(env.tool())).execute(call, { _, _ ->
            env.history.setLayout(c)
            true
        }, stopped = { stopped = true })
        assertTrue(result.isError)
        assertTrue(stopped)
        assertEquals(c, env.history.layout.value)
        assertEquals(b, env.history.layoutUndo.value?.previous)
        assertEquals(2, env.writes.size)
    }

    @Test fun equalDocumentsAndEqualRiskAfterABACannotReviveAnApprovedReceipt() = runTest {
        val env = Environment(guarded)
        env.history.setLayout(b)
        val stale = env.history.layoutUndo.value
        val result = ToolExecutor(listOf(env.tool())).execute(call, { _, _ ->
            env.history.setLayout(guarded)
            env.history.setLayout(b)
            assertEquals(stale?.previous, env.history.layoutUndo.value?.previous)
            assertNotSame(stale, env.history.layoutUndo.value)
            true
        })
        assertTrue(result.isError)
        assertEquals(b, env.history.layout.value)
        assertEquals(3, env.writes.size)
    }

    @Test fun changeAfterPreparationValidationIsStillRejectedByTheStore() = runTest {
        val env = Environment(a)
        env.history.setLayout(b)
        val tool = env.tool { receipt ->
            // Models an IO-dispatch gap after the prepared action's first check.
            env.history.setLayout(c)
            env.history.undoLayout(receipt)
        }
        var stopped = false
        val result = ToolExecutor(listOf(tool)).execute(call, { _, _ -> error("Unexpected approval") },
            stopped = { stopped = true })
        assertTrue(result.isError)
        assertTrue(stopped)
        assertTrue(result.content.contains("changed"))
        assertEquals(c, env.history.layout.value)
        assertEquals(b, env.history.layoutUndo.value?.previous)
        assertEquals(2, env.writes.size)
    }

    @Test fun storageInvalidationStopsTheBatchBeforeAnotherUndoCanCaptureNewHistory() = runTest {
        val env = Environment(a)
        env.history.setLayout(b)
        var restoreAttempts = 0
        val tool = env.tool { receipt ->
            if (++restoreAttempts == 1) env.history.setLayout(c)
            env.history.undoLayout(receipt)
        }
        var turns = 0
        val provider = object : Provider {
            override suspend fun complete(system: String, history: List<Msg>, tools: List<ToolSpec>): Turn =
                if (turns++ == 0) Turn(null, listOf(call, call.copy(id = "second-undo")))
                else Turn("Done", emptyList())
        }
        val events = mutableListOf<AgentEvent>()
        val result = Agent(provider, listOf(tool), "", { _, _ -> error("Unexpected approval") })
            .run("Undo the previous Home change", events = { events += it })
        assertEquals(1, restoreAttempts)
        assertEquals(1, turns)
        assertEquals(c, env.history.layout.value)
        assertEquals(b, env.history.layoutUndo.value?.previous)
        assertEquals(2, env.writes.size)
        val outcomes = result.filterIsInstance<Msg.ToolResult>()
        assertEquals(2, outcomes.size)
        assertTrue(outcomes.all { it.isError })
        assertTrue(outcomes[1].content.startsWith("Skipped:"))
        assertTrue(events.last() is AgentEvent.Failed)
    }

    @Test fun ordinaryPersistenceFailureKeepsHistoryAvailableForAnExplicitRetry() = runTest {
        val env = Environment(a)
        env.history.setLayout(b)
        val receipt = env.history.layoutUndo.value
        var attempts = 0
        val tool = env.tool { captured ->
            if (++attempts == 1) throw IllegalStateException("Simulated storage verification failure")
            env.history.undoLayout(captured)
        }
        var stopped = false
        val executor = ToolExecutor(listOf(tool))
        val failed = executor.execute(call, { _, _ -> error("Unexpected approval") }, stopped = { stopped = true })
        assertTrue(failed.isError)
        assertFalse(stopped)
        assertSame(receipt, env.history.layoutUndo.value)
        assertEquals(b, env.history.layout.value)
        assertEquals(1, env.writes.size)
        val retried = executor.execute(call.copy(id = "explicit-retry"), { _, _ -> error("Unexpected approval") })
        assertFalse(retried.isError)
        assertEquals(a, env.history.layout.value)
        assertNull(env.history.layoutUndo.value)
        assertEquals(2, attempts)
    }

    @Test fun cancellationDuringApprovalDoesNotConsumeOrRestoreTheLayout() = runTest {
        val env = Environment(guarded)
        env.history.setLayout(b)
        val receipt = env.history.layoutUndo.value
        val job = launch { ToolExecutor(listOf(env.tool())).execute(call, { _, _ -> awaitCancellation() }) }
        runCurrent()
        job.cancelAndJoin()
        assertEquals(b, env.history.layout.value)
        assertSame(receipt, env.history.layoutUndo.value)
        assertEquals(1, env.writes.size)
    }

    private class Environment(initial: HomeLayout) {
        val writes = mutableListOf<String>()
        val history = LayoutHistory(initial) { writes += it }
        fun tool(restore: suspend (LayoutUndo) -> Unit = { history.undoLayout(it) }): Tool = object : Tool {
            override val spec = ToolSpec("layout_undo", "Undo Home", buildJsonObject {})
            override suspend fun run(args: JsonObject): ToolOutcome = error("Unprepared Undo")
            override suspend fun prepare(args: JsonObject) = prepareLayoutUndo(history.layoutUndo.value,
                { history.layoutUndo.value }, { TileGuards.layout(it) }, restore)
        }
    }

    private fun layout(id: String) = HomeLayout(rows = listOf(RowSpec(id, id, Source.Apps())))
}
