package app.vela.tv

import android.app.Application
import app.vela.tv.agent.Runner
import app.vela.tv.agent.TvTools
import app.vela.tv.integrations.ControlArbiter
import app.vela.tv.integrations.ControlApprovalBroker
import app.vela.tv.mcp.*
import app.vela.tv.profile.*
import app.vela.tv.routines.*
import app.vela.tv.runtime.*
import app.vela.tv.settings.SettingsStore
import app.vela.tv.web.PhoneServer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

class VelaApp : Application(), RoutineRuntimeOwner {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val control = ControlArbiter()
    val externalApprovals = ControlApprovalBroker(uptimeMs = android.os.SystemClock::uptimeMillis)
    private val mutableHousehold = MutableStateFlow<HouseholdStore?>(null)
    val householdReady: StateFlow<HouseholdStore?> = mutableHousehold.asStateFlow()
    private val mutableStatus = MutableStateFlow(RuntimeStatus())
    val runtimeStatus: StateFlow<RuntimeStatus> = mutableStatus.asStateFlow()
    private val householdLock = Mutex()
    private val routineLock = Mutex()
    private var engine: RoutineEngine? = null
    lateinit var settings: SettingsStore
        private set
    lateinit var runner: Runner
        private set
    lateinit var phone: PhoneServer
        private set
    lateinit var management: OwnerManagement
        private set
    var mcpService: McpService? = null
        private set
    private lateinit var external: ExternalControlManager
    private lateinit var bindings: RoutineBindings

    fun householdOrNull(): HouseholdStore? = householdReady.value
    suspend fun householdStore(): HouseholdStore = householdLock.withLock {
        householdReady.value ?: try {
            AndroidHouseholds.open(this).also { store ->
                mutableHousehold.value = store
                mutableStatus.update { it.copy(householdError = null) }
                scope.launch { store.state.collect { state ->
                    mutableStatus.update { it.copy(householdError = (state as? HouseholdState.Unavailable)?.message) }
                } }
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            mutableStatus.update { it.copy(householdError = "Household storage could not be opened. Retry storage recovery in Settings.") }
            throw RuntimeUnavailableException()
        }
    }
    override suspend fun routineEngine(): RoutineEngine = routineLock.withLock {
        engine ?: try {
            householdStore()
            withContext(Dispatchers.IO) {
                AndroidRoutineScheduler(this@VelaApp).ensureScheduled()
                bindings.validate()
                RoutineEngine.open(AtomicRoutineStorage(this@VelaApp), RoutineSubmission(
                    reserve = { request ->
                        val run = engine?.state?.value?.history?.singleOrNull { it.id == request.runId }
                        val document = householdStore().snapshot()
                        requireRoutineProfile(request, run, document, bindings)
                        runner.submit(request.task)
                    },
                    await = runner::await, cancelAndJoin = runner::cancelAndJoin,
                )).also { pauseUnboundRoutines(it) }
            }.also { engine = it; mutableStatus.update { state -> state.copy(routineError = null) } }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            mutableStatus.update { it.copy(routineError = "Routine storage or Android scheduling is unavailable. Saved work was retained.") }
            throw RuntimeUnavailableException()
        }
    }
    private fun wakeRoutines() { scope.launch(Dispatchers.IO) {
        try { routineEngine().onWake(); mutableStatus.update { it.copy(routineError = null) } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { mutableStatus.update { it.copy(routineError = "Routine wake or storage failed. Inspect saved history before retrying.") } }
    } }
    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
        val bindingFile = PrivateRuntimeFile(File(noBackupFilesDir, "routine-profiles-v1.json"))
        bindings = RoutineBindings({ bindingFile.read(RoutineBindings.MAX_DOCUMENT_BYTES) }, bindingFile::write)
        runner = Runner(this, scope)
        external = ExternalControlManager(AndroidExternalCredentials(this), LoopbackMcpListener(), scope,
            backend = { GuardedToolBackend(TvTools(this).all(), control,
                approve = { call, hit -> externalApprovals.confirm(call.controller, hit) },
                admission = { controller, action -> runner.withController(controller, action) }) },
            publish = { mcpService = it })
        management = OwnerManagement(control, runner.running,
            stopAndDrain = {
                runner.stop()
                runner.running.first { !it }
                control.owner.first { it == null }
            }, invalidateContext = runner::clear, household = ::householdStore,
            engine = ::routineEngine, bindings = bindings, wake = ::wakeRoutines, external = external)
        phone = PhoneServer(this)
        try { phone.start() } catch (_: Exception) {
            mutableStatus.update { it.copy(phoneError = "Phone service could not start. Restart Vela after checking its port.") }
        }
        scope.launch { try { householdStore() } catch (_: RuntimeUnavailableException) { } }
        scope.launch { external.restore() }
        scope.launch { external.status.collect { status -> mutableStatus.update { it.copy(externalError = status.error) } } }
        // Covers startup and both internal/external controller completion. Never await a wake while holding control.
        scope.launch {
            combine(runner.running, control.owner) { running, owner -> !running && owner == null }
                .distinctUntilChanged().collect { idle -> if (idle) wakeRoutines() }
        }
    }
}
