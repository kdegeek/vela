package app.vela.tv.design

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp

@Composable fun AgentLantern(indicator: AgentIndicator, onAsk: () -> Unit, onOpenActivity: () -> Unit, modifier: Modifier = Modifier) {
    val label = when (indicator) {
        AgentIndicator.Idle -> "Ask Vela"
        is AgentIndicator.Working -> "Working…"
        is AgentIndicator.NeedsApproval -> "Needs your OK"
    }
    VelaButton(label, modifier.semantics { liveRegion = LiveRegionMode.Polite }, variant = ButtonVariant.Agent,
        glyph = when (indicator) {
            AgentIndicator.Idle -> VelaGlyph.Sail
            is AgentIndicator.Working -> VelaGlyph.Activity
            is AgentIndicator.NeedsApproval -> VelaGlyph.Caution
        }, onClick = if (indicator == AgentIndicator.Idle) onAsk else onOpenActivity)
}
@Composable fun ApprovalNotice(question: String, remoteAnswers: Boolean, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().velaPanel(Vela.colors, agentic = true)
        .border(2.dp, if (Vela.highContrast) Vela.colors.lineStrong else Vela.colors.agent, VelaShapes.Panel)
        .padding(VelaSpace.M).semantics { liveRegion = LiveRegionMode.Assertive },
        verticalArrangement = Arrangement.spacedBy(VelaSpace.Xs)) {
        Row(horizontalArrangement = Arrangement.spacedBy(VelaSpace.Xs)) {
            Glyph(VelaGlyph.Caution, null, tint = Vela.colors.agent)
            Text("Vela needs your OK", style = Vela.type.section, color = Vela.colors.ink, modifier = Modifier.semantics { heading() })
        }
        Text(question, style = Vela.type.body, color = Vela.colors.ink, modifier = Modifier.readingWidth())
        Text(approvalInstruction(remoteAnswers), style = Vela.type.body, color = Vela.colors.inkMuted, modifier = Modifier.readingWidth())
    }
}
@Composable fun AgentTaskPanel(task: String, latestStep: String?, modifier: Modifier = Modifier,
    approvalQuestion: String? = null, remoteAnswers: Boolean = true, actions: @Composable RowScope.() -> Unit) {
    Column(modifier.fillMaxWidth().velaPanel(Vela.colors, agentic = true).padding(VelaSpace.L),
        verticalArrangement = Arrangement.spacedBy(VelaSpace.S)) {
        Text("Now", style = Vela.type.meta, color = Vela.colors.agent)
        Text(task, style = Vela.type.title, color = Vela.colors.ink, modifier = Modifier.semantics { heading() })
        WorkingIndicator(latestStep?.takeIf { it.isNotBlank() } ?: "Working…")
        if (!approvalQuestion.isNullOrBlank()) ApprovalNotice(approvalQuestion, remoteAnswers)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(VelaSpace.S), verticalArrangement = Arrangement.spacedBy(VelaSpace.S)) { actions() }
    }
}
