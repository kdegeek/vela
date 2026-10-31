package app.vela.tv.launcher

import androidx.compose.ui.Modifier

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import app.vela.tv.VelaApp
import app.vela.tv.design.*
import app.vela.tv.routines.*
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private fun instantLabel(at: Long?): String = at?.let { DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(it)) } ?: "none / exhausted"

internal fun routineManagementTone(state: RoutineRunState): StatusTone = when (state) {
    RoutineRunState.RUNNING -> StatusTone.Agent
    RoutineRunState.SUCCEEDED -> StatusTone.Success
    RoutineRunState.FAILED -> StatusTone.Danger
    RoutineRunState.QUEUED, RoutineRunState.CANCELING, RoutineRunState.INTERRUPTED, RoutineRunState.MISSED -> StatusTone.Caution
    RoutineRunState.CANCELED -> StatusTone.Neutral
}

@Composable internal fun RoutinesPage(app: VelaApp) {
    val management = app.management
    val actions = rememberManagementActions()
    val requests = remember { RoutineRequestKeys() }
    var snapshot by remember { mutableStateOf<RoutineSnapshot?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf(false) }
    var original by remember { mutableStateOf<Routine?>(null) }
    suspend fun refresh() { snapshot = management.routines(); error = null }
    LaunchedEffect(Unit) {
        try {
            val engine = app.routineEngine()
            engine.state.collect { snapshot = it; error = null }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "Could not initialize routines." }
    }
    if (editing) {
        key(original?.id, original?.revision) {
            RoutineEditor(original, actions, { editing = false }) { spec ->
                actions.run {
                    val current = original
                    if (current == null) management.createRoutine(spec) else management.updateRoutine(current.id, current.revision, spec)
                    editing = false; refresh()
                }
            }
        }
        return
    }
    ManagementList("Durable routines", actions) {
        item("routine-actions") { ManagementButtons {
            VelaButton("Refresh routines & history", enabled = !actions.busy, glyph = VelaGlyph.Refresh) { actions.run { refresh() } }
            VelaButton("New routine", enabled = !actions.busy && snapshot != null, variant = ButtonVariant.Primary) { original = null; editing = true }
        } }
        item("routine-explanation") { ManagementText("Routines submit normal tasks with the same approvals and restrictions. Each is bound to the profile and policy/preferences saved with it. If that binding changes or another profile is active, execution fails closed: deliberately edit and Save to rebind. Android background wakes are inexact.", "Routine behavior") }
        item("routine-owner") { OwnerControlStatus(management) }
        error?.let { item("routine-error") { ManagementError(it) } }
        if (snapshot == null && error == null) item("routine-loading") { LoadingState("Loading durable routines…") }
        snapshot?.let { displayed ->
            item("routine-heading") { VelaSectionHeader("Saved routines") }
            if (displayed.routines.isEmpty()) item("routine-empty") { EmptyState("No routines saved", "Create a normal task and choose when it should run.") }
            items(displayed.routines, key = { "routine:${it.id}" }) { routine ->
                Column(verticalArrangement = Arrangement.spacedBy(VelaSpace.S)) {
                    VelaListRow(routine.spec.name, supporting = if (routine.spec.enabled) "Enabled" else "Disabled", glyph = VelaGlyph.Activity)
                    ManagementText(routine.spec.task, routine.spec.name)
                    ManagementText("Schedule: ${routine.spec.schedule}", "Routine schedule")
                    Text("Next: ${instantLabel(routine.nextRunAt)}", style = Vela.type.numeric, color = Vela.colors.inkMuted)
                    ManagementButtons {
                        VelaButton("Edit / rebind", enabled = !actions.busy) { original = routine; editing = true }
                        VelaButton(if (routine.spec.enabled) "Disable" else "Enable", enabled = !actions.busy) { actions.run { management.enableRoutine(routine.id, routine.revision, !routine.spec.enabled); refresh() } }
                        val operation = "run:${routine.id}"
                        VelaButton(if (requests.uncertain(operation)) "Retry same run request" else "Run now", enabled = !actions.busy) {
                            actions.run { management.runRoutine(routine.id, requests.key(operation)); requests.accepted(operation); refresh() }
                        }
                        VelaButton("Delete…", enabled = !actions.busy, variant = ButtonVariant.Danger) { actions.confirm("Delete ${routine.spec.name} and cancel its pending work? Durable run history stays.", danger = true) { management.deleteRoutine(routine.id, routine.revision); refresh() } }
                    }
                    HorizontalDivider(color = Vela.colors.line)
                }
            }
            item("routine-history-heading") {
                Column(verticalArrangement = Arrangement.spacedBy(VelaSpace.S)) {
                    VelaSectionHeader("Run history")
                    ManagementText("QUEUED waits for control; CANCELING waits for cleanup. FAILED / INTERRUPTED need review. A lost acknowledgement keeps the same request key for Retry. Refresh history before retrying an unknown result.", "Run history states")
                }
            }
            if (displayed.history.isEmpty()) item("routine-history-empty") { EmptyState("No run history", "No durable run history yet.") }
            items(displayed.history.asReversed(), key = { "run:${it.id}" }) { run ->
                val tone = routineManagementTone(run.state)
                Column(verticalArrangement = Arrangement.spacedBy(VelaSpace.S)) {
                    VelaListRow(run.name, supporting = run.state.name.lowercase().replaceFirstChar { it.titlecase() },
                        glyph = statusGlyph(tone) ?: VelaGlyph.Stop, tone = tone)
                    Text("Queued ${instantLabel(run.queuedAt)}\nStarted ${instantLabel(run.startedAt)}\nFinished ${instantLabel(run.finishedAt)}",
                        style = Vela.type.numeric, color = Vela.colors.inkMuted)
                    run.detail?.let { ManagementText(it, "Run details · ${run.name}") }
                    ManagementButtons {
                        // A canceled or uncertain request never becomes a new request through restyling.
                        if (!run.state.terminal) VelaButton("Cancel run", enabled = !actions.busy, variant = ButtonVariant.Danger) { actions.run { management.cancelRoutineRun(run.id); refresh() } }
                        if (displayed.routines.any { it.id == run.routineId }) {
                            val operation = "restart:${run.id}"
                            VelaButton(if (requests.uncertain(operation)) "Retry same restart" else "Restart current definition…", enabled = !actions.busy) {
                                actions.confirm("Cancel this routine's pending work and enqueue its current definition? The earlier task must finish cleanup first. An unknown previous request reuses its existing key.") {
                                    management.restartRoutineRun(run.id, requests.key(operation)); requests.accepted(operation); refresh()
                                }
                            }
                        }
                    }
                    HorizontalDivider(color = Vela.colors.line)
                }
            }
        }
    }
}

@Composable private fun RoutineEditor(original: Routine?, actions: ManagementActions, close: () -> Unit, save: (RoutineSpec) -> Unit) {
    val spec = original?.spec
    val schedule = spec?.schedule
    var name by rememberSaveable { mutableStateOf(spec?.name.orEmpty()) }
    var task by rememberSaveable { mutableStateOf(spec?.task.orEmpty()) }
    var kind by rememberSaveable { mutableStateOf(when (schedule) { is RoutineSchedule.Interval -> "interval"; is RoutineSchedule.Weekly -> "weekly"; is RoutineSchedule.Monthly -> "monthly"; else -> "once" }) }
    var instant by rememberSaveable { mutableStateOf(when (schedule) { is RoutineSchedule.Once -> Instant.ofEpochMilli(schedule.at).toString(); is RoutineSchedule.Interval -> Instant.ofEpochMilli(schedule.firstAt).toString(); else -> "" }) }
    var interval by rememberSaveable { mutableStateOf(((schedule as? RoutineSchedule.Interval)?.everyMillis?.div(60_000L) ?: 60).toString()) }
    var zone by rememberSaveable { mutableStateOf(when (schedule) { is RoutineSchedule.Weekly -> schedule.zone; is RoutineSchedule.Monthly -> schedule.zone; else -> ZoneId.systemDefault().id }) }
    var time by rememberSaveable { mutableStateOf(when (schedule) { is RoutineSchedule.Weekly -> schedule.time; is RoutineSchedule.Monthly -> schedule.time; else -> "18:30" }) }
    var days by rememberSaveable { mutableStateOf((schedule as? RoutineSchedule.Weekly)?.days?.sorted()?.joinToString(",") ?: "1,2,3,4,5") }
    var day by rememberSaveable { mutableStateOf(((schedule as? RoutineSchedule.Monthly)?.day ?: 1).toString()) }
    var missed by rememberSaveable { mutableStateOf(spec?.missedRunPolicy ?: MissedRunPolicy.RUN_LATEST) }
    var late by rememberSaveable { mutableStateOf(((spec?.maxLatenessMillis ?: 86_400_000L) / 60_000L).toString()) }
    var enabled by rememberSaveable { mutableStateOf(spec?.enabled ?: true) }
    var validation by remember { mutableStateOf<String?>(null) }
    val backFocus = Modifier.rememberManagementEntryFocus()
    fun submit() {
        try {
            val parsed = ManagementForms.schedule(kind, instant, interval, zone, time, days, day)
            val draft = RoutineSpec(name.trim(), task.trim(), parsed, enabled, missed, Math.multiplyExact(late.toLong(), 60_000L)).validated()
            validation = null; save(draft)
        } catch (e: Exception) { validation = e.message ?: "Review the schedule fields." }
    }
    BackHandler(!actions.busy, close)
    ManagementList(if (original == null) "New routine" else "Edit / rebind routine", actions) {
        item("routine-editor-back") { VelaButton("Back", backFocus, enabled = !actions.busy, onClick = close) }
        validation?.let { item("routine-editor-validation") { ManagementError(it) } }
        item("routine-editor-name") { ManagementField("Name", name, !actions.busy, 120) { name = it } }
        item("routine-editor-task") { ManagementField("Normal task · approvals still apply", task, !actions.busy, 8000, true) { task = it } }
        item("routine-editor-schedule") { VelaChoiceGroup("Schedule", listOf("once", "interval", "weekly", "monthly"), kind, { it.replaceFirstChar { char -> char.titlecase() } }, { kind = it }, enabled = !actions.busy) }
        if (kind == "once" || kind == "interval") {
            item("routine-editor-instant") {
                Column(verticalArrangement = Arrangement.spacedBy(VelaSpace.Xs)) {
                    ManagementField("First instant · ISO 8601 with Z or offset", instant, !actions.busy, 64) { instant = it }
                    ManagementText("Example: 2026-11-01T18:30:00-06:00. This is a precise instant, independent of future wall-clock changes.")
                }
            }
            if (kind == "interval") item("routine-editor-interval") { ManagementField("Interval minutes · 15 to 527040", interval, !actions.busy, 7) { interval = it } }
        } else {
            item("routine-editor-zone") { ManagementField("Named time zone", zone, !actions.busy, 100) { zone = it } }
            item("routine-editor-time") { ManagementField("Local time · HH:mm", time, !actions.busy, 5) { time = it } }
            if (kind == "weekly") item("routine-editor-days") {
                Column(verticalArrangement = Arrangement.spacedBy(VelaSpace.Xs)) {
                    ManagementField("ISO weekdays · Monday=1, Sunday=7 · comma-separated", days, !actions.busy, 30) { days = it }
                    ManagementText("Choose all seven days for a daily routine.")
                }
            }
            else item("routine-editor-day") { ManagementField("Day of month · 1–31 · clamps at month end", day, !actions.busy, 2) { day = it } }
        }
        item("routine-editor-missed") {
            Column(verticalArrangement = Arrangement.spacedBy(VelaSpace.S)) {
                VelaChoiceGroup("Missed runs", MissedRunPolicy.entries, missed, { it.name }, { missed = it }, enabled = !actions.busy)
                ManagementText("RUN_LATEST coalesces downtime within the deadline. SKIP uses a strict one-minute grace and can miss inexact Android wakes.")
            }
        }
        item("routine-editor-lateness") { ManagementField("Deadline / maximum lateness · minutes 1–43200", late, !actions.busy, 6) { late = it } }
        item("routine-editor-enabled") { VelaToggleRow("Routine enabled", enabled, { enabled = it }, enabled = !actions.busy) }
        item("routine-editor-explanation") { ManagementText("Save binds this definition to the current profile and its exact policy/preferences. Later profile changes fail closed until you edit and Save again. A stale edit retains its original revision: return, refresh and reopen before reapplying it.", "Saving a routine") }
        item("routine-editor-save") { ManagementButtons {
            VelaButton("Back", enabled = !actions.busy, onClick = close)
            VelaButton(if (actions.busy) "Saving…" else "Save routine", enabled = !actions.busy, busy = actions.busy, variant = ButtonVariant.Primary) { submit() }
        } }
    }
}
