package app.vela.tv.media.plex

import java.io.IOException

/** Public metadata contains no connection address or authentication material. Times are milliseconds. */
data class PlexServer(val machineIdentifier: String, val name: String, val version: String?)
data class PlexLibrary(val id: String, val title: String, val type: String)
data class PlexPageRequest(val offset: Int = 0, val limit: Int = 20) {
    init { require(offset in 0..1_000_000 && limit in 1..100) { "Invalid Plex page." } }
}
data class PlexPage<T>(val items: List<T>, val offset: Int, val total: Int?, val nextOffset: Int?, val truncated: Boolean = false)
data class PlexConnection(val server: PlexServer, val libraries: PlexPage<PlexLibrary>)
data class PlexItem(
    val serverId: String,
    val id: String,
    val title: String,
    val type: String,
    val libraryId: String?,
    val year: Int? = null,
    val summary: String? = null,
    val seriesTitle: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val durationMs: Long? = null,
    val viewOffsetMs: Long? = null,
    val contentRating: String? = null,
    val thumb: String? = null,
    val art: String? = null,
) {
    val progress: Float? get() = if (durationMs != null && durationMs > 0 && viewOffsetMs != null && viewOffsetMs > 0)
        (viewOffsetMs.toDouble() / durationMs).coerceIn(0.0, 1.0).toFloat() else null
    val resumePositionMs: Long get() = (viewOffsetMs ?: 0).coerceAtLeast(0).let {
        if (durationMs != null && durationMs > 0 && it >= durationMs) 0 else it
    }
}

enum class PlexError { AUTH, REDIRECT, HTTP, NETWORK, INVALID_RESPONSE, BODY_LIMIT, IDENTITY_CHANGED, UNSUPPORTED_HUB }
/** Deliberately has no raw server body, URL, request headers or nested network/parser exception. */
class PlexException(val code: PlexError, message: String) : IOException(message)
