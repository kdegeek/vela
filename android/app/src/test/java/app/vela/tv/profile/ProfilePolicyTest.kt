package app.vela.tv.profile

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ProfilePolicyTest {
    private fun child() = ProfileDraft("Child", ProfileKind.CHILD,
        preferences = ProfilePreferences(preferredMediaProviders = listOf("not-allowed")),
        restrictions = ProfileRestrictions(allowedMediaProviders = listOf("jellyfin"),
            allowedLibraries = listOf(LibraryRef("jellyfin", "family")), allowedAppPackages = listOf("app.vela.tv"),
            maxAgeRating = 12, allowUnrated = false, allowAgentActions = false, allowHomeControl = false))

    @Test fun `child restrictions fail closed and preferences do not grant permissions`() = runBlocking {
        val store = TestHouseholdFixture().open()
        val profile = store.createProfile(0, child()).value
        assertTrue(ProfilePolicy.canPlay(profile, "jellyfin", "family", 12).allowed)
        assertFalse(ProfilePolicy.canPlay(profile, "not-allowed", "family", 0).allowed)
        assertFalse(ProfilePolicy.canPlay(profile, "jellyfin", "adult", 0).allowed)
        assertFalse(ProfilePolicy.canPlay(profile, "jellyfin", null, 0).allowed)
        assertFalse(ProfilePolicy.canPlay(profile, "jellyfin", "family", null).allowed)
        assertFalse(ProfilePolicy.canPlay(profile, "jellyfin", "family", 13).allowed)
        assertTrue(ProfilePolicy.canOpenApp(profile, "app.vela.tv").allowed)
        assertFalse(ProfilePolicy.canOpenApp(profile, "com.unknown.app").allowed)
        assertFalse(ProfilePolicy.canUseAgent(profile).allowed)
        assertFalse(ProfilePolicy.canControlHome(profile).allowed)
    }

    @Test fun `child profile requires explicit restrictions while adult null and empty lists differ`() = runBlocking {
        val store = TestHouseholdFixture().open()
        for (draft in listOf(
            ProfileDraft("Child", ProfileKind.CHILD),
            child().copy(restrictions = child().restrictions.copy(allowUnrated = true)),
            child().copy(restrictions = child().restrictions.copy(maxAgeRating = 18)),
            child().copy(restrictions = child().restrictions.copy(allowedAppPackages = null)),
        )) expectHouseholdError(HouseholdError.INVALID_INPUT) { store.createProfile(0, draft) }
        assertTrue(ProfilePolicy.canPlay(store.snapshot().activeProfile, "jellyfin", null, null).allowed)
        val deny = store.createProfile(0, ProfileDraft("Restricted", restrictions = ProfileRestrictions(
            allowedMediaProviders = emptyList(), allowedAppPackages = emptyList()))).value
        assertFalse(ProfilePolicy.canPlay(deny, "jellyfin", null, null).allowed)
        assertFalse(ProfilePolicy.canOpenApp(deny, "app.vela.tv").allowed)
    }

    @Test fun `invalid preferences names refs languages packages and text controls are rejected`() = runBlocking {
        val store = TestHouseholdFixture().open()
        for (draft in listOf(
            ProfileDraft(" Name"), ProfileDraft("Name\nline"), ProfileDraft("\uD800"),
            ProfileDraft("Name", preferences = ProfilePreferences(audioLanguages = listOf("en", "EN"))),
            ProfileDraft("Name", preferences = ProfilePreferences(audioLanguages = listOf("en_uk"))),
            ProfileDraft("Name", preferences = ProfilePreferences(preferredMediaProviders = listOf("https://secret:token@host"))),
            ProfileDraft("Name", preferences = ProfilePreferences(preferredMediaProviders = listOf("duplicate", "duplicate"))),
            ProfileDraft("Name", preferences = ProfilePreferences(preferredLibraries = listOf(LibraryRef("provider", "")))),
            ProfileDraft("Name", restrictions = ProfileRestrictions(allowedAppPackages = listOf("bad package"))),
            ProfileDraft("Name", restrictions = ProfileRestrictions(maxAgeRating = 22)),
        )) expectHouseholdError(HouseholdError.INVALID_INPUT) { store.createProfile(0, draft) }
        assertEquals(0L, store.snapshot().revision)
    }

    @Test fun `policy refuses malformed directly constructed profiles and targets`() = runBlocking {
        val profile = TestHouseholdFixture().open().snapshot().activeProfile
        val malformedChild = profile.copy(kind = ProfileKind.CHILD)
        expectHouseholdError(HouseholdError.INVALID_INPUT) { ProfilePolicy.canPlay(malformedChild, "jellyfin", null, null) }
        expectHouseholdError(HouseholdError.INVALID_INPUT) { ProfilePolicy.canUseAgent(malformedChild) }
        expectHouseholdError(HouseholdError.INVALID_INPUT) { ProfilePolicy.canControlHome(malformedChild) }
        expectHouseholdError(HouseholdError.INVALID_INPUT) { ProfilePolicy.canOpenApp(profile, "bad target") }
        expectHouseholdError(HouseholdError.INVALID_INPUT) { ProfilePolicy.canPlay(profile, "jellyfin", null, -1) }
    }
}
