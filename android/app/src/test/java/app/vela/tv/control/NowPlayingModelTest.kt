package app.vela.tv.control

import android.media.session.PlaybackState
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import org.junit.Assert.*
import org.junit.Test

class NowPlayingModelTest {
    private fun position(phase: NowPlayingPhase = NowPlayingPhase.PLAYING, base: Long? = 10_000,
        duration: Long? = 100_000, timestamp: Long = 1_000, speed: Float = 1f) =
        NowPlayingPosition(base, duration, timestamp, speed, phase)
    private fun target(phase: NowPlayingPhase = NowPlayingPhase.PAUSED, actions: Long = 1L or 2L or 4L or 16L or 32L or 256L) =
        NowPlayingTarget("ms_12345678-1234-1234-1234-123456789abc_1", "com.example.player", phase, NowPlayingCapabilities(actions), position(phase))

    @Test fun extrapolatesFromElapsedTimeAndActualSpeed() {
        assertEquals(15_000L, position().at(6_000))
        assertEquals(20_000L, position(speed = 2f).at(6_000))
        assertEquals(5_000L, position(NowPlayingPhase.REWINDING, speed = -1f).at(6_000))
    }

    @Test fun nonAdvancingStatesNeverInventProgress() {
        for (phase in NowPlayingPhase.entries.filterNot { it.advances })
            assertEquals(phase.name, 10_000L, position(phase).at(100_000))
    }

    @Test fun clampsAtBothEndsAndHandlesUnknownPositionAndDuration() {
        assertEquals(100_000L, position().at(900_000))
        assertEquals(0L, position(NowPlayingPhase.REWINDING, speed = -10f).at(900_000))
        assertNull(position(base = -1).at(6_000))
        assertNull(position(base = null).at(6_000))
        assertEquals(110_000L, position(duration = null).at(101_000))
        assertEquals(110_000L, position(duration = 0).at(101_000))
    }

    @Test fun invalidTimestampSpeedAndClockCannotCreateProgress() {
        assertEquals(10_000L, position(timestamp = 0).at(6_000))
        assertEquals(10_000L, position(timestamp = -1).at(6_000))
        assertEquals(10_000L, position(timestamp = 10_000).at(6_000))
        assertEquals(10_000L, position().at(-1))
        for (speed in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY))
            assertEquals(10_000L, position(speed = speed).at(6_000))
    }

    @Test fun extremeElapsedTimeCannotOverflowToNegativePosition() {
        assertEquals(Long.MAX_VALUE, position(base = Long.MAX_VALUE - 1, duration = null, speed = Float.MAX_VALUE).at(Long.MAX_VALUE))
        assertEquals(0L, position(NowPlayingPhase.REWINDING, duration = null, speed = -Float.MAX_VALUE).at(Long.MAX_VALUE))
        assertEquals(100_000L, position(base = Long.MAX_VALUE, speed = Float.NaN).at(1_000))
        val beyondDoubleIntegerPrecision = 9_007_199_254_740_995L
        assertEquals(beyondDoubleIntegerPrecision, position(duration = beyondDoubleIntegerPrecision,
            speed = Float.MAX_VALUE).at(Long.MAX_VALUE))
        assertEquals(beyondDoubleIntegerPrecision, position(base = beyondDoubleIntegerPrecision,
            duration = null).at(1_000))
    }

    @Test fun platformMappingPreservesPlaybackAndFailureStates() {
        assertEquals(NowPlayingPhase.IDLE, NowPlayingPhase.fromPlatform(PlaybackState.STATE_NONE))
        assertEquals(NowPlayingPhase.STOPPED, NowPlayingPhase.fromPlatform(PlaybackState.STATE_STOPPED))
        assertEquals(NowPlayingPhase.PAUSED, NowPlayingPhase.fromPlatform(PlaybackState.STATE_PAUSED))
        assertEquals(NowPlayingPhase.PLAYING, NowPlayingPhase.fromPlatform(PlaybackState.STATE_PLAYING))
        assertEquals(NowPlayingPhase.BUFFERING, NowPlayingPhase.fromPlatform(PlaybackState.STATE_BUFFERING))
        assertEquals(NowPlayingPhase.ERROR, NowPlayingPhase.fromPlatform(PlaybackState.STATE_ERROR))
        assertEquals(NowPlayingPhase.CONNECTING, NowPlayingPhase.fromPlatform(PlaybackState.STATE_CONNECTING))
        assertEquals(NowPlayingPhase.FAST_FORWARDING, NowPlayingPhase.fromPlatform(PlaybackState.STATE_FAST_FORWARDING))
        assertEquals(NowPlayingPhase.REWINDING, NowPlayingPhase.fromPlatform(PlaybackState.STATE_REWINDING))
        for (state in listOf(PlaybackState.STATE_SKIPPING_TO_NEXT, PlaybackState.STATE_SKIPPING_TO_PREVIOUS, PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM))
            assertEquals(NowPlayingPhase.SKIPPING, NowPlayingPhase.fromPlatform(state))
        assertEquals(NowPlayingPhase.UNKNOWN, NowPlayingPhase.fromPlatform(null))
        assertEquals(NowPlayingPhase.UNKNOWN, NowPlayingPhase.fromPlatform(Int.MAX_VALUE))
    }

    @Test fun capabilitiesUseActualPlatformActionBits() {
        val mappings = mapOf(NowPlayingAction.PLAY to PlaybackState.ACTION_PLAY, NowPlayingAction.PAUSE to PlaybackState.ACTION_PAUSE,
            NowPlayingAction.STOP to PlaybackState.ACTION_STOP, NowPlayingAction.PREVIOUS to PlaybackState.ACTION_SKIP_TO_PREVIOUS,
            NowPlayingAction.NEXT to PlaybackState.ACTION_SKIP_TO_NEXT, NowPlayingAction.SEEK to PlaybackState.ACTION_SEEK_TO)
        for ((action, mask) in mappings) {
            val phase = if (action == NowPlayingAction.PLAY) NowPlayingPhase.PAUSED else NowPlayingPhase.PLAYING
            assertTrue(action.name, NowPlayingCapabilities(mask).supports(action, phase))
            assertFalse(action.name, NowPlayingCapabilities(0).supports(action, phase))
        }
    }

    @Test fun combinedPlayPauseActionRespectsObservedState() {
        val caps = NowPlayingCapabilities(PlaybackState.ACTION_PLAY_PAUSE)
        assertTrue(caps.supports(NowPlayingAction.PLAY, NowPlayingPhase.PAUSED))
        assertFalse(caps.supports(NowPlayingAction.PAUSE, NowPlayingPhase.PAUSED))
        assertFalse(caps.supports(NowPlayingAction.PLAY, NowPlayingPhase.PLAYING))
        assertTrue(caps.supports(NowPlayingAction.PAUSE, NowPlayingPhase.PLAYING))
        assertTrue(caps.supports(NowPlayingAction.PAUSE, NowPlayingPhase.BUFFERING))
        assertTrue(caps.supports(NowPlayingAction.PAUSE, NowPlayingPhase.CONNECTING))
        assertFalse(caps.supports(NowPlayingAction.PLAY, NowPlayingPhase.UNKNOWN))
        assertFalse(caps.supports(NowPlayingAction.PAUSE, NowPlayingPhase.UNKNOWN))
    }

    @Test fun unsupportedOrBusyActionsHaveNoArguments() {
        assertNull(NowPlayingCommands.arguments(target(actions = 0), NowPlayingAction.PLAY, false))
        for (action in NowPlayingAction.entries) {
            val t = target(if (action == NowPlayingAction.PLAY) NowPlayingPhase.PAUSED else NowPlayingPhase.PLAYING)
            assertNull(NowPlayingCommands.arguments(t, action, true, if (action == NowPlayingAction.SEEK) 1000 else null))
            assertNull(NowPlayingCommands.arguments(t.copy(sessionId = "old-observer-id"), action, false))
        }
    }

    @Test fun exactReferenceAndPackagePreventFallbackAndArgumentsContainOnlySchemaFields() {
        val args = NowPlayingCommands.arguments(target(), NowPlayingAction.PLAY, false)!!
        assertEquals(setOf("package", "session", "action"), args.keys)
        assertEquals(JsonPrimitive("com.example.player"), args["package"])
        assertEquals(JsonPrimitive("play"), args["action"])
        assertEquals(JsonPrimitive(target().sessionId), args["session"])
        assertNotNull(NowPlayingCommands.arguments(target().copy(uniquePackage = false), NowPlayingAction.PLAY, false))
        for (pkg in listOf("", "com.example;bad", "com.example\nplayer", "single", "com." + "a".repeat(256)))
            assertNull(NowPlayingCommands.arguments(target().copy(packageName = pkg), NowPlayingAction.PLAY, false))
    }

    @Test fun seekClampsAndConvertsToExactIntegerSeconds() {
        val seek = NowPlayingCommands.arguments(target(), NowPlayingAction.SEEK, false, 12_345)!!
        assertEquals(setOf("package", "session", "action", "seconds"), seek.keys)
        assertEquals(12, (seek["seconds"] as JsonPrimitive).intOrNull)
        assertFalse((seek["seconds"] as JsonPrimitive).isString)
        assertEquals(JsonPrimitive(0), NowPlayingCommands.arguments(target(), NowPlayingAction.SEEK, false, -100)!!["seconds"])
        assertEquals(JsonPrimitive(100), NowPlayingCommands.arguments(target(), NowPlayingAction.SEEK, false, Long.MAX_VALUE)!!["seconds"])
        val giant = target().let { it.copy(position = it.position.copy(durationMs = Long.MAX_VALUE)) }
        assertEquals(JsonPrimitive(Int.MAX_VALUE), NowPlayingCommands.arguments(giant, NowPlayingAction.SEEK, false, Long.MAX_VALUE)!!["seconds"])
    }

    @Test fun seekNeedsKnownPositionDurationAndSupportedState() {
        val t = target()
        assertNull(NowPlayingCommands.arguments(t, NowPlayingAction.SEEK, false))
        assertNull(NowPlayingCommands.arguments(t.copy(position = t.position.copy(positionMs = null)), NowPlayingAction.SEEK, false, 1000))
        assertNull(NowPlayingCommands.arguments(t.copy(position = t.position.copy(durationMs = null)), NowPlayingAction.SEEK, false, 1000))
        assertNull(NowPlayingCommands.arguments(t.copy(phase = NowPlayingPhase.BUFFERING), NowPlayingAction.SEEK, false, 1000))
        assertNull(NowPlayingCommands.arguments(t, NowPlayingAction.PLAY, false, 1000))
    }

    @Test fun seekOffsetSaturatesInsteadOfWrapping() {
        assertEquals(0, NowPlayingCommands.seekOffset(1_000, -10_000))
        assertEquals(20_000, NowPlayingCommands.seekOffset(10_000, 10_000))
        assertEquals(Long.MAX_VALUE, NowPlayingCommands.seekOffset(Long.MAX_VALUE - 1, 10_000))
        assertEquals(0, NowPlayingCommands.seekOffset(-1, Long.MIN_VALUE))
    }

    @Test fun textFallbacksAndTimesStayBounded() {
        assertEquals("Title unavailable", nowPlayingText(null, 240, "Title unavailable"))
        assertEquals("Fallback", nowPlayingText("\n\t", 240, "Fallback"))
        assertEquals("a b c", nowPlayingText("a\nb\tc", 240, ""))
        assertEquals(240, nowPlayingText("x".repeat(10_000), 240, "").length)
        assertEquals("—", nowPlayingTime(null))
        assertEquals("0:00", nowPlayingTime(0))
        assertEquals("1:01", nowPlayingTime(61_999))
        assertEquals("1:00:01", nowPlayingTime(3_601_000))
    }

    @Test fun dispatchCompletionDoesNotClaimObservedPlayback() {
        val feedback = NowPlayingCommands.feedback(false, "Opened playback successfully!")
        assertTrue(feedback.contains("may not have taken effect"))
        assertFalse(feedback.contains("successfully"))
        assertEquals("Denied", NowPlayingCommands.feedback(true, "Denied"))
        assertEquals(360, NowPlayingCommands.feedback(true, "e".repeat(20_000)).length)
    }
}
