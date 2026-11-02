package app.vela.tv.control

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Platform PlaybackState values, kept pure so malformed third-party state can be tested on the JVM. */
internal enum class NowPlayingPhase(val label: String, val advances: Boolean = false) {
    IDLE("Idle"), STOPPED("Stopped"), PAUSED("Paused"), PLAYING("Playing", true),
    FAST_FORWARDING("Fast forwarding", true), REWINDING("Rewinding", true),
    BUFFERING("Buffering"), ERROR("Playback error"), CONNECTING("Connecting"),
    SKIPPING("Changing track"), UNKNOWN("Playback state unavailable");

    companion object {
        fun fromPlatform(value: Int?): NowPlayingPhase = when (value) {
            0 -> IDLE; 1 -> STOPPED; 2 -> PAUSED; 3 -> PLAYING; 4 -> FAST_FORWARDING
            5 -> REWINDING; 6 -> BUFFERING; 7 -> ERROR; 8 -> CONNECTING
            9, 10, 11 -> SKIPPING
            else -> UNKNOWN
        }
    }
}

internal data class NowPlayingPosition(
    val positionMs: Long?,
    val durationMs: Long?,
    val updatedAtElapsedMs: Long,
    val speed: Float,
    val phase: NowPlayingPhase,
) {
    fun at(nowElapsedMs: Long): Long? {
        val base = positionMs?.takeIf { it >= 0 } ?: return null
        val maximum = durationMs?.takeIf { it > 0 } ?: Long.MAX_VALUE
        // Missing/future timestamps, NaN speeds and clock anomalies must not create fictional progress.
        if (!phase.advances || !speed.isFinite() || speed == 0f || updatedAtElapsedMs <= 0 || nowElapsedMs <= updatedAtElapsedMs)
            return base.coerceAtMost(maximum)
        val elapsed = nowElapsedMs.toDouble() - updatedAtElapsedMs.toDouble()
        return (base.toDouble() + elapsed * speed.toDouble()).coerceIn(0.0, maximum.toDouble()).toLong().coerceIn(0, maximum)
    }
}

internal enum class NowPlayingAction(val wireName: String, val label: String) {
    PLAY("play", "Play"), PAUSE("pause", "Pause"), STOP("stop", "Stop"),
    PREVIOUS("previous", "Previous"), NEXT("next", "Next"), SEEK("seek", "Seek")
}

/** These masks are the public android.media.session.PlaybackState ACTION_* bit values. */
internal data class NowPlayingCapabilities(val actions: Long) {
    fun supports(action: NowPlayingAction, phase: NowPlayingPhase): Boolean = when (action) {
        NowPlayingAction.PLAY -> actions and (4L or 512L) != 0L && phase in setOf(
            NowPlayingPhase.IDLE, NowPlayingPhase.STOPPED, NowPlayingPhase.PAUSED, NowPlayingPhase.ERROR)
        NowPlayingAction.PAUSE -> actions and (2L or 512L) != 0L && (phase.advances || phase in setOf(
            NowPlayingPhase.BUFFERING, NowPlayingPhase.CONNECTING))
        NowPlayingAction.STOP -> actions and 1L != 0L && phase !in setOf(NowPlayingPhase.STOPPED, NowPlayingPhase.UNKNOWN)
        NowPlayingAction.PREVIOUS -> actions and 16L != 0L && phase != NowPlayingPhase.UNKNOWN
        NowPlayingAction.NEXT -> actions and 32L != 0L && phase != NowPlayingPhase.UNKNOWN
        NowPlayingAction.SEEK -> actions and 256L != 0L && (phase.advances || phase == NowPlayingPhase.PAUSED)
    }
}

internal data class NowPlayingTarget(
    val sessionId: String,
    val packageName: String,
    val phase: NowPlayingPhase,
    val capabilities: NowPlayingCapabilities,
    val position: NowPlayingPosition,
    val uniquePackage: Boolean = true,
)

internal object NowPlayingCommands {
    /** Always bind the exact observed session; package is an additional consistency check. */
    fun arguments(target: NowPlayingTarget, action: NowPlayingAction, busy: Boolean, seekMs: Long? = null): JsonObject? {
        if (busy || !MediaSessionTargetStore.isReference(target.sessionId) || target.packageName.length > 255 ||
            !target.packageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+")) ||
            !target.capabilities.supports(action, target.phase)) return null
        val seconds = if (action == NowPlayingAction.SEEK) {
            val duration = target.position.durationMs?.takeIf { it > 0 } ?: return null
            if (target.position.positionMs?.takeIf { it >= 0 } == null || seekMs == null) return null
            // The installed tool schema accepts Int seconds, not fractional milliseconds or a Long overflow.
            (seekMs.coerceIn(0, duration) / 1000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        } else {
            if (seekMs != null) return null
            null
        }
        return buildJsonObject {
            put("package", target.packageName)
            put("session", target.sessionId)
            put("action", action.wireName)
            if (seconds != null) put("seconds", seconds)
        }
    }

    fun seekOffset(positionMs: Long, offsetMs: Long): Long = when {
        offsetMs > 0 && positionMs > Long.MAX_VALUE - offsetMs -> Long.MAX_VALUE
        offsetMs < 0 && positionMs < Long.MIN_VALUE - offsetMs -> 0
        else -> (positionMs + offsetMs).coerceAtLeast(0)
    }

    /** A tool return means dispatch completed; only the separately observed session describes playback. */
    fun feedback(isError: Boolean, content: String): String = if (isError)
        nowPlayingText(content, 360, "The request could not be completed.")
    else "Request sent. Playback status is reported by the app; the change may not have taken effect."
}

internal fun nowPlayingText(value: CharSequence?, maximum: Int, fallback: String): String {
    val text = value?.take(maximum * 2)?.map { if (it.isISOControl() || it == '\u2028' || it == '\u2029') ' ' else it }
        ?.joinToString("")?.trim()?.take(maximum).orEmpty()
    return text.ifBlank { fallback }
}

internal fun nowPlayingTime(ms: Long?): String {
    if (ms == null || ms < 0) return "—"
    val total = ms / 1000
    val seconds = (total % 60).toString().padStart(2, '0')
    val minutes = (total / 60 % 60).toString().padStart(2, '0')
    return if (total >= 3600) "${total / 3600}:$minutes:$seconds" else "${total / 60}:$seconds"
}
