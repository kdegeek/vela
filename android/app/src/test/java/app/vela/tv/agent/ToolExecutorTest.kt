package app.vela.tv.agent

import app.vela.tv.guard.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ToolExecutorTest {
    private val call = ToolCall("call", "select", buildJsonObject {})
    private val risk = GuardHit(Risk.PURCHASE, "Rent")
    private class Target(val identity: Int, val label: String = "Rent")

    @Test fun `same risk and label on different focused node cannot inherit approval`() = runTest {
        var target = Target(1)
        var clicks = 0
        val tool = object : Tool {
            override val spec = ToolSpec("select", "select", buildJsonObject {})
            override suspend fun run(args: JsonObject): ToolOutcome = error("Unbound run")
            override suspend fun prepare(args: JsonObject): PreparedToolAction {
                val bound = target
                return PreparedToolAction(risk, { target === bound }) { clicks++; ToolOutcome("clicked") }
            }
        }
        val answer = CompletableDeferred<Boolean>()
        var stopped = false
        val job = async { ToolExecutor(listOf(tool)).execute(call, { _, _ -> answer.await() }, stopped = { stopped = true }) }
        runCurrent()
        target = Target(2) // Same label and risk; different source identity.
        answer.complete(true); runCurrent()
        assertTrue(job.await().isError)
        assertTrue(stopped)
        assertEquals(0, clicks)
    }

    @Test fun `provider or HA configuration change during prompt invalidates actual execution`() = runTest {
        for (field in listOf("provider", "homeAssistant")) {
            var configuration = Any()
            var dispatches = 0
            val tool = object : Tool {
                override val spec = ToolSpec("select", field, buildJsonObject {})
                override suspend fun run(args: JsonObject): ToolOutcome = error("Unbound run")
                override suspend fun prepare(args: JsonObject): PreparedToolAction {
                    val revision = configuration
                    return PreparedToolAction(risk, { configuration === revision }) { dispatches++; ToolOutcome("done") }
                }
            }
            val answer = CompletableDeferred<Boolean>()
            val job = async { ToolExecutor(listOf(tool)).execute(call, { _, _ -> answer.await() }) }
            runCurrent(); configuration = Any(); answer.complete(true); runCurrent()
            assertTrue(job.await().isError)
            assertEquals(0, dispatches)
        }
    }

    @Test fun `denial and cancellation never dispatch prepared target`() = runTest {
        var dispatches = 0
        val tool = object : Tool {
            override val spec = ToolSpec("select", "select", buildJsonObject {})
            override suspend fun check(args: JsonObject) = risk
            override suspend fun run(args: JsonObject): ToolOutcome { dispatches++; return ToolOutcome("done") }
        }
        assertTrue(ToolExecutor(listOf(tool)).execute(call, { _, _ -> false }).isError)
        val job = launch { ToolExecutor(listOf(tool)).execute(call, { _, _ -> awaitCancellation() }) }
        runCurrent(); job.cancelAndJoin()
        assertEquals(0, dispatches)
    }

    @Test fun `prepared action is single use`() = runTest {
        var clicks = 0
        val action = PreparedToolAction(null) { clicks++; ToolOutcome("done") }
        action.execute()
        assertTrue(runCatching { action.execute() }.isFailure)
        assertEquals(1, clicks)
    }
}
