package app.vela.tv.agent

import app.vela.tv.player.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackToolOutcomeTest {
    @Test fun `activity launch and readiness cannot claim observed playback`() {
        for (phase in PlaybackPhase.entries) {
            val outcome = playbackToolOutcome(PlaybackOutcome("request", "item", phase, "private token"))
            assertEquals(phase != PlaybackPhase.PLAYING, outcome.isError)
            assertFalse(outcome.content.contains("private token"))
        }
    }
    @Test fun `another item playing cannot satisfy this launch's awaited result`() = runTest {
        val first = PlaybackRequests.create("first")
        val second = PlaybackRequests.create("second")
        val waiting = async { playbackToolOutcome(second.awaitStarted(1000)) }
        runCurrent()
        first.publish(PlaybackPhase.PLAYING)
        runCurrent()
        assertFalse(waiting.isCompleted)
        second.publish(PlaybackPhase.FAILED, "private server error")
        runCurrent()
        assertTrue(waiting.await().isError)
    }
    @Test fun `playback timeout returns failure and cancels a late start`() = runTest {
        val request = PlaybackRequests.create("slow")
        val result = async { playbackToolOutcome(request.awaitStarted(100)) }
        advanceTimeBy(100); runCurrent()
        assertTrue(result.await().isError)
        request.publish(PlaybackPhase.PLAYING)
        assertEquals(PlaybackPhase.TIMED_OUT, request.state.value.phase)
    }
}
