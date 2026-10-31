package app.vela.tv.launcher

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import app.vela.tv.VelaApp
import app.vela.tv.design.*
import app.vela.tv.layout.*
import kotlinx.coroutines.*

internal const val OWNER_LAYOUT_CONTROLLER = "owner-layout"
internal suspend fun saveOwnerLayout(app: VelaApp, layout: HomeLayout) = withContext(Dispatchers.Main.immediate) {
    val checked = Layouts.decode(Layouts.encode(layout)).getOrThrow()
    app.runner.withController(OWNER_LAYOUT_CONTROLLER) {
        withContext(Dispatchers.IO) { app.settings.setLayout(checked) }
    }
}

@Composable internal fun ArrangeHome(app: VelaApp, back: () -> Unit) {
    val layout by app.settings.layout.collectAsState()
    val owner by app.control.owner.collectAsState()
    val running by app.runner.running.collectAsState()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var removeId by remember { mutableStateOf<String?>(null) }
    var defaults by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    val backFocus = remember { FocusRequester() }
    val defaultsFocus = remember { FocusRequester() }
    // This map outlives lazy item disposal during a move; identity is never the row index.
    val retainedRequests = remember { mutableMapOf<String, List<FocusRequester>>() }
    val rowIds = layout.rows.map { it.id }
    val requests = remember(rowIds) {
        rowIds.associateWith { id -> retainedRequests[id] ?: List(3) { FocusRequester() } }
    }
    SideEffect {
        retainedRequests.clear()
        retainedRequests.putAll(requests)
    }
    val unavailable = busy || running || owner != null
    fun edit(focusId: String?, button: Int = 0, transform: (HomeLayout) -> HomeLayout) {
        if (busy || app.runner.running.value || app.control.owner.value != null) return
        busy = true; failed = false; message = "Saving home…"
        scope.launch {
            try {
                // Evaluate at activation, never from a captured editor snapshot.
                val current = app.settings.layout.value
                val edited = transform(current)
                saveOwnerLayout(app, edited)
                message = "Home saved."
                if (focusId != null) {
                    val index = app.settings.layout.value.rows.indexOfFirst { it.id == focusId }
                    if (index >= 0) {
                        withFrameNanos { }
                        list.scrollToItem(index + 2) // page header + stable status slot
                        withFrameNanos { }
                        requests[focusId]?.get(button)?.requestFocus()
                    } else backFocus.requestFocus()
                } else { list.scrollToItem(0); withFrameNanos { }; backFocus.requestFocus() }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { failed = true; message = e.message ?: "Couldn't save your home. Review the current layout before trying again." }
            finally { busy = false }
        }
    }
    VelaPage("Arrange home", Modifier.fillMaxSize(), breadcrumb = "Settings", state = list,
        supporting = "Move or remove rows. Changes save immediately.", actions = {
            VelaButton("Back to Settings", Modifier.focusRequester(backFocus), onClick = back)
        }) {
        item("status") {
            if (running || owner != null) InlineStatus("Vela is working. Stop the current task to arrange home.", tone = StatusTone.Caution)
            else message?.let { InlineStatus(it, tone = if (failed) StatusTone.Danger else if (busy) StatusTone.Neutral else StatusTone.Success) }
        }
        itemsIndexed(layout.rows, key = { _, row -> ownerRowKey(row.id) }) { index, row ->
            Column(verticalArrangement = Arrangement.spacedBy(VelaSpace.Xs)) {
                VelaListRow(row.title, supporting = listOfNotNull(row.style.cardFormat().name, sourceLabel(row.source)).joinToString(" · "))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(VelaSpace.S), verticalArrangement = Arrangement.spacedBy(VelaSpace.S)) {
                    val focus = requests.getValue(row.id)
                    VelaButton("Move up", Modifier.focusRequester(focus[0]), enabled = !unavailable && index > 0, busy = busy) { edit(row.id, 0) { LayoutEdits.move(it, row.id, -1) } }
                    VelaButton("Move down", Modifier.focusRequester(focus[1]), enabled = !unavailable && index < layout.rows.lastIndex, busy = busy) { edit(row.id, 1) { LayoutEdits.move(it, row.id, 1) } }
                    VelaButton("Remove…", Modifier.focusRequester(focus[2]), enabled = !unavailable && layout.rows.size > 1, variant = ButtonVariant.Danger) { removeId = row.id }
                }
                if (layout.rows.size == 1) Text("Keep at least one row on your home.", style = Vela.type.meta, color = Vela.colors.inkSubtle)
            }
        }
        item("defaults") { VelaButton("Restore default layout…", Modifier.focusRequester(defaultsFocus), enabled = !unavailable) { defaults = true } }
    }
    removeId?.let { id ->
        ConfirmDialog("Remove row?", "Remove “${layout.rows.firstOrNull { it.id == id }?.title ?: "this row"}” from Home? Its apps and library are kept.", "Remove",
            onConfirm = { removeId = null; edit(null) { LayoutEdits.remove(it, id) } }, onDismiss = { removeId = null }, danger = true)
    }
    if (defaults) ConfirmDialog("Restore default layout?", "Replace your current rows with Apps, Continue watching, Recently added and My list?", "Restore defaults",
        onConfirm = { defaults = false; edit(null) { Layouts.default } }, onDismiss = { defaults = false })
}
