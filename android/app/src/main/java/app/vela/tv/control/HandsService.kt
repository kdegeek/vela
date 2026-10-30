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

/**
 * The agent's hands: reads any app's screen and acts on it the way the remote would. Also owns the Home button (when
 * the owner turns that on), the step overlay, and the remote answer to "may I?" prompts.
 */
class HandsService : AccessibilityService() {

    private var nodes = HashMap<Int, AccessibilityNodeInfo>()
    private var pkg: String? = null
    private val main = Handler(Looper.getMainLooper())
    private var homeHeld: Runnable? = null
    private var armed: Int? = null
    lateinit var overlay: Overlay
        private set

    private val app get() = application as VelaApp

    private val probe = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val result = when (val cmd = intent.getStringExtra("cmd")) {
                "dump" -> snapshot()
                "press" -> press(intent.getIntExtra("id", -1))
                "dpad" -> dpad(intent.getStringExtra("dir") ?: "center")
                "ask" -> intent.getStringExtra("text")?.let { app.runner.ask(it); "asked" } ?: "no text"
                else -> "unknown cmd $cmd"
            }
            Log.i(TAG, "probe -> \n$result") // debug builds only: the receiver isn't registered otherwise
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
        val since = app.runner.pendingSince
        if (since != null) {
            val allow = when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A -> true
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B -> false
                else -> null
            }
            if (allow != null) {
                // Only a press that started after the prompt had been on screen a moment counts, so an OK already
                // on its way (or held) when the prompt appeared can't approve it.
                if (down && event.repeatCount == 0) armed = if (event.downTime >= since + PROMPT_GRACE_MS) event.keyCode else null
                else if (!down && armed == event.keyCode) {
                    armed = null
                    app.runner.answer(allow)
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

    /**
     * Presses a node from the last snapshot the way the remote would: focus it, then OK. Many TV screens (Leanback lists,
     * guided steps) only act on the select key, and treat a plain click as "move focus here". Without the global D-pad
     * actions (before Android 13), or for elements that can't take focus, it falls back to a click on the nearest
     * clickable ancestor.
     */
    @Synchronized
    fun press(id: Int): String {
        val node = pressTarget(id) ?: return "No element [$id]; read the screen again."
        if (Build.VERSION.SDK_INT >= 33 && node.isFocusable && node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) {
            Thread.sleep(150)
            if (performGlobalAction(GLOBAL_ACTION_DPAD_CENTER)) return "Pressed [$id]."
        }
        return if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) "Pressed [$id]." else "Pressing [$id] did nothing; try moving focus to it with the remote."
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
        private const val PROMPT_GRACE_MS = 400L

        @Volatile
        var current: HandsService? = null
            private set
    }
}
