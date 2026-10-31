package app.vela.tv.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp

internal fun Modifier.velaPanel(colors: VelaColors, agentic: Boolean = false): Modifier =
    background(if (agentic) colors.agentContainer else colors.surface, VelaShapes.Panel)
        .then(if (colors.highContrast) Modifier.border(1.dp, colors.lineStrong, VelaShapes.Panel) else Modifier)

@Composable fun StatusPanel(title: String, message: String, modifier: Modifier = Modifier,
    kind: StatusKind = StatusKind.Info, action: String? = null, onAction: () -> Unit = {},
    actionVariant: ButtonVariant = ButtonVariant.Secondary) {
    Column(modifier.fillMaxWidth().velaPanel(Vela.colors).padding(VelaSpace.L)
        .semantics { if (kind == StatusKind.Error || kind == StatusKind.Caution) liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(VelaSpace.S)) {
        Row(horizontalArrangement = Arrangement.spacedBy(VelaSpace.S), verticalAlignment = Alignment.CenterVertically) {
            Glyph(statusGlyph(kind.tone) ?: if (kind == StatusKind.Empty) VelaGlyph.Search else VelaGlyph.Activity,
                null, tint = Vela.colors.tone(kind.tone), size = VelaSize.GlyphStatus)
            Text(title, style = Vela.type.title, color = Vela.colors.ink, modifier = Modifier.semantics { heading() })
        }
        Text(message, style = Vela.type.body, color = Vela.colors.inkMuted, modifier = Modifier.readingWidth())
        if (kind == StatusKind.Loading) WorkingIndicator("Loading…", tone = StatusTone.Neutral)
        if (action != null) VelaButton(action, variant = actionVariant, onClick = onAction)
    }
}
@Composable fun InlineStatus(text: String, modifier: Modifier = Modifier, tone: StatusTone = StatusTone.Neutral, live: Boolean = true) {
    Row(modifier.then(if (live) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier),
        horizontalArrangement = Arrangement.spacedBy(VelaSpace.Xs), verticalAlignment = Alignment.CenterVertically) {
        statusGlyph(tone)?.let { Glyph(it, null, tint = Vela.colors.tone(tone)) }
        Text(text, style = Vela.type.body, color = Vela.colors.tone(tone), modifier = Modifier.readingWidth())
    }
}
@Composable fun WorkingIndicator(label: String, modifier: Modifier = Modifier, tone: StatusTone = StatusTone.Agent) {
    val c = Vela.colors
    Column(modifier.semantics { liveRegion = LiveRegionMode.Polite; stateDescription = "Working" },
        verticalArrangement = Arrangement.spacedBy(VelaSpace.Xs)) {
        Row(horizontalArrangement = Arrangement.spacedBy(VelaSpace.Xs), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(VelaSpace.Xs).background(c.tone(tone), CircleShape))
            Text(label, style = Vela.type.body, color = c.tone(tone), modifier = Modifier.readingWidth())
        }
        // Do not instantiate Material's infinite transition in reduced motion.
        if (!Vela.reducedMotion) LinearProgressIndicator(Modifier.fillMaxWidth(), color = c.tone(tone), trackColor = c.surfaceRaised)
    }
}
@Composable fun EmptyState(title: String, message: String, action: String? = null, onAction: () -> Unit = {}) =
    StatusPanel(title, message, kind = StatusKind.Empty, action = action, onAction = onAction)
@Composable fun LoadingState(message: String, modifier: Modifier = Modifier) = WorkingIndicator(message, modifier, StatusTone.Neutral)
