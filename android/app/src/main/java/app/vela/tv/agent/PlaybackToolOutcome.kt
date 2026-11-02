package app.vela.tv.agent

import app.vela.tv.player.PlaybackOutcome
import app.vela.tv.player.PlaybackPhase

/** Only an observed PLAYING outcome from the exact awaited request is success. Never expose raw player errors. */
internal fun playbackToolOutcome(outcome: PlaybackOutcome): ToolOutcome = when (outcome.phase) {
    PlaybackPhase.PLAYING -> ToolOutcome("Playing item ${outcome.itemId}; playback was observed.")
    PlaybackPhase.FAILED -> ToolOutcome("Playback failed. Check the player's error and connection.", true)
    PlaybackPhase.TIMED_OUT -> ToolOutcome("Playback did not start before the deadline. Inspect the player before retrying.", true)
    else -> ToolOutcome("Playback did not start (${outcome.phase.name.lowercase()}).", true)
}
