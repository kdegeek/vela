package app.vela.tv.control

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import app.vela.tv.BuildConfig
import app.vela.tv.VelaApp
import app.vela.tv.launcher.AskActivity
import app.vela.tv.launcher.HomeActivity
import app.vela.tv.agent.ActionInvalidatedException
import app.vela.tv.agent.ToolCall
import app.vela.tv.guard.Guard
import app.vela.tv.guard.PromptKeyGate
import kotlinx.coroutines.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The agent's hands: reads any app's screen and acts on it the way the remote would. Also owns the Home button (when
 * the owner turns that on), the step overlay, and the remote answer to "may I?" prompts.
 */
class HandsService : AccessibilityService() {

    private var nodes = HashMap<Int, AccessibilityNodeInfo>()
    private var pkg: String? = null
    private val main = Handler(Looper.getMainLooper())
    private var homeHeld: Runnable? = null
    private val promptKeys = PromptKeyGate()
    lateinit var overlay: Overlay
        private set

    private val app get() = application as VelaApp

    private val probe = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val result = when (val cmd = intent.getStringExtra("cmd")) {
                "dump" -> snapshot()
                "press" -> {
                    val id = intent.getIntExtra("id", -1)
                    submitProbe(ToolCall("probe", "screen_press", buildJsonObject { put("id", id) }))
                    "submitted guarded press"
                }
                "dpad" -> {
                    val dir = (intent.getStringExtra("dir") ?: "select").let { if (it == "center") "select" else it }
                    submitProbe(ToolCall("probe", "remote", buildJsonObject { put("key", dir) }))
                    "submitted guarded remote"
                }
                "ask" -> intent.getStringExtra("text")?.let { app.runner.ask(it); "asked" } ?: "no text"
                else -> "unknown cmd $cmd"
            }
            Log.i(TAG, "probe -> \n$result") // debug builds only: the receiver isn't registered otherwise
        }
    }

    private fun submitProbe(call: ToolCall) {
        if (!BuildConfig.DEBUG) return
        app.scope.launch {
            try { Log.i(TAG, "probe action -> ${app.runner.executeTool(call).content}") }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { Log.i(TAG, "probe action rejected; controller is busy or unavailable") }
        }
    }

    override fun onServiceConnected() {
        current = this
        overlay = Overlay(this)
        if (BuildConfig.DEBUG) {
            // Only senders holding DUMP (the adb shell has it; ordinary apps can't get it) may drive the probe.
            val filter = IntentFilter(PROBE_ACTION)
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(probe, filter, PROBE_PERMISSION, null, RECEIVER_EXPORTED)
            else @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(probe, filter, PROBE_PERMISSION, null)
        }
        app.runner.attach(this)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        current = null
        if (BuildConfig.DEBUG) runCatching { unregisterReceiver(probe) }
        overlay.hide()
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.packageName?.toString()?.let { pkg = it }
        }
    }

    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (BuildConfig.DEBUG) Log.i(TAG, "key ${KeyEvent.keyCodeToString(event.keyCode)} action=${event.action} pending=${app.runner.pending.value != null}")
        val down = event.action == KeyEvent.ACTION_DOWN
        val prompt = app.runner.session.pending.value
        val since = prompt?.since
        if (since == null) promptKeys.clear()
        if (since != null) {
            val allow = when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A -> true
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B -> false
                else -> null
            }
            if (allow != null) {
                // Only a press that started after the prompt had been on screen a moment counts, so an OK already
                // on its way (or held) when the prompt appeared can't approve it.
                if (promptKeys.accept(prompt.id, since, event.keyCode, down, event.action == KeyEvent.ACTION_UP,
                        event.repeatCount, event.downTime)) {
                    app.runner.answer(allow, prompt.id)
                }
                return true
            }
        }
        if (event.keyCode == KeyEvent.KEYCODE_HOME && app.settings.settings.value.ownHome) {
            // Short press: Vela's home. Hold: ask Vela.
            if (down && event.repeatCount == 0) {
                homeHeld = Runnable {
                    homeHeld = null
                    open(AskActivity::class.java)
                }.also { main.postDelayed(it, 600) }
            } else if (!down) {
                homeHeld?.let {
                    main.removeCallbacks(it)
                    homeHeld = null
                    goHome()
                }
            }
            return true
        }
        return false
    }

    fun goHome() = open(HomeActivity::class.java)

    private fun open(cls: Class<*>) {
        startActivity(Intent(this, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    val foreground: String? get() = rootInActiveWindow?.packageName?.toString() ?: pkg

    /** Copies the active window into numbered [UiNode]s and returns them as the agent's text. */
    @Synchronized
    fun snapshot(): String {
        nodes = HashMap()
        val root = rootInActiveWindow
        var next = 1
        fun copy(n: AccessibilityNodeInfo, depth: Int): UiNode {
            val id = next++
            nodes[id] = n
            val kids = if (depth > 40) emptyList() else (0 until n.childCount).mapNotNull { i -> n.getChild(i)?.let { copy(it, depth + 1) } }
            return UiNode(
                id = id,
                role = n.className?.toString()?.substringAfterLast('.') ?: "View",
                text = n.text?.toString(),
                desc = n.contentDescription?.toString(),
                viewId = n.viewIdResourceName,
                clickable = n.isClickable,
                focusable = n.isFocusable,
                focused = n.isFocused,
                selected = n.isSelected,
                checked = if (n.isCheckable) n.isChecked else null,
                editable = n.isEditable,
                scrollable = n.isScrollable,
                children = kids,
            )
        }
        val tree = root?.let { copy(it, 0) }
        return ScreenText.format(root?.packageName?.toString() ?: pkg, tree)
    }

    /** The node a press on [id] actually acts on: the element, or its nearest focusable or clickable ancestor. */
    private fun pressTarget(id: Int): AccessibilityNodeInfo? {
        val target = nodes[id] ?: return null
        return pressTarget(target)
    }

    private fun pressTarget(target: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var n: AccessibilityNodeInfo? = target
        while (n != null && !n.isFocusable && !n.isClickable) n = n.parent
        return n ?: target
    }

    /**
     * Everything a press on [id] would act on says: the element and the node really pressed, with their children's text
     * (a card's button often has no text of its own). The guard reads this, so it judges what gets pressed.
     */
    @Synchronized
    fun label(id: Int): String? {
        val target = nodes[id] ?: return null
        val pressed = pressTarget(id) ?: target
        return listOf(labelOf(target), labelOf(pressed)).distinct().joinToString(" ")
    }

    fun focusedLabel(): String? = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { labelOf(it) }

    private fun labelOf(n: AccessibilityNodeInfo, depth: Int = 0): String {
        val own = listOfNotNull(n.text?.toString(), n.contentDescription?.toString()).filter { it.isNotBlank() }
        val kids = if (depth > 3) emptyList() else (0 until n.childCount).mapNotNull { n.getChild(it)?.let { c -> labelOf(c, depth + 1) } }
        return (own + kids).filter { it.isNotBlank() }.distinct().joinToString(" ")
    }

    /** The Accessibility node's equality includes its source identity, not just a label or list position. */
    class PressBinding internal constructor(
        internal val target: PressTarget<AccessibilityNodeInfo>,
        internal val pkg: String?,
        internal val window: Int,
        internal val originalFocus: AccessibilityNodeInfo?,
        internal val select: Boolean,
    ) {
        internal val node get() = target.dispatched
        internal val label get() = target.riskLabel
    }

    fun bindPress(id: Int): PressBinding {
        val selected = nodes[id] ?: throw ActionInvalidatedException()
        return bind(selected, pressTarget(selected), false)
    }
    fun bindSelect(): PressBinding {
        val focused = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: throw ActionInvalidatedException()
        return bind(focused, focused, true)
    }

    private fun bind(selected: AccessibilityNodeInfo, node: AccessibilityNodeInfo, select: Boolean): PressBinding =
        PressBinding(PressTarget.capture(selected, node, ::labelOf), foreground, node.windowId,
            rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT), select)

    fun pressRisk(binding: PressBinding) = Guard.press(binding.label, binding.pkg)

    fun valid(binding: PressBinding, originalFocus: Boolean = true): Boolean {
        val root = rootInActiveWindow ?: return false
        return root.windowId == binding.window && root.packageName?.toString() == binding.pkg &&
            binding.node.refresh() && binding.node.windowId == binding.window &&
            binding.target.selected.refresh() && binding.target.selected.windowId == binding.window &&
            binding.target.valid(::labelOf) { selected -> if (binding.select) selected else pressTarget(selected) } &&
            (!originalFocus || root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) == binding.originalFocus)
    }

    /** All focus/dispatch operations are serialized with Android input on Main; settle is cancellable. */
    suspend fun press(binding: PressBinding, configurationValid: () -> Boolean = { true }): String = withContext(Dispatchers.Main.immediate) {
        currentCoroutineContext().ensureActive()
        if (!configurationValid() || !valid(binding)) throw ActionInvalidatedException()
        val node = binding.node
        if (binding.select) {
            if (rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) != node) throw ActionInvalidatedException()
            currentCoroutineContext().ensureActive()
            return@withContext if (Build.VERSION.SDK_INT >= 33) {
                if (performGlobalAction(GLOBAL_ACTION_DPAD_CENTER)) "Pressed select." else "Select did nothing."
            } else if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) "Pressed select." else "Select did nothing."
        }
        if (Build.VERSION.SDK_INT >= 33 && node.isFocusable && node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) {
            settleAndDispatch(
                valid = { configurationValid() && valid(binding, originalFocus = false) && rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) == node },
                dispatch = { performGlobalAction(GLOBAL_ACTION_DPAD_CENTER) },
            ).let { if (it) return@withContext "Pressed the selected element." }
        }
        currentCoroutineContext().ensureActive()
        if (!configurationValid() || !valid(binding, originalFocus = false)) throw ActionInvalidatedException()
        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) "Pressed the selected element."
        else "Press did nothing; read the screen again."
    }

    @Synchronized
    fun focus(id: Int): String {
        val n = nodes[id] ?: return "No element [$id]; read the screen again."
        return if (n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) "Focused [$id]." else "Couldn't focus [$id]."
    }

    @Synchronized
    fun type(id: Int, text: String): String {
        val n = nodes[id] ?: return "No element [$id]; read the screen again."
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        return if (n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) "Typed into [$id]." else "[$id] doesn't take text."
    }

    @Synchronized
    fun scroll(id: Int, forward: Boolean): String {
        val n = nodes[id] ?: return "No element [$id]; read the screen again."
        val a = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        return if (n.performAction(a)) "Scrolled [$id]." else "[$id] didn't scroll."
    }

    fun back(): String = if (performGlobalAction(GLOBAL_ACTION_BACK)) "Pressed Back." else "Back didn't work."

    /** D-pad moves: a global action on Android 13+, otherwise focus search from the focused element. */
    fun dpad(dir: String): String {
        if (Build.VERSION.SDK_INT >= 33) {
            val g = when (dir) {
                "up" -> GLOBAL_ACTION_DPAD_UP
                "down" -> GLOBAL_ACTION_DPAD_DOWN
                "left" -> GLOBAL_ACTION_DPAD_LEFT
                "right" -> GLOBAL_ACTION_DPAD_RIGHT
                else -> GLOBAL_ACTION_DPAD_CENTER
            }
            return if (performGlobalAction(g)) "Pressed $dir." else "Pressing $dir didn't work."
        }
        val focused = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: return "Nothing has focus here; press an element by number instead."
        if (dir == "center" || dir == "select") {
            return if (focused.performAction(AccessibilityNodeInfo.ACTION_CLICK)) "Pressed select." else "Select did nothing."
        }
        val d = when (dir) {
            "up" -> View.FOCUS_UP
            "down" -> View.FOCUS_DOWN
            "left" -> View.FOCUS_LEFT
            else -> View.FOCUS_RIGHT
        }
        val target = focused.focusSearch(d) ?: return "Nothing further $dir."
        return if (target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) "Moved $dir." else "Couldn't move $dir."
    }

    companion object {
        const val TAG = "VelaHands"
        const val PROBE_ACTION = "app.vela.tv.PROBE"
        const val PROBE_PERMISSION = "android.permission.DUMP"

        @Volatile
        var current: HandsService? = null
            private set
    }
}
