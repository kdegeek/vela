package app.vela.tv.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

enum class ButtonVariant { Primary, Secondary, Agent, Danger, Quiet }

@Composable fun VelaButton(
    label: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    variant: ButtonVariant = ButtonVariant.Secondary,
    glyph: VelaGlyph? = null,
    busy: Boolean = false,
    onClick: () -> Unit,
) {
    VelaFocusSurface(onClick, modifier.then(if (busy) Modifier.semantics { stateDescription = "Working" } else Modifier),
        enabled = enabled && !busy, selected = selected, onClickLabel = label) { state ->
        ButtonBody(label, state, variant, glyph, selected, busy)
    }
}

@Composable private fun ButtonBody(label: String, state: VelaInteractionState, variant: ButtonVariant,
    glyph: VelaGlyph?, selected: Boolean, busy: Boolean = false, tab: Boolean = false) {
    val c = Vela.colors
    val active = state.enabled || busy
    val lit = state.focused && active
    val fill = when {
        !active -> if (c.highContrast) c.canvas else Color.Transparent
        state.pressed -> when (variant) {
            ButtonVariant.Agent -> c.agent
            ButtonVariant.Danger -> c.danger
            else -> c.accentStrong
        }
        lit -> when (variant) {
            ButtonVariant.Primary -> c.accentStrong
            ButtonVariant.Agent -> c.agent
            ButtonVariant.Danger -> c.danger
            else -> c.accent
        }
        variant == ButtonVariant.Primary -> c.accent
        variant == ButtonVariant.Agent -> c.agentContainer
        variant == ButtonVariant.Quiet -> if (c.highContrast) c.canvas else Color.Transparent
        else -> c.surfaceRaised
    }
    val ink = when {
        !active -> c.inkSubtle
        lit || state.pressed -> when (variant) {
            ButtonVariant.Agent -> c.onAgent
            ButtonVariant.Danger -> c.onDanger
            else -> c.onAccent
        }
        variant == ButtonVariant.Primary -> c.onAccent
        variant == ButtonVariant.Agent -> c.agent
        variant == ButtonVariant.Danger -> c.danger
        variant == ButtonVariant.Quiet && !selected -> c.inkMuted
        else -> c.ink
    }
    val fillAnimation by animateColorAsState(fill, velaTween(if (state.pressed) VelaMotion.PressMs else VelaMotion.FocusMs), label = "control fill")
    val inkAnimation by animateColorAsState(ink, velaTween(VelaMotion.FocusMs), label = "control label")
    val actualFill = if (Vela.reducedMotion) fill else fillAnimation
    val actualInk = if (Vela.reducedMotion) ink else inkAnimation
    val border = when {
        c.highContrast -> Modifier.border(1.dp, c.lineStrong, VelaShapes.Control)
        selected && !tab -> Modifier.border(2.dp, c.accent, VelaShapes.Control)
        else -> Modifier
    }
    Row(Modifier.heightIn(min = VelaSize.MinTarget).background(actualFill, VelaShapes.Control).then(border)
        .then(if (tab && selected) Modifier.drawBehind {
            val inset = VelaSpace.M.toPx()
            drawLine(if (state.focused) c.onAccent else c.accent,
                Offset(inset, size.height - 2.dp.toPx()), Offset(size.width - inset, size.height - 2.dp.toPx()), 3.dp.toPx())
        } else Modifier).padding(horizontal = VelaSpace.M, vertical = VelaSpace.S),
        horizontalArrangement = Arrangement.spacedBy(VelaSpace.Xs, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
        CompositionLocalProvider(LocalContentColor provides actualInk) {
            if (glyph != null) Glyph(glyph, null)
            Text(label, style = Vela.type.label, color = actualInk, textAlign = TextAlign.Center)
        }
    }
}

@Composable fun VelaTab(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    VelaFocusSurface(onClick, modifier, selected = selected, role = Role.Tab, onClickLabel = label) { state ->
        ButtonBody(label, state, ButtonVariant.Quiet, null, selected, tab = true)
    }
}
@Composable fun VelaChoiceChip(label: String, selected: Boolean, onClick: () -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true) {
    VelaFocusSurface(onClick, modifier, enabled, selected, role = Role.RadioButton, onClickLabel = label) { state ->
        ButtonBody(label, state, ButtonVariant.Secondary, if (selected) VelaGlyph.Check else null, selected)
    }
}
@Composable fun <T> VelaChoiceGroup(label: String, options: List<T>, selected: T, optionLabel: (T) -> String,
    onSelect: (T) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(VelaSpace.Xs)) {
        Text(label, color = Vela.colors.ink, style = Vela.type.section, modifier = Modifier.semantics { heading() })
        FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(VelaSpace.S),
            verticalArrangement = Arrangement.spacedBy(VelaSpace.S)) {
            options.forEach { option -> VelaChoiceChip(optionLabel(option), option == selected, { onSelect(option) }, enabled = enabled) }
        }
    }
}
