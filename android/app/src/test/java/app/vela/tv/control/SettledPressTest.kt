package app.vela.tv.control

import app.vela.tv.agent.ActionInvalidatedException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettledPressTest {
    @Test fun `Stop while focus settles cannot produce a late click`() = runTest {
        var clicks = 0
        val job = launch { settleAndDispatch(valid = { true }, dispatch = { clicks++; true }) }
        runCurrent()
        advanceTimeBy(149)
        job.cancelAndJoin()
        advanceUntilIdle()
        assertEquals(0, clicks)
    }
    @Test fun `changed focus immediately before dispatch aborts click`() = runTest {
        var focused = 1
        var clicks = 0
        val job = async { runCatching { settleAndDispatch(valid = { focused == 1 }, dispatch = { clicks++; true }) } }
        runCurrent()
        focused = 2
        advanceTimeBy(150); runCurrent()
        assertTrue(job.await().exceptionOrNull() is ActionInvalidatedException)
        assertEquals(0, clicks)
    }
    @Test fun `unchanged target dispatches exactly once after settle`() = runTest {
        var clicks = 0
        val job = async { settleAndDispatch(valid = { true }, dispatch = { clicks++; true }) }
        runCurrent(); advanceTimeBy(149)
        assertEquals(0, clicks)
        advanceTimeBy(1); runCurrent()
        assertTrue(job.await())
        assertEquals(1, clicks)
    }
}
