package app.vela.tv.web

import kotlinx.serialization.encodeToString

import app.vela.tv.memory.OwnerMemorySource
import app.vela.tv.profile.HouseholdCommit
import app.vela.tv.profile.ProfileDraft
import app.vela.tv.routines.RoutineSpec
import app.vela.tv.runtime.MemoryDraft
import app.vela.tv.runtime.OwnerManagement
import app.vela.tv.runtime.RuntimeUnavailableException
import io.ktor.server.application.ApplicationCall
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

/** Registered inside the same pairing, byte-limit, no-store and sanitized-error boundary as the existing API. */
internal fun Route.managementRoutes(backend: PhoneBackend) {
    val json = Json { encodeDefaults = true }
    fun owner(): OwnerManagement = backend.management ?: throw RuntimeUnavailableException()
    suspend fun request(call: ApplicationCall, vararg fields: String) = ManagementProtocol.only(ManagementProtocol.objectBody(body(call)), *fields)
    fun revision(value: JsonObject) = ManagementProtocol.revision(value)
    fun id(call: ApplicationCall) = call.parameters["id"] ?: throw IllegalArgumentException()
    fun memoryId(call: ApplicationCall) = call.parameters["memoryId"] ?: throw IllegalArgumentException()
    fun draft(value: JsonObject) = ManagementProtocol.decode(ProfileDraft.serializer(), value.getValue("profile"))
    fun spec(value: JsonObject): RoutineSpec {
        val spec = value.getValue("spec") as? JsonObject ?: throw IllegalArgumentException()
        ManagementProtocol.only(spec, "name", "task", "schedule", "enabled", "missedRunPolicy", "maxLatenessMillis")
        return ManagementProtocol.decode(RoutineSpec.serializer(), spec)
    }
    fun memory(value: JsonObject) = ManagementProtocol.decode(MemoryDraft.serializer(), JsonObject(value.filterKeys { it != "revision" }))
    fun boolean(value: JsonObject, key: String) = ManagementProtocol.bool(value, key)
    fun key(value: JsonObject) = ManagementProtocol.string(value, "requestKey")
    get("/api/profiles") { paired(call, backend) { json.encodeToString(owner().profiles()) } }
    post("/api/profiles") { paired(call, backend) { val v = request(call, "revision", "profile"); commit(owner().createProfile(revision(v), draft(v))) } }
    put("/api/profiles/{id}") { paired(call, backend) { val v = request(call, "revision", "profile"); commit(owner().editProfile(revision(v), id(call), draft(v))) } }
    post("/api/profiles/active") { paired(call, backend) {
        val v = request(call, "revision", "profileId"); commit(owner().switchProfile(revision(v), ManagementProtocol.string(v, "profileId")))
    } }
    delete("/api/profiles/{id}") { paired(call, backend) {
        val v = request(call, "revision", "deleteOwnedMemories"); commit(owner().deleteProfile(revision(v), id(call), boolean(v, "deleteOwnedMemories")))
    } }
    get("/api/profiles/{id}/memories") { paired(call, backend) {
        val query = call.request.queryParameters
        require(query.names().all { it in setOf("offset", "limit", "expectedRevision") } && query.names().all { query.getAll(it)?.size == 1 })
        fun number(name: String, default: Long? = null) = query[name]?.let { require(it.matches(Regex("0|[1-9][0-9]*"))); it.toLong() } ?: default
        val offset = number("offset", 0)!!; val limit = number("limit", 50)!!
        require(offset <= Int.MAX_VALUE && limit <= Int.MAX_VALUE)
        json.encodeToString(owner().memories(id(call), offset.toInt(), limit.toInt(), number("expectedRevision")))
    } }
    post("/api/profiles/{id}/memories") { paired(call, backend) {
        val v = request(call, "revision", "kind", "text", "tags", "shareWithAgent")
        commit(owner().saveMemory(revision(v), id(call), memory(v), source = OwnerMemorySource.PHONE))
    } }
    put("/api/profiles/{id}/memories/{memoryId}") { paired(call, backend) {
        val v = request(call, "revision", "kind", "text", "tags", "shareWithAgent")
        commit(owner().saveMemory(revision(v), id(call), memory(v), memoryId(call), OwnerMemorySource.PHONE))
    } }
    delete("/api/profiles/{id}/memories/{memoryId}") { paired(call, backend) {
        val v = request(call, "revision"); commit(owner().deleteMemory(revision(v), id(call), memoryId(call)))
    } }
    post("/api/profiles/{id}/memories/clear") { paired(call, backend) {
        val v = request(call, "revision", "ownerConfirmed"); commit(owner().clearMemories(revision(v), id(call), boolean(v, "ownerConfirmed")))
    } }
    get("/api/profiles/{id}/export") { paired(call, backend) { owner().exportProfile(id(call)) } }
    post("/api/household/reload") { paired(call, backend) { request(call); json.encodeToString(owner().reloadHousehold()) } }
    get("/api/apps") { paired(call, backend) { backend.apps() } }
    get("/api/routines") { paired(call, backend) { json.encodeToString(owner().routines()) } }
    post("/api/routines") { paired(call, backend) { val v = request(call, "spec"); json.encodeToString(owner().createRoutine(spec(v))) } }
    put("/api/routines/{id}") { paired(call, backend) { val v = request(call, "revision", "spec"); json.encodeToString(owner().updateRoutine(id(call), revision(v), spec(v))) } }
    delete("/api/routines/{id}") { paired(call, backend) { val v = request(call, "revision"); owner().deleteRoutine(id(call), revision(v)); "{}" } }
    post("/api/routines/{id}/enabled") { paired(call, backend) { val v = request(call, "revision", "enabled"); json.encodeToString(owner().enableRoutine(id(call), revision(v), boolean(v, "enabled"))) } }
    post("/api/routines/{id}/run") { paired(call, backend) { val v = request(call, "requestKey"); json.encodeToString(owner().runRoutine(id(call), key(v))) } }
    post("/api/routine-runs/{id}/cancel") { paired(call, backend) { request(call); json.encodeToString(owner().cancelRoutineRun(id(call))) } }
    post("/api/routine-runs/{id}/restart") { paired(call, backend) { val v = request(call, "requestKey"); json.encodeToString(owner().restartRoutineRun(id(call), key(v))) } }
    get("/api/external-control") { paired(call, backend) { json.encodeToString(owner().externalControl()) } }
    post("/api/external-control/enable") { paired(call, backend) { val v = request(call, "ownerConfirmed"); json.encodeToString(owner().enableExternalControl(boolean(v, "ownerConfirmed"))) } }
    post("/api/external-control/revoke") { paired(call, backend) { request(call); json.encodeToString(owner().revokeExternalControl()) } }
}
private val managementJson = Json { encodeDefaults = true }
private inline fun <reified T> commit(value: HouseholdCommit<T>) = buildJsonObject {
    put("revision", value.revision); put("value", managementJson.encodeToJsonElement(value.value))
}.toString()
