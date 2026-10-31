package app.vela.tv.player

import androidx.media3.common.C
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import app.vela.tv.profile.ProfilePreferences
import app.vela.tv.profile.SubtitlePreference
import org.junit.Assert.*
import org.junit.Test

@androidx.annotation.OptIn(UnstableApi::class)
class SubtitlePreferencesTest {
    @Test fun `On selects supported captions without language default or forced flags`() {
        val parameters = profileTrackPreferences(TrackSelectionParameters.Builder().build(),
            ProfilePreferences(subtitles = SubtitlePreference.ON))
        assertTrue(parameters.selectTextByDefault)
        assertTrue(parameters.preferredTextLanguages.isEmpty())
        assertFalse(C.TRACK_TYPE_TEXT in parameters.disabledTrackTypes)
        assertEquals(0, parameters.ignoredTextSelectionFlags)
    }
    @Test fun `Off resets a prior On intent and keeps only forced selection`() {
        val on = profileTrackPreferences(TrackSelectionParameters.Builder().build(),
            ProfilePreferences(subtitles = SubtitlePreference.ON))
        val off = profileTrackPreferences(on, ProfilePreferences(subtitles = SubtitlePreference.OFF))
        assertFalse(off.selectTextByDefault)
        assertFalse(off.selectUndeterminedTextLanguage)
        assertTrue(off.preferredTextLanguages.isEmpty())
        assertEquals(0, off.ignoredTextSelectionFlags and C.SELECTION_FLAG_FORCED)
        assertNotEquals(0, off.ignoredTextSelectionFlags and C.SELECTION_FLAG_DEFAULT)
    }
    @Test fun `Automatic retains native captioning intent`() {
        val native = TrackSelectionParameters.Builder().setSelectTextByDefault(true).build()
        assertEquals(native, profileTrackPreferences(native, ProfilePreferences()))
    }
}
