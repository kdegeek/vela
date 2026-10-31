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
    Row(Modifier.fillMaxWidth().background(Vela.colors.canvas).safeHorizontal()
        .heightIn(min = VelaSize.TopBar).onFocusChanged { if (it.hasFocus) onFocused() },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(VelaSpace.Xs)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(VelaSpace.Xxs)) {
            Glyph(VelaGlyph.Sail, null, tint = Vela.colors.accent)
            Text("Vela", style = Vela.type.headline.copy(fontSize = 26.sp), color = Vela.colors.ink)
        }
        LazyRow(Modifier.weight(1f).focusRestorer(), state = navigation,
            contentPadding = PaddingValues(horizontal = VelaSpace.Xs, vertical = VelaSpace.Xxs), horizontalArrangement = Arrangement.spacedBy(VelaSpace.Xxs)) {
            items(Page.tabs, key = { it.name }) { page ->
                VelaTab(page.label, selected.tab == page, { onPage(page) }, Modifier.focusRequester(requesters.getValue(page))
                    .onFocusChanged { if (page == Page.HOME) onHomeTabFocus(it.isFocused) })
            }
        }
        ProfileChip(identity, onProfiles)
        AgentLantern(indicator, onAsk, { onPage(Page.ACTIVITY) })
    }
}

@Composable private fun ProfileChip(identity: String?, onClick: () -> Unit) {
    VelaFocusSurface(onClick, onClickLabel = "Open profiles", modifier = Modifier.semantics {
        contentDescription = identity?.let { "Profile: $it" } ?: "Profiles"
    }) { state ->
        val fill = if (state.pressed) Vela.colors.accentStrong else if (state.focused) Vela.colors.accent else Vela.colors.surfaceRaised
        val ink = if (state.focused || state.pressed) Vela.colors.onAccent else Vela.colors.ink
        Row(Modifier.heightIn(min = VelaSize.MinTarget).background(fill, VelaShapes.Control)
            .then(if (Vela.highContrast) Modifier.border(1.dp, Vela.colors.lineStrong, VelaShapes.Control) else Modifier)
            .padding(horizontal = VelaSpace.S, vertical = VelaSpace.Xs),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(VelaSpace.Xs)) {
            Box(Modifier.size(VelaSize.GlyphList).background(ink, VelaShapes.Control), contentAlignment = Alignment.Center) {
                Text(identity?.firstOrNull()?.uppercase() ?: "V", style = Vela.type.label, color = fill)
            }
            Text(identity?.take(14) ?: "Profiles", style = Vela.type.label, color = ink)
        }
    }
}
