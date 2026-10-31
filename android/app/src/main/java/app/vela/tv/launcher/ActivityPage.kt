package app.vela.tv.launcher

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import app.vela.tv.VelaApp
import app.vela.tv.design.*
import java.text.DateFormat
import java.util.Date

@Composable internal fun ActivityPage(app: VelaApp, remoteAnswers: Boolean, ask: (String) -> Unit, openAsk: () -> Unit) {
    val tasks by app.runner.tasks.collectAsState()
    val busy by app.runner.running.collectAsState()
    val lines by app.runner.lines.collectAsState()
    val pending by app.runner.pending.collectAsState()
    VelaPage("Working for you", Modifier.fillMaxSize(), actions = {
        // The first content action is safe; entering Activity never focuses Stop.
        VelaButton("Ask something else", variant = ButtonVariant.Agent, onClick = openAsk)
        if (!busy) VelaButton("Clear activity", variant = ButtonVariant.Quiet) { app.runner.clear() }
    }) {
        if (busy) item("current") {
            AgentTaskPanel(tasks.lastOrNull { it.state == "running" }?.task ?: "Working for you",
                lines.lastOrNull()?.text, approvalQuestion = pending, remoteAnswers = remoteAnswers) {
                VelaButton("Ask something else", variant = ButtonVariant.Agent, onClick = openAsk)
                VelaButton("Stop task", variant = ButtonVariant.Danger) { app.runner.stop() }
            }
        } else pending?.let { question -> item("approval") { ApprovalNotice(question, remoteAnswers) } }
        if (tasks.isEmpty() && !busy) item("empty") { EmptyState("Nothing to catch up on", "Ask Vela to play a show, change your home screen, or help around the house. Activity stays on this device and clears when Vela restarts.") }
        items(tasks.asReversed(), key = { it.id }) { task ->
            Column(verticalArrangement = Arrangement.spacedBy(VelaSpace.Xs)) {
                val tone = taskTone(task.state)
                VelaListRow(task.task, supporting = listOfNotNull(taskStateLabel(task.state), task.detail).joinToString(" · "),
                    glyph = statusGlyph(tone) ?: VelaGlyph.Stop, tone = tone) {
                    if (task.state != "running") VelaButton("Ask again", variant = ButtonVariant.Agent) { ask(task.task) }
                }
                Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(task.startedAt)), style = Vela.type.numeric, color = Vela.colors.inkSubtle)
            }
        }
    }
}
