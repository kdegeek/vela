package app.vela.tv.design

/** A held press cannot become eligible when a busy/disabled control becomes available. */
internal class ActivationGate {
    enum class Route { Key, Pointer, Indirect }
    sealed interface Input {
        data class Key(val code: Long) : Input
        data object Pointer : Input
        data object Indirect : Input
    }

    private val Input.route: Route get() = when (this) {
        is Input.Key -> Route.Key
        Input.Pointer -> Route.Pointer
        Input.Indirect -> Route.Indirect
    }

    private data class Press(val input: Input, val revision: Long, val eligible: Boolean)
    private var available = true
    private var revision = 0L
    private val held = mutableMapOf<Input, Press>()
    private var completed: Press? = null
    private var eventRoute: Route? = null

    fun availability(value: Boolean) {
        if (available && !value) {
            revision++
            completed = null
        }
        available = value
    }

    /** A completion is usable only during its synchronous platform event dispatch. */
    fun beginEvent(route: Route) {
        eventRoute = route
        completed = null
    }

    fun endEvent(route: Route) {
        if (eventRoute == route) {
            completed = null
            eventRoute = null
        }
    }

    fun begin(input: Input, isRepeat: Boolean = false) {
        // Key repeats retain the original press, including its eligibility.
        // A repeat arriving after focus loss must never become a new physical press.
        held.putIfAbsent(input, Press(input, revision, available && !isRepeat))
    }

    fun release(input: Input) {
        val press = held.remove(input)
        completed = press?.takeIf { input.route == eventRoute }
    }

    fun invalidate(input: Input) {
        held[input]?.let { held[input] = it.copy(eligible = false) }
        if (completed?.input == input) completed = null
    }

    fun cancel(input: Input) {
        held.remove(input)
        if (completed?.input == input) completed = null
    }

    fun cancelKeys() {
        held.keys.removeAll { it is Input.Key }
        if (completed?.input is Input.Key) completed = null
    }

    fun cancelAll() {
        held.clear()
        completed = null
        eventRoute = null
    }

    fun takeClick(): Boolean {
        val press = completed
        completed = null
        return available && press?.eligible == true && press.revision == revision && press.input.route == eventRoute
    }
}
