package app.vela.tv.design

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

/** A text-only overflowing body remains readable with a D-pad, even when its actions are outside it.
 * Descendant fields/buttons keep their own key handling; the scroll target handles keys only when
 * it itself has focus, and releases navigation at either end. No extra click target is introduced. */
@Composable private fun ScrollableDetails(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    var focused by remember { mutableStateOf(false) }
    val overflowing = scroll.maxValue > 0
    Column(modifier
        .velaFocusRing(focused, VelaShapes.Field, Vela.colors.focusRing,
            if (Vela.highContrast) VelaSize.HighContrastRing else VelaSize.ControlRing, VelaSize.ControlRingOffset)
        .onFocusChanged { focused = it.isFocused }
        .onKeyEvent { event ->
            val direction = when (event.key) { Key.DirectionUp -> -1; Key.DirectionDown -> 1; else -> 0 }
            val canScroll = if (direction < 0) scroll.canScrollBackward else scroll.canScrollForward
            if (focused && direction != 0 && canScroll && event.type == KeyEventType.KeyDown) {
                scope.launch { scroll.scrollBy(direction * (scroll.viewportSize * 0.65f).coerceAtLeast(1f)) }
                true
            } else false
        }
        .semantics { if (overflowing) stateDescription = "Scrollable details" }
        .focusable(overflowing)
        .verticalScroll(scroll).padding(VelaSpace.Xs),
        verticalArrangement = Arrangement.spacedBy(VelaSpace.S), content = content)
}

@Composable private fun DialogTitle(title: String, sheet: Boolean = false) {
    Text(title, style = if (sheet) Vela.type.headline else Vela.type.title, color = Vela.colors.ink,
        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
}

@Composable fun VelaDialog(title: String, onDismissRequest: () -> Unit, initialFocus: FocusRequester,
    modifier: Modifier = Modifier, actions: @Composable FlowRowScope.() -> Unit, content: @Composable ColumnScope.() -> Unit = {}) {
    Dialog(onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(modifier.padding(VelaSpace.L).widthIn(max = VelaSize.DialogWidth).fillMaxWidth()
            .heightIn(max = VelaSize.DialogHeight).velaPanel(Vela.colors).padding(VelaSpace.L)) {
            if (maxHeight < 300.dp || LocalDensity.current.fontScale > 1.3f) {
                // When chrome itself could exhaust the window, let everything grow and scroll.
                ScrollableDetails {
                    DialogTitle(title)
                    content()
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(VelaSpace.S), verticalArrangement = Arrangement.spacedBy(VelaSpace.S), content = actions)
                }
            } else Column(verticalArrangement = Arrangement.spacedBy(VelaSpace.M)) {
                DialogTitle(title)
                ScrollableDetails(Modifier.weight(1f, fill = false), content)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(VelaSpace.S), verticalArrangement = Arrangement.spacedBy(VelaSpace.S), content = actions)
            }
        }
        LaunchedEffect(initialFocus) { withFrameNanos { }; initialFocus.requestFocus() }
    }
}
@Composable fun VelaSheet(title: String, onDismissRequest: () -> Unit, initialFocus: FocusRequester,
    modifier: Modifier = Modifier, headerActions: @Composable FlowRowScope.() -> Unit, content: LazyListScope.() -> Unit) {
    Dialog(onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(modifier.padding(VelaSpace.L).widthIn(max = VelaSize.SheetWidth).fillMaxWidth()
            .heightIn(max = VelaSize.SheetHeight).velaPanel(Vela.colors).padding(VelaSpace.L)) {
            if (maxHeight < 300.dp || LocalDensity.current.fontScale > 1.3f) {
                LazyColumn(Modifier.focusRestorer(), contentPadding = PaddingValues(VelaSpace.S),
                    verticalArrangement = Arrangement.spacedBy(VelaSpace.M)) {
                    item("vela-sheet-actions") {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(VelaSpace.S), verticalArrangement = Arrangement.spacedBy(VelaSpace.S), content = headerActions)
                    }
                    item("vela-sheet-title") { DialogTitle(title, sheet = true) }
                    content()
                }
            } else Column(verticalArrangement = Arrangement.spacedBy(VelaSpace.M)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(VelaSpace.S), verticalArrangement = Arrangement.spacedBy(VelaSpace.S), content = headerActions)
                DialogTitle(title, sheet = true)
                LazyColumn(Modifier.weight(1f, fill = false).focusRestorer(), contentPadding = PaddingValues(VelaSpace.S),
                    verticalArrangement = Arrangement.spacedBy(VelaSpace.M), content = content)
            }
        }
        LaunchedEffect(initialFocus) { withFrameNanos { }; initialFocus.requestFocus() }
    }
}
@Composable fun ConfirmDialog(title: String, message: String, confirmLabel: String, onConfirm: () -> Unit,
    onDismiss: () -> Unit, danger: Boolean = false, cancelLabel: String = "Cancel") {
    val cancel = remember { FocusRequester() }
    VelaDialog(title, onDismiss, cancel, actions = {
        VelaButton(cancelLabel, Modifier.focusRequester(cancel), onClick = onDismiss)
        VelaButton(confirmLabel, variant = if (danger) ButtonVariant.Danger else ButtonVariant.Primary, onClick = onConfirm)
    }) { Text(message, style = Vela.type.body, color = Vela.colors.inkMuted, modifier = Modifier.readingWidth()) }
}
