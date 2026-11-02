package app.vela.tv.design

import org.junit.Assert.*
import org.junit.Test

class UiPresentationTest {
    @Test fun `invalid progress never reaches Compose size or accessibility percentages`() {
        assertNull(displayProgress(null))
        assertNull(displayProgress(Float.NaN))
        assertNull(displayProgress(Float.POSITIVE_INFINITY))
        assertNull(displayProgress(Float.NEGATIVE_INFINITY))
        assertEquals(0f, displayProgress(-0.5f))
        assertEquals(1f, displayProgress(7f))
        assertEquals(0.42f, displayProgress(0.42f))
    }

    @Test fun `loading and failed metadata cannot offer playback even with a stale playable type`() {
        assertEquals(TitleContent.LOADING, titleContent(true, false, "Movie"))
        assertEquals(TitleContent.LOADING, titleContent(true, true, "Episode"))
        assertEquals(TitleContent.ERROR, titleContent(false, true, "Movie"))
        assertEquals(TitleContent.ERROR, titleContent(false, true, "Series"))
    }

    @Test fun `series offer episodes and unsupported containers cannot be played`() {
        assertEquals(TitleContent.EPISODES, titleContent(false, false, "Series"))
        for (type in listOf(null, "Season", "Folder", "Audio", "")) {
            assertEquals(TitleContent.UNSUPPORTED, titleContent(false, false, type))
        }
        for (type in listOf("Movie", "Episode", "Video")) {
            assertEquals(TitleContent.UNSUPPORTED, titleContent(false, false, type, missing = true))
            assertEquals(TitleContent.PLAYABLE, titleContent(false, false, type))
        }
    }
}

class AgentPresentationTest {
    @Test fun `pending approval wins over both running and idle and retains the actual question`() {
        for (running in listOf(false, true)) assertEquals(AgentIndicator.NeedsApproval("Allow playback?"),
            agentIndicator(running, "Allow playback?", "Opening app"))
    }
    @Test fun `blank pending and step text do not invent agent activity`() {
        for (blank in listOf(null, "", " \n\t")) {
            assertEquals(AgentIndicator.Idle, agentIndicator(false, blank, "stale step"))
            assertEquals(AgentIndicator.Working(null), agentIndicator(true, blank, blank))
            assertEquals(AgentIndicator.Working("Opening app"), agentIndicator(true, blank, "Opening app"))
        }
    }
    @Test fun `task outcome words never imply success for an unknown or failed outcome`() {
        val known = mapOf("running" to StatusTone.Agent, "complete" to StatusTone.Success,
            "failed" to StatusTone.Danger, "canceled" to StatusTone.Neutral)
        known.forEach { (state, tone) ->
            assertEquals(tone, taskTone(state))
            assertEquals(state.replaceFirstChar { it.uppercaseChar() }, taskStateLabel(state))
        }
        for (state in listOf("", "effect unknown", "queued", "COMPLETE")) assertEquals(StatusTone.Neutral, taskTone(state))
        assertEquals("Effect unknown", taskStateLabel("effect unknown"))
        assertEquals("", taskStateLabel(""))
    }
    @Test fun `approval instructions offer only the actual external response path`() {
        assertEquals("Press OK to allow once, or Back to decline.", approvalInstruction(true))
        assertEquals("Answer on your paired phone.", approvalInstruction(false))
    }
    @Test fun `status tones have distinct noncolor signals`() {
        assertNull(statusGlyph(StatusTone.Neutral))
        assertEquals(VelaGlyph.Activity, statusGlyph(StatusTone.Agent))
        assertEquals(VelaGlyph.Check, statusGlyph(StatusTone.Success))
        assertEquals(VelaGlyph.Caution, statusGlyph(StatusTone.Caution))
        assertEquals(VelaGlyph.Error, statusGlyph(StatusTone.Danger))
        assertEquals(StatusTone.Danger, StatusKind.Error.tone)
        assertEquals(StatusTone.Caution, StatusKind.Caution.tone)
        assertEquals(StatusTone.Success, StatusKind.Success.tone)
    }
}
