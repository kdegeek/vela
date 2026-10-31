package app.vela.tv.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable private fun ListBody(title: String, supporting: String?, glyph: VelaGlyph?, tone: StatusTone,
    enabled: Boolean, focused: Boolean, trailing: (@Composable RowScope.() -> Unit)?) {
    val c = Vela.colors
    Row(Modifier.fillMaxWidth().heightIn(min = VelaSize.ListRow)
        .background(if (focused) c.surfaceFocused else if (c.highContrast) c.canvas else androidx.compose.ui.graphics.Color.Transparent, VelaShapes.Card)
        .then(if (c.highContrast) Modifier.border(1.dp, c.lineStrong, VelaShapes.Card) else Modifier)
        .padding(VelaSpace.S), horizontalArrangement = Arrangement.spacedBy(VelaSpace.M), verticalAlignment = Alignment.CenterVertically) {
        if (glyph != null) Glyph(glyph, null, tint = c.tone(tone), size = VelaSize.GlyphList)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(VelaSpace.Xxs)) {
            // Status text on focused fills uses ink; the glyph and status word preserve the state.
            Text(title, style = Vela.type.section, color = if (!enabled && !focused) c.inkSubtle else c.ink,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (supporting != null) Text(supporting, style = Vela.type.body, color = c.inkMuted, modifier = Modifier.readingWidth())
        }
        trailing?.invoke(this)
    }
}
@Composable fun VelaListRow(title: String, modifier: Modifier = Modifier, supporting: String? = null,
    glyph: VelaGlyph? = null, tone: StatusTone = StatusTone.Neutral, onClick: (() -> Unit)? = null,
    enabled: Boolean = true, trailing: (@Composable RowScope.() -> Unit)? = null) {
    if (onClick != null) VelaFocusSurface(onClick, modifier.fillMaxWidth(), enabled = enabled,
        treatment = FocusTreatment.Row, shape = VelaShapes.Card, onClickLabel = title) { state ->
        ListBody(title, supporting, glyph, tone, enabled, state.focused, trailing)
    } else Box(modifier) { ListBody(title, supporting, glyph, tone, enabled, false, trailing) }
}
@Composable fun VelaToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier, supporting: String? = null, enabled: Boolean = true) {
    VelaFocusSurface({ onCheckedChange(!checked) }, modifier.fillMaxWidth().semantics {
        toggleableState = if (checked) androidx.compose.ui.state.ToggleableState.On else androidx.compose.ui.state.ToggleableState.Off
        stateDescription = if (checked) "On" else "Off"
    }, enabled = enabled, treatment = FocusTreatment.Row, shape = VelaShapes.Card, role = Role.Switch, onClickLabel = label) { state ->
        ListBody(label, supporting, null, StatusTone.Neutral, enabled, state.focused) {
            if (checked) Glyph(VelaGlyph.Check, null, tint = Vela.colors.accent)
            Text(if (checked) "On" else "Off", style = Vela.type.label, color = Vela.colors.ink)
        }
    }
}
