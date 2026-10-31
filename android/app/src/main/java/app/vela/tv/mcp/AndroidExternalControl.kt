package app.vela.tv.mcp

import android.content.Context
import app.vela.tv.runtime.PrivateRuntimeFile
import io.ktor.server.cio.CIO
import io.ktor.server.engine.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext
import java.io.File

class AndroidExternalCredentials(context: Context) : ExternalCredentialStorage {
    private val file = PrivateRuntimeFile(File(context.noBackupFilesDir, "external-control-credential"))
    override fun read(): String? = file.read(256)?.toString(Charsets.US_ASCII)?.takeIf { it.isNotEmpty() }
    override fun write(token: String?) = file.write(token.orEmpty().toByteArray(Charsets.US_ASCII))
}
class LoopbackMcpListener(private val port: Int = 8766) : ExternalControlListener {
    private var server: EmbeddedServer<*, *>? = null
    private var listenerJob: Job? = null
    override suspend fun start(service: McpService, authorization: McpBearerAuthorization) = withContext(Dispatchers.IO) {
        check(server == null)
        // Ktor's CoroutineScope overload inherits its receiver's Job. The short startup
        // withContext must not own the long-lived listener or it waits forever to return.
        val lifetime = SupervisorJob()
        listenerJob = lifetime
        val next = CoroutineScope(Dispatchers.IO + lifetime).embeddedServer(CIO, host = "127.0.0.1", port = port) { mcpApi(service, authorization) }
        server = next
        try { next.start(wait = false); Unit } catch (e: Exception) {
            withContext(NonCancellable) {
                try { next.stop(0, 1000) } finally { lifetime.cancelAndJoin(); server = null; listenerJob = null }
            }
            throw e
        }
    }
    override suspend fun stop() = withContext(Dispatchers.IO) {
        try { server?.stop(0, 1000) }
        finally { listenerJob?.cancelAndJoin(); listenerJob = null; server = null }
        Unit
    }
}
