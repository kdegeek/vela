package app.vela.tv.profile

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class HouseholdCodecTest {
    private val json = Json { encodeDefaults = true }
    @Test fun `v1 profile-only migration persists v2 once while retaining ids active profile and preferences`() = runBlocking {
        val fixture = TestHouseholdFixture()
        val store = fixture.open()
        val other = store.createProfile(0, ProfileDraft("Second", preferences = ProfilePreferences(audioLanguages = listOf("fr")))).value
        store.switchProfile(1, other.id)
        val current = store.snapshot()
        val legacy = HouseholdV1(1, current.revision, current.activeProfileId, current.profiles)
        fixture.storage.bytes = json.encodeToString(HouseholdV1.serializer(), legacy).toByteArray()
        val writes = fixture.storage.writes
        val migrated = fixture.open().snapshot()
        assertEquals(2, migrated.version)
        assertEquals(current.revision + 1, migrated.revision)
        assertEquals(current.profiles, migrated.profiles)
        assertEquals(other.id, migrated.activeProfileId)
        assertTrue(migrated.memories.isEmpty())
        assertEquals(writes + 1, fixture.storage.writes)
        assertEquals(migrated, fixture.open().snapshot())
        assertEquals(writes + 1, fixture.storage.writes)
    }

    @Test fun `future schema and malformed storage are preserved byte for byte`() = runBlocking {
        val fixture = TestHouseholdFixture()
        val valid = fixture.open().snapshot()
        val encoded = HouseholdCodec.encode(valid).toString(Charsets.UTF_8)
        val cases = listOf(
            encoded.replace("\"version\":2", "\"version\":3") to HouseholdError.UNSUPPORTED_VERSION,
            encoded.replace("\"version\":2", "\"version\":99999999999999999999999999999") to HouseholdError.UNSUPPORTED_VERSION,
            encoded.replace("\"version\":2", "\"version\":\"2\"") to HouseholdError.CORRUPT_STORAGE,
            encoded.replace("\"version\":2", "\"version\":2.0") to HouseholdError.CORRUPT_STORAGE,
            encoded.replace("\"version\":2,", "") to HouseholdError.CORRUPT_STORAGE,
            encoded.replace(",\"memories\":[]", "") to HouseholdError.CORRUPT_STORAGE,
            encoded.replace("\"revision\":0", "\"revision\":-1") to HouseholdError.CORRUPT_STORAGE,
            encoded.replace("\"revision\":0", "\"revision\":\"0\"") to HouseholdError.CORRUPT_STORAGE,
            encoded.replace("\"revision\":0", "\"revision\":0.0") to HouseholdError.CORRUPT_STORAGE,
            encoded.replace("\"createdAt\":1000", "\"createdAt\":\"1000\"") to HouseholdError.CORRUPT_STORAGE,
            encoded.replace("\"autoplayNext\":false", "\"autoplayNext\":\"false\"") to HouseholdError.CORRUPT_STORAGE,
            encoded.replace("\"displayName\":\"Household\"", "\"displayName\":123") to HouseholdError.CORRUPT_STORAGE,
            encoded.replace("\"profiles\":[", "\"profiles\":[null,") to HouseholdError.CORRUPT_STORAGE,
            encoded.dropLast(3) to HouseholdError.CORRUPT_STORAGE,
            "{\"password\":\"owner private value\"}" to HouseholdError.CORRUPT_STORAGE,
            encoded.dropLast(1) + ",\"unknown\":true}" to HouseholdError.CORRUPT_STORAGE,
        )
        for ((text, code) in cases) {
            val storage = TestHouseholdStorage(text.toByteArray())
            expectHouseholdError(code) { HouseholdStore.open(storage) }
            assertArrayEquals(text.toByteArray(), storage.bytes)
            assertEquals(0, storage.writes)
        }
    }

    @Test fun `malformed utf8 and oversized files fail closed`() = runBlocking {
        for (bytes in listOf(byteArrayOf(0xC3.toByte(), 0x28), ByteArray(HouseholdLimits.MAX_DOCUMENT_BYTES + 1) { 32 })) {
            val storage = TestHouseholdStorage(bytes)
            expectHouseholdError(HouseholdError.CORRUPT_STORAGE) { HouseholdStore.open(storage) }
            assertArrayEquals(bytes, storage.bytes)
            assertEquals(0, storage.writes)
        }
    }

    @Test fun `invalid decoded relationships duplicates timestamps and child defaults are rejected`() = runBlocking {
        val fixture = TestHouseholdFixture()
        val document = fixture.open().snapshot()
        val profile = document.activeProfile
        val json = Json { encodeDefaults = true }
        val invalid = listOf(
            document.copy(activeProfileId = "missing"),
            document.copy(profiles = listOf(profile, profile)),
            document.copy(profiles = listOf(profile.copy(updatedAt = profile.createdAt - 1))),
            document.copy(profiles = listOf(profile.copy(kind = ProfileKind.CHILD))),
            document.copy(profiles = listOf(profile.copy(id = "not-stable")), activeProfileId = "not-stable"),
        )
        for (bad in invalid) {
            val bytes = json.encodeToString(HouseholdDocument.serializer(), bad).toByteArray()
            val storage = TestHouseholdStorage(bytes)
            expectHouseholdError(HouseholdError.CORRUPT_STORAGE) { HouseholdStore.open(storage) }
            assertEquals(0, storage.writes)
        }
    }

    @Test fun `failed first-run or migration writes return no usable store and keep previous document`() = runBlocking {
        val storage = TestHouseholdStorage().apply { failBeforeWrite = true }
        expectHouseholdError(HouseholdError.STORAGE_FAILURE) { HouseholdStore.open(storage) }
        assertNull(storage.bytes)
        val fixture = TestHouseholdFixture()
        val document = fixture.open().snapshot()
        val legacy = Json.encodeToString(HouseholdV1.serializer(), HouseholdV1(1, 0, document.activeProfileId, document.profiles)).toByteArray()
        storage.bytes = legacy
        expectHouseholdError(HouseholdError.STORAGE_FAILURE) { HouseholdStore.open(storage) }
        assertArrayEquals(legacy, storage.bytes)
    }
}
