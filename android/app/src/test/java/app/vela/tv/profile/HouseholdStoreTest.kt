package app.vela.tv.profile

import app.vela.tv.memory.MemoryQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class HouseholdStoreTest {
    @Test fun `first run persists a real stable profile and reopening preserves edits and active selection`() = runBlocking {
        val fixture = TestHouseholdFixture()
        val store = fixture.open()
        val initial = store.snapshot()
        assertEquals(1, fixture.storage.writes)
        assertEquals(0L, initial.revision)
        val second = store.createProfile(0, ProfileDraft("Morgan", preferences = ProfilePreferences(
            preferredMediaProviders = listOf("jellyfin-main"), preferredLibraries = listOf(LibraryRef("jellyfin-main", "movies")),
            audioLanguages = listOf("en-GB"), subtitleLanguages = listOf("en"), autoplayNext = true))).value
        store.switchProfile(1, second.id)
        store.remember(2, second.id, ownerMemory())
        val reopened = fixture.open().snapshot()
        assertEquals(store.snapshot(), reopened)
        assertEquals(second.id, reopened.activeProfileId)
        assertEquals(initial.activeProfileId, reopened.profiles.first().id)
        assertEquals(listOf("jellyfin-main"), reopened.activeProfile.preferences.preferredMediaProviders)
    }

    @Test fun `editing a profile keeps its id creation time and scoped memories`() = runBlocking {
        val fixture = TestHouseholdFixture()
        val store = fixture.open()
        val profile = store.snapshot().activeProfile
        store.remember(0, profile.id, ownerMemory())
        fixture.time = 2_000
        val change = store.editProfile(1, profile.id, ProfileDraft("New name"))
        assertEquals(2L, change.revision)
        assertEquals(profile.id, change.value.id)
        assertEquals(profile.createdAt, change.value.createdAt)
        assertEquals(2_000L, change.value.updatedAt)
        fixture.time = 500 // clock skew must not make timestamps invalid
        store.editProfile(2, profile.id, ProfileDraft("Another name"))
        assertEquals(2_000L, store.profile(profile.id).updatedAt)
        assertEquals(1, store.listMemories(profile.id).total)
    }

    @Test fun `last and active profiles cannot be deleted and memory cascade requires consent`() = runBlocking {
        val store = TestHouseholdFixture().open()
        val owner = store.snapshot().activeProfileId
        expectHouseholdError(HouseholdError.LAST_PROFILE) { store.deleteProfile(0, owner, true) }
        val second = store.createProfile(0, ProfileDraft("Second")).value
        expectHouseholdError(HouseholdError.ACTIVE_PROFILE) { store.deleteProfile(1, owner, true) }
        store.remember(1, second.id, ownerMemory())
        expectHouseholdError(HouseholdError.PROFILE_HAS_MEMORY) { store.deleteProfile(2, second.id) }
        store.deleteProfile(2, second.id, deleteOwnedMemories = true)
        assertEquals(owner, store.snapshot().activeProfileId)
        assertTrue(store.snapshot().memories.isEmpty())
        assertEquals(1, store.snapshot().profiles.size)
    }

    @Test fun `stale updates are rejected without writing or silently changing profile selection`() = runBlocking {
        val fixture = TestHouseholdFixture()
        val store = fixture.open()
        val profile = store.snapshot().activeProfileId
        val second = store.createProfile(0, ProfileDraft("Second")).value
        val writes = fixture.storage.writes
        expectHouseholdError(HouseholdError.CONFLICT) { store.switchProfile(0, second.id) }
        expectHouseholdError(HouseholdError.CONFLICT) { store.remember(0, profile, ownerMemory()) }
        assertEquals(writes, fixture.storage.writes)
        assertEquals(profile, store.snapshot().activeProfileId)
    }

    @Test fun `concurrent same-revision writes have exactly one winner`() = runBlocking {
        val store = TestHouseholdFixture().open()
        val profile = store.snapshot().activeProfileId
        val results = (1..24).map { index -> async(Dispatchers.Default) {
            try { store.remember(0, profile, ownerMemory("Favorite number is $index")); true }
            catch (e: HouseholdException) { assertEquals(HouseholdError.CONFLICT, e.code); false }
        } }.awaitAll()
        assertEquals(1, results.count { it })
        assertEquals(1L, store.snapshot().revision)
        assertEquals(1, store.listMemories(profile).total)
    }

    @Test fun `concurrent clients retrying conflicts preserve all writes durably`() = runBlocking {
        val fixture = TestHouseholdFixture()
        val store = fixture.open()
        val profile = store.snapshot().activeProfileId
        (1..24).map { index -> async(Dispatchers.Default) {
            while (true) {
                try { store.remember(store.snapshot().revision, profile, ownerMemory("Favorite item $index")); break }
                catch (e: HouseholdException) { if (e.code != HouseholdError.CONFLICT) throw e }
            }
        } }.awaitAll()
        assertEquals(24L, store.snapshot().revision)
        assertEquals(24, store.listMemories(profile).total)
        assertEquals(store.snapshot(), fixture.open().snapshot())
    }

    @Test fun `failed writes never publish uncommitted state and reload recovers previous document`() = runBlocking {
        val fixture = TestHouseholdFixture()
        val store = fixture.open()
        val before = store.snapshot()
        fixture.storage.failBeforeWrite = true
        expectHouseholdError(HouseholdError.STORAGE_FAILURE) { store.createProfile(0, ProfileDraft("Lost")) }
        assertTrue(store.state.value is HouseholdState.Unavailable)
        expectHouseholdError(HouseholdError.STORAGE_FAILURE) { store.snapshot() }
        expectHouseholdError(HouseholdError.STORAGE_FAILURE) { store.createProfile(0, ProfileDraft("No blind retry")) }
        fixture.storage.failBeforeWrite = false
        assertEquals(before, store.reload())
        assertEquals(1L, store.createProfile(0, ProfileDraft("Recovered")).revision)
    }

    @Test fun `a thrown write that actually committed is reconciled rather than overwritten`() = runBlocking {
        val fixture = TestHouseholdFixture()
        val store = fixture.open()
        fixture.storage.failAfterWrite = true
        expectHouseholdError(HouseholdError.STORAGE_FAILURE) { store.createProfile(0, ProfileDraft("Committed")) }
        fixture.storage.failAfterWrite = false
        val recovered = store.reload()
        assertEquals(1L, recovered.revision)
        assertEquals("Committed", recovered.profiles.last().displayName)
        expectHouseholdError(HouseholdError.CONFLICT) { store.createProfile(0, ProfileDraft("Duplicate")) }
    }

    @Test fun `cancellation during atomic commit finishes disk and state together`() = runBlocking {
        val fixture = TestHouseholdFixture()
        val store = fixture.open()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        fixture.storage.beforeWrite = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        val job = launch(Dispatchers.Default) { store.createProfile(0, ProfileDraft("Durable")) }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertEquals(0L, store.snapshot().revision) // no premature publication
            job.cancel()
        } finally { release.countDown() }
        job.join()
        fixture.storage.beforeWrite = null
        assertEquals(1L, store.snapshot().revision)
        assertEquals(store.snapshot(), fixture.open().snapshot())
    }

    @Test fun `invalid mutations and profile limit do not change disk or state`() = runBlocking {
        val fixture = TestHouseholdFixture()
        val store = fixture.open()
        expectHouseholdError(HouseholdError.INVALID_INPUT) { store.createProfile(0, ProfileDraft(" ")) }
        assertEquals(1, fixture.storage.writes)
        repeat(7) { store.createProfile(store.snapshot().revision, ProfileDraft("Person $it")) }
        val before = store.snapshot()
        expectHouseholdError(HouseholdError.LIMIT_EXCEEDED) { store.createProfile(before.revision, ProfileDraft("Ninth")) }
        assertEquals(before, store.snapshot())
    }

    @Test fun `unknown ids and id collisions fail without destructive fallback`() = runBlocking {
        val storage = TestHouseholdStorage()
        val id = "00000000-0000-4000-8000-000000000001"
        val store = HouseholdStore.open(storage, ids = HouseholdIdGenerator { id })
        expectHouseholdError(HouseholdError.NOT_FOUND) { store.switchProfile(0, "absent") }
        expectHouseholdError(HouseholdError.INVALID_INPUT) { store.createProfile(0, ProfileDraft("Collision")) }
        assertEquals(0L, store.snapshot().revision)
    }

    @Test fun `mutable caller inputs and returned lists cannot mutate store state`() = runBlocking {
        val store = TestHouseholdFixture().open()
        val providers = mutableListOf("jellyfin")
        val profile = store.createProfile(0, ProfileDraft("Second", preferences = ProfilePreferences(preferredMediaProviders = providers))).value
        providers.add("other")
        assertEquals(listOf("jellyfin"), store.profile(profile.id).preferences.preferredMediaProviders)
        try {
            (store.snapshot().profiles as MutableList).clear()
            fail("Expected an immutable snapshot")
        } catch (_: UnsupportedOperationException) { }
        try {
            (profile.preferences.preferredMediaProviders as MutableList).clear()
            fail("Expected immutable nested preferences")
        } catch (_: UnsupportedOperationException) { }
        assertEquals(2, store.snapshot().profiles.size)
    }

    @Test fun `reload of missing storage never recreates the household`() = runBlocking {
        val fixture = TestHouseholdFixture()
        val store = fixture.open()
        fixture.storage.bytes = null
        expectHouseholdError(HouseholdError.CORRUPT_STORAGE) { store.reload() }
        assertEquals(1, fixture.storage.writes)
        assertTrue(store.state.value is HouseholdState.Unavailable)
    }

    @Test fun `export includes one profile and its private memories only`() = runBlocking {
        val store = TestHouseholdFixture().open()
        val first = store.snapshot().activeProfileId
        val second = store.createProfile(0, ProfileDraft("Second")).value.id
        store.remember(1, first, ownerMemory("Only first", share = false))
        store.remember(2, second, ownerMemory("Only second"))
        val export = Json.decodeFromString(ProfileExport.serializer(), store.exportProfile(first))
        assertEquals(first, export.profile.id)
        assertEquals(listOf("Only first"), export.memories.map { it.text })
        assertFalse(store.exportProfile(first).contains(second))
        assertTrue(store.retrieveMemory(first, MemoryQuery()).context.entries.isEmpty())
    }
}
