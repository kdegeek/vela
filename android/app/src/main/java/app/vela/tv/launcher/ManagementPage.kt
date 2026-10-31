package app.vela.tv.launcher

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import app.vela.tv.VelaApp
import app.vela.tv.design.*
import app.vela.tv.mcp.ExternalControlStatus
import app.vela.tv.runtime.OwnerManagement
import kotlinx.coroutines.*

private data class ManagementConfirmation(val text: String, val danger: Boolean, val work: suspend () -> Unit)

/** Page-owned work can be canceled; the facade retains process-owned execution/cleanup. */
@Stable internal class ManagementActions(private val scope: CoroutineScope) {
    var busy by mutableStateOf(false); private set
    var message by mutableStateOf(""); private set
    private var messageTone by mutableStateOf(StatusTone.Neutral)
    private var confirmation by mutableStateOf<ManagementConfirmation?>(null)
    fun run(work: suspend () -> Unit) {
        if (busy) return
        busy = true; message = "Working…"; messageTone = StatusTone.Neutral
        scope.launch {
            try { work(); message = "Request completed. Review the current state below."; messageTone = StatusTone.Success }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                message = (e.message ?: "Could not complete this action.") + " Reload before reapplying a change; your draft is retained."
                messageTone = StatusTone.Danger
            }
            finally { busy = false }
        }
    }
    fun confirm(text: String, work: suspend () -> Unit) = confirm(text, danger = false, work)
    fun confirm(text: String, danger: Boolean, work: suspend () -> Unit) {
        if (!busy) confirmation = ManagementConfirmation(text, danger, work)
    }
    @Composable fun Feedback() {
        StatusFeedback()
        ConfirmationDialog()
    }
    @Composable fun StatusFeedback() {
        if (message.isNotEmpty()) {
            if (busy) WorkingIndicator(message, tone = StatusTone.Neutral)
            else if (messageTone == StatusTone.Danger) ManagementError(message)
            else InlineStatus(message, tone = messageTone)
        }
    }
    @Composable fun ConfirmationDialog() {
        confirmation?.let { pending ->
            ConfirmDialog("Confirm owner action", pending.text, "Confirm",
                onConfirm = { confirmation = null; run(pending.work) },
                onDismiss = { confirmation = null }, danger = pending.danger)
        }
    }
}

@Composable internal fun rememberManagementActions(): ManagementActions {
    val scope = rememberCoroutineScope()
    return remember(scope) { ManagementActions(scope) }
}

@Composable internal fun ManagementList(title: String, actions: ManagementActions, content: LazyListScope.() -> Unit) {
    // A confirmation must remain composed even when the feedback row has scrolled off screen.
    actions.ConfirmationDialog()
    VelaPage(title, Modifier.fillMaxSize().imePadding(), breadcrumb = "Settings") {
        item("management-feedback") { actions.StatusFeedback() }
        content()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun ManagementButtons(content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(VelaSpace.S), verticalArrangement = Arrangement.spacedBy(VelaSpace.Xs), content = content)
}

@Composable internal fun ManagementField(label: String, value: String, enabled: Boolean, max: Int = 8000,
                                       multiline: Boolean = false, update: (String) -> Unit) {
    VelaTextField(value, update, label, Modifier.fillMaxWidth(), enabled = enabled, maxLength = max,
        singleLine = !multiline, minLines = if (multiline) 3 else 1, maxLines = if (multiline) 8 else 1)
}

/** Keep prose previews bounded; the shared dialog supplies a D-pad-scrollable full reading view. */
@Composable internal fun ManagementText(text: String, title: String = "Details") {
    var overflow by remember(text) { mutableStateOf(false) }
    var reading by remember(text) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(VelaSpace.Xs)) {
        Text(text, Modifier.readingWidth(), style = Vela.type.body, color = Vela.colors.inkMuted,
            maxLines = 4, overflow = TextOverflow.Ellipsis, onTextLayout = { overflow = it.hasVisualOverflow })
        if (overflow) VelaButton("Read more", variant = ButtonVariant.Quiet) { reading = true }
    }
    if (reading) {
        val back = remember { FocusRequester() }
        VelaDialog(title, { reading = false }, back, actions = {
            VelaButton("Back", Modifier.focusRequester(back)) { reading = false }
        }) { Text(text, Modifier.readingWidth(), style = Vela.type.body, color = Vela.colors.ink) }
    }
}

@Composable internal fun ManagementError(message: String) {
    Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(VelaSpace.Xs)) {
        InlineStatus("Needs attention", tone = StatusTone.Danger, live = false)
        ManagementText(message, "Needs attention")
    }
}

private class ManagementEntryFocus {
    val requester = FocusRequester()
    var requested = false
}

/** Request only when the safe control is actually placed, once per page/editor entry. */
@Composable internal fun Modifier.rememberManagementEntryFocus(): Modifier {
    val entry = remember { ManagementEntryFocus() }
    return this.then(remember(entry) {
        Modifier.focusRequester(entry.requester).onGloballyPositioned {
            if (!entry.requested) entry.requested = entry.requester.requestFocus()
        }
    })
}

@Composable internal fun OwnerControlStatus(management: OwnerManagement) {
    val owner by management.busy.collectAsState()
    val actions = rememberManagementActions()
    if (owner != null) {
        Column(verticalArrangement = Arrangement.spacedBy(VelaSpace.S)) {
            InlineStatus("Control busy: $owner", tone = StatusTone.Caution)
            VelaButton(if (actions.busy) "Stopping…" else "Stop current work", enabled = !actions.busy, busy = actions.busy, variant = ButtonVariant.Danger) {
                actions.run { management.stopAndJoin() }
            }
            actions.Feedback()
        }
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
        item("control-actions") { ManagementButtons {
            VelaButton("Refresh status", enabled = !actions.busy, glyph = VelaGlyph.Refresh) { actions.run { refresh() } }
            VelaButton("Enable external control…", enabled = !actions.busy && state?.credentialPresent == false) {
                actions.confirm("Enable external control and issue a dedicated credential? Share only with a trusted controller.") {
                    token = null; visible = false
                    val provision = management.enableExternalControl(true)
                    state = provision.status; token = provision.token
                }
            }
            VelaButton("Revoke and stop external work…", enabled = !actions.busy && state != null, variant = ButtonVariant.Danger) {
                actions.confirm("Disable external control, revoke its credential and drain external work?", danger = true) {
                    state = management.revokeExternalControl(); token = null; visible = false
                }
            }
        } }
        item("control-explanation") { ManagementText("Disabled by default. The MCP listener binds only TV loopback, 127.0.0.1:8766. A separately configured tunnel is required from another computer. Enabling does not create LAN or public access. External actions still require policy checks and approvals.", "External agent control") }
        item("control-owner") { OwnerControlStatus(management) }
        item("control-status") {
            Column(verticalArrangement = Arrangement.spacedBy(VelaSpace.S)) {
                VelaSectionHeader("Connection status")
                loadingError?.let { ManagementError(it) }
                val displayed = state
                if (displayed == null && loadingError == null) LoadingState("Loading control status…")
                displayed?.let {
                    VelaListRow(if (it.enabled) "Enabled" else "Disabled",
                        supporting = "${it.address} · ${if (it.credentialPresent) "credential present" else "no credential"}",
                        glyph = if (it.enabled) VelaGlyph.Check else VelaGlyph.Link,
                        tone = if (it.enabled) StatusTone.Success else StatusTone.Neutral)
                    it.error?.let { error -> ManagementError(error) }
                }
            }
        }
        token?.let { secret -> item("control-one-time-credential") {
            Column(verticalArrangement = Arrangement.spacedBy(VelaSpace.S)) {
                VelaSectionHeader("Dedicated credential")
                ManagementText("Shown only once. To issue a new credential, revoke this one, then enable external control again from the TV or paired phone.")
                VelaTextField(secret, {}, "Dedicated credential", Modifier.fillMaxWidth(), readOnly = true,
                    secret = true, revealSecret = visible)
                ManagementButtons {
                    VelaButton("Hide and discard") { token = null; visible = false }
                    VelaButton(if (visible) "Mask credential" else "Show credential") { visible = !visible }
                }
            }
        } }
    }
}
