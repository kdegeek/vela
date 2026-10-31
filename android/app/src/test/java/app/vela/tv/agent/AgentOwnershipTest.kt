package app.vela.tv.agent

import app.vela.tv.guard.*
import app.vela.tv.integrations.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AgentOwnershipTest {
    @Test fun `owner Ask cannot cancel an admitted external controller or its draining cleanup`() = runTest {
        var factories = 0
        val drain = CompletableDeferred<Unit>()
        val session = AgentSession(backgroundScope, { factories++; null })
        var canceled = false
        val external = launch {
            session.withController("mcp:desktop") {
                try { awaitCancellation() } finally { withContext(NonCancellable) { canceled = true; drain.await() } }
            }
        }
        runCurrent()
        assertTrue(runCatching { session.ask("new task") }.exceptionOrNull() is ControlBusyException)
        assertNull(session.submit("scheduled task"))
        assertTrue(external.isActive)
        assertFalse(canceled)
        assertEquals(0, factories)
        session.stop(); runCurrent()
        assertTrue(canceled)
        assertTrue(runCatching { session.ask("while external drains") }.exceptionOrNull() is ControlBusyException)
        drain.complete(Unit); external.join(); runCurrent()
        assertFalse(session.running.value)
        session.ask("after cleanup"); runCurrent()
        assertEquals(1, factories)
    }
    private fun waitingAgent(confirm: suspend (ToolCall, GuardHit) -> Boolean, cleanup: suspend () -> Unit): Agent =
        Agent(object : Provider {
            override suspend fun complete(system: String, history: List<Msg>, tools: List<ToolSpec>): Turn =
                try { awaitCancellation() } finally { withContext(NonCancellable) { cleanup() } }
        }, emptyList(), "s", confirm)

    @Test fun `Stop retains exclusive lease until canceled work drains`() = runTest {
        val drain = CompletableDeferred<Unit>()
        val control = ControlArbiter()
        val session = AgentSession(backgroundScope, { waitingAgent(it) { drain.await() } }, control = control)
        session.ask("work"); runCurrent()
        session.stop(); runCurrent()
        assertTrue(session.running.value)
        assertNotNull(control.owner.value)
        assertNull(session.submit("scheduled"))
        assertTrue(runCatching { control.withControl("phone") { fail("must not take over") } }.exceptionOrNull() is ControlBusyException)
        drain.complete(Unit); runCurrent()
        assertFalse(session.running.value)
        assertNull(control.owner.value)
        control.withControl("phone") { /* Now safe. */ }
    }

    @Test fun `stopAndJoin waits for actual cleanup before reporting idle`() = runTest {
        val drain = CompletableDeferred<Unit>()
        val session = AgentSession(backgroundScope, { waitingAgent(it) { drain.await() } })
        session.ask("work"); runCurrent()
        val stopping = async { session.stopAndJoin() }
        runCurrent()
        assertFalse(stopping.isCompleted)
        assertTrue(session.running.value)
        drain.complete(Unit)
        stopping.await()
        assertFalse(session.running.value)
    }

    @Test fun `rapid replacement of canceled waiters cannot skip predecessor cleanup`() = runTest {
        val drain = CompletableDeferred<Unit>()
        var instances = 0
        val session = AgentSession(backgroundScope, { confirm ->
            instances++
            if (instances == 1) waitingAgent(confirm) { drain.await() }
            else waitingAgent(confirm) {}
        })
        session.ask("first"); runCurrent()
        session.ask("second"); runCurrent()
        session.ask("third"); runCurrent()
        assertEquals(1, instances)
        assertTrue(session.running.value)
        drain.complete(Unit); runCurrent()
        assertEquals(2, instances)
        assertTrue(session.running.value)
        session.stop(); runCurrent()
        assertFalse(session.running.value)
    }

    @Test fun `routine reservation is atomic and cleanup cannot cancel newer owner task`() = runTest {
        val session = AgentSession(backgroundScope, { waitingAgent(it) {} })
        val routine = session.submit("routine")!!
        assertNull(session.submit("competing routine"))
        runCurrent()
        val owner = session.ask("owner"); runCurrent()
        session.cancelAndJoin(routine)
        assertEquals("canceled", session.await(routine).state)
        assertTrue(session.running.value)
        assertEquals(owner, session.tasks.value.last().id)
        assertEquals("running", session.tasks.value.last().state)
        session.cancelAndJoin(owner)
        runCurrent()
        assertFalse(session.running.value)
    }

    @Test fun `busy external controller prevents nonreplacing reservation`() = runTest {
        val control = ControlArbiter()
        val session = AgentSession(backgroundScope, { error("must not create a model") }, control = control)
        control.withControl("external") { assertNull(session.submit("routine")) }
        assertTrue(session.tasks.value.isEmpty())
    }

    @Test fun `external prompt and phone response use session's actual shared broker`() = runTest {
        val approvals = ControlApprovalBroker(uptimeMs = { 123L })
        val session = AgentSession(backgroundScope, { null }, approvals = approvals)
        val request = async { approvals.confirm("mcp:one", GuardHit(Risk.SECURITY, "garage")) }
        runCurrent()
        val prompt = session.pending.value!!
        assertEquals("mcp:one", prompt.controller)
        assertEquals(123L, prompt.since)
        assertFalse(session.answer(true, "old-prompt"))
        assertTrue(session.answer(false, prompt.id))
        assertFalse(request.await())
        assertNull(session.pending.value)
    }

    @Test fun `profile or memory revision changes discard the former conversation`() = runTest {
        var revision = "profile-one:1"
        val observed = mutableListOf<List<Msg>>()
        val session = AgentSession(backgroundScope, { confirm -> Agent(object : Provider {
            override suspend fun complete(system: String, history: List<Msg>, tools: List<ToolSpec>): Turn {
                observed += history
                return Turn("private previous context", emptyList())
            }
        }, emptyList(), "s", confirm) }, contextKey = { revision })
        session.ask("first"); runCurrent()
        revision = "profile-one:2" // Even same-profile deletion invalidates used memory.
        session.ask("second"); runCurrent()
        assertEquals(listOf(Msg.User("second")), observed.last())
        revision = "profile-two:3"
        session.ask("third"); runCurrent()
        assertEquals(listOf(Msg.User("third")), observed.last())
    }
}
