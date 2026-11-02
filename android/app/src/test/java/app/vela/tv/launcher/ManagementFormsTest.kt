package app.vela.tv.launcher

import app.vela.tv.profile.*
import app.vela.tv.routines.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class ManagementFormsTest {
    @Test fun preferenceEditsPreserveChildRestrictionsAndProviderReferences() {
        val restrictions = ProfileRestrictions(emptyList(), emptyList(), emptyList(), 12, false, false, false)
        val original = HouseholdProfile("child", "Child", ProfileKind.CHILD,
            ProfilePreferences(preferredMediaProviders = listOf("jellyfin"), preferredLibraries = listOf(LibraryRef("jellyfin", "kids"))), restrictions, 1, 1)
        val draft = ManagementForms.profile(original, " New name ", "en, es", "fr", SubtitlePreference.ON, true)
        assertEquals(ProfileKind.CHILD, draft.kind); assertEquals(restrictions, draft.restrictions)
        assertEquals(original.preferences.preferredMediaProviders, draft.preferences.preferredMediaProviders)
        assertEquals(original.preferences.preferredLibraries, draft.preferences.preferredLibraries)
        assertEquals(listOf("en", "es"), draft.preferences.audioLanguages)
        assertEquals(SubtitlePreference.ON, draft.preferences.subtitles); assertTrue(draft.preferences.autoplayNext)
        assertEquals("New name", draft.displayName)
    }
    @Test fun newProfileIsAdultAndAutoplayIntentIsExplicit() {
        val draft = ManagementForms.profile(null, "Alex", "", "", SubtitlePreference.AUTOMATIC, false)
        assertEquals(ProfileKind.ADULT, draft.kind); assertFalse(draft.preferences.autoplayNext)
    }
    @Test fun schedulesRetainNamedZonesOffsetsAndTypedFields() {
        val weekly = ManagementForms.schedule("weekly", "", "", "America/Chicago", "18:30", "1,3,5", "") as RoutineSchedule.Weekly
        assertEquals(setOf(1,3,5), weekly.days); assertEquals("America/Chicago", weekly.zone)
        val monthly = ManagementForms.schedule("monthly", "", "", "Europe/London", "09:00", "", "31") as RoutineSchedule.Monthly
        assertEquals(31, monthly.day); assertEquals("Europe/London", monthly.zone)
        val once = ManagementForms.schedule("once", "2026-11-01T18:30:00-06:00", "", "", "", "", "") as RoutineSchedule.Once
        assertEquals(Instant.parse("2026-11-02T00:30:00Z").toEpochMilli(), once.at)
        val interval = ManagementForms.schedule("interval", "2026-11-02T00:30:00Z", "15", "", "", "", "") as RoutineSchedule.Interval
        assertEquals(900_000L, interval.everyMillis); assertEquals(once.at, interval.firstAt)
    }
    @Test fun unknownScheduleAndInvalidZoneCannotBecomeAValidRoutine() {
        for (args in listOf(listOf("opaque","","","","","",""), listOf("weekly","","","Not/AZone","18:30","1",""), listOf("interval","2026-11-02T00:30:00Z","1","","","",""), listOf("once","2026-11-02T18:30:00","","","","",""))) {
            assertThrows(Exception::class.java) { ManagementForms.schedule(args[0],args[1],args[2],args[3],args[4],args[5],args[6]) }
        }
    }
    @Test fun unknownRequestAcknowledgementRetainsKeyUntilAcceptedThenNextActionIsFresh() {
        val keys = RoutineRequestKeys()
        val first = keys.key("run:r")
        assertTrue(keys.uncertain("run:r")); assertEquals(first, keys.key("run:r"))
        assertNotEquals(first, keys.key("restart:x"))
        keys.accepted("run:r"); assertFalse(keys.uncertain("run:r")); assertNotEquals(first, keys.key("run:r"))
    }
}
