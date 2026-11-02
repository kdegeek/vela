package app.vela.tv.profile

import app.vela.tv.memory.MemoryEntry
import app.vela.tv.memory.MemoryPage
import app.vela.tv.memory.MemoryQuery
import app.vela.tv.memory.MemoryRetrieval
import app.vela.tv.memory.OwnerMemoryWrite
import app.vela.tv.memory.RetrievedMemory
import app.vela.tv.memory.validateOwnerWrite
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Synchronous, bounded storage port. Null means no committed document; errors must throw.
 * A write must atomically replace the entire document (old or new after interruption, never partial).
 * A thrown write may have committed: the store becomes unavailable until a successful reload.
 * One store owns one storage location; multi-process writers are not supported.
 */
interface HouseholdStorage {
    fun read(): ByteArray?
    fun writeAtomically(bytes: ByteArray)
}

fun interface HouseholdClock { fun nowMillis(): Long }
fun interface HouseholdIdGenerator { fun newId(): String }

sealed interface HouseholdState {
    data class Ready(val document: HouseholdDocument) : HouseholdState
    data class Unavailable(val error: HouseholdError, val message: String) : HouseholdState
}

/** The revision is captured under the same lock as the returned value, for correct HTTP conflict handling. */
data class HouseholdCommit<T>(val revision: Long, val value: T)

@Serializable
data class ProfileExport(
    val exportVersion: Int = 1,
    val householdRevision: Long,
    val profile: HouseholdProfile,
    val memories: List<MemoryEntry>,
)

/**
 * Owner-facing facade shared by TV, phone and agent integrations. Every write requires the revision
 * the owner actually saw. No memory operation follows the current active profile implicitly.
 * State snapshots and nested collections are immutable; writes publish only after storage succeeds.
 */
class HouseholdStore private constructor(
    private val storage: HouseholdStorage,
    private val dispatcher: CoroutineDispatcher,
    private val clock: HouseholdClock,
    private val ids: HouseholdIdGenerator,
    initial: HouseholdDocument,
) {
    private val mutex = Mutex()
    private val exportJson = Json { encodeDefaults = true }
    private val mutableState = MutableStateFlow<HouseholdState>(HouseholdState.Ready(initial.frozen()))
    val state: StateFlow<HouseholdState> = mutableState.asStateFlow()

    fun snapshot(): HouseholdDocument = when (val current = state.value) {
        is HouseholdState.Ready -> current.document
        is HouseholdState.Unavailable -> throw HouseholdException(current.error, current.message)
    }

    fun profile(profileId: String): HouseholdProfile = requireProfile(snapshot(), profileId)

    suspend fun createProfile(expectedRevision: Long, draft: ProfileDraft): HouseholdCommit<HouseholdProfile> =
        mutate(expectedRevision) { current ->
            householdRequire(current.profiles.size < HouseholdLimits.MAX_PROFILES, "At most eight profiles are allowed.", HouseholdError.LIMIT_EXCEEDED)
            validateDraft(draft)
            val now = timestamp()
            val profile = HouseholdProfile(newId(current), draft.displayName, draft.kind, draft.preferences, draft.restrictions, now, now).frozen()
            Mutation(current.copy(profiles = current.profiles + profile), profile)
        }

    suspend fun editProfile(expectedRevision: Long, profileId: String, draft: ProfileDraft): HouseholdCommit<HouseholdProfile> =
        mutate(expectedRevision) { current ->
            val old = requireProfile(current, profileId)
            validateDraft(draft)
            val profile = old.copy(displayName = draft.displayName, kind = draft.kind, preferences = draft.preferences,
                restrictions = draft.restrictions, updatedAt = timestamp(old.updatedAt)).frozen()
            Mutation(current.copy(profiles = current.profiles.map { if (it.id == profileId) profile else it }), profile)
        }

    suspend fun switchProfile(expectedRevision: Long, profileId: String): HouseholdCommit<HouseholdProfile> =
        mutate(expectedRevision) { current ->
            val profile = requireProfile(current, profileId)
            Mutation(current.copy(activeProfileId = profileId), profile)
        }

    /** Switch away first; explicit consent is required to cascade the profile's memories. */
    suspend fun deleteProfile(expectedRevision: Long, profileId: String, deleteOwnedMemories: Boolean = false): HouseholdCommit<String> =
        mutate(expectedRevision) { current ->
            requireProfile(current, profileId)
            householdRequire(current.profiles.size > 1, "The last household profile cannot be deleted.", HouseholdError.LAST_PROFILE)
            householdRequire(current.activeProfileId != profileId, "Switch to another profile before deleting this profile.", HouseholdError.ACTIVE_PROFILE)
            householdRequire(deleteOwnedMemories || current.memories.none { it.profileId == profileId },
                "This profile has memories. Explicitly confirm deleting them with the profile.", HouseholdError.PROFILE_HAS_MEMORY)
            Mutation(current.copy(profiles = current.profiles.filterNot { it.id == profileId },
                memories = current.memories.filterNot { it.profileId == profileId }), profileId)
        }

    suspend fun remember(expectedRevision: Long, profileId: String, write: OwnerMemoryWrite): HouseholdCommit<MemoryEntry> =
        mutate(expectedRevision) { current ->
            requireProfile(current, profileId)
            validateOwnerWrite(write)
            val now = timestamp()
            val memory = MemoryEntry(newId(current), profileId, write.kind, write.text, write.source,
                write.tags.frozen(), write.shareWithAgent, now, now)
            Mutation(current.copy(memories = current.memories + memory), memory)
        }

    /** The profile ID is checked as well as the memory ID; another profile's entry is always NOT_FOUND. */
    suspend fun editMemory(expectedRevision: Long, profileId: String, memoryId: String, write: OwnerMemoryWrite): HouseholdCommit<MemoryEntry> =
        mutate(expectedRevision) { current ->
            requireProfile(current, profileId)
            val old = requireMemory(current, profileId, memoryId)
            validateOwnerWrite(write)
            val memory = old.copy(kind = write.kind, text = write.text, source = write.source,
                tags = write.tags.frozen(), shareWithAgent = write.shareWithAgent, updatedAt = timestamp(old.updatedAt))
            Mutation(current.copy(memories = current.memories.map { if (it.id == memoryId) memory else it }), memory)
        }

    suspend fun deleteMemory(expectedRevision: Long, profileId: String, memoryId: String): HouseholdCommit<String> =
        mutate(expectedRevision) { current ->
            requireProfile(current, profileId)
            requireMemory(current, profileId, memoryId)
            Mutation(current.copy(memories = current.memories.filterNot { it.id == memoryId }), memoryId)
        }

    suspend fun clearMemories(expectedRevision: Long, profileId: String, ownerConfirmed: Boolean): HouseholdCommit<Int> =
        mutate(expectedRevision) { current ->
            requireProfile(current, profileId)
            householdRequire(ownerConfirmed, "Confirm clearing this profile's memories first.")
            Mutation(current.copy(memories = current.memories.filterNot { it.profileId == profileId }),
                current.memories.count { it.profileId == profileId })
        }

    fun memory(profileId: String, memoryId: String): MemoryEntry {
        val current = snapshot()
        requireProfile(current, profileId)
        return requireMemory(current, profileId, memoryId)
    }

    /** Offset pages are tied to revision. Supply expectedRevision for all pages after the first. */
    fun listMemories(profileId: String, offset: Int = 0, limit: Int = 50, expectedRevision: Long? = null): MemoryPage {
        val current = snapshot()
        requireProfile(current, profileId)
        if (expectedRevision != null) requireRevision(current, expectedRevision)
        householdRequire(offset >= 0 && limit in 1..100, "Memory pages need a nonnegative offset and 1–100 entries.")
        val entries = current.memories.filter { it.profileId == profileId }
            .sortedWith(compareByDescending<MemoryEntry> { it.updatedAt }.thenBy { it.id })
        return MemoryPage(profileId, current.revision, entries.size, entries.drop(offset).take(limit).frozen())
    }

    fun retrieveMemory(profileId: String, query: MemoryQuery = MemoryQuery()): RetrievedMemory {
        val current = snapshot()
        requireProfile(current, profileId)
        return MemoryRetrieval.retrieve(profileId, current.revision, current.memories, query)
    }

    /** Owner-only export, not an agent tool. Contains precisely one profile, including opted-out memory. */
    fun exportProfile(profileId: String): String {
        val current = snapshot()
        val export = ProfileExport(householdRevision = current.revision, profile = requireProfile(current, profileId),
            memories = current.memories.filter { it.profileId == profileId })
        return exportJson.encodeToString(ProfileExport.serializer(), export)
    }

    /** Reconcile a failed/ambiguous write. Missing/corrupt/future data is never replaced with defaults. */
    suspend fun reload(): HouseholdDocument = mutex.withLock {
        currentCoroutineContext().ensureActive()
        withContext(NonCancellable + dispatcher) {
            try {
                val bytes = storage.read() ?: throw HouseholdException(HouseholdError.CORRUPT_STORAGE, "Household storage is missing. Restore it before continuing.")
                val decoded = HouseholdCodec.decode(bytes)
                if (decoded.migrated) storage.writeAtomically(HouseholdCodec.encode(decoded.document))
                mutableState.value = HouseholdState.Ready(decoded.document)
                decoded.document
            } catch (e: Exception) {
                val failure = storageException(e)
                mutableState.value = HouseholdState.Unavailable(failure.code, failure.message!!)
                throw failure
            }
        }
    }

    private data class Mutation<T>(val document: HouseholdDocument, val value: T)

    private suspend fun <T> mutate(expectedRevision: Long, change: (HouseholdDocument) -> Mutation<T>): HouseholdCommit<T> = mutex.withLock {
        currentCoroutineContext().ensureActive()
        withContext(NonCancellable + dispatcher) {
            val current = snapshot()
            requireRevision(current, expectedRevision)
            val mutation = change(current)
            if (mutation.document == current) return@withContext HouseholdCommit(current.revision, mutation.value)
            householdRequire(current.revision < Long.MAX_VALUE, "Household revision limit reached.", HouseholdError.LIMIT_EXCEEDED)
            val next = mutation.document.copy(revision = current.revision + 1).frozen()
            val encoded = HouseholdCodec.encode(next)
            try {
                storage.writeAtomically(encoded)
            } catch (e: Exception) {
                // The disk may hold old OR new data. Do not continue using a potentially stale snapshot.
                val failure = storageException(e)
                mutableState.value = HouseholdState.Unavailable(failure.code, failure.message!!)
                throw failure
            }
            mutableState.value = HouseholdState.Ready(next)
            HouseholdCommit(next.revision, mutation.value)
        }
    }

    private fun timestamp(previous: Long = 0): Long {
        val now = clock.nowMillis()
        householdRequire(now >= 0, "Household clock returned a negative timestamp.")
        return maxOf(now, previous)
    }

    private fun newId(current: HouseholdDocument): String {
        val id = ids.newId()
        householdRequire(validId(id) && current.profiles.none { it.id == id } && current.memories.none { it.id == id },
            "Household ID generator returned an invalid or repeated ID.")
        return id
    }

    companion object {
        /** Open once per application, off the main thread; default dispatcher owns bounded blocking IO. */
        suspend fun open(
            storage: HouseholdStorage,
            initialProfileName: String = "Household",
            dispatcher: CoroutineDispatcher = Dispatchers.IO,
            clock: HouseholdClock = HouseholdClock { System.currentTimeMillis() },
            ids: HouseholdIdGenerator = HouseholdIdGenerator { UUID.randomUUID().toString() },
        ): HouseholdStore = withContext(dispatcher) {
            try {
                val bytes = storage.read()
                val decoded = if (bytes == null) {
                    val draft = ProfileDraft(initialProfileName)
                    validateDraft(draft)
                    val now = clock.nowMillis()
                    val profile = HouseholdProfile(ids.newId(), draft.displayName, draft.kind, draft.preferences, draft.restrictions, now, now)
                    DecodedHousehold(HouseholdDocument(revision = 0, activeProfileId = profile.id, profiles = listOf(profile), memories = emptyList()).frozen(), true)
                } else HouseholdCodec.decode(bytes)
                val store = HouseholdStore(storage, dispatcher, clock, ids, decoded.document)
                // Check cancellation before committing, then finish the bounded write even if the caller leaves.
                currentCoroutineContext().ensureActive()
                if (decoded.migrated) withContext(NonCancellable) {
                    storage.writeAtomically(HouseholdCodec.encode(decoded.document))
                }
                store
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                throw storageException(e)
            }
        }

        private fun requireRevision(current: HouseholdDocument, revision: Long) {
            householdRequire(revision == current.revision, "Household changed. Reload before saving this edit.", HouseholdError.CONFLICT)
        }

        private fun requireProfile(current: HouseholdDocument, profileId: String): HouseholdProfile =
            current.profiles.firstOrNull { it.id == profileId }
                ?: throw HouseholdException(HouseholdError.NOT_FOUND, "Profile was not found.")

        private fun requireMemory(current: HouseholdDocument, profileId: String, memoryId: String): MemoryEntry =
            current.memories.firstOrNull { it.id == memoryId && it.profileId == profileId }
                ?: throw HouseholdException(HouseholdError.NOT_FOUND, "Memory was not found in this profile.")

        private fun storageException(error: Exception): HouseholdException = if (error is HouseholdException) error else
            HouseholdException(HouseholdError.STORAGE_FAILURE, "Household storage failed. Reload before continuing.")
    }
}
