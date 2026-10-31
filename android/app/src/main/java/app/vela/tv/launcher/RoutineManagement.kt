package app.vela.tv.launcher

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.vela.tv.VelaApp
import app.vela.tv.design.*
import app.vela.tv.routines.*
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private fun instantLabel(at: Long?): String = at?.let { DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(it)) } ?: "none / exhausted"

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
        item { Text("Routines submit normal tasks with the same approvals and restrictions. Each is bound to the profile and policy/preferences saved with it. If that binding changes or another profile is active, execution fails closed: deliberately edit and Save to rebind. Android background wakes are inexact.") }
        item { OwnerControlStatus(management) }
        item { ManagementButtons {
            VelaButton("Refresh routines & history", enabled = !actions.busy) { actions.run { refresh() } }
            VelaButton("New routine", enabled = !actions.busy && snapshot != null) { original = null; editing = true }
        } }
        error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        if (snapshot == null && error == null) item { LoadingState("Loading durable routines…") }
        snapshot?.let { displayed ->
            if (displayed.routines.isEmpty()) item { Text("No routines saved. Create a normal task and choose when it should run.") }
            items(displayed.routines, key = { "routine:${it.id}" }) { routine ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(routine.spec.name, style = MaterialTheme.typography.titleLarge)
                    Text(routine.spec.task)
                    Text("${if (routine.spec.enabled) "Enabled" else "Disabled"} · ${routine.spec.schedule}\nNext: ${instantLabel(routine.nextRunAt)}")
                    ManagementButtons {
                        VelaButton("Edit / rebind", enabled = !actions.busy) { original = routine; editing = true }
                        VelaButton(if (routine.spec.enabled) "Disable" else "Enable", enabled = !actions.busy) { actions.run { management.enableRoutine(routine.id, routine.revision, !routine.spec.enabled); refresh() } }
                        val operation = "run:${routine.id}"
                        VelaButton(if (requests.uncertain(operation)) "Retry same run request" else "Run now", enabled = !actions.busy) {
                            actions.run { management.runRoutine(routine.id, requests.key(operation)); requests.accepted(operation); refresh() }
                        }
                        VelaButton("Delete…", enabled = !actions.busy) { actions.confirm("Delete ${routine.spec.name} and cancel its pending work? Durable run history stays.") { management.deleteRoutine(routine.id, routine.revision); refresh() } }
                    }
                    HorizontalDivider()
                }
            }
            item { Text("Run history", style = MaterialTheme.typography.titleLarge); Text("QUEUED waits for control; CANCELING waits for cleanup. FAILED / INTERRUPTED need review. A lost acknowledgement keeps the same request key for Retry. Refresh history before retrying an unknown result.") }
            if (displayed.history.isEmpty()) item { Text("No durable run history yet.") }
            items(displayed.history.asReversed(), key = { "run:${it.id}" }) { run ->
                Column {
                    Text("${run.name} · ${run.state}", style = MaterialTheme.typography.titleMedium)
                    Text("Queued ${instantLabel(run.queuedAt)}\nStarted ${instantLabel(run.startedAt)}\nFinished ${instantLabel(run.finishedAt)}")
                    run.detail?.let { Text(it) }
                    ManagementButtons {
                        if (!run.state.terminal) VelaButton("Cancel run", enabled = !actions.busy) { actions.run { management.cancelRoutineRun(run.id); refresh() } }
                        if (displayed.routines.any { it.id == run.routineId }) {
                            val operation = "restart:${run.id}"
                            VelaButton(if (requests.uncertain(operation)) "Retry same restart" else "Restart current definition…", enabled = !actions.busy) {
                                actions.confirm("Cancel this routine's pending work and enqueue its current definition? The earlier task must finish cleanup first. An unknown previous request reuses its existing key.") {
                                    management.restartRoutineRun(run.id, requests.key(operation)); requests.accepted(operation); refresh()
                                }
                            }
                        }
                    }
                    HorizontalDivider()
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
    fun submit() {
        try {
            val parsed = ManagementForms.schedule(kind, instant, interval, zone, time, days, day)
            val draft = RoutineSpec(name.trim(), task.trim(), parsed, enabled, missed, Math.multiplyExact(late.toLong(), 60_000L)).validated()
            validation = null; save(draft)
        } catch (e: Exception) { validation = e.message ?: "Review the schedule fields." }
    }
    BackHandler(!actions.busy, close)
    ManagementList(if (original == null) "New routine" else "Edit / rebind routine", actions) {
        item { ManagementButtons { VelaButton("Back", enabled = !actions.busy, onClick = close); VelaButton("Save routine", enabled = !actions.busy) { submit() } } }
        validation?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        item { ManagementField("Name", name, !actions.busy, 120) { name = it } }
        item { ManagementField("Normal task · approvals still apply", task, !actions.busy, 8000, true) { task = it } }
        item { Text("Schedule"); ManagementButtons { listOf("once", "interval", "weekly", "monthly").forEach { value -> VelaButton(value, selected = value == kind, enabled = !actions.busy) { kind = value } } } }
        if (kind == "once" || kind == "interval") {
            item { ManagementField("First instant · ISO 8601 with Z or offset", instant, !actions.busy, 64) { instant = it }; Text("Example: 2026-11-01T18:30:00-06:00. This is a precise instant, independent of future wall-clock changes.") }
            if (kind == "interval") item { ManagementField("Interval minutes · 15 to 527040", interval, !actions.busy, 7) { interval = it } }
        } else {
            item { ManagementField("Named time zone", zone, !actions.busy, 100) { zone = it } }
            item { ManagementField("Local time · HH:mm", time, !actions.busy, 5) { time = it } }
            if (kind == "weekly") item { ManagementField("ISO weekdays · Monday=1, Sunday=7 · comma-separated", days, !actions.busy, 30) { days = it }; Text("Choose all seven days for a daily routine.") }
            else item { ManagementField("Day of month · 1–31 · clamps at month end", day, !actions.busy, 2) { day = it } }
        }
        item { ManagementButtons { MissedRunPolicy.entries.forEach { value -> VelaButton(value.name, selected = missed == value, enabled = !actions.busy) { missed = value } } }; Text("RUN_LATEST coalesces downtime within the deadline. SKIP uses a strict one-minute grace and can miss inexact Android wakes.") }
        item { ManagementField("Deadline / maximum lateness · minutes 1–43200", late, !actions.busy, 6) { late = it } }
        item { VelaButton("Enabled: ${if (enabled) "yes" else "no"}", enabled = !actions.busy) { enabled = !enabled } }
        item { Text("Save binds this definition to the current profile and its exact policy/preferences. Later profile changes fail closed until you edit and Save again. A stale edit retains its original revision: return, refresh and reopen before reapplying it.") }
        item { VelaButton("Save routine", enabled = !actions.busy) { submit() } }
    }
}
