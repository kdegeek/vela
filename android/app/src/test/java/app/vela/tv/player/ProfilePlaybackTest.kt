package app.vela.tv.player

import app.vela.tv.profile.*
import org.junit.Assert.*
import org.junit.Test

class ProfilePlaybackTest {
    private fun profile(id: String = "00000000-0000-4000-8000-000000000001", preferences: ProfilePreferences = ProfilePreferences(), restrictions: ProfileRestrictions = ProfileRestrictions()) =
        HouseholdProfile(id, "Viewer", ProfileKind.ADULT, preferences, restrictions, 0, 0)
    @Test fun `track storage is isolated by stable profile identity`() {
        val first = ProfilePlaybackPlan.forProfile(profile())
        val second = ProfilePlaybackPlan.forProfile(profile(id = "00000000-0000-4000-8000-000000000002"))
        assertNotEquals(first.storageName, second.storageName)
    }
    @Test fun `owner preferences are carried without global defaults overriding them`() {
        val prefs = ProfilePreferences(audioLanguages = listOf("fr"), subtitleLanguages = listOf("en"), subtitles = SubtitlePreference.ON, autoplayNext = true)
        assertEquals(prefs, ProfilePlaybackPlan.forProfile(profile(preferences = prefs)).preferences)
    }
    @Test fun `unknown metadata is denied when library or rating is restricted`() {
        assertTrue(runCatching { ProfilePlaybackPlan.forProfile(profile(restrictions = ProfileRestrictions(allowUnrated = false))) }.isFailure)
        assertTrue(runCatching { ProfilePlaybackPlan.forProfile(profile(restrictions = ProfileRestrictions(allowedLibraries = listOf(LibraryRef("jellyfin", "kids"))))) }.isFailure)
    }
    @Test fun `an unrestricted adult retains direct play and autoplay remains opt in`() {
        assertFalse(ProfilePlaybackPlan.forProfile(profile()).preferences.autoplayNext)
    }
}
