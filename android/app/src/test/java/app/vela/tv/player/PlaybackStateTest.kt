package app.vela.tv.player

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class PlaybackStateTest {
    @Test fun `failure before preparation preserves resume without writing a false stop`() {
        val state = PlaybackState(120_000, 600_000)
        state.observe(0)
        assertEquals(120_000L, state.positionMs)
        assertNull(state.stop(failed = true))
    }

    @Test fun `ready resume can seek backwards and stop is reported once`() {
        val state = PlaybackState(120_000, 600_000)
        state.observe(120_000, isReady = true)
        state.observe(30_000)
        assertEquals(PlaybackStop(300_000_000, false, false), state.stop())
        state.observe(0, isReady = true)
        assertEquals(30_000L, state.positionMs)
        assertNull(state.stop())
    }

    @Test fun `a stop near the end is not completion but ENDED is`() {
        val nearEnd = PlaybackState(0, 600_000)
        nearEnd.observe(599_000, isReady = true)
        assertFalse(nearEnd.stop()!!.completed)
        val ended = PlaybackState(0, 600_000)
        ended.observe(0, isReady = true)
        ended.end(599_000)
        assertEquals(PlaybackStop(6_000_000_000, true, false), ended.stop())
    }

    @Test fun `failed playback cannot become watched even after an end signal`() {
        val state = PlaybackState(0, 100)
        state.observe(90, true)
        state.end(100)
        assertFalse(state.stop(failed = true)!!.completed)
    }

    @Test fun `unknown duration keeps valid positions and corrupt positions do not overflow`() {
        val state = PlaybackState(0)
        state.observe(Long.MAX_VALUE, true)
        assertTrue(state.stop()!!.positionTicks > 0)
        val bounded = PlaybackState(20, 100)
        bounded.observe(-1, true)
        assertEquals(0L, bounded.positionMs)
        bounded.observe(300)
        assertEquals(100L, bounded.positionMs)
    }

    @Test fun `a delayed start cannot arrive after stop and a failing progress cannot strand stop`() = runTest {
        val queue = PlaybackReportQueue(this)
        val release = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        queue.submit { release.await(); order += "start" }
        queue.submit { order += "progress"; throw java.io.IOException("offline") }
        queue.submit { order += "stop" }
        queue.close()
        assertTrue(order.isEmpty())
        release.complete(Unit)
        queue.drain()
        assertEquals(listOf("start", "progress", "stop"), order)
    }

    @Test fun `slow reporting coalesces periodic checkpoints without moving them after stop`() = runTest {
        val queue = PlaybackReportQueue(this)
        val order = mutableListOf<String>()
        queue.submit { order += "start" }
        repeat(1000) { number -> queue.progress { order += "progress-$number" } }
        queue.submit { order += "stop" }
        queue.submit { order += "next-start" }
        queue.progress { order += "next-progress" }
        queue.drain()
        assertEquals(listOf("start", "progress-999", "stop", "next-start", "next-progress"), order)
    }
}
