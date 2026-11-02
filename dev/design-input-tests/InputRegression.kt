package app.vela.tv.design

import android.view.KeyEvent as NativeKey
import androidx.compose.runtime.CompositionLocalMap
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.indirect.*
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyInputModifierNode
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.node.SemanticsModifierNode
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import java.lang.reflect.Proxy

// Only the platform carriers, private layout, and dispatch ROUTING are fixtures.
// All observation/authorization and Foundation event handling execute production bytecode.
private var assertions = 0
private fun expect(label: String, actual: Any?, expected: Any?) {
    assertions++
    check(actual == expected) { "$label: expected=$expected, actual=$actual" }
    println("  ASSERT $label = $expected")
}
private fun field(obj: Any, name: String, type: Class<*> = obj.javaClass) =
    type.getDeclaredField(name).also { it.isAccessible = true }

private val nodeClass = Class.forName("androidx.compose.foundation.ClickableNode")
private val abstractClass: Class<*> = requireNotNull(nodeClass.superclass)
private val bounds = IntSize(100, 100)

private class Harness {
    val gate = ActivationGate()
    val observer = ActivationInputNode(gate)
    var available = true
    var callbacks = 0
    var attempted = 0
    var accessible = 0
    private val callback: () -> Unit = {
        attempted++
        if (available && gate.takeClick()) callbacks++
    }
    val clickable: Modifier.Node = nodeClass.declaredConstructors.single { it.parameterCount == 8 }.let {
        it.isAccessible = true
        it.newInstance(null, null, false, true, "foundation", null, callback, null) as Modifier.Node
    }
    private val direct get() = clickable as PointerInputModifierNode
    private val indirect get() = clickable as IndirectPointerInputModifierNode
    private val keys get() = clickable as KeyInputModifierNode

    init {
        val layout = Class.forName("androidx.compose.ui.node.LayoutNode").getConstructor().newInstance()
        val config = object : ViewConfiguration {
            override val longPressTimeoutMillis = 500L
            override val doubleTapTimeoutMillis = 300L
            override val doubleTapMinTimeMillis = 40L
            override val touchSlop = 8f
        }
        val locals = Proxy.newProxyInstance(CompositionLocalMap::class.java.classLoader,
            arrayOf(CompositionLocalMap::class.java)) { _, method, args ->
            check(method.name == "get") { "unexpected composition local method ${method.name}" }
            when (args!![0]) {
                LocalViewConfiguration -> config
                LocalDensity -> Density(1f)
                LocalLayoutDirection -> LayoutDirection.Ltr
                else -> error("unexpected composition local ${args[0]}")
            }
        } as CompositionLocalMap
        field(layout, "compositionLocalMap").set(layout, locals)
        val nodes = requireNotNull(field(layout, "nodes").get(layout))
        val coordinator = field(nodes, "innerCoordinator").get(nodes)
        for (node in listOf(observer, clickable)) {
            field(node, "coordinator", Modifier.Node::class.java).set(node, coordinator)
            field(node, "isAttached", Modifier.Node::class.java).set(node, true)
        }
        // Bypass only gesture-coordination attachment to a real Owner/tree.
        // This does not replace any ClickableNode handler or state machine.
        field(clickable, "gestureNode", abstractClass).set(clickable, clickable)
    }

    fun availability(value: Boolean) { available = value; gate.availability(value) }
    fun focus(value: Boolean) {
        observer.onFocusEvent(object : FocusState {
            override val isFocused = value
            override val hasFocus = value
            override val isCaptured = false
        })
        abstractClass.getDeclaredMethod("onFocusChange", Boolean::class.javaPrimitiveType).also {
            it.isAccessible = true
        }.invoke(clickable, value)
        // FocusOwner's independent indirect-cancel dispatch is explicit in this fixture.
        if (!value) indirect.onCancelIndirectPointerInput()
    }

    fun native(action: Int, code: Int = 23, repeat: Int = 0, flags: Int = 0): KeyEvent {
        // JVM-only carrier constructor; production was compiled against SDK37, not this constructor.
        val event = NativeKey::class.java.getConstructor(Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .newInstance(action, code, repeat, flags)
        return KeyEvent(event)
    }
    fun key(action: Int, code: Int = 23, repeat: Int = 0, flags: Int = 0,
            intercept: Boolean = false): Boolean {
        val event = native(action, code, repeat, flags)
        check(!observer.onPreKeyEvent(event))
        check(!keys.onPreKeyEvent(event))
        if (intercept) return true // e.g. focused embedded child consumes the event
        val consumed = keys.onKeyEvent(event)
        // FocusOwner stops bubbling when Foundation consumes; don't invent a cleanup callback.
        if (!consumed) check(!observer.onKeyEvent(event))
        return consumed
    }
    fun keyTap(code: Int = 23) { key(0, code); key(1, code) }

    fun pointer(vararg changes: PointerInputChange, beforeMain: (() -> Unit)? = null,
                beforeFinal: (() -> Unit)? = null, dispatchClickable: Boolean = true) {
        val event = PointerEvent(changes.toList())
        observer.onPointerEvent(event, PointerEventPass.Initial, bounds)
        if (dispatchClickable) direct.onPointerEvent(event, PointerEventPass.Initial, bounds)
        beforeMain?.invoke()
        if (dispatchClickable) direct.onPointerEvent(event, PointerEventPass.Main, bounds)
        observer.onPointerEvent(event, PointerEventPass.Main, bounds)
        beforeFinal?.invoke()
        observer.onPointerEvent(event, PointerEventPass.Final, bounds)
        if (dispatchClickable) direct.onPointerEvent(event, PointerEventPass.Final, bounds)
    }
    fun pointerTap() { pointer(d()); pointer(u()) }
    fun cancelPointer() { direct.onCancelPointerInput(); observer.onCancelPointerInput() }

    fun indirect(vararg changes: IndirectPointerInputChange, beforeMain: (() -> Unit)? = null,
                 beforeFinal: (() -> Unit)? = null, dispatchClickable: Boolean = true) {
        val event = indirectEvent(changes.toList())
        observer.onIndirectPointerEvent(event, PointerEventPass.Initial)
        if (dispatchClickable) indirect.onIndirectPointerEvent(event, PointerEventPass.Initial)
        beforeMain?.invoke()
        if (dispatchClickable) indirect.onIndirectPointerEvent(event, PointerEventPass.Main)
        observer.onIndirectPointerEvent(event, PointerEventPass.Main)
        beforeFinal?.invoke()
        observer.onIndirectPointerEvent(event, PointerEventPass.Final)
        if (dispatchClickable) indirect.onIndirectPointerEvent(event, PointerEventPass.Final)
    }
    fun indirectTap() { indirect(i(false, true)); indirect(i(true, false)) }
    fun cancelIndirect() { indirect.onCancelIndirectPointerInput(); observer.onCancelIndirectPointerInput() }
    fun unusedReceipt(route: String) {
        when (route) {
            "key" -> { key(0, intercept = true); key(1, intercept = true) }
            "direct" -> {
                observer.onPointerEvent(PointerEvent(listOf(d())), PointerEventPass.Initial, bounds)
                observer.onPointerEvent(PointerEvent(listOf(u())), PointerEventPass.Initial, bounds)
            }
            "indirect" -> {
                observer.onIndirectPointerEvent(indirectEvent(listOf(i(false, true))), PointerEventPass.Initial)
                observer.onIndirectPointerEvent(indirectEvent(listOf(i(true, false))), PointerEventPass.Initial)
            }
            else -> error(route)
        }
    }
    fun semantics(selected: Boolean? = null): SemanticsConfiguration {
        val cfg = SemanticsConfiguration()
        // LayoutNode.calculateSemanticsConfiguration walks tail to head: clickable first.
        with(clickable as SemanticsModifierNode) { cfg.applySemantics() }
        cfg.productionSemantics(available, selected, "vela") { accessible++ }
        return cfg
    }
    fun noReceipt() = expect("no unused receipt", gate.takeClick(), false)
    fun count(expected: Int) = expect("authorized callbacks", callbacks, expected)
}

private fun p(was: Boolean, now: Boolean, id: Long = 1, consumed: Boolean = false,
              pos: Offset = Offset(20f, 20f), previous: Offset = Offset(20f, 20f)) =
    PointerInputChange(PointerId(id), 20, pos, now, 10, previous, was, consumed)
private fun d() = p(false, true)
private fun u(consumed: Boolean = false) = p(true, false, consumed = consumed)
private fun i(was: Boolean, now: Boolean, id: Long = 42, consumed: Boolean = false,
              pos: Offset = Offset.Zero) =
    IndirectPointerInputChange(PointerId(id), 20, pos, now, 1f, 10, Offset.Zero, was)
        .also { if (consumed) it.consume() }

private fun indirectEvent(changes: List<IndirectPointerInputChange>): IndirectPointerEvent =
    Proxy.newProxyInstance(IndirectPointerEvent::class.java.classLoader,
        arrayOf(IndirectPointerEvent::class.java)) { _, method, _ ->
        when {
            method.name == "getChanges" -> changes
            method.name.startsWith("getType") -> when {
                changes.any { it.pressed && !it.previousPressed } -> 1
                changes.all { !it.pressed } -> 2
                else -> 3
            }
            method.name.startsWith("getPrimaryDirectionalMotionAxis") -> 0
            else -> error("unexpected indirect event method ${method.name}")
        }
    } as IndirectPointerEvent

fun main(args: Array<String>) {
    Class.forName("androidx.compose.foundation.ComposeFoundationFlags")
        .getField("isInteractionSoundEffectOnClickEnabled").set(null, false)
    var cases = 0
    val failures = mutableListOf<String>()
    fun case(name: String, block: () -> Unit) {
        if (args.isNotEmpty() && !name.contains(args[0])) return
        cases++
        println("CASE $name")
        try { block(); println("PASS $name") }
        catch (e: Throwable) {
            failures += "$name: $e"
            println("FAIL $name: $e")
            e.printStackTrace(System.out)
        }
    }

    for (code in listOf(23, 66, 160, 62)) {
        case("key/$code/fresh-hold-repeat") {
            val h = Harness()
            expect("down handled", h.key(0, code), true)
            for (n in 1..5) h.key(0, code, n, if (n == 1) NativeKey.FLAG_LONG_PRESS else 0)
            h.count(0)
            expect("up handled", h.key(1, code), true)
            h.count(1); h.noReceipt()
            expect("duplicate up unhandled", h.key(1, code), false)
            h.count(1); h.keyTap(code); h.count(2)
        }
        case("key/$code/busy-focus-loss-orphan-repeat") {
            val h = Harness()
            h.availability(false); h.key(0, code); h.focus(false)
            h.availability(true); h.focus(true)
            h.key(0, code, 4, NativeKey.FLAG_LONG_PRESS); h.key(1, code)
            expect("Foundation attempted orphan-repeat click", h.attempted, 1)
            h.count(0); h.keyTap(code); h.count(1)
        }
        case("key/$code/availability-recovery") {
            val h = Harness()
            h.availability(false); h.key(0, code); h.availability(true)
            h.key(0, code, 2); h.key(1, code); h.count(0)
            h.key(0, code); h.availability(false); h.availability(true)
            h.key(1, code); h.count(0); h.keyTap(code); h.count(1)
        }
        for (flags in listOf(32, 256, 32 or 256)) case("key/$code/canceled-up-$flags") {
            val h = Harness(); h.key(0, code); h.key(1, code, flags = flags)
            expect("Foundation attempted canceled click", h.attempted, 1)
            h.count(0); h.noReceipt(); h.keyTap(code); h.count(1)
        }
    }
    case("key/focus-loss-without-repeat-and-repeat-only") {
        val h = Harness(); h.key(0); h.focus(false); h.focus(true); h.key(1); h.count(0)
        h.key(0, repeat = 1); h.key(1); h.count(0); h.keyTap(); h.count(1)
    }
    case("key/canceled-down-rejects") {
        val h = Harness(); h.key(0, flags = 32); h.key(1); h.count(0); h.keyTap(); h.count(1)
    }
    case("key/unrelated-shift-and-no-gamepad-claim") {
        val h = Harness()
        for (code in listOf(59, 96)) {
            expect("unhandled down $code", h.key(0, code), false)
            expect("unhandled up $code", h.key(1, code), false)
            h.noReceipt()
        }
        h.count(0); expect("Foundation has no button-A activation", h.attempted, 0)
    }
    case("direct/fresh-hold-repeat-and-availability") {
        val h = Harness(); h.pointer(d()); repeat(5) { h.pointer(p(true, true)) }; h.count(0)
        h.pointer(u()); h.count(1); h.noReceipt(); h.pointer(u()); h.count(1)
        h.availability(false); h.pointer(d()); h.availability(true); h.pointer(u()); h.count(1)
        h.pointer(d()); h.availability(false); h.availability(true); h.pointer(u()); h.count(1)
        h.pointerTap(); h.count(2)
    }
    case("direct/busy-two-pointer-first-up-shift-last-up") {
        val h = Harness(); h.availability(false); h.pointer(d())
        h.pointer(p(true, true), p(false, true, 2)); h.availability(true)
        h.pointer(u(), p(true, true, 2)); h.count(0); h.noReceipt()
        h.keyTap(59); h.pointer(p(true, false, 2)); h.count(0)
        expect("Foundation completed all-pointer gesture", h.attempted, 1)
        h.pointerTap(); h.count(1)
    }
    case("direct/ready-two-pointer-all-up") {
        val h = Harness(); h.pointer(d()); h.pointer(p(true, true), p(false, true, 2))
        h.pointer(u(), p(true, true, 2)); h.count(0); h.noReceipt()
        h.pointer(p(true, false, 2)); h.count(1); h.noReceipt()
    }
    for (kind in listOf("cancel", "synthetic-consumed-up", "drag", "out-of-bounds", "main-consumption", "final-consumption")) {
        case("direct/$kind-cannot-authorize-busy-indirect") {
            val h = Harness(); h.availability(false); h.indirect(i(false, true)); h.availability(true)
            h.pointer(d())
            when (kind) {
                "cancel" -> h.cancelPointer()
                "synthetic-consumed-up" -> { h.pointer(u(true)); h.cancelPointer() }
                "drag" -> h.pointer(p(true, true, consumed = true))
                "out-of-bounds" -> h.pointer(p(true, true, pos = Offset(200f, 200f)))
                "main-consumption" -> { val change = p(true, true); h.pointer(change, beforeMain = { change.consume() }) }
                "final-consumption" -> { val change = p(true, true); h.pointer(change, beforeFinal = { change.consume() }) }
            }
            h.pointer(u()); h.count(0); h.noReceipt()
            h.indirect(i(true, false)); h.count(0)
            h.pointerTap(); h.count(1); h.indirectTap(); h.count(2)
        }
    }
    case("direct/consumed-release-no-callback") {
        val h = Harness(); h.pointer(d()); val up = u()
        h.pointer(up, beforeMain = { up.consume() }); h.count(0); h.noReceipt()
        h.pointerTap(); h.count(1)
    }
    case("direct/focus-loss-does-not-cancel-independent-pointer") {
        val h = Harness(); h.pointer(d()); h.key(0); h.focus(false); h.pointer(u()); h.count(1)
        h.focus(true); h.key(1); h.count(1)
    }
    case("indirect/ready-tap-once-hold-and-availability") {
        val h = Harness(); h.indirect(i(false, true)); repeat(5) { h.indirect(i(true, true)) }; h.count(0)
        h.indirect(i(true, false)); h.count(1); h.noReceipt(); h.indirect(i(true, false)); h.count(1)
        h.availability(false); h.indirect(i(false, true)); h.availability(true); h.indirect(i(true, false)); h.count(1)
        h.indirect(i(false, true)); h.availability(false); h.availability(true); h.indirect(i(true, false)); h.count(1)
        h.indirectTap(); h.count(2)
    }
    for (kind in listOf("cancel", "focus-loss", "slop", "consumed-up", "main-consumption", "final-consumption")) {
        case("indirect/$kind-and-next-fresh") {
            val h = Harness(); h.indirect(i(false, true))
            when (kind) {
                "cancel" -> h.cancelIndirect()
                "focus-loss" -> { h.focus(false); h.focus(true) }
                "slop" -> h.indirect(i(true, true, pos = Offset(20f, 0f)))
                "consumed-up" -> h.indirect(i(true, false, consumed = true))
                "main-consumption" -> { val change = i(true, true); h.indirect(change, beforeMain = { change.consume() }) }
                "final-consumption" -> { val change = i(true, true); h.indirect(change, beforeFinal = { change.consume() }) }
            }
            h.indirect(i(true, false)); h.count(0); h.noReceipt(); h.indirectTap(); h.count(1)
        }
    }
    case("indirect/two-pointer-busy-with-shift") {
        val h = Harness(); h.availability(false); h.indirect(i(false, true))
        h.indirect(i(true, true), i(false, true, 43)); h.availability(true)
        h.indirect(i(true, false), i(true, true, 43)); h.noReceipt(); h.keyTap(59)
        h.indirect(i(true, false, 43)); h.count(0); h.indirectTap(); h.count(1)
    }
    for (route in listOf("key", "direct", "indirect")) {
        case("expiry/$route-positive-control-single-use-receipt") {
            val h = Harness(); h.unusedReceipt(route)
            expect("receipt exists before event cleanup", h.gate.takeClick(), true)
            h.noReceipt()
        }
        case("expiry/$route-unused-through-normal-passes") {
            val h = Harness()
            when (route) {
                "key" -> { h.key(0, intercept = true); h.key(1) }
                "direct" -> { h.pointer(d(), dispatchClickable = false); h.pointer(u(), dispatchClickable = false) }
                "indirect" -> { h.indirect(i(false, true), dispatchClickable = false); h.indirect(i(true, false), dispatchClickable = false) }
            }
            h.count(0); h.noReceipt(); h.pointerTap(); h.keyTap(); h.indirectTap(); h.count(3)
        }
    }
    for (route in listOf("direct", "indirect")) for (pass in listOf(PointerEventPass.Main, PointerEventPass.Final)) {
        case("expiry/$route-unused-$pass") {
            val h = Harness()
            if (route == "direct") {
                val down = PointerEvent(listOf(d())); val up = PointerEvent(listOf(u()))
                h.observer.onPointerEvent(down, PointerEventPass.Initial, bounds)
                h.observer.onPointerEvent(up, PointerEventPass.Initial, bounds)
                h.observer.onPointerEvent(up, pass, bounds)
            } else {
                h.observer.onIndirectPointerEvent(indirectEvent(listOf(i(false, true))), PointerEventPass.Initial)
                val up = indirectEvent(listOf(i(true, false)))
                h.observer.onIndirectPointerEvent(up, PointerEventPass.Initial)
                h.observer.onIndirectPointerEvent(up, pass)
            }
            h.noReceipt()
        }
    }
    // Leave a real observer-created receipt before its normal cleanup. Starting a different
    // route must clear it immediately, even when a consumer skips the outer bubble handler.
    for (from in listOf("key", "direct", "indirect")) for (to in listOf("key", "direct", "indirect")) {
        if (from == to) continue
        case("expiry/cross-route-$from-to-$to") {
            val h = Harness()
            h.unusedReceipt(from)
            when (to) {
                "key" -> h.observer.onPreKeyEvent(h.native(0, 59))
                "direct" -> h.observer.onPointerEvent(PointerEvent(listOf(d())), PointerEventPass.Initial, bounds)
                "indirect" -> h.observer.onIndirectPointerEvent(indirectEvent(listOf(i(false, true))), PointerEventPass.Initial)
            }
            h.noReceipt(); h.count(0)
        }
    }
    case("expiry/unused-direct-release-cannot-authorize-busy-indirect") {
        val h = Harness(); h.availability(false); h.indirect(i(false, true)); h.availability(true)
        h.pointer(d(), dispatchClickable = false); h.pointer(u(), dispatchClickable = false)
        h.indirect(i(true, false)); h.count(0); h.indirectTap(); h.count(1)
    }
    case("expiry/intercepted-key-release-cannot-authorize-busy-indirect") {
        val h = Harness(); h.availability(false); h.indirect(i(false, true)); h.availability(true)
        h.unusedReceipt("key")
        h.indirect(i(true, false)); h.count(0)
        expect("Foundation attempted busy indirect completion", h.attempted, 1)
        h.indirectTap(); h.count(1)
    }
    for (route in listOf("direct", "indirect")) case("$route/availability-changes-during-release") {
        val h = Harness()
        if (route == "direct") {
            h.pointer(d()); h.pointer(u(), beforeMain = { h.availability(false); h.availability(true) })
            h.count(0); h.pointerTap()
        } else {
            h.indirect(i(false, true))
            h.indirect(i(true, false), beforeMain = { h.availability(false); h.availability(true) })
            h.count(0); h.indirectTap()
        }
        h.count(1)
    }
    for (reset in listOf("density", "configuration", "reset", "detach", "gate-replacement")) {
        case("lifecycle/$reset-disarms-held-inputs") {
            val h = Harness(); h.pointer(d()); h.indirect(i(false, true))
            if (reset !in listOf("density", "configuration")) h.key(0)
            when (reset) {
                "density" -> h.observer.onDensityChange()
                "configuration" -> h.observer.onViewConfigurationChange()
                "reset" -> h.observer.onReset()
                "detach" -> h.observer.onDetach()
                "gate-replacement" -> h.observer.update(ActivationGate())
            }
            h.pointer(u()); h.indirect(i(true, false)); h.key(1); h.count(0); h.noReceipt()
            if (reset == "gate-replacement") h.observer.update(h.gate)
            h.keyTap(); h.pointerTap(); h.indirectTap(); h.count(3)
        }
    }
    case("semantics/outer-action-wins-current-enabled-separate-from-held-press") {
        val h = Harness(); h.availability(false); h.key(0)
        var cfg = h.semantics(true)
        expect("disabled semantics", cfg.contains(SemanticsProperties.Disabled), true)
        expect("selected semantics", cfg[SemanticsProperties.Selected], true)
        expect("disabled accessibility result", cfg[SemanticsActions.OnClick].action!!.invoke(), false)
        expect("disabled accessibility callbacks", h.accessible, 0)
        h.availability(true); cfg = h.semantics(false)
        expect("ready semantics not disabled", cfg.contains(SemanticsProperties.Disabled), false)
        expect("outer action label", cfg[SemanticsActions.OnClick].label, "vela")
        expect("ready accessibility result", cfg[SemanticsActions.OnClick].action!!.invoke(), true)
        expect("accessibility bypasses physical hold", h.accessible, 1)
        expect("Foundation action not invoked", h.attempted, 0)
        h.key(1); h.count(0); h.keyTap(); h.count(1)
        h.availability(false); cfg = h.semantics()
        expect("current disabled action returns false", cfg[SemanticsActions.OnClick].action!!.invoke(), false)
        expect("no additional accessibility callback", h.accessible, 1)
    }
    check(cases > 0) { "no cases matched ${args.toList()}" }
    println("RESULT cases=$cases assertions=$assertions failures=${failures.size}")
    failures.forEach { println("FAILED $it") }
    check(failures.isEmpty()) { "${failures.size} regression case(s) failed" }
}
