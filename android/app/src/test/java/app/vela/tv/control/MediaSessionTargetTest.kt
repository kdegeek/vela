package app.vela.tv.control

import app.vela.tv.agent.ActionInvalidatedException
import app.vela.tv.agent.PreparedToolAction
import app.vela.tv.agent.Tool
import app.vela.tv.agent.ToolCall
import app.vela.tv.agent.ToolExecutor
import app.vela.tv.agent.ToolSpec
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MediaSessionTargetTest {
    // Equal hashes deliberately model why a hash/toString must never identify an authorized session.
    private data class Token(val value: String) {
        override fun hashCode(): Int = 1
        override fun toString(): String = "not-an-authorizing-identity"
    }
    private class Session(
        override val identity: Any = Token("original"),
        override val packageName: String = "com.example.player",
        phase: NowPlayingPhase = NowPlayingPhase.PAUSED,
        actions: Long = 1L or 2L or 4L or 16L or 32L or 256L,
    ) : MediaSessionTarget {
        var state = MediaSessionPlayback(phase, NowPlayingCapabilities(actions),
            NowPlayingPosition(10_000, 100_000, 1_000, 1f, phase), "Observed title")
        val sent = mutableListOf<Pair<NowPlayingAction, Long?>>()
        var onRead: () -> Unit = {}
        override fun playback(): MediaSessionPlayback { onRead(); return state }
        override fun dispatch(action: NowPlayingAction, seekMs: Long?) { sent += action to seekMs }
    }
    private class Fixture {
        var now = 1_000L
        val original = Session()
        var active: List<MediaSessionTarget> = listOf(original)
        var onList: () -> Unit = {}
        val store = MediaSessionTargetStore({ now })
        fun reference(target: MediaSessionTarget = original) = store.reference(target, active)!!
        fun commands(dispatcher: kotlinx.coroutines.CoroutineDispatcher) = MediaSessionCommands(store,
            { onList(); active }, { now }, dispatcher)
    }
    private suspend fun invalidated(action: PreparedToolAction) {
        try { action.execute(); fail("Expected invalidated target") }
        catch (_: ActionInvalidatedException) { }
    }

    @Test fun replacementBetweenPrepareAndExecuteStopsExecutorWithoutAnyDispatch() = runTest {
        val f = Fixture()
        val commands = f.commands(StandardTestDispatcher(testScheduler))
        val reference = f.reference()
        val replacement = Session(Token("replacement"))
        val tool = object : Tool {
            override val spec = ToolSpec("media_control", "injected target", JsonObject(emptyMap()))
            override suspend fun run(args: JsonObject) = error("Must prepare")
            override suspend fun prepare(args: JsonObject) =
                commands.prepare(MediaSessionCommandRequest("play", sessionReference = reference)).also {
                    f.active = listOf(replacement)
                }
        }
        var stopped = 0
        val result = ToolExecutor(listOf(tool)).execute(ToolCall("one", "media_control", JsonObject(emptyMap())),
            confirm = { _, _ -> fail("No approval may substitute for identity"); false }, stopped = { stopped++ })
        assertTrue(result.isError)
        assertEquals(1, stopped)
        assertTrue(f.original.sent.isEmpty())
        assertTrue(replacement.sent.isEmpty())
    }

    @Test fun packageCompatibleCallsAlsoCaptureIdentityBeforeExecution() = runTest {
        val f = Fixture()
        val prepared = f.commands(StandardTestDispatcher(testScheduler))
            .prepare(MediaSessionCommandRequest("play", packageName = f.original.packageName))
        val replacement = Session(Token("replacement"))
        f.active = listOf(replacement)
        invalidated(prepared)
        assertTrue(f.original.sent.isEmpty())
        assertTrue(replacement.sent.isEmpty())
    }

    @Test fun reenumeratedControllerWithEqualTokenDoesNotReplaceCapturedController() = runTest {
        val f = Fixture()
        val prepared = f.commands(StandardTestDispatcher(testScheduler))
            .prepare(MediaSessionCommandRequest("play", sessionReference = f.reference()))
        val reenumerated = Session(Token("original"))
        f.active = listOf(reenumerated)
        val result = prepared.execute()
        assertFalse(result.isError)
        assertEquals(listOf(NowPlayingAction.PLAY to null), f.original.sent)
        assertTrue(reenumerated.sent.isEmpty())
        assertTrue(result.content.contains("Playback may not have changed"))
    }

    @Test fun samePackageDuplicatesRequireExactReferenceAndThenAddressOnlyOne() = runTest {
        val f = Fixture()
        val second = Session(Token("second"))
        f.active = listOf(f.original, second)
        val commands = f.commands(StandardTestDispatcher(testScheduler))
        assertTrue(commands.prepare(MediaSessionCommandRequest("play", packageName = f.original.packageName)).execute().isError)
        assertTrue(commands.prepare(MediaSessionCommandRequest("play")).execute().isError)
        val reference = f.reference(second)
        commands.prepare(MediaSessionCommandRequest("play", packageName = second.packageName, sessionReference = reference)).execute()
        assertTrue(f.original.sent.isEmpty())
        assertEquals(listOf(NowPlayingAction.PLAY to null), second.sent)
    }

    @Test fun duplicatePackageAppearingAfterPackagePreparationInvalidatesIt() = runTest {
        val f = Fixture()
        val prepared = f.commands(StandardTestDispatcher(testScheduler)).prepare(MediaSessionCommandRequest("play"))
        val second = Session(Token("second"))
        f.active = listOf(f.original, second)
        invalidated(prepared)
        assertTrue(f.original.sent.isEmpty())
        assertTrue(second.sent.isEmpty())
    }

    @Test fun packageAndReferenceMustAgreeWithNoFallback() = runTest {
        val f = Fixture()
        val commands = f.commands(StandardTestDispatcher(testScheduler))
        val result = commands.prepare(MediaSessionCommandRequest("play", packageName = "other.app", sessionReference = f.reference())).execute()
        assertTrue(result.isError)
        assertTrue(result.content.contains("does not belong"))
        assertTrue(f.original.sent.isEmpty())
    }

    @Test fun invalidExpiredAndUnknownReferencesNeverFallBackToPackage() = runTest {
        val f = Fixture()
        val commands = f.commands(StandardTestDispatcher(testScheduler))
        val reference = f.reference()
        f.now += 300_000
        for (ref in listOf(reference, "ms_12345678-1234-1234-1234-123456789abc_zzz", "session-1", "", "x".repeat(500))) {
            assertTrue(commands.prepare(MediaSessionCommandRequest("play", packageName = f.original.packageName, sessionReference = ref)).execute().isError)
        }
        assertTrue(f.original.sent.isEmpty())
    }

    @Test fun defaultSelectsOnlyAUniquePlayingSessionOrTheOnlySession() = runTest {
        val f = Fixture()
        f.original.state = f.original.state.copy(phase = NowPlayingPhase.PLAYING)
        val other = Session(Token("other"), "other.app")
        f.active = listOf(other, f.original)
        val commands = f.commands(StandardTestDispatcher(testScheduler))
        assertFalse(commands.prepare(MediaSessionCommandRequest("pause")).execute().isError)
        assertEquals(listOf(NowPlayingAction.PAUSE to null), f.original.sent)
        other.state = other.state.copy(phase = NowPlayingPhase.PLAYING)
        assertTrue(commands.prepare(MediaSessionCommandRequest("pause")).execute().isError)
        assertTrue(other.sent.isEmpty())
    }

    @Test fun duplicateTokenIdentityCannotBeResolvedEvenOutsideDisplayLimit() = runTest {
        val f = Fixture()
        val oldReference = f.reference()
        f.active = listOf(f.original) + (1..24).map { Session(Token("$it"), "other.app$it") } + Session(Token("original"))
        val commands = f.commands(StandardTestDispatcher(testScheduler))
        assertTrue(commands.prepare(MediaSessionCommandRequest("play", sessionReference = oldReference)).execute().isError)
        assertNull(f.store.reference(f.original, f.active))
        assertFalse(f.store.observe(f.active).any { it.target.identity == f.original.identity })
        assertTrue(f.original.sent.isEmpty())
    }

    @Test fun unsupportedCapabilitiesAreDeniedAtPreparation() = runTest {
        for (action in listOf("play", "pause", "stop", "next", "previous", "seek", "skip")) {
            val f = Fixture()
            f.original.state = f.original.state.copy(capabilities = NowPlayingCapabilities(0))
            val command = MediaSessionCommandRequest(action, seconds = if (action in listOf("seek", "skip")) 5 else null)
            assertTrue(f.commands(StandardTestDispatcher(testScheduler)).prepare(command).execute().isError)
            assertTrue(f.original.sent.isEmpty())
        }
    }

    @Test fun disappearingCapabilitiesAndChangedPhaseAreRecheckedBeforeDispatch() = runTest {
        val f = Fixture()
        val commands = f.commands(StandardTestDispatcher(testScheduler))
        val prepared = commands.prepare(MediaSessionCommandRequest("play"))
        f.original.state = f.original.state.copy(capabilities = NowPlayingCapabilities(0))
        invalidated(prepared)
        f.original.state = f.original.state.copy(capabilities = NowPlayingCapabilities(512))
        val combined = commands.prepare(MediaSessionCommandRequest("play"))
        f.original.state = f.original.state.copy(phase = NowPlayingPhase.PLAYING)
        invalidated(combined)
        assertTrue(f.original.sent.isEmpty())
    }

    @Test fun everySupportedActionDispatchesOnceWithoutRetry() = runTest {
        for (action in NowPlayingAction.entries) {
            val f = Fixture()
            if (action != NowPlayingAction.PLAY) f.original.state = f.original.state.copy(phase = NowPlayingPhase.PLAYING)
            val prepared = f.commands(StandardTestDispatcher(testScheduler)).prepare(MediaSessionCommandRequest(action.wireName,
                seconds = if (action == NowPlayingAction.SEEK) 20 else null))
            assertFalse(prepared.execute().isError)
            assertEquals(listOf(action to if (action == NowPlayingAction.SEEK) 20_000L else null), f.original.sent)
            try { prepared.execute(); fail("Must be single use") } catch (_: IllegalStateException) { }
            assertEquals(1, f.original.sent.size)
        }
    }

    @Test fun seekAndSkipUseFreshBoundedPositionAndDuration() = runTest {
        val f = Fixture()
        val commands = f.commands(StandardTestDispatcher(testScheduler))
        val prepared = commands.prepare(MediaSessionCommandRequest("skip", 10))
        f.original.state = f.original.state.copy(phase = NowPlayingPhase.PLAYING,
            position = f.original.state.position.copy(phase = NowPlayingPhase.PLAYING))
        f.now = 6_000
        prepared.execute()
        assertEquals(NowPlayingAction.SEEK to 25_000L, f.original.sent.last())
        commands.prepare(MediaSessionCommandRequest("seek", Int.MAX_VALUE)).execute()
        assertEquals(NowPlayingAction.SEEK to 100_000L, f.original.sent.last())
        commands.prepare(MediaSessionCommandRequest("skip", Int.MIN_VALUE)).execute()
        assertEquals(NowPlayingAction.SEEK to 0L, f.original.sent.last())
        val shrink = commands.prepare(MediaSessionCommandRequest("seek", 100))
        f.original.state = f.original.state.copy(position = f.original.state.position.copy(durationMs = 12_345))
        shrink.execute()
        assertEquals(NowPlayingAction.SEEK to 12_345L, f.original.sent.last())
    }

    @Test fun seekNeedsPositionDurationAndIntegerAmount() = runTest {
        val f = Fixture()
        val commands = f.commands(StandardTestDispatcher(testScheduler))
        for (request in listOf(MediaSessionCommandRequest("seek"), MediaSessionCommandRequest("skip"),
            MediaSessionCommandRequest("play", 10), MediaSessionCommandRequest("unknown"),
            MediaSessionCommandRequest("play", packageName = "")))
            assertTrue(commands.prepare(request).execute().isError)
        val prepared = commands.prepare(MediaSessionCommandRequest("seek", 10))
        f.original.state = f.original.state.copy(position = f.original.state.position.copy(durationMs = null))
        invalidated(prepared)
        assertTrue(commands.prepare(MediaSessionCommandRequest("skip", 10)).execute().isError)
        f.original.state = f.original.state.copy(position = f.original.state.position.copy(durationMs = 100_000, positionMs = null))
        assertTrue(commands.prepare(MediaSessionCommandRequest("seek", 10)).execute().isError)
        assertTrue(f.original.sent.isEmpty())
    }

    @Test fun cancellationBeforeMainDispatchDoesNotSendCommand() = runTest {
        val f = Fixture()
        val commands = f.commands(StandardTestDispatcher(testScheduler))
        val prepared = commands.prepare(MediaSessionCommandRequest("play"))
        val job = async { prepared.execute() }
        job.cancel()
        runCurrent()
        assertTrue(job.isCancelled)
        assertTrue(f.original.sent.isEmpty())
    }

    @Test fun cancellationDuringLiveReadHasNoPostStopDispatch() = runTest {
        val f = Fixture()
        val prepared = f.commands(StandardTestDispatcher(testScheduler)).prepare(MediaSessionCommandRequest("play"))
        val job = async {
            val ownJob = currentCoroutineContext()[Job]!!
            f.original.onRead = { ownJob.cancel() }
            prepared.execute()
        }
        try { job.await(); fail("Must propagate cancellation") } catch (_: CancellationException) { }
        assertTrue(f.original.sent.isEmpty())
    }

    @Test fun expiryOrRevocationDuringFinalCapabilityReadPreventsDispatch() = runTest {
        for (expire in listOf(true, false)) {
            val f = Fixture()
            val prepared = f.commands(StandardTestDispatcher(testScheduler)).prepare(MediaSessionCommandRequest("play"))
            f.original.onRead = {
                if (expire) f.now += 300_000 else f.store.invalidate(f.original.identity)
            }
            invalidated(prepared)
            assertTrue(f.original.sent.isEmpty())
        }
    }

    @Test fun failedSystemReadClearsReferencesAndCannotRedirect() = runTest {
        val f = Fixture()
        val reference = f.reference()
        val prepared = f.commands(StandardTestDispatcher(testScheduler)).prepare(MediaSessionCommandRequest("play", sessionReference = reference))
        f.onList = { throw SecurityException("revoked") }
        try { prepared.execute(); fail("Must fail closed") } catch (_: SecurityException) { }
        f.onList = {}
        assertNull(f.store.resolve(reference, f.active))
        assertTrue(f.original.sent.isEmpty())
    }

    @Test fun statusReturnsUsableBoundedReferencesAndOnlyObservedMetadata() = runTest {
        val f = Fixture()
        f.original.state = f.original.state.copy(title = "x".repeat(20_000))
        f.active = listOf(f.original) + (1..30).map { Session(Token("$it"), "other.app$it") }
        val result = f.commands(StandardTestDispatcher(testScheduler)).status()
        val references = Regex("session=(ms_[a-z0-9_-]+)").findAll(result.content).map { it.groupValues[1] }.toList()
        assertEquals(24, references.size)
        assertEquals(24, references.distinct().size)
        assertTrue(references.all { f.store.resolve(it, f.active) != null })
        assertFalse(result.content.contains("x".repeat(241)))
        assertFalse(result.content.contains("not-an-authorizing-identity"))
        assertTrue(result.content.contains("state=Paused"))
        assertTrue(result.content.contains("actions=[play,stop,previous,next,seek]"))
        assertTrue(result.content.contains("omitted"))
    }

    @Test fun tokenHashCollisionDoesNotGrantReferenceToReplacement() {
        val f = Fixture()
        val reference = f.reference()
        val collision = Session(Token("collision"))
        assertEquals(f.original.identity.hashCode(), collision.identity.hashCode())
        assertNull(f.store.resolve(reference, listOf(collision)))
        assertNotEquals(reference, f.store.reference(collision, listOf(collision)))
    }

    @Test fun referenceLifetimeIsAbsoluteAndNeverExtendedByObservation() {
        val f = Fixture()
        val reference = f.reference()
        f.now += 299_999
        assertEquals(reference, f.reference())
        assertNotNull(f.store.resolve(reference, f.active))
        f.now += 1
        assertNull(f.store.resolve(reference, f.active))
        val renewed = f.reference()
        assertNotEquals(reference, renewed)
        f.store.clear()
        assertNull(f.store.resolve(renewed, f.active))
        assertNotEquals(renewed, f.reference())
    }

    @Test fun disappearanceDestructionAndProcessReplacementCannotReviveOldReferences() {
        val f = Fixture()
        val reference = f.reference()
        f.store.reconcile(emptyList())
        assertNull(f.store.resolve(reference, f.active))
        val renewed = f.reference()
        f.store.invalidate(f.original.identity)
        assertNull(f.store.resolve(renewed, f.active))
        val anotherProcess = MediaSessionTargetStore({ f.now })
        assertNull(anotherProcess.resolve(reference, f.active))
        assertNotEquals(reference, anotherProcess.reference(f.original, f.active))
    }

    @Test fun backwardsClockInvalidatesReferences() {
        val f = Fixture()
        val reference = f.reference()
        f.now--
        assertNull(f.store.resolve(reference, f.active))
    }

    @Test fun capacityEvictionIsBoundedAndAnObservationNeverEvictsItsOwnReferences() {
        var now = 10L
        val store = MediaSessionTargetStore({ now }, capacity = 2, lifetimeMs = 100)
        val a = Session(Token("a"))
        val b = Session(Token("b"))
        val c = Session(Token("c"))
        val all = listOf(a, b, c)
        val first = store.reference(a, all)!!
        store.reference(c, all)
        val observed = store.observe(all)
        assertEquals(listOf(a, b), observed.map { it.target })
        assertTrue(observed.all { store.resolve(it.reference, all) === it.target })
        store.reference(c, all)
        assertNull(store.resolve(first, all))
        now += 100
        assertTrue(observed.all { store.resolve(it.reference, all) == null })
    }
}
