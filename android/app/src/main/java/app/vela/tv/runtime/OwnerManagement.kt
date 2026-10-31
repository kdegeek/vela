package app.vela.tv.runtime

import app.vela.tv.integrations.ControlArbiter
import app.vela.tv.integrations.ControlBusyException
import app.vela.tv.mcp.*
import app.vela.tv.memory.*
import app.vela.tv.profile.*
import app.vela.tv.routines.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Trusted owner operations. Model tools never receive this facade. */
class OwnerManagement(
    private val control: ControlArbiter,
    private val running: StateFlow<Boolean>,
    private val stopAndDrain: suspend () -> Unit,
    private val invalidateContext: () -> Unit,
    private val household: suspend () -> HouseholdStore,
    private val engine: suspend () -> RoutineEngine,
    private val bindings: RoutineBindings,
    private val wake: () -> Unit,
    private val external: ExternalControlManager,
) {
    val busy: StateFlow<String?> = control.owner
    private val mutex = Mutex()
    suspend fun stopAndJoin() = withContext(Dispatchers.Main.immediate) { mutex.withLock { stopAndDrain() } }
    private suspend fun <T> exclusive(clear: Boolean = false, drain: Boolean = false, block: suspend () -> T): T =
        withContext(Dispatchers.Main.immediate) {
            mutex.withLock {
                if (drain) stopAndDrain()
                if (running.value) throw ControlBusyException()
                control.withControl("owner-management") {
                    try { block() } finally { if (clear) invalidateContext() }
                }
            }
        }
    suspend fun profiles(): ProfileList = household().snapshot().let { ProfileList(it.revision, it.activeProfileId, it.profiles) }
    suspend fun createProfile(revision: Long, draft: ProfileDraft) = exclusive(clear = true) { household().createProfile(revision, draft) }
    suspend fun editProfile(revision: Long, profileId: String, draft: ProfileDraft) = exclusive(clear = true) { household().editProfile(revision, profileId, draft) }
    suspend fun switchProfile(revision: Long, profileId: String) = exclusive(clear = true, drain = true) { household().switchProfile(revision, profileId) }
    suspend fun deleteProfile(revision: Long, profileId: String, deleteOwnedMemories: Boolean) = exclusive(clear = true) {
        household().deleteProfile(revision, profileId, deleteOwnedMemories)
    }
    suspend fun memories(profileId: String, offset: Int = 0, limit: Int = 50, expectedRevision: Long? = null) =
        household().listMemories(profileId, offset, limit, expectedRevision)
    suspend fun saveMemory(revision: Long, profileId: String, draft: MemoryDraft, memoryId: String? = null, source: OwnerMemorySource = OwnerMemorySource.TV): HouseholdCommit<MemoryEntry> = exclusive(clear = true) {
        require(source == OwnerMemorySource.TV || source == OwnerMemorySource.PHONE)
        val write = OwnerMemoryWrite(draft.kind, draft.text, source, true, draft.tags, draft.shareWithAgent)
        if (memoryId == null) household().remember(revision, profileId, write) else household().editMemory(revision, profileId, memoryId, write)
    }
    suspend fun deleteMemory(revision: Long, profileId: String, memoryId: String) = exclusive(clear = true) { household().deleteMemory(revision, profileId, memoryId) }
    suspend fun clearMemories(revision: Long, profileId: String, ownerConfirmed: Boolean) = exclusive(clear = true) { household().clearMemories(revision, profileId, ownerConfirmed) }
    suspend fun exportProfile(profileId: String) = household().exportProfile(profileId)
    suspend fun reloadHousehold(): ProfileList = exclusive(clear = true, drain = true) { household().reload(); profiles() }
    // Definition/history edits have no immediate device effect. They may queue or cancel their own
    // routine while an owner is active; the executor still reserves the shared task slot at dispatch.
    private suspend fun <T> routineEdit(block: suspend () -> T): T = withContext(Dispatchers.Main.immediate) { mutex.withLock { block() } }
    suspend fun routines(): RoutineSnapshot = engine().state.value
    suspend fun createRoutine(spec: RoutineSpec): Routine = routineEdit {
        val profile = household().snapshot().activeProfile
        withContext(Dispatchers.IO) { engine().create(spec.copy(ownerProfileId = profile.id)).also { bindings.bind(it, profile) } }
    }.also { wake() }
    suspend fun updateRoutine(id: String, revision: Long, spec: RoutineSpec): Routine = routineEdit {
        val profile = household().snapshot().activeProfile
        withContext(Dispatchers.IO) { routine(id, revision); engine().update(id, revision, spec.copy(ownerProfileId = profile.id)).also { bindings.bind(it, profile) } }
    }.also { wake() }
    suspend fun deleteRoutine(id: String, revision: Long) { routineEdit {
        withContext(Dispatchers.IO) { routine(id, revision); engine().delete(id, revision); bindings.remove(id) }
    }; wake() }
    suspend fun enableRoutine(id: String, revision: Long, enabled: Boolean): Routine = routineEdit {
        withContext(Dispatchers.IO) {
            val old = routine(id, revision)
            if (enabled && old.spec.ownerProfileId == null) throw ManagementConflictException()
            engine().setEnabled(id, revision, enabled).also { bindings.advance(old, it) }
        }
    }.also { wake() }
    suspend fun runRoutine(id: String, requestKey: String): RoutineRun = routineEdit {
        withContext(Dispatchers.IO) { routine(id); checkPending(id, requestKey); engine().runNow(id, requestKey) }
    }.also { wake() }
    // Cancellation remains usable while that routine owns the controller.
    suspend fun cancelRoutineRun(runId: String): RoutineRun = withContext(Dispatchers.IO) { run(runId); engine().cancelRun(runId) }.also { wake() }
    suspend fun restartRoutineRun(runId: String, requestKey: String): RoutineRun = withContext(Dispatchers.IO) {
        run(runId); engine().restartRun(runId, requestKey)
    }.also { wake() }
    suspend fun externalControl() = external.status.value
    suspend fun enableExternalControl(ownerConfirmed: Boolean): ExternalControlProvision = exclusive {
        if (external.status.value.credentialPresent) throw ManagementConflictException()
        external.enable(ownerConfirmed)
    }
    suspend fun revokeExternalControl(): ExternalControlStatus = withContext(Dispatchers.Main.immediate) {
        // Close authorization before drain. This must remain callable while an MCP invocation owns control.
        mutex.withLock { external.revoke() }
    }
    private suspend fun routine(id: String, revision: Long? = null): Routine {
        val item = engine().state.value.routines.firstOrNull { it.id == id } ?: throw ManagementNotFoundException()
        if (revision != null && item.revision != revision) throw ManagementConflictException()
        return item
    }
    private suspend fun run(id: String) = engine().state.value.history.firstOrNull { it.id == id } ?: throw ManagementNotFoundException()
    private suspend fun checkPending(id: String, key: String) {
        val history = engine().state.value.history
        if (history.any { it.routineId == id && !it.state.terminal } && history.none { it.routineId == id && it.requestKey == key })
            throw ManagementConflictException()
    }
}
