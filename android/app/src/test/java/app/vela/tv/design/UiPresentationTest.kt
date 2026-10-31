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
