package app.vela.tv.design

import org.junit.Assert.*
import org.junit.Test

class ActivationGateTest {
    private val key = ActivationGate.Input.Key(23)
    private val pointer = ActivationGate.Input.Pointer
    private val indirect = ActivationGate.Input.Indirect

    private fun route(input: ActivationGate.Input) = when (input) {
        is ActivationGate.Input.Key -> ActivationGate.Route.Key
        ActivationGate.Input.Pointer -> ActivationGate.Route.Pointer
        ActivationGate.Input.Indirect -> ActivationGate.Route.Indirect
    }
    private fun ActivationGate.down(input: ActivationGate.Input, repeat: Boolean = false) {
        beginEvent(route(input)); begin(input, isRepeat = repeat); endEvent(route(input))
    }
    private fun ActivationGate.up(input: ActivationGate.Input) { beginEvent(route(input)); release(input) }

    @Test fun unavailablePressCannotActivateAfterBecomingAvailable() {
        for (input in listOf(key, pointer, indirect)) {
            val gate = ActivationGate()
            gate.availability(false)
            gate.down(input)
            gate.availability(true)
            gate.up(input)
            assertFalse(gate.takeClick())
            gate.down(input)
            gate.up(input)
            assertTrue(gate.takeClick())
        }
    }

    @Test fun losingAvailabilityInvalidatesTheWholePressEvenIfRestored() {
        for (input in listOf(key, pointer, indirect)) {
            val gate = ActivationGate()
            gate.down(input)
            gate.availability(false)
            gate.availability(true)
            gate.up(input)
            assertFalse(gate.takeClick())
        }
    }

    @Test fun keyRepeatsCannotRearmAnUnavailablePress() {
        val gate = ActivationGate()
        gate.availability(false)
        gate.down(key)
        gate.availability(true)
        repeat(10) { gate.down(key, repeat = true) }
        gate.up(key)
        assertFalse(gate.takeClick())
    }

    @Test fun releaseAndCancellationCannotCreateOrReplayAnAction() {
        val gate = ActivationGate()
        gate.up(key)
        assertFalse(gate.takeClick())
        gate.down(pointer)
        gate.cancel(pointer)
        gate.up(pointer)
        assertFalse(gate.takeClick())
        gate.down(key)
        gate.up(key)
        assertTrue(gate.takeClick())
        assertFalse(gate.takeClick())
    }

    @Test fun focusLossDisarmsKeysWithoutCancelingAnIndependentPointer() {
        val gate = ActivationGate()
        gate.down(key)
        gate.down(pointer)
        gate.cancelKeys()
        gate.up(key)
        assertFalse(gate.takeClick())
        gate.up(pointer)
        assertTrue(gate.takeClick())
    }

    @Test fun lossOfAvailabilityAfterReleaseStillRejectsDispatch() {
        val gate = ActivationGate()
        gate.down(pointer)
        gate.up(pointer)
        gate.availability(false)
        gate.availability(true)
        assertFalse(gate.takeClick())
    }

    @Test fun repeatsAfterFocusLossCannotStartANewPress() {
        val gate = ActivationGate()
        gate.availability(false)
        gate.down(key)
        gate.cancelKeys()
        gate.availability(true)
        gate.down(key, repeat = true)
        gate.up(key)
        assertFalse(gate.takeClick())
        gate.down(key)
        gate.up(key)
        assertTrue(gate.takeClick())
    }

    @Test fun anotherRouteCannotBorrowAReleaseReceipt() {
        val gate = ActivationGate()
        gate.down(key)
        gate.up(key)
        gate.beginEvent(ActivationGate.Route.Pointer)
        assertFalse(gate.takeClick())
        gate.down(pointer)
        gate.up(pointer)
        gate.beginEvent(ActivationGate.Route.Indirect)
        assertFalse(gate.takeClick())
    }

    @Test fun unusedReceiptsExpireWithTheirEvent() {
        for (input in listOf(key, pointer, indirect)) {
            val gate = ActivationGate()
            gate.down(input)
            gate.up(input)
            gate.endEvent(route(input))
            assertFalse(gate.takeClick())
        }
    }

    @Test fun overlappingKeyCannotMakeAnUnavailableGestureEligible() {
        for (input in listOf(pointer, indirect)) {
            val gate = ActivationGate()
            gate.availability(false)
            gate.down(input)
            gate.availability(true)
            gate.down(key)
            gate.up(key)
            assertTrue(gate.takeClick())
            gate.up(input)
            assertFalse(gate.takeClick())
        }
    }

    @Test fun consumptionInvalidatesTheGestureUntilItsLastRelease() {
        for (input in listOf(pointer, indirect)) {
            val gate = ActivationGate()
            gate.down(input)
            gate.invalidate(input)
            gate.begin(input)
            gate.up(input)
            assertFalse(gate.takeClick())
            gate.down(input)
            gate.up(input)
            assertTrue(gate.takeClick())
        }
    }

    @Test fun cancellationDoesNotLeaveACompletionForAnotherSource() {
        val gate = ActivationGate()
        gate.down(pointer)
        gate.up(pointer)
        gate.cancel(pointer)
        assertFalse(gate.takeClick())
        gate.down(indirect)
        gate.cancelAll()
        gate.up(indirect)
        assertFalse(gate.takeClick())
    }
}
