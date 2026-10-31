package app.vela.tv.guard

/** A key must start after grace and finish on the exact same prompt, even when prompt uptimes coincide. */
internal class PromptKeyGate(private val graceMs: Long = 400) {
    private var armed: Pair<Int, String>? = null
    fun clear() { armed = null }
    fun accept(promptId: String, since: Long, key: Int, down: Boolean, up: Boolean, repeats: Int, downTime: Long): Boolean {
        if (down && repeats == 0) {
            armed = if (downTime >= since && downTime - since >= graceMs) key to promptId else null
        } else if (up && armed == (key to promptId) && downTime >= since && downTime - since >= graceMs) {
            armed = null
            return true
        }
        return false
    }
}
