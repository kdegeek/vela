package app.vela.tv.mcp

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

@Serializable data class ExternalControlStatus(
    val enabled: Boolean = false, val address: String = "http://127.0.0.1:8766/mcp",
    val credentialPresent: Boolean = false, val error: String? = null,
)
@Serializable class ExternalControlProvision(val status: ExternalControlStatus, val token: String) {
    override fun toString() = "ExternalControlProvision(redacted)"
}
interface ExternalCredentialStorage { fun read(): String?; fun write(token: String?) }
interface ExternalControlListener { suspend fun start(service: McpService, authorization: McpBearerAuthorization); suspend fun stop() }

/** Lifecycle owns every invocation. Empty authorization is published before draining/revoking the listener. */
class ExternalControlManager(
    private val storage: ExternalCredentialStorage,
    private val listener: ExternalControlListener,
    private val scope: CoroutineScope,
    private val backend: () -> ExternalToolBackend,
    private val publish: (McpService?) -> Unit,
) {
    private val mutex = Mutex()
    private val mutableStatus = MutableStateFlow(ExternalControlStatus())
    val status: StateFlow<ExternalControlStatus> = mutableStatus.asStateFlow()
    @Volatile private var credentials = emptyList<McpCredential>()
    private var lifecycle: Job? = null
    private var service: McpService? = null
    private val authorization = McpBearerAuthorization { credentials }

    /** Restore only an already explicitly enabled credential. Never provision on startup. */
    suspend fun restore() = mutex.withLock {
        try { withContext(Dispatchers.IO) { storage.read() }?.let {
            mutableStatus.value = status.value.copy(credentialPresent = true)
            mount(it)
        } }
        catch (e: CancellationException) { withContext(NonCancellable) { shutdown() }; throw e }
        catch (_: Exception) { shutdown(); fail("External control could not start. Revoke and enable again.") }
    }
    suspend fun enable(ownerConfirmed: Boolean): ExternalControlProvision = mutex.withLock {
        require(ownerConfirmed)
        check(service == null && !status.value.credentialPresent) { "Revoke the existing credential before enabling again." }
        val token = McpCredential.generateToken()
        try {
            // Bind before publishing credentials. No accepted action is possible until the durable write succeeds.
            mount(token, authorize = false)
            // A failed/canceled write can have an unknown durable outcome. Require revoke before reprovisioning.
            mutableStatus.value = status.value.copy(credentialPresent = true)
            withContext(Dispatchers.IO) { storage.write(token) }
            credentials = listOf(McpCredential("owner-desktop", token))
            mutableStatus.value = ExternalControlStatus(true, credentialPresent = true)
            ExternalControlProvision(status.value, token)
        } catch (e: CancellationException) {
            withContext(NonCancellable) { shutdown() }; throw e
        } catch (_: Exception) {
            shutdown(); fail("External control could not be enabled. Check private storage and the loopback port.")
            throw app.vela.tv.runtime.RuntimeUnavailableException()
        }
    }
    suspend fun revoke(): ExternalControlStatus = mutex.withLock {
        withContext(NonCancellable) {
            val stopped = shutdown()
            try {
                withContext(Dispatchers.IO) { storage.write(null) }
                mutableStatus.value = ExternalControlStatus(error = if (stopped) null else "External control is unauthorized, but its listener could not close. Retry revoke.")
            } catch (_: Exception) { fail("External control stopped, but credential removal could not be saved. Retry revoke before restarting Vela.") }
            status.value
        }
    }
    private suspend fun mount(token: String, authorize: Boolean = true) {
        val credential = McpCredential("owner-desktop", token)
        val job = SupervisorJob(scope.coroutineContext[Job])
        lifecycle = job
        val next = McpService(backend(), CoroutineScope(scope.coroutineContext + job))
        service = next
        publish(next)
        listener.start(next, authorization)
        credentials = if (authorize) listOf(credential) else emptyList()
        mutableStatus.value = ExternalControlStatus(authorize, credentialPresent = authorize)
    }
    private suspend fun shutdown() = withContext(NonCancellable) {
        credentials = emptyList()
        mutableStatus.value = status.value.copy(enabled = false)
        service?.cancelAll(); service?.close()
        lifecycle?.cancelAndJoin()
        lifecycle = null; service = null; publish(null)
        try { listener.stop(); true } catch (_: Exception) { false }
    }
    private fun fail(message: String) { mutableStatus.value = ExternalControlStatus(credentialPresent = status.value.credentialPresent, error = message) }
}
