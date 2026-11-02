package app.vela.tv.mcp

import app.vela.tv.agent.ToolOutcome
import app.vela.tv.agent.Agent
import app.vela.tv.agent.AgentSession
import app.vela.tv.agent.Provider
import app.vela.tv.agent.Msg
import app.vela.tv.agent.ToolSpec
import app.vela.tv.agent.Turn
import app.vela.tv.guard.GuardHit
import app.vela.tv.guard.Risk
import app.vela.tv.integrations.ControlBusyException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GuardedToolBackendTest {
    private val risk = GuardHit(Risk.PURCHASE, "Buy movie")
    private fun call(f: McpFixture) = ExternalToolCall("mcp:desktop", "1", "remote", f.args)

    @Test fun `invalid arguments never reach guard approval or execution`() = runTest {
        val f = McpFixture(); val backend = f.backend()
        for (args in listOf("{}", """{"key":"delete"}""", """{"key":"up","times":1.5}""", """{"key":"up","times":"2"}""", """{"key":"up","extra":true}""")) {
            assertTrue(backend.executeGuarded(call(f).copy(arguments = Json.parseToJsonElement(args).jsonObject)).isError)
        }
        assertEquals(0, f.checks); assertEquals(0, f.approvals); assertEquals(0, f.effects)
    }

    @Test fun `decline and approval timeout fail closed`() = runTest {
        val f = McpFixture(); f.check = { risk }; f.approval = { false }
        assertTrue(f.backend().executeGuarded(call(f)).isError)
        f.approval = { awaitCancellation() }
        assertTrue(f.backend(approvalTimeoutMs = 10).executeGuarded(call(f)).isError)
        assertEquals(2, f.approvals); assertEquals(0, f.effects); assertNull(f.control.owner.value)
    }

    @Test fun `prepared target identity invalidation rejects even when guard reason stays equal`() = runTest {
        val f = McpFixture()
        var target = 1
        var prepared = 0
        val tool = object : app.vela.tv.agent.Tool {
            override val spec = f.tool.spec
            override suspend fun run(args: JsonObject): ToolOutcome = error("Raw execution forbidden")
            override suspend fun prepare(args: JsonObject): app.vela.tv.agent.PreparedToolAction {
                prepared++
                val identity = target
                return app.vela.tv.agent.PreparedToolAction(risk, { target == identity }) { f.effects++; ToolOutcome("observed") }
            }
        }
        val backend = GuardedToolBackend(listOf(tool), f.control, { _, _ -> target = 2; true })
        val result = backend.executeGuarded(call(f))
        assertTrue(result.isError); assertEquals(1, prepared); assertEquals(0, f.effects)
    }

    @Test fun `approved action executes once with bounded output and redacted exceptions`() = runTest {
        val f = McpFixture(); f.check = { risk }; f.run = { ToolOutcome("x".repeat(30_000)) }
        val backend = f.backend()
        assertEquals(24_000, backend.executeGuarded(call(f)).content.length)
        assertEquals(1, f.effects); assertEquals(1, f.approvals); assertEquals(2, f.checks)
        f.run = { error("private-secret") }
        val failure = backend.executeGuarded(call(f))
        assertTrue(failure.isError); assertFalse(failure.content.contains("private-secret"))
    }

    @Test fun `cancellation during approval releases control after cleanup without running action`() = runTest {
        val f = McpFixture(); f.check = { risk }
        val entered = CompletableDeferred<Unit>(); val cleanup = CompletableDeferred<Unit>()
        f.approval = { try { entered.complete(Unit); awaitCancellation() } finally { withContext(NonCancellable) { cleanup.await() } } }
        val task = launch { f.backend().executeGuarded(call(f)) }
        entered.await(); task.cancel(); runCurrent()
        try { f.control.withControl("phone") { fail("Competing input ran") }; fail("Expected busy") } catch (e: ControlBusyException) { }
        cleanup.complete(Unit); task.join()
        f.control.withControl("runner") { assertEquals("runner", f.control.owner.value) }
        assertEquals(0, f.effects); assertNull(f.control.owner.value)
    }

    @Test fun `runner and phone lease reject external calls before even checking a tool`() = runTest {
        val f = McpFixture()
        for (owner in listOf("runner", "phone")) f.control.withControl(owner) {
            try { f.backend().executeGuarded(call(f)); fail("Expected busy") } catch (e: ControlBusyException) { }
        }
        assertEquals(0, f.checks); assertEquals(0, f.effects)
    }

    @Test fun `external admission cannot overtake an accepted replacement while predecessor drains`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val f = McpFixture()
            val drain = CompletableDeferred<Unit>()
            var instances = 0
            val session = AgentSession(backgroundScope, { confirm ->
                val first = ++instances == 1
                Agent(object : Provider {
                    override suspend fun complete(system: String, history: List<Msg>, tools: List<ToolSpec>): Turn =
                        try { awaitCancellation() } finally { if (first) withContext(NonCancellable) { drain.await() } }
                }, emptyList(), "s", confirm)
            }, control = f.control)
            val backend = GuardedToolBackend(listOf(f.tool), f.control, { _, _ -> true },
                admission = { owner, action -> withContext(Dispatchers.Main.immediate) { session.withController(owner, action) } })
            session.ask("first"); runCurrent()
            val replacement = session.ask("replacement"); runCurrent()
            var refused = false
            val external = launch(UnconfinedTestDispatcher(testScheduler)) {
                f.control.owner.first { it == null }
                try { backend.executeGuarded(call(f)); fail("External action overtook replacement") }
                catch (_: ControlBusyException) { refused = true }
            }
            drain.complete(Unit); runCurrent(); external.join()
            assertTrue(refused)
            assertEquals(0, f.checks); assertEquals(0, f.effects)
            assertTrue(session.running.value)
            assertEquals(replacement, session.tasks.value.last().id)
            assertEquals("running", session.tasks.value.last().state)
            session.stopAndJoin()
        } finally { Dispatchers.resetMain() }
    }
}
