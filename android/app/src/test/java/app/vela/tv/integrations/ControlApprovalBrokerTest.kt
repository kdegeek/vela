package app.vela.tv.integrations

import app.vela.tv.guard.GuardHit
import app.vela.tv.guard.Risk
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ControlApprovalBrokerTest {
    private val hit = GuardHit(Risk.PURCHASE, "Buy movie")

    @Test fun `only an answer to the displayed prompt can approve and it is consumed once`() = runTest {
        val broker = ControlApprovalBroker(uptimeMs = { 123 })
        val action = async { broker.confirm("mcp:desktop", hit) }; runCurrent()
        val prompt = broker.pending.value!!
        assertEquals(123L, prompt.since); assertEquals("mcp:desktop", prompt.controller)
        assertFalse(broker.answer(true, "stale"))
        assertTrue(broker.answer(true, prompt.id)); assertFalse(broker.answer(true, prompt.id))
        assertTrue(action.await()); assertNull(broker.pending.value)
    }

    @Test fun `a canceled approval cannot leak its answer to a replacement`() = runTest {
        val broker = ControlApprovalBroker()
        val old = async { broker.confirm("mcp:old", hit) }; runCurrent()
        val oldId = broker.pending.value!!.id
        old.cancelAndJoin(); assertNull(broker.pending.value)
        val fresh = async { broker.confirm("mcp:new", hit) }; runCurrent()
        assertFalse(broker.answer(true, oldId))
        assertTrue(broker.answer(false, broker.pending.value!!.id))
        assertFalse(fresh.await())
    }

    @Test fun `missing answer expires as decline and clears presentation state`() = runTest {
        val broker = ControlApprovalBroker(timeoutMs = 10)
        assertFalse(broker.confirm("mcp:desktop", hit)); assertNull(broker.pending.value)
    }

    @Test fun `a second prompt cannot replace or implicitly approve an outstanding prompt`() = runTest {
        val broker = ControlApprovalBroker()
        val first = async { broker.confirm("mcp:first", hit) }; runCurrent()
        val id = broker.pending.value!!.id
        try { broker.confirm("mcp:second", hit); fail("Expected busy") } catch (e: ControlBusyException) { }
        assertEquals(id, broker.pending.value!!.id)
        first.cancelAndJoin(); assertNull(broker.pending.value)
    }
}
