package app.vela.tv.player

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class PlaybackRequestTest {
    @Test fun `a ready decoder is not a claim that the requested item is playing`() = runTest {
        val request = PlaybackRequests.create("movie")
        val result = async { request.awaitStarted() }
        request.publish(PlaybackPhase.READY)
        assertFalse(result.isCompleted)
        request.publish(PlaybackPhase.PLAYING)
        assertEquals(PlaybackPhase.PLAYING, result.await().phase)
        assertEquals("movie", result.await().itemId)
        assertEquals(request.id, result.await().requestId)
    }
    @Test fun `failure and timeout are associated with the exact launch`() = runTest {
        val first = PlaybackRequests.create("same-movie")
        val second = PlaybackRequests.create("same-movie")
        first.publish(PlaybackPhase.FAILED, "Unsupported version.")
        second.publish(PlaybackPhase.PLAYING)
        assertEquals(PlaybackPhase.FAILED, first.awaitStarted().phase)
        assertEquals(PlaybackPhase.PLAYING, second.awaitStarted().phase)
        assertNull(PlaybackRequests.find(second.id, "different-movie"))
        val late = PlaybackRequests.create("late")
        assertEquals(PlaybackPhase.TIMED_OUT, late.awaitStarted(1).phase)
        late.publish(PlaybackPhase.PLAYING)
        assertEquals(PlaybackPhase.TIMED_OUT, late.state.value.phase)
    }
    @Test fun `cancelling the caller revokes pending playback instead of allowing a late launch`() = runTest {
        val request = PlaybackRequests.create("movie")
        val wait = async { request.awaitStarted() }
        testScheduler.runCurrent()
        wait.cancelAndJoin()
        assertEquals(PlaybackPhase.CANCELLED, request.state.value.phase)
        request.publish(PlaybackPhase.PLAYING)
        assertEquals(PlaybackPhase.CANCELLED, request.state.value.phase)
    }
}
