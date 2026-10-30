package app.vela.tv.control

/** One element of an app's screen, copied out of the accessibility tree so it can be formatted and tested off-device. */
data class UiNode(
    val id: Int,
    val role: String,
    val text: String? = null,
    val desc: String? = null,
    val viewId: String? = null,
    val clickable: Boolean = false,
    val focusable: Boolean = false,
    val focused: Boolean = false,
    val selected: Boolean = false,
    val checked: Boolean? = null,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val children: List<UiNode> = emptyList(),
)

/** Turns a screen into the compact text the agent reads: one line per element that says or does something. */
object ScreenText {
    fun format(pkg: String?, root: UiNode?, maxLines: Int = 250): String {
        val out = StringBuilder("app: ${pkg ?: "unknown"}\n")
        if (root == null) return out.append("(screen not readable)").toString()
        var lines = 0
        var cut = false
        fun walk(n: UiNode, depth: Int) {
            if (lines >= maxLines) {
                cut = true
                return
            }
            val shown = informative(n)
            if (shown) {
                out.append("  ".repeat(depth)).append(line(n)).append('\n')
                lines++
            }
            for (c in n.children) walk(c, if (shown) depth + 1 else depth)
        }
        walk(root, 0)
        if (lines == 0) out.append("(no readable elements: the app draws its own screen)\n")
        if (cut) out.append("… (more elements not shown)\n")
        return out.toString().trimEnd()
    }

    private fun informative(n: UiNode): Boolean =
        !n.text.isNullOrBlank() || !n.desc.isNullOrBlank() || n.clickable || n.editable || n.scrollable || n.focused

    fun line(n: UiNode): String {
        val sb = StringBuilder("[${n.id}] ${n.role}")
        n.text?.takeIf { it.isNotBlank() }?.let { sb.append(" \"").append(clip(it)).append('"') }
        n.desc?.takeIf { it.isNotBlank() && it != n.text }?.let { sb.append(" (").append(clip(it)).append(')') }
        n.viewId?.substringAfter(":id/")?.takeIf { it.isNotBlank() }?.let { sb.append(" #").append(it) }
        val flags = buildList {
            if (n.focused) add("focused")
            if (n.selected) add("selected")
            n.checked?.let { add(if (it) "checked" else "unchecked") }
            if (n.clickable) add("click")
            if (n.editable) add("edit")
            if (n.scrollable) add("scroll")
        }
        if (flags.isNotEmpty()) sb.append(" {").append(flags.joinToString(",")).append('}')
        return sb.toString()
    }

    private fun clip(s: String): String {
        val one = s.replace(Regex("\\s+"), " ").trim()
        return if (one.length > 120) one.take(117) + "…" else one
    }
}
