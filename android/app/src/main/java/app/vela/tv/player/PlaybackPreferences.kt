package app.vela.tv.player

import android.content.Context
import android.media.MediaCodecList
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import app.vela.tv.media.PlaybackCapabilities
import app.vela.tv.profile.ProfilePreferences
import app.vela.tv.profile.SubtitlePreference
import kotlinx.serialization.json.Json

/** Persist language intent, never a source-specific track/group ID from a previous movie. */
@UnstableApi
internal class PlaybackPreferences(context: Context, private val plan: ProfilePlaybackPlan) {
    private val prefs = context.getSharedPreferences(plan.storageName, Context.MODE_PRIVATE)
    init {
        val signature = Json.encodeToString(ProfilePreferences.serializer(), plan.preferences)
        if (prefs.getString("profile-preferences", null) != signature) {
            prefs.edit().remove("audio-language").remove("subtitle-mode").remove("subtitle-language")
                .putString("profile-preferences", signature).apply()
        }
    }
    private var previous: TrackSelectionParameters? = null

    fun apply(parameters: TrackSelectionParameters): TrackSelectionParameters = profileTrackPreferences(parameters, plan.preferences).buildUpon().apply {
        prefs.getString("audio-language", null)?.let { setPreferredAudioLanguage(it) }
        when (prefs.getString("subtitle-mode", null)) {
            "off" -> {
                setSelectTextByDefault(false)
                setSelectUndeterminedTextLanguage(false)
                // Match the native CC None action: retain forced subtitles, clear ordinary text selection.
                setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                setIgnoredTextSelectionFlags(C.SELECTION_FLAG_FORCED.inv())
                setPreferredTextLanguage(null)
                setPreferredTextRoleFlags(0)
            }
            "language" -> {
                setSelectTextByDefault(true)
                setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                setIgnoredTextSelectionFlags(0)
                prefs.getString("subtitle-language", null)?.let { setPreferredTextLanguage(it) }
            }
        }
    }.build().also { previous = it }

    fun save(parameters: TrackSelectionParameters) {
        val before = previous ?: parameters
        previous = parameters
        fun TrackSelectionParameters.overridesOf(type: Int) = overrides.values.filter { it.type == type }
        fun language(type: Int) = parameters.overridesOf(type).firstOrNull()?.let { override ->
            override.trackIndices.firstOrNull()?.let { override.mediaTrackGroup.getFormat(it).language }
        }?.takeUnless { it == "und" }
        val edit = prefs.edit()
        if (parameters.overridesOf(C.TRACK_TYPE_AUDIO) != before.overridesOf(C.TRACK_TYPE_AUDIO)) {
            language(C.TRACK_TYPE_AUDIO)?.let { edit.putString("audio-language", it) } ?: edit.remove("audio-language")
        }
        val textChanged = parameters.overridesOf(C.TRACK_TYPE_TEXT) != before.overridesOf(C.TRACK_TYPE_TEXT) ||
            parameters.ignoredTextSelectionFlags != before.ignoredTextSelectionFlags ||
            parameters.disabledTrackTypes != before.disabledTrackTypes || parameters.preferredTextLanguages != before.preferredTextLanguages
        if (textChanged) {
            val off = C.TRACK_TYPE_TEXT in parameters.disabledTrackTypes ||
                (parameters.overridesOf(C.TRACK_TYPE_TEXT).isEmpty() && parameters.preferredTextLanguages.isEmpty() &&
                    parameters.ignoredTextSelectionFlags and C.SELECTION_FLAG_DEFAULT != 0)
            edit.putString("subtitle-mode", if (off) "off" else "language")
            language(C.TRACK_TYPE_TEXT)?.let { edit.putString("subtitle-language", it) } ?: edit.remove("subtitle-language")
        }
        edit.apply()
    }
}

/** On explicitly permits any supported text track, including non-default language-tagged captions. */
@UnstableApi
internal fun profileTrackPreferences(parameters: TrackSelectionParameters, defaults: ProfilePreferences): TrackSelectionParameters =
    parameters.buildUpon().apply {
        if (defaults.audioLanguages.isNotEmpty()) setPreferredAudioLanguages(*defaults.audioLanguages.toTypedArray())
        if (defaults.subtitleLanguages.isNotEmpty()) setPreferredTextLanguages(*defaults.subtitleLanguages.toTypedArray())
        when (defaults.subtitles) {
            SubtitlePreference.OFF -> {
                setSelectTextByDefault(false)
                setSelectUndeterminedTextLanguage(false)
                setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                setIgnoredTextSelectionFlags(C.SELECTION_FLAG_FORCED.inv())
                setPreferredTextLanguage(null)
                setPreferredTextRoleFlags(0)
            }
            SubtitlePreference.ON -> {
                setSelectTextByDefault(true)
                setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                setIgnoredTextSelectionFlags(0)
                setSelectUndeterminedTextLanguage(true)
            }
            SubtitlePreference.AUTOMATIC -> Unit
        }
    }.build()

internal fun devicePlaybackCapabilities(): PlaybackCapabilities {
    val types = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filterNot { it.isEncoder }
        .flatMap { it.supportedTypes.toList() }.map { it.lowercase() }.toSet()
    val video = mapOf("video/avc" to "h264", "video/hevc" to "hevc", "video/x-vnd.on2.vp8" to "vp8", "video/x-vnd.on2.vp9" to "vp9", "video/av01" to "av1", "video/mp4v-es" to "mpeg4")
    val audio = mapOf("audio/mp4a-latm" to "aac", "audio/mpeg" to "mp3", "audio/ac3" to "ac3", "audio/eac3" to "eac3", "audio/flac" to "flac", "audio/opus" to "opus", "audio/vorbis" to "vorbis")
    return PlaybackCapabilities(video.filterKeys { it in types }.values.toSet(), audio.filterKeys { it in types }.values.toSet())
}
