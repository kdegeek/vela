package app.vela.tv.agent

import app.vela.tv.memory.*
import app.vela.tv.profile.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ProfileExecutionTest {
    private class Storage : HouseholdStorage {
        var data: ByteArray? = null
        override fun read() = data
        override fun writeAtomically(bytes: ByteArray) { data = bytes }
    }
    @Test fun `memory retrieval is bounded and cannot choose another profile`() = runTest {
        val store = HouseholdStore.open(Storage(), dispatcher = StandardTestDispatcher(testScheduler))
        var doc = store.snapshot()
        store.remember(doc.revision, doc.activeProfileId, OwnerMemoryWrite(MemoryKind.FACT, "Adult private memory", OwnerMemorySource.PHONE, true))
        doc = store.snapshot()
        val second = store.createProfile(doc.revision, ProfileDraft("Second"))
        store.switchProfile(second.revision, second.value.id)
        val bound = ProfileExecution.capture(store)
        assertFalse(bound.memory("").contains("Adult private memory"))
        assertTrue(bound.memory("").length <= 4000)
    }
    @Test fun `deleting used memory invalidates the pinned context`() = runTest {
        val store = HouseholdStore.open(Storage(), dispatcher = StandardTestDispatcher(testScheduler))
        val doc = store.snapshot()
        val entry = store.remember(doc.revision, doc.activeProfileId, OwnerMemoryWrite(MemoryKind.FACT, "Saved preference", OwnerMemorySource.TV, true))
        val bound = ProfileExecution.capture(store)
        assertTrue(bound.memory("").contains("Saved preference"))
        store.deleteMemory(entry.revision, doc.activeProfileId, entry.value.id)
        assertFalse(bound.valid())
        assertTrue(runCatching { bound.memory("") }.exceptionOrNull() is ActionInvalidatedException)
    }
    @Test fun `profile restrictions deny opaque alternatives and unproven media targets`() {
        val child = HouseholdProfile("00000000-0000-4000-8000-000000000001", "Child", ProfileKind.CHILD,
            ProfilePreferences(), ProfileRestrictions(allowedMediaProviders = listOf("jellyfin"), allowedLibraries = listOf(LibraryRef("jellyfin", "kids")),
                allowedAppPackages = listOf("app.safe.player"), maxAgeRating = 12, allowUnrated = false, allowHomeControl = false), 0, 0)
        for (tool in listOf("screen_press", "screen_read", "screen_focus", "screen_type", "screen_scroll", "wait", "remote", "open_link", "app_open", "app_search", "media_control", "media_status", "jellyfin_play", "jellyfin_search", "plex_libraries", "plex_search", "home_call"))
            assertNotNull(tool, ProfileToolPolicy.error(child, tool, buildJsonObject { put("package", "app.safe.player") }))
        assertNull(ProfileToolPolicy.error(child, "memory_retrieve", buildJsonObject {}))
    }
    @Test fun `disabled agent cannot access even benign tools`() {
        val profile = HouseholdProfile("00000000-0000-4000-8000-000000000001", "Adult", ProfileKind.ADULT,
            ProfilePreferences(), ProfileRestrictions(allowAgentActions = false), 0, 0)
        assertNotNull(ProfileToolPolicy.error(profile, "memory_retrieve", buildJsonObject {}))
    }
}
