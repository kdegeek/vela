package app.vela.tv.integrations

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ControlArbiterTest {
    @Test fun `an immediate idle collector can acquire and retain the next lease`() = runTest {
        val control = ControlArbiter()
        val finishFirst = CompletableDeferred<Unit>()
        val finishSecond = CompletableDeferred<Unit>()
        val first = launch { control.withControl("first") { finishFirst.await() } }
        runCurrent()
        var acquired = false
        val second = launch(UnconfinedTestDispatcher(testScheduler)) {
            control.owner.first { it == null }
            control.withControl("second") { acquired = true; finishSecond.await() }
        }
        finishFirst.complete(Unit); runCurrent()
        assertTrue(acquired)
        assertEquals("second", control.owner.value)
        assertTrue(runCatching { control.withControl("third") { fail("Competing effect") } }.exceptionOrNull() is ControlBusyException)
        finishSecond.complete(Unit)
        first.join(); second.join()
        assertNull(control.owner.value)
    }
}
