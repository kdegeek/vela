package app.vela.tv.player

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.media3.common.PlaybackException
import androidx.media3.common.MediaMetadata
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import kotlinx.coroutines.CancellationException
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.ui.PlayerView
import app.vela.tv.VelaApp
import app.vela.tv.media.JellyfinClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Vela's own Jellyfin player (the start of the custom Jellyfin front end): direct play, resume where the owner left
 * off, and progress reported back so Continue Watching stays right. It also publishes a media session, so the agent's
 * media tools can pause and seek it like any other app.
 */
class PlayerActivity : ComponentActivity() {
    private var player: ExoPlayer? = null
    private var session: MediaSession? = null
    private var jf: JellyfinClient? = null
    private var itemId: String? = null
    private var progressJob: Job? = null
    private var prepareJob: Job? = null
    private var playbackReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as VelaApp
        val id = intent.getStringExtra(EXTRA_ITEM) ?: return finish()
        val client = app.settings.settings.value.jellyfin(app.settings.deviceId) ?: return fail("Jellyfin isn't set up.")
        jf = client
        itemId = id
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val view = PlayerView(this)
        setContentView(view)
        val p = ExoPlayer.Builder(this).build()
        p.setAudioAttributes(AudioAttributes.Builder().setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).setUsage(C.USAGE_MEDIA).build(), true)
        p.setHandleAudioBecomingNoisy(true)
        player = p
        session = MediaSession.Builder(this, p).build()
        view.player = p
        view.useController = true
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) playbackReady = true
                if (state == Player.STATE_ENDED) finish()
            }
            override fun onPlayerError(error: PlaybackException) { fail("This title couldn't play (${error.errorCodeName}). Check your server or try another title.") }
        })
        prepareJob = lifecycleScope.launch {
            val item = try { client.item(id) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { return@launch fail("Couldn't open that title: ${e.message}") }
            if (item.type !in setOf("Movie", "Episode", "Video")) return@launch fail("Choose an episode or movie to play.")
            val startMs = (item.positionTicks ?: 0L) / TICKS_PER_MS
            p.setMediaItem(MediaItem.Builder().setUri(client.streamUrl(id)).setMediaMetadata(MediaMetadata.Builder().setTitle(item.name).build()).build(), startMs)
            p.prepare()
            p.playWhenReady = true
            try { client.reportStart(id, startMs * TICKS_PER_MS) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Playback remains usable if progress reporting is offline. */ }
            progressJob = launch {
                while (true) {
                    delay(10_000)
                    try { client.reportProgress(id, p.currentPosition * TICKS_PER_MS, !p.isPlaying) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { /* Retry at the next progress tick. */ }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        val p = player ?: return
        val client = jf ?: return
        val id = itemId ?: return
        val pos = p.currentPosition * TICKS_PER_MS
        prepareJob?.cancel()
        progressJob?.cancel()
        session?.release()
        session = null
        p.release()
        player = null
        // The activity is going away; report from the app scope so the write isn't cancelled with it.
        if (playbackReady) (application as VelaApp).scope.launch { runCatching { client.reportStopped(id, pos) } }
        finish()
    }

    private fun fail(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        finish()
    }

    companion object {
        private const val EXTRA_ITEM = "item"
        private const val TICKS_PER_MS = 10_000L

        fun intent(context: Context, itemId: String): Intent = Intent(context, PlayerActivity::class.java).putExtra(EXTRA_ITEM, itemId)
    }
}
