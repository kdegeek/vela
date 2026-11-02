package app.vela.tv.launcher

import app.vela.tv.profile.*
import app.vela.tv.routines.*
import java.time.OffsetDateTime
import java.util.UUID

/** Parsing shared by the actual TV forms and their JVM regression tests. No persistence here. */
internal object ManagementForms {
    fun words(value: String): List<String> = value.split(',').map(String::trim).filter(String::isNotEmpty)

    fun profile(original: HouseholdProfile?, name: String, audio: String, subtitles: String,
                intent: SubtitlePreference, autoplay: Boolean): ProfileDraft =
        (original?.draft() ?: ProfileDraft("")).copy(displayName = name.trim(),
            preferences = (original?.preferences ?: ProfilePreferences()).copy(
                audioLanguages = words(audio), subtitleLanguages = words(subtitles),
                subtitles = intent, autoplayNext = autoplay))

    fun schedule(kind: String, instant: String, intervalMinutes: String, zone: String,
                 time: String, days: String, day: String): RoutineSchedule {
        val schedule = when (kind) {
            "once" -> RoutineSchedule.Once(OffsetDateTime.parse(instant.trim()).toInstant().toEpochMilli())
            "interval" -> RoutineSchedule.Interval(OffsetDateTime.parse(instant.trim()).toInstant().toEpochMilli(),
                Math.multiplyExact(intervalMinutes.toLong(), 60_000L))
            "weekly" -> RoutineSchedule.Weekly(words(days).map(String::toInt).toSet(), time.trim(), zone.trim())
            "monthly" -> RoutineSchedule.Monthly(day.toInt(), time.trim(), zone.trim())
            else -> throw IllegalArgumentException("Choose a supported schedule.")
        }
        ScheduleTimes.validate(schedule)
        return schedule
    }
}

/** A failed transport/storage acknowledgement retains the deliberate request key for a retry. */
internal class RoutineRequestKeys {
    private val pending = mutableMapOf<String, String>()
    fun key(operation: String): String = pending.getOrPut(operation) { UUID.randomUUID().toString() }
    fun uncertain(operation: String): Boolean = operation in pending
    fun accepted(operation: String) { pending.remove(operation) }
}
