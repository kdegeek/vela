package app.vela.tv.launcher

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.vela.tv.VelaApp
import app.vela.tv.design.*
import app.vela.tv.mcp.ExternalControlStatus
import app.vela.tv.runtime.OwnerManagement
import kotlinx.coroutines.*

/** Page-owned work can be canceled; the facade retains process-owned execution/cleanup. */
@Stable internal class ManagementActions(private val scope: CoroutineScope) {
    var busy by mutableStateOf(false); private set
    var message by mutableStateOf(""); private set
    private var confirmation by mutableStateOf<Pair<String, suspend () -> Unit>?>(null)
    fun run(work: suspend () -> Unit) {
        if (busy) return
        busy = true; message = "Working…"
        scope.launch {
            try { work(); message = "Request completed. Review the current state below." }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = (e.message ?: "Could not complete this action.") + " Reload before reapplying a change; your draft is retained." }
            finally { busy = false }
        }
    }
    fun confirm(text: String, work: suspend () -> Unit) { if (!busy) confirmation = text to work }
    @Composable fun Feedback() {
        if (message.isNotEmpty()) Text(message, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        confirmation?.let { (text, work) ->
            AlertDialog(onDismissRequest = { confirmation = null }, title = { Text("Confirm owner action") },
                text = { Text(text) }, confirmButton = { VelaButton("Confirm") { confirmation = null; run(work) } },
                dismissButton = { VelaButton("Cancel") { confirmation = null } })
        }
    }
}

@Composable internal fun rememberManagementActions(): ManagementActions {
    val scope = rememberCoroutineScope()
    return remember(scope) { ManagementActions(scope) }
}

@Composable internal fun ManagementList(title: String, actions: ManagementActions, content: LazyListScope.() -> Unit) {
    LazyColumn(Modifier.fillMaxSize().focusRestorer().imePadding(), state = rememberLazyListState(),
        contentPadding = PaddingValues(horizontal = 40.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium) }
        item { actions.Feedback() }
        content()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun ManagementButtons(content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

@Composable internal fun ManagementField(label: String, value: String, enabled: Boolean, max: Int = 8000,
                                       multiline: Boolean = false, update: (String) -> Unit) {
    OutlinedTextField(value, { update(it.take(max)) }, Modifier.fillMaxWidth(), label = { Text(label) },
        enabled = enabled, singleLine = !multiline, minLines = if (multiline) 3 else 1, maxLines = if (multiline) 8 else 1)
}

@Composable internal fun OwnerControlStatus(management: OwnerManagement) {
    val owner by management.busy.collectAsState()
    val actions = rememberManagementActions()
    if (owner != null) {
        ManagementButtons { Text("Control busy: $owner"); VelaButton("Stop current work", enabled = !actions.busy) { actions.run { management.stopAndJoin() } } }
        actions.Feedback()
    }
}

@Composable internal fun ExternalControlPage(app: VelaApp) {
    val management = app.management
    val actions = rememberManagementActions()
    var state by remember { mutableStateOf<ExternalControlStatus?>(null) }
    var loadingError by remember { mutableStateOf<String?>(null) }
    // A token is never saved across recreation or put in activity/log/status text.
    var token by remember { mutableStateOf<String?>(null) }
    var visible by remember { mutableStateOf(false) }
    suspend fun refresh() { state = management.externalControl(); loadingError = null }
    LaunchedEffect(Unit) { try { refresh() } catch (e: CancellationException) { throw e } catch (e: Exception) { loadingError = e.message ?: "Could not read control status." } }
    BackHandler(token != null) { token = null; visible = false }
    ManagementList("External agent control", actions) {
        item { Text("Disabled by default. The MCP listener binds only TV loopback, 127.0.0.1:8766. A separately configured tunnel is required from another computer. Enabling does not create LAN or public access. External actions still require policy checks and approvals.") }
        item { OwnerControlStatus(management) }
        item {
            Text(loadingError ?: state?.let { "${if (it.enabled) "Enabled" else "Disabled"} · ${it.address} · ${if (it.credentialPresent) "credential present" else "no credential"}${it.error?.let { error -> " · $error" }.orEmpty()}" } ?: "Loading control status…")
            ManagementButtons {
                VelaButton("Refresh status", enabled = !actions.busy) { actions.run { refresh() } }
                VelaButton("Enable external control…", enabled = !actions.busy && state?.credentialPresent == false) {
                    actions.confirm("Enable external control and issue a dedicated credential? Share only with a trusted controller.") {
                        token = null; visible = false
                        val provision = management.enableExternalControl(true)
                        state = provision.status; token = provision.token
                    }
                }
                VelaButton("Revoke and stop external work…", enabled = !actions.busy && state != null) {
                    actions.confirm("Disable external control, revoke its credential and drain external work?") {
                        state = management.revokeExternalControl(); token = null; visible = false
                    }
                }
            }
        }
        token?.let { secret -> item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Shown only once. To issue a new credential, revoke this one, then enable external control again from the TV or paired phone.")
                OutlinedTextField(secret, {}, Modifier.fillMaxWidth(), label = { Text("Dedicated credential") }, readOnly = true,
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation())
                ManagementButtons { VelaButton(if (visible) "Mask credential" else "Show credential") { visible = !visible }; VelaButton("Hide and discard") { token = null; visible = false } }
            }
        } }
    }
}
