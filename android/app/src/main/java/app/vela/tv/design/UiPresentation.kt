package app.vela.tv.design

/** Presentation rules kept independent of Android so malformed metadata cannot break layout. */
internal fun displayProgress(value: Float?): Float? = value?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)

internal enum class TitleContent { LOADING, ERROR, EPISODES, PLAYABLE, UNSUPPORTED }

internal fun titleContent(loading: Boolean, failed: Boolean, type: String?, missing: Boolean = false): TitleContent = when {
    loading -> TitleContent.LOADING
    failed -> TitleContent.ERROR
    missing -> TitleContent.UNSUPPORTED
    type == "Series" -> TitleContent.EPISODES
    type in setOf("Movie", "Episode", "Video") -> TitleContent.PLAYABLE
    else -> TitleContent.UNSUPPORTED
}


enum class StatusTone { Neutral, Agent, Success, Caution, Danger }
enum class StatusKind(val tone: StatusTone) {
    Info(StatusTone.Neutral), Empty(StatusTone.Neutral), Loading(StatusTone.Neutral),
    Success(StatusTone.Success), Caution(StatusTone.Caution), Error(StatusTone.Danger),
}
sealed interface AgentIndicator {
    data object Idle : AgentIndicator
    data class Working(val step: String?) : AgentIndicator
    data class NeedsApproval(val question: String) : AgentIndicator
}

fun agentIndicator(running: Boolean, pendingQuestion: String?, latestStep: String?): AgentIndicator {
    val question = pendingQuestion?.takeIf { it.isNotBlank() }
    return when {
        question != null -> AgentIndicator.NeedsApproval(question)
        running -> AgentIndicator.Working(latestStep?.takeIf { it.isNotBlank() })
        else -> AgentIndicator.Idle
    }
}
fun taskTone(state: String): StatusTone = when (state) {
    "running" -> StatusTone.Agent
    "complete" -> StatusTone.Success
    "failed" -> StatusTone.Danger
    else -> StatusTone.Neutral
}
fun taskStateLabel(state: String): String = state.replaceFirstChar { it.uppercaseChar() }
fun approvalInstruction(remoteAnswers: Boolean): String = if (remoteAnswers)
    "Press OK to allow once, or Back to decline." else "Answer on your paired phone."
fun statusGlyph(tone: StatusTone): VelaGlyph? = when (tone) {
    StatusTone.Neutral -> null
    StatusTone.Agent -> VelaGlyph.Activity
    StatusTone.Success -> VelaGlyph.Check
    StatusTone.Caution -> VelaGlyph.Caution
    StatusTone.Danger -> VelaGlyph.Error
}
