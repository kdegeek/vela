package app.vela.tv.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.foundation.border
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.*
import app.vela.tv.design.*

internal enum class Page(val label: String) {
    HOME("Home"), SEARCH("Search"), APPS("Apps"), LIST("My list"), HOUSE("House"), ACTIVITY("Activity"), SETTINGS("Settings"),
    PROFILES("Profiles"), ROUTINES("Routines"), CONTROL("External control"), ARRANGE("Arrange home");
    val isSettingsChild: Boolean get() = this in listOf(PROFILES, ROUTINES, CONTROL, ARRANGE)
    val tab: Page get() = if (isSettingsChild) SETTINGS else this
    companion object { val tabs = entries.take(7) }
}

@Composable internal fun TopBar(selected: Page, identity: String?, indicator: AgentIndicator,
    navigation: LazyListState, requesters: Map<Page, FocusRequester>, onPage: (Page) -> Unit,
    onProfiles: () -> Unit, onAsk: () -> Unit, onFocused: () -> Unit, onHomeTabFocus: (Boolean) -> Unit) {
    LaunchedEffect(selected.tab) {
        // Selection can also come from the profile chip or another page, without tab focus.
        navigation.scrollToItem(Page.tabs.indexOf(selected.tab))
    }
    VelaGlassSurface(Modifier.fillMaxWidth().safeHorizontal()) {
        Row(Modifier.fillMaxWidth().heightIn(min = VelaSize.TopBar)
            .padding(horizontal = VelaSpace.S, vertical = VelaSpace.Xxs)
            .onFocusChanged { if (it.hasFocus) onFocused() },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(VelaSpace.Xs)) {
            Glyph(VelaGlyph.Sail, "Vela", tint = Vela.colors.accent, modifier = Modifier.size(24.dp))
            LazyRow(Modifier.weight(1f).focusRestorer(), state = navigation,
                contentPadding = PaddingValues(horizontal = VelaSpace.Xxs),
                horizontalArrangement = Arrangement.spacedBy(VelaSpace.Xxs)) {
                items(Page.tabs, key = { it.name }) { page ->
                    ShellNavTab(page.label, selected.tab == page, { onPage(page) },
                        Modifier.focusRequester(requesters.getValue(page)).onFocusChanged {
                            if (page == Page.HOME) onHomeTabFocus(it.isFocused)
                        })
                }
            }
            ProfileChip(identity, onProfiles)
            Box(Modifier.width(1.dp).height(28.dp).background(Vela.colors.line))
            ShellAgentEntry(indicator, onAsk, { onPage(Page.ACTIVITY) })
        }
    }
}

@Composable private fun ProfileChip(identity: String?, onClick: () -> Unit) {
    VelaFocusSurface(onClick, onClickLabel = "Open profiles", modifier = Modifier.width(136.dp).semantics {
        contentDescription = identity?.let { "Profile: $it" } ?: "Profiles"
    }) { state ->
        val fill = when { state.pressed -> Vela.colors.accentStrong; state.focused -> Vela.colors.accent; else -> Color.Transparent }
        val ink = if (state.focused || state.pressed) Vela.colors.onAccent else Vela.colors.ink
        Row(Modifier.fillMaxWidth().heightIn(min = VelaSize.MinTarget).background(fill, VelaShapes.Control)
            .then(if (Vela.highContrast) Modifier.border(1.dp, Vela.colors.lineStrong, VelaShapes.Control) else Modifier)
            .padding(horizontal = VelaSpace.Xs, vertical = VelaSpace.Xxs),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(VelaSpace.Xs)) {
            Box(Modifier.size(VelaSize.GlyphList).background(ink.copy(alpha = .16f), VelaShapes.Control), contentAlignment = Alignment.Center) {
                Text(identity?.firstOrNull()?.uppercase() ?: "V", style = Vela.type.caption, color = ink)
            }
            Text(identity ?: "Profiles", style = Vela.type.meta, color = ink,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A fixed status slot keeps all navigation still when a task needs approval. */
@Composable private fun ShellAgentEntry(indicator: AgentIndicator, onAsk: () -> Unit, onActivity: () -> Unit) {
    val label = when (indicator) {
        AgentIndicator.Idle -> "Ask Vela"
        is AgentIndicator.Working -> "Working…"
        is AgentIndicator.NeedsApproval -> "Needs your OK"
    }
    val glyph = when (indicator) {
        AgentIndicator.Idle -> VelaGlyph.Sail
        is AgentIndicator.Working -> VelaGlyph.Activity
        is AgentIndicator.NeedsApproval -> VelaGlyph.Caution
    }
    VelaFocusSurface(if (indicator == AgentIndicator.Idle) onAsk else onActivity,
        Modifier.width(134.dp).semantics { liveRegion = LiveRegionMode.Polite }, onClickLabel = label) { state ->
        val fill = if (state.focused || state.pressed) Vela.colors.agent else Color.Transparent
        val ink = if (state.focused || state.pressed) Vela.colors.onAgent else Vela.colors.agent
        Row(Modifier.fillMaxWidth().heightIn(min = VelaSize.MinTarget).background(fill, VelaShapes.Control)
            .padding(horizontal = VelaSpace.Xs, vertical = VelaSpace.Xxs),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(VelaSpace.Xs)) {
            Glyph(glyph, null, tint = ink)
            Text(label, style = Vela.type.label.copy(fontSize = 14.sp), color = ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable private fun ShellNavTab(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    VelaFocusSurface(onClick, modifier, selected = selected, role = Role.Tab, onClickLabel = label) { state ->
        val colors = Vela.colors
        val lit = state.focused || state.pressed
        val ink = if (lit) colors.onAccent else if (selected) colors.ink else colors.inkMuted
        Box(Modifier.heightIn(min = VelaSize.MinTarget)
            .background(if (lit) colors.accent else Color.Transparent, VelaShapes.Control)
            .then(if (selected) Modifier.drawBehind {
                drawLine(if (lit) colors.onAccent else colors.accent,
                    Offset(6.dp.toPx(), size.height - 2.dp.toPx()),
                    Offset(size.width - 6.dp.toPx(), size.height - 2.dp.toPx()), 2.dp.toPx())
            } else Modifier).padding(horizontal = 6.dp, vertical = VelaSpace.Xs), contentAlignment = Alignment.Center) {
            Text(label, style = Vela.type.label.copy(fontSize = 14.5.sp), color = ink, maxLines = 1)
        }
    }
}
