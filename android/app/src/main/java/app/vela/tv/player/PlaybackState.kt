package app.vela.tv.player

import app.vela.tv.media.millisecondsToTicks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** Player-independent resume guard: buffering/error/stop before READY cannot erase an existing resume point. */
internal class PlaybackState(initialPositionMs: Long, private val durationMs: Long = 0) {
    var positionMs: Long = initialPositionMs.coerceAtLeast(0)
        private set
    var ready = false
        private set
    var completed = false
        private set
    private var stopped = false

    fun observe(positionMs: Long, isReady: Boolean = false) {
        if (stopped) return
        ready = ready || isReady
        if (ready) this.positionMs = positionMs.coerceAtLeast(0).let { if (durationMs > 0) it.coerceAtMost(durationMs) else it }
    }

    fun end(positionMs: Long) {
        if (!ready || stopped) return
        completed = true
        observe(if (durationMs > 0) durationMs else positionMs)
    }

    /** At most one terminal report, and none for a stream that never prepared. */
    fun stop(failed: Boolean = false): PlaybackStop? {
        if (stopped) return null
        stopped = true
        return if (ready) PlaybackStop(millisecondsToTicks(positionMs), completed && !failed, failed) else null
    }
}

internal data class PlaybackStop(val positionTicks: Long, val completed: Boolean, val failed: Boolean)

/** FIFO survives activity stopping. Failure of one report cannot strand later progress/stop reports. */
internal class PlaybackReportQueue(scope: CoroutineScope) {
    private val queue = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private class Pending(var report: suspend () -> Unit)
    private val lock = Any()
    private var pendingProgress: Pending? = null
    private val job = scope.launch {
        for (report in queue) {
            try { report() } catch (e: CancellationException) { throw e } catch (_: Exception) { /* Next event can recover. */ }
        }
    }
    fun submit(report: suspend () -> Unit) = synchronized(lock) {
        pendingProgress = null // A start/stop is an ordering barrier; never coalesce across it.
        queue.trySend(report)
        Unit
    }
    fun progress(report: suspend () -> Unit) = synchronized(lock) {
        val pending = pendingProgress
        if (pending != null) pending.report = report
        else {
            val next = Pending(report)
            pendingProgress = next
            queue.trySend {
                val action = synchronized(lock) {
                    if (pendingProgress === next) pendingProgress = null
                    next.report
                }
                action()
            }
        }
        Unit
    }
    fun close() { queue.close() }
    suspend fun drain() { close(); job.join() }

    companion object {
        private var appQueue: PlaybackReportQueue? = null
        /** One FIFO across activity recreation; an old slow stop cannot overwrite a newer session's progress. */
        @Synchronized fun shared(scope: CoroutineScope): PlaybackReportQueue = appQueue ?: PlaybackReportQueue(scope).also { appQueue = it }
    }
}
