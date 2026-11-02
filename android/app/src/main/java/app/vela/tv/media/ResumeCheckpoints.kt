package app.vela.tv.media

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Latest exact user-data write per item. Retry only this idempotent operation, never session start/stop. */
internal object ResumeCheckpoints {
    private class Pending(val write: suspend () -> Unit)
    private val pending = ConcurrentHashMap<String, Pending>()
    private val writes = Mutex()
    private var retryJob: Job? = null

    suspend fun save(key: String, write: suspend () -> Unit) {
        val next = Pending(write)
        pending[key] = next
        flush(key, next)
    }

    private suspend fun flush(key: String, checkpoint: Pending) = writes.withLock {
        if (pending[key] !== checkpoint) return@withLock
        try {
            checkpoint.write()
            pending.remove(key, checkpoint)
        } catch (e: JellyfinAuthError) {
            // A rejected credential needs owner action; never retry it on a timer.
            pending.remove(key, checkpoint)
            throw e
        }
    }

    suspend fun retryPending(): Int {
        for ((key, checkpoint) in pending) {
            try { flush(key, checkpoint) } catch (_: IOException) { /* Remains pending unless authentication was rejected. */ }
        }
        return pending.size
    }

    @Synchronized fun schedule(scope: CoroutineScope) {
        if (pending.isEmpty() || retryJob?.isActive == true) return
        retryJob = scope.launch {
            for (wait in listOf(1_000L, 5_000L, 15_000L)) {
                delay(wait)
                if (retryPending() == 0) break
            }
            // After the bounded retry window, retain the latest checkpoints in memory. A later reporting
            // event schedules another window and newer positions replace older writes for the same item.
        }
    }
}
