package app.vela.tv.agent

import app.vela.tv.guard.GuardHit
import app.vela.tv.layout.HomeLayout
import app.vela.tv.settings.LayoutUndo

/** Capture the exact prior layout before approval. The caller owns the controller lease. */
internal fun prepareLayoutUndo(
    receipt: LayoutUndo?,
    current: () -> LayoutUndo?,
    classify: (HomeLayout) -> GuardHit?,
    restore: suspend (LayoutUndo) -> Unit,
): PreparedToolAction {
    if (receipt == null) return PreparedToolAction(null) {
        ToolOutcome("There is no previous Home layout to undo in this session. Restore defaults is available in Arrange home.", true)
    }
    return PreparedToolAction(classify(receipt.previous), { current() === receipt }) {
        // SettingsStore revalidates identity atomically with its verified write, including after IO dispatch.
        restore(receipt)
        ToolOutcome("Previous Home layout restored.")
    }
}
