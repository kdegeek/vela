package app.vela.tv.routines

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId

/** Calendar schedules keep their named zone, independently of the TV's current zone. */
@Serializable
sealed interface RoutineSchedule {
    @Serializable @SerialName("once")
    data class Once(val at: Long) : RoutineSchedule

    /** Fixed elapsed time, anchored at [firstAt], rather than local wall time. */
    @Serializable @SerialName("interval")
    data class Interval(val firstAt: Long, val everyMillis: Long) : RoutineSchedule

    /** ISO weekdays (Monday=1); all seven days is a daily schedule. Minute precision. */
    @Serializable @SerialName("weekly")
    data class Weekly(val days: Set<Int>, val time: String, val zone: String) : RoutineSchedule

    /** A day beyond a month's length is clamped to that month's last day. */
    @Serializable @SerialName("monthly")
    data class Monthly(val day: Int, val time: String, val zone: String) : RoutineSchedule
}

@Serializable enum class MissedRunPolicy { SKIP, RUN_LATEST }
@Serializable enum class RoutineRunSource { SCHEDULE, MANUAL, RESTART }
@Serializable enum class RoutineRunState {
    QUEUED, RUNNING, CANCELING, SUCCEEDED, FAILED, CANCELED, INTERRUPTED, MISSED;
    val terminal: Boolean get() = this !in setOf(QUEUED, RUNNING, CANCELING)
}

@Serializable
data class RoutineSpec(
    val name: String,
    /** A normal owner task, never a saved tool invocation or pre-approved action. */
    val task: String,
    val schedule: RoutineSchedule,
    val enabled: Boolean = true,
    val missedRunPolicy: MissedRunPolicy = MissedRunPolicy.RUN_LATEST,
    val maxLatenessMillis: Long = 24 * 60 * 60 * 1000L,
    /** Set only by trusted owner management. Null is an unbound legacy definition, never executable by the app. */
    val ownerProfileId: String? = null,
) {
    fun validated(): RoutineSpec = apply {
        require(name.isNotBlank() && name.length <= 120) { "Routine name needs 1–120 characters." }
        require(task.isNotBlank() && task.length <= 8_000) { "Routine task needs 1–8000 characters." }
        require(maxLatenessMillis in 60_000L..30 * 24 * 60 * 60 * 1000L) { "Late-run window must be one minute to 30 days." }
        require(ownerProfileId == null || ownerProfileId.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))) { "Invalid routine owner." }
        ScheduleTimes.validate(schedule)
    }
}

@Serializable
data class Routine(
    val id: String, val revision: Long, val spec: RoutineSpec,
    val createdAt: Long, val updatedAt: Long,
    /** Durable cursor. Null means a one-shot is exhausted; enabling does not re-arm it. */
    val nextRunAt: Long?,
)

@Serializable
data class RoutineRun(
    val id: String, val routineId: String, val revision: Long,
    val name: String, val task: String, val source: RoutineRunSource,
    val scheduledAt: Long, val queuedAt: Long, val expiresAt: Long,
    val state: RoutineRunState = RoutineRunState.QUEUED,
    val startedAt: Long? = null, val finishedAt: Long? = null,
    val detail: String? = null,
    /** Client-generated idempotency key, scoped to this routine, retained with this history entry. */
    val requestKey: String? = null,
    val restartedFrom: String? = null,
    val ownerProfileId: String? = null,
)

@Serializable
data class RoutineSnapshot(
    val version: Int = 1,
    val routines: List<Routine> = emptyList(),
    val history: List<RoutineRun> = emptyList(),
)

/** Pure, bounded calendar arithmetic: no iteration over a backlog after a long outage. */
object ScheduleTimes {
    const val MIN_INTERVAL_MILLIS = 15 * 60 * 1000L
    private fun timestamp(at: Long) = require(at in 0..7_258_118_400_000L) { "Schedule timestamp must be between 1970 and 2200." }
    private fun localTime(time: String): LocalTime {
        require(time.matches(Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]"))) { "Time must be HH:mm." }
        return LocalTime.parse(time)
    }

    fun validate(schedule: RoutineSchedule) {
        when (schedule) {
            is RoutineSchedule.Once -> timestamp(schedule.at)
            is RoutineSchedule.Interval -> {
                timestamp(schedule.firstAt)
                require(schedule.everyMillis in MIN_INTERVAL_MILLIS..366 * 24 * 60 * 60 * 1000L) { "Interval must be 15 minutes to 366 days." }
            }
            is RoutineSchedule.Weekly -> {
                require(schedule.days.isNotEmpty() && schedule.days.all { it in 1..7 }) { "Choose ISO weekdays 1–7." }
                localTime(schedule.time); ZoneId.of(schedule.zone)
            }
            is RoutineSchedule.Monthly -> {
                require(schedule.day in 1..31) { "Monthly day must be 1–31." }
                localTime(schedule.time); ZoneId.of(schedule.zone)
            }
        }
    }

    /** Strictly later than [after]. Gaps shift forward; overlaps use the earlier offset, once. */
    fun nextAfter(schedule: RoutineSchedule, after: Long): Long? = when (schedule) {
        is RoutineSchedule.Once -> schedule.at.takeIf { it > after }
        is RoutineSchedule.Interval -> if (after < schedule.firstAt) schedule.firstAt else
            schedule.firstAt + ((after - schedule.firstAt) / schedule.everyMillis + 1) * schedule.everyMillis
        else -> calendarCandidates(schedule, after, forward = true).filter { it > after }.minOrNull()
    }

    fun latestAtOrBefore(schedule: RoutineSchedule, at: Long): Long? = when (schedule) {
        is RoutineSchedule.Once -> schedule.at.takeIf { it <= at }
        is RoutineSchedule.Interval -> if (at < schedule.firstAt) null else
            schedule.firstAt + ((at - schedule.firstAt) / schedule.everyMillis) * schedule.everyMillis
        else -> calendarCandidates(schedule, at, forward = false).filter { it <= at }.maxOrNull()
    }

    private fun calendarCandidates(schedule: RoutineSchedule, at: Long, forward: Boolean): List<Long> {
        val zone = ZoneId.of(when (schedule) {
            is RoutineSchedule.Weekly -> schedule.zone
            is RoutineSchedule.Monthly -> schedule.zone
            else -> error("Not a calendar schedule.")
        })
        val date = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
        val offsets = if (forward) 0L..8L else -8L..0L
        return when (schedule) {
            is RoutineSchedule.Weekly -> offsets.map { date.plusDays(it) }
                .filter { it.dayOfWeek.value in schedule.days }
                .map { it.atTime(localTime(schedule.time)).atZone(zone).toInstant().toEpochMilli() }
            is RoutineSchedule.Monthly -> (if (forward) 0L..2L else -2L..0L).map {
                val month = YearMonth.from(date).plusMonths(it)
                month.atDay(minOf(schedule.day, month.lengthOfMonth()))
                    .atTime(localTime(schedule.time)).atZone(zone).toInstant().toEpochMilli()
            }
        }
    }
}
