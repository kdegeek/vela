package app.vela.tv.launcher

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.vela.tv.design.*

/** A quiet scene and compact heading; content retains its existing keyed lazy items. */
@Composable internal fun GlassDestinationPage(
    title: String,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    inset: Boolean = true,
    breadcrumb: String? = null,
    actions: (@Composable FlowRowScope.() -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    Box(modifier) {
        VelaAmbientBackground(Modifier.matchParentSize())
        Column(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.weight(1f).fillMaxWidth().focusRestorer(), state = state,
                contentPadding = PaddingValues(horizontal = if (inset) VelaSpace.SafeHorizontal else 0.dp,
                    vertical = VelaSpace.S), verticalArrangement = Arrangement.spacedBy(VelaSpace.M)) {
                item("vela-page-header") {
                    Column(if (inset) Modifier else Modifier.safeHorizontal(), verticalArrangement = Arrangement.spacedBy(VelaSpace.Xs)) {
                        if (breadcrumb != null) Text(breadcrumb, style = Vela.type.meta, color = Vela.colors.inkMuted)
                        Text(title, style = Vela.type.title, color = Vela.colors.ink, modifier = Modifier.semantics { heading() })
                        if (actions != null) ManagementButtons(actions)
                    }
                }
                content()
            }
            if (footer != null) Box(Modifier.safeHorizontal().padding(bottom = VelaSpace.Xs)) { footer() }
        }
    }
}

/** Related controls share one reading material. The children keep their own flat controls. */
@Composable internal fun GlassManagementGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    VelaGlassSurface(modifier.fillMaxWidth(), dense = true) {
        Column(Modifier.fillMaxWidth().padding(VelaSpace.M), verticalArrangement = Arrangement.spacedBy(VelaSpace.S), content = content)
    }
}

/** At normal TV size the actions are outside the scrolling fields. At short/IME or enlarged-text
 * sizes the whole form scrolls, with the same safe actions also available at its beginning. */
@Composable internal fun GlassManagementEditor(
    title: String, actions: ManagementActions, close: () -> Unit, saveLabel: String,
    canSave: Boolean = true, save: () -> Unit, person: (@Composable () -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    val backFocus = Modifier.rememberManagementEntryFocus()
    actions.ConfirmationDialog()
    BoxWithConstraints(Modifier.fillMaxSize().imePadding()) {
        VelaAmbientBackground(Modifier.matchParentSize())
        val compact = maxHeight < 300.dp || LocalDensity.current.fontScale > 1.3f
        val sideBySide = person != null && maxWidth >= 760.dp && !compact
        @Composable fun EditorActions(entry: Boolean) {
            ManagementButtons {
                VelaButton("Back", if (entry) backFocus else Modifier, enabled = !actions.busy, variant = ButtonVariant.Quiet, onClick = close)
                VelaButton(if (actions.busy) "Saving…" else saveLabel, enabled = !actions.busy && canSave,
                    busy = actions.busy, variant = ButtonVariant.Primary, onClick = save)
            }
        }
        Row(Modifier.fillMaxSize().safeHorizontal().padding(vertical = VelaSpace.S), horizontalArrangement = Arrangement.spacedBy(VelaSpace.L)) {
            if (sideBySide) Column(Modifier.width(200.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(vertical = VelaSpace.S)) { person() }
            VelaGlassSurface(Modifier.weight(1f).fillMaxHeight(), dense = true) {
                if (compact) LazyColumn(Modifier.fillMaxSize().focusRestorer(), contentPadding = PaddingValues(VelaSpace.M),
                    verticalArrangement = Arrangement.spacedBy(VelaSpace.S)) {
                    item("editor-actions-start") { EditorActions(true) }
                    item("editor-heading") { Text(title, style = Vela.type.title, color = Vela.colors.ink, modifier = Modifier.semantics { heading() }) }
                    if (person != null) item("editor-person") { person() }
                    content()
                    item("editor-feedback") { actions.CompactFeedback() }
                    item("editor-actions-end") { EditorActions(false) }
                } else Column(Modifier.fillMaxSize().padding(VelaSpace.M), verticalArrangement = Arrangement.spacedBy(VelaSpace.Xs)) {
                    Text(title, style = Vela.type.title, color = Vela.colors.ink, modifier = Modifier.semantics { heading() })
                    LazyColumn(Modifier.weight(1f).fillMaxWidth().focusRestorer(), contentPadding = PaddingValues(VelaSpace.Xs),
                        verticalArrangement = Arrangement.spacedBy(VelaSpace.S)) {
                        if (person != null && !sideBySide) item("editor-person") { person() }
                        content()
                    }
                    HorizontalDivider(color = Vela.colors.line)
                    EditorActions(true)
                    actions.CompactFeedback()
                }
            }
        }
    }
}
