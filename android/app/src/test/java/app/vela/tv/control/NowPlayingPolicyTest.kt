package app.vela.tv.control

import app.vela.tv.profile.*
import org.junit.Assert.*
import org.junit.Test

class NowPlayingPolicyTest {
    private fun state(restrictions: ProfileRestrictions = ProfileRestrictions(), kind: ProfileKind = ProfileKind.ADULT): HouseholdState {
        val profile = HouseholdProfile("00000000-0000-4000-8000-000000000001", "Owner", kind,
            ProfilePreferences(), restrictions, 1, 1)
        return HouseholdState.Ready(HouseholdDocument(revision = 1, activeProfileId = profile.id,
            profiles = listOf(profile), memories = emptyList()))
    }

    @Test fun unrestrictedAdultCanObserveAndRequestControlsWithoutAModel() {
        assertNull(nowPlayingPolicyError(state()))
    }

    @Test fun startupAndStorageFailureCannotExposeSessions() {
        assertNotNull(nowPlayingPolicyError(null))
        assertNotNull(nowPlayingPolicyError(HouseholdState.Unavailable(HouseholdError.STORAGE_FAILURE, "unavailable")))
    }

    @Test fun childAndEveryOpaqueRestrictionAreDenied() {
        val child = ProfileRestrictions(allowedMediaProviders = listOf("jellyfin"),
            allowedLibraries = listOf(LibraryRef("jellyfin", "library")),
            allowedAppPackages = listOf("com.example.player"), maxAgeRating = 13, allowUnrated = false)
        assertNotNull(nowPlayingPolicyError(state(child, ProfileKind.CHILD)))
        for (restriction in listOf(
            ProfileRestrictions(allowedAppPackages = listOf("com.example.player")),
            ProfileRestrictions(allowedAppPackages = emptyList()),
            ProfileRestrictions(allowedMediaProviders = listOf("jellyfin")),
            ProfileRestrictions(allowedLibraries = listOf(LibraryRef("jellyfin", "library"))),
            ProfileRestrictions(maxAgeRating = 13), ProfileRestrictions(allowUnrated = false),
            ProfileRestrictions(allowAgentActions = false),
        )) assertNotNull(restriction.toString(), nowPlayingPolicyError(state(restriction)))
    }

    @Test fun unrelatedHomeRestrictionDoesNotDenyPlayback() {
        assertNull(nowPlayingPolicyError(state(ProfileRestrictions(allowHomeControl = false))))
    }

    @Test fun malformedProfileFailsClosedInsteadOfCrashingTheShell() {
        assertNotNull(nowPlayingPolicyError(state(kind = ProfileKind.CHILD)))
    }
}
