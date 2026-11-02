package app.vela.tv.launcher

import app.vela.tv.profile.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class GlassManagementLanguagesTest {
    private val original = HouseholdProfile("person", "Alex", ProfileKind.ADULT,
        ProfilePreferences(preferredMediaProviders = listOf("jellyfin"),
            preferredLibraries = listOf(LibraryRef("jellyfin", "home")),
            audioLanguages = listOf("EN", "pt-BR", "zh-Hant-TW", "en-US-x-private"),
            subtitleLanguages = listOf("sr-Latn", "de-1996"), subtitles = SubtitlePreference.ON),
        ProfileRestrictions(allowedAppPackages = listOf("app.vela.tv"), allowHomeControl = false), 1, 1)

    @Test fun readableLabelsRoundTripExactSavedTagsThroughTheRealFormAndSerialization() {
        val audio = original.preferences.audioLanguages.joinToString(", ")
        val subtitles = original.preferences.subtitleLanguages.joinToString(", ")
        assertEquals("English, pt-BR, zh-Hant-TW, en-US-x-private", GlassLanguages.label(audio))
        assertEquals("sr-Latn, de-1996", GlassLanguages.label(subtitles))
        val draft = ManagementForms.profile(original, "Alex", audio, subtitles, SubtitlePreference.ON, false)
        validateDraft(draft)
        val decoded = Json.decodeFromString<ProfileDraft>(Json.encodeToString(draft))
        assertEquals(original.preferences, decoded.preferences)
        assertEquals(original.restrictions, decoded.restrictions)
    }

    @Test fun choosingTheCurrentLanguageDoesNotCanonicalizeCaseOrDraftSpacing() {
        assertEquals(" EN ", GlassLanguages.choose(" EN ", "en"))
        assertEquals(" pt-BR ", GlassLanguages.choose(" pt-BR ", "pt-br"))
        assertEquals(" , ", GlassLanguages.choose(" , ", ""))
        assertEquals("Follow the title", GlassLanguages.label(""))
    }

    @Test fun anExplicitNewChoiceChangesOnlyTheLanguagePreferenceBeingEdited() {
        val raw = original.preferences.audioLanguages.joinToString(", ")
        val chosen = GlassLanguages.choose(raw, "es")
        assertEquals("Spanish", GlassLanguages.label(chosen))
        val draft = ManagementForms.profile(original, "Alex", chosen,
            original.preferences.subtitleLanguages.joinToString(", "), SubtitlePreference.ON, false)
        validateDraft(draft)
        assertEquals(original.preferences.copy(audioLanguages = listOf("es")), draft.preferences)
        assertEquals(original.restrictions, draft.restrictions)
        assertEquals("", GlassLanguages.choose(chosen, ""))
    }

    @Test fun customOrderedTagsKeepTheirExactValuesAndBackendValidationRemainsAuthoritative() {
        val raw = "es-MX, en-GB, fil, zh-Hans"
        val draft = ManagementForms.profile(original, "Alex", raw, "", SubtitlePreference.AUTOMATIC, false)
        validateDraft(draft)
        assertEquals(listOf("es-MX", "en-GB", "fil", "zh-Hans"), draft.preferences.audioLanguages)
        assertEquals(draft, Json.decodeFromString<ProfileDraft>(Json.encodeToString(draft)))
        for (invalid in listOf("en, EN", "en_US", "en, @bad", (1..9).joinToString(",") { "en-x-$it" })) {
            val rejected = ManagementForms.profile(original, "Alex", invalid, "", SubtitlePreference.AUTOMATIC, false)
            try {
                validateDraft(rejected)
                fail("Backend accepted invalid language list: $invalid")
            } catch (error: HouseholdException) {
                assertEquals(HouseholdError.INVALID_INPUT, error.code)
            }
        }
    }
}
