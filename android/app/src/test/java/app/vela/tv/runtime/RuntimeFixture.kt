package app.vela.tv.runtime

import app.vela.tv.agent.ToolOutcome
import app.vela.tv.agent.ToolSpec
import app.vela.tv.integrations.ControlArbiter
import app.vela.tv.mcp.*
import app.vela.tv.profile.*
import app.vela.tv.routines.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonObject

class RuntimeFixture(private val scope: CoroutineScope) {
    val control = ControlArbiter()
    val running = MutableStateFlow(false)
    var clears = 0; var stops = 0; var wakes = 0
    var householdBytes: ByteArray? = null
    var failHouseholdWrites = false
    var routineState = RoutineSnapshot()
    var bindingBytes: ByteArray? = null
    val bindings = RoutineBindings({ bindingBytes }, { bindingBytes = it })
    val external = ExternalControlManager(object : ExternalCredentialStorage {
        override fun read(): String? = null
        override fun write(token: String?) { }
    }, object : ExternalControlListener {
        override suspend fun start(service: McpService, authorization: McpBearerAuthorization) { }
        override suspend fun stop() { }
    }, scope, { object : ExternalToolBackend {
        override val tools = emptyList<ToolSpec>()
        override fun argumentError(name: String, arguments: JsonObject): String? = null
        override suspend fun executeGuarded(call: ExternalToolCall) = ToolOutcome("unused")
    } }, {})
    lateinit var store: HouseholdStore
    lateinit var engine: RoutineEngine
    suspend fun management(): OwnerManagement {
        store = HouseholdStore.open(object : HouseholdStorage {
            override fun read() = householdBytes
            override fun writeAtomically(bytes: ByteArray) { if (failHouseholdWrites) error("private-storage-cause"); householdBytes = bytes }
        })
        engine = RoutineEngine.open(object : RoutineStorage {
            override fun read() = routineState
            override fun write(snapshot: RoutineSnapshot) { routineState = snapshot }
        }, { RoutineTaskResult.Completed() }, now = { 1_000_000 })
        return OwnerManagement(control, running, { stops++; running.value = false }, { clears++ }, { store }, { engine }, bindings, { wakes++ }, external)
    }
}
