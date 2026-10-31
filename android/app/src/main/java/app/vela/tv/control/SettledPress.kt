package app.vela.tv.control

import app.vela.tv.agent.ActionInvalidatedException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/** Called on the input dispatcher. Never dispatch to a stale target or after Stop during focus settling. */
internal suspend fun settleAndDispatch(
    settleMs: Long = 150,
    valid: () -> Boolean,
    dispatch: () -> Boolean,
): Boolean {
    delay(settleMs)
    currentCoroutineContext().ensureActive()
    if (!valid()) throw ActionInvalidatedException()
    return dispatch()
}
