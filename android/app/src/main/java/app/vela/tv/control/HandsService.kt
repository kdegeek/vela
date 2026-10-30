package app.vela.tv.control

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import app.vela.tv.BuildConfig
import app.vela.tv.ProbeActivity

/**
 * The agent's hands: reads any app's screen and acts on it the way the remote would.
 *
 * Milestone 0 also answers three questions on a real box, through logcat (tag "VelaHands"):
 * does the Home key reach us, how much of each app's screen is readable, and do node clicks land.
 * In debug builds `adb shell am broadcast -a app.vela.tv.PROBE --es cmd <dump|click|focus|dpad|back|home-intercept> ...`
 * drives it.
 */
class HandsService : AccessibilityService() {

    private var nodes = HashMap<Int, AccessibilityNodeInfo>()
    private var homeIntercept = false
    private var lastPkg: String? = null

    private val probe = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val cmd = intent.getStringExtra("cmd") ?: return
            val result = when (cmd) {
                "dump" -> snapshot()
                "click" -> act(intent.getIntExtra("id", -1), AccessibilityNodeInfo.ACTION_CLICK)
                "focus" -> act(intent.getIntExtra("id", -1), AccessibilityNodeInfo.ACTION_FOCUS)
                "dpad" -> dpad(intent.getStringExtra("dir") ?: "center")
                "back" -> performGlobalAction(GLOBAL_ACTION_BACK).toString()
                "home-intercept" -> {
                    homeIntercept = intent.getBooleanExtra("on", true)
                    "home intercept $homeIntercept"
                }
                else -> "unknown cmd $cmd"
            }
            Log.i(TAG, "probe $cmd -> \n$result") // debug builds only: the receiver isn't registered otherwise
        }
    }

    override fun onServiceConnected() {
        Log.i(TAG, "connected sdk=${Build.VERSION.SDK_INT} model=${Build.MODEL}")
        if (BuildConfig.DEBUG) {
            val filter = IntentFilter(PROBE_ACTION)
            // Only senders holding DUMP (the adb shell has it; ordinary apps can't get it) may drive the probe.
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(probe, filter, PROBE_PERMISSION, null, RECEIVER_EXPORTED)
            else @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(probe, filter, PROBE_PERMISSION, null)
        }
    }

    override fun onDestroy() {
        if (BuildConfig.DEBUG) runCatching { unregisterReceiver(probe) }
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == lastPkg) return
        lastPkg = pkg
        if (BuildConfig.DEBUG) Log.i(TAG, "foreground $pkg (${event.className})")
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (BuildConfig.DEBUG) Log.i(TAG, "key ${KeyEvent.keyCodeToString(event.keyCode)} action=${event.action} repeat=${event.repeatCount} long=${event.isLongPress}")
        if (homeIntercept && event.keyCode == KeyEvent.KEYCODE_HOME) {
            if (event.action == KeyEvent.ACTION_UP) {
                startActivity(Intent(this, ProbeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
            }
            return true
        }
        return false
    }

    override fun onInterrupt() {}

    /** Copies the active window into [UiNode]s, numbering every node so the agent can point at one. */
    fun snapshot(): String {
        nodes.forEach { (_, n) -> runCatching { @Suppress("DEPRECATION") n.recycle() } }
        nodes = HashMap()
        val root = rootInActiveWindow
        var next = 0
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
                focused = n.isFocused || n.isAccessibilityFocused,
                selected = n.isSelected,
                checked = if (n.isCheckable) n.isChecked else null,
                editable = n.isEditable,
                scrollable = n.isScrollable,
                children = kids,
            )
        }
        val tree = root?.let { copy(it, 0) }
        return ScreenText.format(root?.packageName?.toString(), tree)
    }

    /** Clicks or focuses a node from the last snapshot; a click on a non-clickable node goes to its nearest clickable ancestor. */
    fun act(id: Int, action: Int): String {
        var n = nodes[id] ?: return "no node $id (take a fresh snapshot)"
        if (action == AccessibilityNodeInfo.ACTION_CLICK) {
            var p: AccessibilityNodeInfo? = n
            while (p != null && !p.isClickable) p = p.parent
            if (p != null) n = p
        }
        return "action $action on [$id] -> ${n.performAction(action)}"
    }

    /** D-pad moves: a global action on Android 13+, otherwise focus search from the focused node. */
    fun dpad(dir: String): String {
        if (Build.VERSION.SDK_INT >= 33) {
            val g = when (dir) {
                "up" -> GLOBAL_ACTION_DPAD_UP
                "down" -> GLOBAL_ACTION_DPAD_DOWN
                "left" -> GLOBAL_ACTION_DPAD_LEFT
                "right" -> GLOBAL_ACTION_DPAD_RIGHT
                else -> GLOBAL_ACTION_DPAD_CENTER
            }
            return "global dpad $dir -> ${performGlobalAction(g)}"
        }
        val focused = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return "nothing focused"
        if (dir == "center") return "click focused -> ${focused.performAction(AccessibilityNodeInfo.ACTION_CLICK)}"
        val d = when (dir) {
            "up" -> android.view.View.FOCUS_UP
            "down" -> android.view.View.FOCUS_DOWN
            "left" -> android.view.View.FOCUS_LEFT
            else -> android.view.View.FOCUS_RIGHT
        }
        val target = focused.focusSearch(d) ?: return "no element $dir of focus"
        return "focus $dir -> ${target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)}"
    }

    companion object {
        const val TAG = "VelaHands"
        const val PROBE_ACTION = "app.vela.tv.PROBE"
        const val PROBE_PERMISSION = "android.permission.DUMP"
    }
}
