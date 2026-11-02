package app.vela.tv.media.plex

import app.vela.tv.agent.ProfileToolPolicy
import app.vela.tv.agent.TileCalls
import app.vela.tv.layout.TileAction
import app.vela.tv.profile.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PlexPolicyTest {
    private fun profile(restrictions: ProfileRestrictions = ProfileRestrictions()) = HouseholdProfile(
        "00000000-0000-0000-0000-000000000001", "Adult", ProfileKind.ADULT, ProfilePreferences(), restrictions, 0, 0)
    @Test fun `only unrestricted profile browses because app handoff is opaque`() {
        PlexBrowsePolicy.requireAllowed(profile())
        for (r in listOf(ProfileRestrictions(allowedMediaProviders = listOf("plex")),
            ProfileRestrictions(allowedLibraries = listOf(LibraryRef("plex", "1"))),
            ProfileRestrictions(maxAgeRating = 18), ProfileRestrictions(allowUnrated = false),
            ProfileRestrictions(allowedAppPackages = listOf(PlexBrowsePolicy.APP_PACKAGE)),
            ProfileRestrictions(allowedMediaProviders = emptyList()), ProfileRestrictions(allowAgentActions = false))) {
            assertThrows(IllegalStateException::class.java) { PlexBrowsePolicy.requireAllowed(profile(r)) }
        }
    }
    @Test fun `saved search tile uses existing app_search arguments with no link or playback request`() {
        val call = TileCalls.call(TileAction.SearchApp(PlexBrowsePolicy.APP_PACKAGE, "Arrival"))
        assertEquals("app_search", call.name)
        assertEquals(setOf("package", "query"), call.arguments.keys)
        assertEquals("Arrival", call.arguments["query"]!!.jsonPrimitive.content)
        assertEquals(PlexBrowsePolicy.APP_PACKAGE, call.arguments["package"]!!.jsonPrimitive.content)
        assertNull(ProfileToolPolicy.error(profile(), call.name, call.arguments))
        assertNotNull(ProfileToolPolicy.error(profile(ProfileRestrictions(allowedAppPackages = listOf(PlexBrowsePolicy.APP_PACKAGE))), call.name, call.arguments))
        assertNotNull(ProfileToolPolicy.error(profile(ProfileRestrictions(maxAgeRating = 12)), call.name, call.arguments))
    }
}
