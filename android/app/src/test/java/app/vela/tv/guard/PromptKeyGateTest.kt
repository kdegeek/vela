package app.vela.tv.guard

import org.junit.Assert.*
import org.junit.Test

class PromptKeyGateTest {
    @Test fun `key already underway or held when prompt appears cannot approve`() {
        val gate = PromptKeyGate()
        assertFalse(gate.accept("one", 1000, 1, true, false, 0, 1300))
        assertFalse(gate.accept("one", 1000, 1, false, true, 0, 1300))
        assertFalse(gate.accept("one", 1000, 1, true, false, 1, 1500))
        assertFalse(gate.accept("one", 1000, 1, false, true, 0, 1500))
    }
    @Test fun `key released against new prompt with identical uptime cannot approve`() {
        val gate = PromptKeyGate()
        assertFalse(gate.accept("one", 1000, 1, true, false, 0, 1500))
        assertFalse(gate.accept("two", 1000, 1, false, true, 0, 1500))
    }
    @Test fun `fresh matching key approves once and clearing prompt disarms it`() {
        val gate = PromptKeyGate()
        assertFalse(gate.accept("one", 1000, 1, true, false, 0, 1500))
        assertTrue(gate.accept("one", 1000, 1, false, true, 0, 1500))
        assertFalse(gate.accept("one", 1000, 1, false, true, 0, 1500))
        gate.accept("two", 2000, 2, true, false, 0, 2500)
        gate.clear()
        assertFalse(gate.accept("two", 2000, 2, false, true, 0, 2500))
    }
}
