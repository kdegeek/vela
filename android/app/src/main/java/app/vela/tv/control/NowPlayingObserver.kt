package app.vela.tv.control

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.ref.WeakReference

internal enum class NowPlayingAccess { INACTIVE, LOADING, READY, PERMISSION, UNAVAILABLE }
internal data class NowPlayingQueueItem(val title: String, val current: Boolean)
internal data class NowPlayingSession(
    val target: NowPlayingTarget,
    val appLabel: String,
    val title: String,
    val subtitle: String,
    val artwork: Bitmap?,
    val error: String?,
    val queue: List<NowPlayingQueueItem>,
    val queueTruncated: Boolean,
)
internal data class NowPlayingSnapshot(
    val access: NowPlayingAccess = NowPlayingAccess.INACTIVE,
    val sessions: List<NowPlayingSession> = emptyList(),
    val nowElapsedMs: Long = 0,
)

/** Read-only observer. All registration, callbacks and disposal are confined to Main by its lifecycle owner. */
internal class NowPlayingObserver(context: Context, private val permitted: () -> Boolean) {
    private val context = context.applicationContext
    private val manager = context.getSystemService(MediaSessionManager::class.java)
    private val component = ComponentName(context, MediaListener::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val mutable = MutableStateFlow(NowPlayingSnapshot())
    val state = mutable.asStateFlow()
    private var started = false
    private var registered = false
    private var tick = 0
    private var packageCounts = emptyMap<String, Int>()
    private val entries = linkedMapOf<MediaSession.Token, Entry>()
    private val listener = MediaSessionManager.OnActiveSessionsChangedListener { if (started) refresh() }
    private val ticker = object : Runnable {
        override fun run() {
            if (!started) return
            if (!permitted()) { stop(); return }
            // Recheck notification access even when Android emits no session event on revocation.
            if (++tick % 5 == 0) refresh()
            else mutable.value = mutable.value.copy(nowElapsedMs = SystemClock.elapsedRealtime())
            if (started) handler.postDelayed(this, 1000)
        }
    }

    fun start() {
        if (started || !permitted()) return
        started = true
        mutable.value = NowPlayingSnapshot(NowPlayingAccess.LOADING)
        refresh()
        handler.postDelayed(ticker, 1000)
    }

    fun stop() {
        started = false
        detach()
        handler.removeCallbacks(ticker)
        mutable.value = NowPlayingSnapshot()
    }

    fun refresh() {
        if (!started) return
        if (!permitted()) { stop(); return }
        try {
            val service = manager ?: error("Media sessions unavailable")
            if (!registered) {
                service.addOnActiveSessionsChangedListener(listener, component, handler)
                registered = true
            }
            val all = service.getActiveSessions(component)
            packageCounts = all.groupingBy { it.packageName }.eachCount()
            val observed = ProcessMediaSessionTargets.references.observe(all.map(::AndroidMediaSessionTarget))
            val current = observed.associateBy { it.target.identity }
            entries.keys.toList().filter { it !in current }.forEach { remove(it) }
            current.forEach { (identity, session) ->
                val token = identity as MediaSession.Token
                val controller = all.single { it.sessionToken == token }
                // Expiry/eviction issues a fresh reference; queued callbacks of the old entry cannot republish it.
                if (entries[token]?.id != session.reference) remove(token)
                if (token !in entries) {
                    val entry = Entry(controller, session.reference)
                    entries[token] = entry
                    controller.registerCallback(entry.callback, handler)
                }
                entries[token]?.read()
            }
            publish()
        } catch (_: SecurityException) {
            fail(NowPlayingAccess.PERMISSION)
        } catch (_: Exception) {
            fail(NowPlayingAccess.UNAVAILABLE)
        }
    }

    /** Fresh list/capability/identity check immediately before the UI asks Runner to acquire control. */
    fun currentTarget(sessionId: String): NowPlayingTarget? {
        refresh()
        return state.value.takeIf { it.access == NowPlayingAccess.READY }?.sessions
            ?.singleOrNull { it.target.sessionId == sessionId }?.target
    }

    private fun fail(access: NowPlayingAccess) {
        ProcessMediaSessionTargets.references.clear()
        detach()
        mutable.value = NowPlayingSnapshot(access, nowElapsedMs = SystemClock.elapsedRealtime())
    }

    private fun detach() {
        if (registered) runCatching { manager?.removeOnActiveSessionsChangedListener(listener) }
        registered = false
        entries.keys.toList().forEach(::remove)
        packageCounts = emptyMap()
    }

    private fun remove(token: MediaSession.Token) {
        entries.remove(token)?.let { runCatching { it.controller.unregisterCallback(it.callback) } }
    }

    private fun publish() {
        if (!started || !permitted()) { stop(); return }
        mutable.value = NowPlayingSnapshot(NowPlayingAccess.READY, entries.values.mapNotNull { entry ->
            entry.snapshot?.let { session -> session.copy(target = session.target.copy(
                uniquePackage = packageCounts[session.target.packageName] == 1)) }
        }, SystemClock.elapsedRealtime())
    }

    private inner class Entry(val controller: MediaController, val id: String) {
        var snapshot: NowPlayingSession? = null
        private var lastMetadata: WeakReference<MediaMetadata>? = null
        private var art: Bitmap? = null
        private val label = runCatching {
            val info = context.packageManager.getApplicationInfo(controller.packageName, 0)
            nowPlayingText(context.packageManager.getApplicationLabel(info), 80, controller.packageName)
        }.getOrDefault(controller.packageName)
        val callback = object : MediaController.Callback() {
            override fun onPlaybackStateChanged(state: PlaybackState?) = changed()
            override fun onMetadataChanged(metadata: MediaMetadata?) = changed()
            override fun onQueueChanged(queue: MutableList<MediaSession.QueueItem>?) = changed()
            override fun onSessionDestroyed() {
                if (entries[controller.sessionToken] === this@Entry) {
                    ProcessMediaSessionTargets.references.invalidate(controller.sessionToken)
                    remove(controller.sessionToken)
                    publish()
                }
            }
        }

        private fun changed() {
            // Callbacks already queued when stop/unregister runs must not repopulate private metadata.
            if (!started || entries[controller.sessionToken] !== this) return
            if (!permitted()) { stop(); return }
            try { read(); publish() }
            catch (_: SecurityException) { fail(NowPlayingAccess.PERMISSION) }
            catch (_: Exception) { fail(NowPlayingAccess.UNAVAILABLE) }
        }

        fun read() {
            val playback = controller.playbackState
            val metadata = controller.metadata
            if (metadata == null) {
                art = null
                lastMetadata = null
            } else if (metadata !== lastMetadata?.get()) {
                art = try { boundedArtwork(metadata) } catch (_: Exception) { null }
                // Do not keep rejected/full-sized source artwork alive through the metadata cache.
                lastMetadata = WeakReference(metadata)
            }
            val phase = NowPlayingPhase.fromPlatform(playback?.state)
            val queue = controller.queue.orEmpty()
            snapshot = NowPlayingSession(
                NowPlayingTarget(id, controller.packageName, phase, NowPlayingCapabilities(playback?.actions ?: 0),
                    NowPlayingPosition(playback?.position?.takeIf { it >= 0 },
                        metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0 },
                        playback?.lastPositionUpdateTime ?: 0, playback?.playbackSpeed ?: 0f, phase)),
                label,
                nowPlayingText(metadata?.description?.title ?: metadata?.getText(MediaMetadata.METADATA_KEY_TITLE),
                    240, "Title unavailable"),
                nowPlayingText(metadata?.description?.subtitle ?: metadata?.getText(MediaMetadata.METADATA_KEY_ARTIST), 240, ""),
                art,
                if (phase == NowPlayingPhase.ERROR) nowPlayingText(playback?.errorMessage, 360,
                    "The app reported a playback error. Check playback in that app.") else null,
                queue.take(40).map { NowPlayingQueueItem(nowPlayingText(it.description.title, 160, "Untitled item"),
                    playback?.activeQueueItemId == it.queueId) }, queue.size > 40,
            )
        }
    }

    private fun boundedArtwork(metadata: MediaMetadata?): Bitmap? {
        // Never load a third-party URI: avoid unbounded/network/credential-bearing artwork requests.
        val original = listOf(MediaMetadata.METADATA_KEY_DISPLAY_ICON, MediaMetadata.METADATA_KEY_ART,
            MediaMetadata.METADATA_KEY_ALBUM_ART).firstNotNullOfOrNull { metadata?.getBitmap(it) } ?: return null
        if (original.isRecycled || original.width !in 1..2048 || original.height !in 1..2048 ||
            original.allocationByteCount > 16 * 1024 * 1024) return null
        val scale = 512.0 / maxOf(original.width, original.height)
        return if (scale >= 1) original else Bitmap.createScaledBitmap(original,
            (original.width * scale).toInt().coerceAtLeast(1), (original.height * scale).toInt().coerceAtLeast(1), true)
    }
}
