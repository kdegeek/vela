package app.vela.tv.media

import java.io.IOException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Millisecond conversions saturate rather than wrapping a corrupt server/player timestamp. */
fun millisecondsToTicks(milliseconds: Long): Long = milliseconds.coerceIn(0, Long.MAX_VALUE / 10_000) * 10_000
fun ticksToMilliseconds(ticks: Long): Long = ticks.coerceAtLeast(0) / 10_000

class JellyfinPlaybackError(message: String) : IOException(message)

/** Only advertise decoders actually present on the device. This is not a codec/profile guarantee. */
data class PlaybackCapabilities(val videoCodecs: Set<String>, val audioCodecs: Set<String>) {
    init { require(videoCodecs.isNotEmpty() && audioCodecs.isNotEmpty()) { "No usable audio/video decoders found." } }
}

@Serializable
internal data class PlaybackInfoResponse(
    @SerialName("MediaSources") val sources: List<MediaSource> = emptyList(),
    @SerialName("PlaySessionId") val playSessionId: String? = null,
    @SerialName("ErrorCode") val errorCode: String? = null,
)

@Serializable
data class MediaSource(
    @SerialName("Id") val id: String = "",
    @SerialName("Container") val container: String? = null,
    @SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
    @SerialName("SupportsDirectPlay") val supportsDirectPlay: Boolean = false,
    @SerialName("RequiresOpening") val requiresOpening: Boolean = false,
    @SerialName("IsInfiniteStream") val infinite: Boolean = false,
    @SerialName("DefaultAudioStreamIndex") val defaultAudioStreamIndex: Int? = null,
    @SerialName("DefaultSubtitleStreamIndex") val defaultSubtitleStreamIndex: Int? = null,
    @SerialName("MediaStreams") val streams: List<MediaStream> = emptyList(),
)

@Serializable
data class MediaStream(
    @SerialName("Index") val index: Int,
    @SerialName("Type") val type: String = "",
    @SerialName("Codec") val codec: String? = null,
    @SerialName("Language") val language: String? = null,
    @SerialName("DisplayTitle") val title: String? = null,
    @SerialName("IsExternal") val external: Boolean = false,
    @SerialName("IsDefault") val default: Boolean = false,
    @SerialName("IsForced") val forced: Boolean = false,
    @SerialName("DeliveryUrl") val deliveryUrl: String? = null,
    @SerialName("DeliveryMethod") val deliveryMethod: String? = null,
)

data class PlaybackSubtitle(val index: Int, val url: String, val mimeType: String, val language: String?, val label: String?, val default: Boolean, val forced: Boolean)

/** URLs are private, credential-free and must be loaded through the client's authenticated HTTP stack. */
data class JellyfinPlayback(
    val item: JellyfinItem,
    val source: MediaSource,
    val playSessionId: String?,
    val url: String,
    val startPositionMs: Long,
    val subtitles: List<PlaybackSubtitle>,
) {
    val playMethod: String get() = "DirectPlay"
}

/** Explicit page cursor; callers can render a page without downloading the entire library. */
data class JellyfinPage(val items: List<JellyfinItem>, val startIndex: Int, val totalRecordCount: Int?, val nextStartIndex: Int?)
