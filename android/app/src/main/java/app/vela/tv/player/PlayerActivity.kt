package app.vela.tv.player

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.ui.PlayerView
import app.vela.tv.VelaApp
import app.vela.tv.profile.HouseholdProfile
import app.vela.tv.profile.HouseholdState
import app.vela.tv.profile.HouseholdStore
import app.vela.tv.media.JellyfinAuthError
import app.vela.tv.media.JellyfinClient
import app.vela.tv.media.JellyfinItem
import app.vela.tv.media.JellyfinPlayback
import app.vela.tv.media.JellyfinPlaybackError
import app.vela.tv.media.millisecondsToTicks
import app.vela.tv.media.ticksToMilliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

/** Foreground TV playback. Stopping releases the decoder/audio focus; returning restores position and pause intent. */
@androidx.annotation.OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {
    private var player: ExoPlayer? = null
    private var session: MediaSession? = null
    private var playerListener: Player.Listener? = null
    private var client: JellyfinClient? = null
    private lateinit var view: PlayerView
    private lateinit var chrome: PlayerChrome
    private lateinit var preferences: PlaybackPreferences
    private lateinit var reports: PlaybackReportQueue
    private var targetId = ""
    private var playback: JellyfinPlayback? = null
    private var state: PlaybackState? = null
    private var startReported = false
    private var progressJob: Job? = null
    private var prepareJob: Job? = null
    private var nextJob: Job? = null
    private var savedPosition: Long? = null
    private var playWhenReady = true
    private var dialog: AlertDialog? = null
    private var foreground = false
    private var loading = false
    private var prepareGeneration = 0
    private var completedItem: JellyfinItem? = null
    private val mediaSessionId = "vela-${UUID.randomUUID()}"
    private var request: PlaybackRequest? = null
    private var requestJob: Job? = null
    private var profileJob: Job? = null
    private var pinnedProfileId: String? = null
    private var playbackProfile: HouseholdProfile? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        targetId = savedInstanceState?.getString("target") ?: intent.getStringExtra(EXTRA_ITEM) ?: return finish()
        bindRequest(savedInstanceState?.getString(EXTRA_REQUEST) ?: intent.getStringExtra(EXTRA_REQUEST))
        savedPosition = savedInstanceState?.takeIf { it.containsKey("position") }?.getLong("position")
        playWhenReady = savedInstanceState?.getBoolean("playing", true) ?: true
        savedInstanceState?.getString("completed-name")?.let { name ->
            completedItem = JellyfinItem(targetId, name, savedInstanceState.getString("completed-type"),
                seriesId = savedInstanceState.getString("completed-series"))
        }
        client = (application as VelaApp).settings.settings.value.jellyfin((application as VelaApp).settings.deviceId)
        pinnedProfileId = savedInstanceState?.getString("playback-profile")
        reports = PlaybackReportQueue.shared((application as VelaApp).scope)
        val appearance = (application as VelaApp).settings.settings.value
        chrome = PlayerChrome(this, appearance.highContrast, appearance.reduceMotion)
        view = PlayerView(this).apply {
            useController = true
            setShowSubtitleButton(true)
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
        }
        chrome.applyTo(view)
        setContentView(view)
    }

    override fun onStart() {
        super.onStart()
        foreground = true
        if (!isFinishing) {
            val app = application as VelaApp
            client = app.settings.settings.value.jellyfin(app.settings.deviceId)
            if (client == null) { showError("Connect Jellyfin in Settings before playing a title.", false); return }
            val completed = completedItem
            if (completed != null) showCompletion(completed) else preparePlayback()
        }
    }

    private fun preparePlayback() {
        if (loading || player != null || !foreground || request?.cancelled == true) return
        val jf = client ?: return
        dialog?.dismiss(); dialog = null
        loading = true
        val generation = ++prepareGeneration
        prepareJob = lifecycleScope.launch {
            try {
                val household = (application as VelaApp).householdStore()
                val document = household.snapshot()
                val profile = document.activeProfile
                if (pinnedProfileId != null && pinnedProfileId != profile.id) throw JellyfinPlaybackError("The profile changed. Open this title again from your home screen.")
                pinnedProfileId = profile.id
                val plan = try { ProfilePlaybackPlan.forProfile(profile) }
                    catch (e: IllegalArgumentException) { throw JellyfinPlaybackError(e.message ?: "Playback is unavailable for this profile.") }
                playbackProfile = profile
                preferences = PlaybackPreferences(this@PlayerActivity, plan)
                observeProfile(household, profile)
                val item = jf.item(targetId)
                if (!item.playable) throw JellyfinPlaybackError("Choose an available episode or movie from this title's details.")
                val prepared = jf.playback(item, devicePlaybackCapabilities(), savedPosition ?: item.resumePositionMs)
                if (!foreground || request?.cancelled == true || generation != prepareGeneration) return@launch
                if (household.snapshot().activeProfile != profile) throw JellyfinPlaybackError("The profile changed before playback started. Open this title again.")
                playback = prepared
                savedPosition = prepared.startPositionMs
                state = PlaybackState(prepared.startPositionMs, ticksToMilliseconds(prepared.source.runTimeTicks ?: item.runTimeTicks ?: 0))
                startReported = false
                val p = ExoPlayer.Builder(this@PlayerActivity)
                    .setMediaSourceFactory(DefaultMediaSourceFactory(JellyfinDataSource.Factory(jf))).build()
                player = p
                p.setAudioAttributes(AudioAttributes.Builder().setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).setUsage(C.USAGE_MEDIA).build(), true)
                p.setHandleAudioBecomingNoisy(true)
                val defaultAudio = prepared.source.streams.firstOrNull { it.index == prepared.source.defaultAudioStreamIndex }?.language
                p.trackSelectionParameters = preferences.apply(p.trackSelectionParameters.buildUpon().apply {
                    defaultAudio?.let { setPreferredAudioLanguage(it) }
                    if (prepared.source.defaultSubtitleStreamIndex == -1) setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                }.build())
                val listener = listenerFor(p)
                playerListener = listener
                p.addListener(listener)
                session = MediaSession.Builder(this@PlayerActivity, p).setId(mediaSessionId).build()
                view.player = p
                p.setMediaItem(mediaItem(prepared), prepared.startPositionMs)
                p.prepare()
                p.playWhenReady = playWhenReady
                view.requestFocus()
                progressJob = launch {
                    while (true) {
                        delay(10_000)
                        reportProgress()
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: JellyfinAuthError) { showError("Jellyfin rejected the connection. Update the token and user in Settings.", false) }
            catch (e: JellyfinPlaybackError) { showError(e.message ?: "No compatible direct-play version is available.", false) }
            catch (_: Exception) { showError("Couldn't open this title. Check the Jellyfin connection and try again.", true) }
            finally { if (generation == prepareGeneration) loading = false }
        }
    }

    private fun observeProfile(store: HouseholdStore, profile: HouseholdProfile) {
        profileJob?.cancel()
        profileJob = lifecycleScope.launch {
            store.state.collect { state ->
                val current = (state as? HouseholdState.Ready)?.document?.activeProfile
                if (current != profile && foreground && !isFinishing) {
                    releasePlayback()
                    showError("The profile or its preferences changed. Open this title again from your home screen.", false)
                }
            }
        }
    }

    private fun mediaItem(prepared: JellyfinPlayback): MediaItem = MediaItem.Builder()
        .setMediaId(prepared.item.id).setUri(prepared.url)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(prepared.item.name).setSubtitle(prepared.item.seriesName).build())
        .setSubtitleConfigurations(prepared.subtitles.map { sub ->
            MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(sub.url)).setId("jellyfin-subtitle-${sub.index}")
                .setMimeType(sub.mimeType).setLanguage(sub.language).setLabel(sub.label)
                .setSelectionFlags((if (sub.index == prepared.source.defaultSubtitleStreamIndex) C.SELECTION_FLAG_DEFAULT else 0) or
                    (if (sub.forced) C.SELECTION_FLAG_FORCED else 0)).build()
        }).build()

    private fun listenerFor(expected: ExoPlayer) = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (player !== expected) return
            val p = player ?: return
            val current = state ?: return
            if (playbackState == Player.STATE_READY) {
                request?.publish(PlaybackPhase.READY)
                current.observe(p.currentPosition, isReady = true)
                if (!startReported) {
                    val jf = client ?: return
                    val prepared = playback ?: return
                    startReported = true
                    val ticks = millisecondsToTicks(current.positionMs)
                    val paused = !p.playWhenReady || p.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE
                    val seekable = p.isCurrentMediaItemSeekable
                    reports.submit { try { jf.reportStart(prepared, ticks, paused, seekable) } finally { scheduleResumeRetry(jf) } }
                }
            }
            if (playbackState == Player.STATE_ENDED) {
                current.end(p.currentPosition)
                stopReport()
                playWhenReady = false
                request?.publish(PlaybackPhase.STOPPED)
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                playback?.item?.let { completedItem = it; showCompletion(it) }
            }
        }

        override fun onEvents(player: Player, events: Player.Events) {
            if (this@PlayerActivity.player !== expected || player !== expected) return
            // Audio focus loss, buffering and pause are reflected truthfully; READY is not equivalent to playing.
            if (player.isPlaying) {
                request?.publish(PlaybackPhase.PLAYING)
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (events.contains(Player.EVENT_IS_PLAYING_CHANGED) || events.contains(Player.EVENT_POSITION_DISCONTINUITY) ||
                events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED)) reportProgress()
        }

        override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) {
            if (player === expected) preferences.save(parameters)
        }

        override fun onPlayerError(error: PlaybackException) {
            if (player !== expected) return
            val p = player ?: return
            state?.observe(p.currentPosition)
            savedPosition = state?.positionMs ?: savedPosition
            stopReport(failed = true)
            progressJob?.cancel()
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val connectionError = error.errorCode in PlaybackException.ERROR_CODE_IO_UNSPECIFIED..PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED
            showError(if (connectionError) "Playback lost the Jellyfin connection. Retry from the last position."
                else "This version couldn't play (${error.errorCodeName}). Retry or choose another version in Jellyfin.", true)
        }
    }

    private fun reportProgress() {
        val p = player ?: return
        val current = state ?: return
        val prepared = playback ?: return
        val jf = client ?: return
        if (!startReported || !current.ready || current.completed || p.playerError != null) return
        current.observe(p.currentPosition)
        val ticks = millisecondsToTicks(current.positionMs)
        val paused = !p.playWhenReady || p.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE
        val canSeek = p.isCurrentMediaItemSeekable
        // Only external subtitle IDs have a verified server index. Media3 audio group IDs are not Jellyfin indexes.
        val subtitleIndex = if (C.TRACK_TYPE_TEXT in p.trackSelectionParameters.disabledTrackTypes) -1 else
            p.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }.firstNotNullOfOrNull { group ->
                (0 until group.length).firstNotNullOfOrNull { i ->
                    if (group.isTrackSelected(i)) group.getTrackFormat(i).id?.takeIf { it.startsWith("jellyfin-subtitle-") }?.removePrefix("jellyfin-subtitle-")?.toIntOrNull() else null
                }
            }
        reports.progress { try { jf.reportProgress(prepared, ticks, paused, canSeek, subtitleIndex = subtitleIndex) } finally { scheduleResumeRetry(jf) } }
    }

    private fun stopReport(failed: Boolean = false) {
        val final = state?.stop(failed) ?: return
        val jf = client ?: return
        val prepared = playback ?: return
        reports.submit { try { jf.reportStopped(prepared, final.positionTicks, final.completed, final.failed) } finally { scheduleResumeRetry(jf) } }
    }

    private fun scheduleResumeRetry(jf: JellyfinClient) { jf.scheduleResumeRetry((application as VelaApp).scope) }

    private fun releasePlayback() {
        prepareGeneration++
        prepareJob?.cancel(); prepareJob = null; loading = false
        progressJob?.cancel(); progressJob = null
        nextJob?.cancel(); nextJob = null
        player?.let { p ->
            state?.observe(p.currentPosition)
            savedPosition = state?.positionMs ?: savedPosition
            if (state?.completed != true && p.playerError == null) playWhenReady = p.playWhenReady
            stopReport(failed = p.playerError != null)
            playerListener?.let { p.removeListener(it) }
            playerListener = null
            session?.release(); session = null
            view.player = null
            p.release()
        }
        player = null; playback = null; state = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun showError(message: String, retryable: Boolean) {
        if (isFinishing || isDestroyed) return
        request?.publish(PlaybackPhase.FAILED, message)
        dialog?.dismiss()
        dialog = chrome.dialog("Couldn't play this title", message, error = true)
            .setNegativeButton("Back") { _, _ -> finish() }.setOnCancelListener { finish() }
            .apply { if (retryable) setPositiveButton("Retry") { _, _ -> releasePlayback(); bindRequest(null); preparePlayback() } }
            .create().also { chrome.show(it) }
    }

    private fun showCompletion(item: JellyfinItem) {
        dialog?.dismiss()
        dialog = chrome.dialog("Finished ${item.name}", "Playback complete.")
            .setNegativeButton("Back") { _, _ -> finish() }.setOnCancelListener { finish() }
            .create().also { chrome.show(it) }
        nextJob = lifecycleScope.launch {
            val next: JellyfinItem = try { client?.nextEpisode(item) ?: return@launch }
            catch (e: CancellationException) { throw e } catch (_: Exception) { return@launch }
            if (!foreground || dialog?.isShowing != true) return@launch
            if (playbackProfile?.preferences?.autoplayNext == true) {
                releasePlayback(); completedItem = null; targetId = next.id; bindRequest(null); savedPosition = null; playWhenReady = true; preparePlayback()
                return@launch
            }
            dialog?.dismiss()
            dialog = chrome.dialog("Up next", "${next.seriesName.orEmpty()} · ${next.name}")
                .setPositiveButton("Play next") { _, _ ->
                    releasePlayback(); completedItem = null; targetId = next.id; bindRequest(null); savedPosition = null; playWhenReady = true; preparePlayback()
                }.setNegativeButton("Back") { _, _ -> finish() }.setOnCancelListener { finish() }.create().also { chrome.show(it) }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        player?.let { state?.observe(it.currentPosition) }
        outState.putString("target", targetId)
        outState.putString("playback-profile", pinnedProfileId)
        outState.putString(EXTRA_REQUEST, request?.id)
        (state?.positionMs ?: savedPosition)?.let { outState.putLong("position", it) }
        outState.putBoolean("playing", player?.playWhenReady ?: playWhenReady)
        completedItem?.let { item ->
            outState.putString("completed-name", item.name); outState.putString("completed-type", item.type)
            outState.putString("completed-series", item.seriesId)
        }
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        foreground = false
        profileJob?.cancel(); profileJob = null
        dialog?.dismiss(); dialog = null
        releasePlayback()
        if (isFinishing) request?.publish(PlaybackPhase.STOPPED)
        super.onStop()
    }

    private fun bindRequest(id: String?) {
        requestJob?.cancel()
        request = PlaybackRequests.find(id, targetId) ?: PlaybackRequests.create(targetId)
        requestJob = lifecycleScope.launch {
            request?.state?.collect { outcome ->
                if (outcome.phase in setOf(PlaybackPhase.CANCELLED, PlaybackPhase.TIMED_OUT)) {
                    releasePlayback()
                    finish()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        request?.publish(PlaybackPhase.STOPPED)
        releasePlayback()
        setIntent(intent)
        targetId = intent.getStringExtra(EXTRA_ITEM) ?: return finish()
        completedItem = null; savedPosition = null; playWhenReady = true
        pinnedProfileId = null; playbackProfile = null
        bindRequest(intent.getStringExtra(EXTRA_REQUEST))
        val app = application as VelaApp
        client = app.settings.settings.value.jellyfin(app.settings.deviceId)
        if (foreground) {
            if (client == null) showError("Connect Jellyfin in Settings before playing a title.", false) else preparePlayback()
        }
    }

    companion object {
        private const val EXTRA_ITEM = "item"
        private const val EXTRA_REQUEST = "playback-request"
        fun intent(context: Context, itemId: String): Intent = intent(context, PlaybackRequests.create(itemId))
        fun intent(context: Context, request: PlaybackRequest): Intent = Intent(context, PlayerActivity::class.java)
            .putExtra(EXTRA_ITEM, request.itemId).putExtra(EXTRA_REQUEST, request.id)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
