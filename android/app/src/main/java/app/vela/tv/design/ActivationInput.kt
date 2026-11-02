package app.vela.tv.design

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.indirect.IndirectPointerEvent
import androidx.compose.ui.input.indirect.IndirectPointerInputChange
import androidx.compose.ui.input.indirect.IndirectPointerInputModifierNode
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyInputModifierNode
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.isOutOfBounds
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.requireDensity
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.IntSize

internal fun Modifier.guardActivation(gate: ActivationGate): Modifier = this then ActivationElement(gate)

private data class ActivationElement(val gate: ActivationGate) : ModifierNodeElement<ActivationInputNode>() {
    override fun create() = ActivationInputNode(gate)
    override fun update(node: ActivationInputNode) = node.update(gate)
    override fun InspectorInfo.inspectableProperties() { name = "guardActivation" }
}

/** Observes input around Foundation's clickable without replacing its focus or gesture handling. */
internal class ActivationInputNode(private var gate: ActivationGate) : Modifier.Node(),
    KeyInputModifierNode, PointerInputModifierNode, IndirectPointerInputModifierNode,
    FocusEventModifierNode, CompositionLocalConsumerModifierNode {
    private var pointerDown: PointerInputChange? = null
    private var indirectDown: IndirectPointerInputChange? = null

    fun update(value: ActivationGate) {
        if (gate !== value) { reset(); gate = value }
    }

    override fun onPreKeyEvent(event: KeyEvent): Boolean {
        gate.beginEvent(ActivationGate.Route.Key)
        val native = event.nativeKeyEvent
        if (native.keyCode !in activationKeys) return false
        val input = ActivationGate.Input.Key(native.keyCode.toLong())
        val canceled = native.flags and (AndroidKeyEvent.FLAG_CANCELED or AndroidKeyEvent.FLAG_CANCELED_LONG_PRESS) != 0
        when (native.action) {
            AndroidKeyEvent.ACTION_DOWN -> if (canceled) gate.cancel(input)
                else gate.begin(input, isRepeat = native.repeatCount > 0)
            AndroidKeyEvent.ACTION_UP -> if (canceled) gate.cancel(input) else gate.release(input)
        }
        return false
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        // Unhandled releases cannot leave receipts for another input source.
        gate.endEvent(ActivationGate.Route.Key)
        return false
    }

    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) {
        val input = ActivationGate.Input.Pointer
        val changes = pointerEvent.changes
        if (pass == PointerEventPass.Initial) {
            gate.beginEvent(ActivationGate.Route.Pointer)
            if (pointerDown == null && changes.any { it.pressed && !it.previousPressed }) {
                pointerDown = changes.first { it.pressed && !it.previousPressed }
                gate.begin(input)
            }
            if (pointerDown != null && changes.any { it.isConsumed || outside(it, bounds) }) gate.invalidate(input)
            if (changes.isNotEmpty() && changes.none { it.pressed }) {
                gate.release(input)
                pointerDown = null
            }
        } else {
            // Foundation consumes its own down. A later consumed change cancels the gesture.
            if (pointerDown != null && changes.any { (it.isConsumed && it !== pointerDown) || outside(it, bounds) }) gate.invalidate(input)
            // Main bubbles outward after clickable has dispatched; Final also expires unused work.
            gate.endEvent(ActivationGate.Route.Pointer)
        }
    }

    override fun onIndirectPointerEvent(event: IndirectPointerEvent, pass: PointerEventPass) {
        val input = ActivationGate.Input.Indirect
        val changes = event.changes
        if (pass == PointerEventPass.Initial) {
            gate.beginEvent(ActivationGate.Route.Indirect)
            if (indirectDown == null && changes.any { it.pressed && !it.previousPressed }) {
                indirectDown = changes.first { it.pressed && !it.previousPressed }
                gate.begin(input)
            }
            if (indirectDown != null && changes.any { it.isConsumed || indirectMoved(it) }) gate.invalidate(input)
            if (changes.isNotEmpty() && changes.none { it.pressed }) {
                gate.release(input)
                indirectDown = null
            }
        } else {
            if (indirectDown != null && changes.any { (it.isConsumed && it !== indirectDown) || indirectMoved(it) }) gate.invalidate(input)
            gate.endEvent(ActivationGate.Route.Indirect)
        }
    }

    private fun outside(change: PointerInputChange, bounds: IntSize): Boolean {
        val minimum = currentValueOf(LocalViewConfiguration).minimumTouchTargetSize
        val padding = with(requireDensity()) {
            Size(((minimum.width.toPx() - bounds.width) / 2).coerceAtLeast(0f),
                ((minimum.height.toPx() - bounds.height) / 2).coerceAtLeast(0f))
        }
        return change.isOutOfBounds(bounds, padding)
    }

    private fun indirectMoved(change: IndirectPointerInputChange): Boolean = indirectDown?.let {
        (change.position - it.position).getDistance() > currentValueOf(LocalViewConfiguration).touchSlop
    } ?: false

    override fun onCancelPointerInput() { gate.cancel(ActivationGate.Input.Pointer); pointerDown = null }
    override fun onCancelIndirectPointerInput() { gate.cancel(ActivationGate.Input.Indirect); indirectDown = null }
    override fun onFocusEvent(focusState: FocusState) {
        if (!focusState.isFocused) { gate.cancelKeys(); onCancelIndirectPointerInput() }
    }
    override fun onDensityChange() { onCancelPointerInput(); onCancelIndirectPointerInput() }
    override fun onViewConfigurationChange() { onCancelPointerInput(); onCancelIndirectPointerInput() }
    override fun onReset() = reset()
    override fun onDetach() = reset()
    private fun reset() { gate.cancelAll(); pointerDown = null; indirectDown = null }

    private companion object {
        val activationKeys = setOf(AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER,
            AndroidKeyEvent.KEYCODE_NUMPAD_ENTER, AndroidKeyEvent.KEYCODE_SPACE)
    }
}
