package app.vela.tv.control

/** A selected leaf and the ancestor that receives its click are both part of the approved target. */
internal class PressTarget<N : Any> private constructor(
    val selected: N,
    val dispatched: N,
    private val selectedLabel: String,
    private val dispatchedLabel: String,
) {
    val riskLabel: String = listOf(selectedLabel, dispatchedLabel).distinct().joinToString(" ")

    fun valid(labelOf: (N) -> String, dispatchedFor: (N) -> N?): Boolean =
        dispatchedFor(selected) == dispatched && labelOf(selected) == selectedLabel && labelOf(dispatched) == dispatchedLabel

    companion object {
        fun <N : Any> capture(selected: N, dispatched: N, labelOf: (N) -> String): PressTarget<N> =
            PressTarget(selected, dispatched, labelOf(selected), labelOf(dispatched))
    }
}
