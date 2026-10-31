package app.vela.tv.media

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ResumeCheckpointsTest {
    @Test fun `failed user data is retained and retried without replaying a session action`() = runTest {
        var attempts = 0
        try { ResumeCheckpoints.save("retry-test") { attempts++; if (attempts == 1) throw IOException("offline") } } catch (_: IOException) { }
        assertEquals(1, attempts)
        ResumeCheckpoints.retryPending()
        assertEquals(2, attempts)
        ResumeCheckpoints.retryPending()
        assertEquals(2, attempts)
    }
    @Test fun `newer positions replace a failed checkpoint including backwards seeks`() = runTest {
        val positions = mutableListOf<Long>()
        try { ResumeCheckpoints.save("seek-test") { throw IOException("offline") } } catch (_: IOException) { }
        ResumeCheckpoints.save("seek-test") { positions += 50 }
        ResumeCheckpoints.retryPending()
        assertEquals(listOf(50L), positions)
    }
    @Test fun `in-flight retry cannot overwrite a newer checkpoint from a recreated activity`() = runTest {
        val release = CompletableDeferred<Unit>()
        val positions = mutableListOf<Int>()
        val first = async { ResumeCheckpoints.save("race-test") { release.await(); positions += 1 } }
        testScheduler.runCurrent()
        val next = async { ResumeCheckpoints.save("race-test") { positions += 2 } }
        testScheduler.runCurrent()
        release.complete(Unit)
        first.await(); next.await()
        ResumeCheckpoints.retryPending()
        assertEquals(listOf(1, 2), positions)
    }
    @Test fun `auth failures are removed instead of repeatedly using rejected credentials`() = runTest {
        var attempts = 0
        try { ResumeCheckpoints.save("auth-test") { attempts++; throw JellyfinAuthError("rejected") } } catch (_: JellyfinAuthError) { }
        ResumeCheckpoints.retryPending()
        assertEquals(1, attempts)
    }
}
