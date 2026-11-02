package app.vela.tv.mcp

import app.vela.tv.runtime.RuntimeUnavailableException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ExternalControlManagerTest {
    private class Storage : ExternalCredentialStorage {
        var token: String? = null; var failed = false
        override fun read(): String? = token
        override fun write(token: String?) { if (failed) error("private-storage-detail"); this.token = token }
    }
    private class Listener : ExternalControlListener {
        var starts = 0; var stops = 0; var failed = false
        lateinit var authorization: McpBearerAuthorization
        override suspend fun start(service: McpService, authorization: McpBearerAuthorization) {
            starts++; this.authorization = authorization
            if (failed) error("private-port-detail")
        }
        override suspend fun stop() { stops++ }
    }
    @Test fun `default disabled explicit enable returns once restore reuses and revoke clears`() = runTest {
        val f = McpFixture(); val storage = Storage(); val listener = Listener(); var published: McpService? = null
        val manager = ExternalControlManager(storage, listener, backgroundScope, { f.backend() }, { published = it })
        manager.restore(); assertEquals(0, listener.starts); assertNull(published); assertFalse(manager.status.value.enabled)
        try { manager.enable(false); fail() } catch (_: IllegalArgumentException) { }
        val provision = manager.enable(true)
        assertEquals(storage.token, provision.token)
        assertNotNull(listener.authorization.authenticate("Bearer ${provision.token}"))
        assertFalse(provision.toString().contains(provision.token)); assertFalse(manager.status.value.toString().contains(provision.token))
        try { manager.enable(true); fail() } catch (_: IllegalStateException) { }
        val revoked = manager.revoke()
        assertFalse(revoked.enabled); assertFalse(revoked.credentialPresent); assertNull(storage.token); assertNull(published)
        assertNull(listener.authorization.authenticate("Bearer ${provision.token}"))
        val again = manager.enable(true); assertNotEquals(provision.token, again.token); manager.revoke()
    }
    @Test fun `revoke waits for in-flight approval cleanup and rejects the old credential before drain`() = runTest {
        val f = McpFixture(); f.check = { app.vela.tv.guard.GuardHit(app.vela.tv.guard.Risk.PURCHASE, "Buy") }
        val entered = CompletableDeferred<Unit>(); val cleanup = CompletableDeferred<Unit>()
        f.approval = { try { entered.complete(Unit); awaitCancellation() } finally { withContext(NonCancellable) { cleanup.await() } } }
        val listener = Listener(); var published: McpService? = null
        val manager = ExternalControlManager(Storage(), listener, backgroundScope, { f.backend() }, { published = it })
        val provision = manager.enable(true)
        val principal = listener.authorization.authenticate("Bearer ${provision.token}")!!
        published!!.modernCall(principal); entered.await()
        val revoke = async { manager.revoke() }; runCurrent()
        assertFalse(revoke.isCompleted)
        assertNull(listener.authorization.authenticate("Bearer ${provision.token}"))
        assertNotNull(f.control.owner.value)
        cleanup.complete(Unit); revoke.await(); assertNull(f.control.owner.value); assertEquals(0, f.effects)
    }
    @Test fun `mount storage and revoke failures expose safe errors and recovery remains explicit`() = runTest {
        val f = McpFixture(); val storage = Storage(); val listener = Listener()
        val manager = ExternalControlManager(storage, listener, backgroundScope, { f.backend() }, {})
        listener.failed = true
        try { manager.enable(true); fail() } catch (_: RuntimeUnavailableException) { }
        assertNull(storage.token); assertFalse(manager.status.value.enabled); assertFalse(manager.status.value.error!!.contains("private-port-detail"))
        listener.failed = false; val token = manager.enable(true).token
        storage.failed = true
        assertNotNull(manager.revoke().error); assertTrue(manager.status.value.credentialPresent)
        assertNull(listener.authorization.authenticate("Bearer $token"))
        storage.failed = false; assertNull(manager.revoke().error); assertNull(storage.token)
        storage.token = "malformed"; manager.restore(); assertFalse(manager.status.value.enabled); assertNotNull(manager.status.value.error)
        manager.revoke()
    }
}
