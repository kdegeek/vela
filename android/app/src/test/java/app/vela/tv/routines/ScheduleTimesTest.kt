package app.vela.tv.routines

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class ScheduleTimesTest {
    private fun at(text: String) = Instant.parse(text).toEpochMilli()

    @Test fun `one shot is strictly consumed`() {
        val schedule = RoutineSchedule.Once(at("2026-10-31T12:00:00Z"))
        assertEquals(schedule.at, ScheduleTimes.nextAfter(schedule, schedule.at - 1))
        assertNull(ScheduleTimes.nextAfter(schedule, schedule.at))
        assertEquals(schedule.at, ScheduleTimes.latestAtOrBefore(schedule, schedule.at + 1))
    }

    @Test fun `elapsed intervals retain their anchor after a long outage`() {
        val first = at("2020-01-01T00:00:00Z")
        val schedule = RoutineSchedule.Interval(first, 900_000)
        assertEquals(first, ScheduleTimes.nextAfter(schedule, first - 1))
        assertNull(ScheduleTimes.latestAtOrBefore(schedule, first - 1))
        val now = at("2026-10-31T12:07:00Z")
        assertEquals(at("2026-10-31T12:00:00Z"), ScheduleTimes.latestAtOrBefore(schedule, now))
        assertEquals(at("2026-10-31T12:15:00Z"), ScheduleTimes.nextAfter(schedule, now))
    }

    @Test fun `daily spring gap shifts forward by gap duration`() {
        val schedule = RoutineSchedule.Weekly((1..7).toSet(), "02:30", "America/New_York")
        assertEquals(at("2026-03-08T07:30:00Z"), ScheduleTimes.nextAfter(schedule, at("2026-03-08T06:00:00Z")))
        assertEquals(at("2026-03-09T06:30:00Z"), ScheduleTimes.nextAfter(schedule, at("2026-03-08T07:30:00Z")))
    }

    @Test fun `fall overlap fires once at earlier offset`() {
        val schedule = RoutineSchedule.Weekly((1..7).toSet(), "01:30", "America/New_York")
        val first = at("2026-11-01T05:30:00Z")
        assertEquals(first, ScheduleTimes.nextAfter(schedule, at("2026-11-01T00:00:00Z")))
        assertEquals(at("2026-11-02T06:30:00Z"), ScheduleTimes.nextAfter(schedule, first))
        assertEquals(first, ScheduleTimes.latestAtOrBefore(schedule, at("2026-11-01T06:45:00Z")))
    }

    @Test fun `weekdays use fixed named zone and ISO numbering`() {
        val schedule = RoutineSchedule.Weekly(setOf(1, 5), "18:00", "Asia/Tokyo")
        assertEquals(at("2026-11-02T09:00:00Z"), ScheduleTimes.nextAfter(schedule, at("2026-10-31T23:00:00Z")))
        assertEquals(at("2026-10-30T09:00:00Z"), ScheduleTimes.latestAtOrBefore(schedule, at("2026-10-31T23:00:00Z")))
    }

    @Test fun `monthly end of month clamps without drifting next month`() {
        val schedule = RoutineSchedule.Monthly(31, "09:00", "UTC")
        val february = at("2028-02-29T09:00:00Z")
        assertEquals(february, ScheduleTimes.nextAfter(schedule, at("2028-02-01T00:00:00Z")))
        assertEquals(at("2028-03-31T09:00:00Z"), ScheduleTimes.nextAfter(schedule, february))
        assertEquals(february, ScheduleTimes.latestAtOrBefore(schedule, at("2028-03-15T00:00:00Z")))
    }

    @Test fun `invalid zone time weekday interval and month day are rejected`() {
        val bad = listOf(
            RoutineSchedule.Weekly(emptySet(), "09:00", "UTC"),
            RoutineSchedule.Weekly(setOf(8), "09:00", "UTC"),
            RoutineSchedule.Weekly(setOf(1), "9:00", "UTC"),
            RoutineSchedule.Weekly(setOf(1), "24:00", "UTC"),
            RoutineSchedule.Weekly(setOf(1), "09:00", "Mars/Olympus"),
            RoutineSchedule.Monthly(0, "09:00", "UTC"),
            RoutineSchedule.Interval(1, 1),
            RoutineSchedule.Once(-1),
        )
        bad.forEach { assertTrue("Accepted $it", runCatching { ScheduleTimes.validate(it) }.isFailure) }
    }
}
