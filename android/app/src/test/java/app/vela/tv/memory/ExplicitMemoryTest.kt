package app.vela.tv.memory

import app.vela.tv.profile.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ExplicitMemoryTest {
    @Test fun `inspect edit delete and clear survive restart and preserve stable ids`() = runBlocking {
        val fixture = TestHouseholdFixture()
        val store = fixture.open()
        val profile = store.snapshot().activeProfileId
        val first = store.remember(0, profile, ownerMemory()).value
        fixture.time = 3_000
        val edited = store.editMemory(1, profile, first.id, ownerMemory("I prefer comedies", share = false)).value
        assertEquals(first.id, edited.id)
        assertEquals(first.createdAt, edited.createdAt)
        assertEquals(3_000L, edited.updatedAt)
        assertEquals(edited, fixture.open().memory(profile, first.id))
        assertTrue(store.retrieveMemory(profile).context.entries.isEmpty())
        val second = store.remember(2, profile, ownerMemory("I also like science")).value
        store.deleteMemory(3, profile, first.id)
        expectHouseholdError(HouseholdError.NOT_FOUND) { store.memory(profile, first.id) }
        assertEquals(second, store.listMemories(profile).entries.single())
        expectHouseholdError(HouseholdError.INVALID_INPUT) { store.clearMemories(4, profile, false) }
        assertEquals(1, store.clearMemories(4, profile, true).value)
        assertEquals(0, fixture.open().listMemories(profile).total)
    }

    @Test fun `all memory operations are isolated even after active profile changes`() = runBlocking {
        val store = TestHouseholdFixture().open()
        val first = store.snapshot().activeProfileId
        val second = store.createProfile(0, ProfileDraft("Second")).value.id
        val memory = store.remember(1, first, ownerMemory("First only")).value
        store.switchProfile(2, second)
        expectHouseholdError(HouseholdError.NOT_FOUND) { store.memory(second, memory.id) }
        expectHouseholdError(HouseholdError.NOT_FOUND) { store.editMemory(3, second, memory.id, ownerMemory()) }
        expectHouseholdError(HouseholdError.NOT_FOUND) { store.deleteMemory(3, second, memory.id) }
        assertTrue(store.retrieveMemory(second).context.entries.isEmpty())
        assertEquals(0, store.listMemories(second).total)
        assertEquals(listOf(memory.id), store.retrieveMemory(first).context.entries.map { it.id })
        store.clearMemories(3, second, true)
        assertEquals(memory, store.memory(first, memory.id))
    }

    @Test fun `explicit confirmation is required and obvious credentials never enter storage`() = runBlocking {
        val fixture = TestHouseholdFixture()
        val store = fixture.open()
        val profile = store.snapshot().activeProfileId
        expectHouseholdError(HouseholdError.INVALID_INPUT) { store.remember(0, profile, ownerMemory(confirmed = false)) }
        for (secret in listOf("My password is hunter2", "api_key = private123", "Bearer abcdef123456", "sk-12345678901234567890", "-----BEGIN RSA PRIVATE KEY-----")) {
            expectHouseholdError(HouseholdError.SENSITIVE_MEMORY) { store.remember(0, profile, ownerMemory(secret)) }
            assertFalse(fixture.storage.bytes!!.toString(Charsets.UTF_8).contains(secret))
        }
        expectHouseholdError(HouseholdError.INVALID_INPUT) { store.remember(0, profile, ownerMemory("Untrusted\u0000text")) }
        expectHouseholdError(HouseholdError.INVALID_INPUT) { store.remember(0, profile, ownerMemory().copy(tags = listOf("Invalid Tag"))) }
        assertEquals(0L, store.snapshot().revision)
    }

    @Test fun `memory entry and character limits reject additions without evicting older facts`() = runBlocking {
        val store = TestHouseholdFixture().open()
        val profile = store.snapshot().activeProfileId
        repeat(100) { store.remember(store.snapshot().revision, profile, ownerMemory("Explicit fact $it")) }
        val before = store.snapshot()
        expectHouseholdError(HouseholdError.LIMIT_EXCEEDED) { store.remember(100, profile, ownerMemory("Over capacity")) }
        assertEquals(before, store.snapshot())
        store.clearMemories(100, profile, true)
        repeat(50) { store.remember(store.snapshot().revision, profile, ownerMemory("x".repeat(2_000))) }
        expectHouseholdError(HouseholdError.LIMIT_EXCEEDED) { store.remember(store.snapshot().revision, profile, ownerMemory("one too many")) }
        expectHouseholdError(HouseholdError.INVALID_INPUT) { store.remember(store.snapshot().revision, profile, ownerMemory("x".repeat(2_001))) }
        assertEquals(50, store.listMemories(profile).total)
    }

    @Test fun `retrieval ranks matching owner text excludes private entries and has strict full-json bounds`() = runBlocking {
        val fixture = TestHouseholdFixture()
        val store = fixture.open()
        val profile = store.snapshot().activeProfileId
        val first = store.remember(0, profile, ownerMemory("I prefer documentaries").copy(tags = listOf("documentary"))).value
        fixture.time++
        store.remember(1, profile, ownerMemory("I like comedy").copy(tags = listOf("comedy")))
        store.remember(2, profile, ownerMemory("Private documentary fact", share = false))
        store.remember(3, profile, ownerMemory("Untrusted \"quotes\" and\nnewlines"))
        val match = store.retrieveMemory(profile, MemoryQuery("DOCUMENTARY"))
        assertEquals(listOf(first.id), match.context.entries.map { it.id })
        val empty = store.retrieveMemory(profile, MemoryQuery("nonexistent"))
        assertTrue(empty.context.entries.isEmpty())
        val bounded = store.retrieveMemory(profile, MemoryQuery(maxCharacters = 256))
        assertTrue(bounded.json.length <= 256)
        assertTrue(bounded.context.hasMore)
        assertEquals(bounded.context, Json.decodeFromString(AgentMemoryContext.serializer(), bounded.json))
        assertFalse(bounded.json.contains("Private"))
        val zero = store.retrieveMemory(profile, MemoryQuery(limit = 0))
        assertTrue(zero.context.entries.isEmpty())
        assertTrue(zero.context.hasMore)
    }

    @Test fun `large memories are skipped whole allowing smaller useful entries within budget`() = runBlocking {
        val fixture = TestHouseholdFixture()
        val store = fixture.open()
        val profile = store.snapshot().activeProfileId
        val small = store.remember(0, profile, ownerMemory("Tea")).value
        fixture.time++
        store.remember(1, profile, ownerMemory("\\".repeat(2_000)))
        val result = store.retrieveMemory(profile, MemoryQuery(maxCharacters = 300))
        assertEquals(listOf(small.id), result.context.entries.map { it.id })
        assertTrue(result.context.hasMore)
        assertTrue(result.json.length <= 300)
        assertEquals(result, store.retrieveMemory(profile, MemoryQuery(maxCharacters = 300)))
    }

    @Test fun `pagination revision and retrieval query bounds are validated`() = runBlocking {
        val store = TestHouseholdFixture().open()
        val profile = store.snapshot().activeProfileId
        repeat(3) { store.remember(store.snapshot().revision, profile, ownerMemory("Fact $it")) }
        val first = store.listMemories(profile, limit = 2)
        val next = store.listMemories(profile, offset = 2, limit = 2, expectedRevision = first.revision)
        assertEquals(3, first.total)
        assertEquals(3, (first.entries + next.entries).map { it.id }.distinct().size)
        store.remember(3, profile, ownerMemory("New fact"))
        expectHouseholdError(HouseholdError.CONFLICT) { store.listMemories(profile, 2, 2, first.revision) }
        expectHouseholdError(HouseholdError.INVALID_INPUT) { store.listMemories(profile, -1) }
        for (query in listOf(MemoryQuery(text = "x".repeat(501)), MemoryQuery(limit = 21), MemoryQuery(limit = -1),
            MemoryQuery(maxCharacters = 255), MemoryQuery(maxCharacters = 8_001))) {
            expectHouseholdError(HouseholdError.INVALID_INPUT) { store.retrieveMemory(profile, query) }
        }
    }

    @Test fun `decoded duplicate and orphaned memories fail closed`() = runBlocking {
        val store = TestHouseholdFixture().open()
        val profile = store.snapshot().activeProfileId
        val memory = store.remember(0, profile, ownerMemory()).value
        val json = Json { encodeDefaults = true }
        for (document in listOf(
            store.snapshot().copy(memories = listOf(memory, memory)),
            store.snapshot().copy(memories = listOf(memory.copy(profileId = "00000000-0000-4000-8000-999999999999"))),
        )) {
            val storage = TestHouseholdStorage(json.encodeToString(HouseholdDocument.serializer(), document).toByteArray())
            expectHouseholdError(HouseholdError.CORRUPT_STORAGE) { HouseholdStore.open(storage) }
            assertEquals(0, storage.writes)
        }
    }
}
