package app.vela.tv.control

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.os.SystemClock

/** Shared by every observer and TvTools instance in this app process. No raw token is exposed to callers. */
internal object ProcessMediaSessionTargets {
    val references = MediaSessionTargetStore(SystemClock::elapsedRealtime)

    fun controllers(context: Context): List<MediaController> = try {
        val manager = context.getSystemService(MediaSessionManager::class.java)
            ?: error("Media sessions unavailable")
        manager.getActiveSessions(ComponentName(context, MediaListener::class.java))
    } catch (_: SecurityException) {
        references.clear()
        throw IllegalStateException("Vela can't see what's playing: allow Vela's notification access in the TV's settings.")
    } catch (failure: Exception) {
        references.clear()
        throw failure
    }

    fun commands(context: Context) = MediaSessionCommands(references,
        { controllers(context).map(::AndroidMediaSessionTarget) }, SystemClock::elapsedRealtime)
}

internal class AndroidMediaSessionTarget(private val controller: MediaController) : MediaSessionTarget {
    override val identity = controller.sessionToken
    override val packageName: String = controller.packageName

    override fun playback(): MediaSessionPlayback {
        val state = controller.playbackState
        val metadata = controller.metadata
        val phase = NowPlayingPhase.fromPlatform(state?.state)
        return MediaSessionPlayback(phase, NowPlayingCapabilities(state?.actions ?: 0),
            NowPlayingPosition(state?.position?.takeIf { it >= 0 },
                metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0 },
                state?.lastPositionUpdateTime ?: 0, state?.playbackSpeed ?: 0f, phase),
            nowPlayingText(metadata?.description?.title ?: metadata?.getText(MediaMetadata.METADATA_KEY_TITLE),
                240, "Title unavailable"))
    }

    override fun dispatch(action: NowPlayingAction, seekMs: Long?) {
        val controls = controller.transportControls
        when (action) {
            NowPlayingAction.PLAY -> controls.play()
            NowPlayingAction.PAUSE -> controls.pause()
            NowPlayingAction.STOP -> controls.stop()
            NowPlayingAction.NEXT -> controls.skipToNext()
            NowPlayingAction.PREVIOUS -> controls.skipToPrevious()
            NowPlayingAction.SEEK -> controls.seekTo(requireNotNull(seekMs))
        }
    }
}
