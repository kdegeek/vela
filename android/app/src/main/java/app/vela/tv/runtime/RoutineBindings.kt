package app.vela.tv.runtime

import kotlinx.serialization.encodeToString

import app.vela.tv.agent.TaskRecord
import app.vela.tv.profile.HouseholdDocument
import app.vela.tv.profile.HouseholdProfile
import app.vela.tv.routines.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable data class RoutineBinding(val routineId: String, val revision: Long, val profile: HouseholdProfile)

/** Missing or mismatched sidecars deny dispatch. A crash between two files can disable work, never broaden its authority. */
class RoutineBindings(private val read: () -> ByteArray?, private val write: (ByteArray) -> Unit) {
    companion object { const val MAX_DOCUMENT_BYTES = 16 * 1024 * 1024 }
    private val json = Json { encodeDefaults = true }
    private var bindings: List<RoutineBinding>? = null
    @Synchronized private fun loaded(): List<RoutineBinding> = bindings ?: run {
        val values = read()?.let {
            require(it.size <= MAX_DOCUMENT_BYTES) { "Routine bindings are too large." }
            json.decodeFromString<List<RoutineBinding>>(it.toString(Charsets.UTF_8))
        } ?: emptyList()
        require(values.size <= 100 && values.map { it.routineId }.distinct().size == values.size)
        bindings = values
        values
    }
    @Synchronized fun validate() { loaded() }
    @Synchronized fun bind(routine: Routine, profile: HouseholdProfile) {
        require(routine.spec.ownerProfileId == profile.id)
        val next = loaded().filterNot { it.routineId == routine.id } + RoutineBinding(routine.id, routine.revision, profile)
        persist(next)
    }
    @Synchronized fun advance(old: Routine, next: Routine) {
        if (old.revision == next.revision) return
        val profile = loaded().singleOrNull { it.routineId == old.id && it.revision == old.revision }?.profile
            ?: throw RuntimeUnavailableException()
        bind(next, profile)
    }
    @Synchronized fun remove(id: String) {
        val next = loaded().filterNot { it.routineId == id }
        persist(next)
    }
    private fun persist(next: List<RoutineBinding>) {
        require(next.size <= RoutineDocuments.MAX_ROUTINES)
        val bytes = json.encodeToString(next).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_DOCUMENT_BYTES) { "Routine bindings are too large." }
        // Invalidate the cache even when disk outcome is unknown.
        bindings = null
        write(bytes)
        bindings = next
    }
    @Synchronized fun permits(run: RoutineRun, document: HouseholdDocument): Boolean {
        val binding = loaded().singleOrNull { it.routineId == run.routineId && it.revision == run.revision } ?: return false
        return run.ownerProfileId == binding.profile.id && document.activeProfileId == binding.profile.id && document.activeProfile == binding.profile
    }
}

/** Unbound legacy definitions remain inspectable/editable, but startup never dispatches them. */
suspend fun pauseUnboundRoutines(engine: RoutineEngine) {
    for (routine in engine.state.value.routines) {
        if (routine.spec.ownerProfileId == null && routine.spec.enabled)
            engine.setEnabled(routine.id, routine.revision, false)
    }
}

/** Submission and profile validation run together on the injected Main dispatcher. Cleanup targets only the returned ID. */
class RoutineSubmission(
    private val reserve: suspend (RoutineTaskRequest) -> String?,
    private val await: suspend (String) -> TaskRecord,
    private val cancelAndJoin: suspend (String) -> Boolean,
    private val admissionDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : RoutineTaskExecutor {
    override suspend fun tryExecute(request: RoutineTaskRequest): RoutineTaskResult {
        var reservedId: String? = null
        return try {
            // Retain ownership on the acquiring dispatcher before a cancellable return to IO.
            // The finally is already installed if that return drops the result.
            withContext(admissionDispatcher) { reservedId = reserve(request) }
            val id = reservedId ?: return RoutineTaskResult.Busy
            val result = await(id)
            if (result.state == "complete") RoutineTaskResult.Completed(result.detail)
            else RoutineTaskResult.Failed("Task stopped or failed. Inspect activity before restarting.")
        } catch (e: RoutineProfileException) {
            RoutineTaskResult.Failed(e.message!!)
        } finally { reservedId?.let { id -> withContext(NonCancellable) { cancelAndJoin(id) } } }
    }
}

/** Called immediately before the Main-confined non-replacing reservation; no suspension between these operations. */
fun requireRoutineProfile(request: RoutineTaskRequest, run: RoutineRun?, document: HouseholdDocument, bindings: RoutineBindings) {
    if (run == null || request.ownerProfileId == null || request.ownerProfileId != run.ownerProfileId ||
        request.runId != run.id || request.routineId != run.routineId || request.task != run.task || !bindings.permits(run, document))
        throw RoutineProfileException()
}
