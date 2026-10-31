package app.vela.tv.launcher

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.vela.tv.VelaApp
import app.vela.tv.agent.ToolCall
import app.vela.tv.control.*
import app.vela.tv.design.LocalHighContrast
import app.vela.tv.design.VelaButton
import app.vela.tv.design.VelaDesign
import app.vela.tv.profile.HouseholdState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.UUID

/** A shell-level observer: navigation never tears it down; leaving the visible activity does. */
@Composable internal fun NowPlayingSurface(
    app: VelaApp,
    resume: Int,
    detailsOpen: Boolean,
    openDetails: () -> Unit,
    dismissDetails: () -> Unit,
) {
    val household by app.householdReady.collectAsState()
    val householdState = household?.state?.collectAsState()?.value
    val revision = (householdState as? HouseholdState.Ready)?.document?.revision
    val policyError = nowPlayingPolicyError(householdState)
    // Replacing the observer on a profile revision prevents a previous profile's cached metadata flashing.
    val observer = remember(app, household, revision, policyError) {
        NowPlayingObserver(app) { nowPlayingPolicyError(app.householdOrNull()?.state?.value) == null }
    }
    val observed by observer.state.collectAsState()
    val snapshot = if (policyError == null) observed else NowPlayingSnapshot()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(observer, lifecycle, policyError) {
        fun synchronize() {
            if (policyError == null && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) observer.start()
            else observer.stop()
        }
        val listener = LifecycleEventObserver { _, _ -> synchronize() }
        lifecycle.addObserver(listener)
        synchronize()
        onDispose { lifecycle.removeObserver(listener); observer.stop() }
    }
    LaunchedEffect(observer, resume) { observer.refresh() }
    val running by app.runner.running.collectAsState()
    val owner by app.control.owner.collectAsState()
    var sending by remember(observer) { mutableStateOf(false) }
    var feedback by remember(observer) { mutableStateOf<String?>(null) }
    var failed by remember(observer) { mutableStateOf(false) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val session = snapshot.sessions.firstOrNull { it.target.sessionId == selectedId }
        ?: snapshot.sessions.firstOrNull { it.target.phase.advances }
        ?: snapshot.sessions.firstOrNull()
    val busy = running || owner != null || sending
    val scope = rememberCoroutineScope()
    fun act(chosen: NowPlayingSession, action: NowPlayingAction, seekMs: Long? = null) {
        if (busy || sending) return
        // Check the live policy too: composition may not yet have processed a household update.
        val denial = nowPlayingPolicyError(app.householdOrNull()?.state?.value)
        if (denial != null) { feedback = denial; failed = true; observer.stop(); return }
        val target = observer.currentTarget(chosen.target.sessionId)
        val arguments = target?.let { NowPlayingCommands.arguments(it, action,
            app.runner.running.value || app.control.owner.value != null, seekMs) }
        if (arguments == null) {
            feedback = "Playback changed or this control is unavailable. Check the current session before trying again."
            failed = true
            return
        }
        sending = true
        feedback = null
        scope.launch {
            try {
                val result = app.runner.executeTool(
                    ToolCall(UUID.randomUUID().toString(), "media_control", arguments), controller = "owner-now-playing")
                feedback = "${chosen.appLabel}: ${NowPlayingCommands.feedback(result.isError, result.content)}"
                failed = result.isError
                observer.refresh()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                feedback = "The request could not be completed; its effect is unknown. Check playback before trying again."
                failed = true
            } finally { sending = false }
        }
    }

    // No extra top-level destination: active sessions have a persistent banner; Settings opens empty/recovery states.
    if (session != null || snapshot.access in setOf(NowPlayingAccess.PERMISSION, NowPlayingAccess.UNAVAILABLE)) {
        Surface(Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 8.dp),
            shape = VelaDesign.CardShape, color = MaterialTheme.colorScheme.surfaceVariant) {
            Row(Modifier.focusRestorer().padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (session != null) NowPlayingArtwork(session, Modifier.size(48.dp))
                Column(Modifier.weight(1f)) {
                    Text(session?.title ?: "Now Playing needs attention", maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium)
                    Text(session?.let { "${it.appLabel} · ${it.target.phase.label}" }
                        ?: if (snapshot.access == NowPlayingAccess.PERMISSION) "Allow notification access to see other apps' playback."
                        else "Media sessions are unavailable. Open details to retry.", maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    feedback?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                        color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                session?.let { current ->
                    val action = primaryAction(current.target.phase)
                    if (current.target.capabilities.supports(action, current.target.phase))
                        VelaButton(action.label, enabled = NowPlayingCommands.arguments(current.target, action, busy) != null) { act(current, action) }
                }
                VelaButton("Now Playing", onClick = openDetails)
            }
        }
    }

    if (detailsOpen) {
        val close = remember { FocusRequester() }
        LaunchedEffect(Unit) { close.requestFocus() }
        Dialog(onDismissRequest = dismissDetails, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxWidth(0.88f).heightIn(max = 560.dp), shape = VelaDesign.PanelShape) {
                Column(Modifier.verticalScroll(rememberScrollState()).focusRestorer().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Now Playing", Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.headlineMedium)
                        VelaButton("Back", Modifier.focusRequester(close), onClick = dismissDetails)
                    }
                    when {
                        policyError != null -> Text(policyError)
                        snapshot.access == NowPlayingAccess.PERMISSION -> {
                            Text("Allow Vela's notification access in Android settings to observe and control other apps' playback. Return here and choose Retry.")
                            VelaButton("Notification access", enabled = !busy) {
                                runCatching { app.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure {
                                    feedback = "Android could not open notification access. Use Android settings from Vela Settings."
                                    failed = true
                                }
                            }
                            VelaButton("Retry") { observer.refresh() }
                        }
                        snapshot.access == NowPlayingAccess.UNAVAILABLE -> {
                            Text("Android could not read media sessions. Playback in other apps may still be available.")
                            VelaButton("Retry") { observer.refresh() }
                        }
                        snapshot.access == NowPlayingAccess.LOADING -> LinearProgressIndicator(Modifier.fillMaxWidth())
                        session == null -> Text("No active media sessions. Start playback in a TV app, then return here. Apps must publish an Android media session to appear.")
                        else -> {
                            SessionChoices(snapshot.sessions, session) { selectedId = it.target.sessionId; feedback = null }
                            NowPlayingDetails(session, snapshot.nowElapsedMs, busy, ::act)
                        }
                    }
                    if (busy) Text(if (sending) "Sending playback request…" else "Controls are unavailable while another task is running.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    feedback?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
}

private fun primaryAction(phase: NowPlayingPhase) = if (phase.advances || phase in setOf(
    NowPlayingPhase.BUFFERING, NowPlayingPhase.CONNECTING)) NowPlayingAction.PAUSE else NowPlayingAction.PLAY

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun SessionChoices(sessions: List<NowPlayingSession>, selected: NowPlayingSession, choose: (NowPlayingSession) -> Unit) {
    if (sessions.size > 1) {
        Text("Playback apps", style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            sessions.forEach { session ->
                VelaButton("${session.appLabel.take(32)} · ${session.target.phase.label}",
                    selected = session.target.sessionId == selected.target.sessionId) { choose(session) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun NowPlayingDetails(session: NowPlayingSession, now: Long, busy: Boolean,
    act: (NowPlayingSession, NowPlayingAction, Long?) -> Unit,
) {
    val target = session.target
    val position = target.position.at(now)
    val duration = target.position.durationMs
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        NowPlayingArtwork(session, Modifier.size(96.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(session.title, style = MaterialTheme.typography.titleLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (session.subtitle.isNotBlank()) Text(session.subtitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${session.appLabel} · ${target.phase.label}", Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    session.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Text("${nowPlayingTime(position)} / ${nowPlayingTime(duration)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (position != null && duration != null && duration > 0)
        LinearProgressIndicator(progress = { (position.toDouble() / duration).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
    if (!target.uniquePackage) Text("This app has multiple sessions. Controls apply only to the selected session.")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(NowPlayingAction.PREVIOUS, primaryAction(target.phase), NowPlayingAction.STOP, NowPlayingAction.NEXT).forEach { action ->
            VelaButton(action.label, enabled = NowPlayingCommands.arguments(target, action, busy) != null) { act(session, action, null) }
        }
    }
    if (target.capabilities.actions and 256L != 0L) {
        Text(if (duration == null || position == null) "Seeking needs an app-reported position and duration."
            else "Seek in 10-second steps", color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Back 10s" to -10_000L, "Forward 10s" to 10_000L).forEach { (label, offset) ->
                val seek = position?.let { NowPlayingCommands.seekOffset(it, offset) }
                VelaButton(label, enabled = seek != null && NowPlayingCommands.arguments(target, NowPlayingAction.SEEK, busy, seek) != null) {
                    act(session, NowPlayingAction.SEEK, seek)
                }
            }
            VelaButton("Start", enabled = NowPlayingCommands.arguments(target, NowPlayingAction.SEEK, busy, 0) != null) {
                act(session, NowPlayingAction.SEEK, 0)
            }
        }
    }
    Text("Queue", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
    if (session.queue.isEmpty()) Text("This app has not shared a queue.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    else {
        session.queue.forEachIndexed { index, item -> Text("${index + 1}. ${if (item.current) "Current · " else ""}${item.title}",
            maxLines = 2, overflow = TextOverflow.Ellipsis) }
        Text(if (session.queueTruncated) "Showing the first 40 items. Use Previous and Next when the app supports them."
            else "Queue is read-only. Use Previous and Next when the app supports them.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun NowPlayingArtwork(session: NowPlayingSession, modifier: Modifier) {
    val art = session.artwork
    Box(modifier.clip(VelaDesign.CardShape).background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
        if (art != null && !art.isRecycled && !LocalHighContrast.current)
            Image(art.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Text("♪", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
