package app.vela.tv.control

import app.vela.tv.agent.ActionInvalidatedException
import app.vela.tv.agent.PreparedToolAction
import app.vela.tv.agent.ToolOutcome
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.UUID

/** An injected platform session. Equality of identity must mean the same session, never a token hash. */
internal interface MediaSessionTarget {
    val identity: Any
    val packageName: String
    fun playback(): MediaSessionPlayback
    fun dispatch(action: NowPlayingAction, seekMs: Long?)
}

internal data class MediaSessionPlayback(
    val phase: NowPlayingPhase,
    val capabilities: NowPlayingCapabilities,
    val position: NowPlayingPosition,
    val title: String,
) {
    fun seekable(): Boolean = capabilities.supports(NowPlayingAction.SEEK, phase) &&
        position.positionMs?.let { it >= 0 } == true && position.durationMs?.let { it > 0 } == true
}

internal data class ReferencedMediaSession(val reference: String, val target: MediaSessionTarget)

/** Process-only, bounded token references. Contains no controller, metadata, artwork or persisted state. */
internal class MediaSessionTargetStore(
    private val clock: () -> Long,
    private val capacity: Int = 128,
    private val lifetimeMs: Long = 300_000,
) {
    private data class Entry(val reference: String, val identity: Any, val packageName: String, val created: Long)
    private val entries = ArrayList<Entry>()
    private val namespace = UUID.randomUUID().toString()
    private var serial = 0L

    init { require(capacity in 1..128 && lifetimeMs in 1..300_000) }

    @Synchronized fun observe(sessions: List<MediaSessionTarget>, limit: Int = 24): List<ReferencedMediaSession> {
        require(limit in 1..24)
        reconcile(sessions)
        val visible = sessions.take(minOf(limit, capacity)).filter { session ->
            sessions.count { it.identity == session.identity } == 1
        }
        val protected = visible.map { it.identity }
        return visible.map { session ->
            // Do not evict an earlier reference from THIS result while issuing a later one.
            ReferencedMediaSession(issue(session, protected), session)
        }
    }

    @Synchronized fun reconcile(sessions: List<MediaSessionTarget>) {
        expire()
        entries.removeAll { entry ->
            // Duplicate identities (even in different packages) are never silently collapsed.
            val matches = sessions.filter { it.identity == entry.identity }
            matches.size != 1 || matches.single().packageName != entry.packageName
        }
    }

    @Synchronized fun reference(session: MediaSessionTarget, sessions: List<MediaSessionTarget>): String? {
        reconcile(sessions)
        if (sessions.count { it.identity == session.identity } != 1 ||
            sessions.none { it.identity == session.identity && it.packageName == session.packageName }) return null
        return issue(session)
    }

    private fun issue(session: MediaSessionTarget, protected: List<Any> = emptyList()): String {
        entries.firstOrNull { it.identity == session.identity && it.packageName == session.packageName }?.let { return it.reference }
        if (entries.size == capacity) entries.removeAt(entries.indexOfFirst { it.identity !in protected })
        // No reuse within a process, including after expiry/eviction; namespace also changes after process restart.
        check(serial < Long.MAX_VALUE)
        val reference = "ms_${namespace}_${(++serial).toString(36)}"
        entries += Entry(reference, session.identity, session.packageName, clock())
        return reference
    }

    @Synchronized fun resolve(reference: String, sessions: List<MediaSessionTarget>): MediaSessionTarget? {
        reconcile(sessions)
        if (!isReference(reference)) return null
        val entry = entries.singleOrNull { it.reference == reference } ?: return null
        return sessions.singleOrNull { it.identity == entry.identity && it.packageName == entry.packageName }
    }

    @Synchronized fun invalidate(identity: Any) { entries.removeAll { it.identity == identity } }
    @Synchronized fun clear() { entries.clear() }

    private fun expire() {
        val now = clock()
        // Clock regression invalidates too; use subtraction only after a nonnegative ordered clock check.
        entries.removeAll { now < it.created || now < 0 || it.created < 0 || now - it.created >= lifetimeMs }
    }

    companion object {
        private val format = Regex("ms_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}_[0-9a-z]{1,13}")
        fun isReference(value: String): Boolean = value.length <= 53 && format.matches(value)
    }
}

internal data class MediaSessionCommandRequest(
    val action: String,
    val seconds: Int? = null,
    val packageName: String? = null,
    val sessionReference: String? = null,
)

/** Selection, revalidation and ONE dispatch share Main. The selected controller is captured before approval. */
internal class MediaSessionCommands(
    private val references: MediaSessionTargetStore,
    private val sessions: () -> List<MediaSessionTarget>,
    private val clock: () -> Long,
    private val main: CoroutineDispatcher = Dispatchers.Main.immediate,
) {
    private fun read(): List<MediaSessionTarget> = try {
        sessions().also(references::reconcile)
    } catch (failure: Exception) {
        references.clear()
        throw failure
    }

    suspend fun status(): ToolOutcome = withContext(main) {
        currentCoroutineContext().ensureActive()
        val all = read()
        if (all.isEmpty()) return@withContext ToolOutcome("No active media sessions.")
        val observed = references.observe(all)
        val lines = observed.map { (reference, target) ->
            val playback = target.playback()
            val actions = NowPlayingAction.entries.filter { playback.capabilities.supports(it, playback.phase) &&
                (it != NowPlayingAction.SEEK || playback.seekable()) }.joinToString(",") { it.wireName }
            "session=$reference package=${nowPlayingText(target.packageName, 255, "unknown")} " +
                "state=${playback.phase.label} title=\"${nowPlayingText(playback.title, 240, "Title unavailable")}\" " +
                "position=${nowPlayingTime(playback.position.at(clock()))} duration=${nowPlayingTime(playback.position.durationMs)} actions=[$actions]"
        }
        ToolOutcome((lines + if (all.size > observed.size)
            listOf("Some sessions are omitted or have duplicate identities. Use an exact reference from a fresh observation.") else emptyList()).joinToString("\n"))
    }

    suspend fun prepare(request: MediaSessionCommandRequest): PreparedToolAction = withContext(main) {
        currentCoroutineContext().ensureActive()
        val action = if (request.action == "skip") NowPlayingAction.SEEK
            else NowPlayingAction.entries.singleOrNull { it.wireName == request.action }
        fun denied(reason: String) = PreparedToolAction(null) { ToolOutcome(reason, true) }
        if (action == null) return@withContext denied("Unsupported media action.")
        if (action == NowPlayingAction.SEEK && request.seconds == null)
            return@withContext denied("seek and skip require integer seconds.")
        if (action != NowPlayingAction.SEEK && request.seconds != null)
            return@withContext denied("seconds is only valid for seek and skip.")
        if (request.packageName != null && (request.packageName.isBlank() || request.packageName.length > 255))
            return@withContext denied("Invalid media package.")
        if (request.sessionReference != null && !MediaSessionTargetStore.isReference(request.sessionReference))
            return@withContext denied("Invalid session reference. Read media_status for a current session.")
        val all = read()
        val target = when {
            request.sessionReference != null -> references.resolve(request.sessionReference, all)
            request.packageName != null -> all.filter { it.packageName == request.packageName }.singleOrNull()
            else -> all.filter { it.playback().phase == NowPlayingPhase.PLAYING }.let { playing ->
                if (playing.isEmpty()) all.singleOrNull() else playing.singleOrNull()
            }
        } ?: return@withContext denied("No unique active media session matches. Read media_status and supply its exact session reference.")
        if (request.packageName != null && target.packageName != request.packageName)
            return@withContext denied("The session reference does not belong to the supplied package.")
        if (request.sessionReference == null && all.count { it.packageName == target.packageName } != 1)
            return@withContext denied("This package has multiple media sessions. Supply an exact session reference from media_status.")
        val reference = request.sessionReference ?: references.reference(target, all)
            ?: return@withContext denied("The media session identity is ambiguous. Read media_status again.")
        val identity = target.identity
        val packageName = target.packageName
        val initial = target.playback()
        if (!initial.capabilities.supports(action, initial.phase) || (action == NowPlayingAction.SEEK && !initial.seekable()))
            return@withContext denied("This session does not currently support ${request.action}.")
        PreparedToolAction(null) {
            withContext(main) {
                currentCoroutineContext().ensureActive()
                val current = read()
                val same = references.resolve(reference, current)
                if (same == null || same.identity != identity || same.packageName != packageName ||
                    target.identity != identity || target.packageName != packageName ||
                    (request.sessionReference == null && current.count { it.packageName == packageName } != 1))
                    throw ActionInvalidatedException()
                // Read capability/position from the CAPTURED controller, never a replacement from the new list.
                val playback = target.playback()
                if (!playback.capabilities.supports(action, playback.phase) ||
                    (action == NowPlayingAction.SEEK && !playback.seekable())) throw ActionInvalidatedException()
                val seek = if (action == NowPlayingAction.SEEK) {
                    val amount = request.seconds!!.toLong() * 1000
                    val requested = if (request.action == "skip")
                        NowPlayingCommands.seekOffset(playback.position.at(clock()) ?: throw ActionInvalidatedException(), amount)
                    else amount
                    requested.coerceIn(0, playback.position.durationMs!!)
                } else null
                // Synchronous platform reads can still consume time or race a revocation from another caller.
                if (references.resolve(reference, current)?.identity != identity) throw ActionInvalidatedException()
                currentCoroutineContext().ensureActive()
                target.dispatch(action, seek)
                ToolOutcome("Request sent to $packageName (session=$reference). Playback may not have changed; read media_status to observe the outcome.")
            }
        }
    }
}
