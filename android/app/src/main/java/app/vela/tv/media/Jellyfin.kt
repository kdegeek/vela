package app.vela.tv.media

import app.vela.tv.network.SingleAttemptBody
import java.io.IOException
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.add
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

private const val TICKS_PER_MIN = 600_000_000L

data class JellyfinItem(
    val id: String,
    val name: String,
    val type: String? = null,
    val year: Int? = null,
    val runTimeTicks: Long? = null,
    val positionTicks: Long? = null,
    val genres: List<String> = emptyList(),
    val overview: String? = null,
    val hasBackdrop: Boolean = false,
    val hasPrimary: Boolean = false,
    val seriesName: String? = null,
    val seriesId: String? = null,
    val seasonId: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val played: Boolean = false,
    val missing: Boolean = false,
) {
    val playable: Boolean get() = !missing && type in setOf("Movie", "Episode", "Video")
    val resumePositionMs: Long get() = if (hasRun && (positionTicks ?: 0) >= runTimeTicks!!) 0 else ticksToMilliseconds(positionTicks ?: 0)
    private val hasRun get() = runTimeTicks != null && runTimeTicks > 0
    private val hasPos get() = hasRun && positionTicks != null && positionTicks > 0

    val runtimeMinutes: Int? get() = if (hasRun) Math.round(runTimeTicks!!.toDouble() / TICKS_PER_MIN).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else null

    /** How far through the item the viewer is, 0..1; null when there is no resume point. */
    val progress: Double? get() = if (hasPos) minOf(1.0, positionTicks!!.toDouble() / runTimeTicks!!) else null

    val remainingMinutes: Int?
        get() = if (hasPos) (runTimeTicks!! - positionTicks!!).coerceAtLeast(0).let { it / TICKS_PER_MIN + if (it % TICKS_PER_MIN > 0) 1 else 0 }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else null
}

/** The server answered but rejected our credentials (or we have none). */
class JellyfinAuthError(message: String) : IOException(message)

@Serializable
private class RawItem(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String = "",
    @SerialName("Type") val type: String? = null,
    @SerialName("ProductionYear") val year: Int? = null,
    @SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
    @SerialName("UserData") val userData: RawUserData? = null,
    @SerialName("Genres") val genres: List<String>? = null,
    @SerialName("Overview") val overview: String? = null,
    @SerialName("BackdropImageTags") val backdropImageTags: List<String>? = null,
    @SerialName("ImageTags") val imageTags: Map<String, String>? = null,
    @SerialName("SeriesName") val seriesName: String? = null,
    @SerialName("SeriesId") val seriesId: String? = null,
    @SerialName("SeasonId") val seasonId: String? = null,
    @SerialName("ParentIndexNumber") val seasonNumber: Int? = null,
    @SerialName("IndexNumber") val episodeNumber: Int? = null,
    @SerialName("IsMissing") val missing: Boolean = false,
)

@Serializable
private class RawUserData(@SerialName("PlaybackPositionTicks") val positionTicks: Long? = null, @SerialName("Played") val played: Boolean = false)

@Serializable
private class ItemsResponse(
    @SerialName("Items") val items: List<RawItem> = emptyList(),
    @SerialName("TotalRecordCount") val total: Int? = null,
)

@Serializable
private class AuthResponse(@SerialName("AccessToken") val accessToken: String, @SerialName("User") val user: AuthUser)

@Serializable
private class AuthUser(@SerialName("Id") val id: String)

private val json = Json { ignoreUnknownKeys = true }
private val JSON_TYPE = "application/json".toMediaType()

class JellyfinClient(
    url: String,
    @Volatile private var token: String?,
    @Volatile private var userId: String?,
    private val deviceId: String,
    client: OkHttpClient = OkHttpClient(),
) {
    private val base = url.trimEnd('/').toHttpUrl()
    // Never forward an Authorization header (or an old URL token) through a redirect.
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).callTimeout(10, TimeUnit.SECONDS).build()
    internal val mediaHttp = client.newBuilder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .callTimeout(0, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()

    /** Image tags seen in listings, used to make image URLs cache-correct. */
    private val tags = ConcurrentHashMap<String, String>()

    /** Header for every request, including the token when we have one. */
    fun authHeader(): String {
        fun quoted(value: String) = value.replace("\\", "\\\\").replace("\"", "\\\"").also {
            require(it.none { char -> char < ' ' || char == '\u007f' }) { "Invalid Jellyfin authorization value." }
        }
        val safeDevice = quoted(deviceId)
        val head = """MediaBrowser Client="Vela", Device="Vela", DeviceId="$safeDevice", Version="0.1.0""""
        return token?.let { """$head, Token="${quoted(it)}"""" } ?: head
    }

    private fun url(vararg segments: String, query: Map<String, String> = emptyMap()): HttpUrl =
        base.newBuilder().apply {
            segments.forEach { addPathSegment(it) }
            query.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()

    private suspend fun send(url: HttpUrl, method: String = "GET", body: JsonObject? = null): String {
        val payload = body?.toString()?.toRequestBody(JSON_TYPE) ?: if (method == "GET") null else ByteArray(0).toRequestBody()
        val request = Request.Builder().url(url)
            .header("Authorization", authHeader())
            .method(method, payload?.let(::SingleAttemptBody))
            .build()
        return http.newCall(request).awaitBody { res ->
            when {
                res.code == 401 || res.code == 403 -> throw JellyfinAuthError("Jellyfin rejected the sign-in (${res.code})")
                !res.isSuccessful -> throw IOException("Jellyfin $method ${url.encodedPath} failed: ${res.code}")
                else -> {
                    val source = res.body.source()
                    val maximum = 8L * 1024 * 1024
                    if (source.request(maximum + 1)) throw IOException("Jellyfin response is too large. Request a smaller page.")
                    source.readUtf8()
                }
            }
        }
    }

    suspend fun authenticate(username: String, password: String): Pair<String, String> {
        val text = send(url("Users", "AuthenticateByName"), "POST", buildJsonObject {
            put("Username", username)
            put("Pw", password)
        })
        val r = json.decodeFromString<AuthResponse>(text)
        token = r.accessToken
        userId = r.user.id
        return r.accessToken to r.user.id
    }

    private fun signedIn(): String = userId ?: throw JellyfinAuthError("Jellyfin client is not signed in")

    private fun parse(items: List<RawItem>): List<JellyfinItem> = items.map { r ->
        val bd = r.backdropImageTags?.firstOrNull()
        val pr = r.imageTags?.get("Primary")
        if (bd != null) tags["${r.id}/Backdrop"] = bd
        if (pr != null) tags["${r.id}/Primary"] = pr
        JellyfinItem(
            id = r.id,
            name = r.name,
            type = r.type,
            year = r.year,
            runTimeTicks = r.runTimeTicks,
            positionTicks = r.userData?.positionTicks,
            genres = r.genres.orEmpty(),
            overview = r.overview,
            hasBackdrop = bd != null,
            hasPrimary = pr != null,
            seriesName = r.seriesName,
            seriesId = r.seriesId, seasonId = r.seasonId, seasonNumber = r.seasonNumber, episodeNumber = r.episodeNumber,
            played = r.userData?.played ?: false, missing = r.missing,
        )
    }

    private suspend fun items(url: HttpUrl): List<JellyfinItem> {
        val text = send(url)
        return withContext(Dispatchers.IO) {
            val root = json.parseToJsonElement(text)
            val raw = if (root is JsonArray) json.decodeFromJsonElement(ListSerializer(RawItem.serializer()), root)
            else json.decodeFromJsonElement(ItemsResponse.serializer(), root).items
            parse(raw)
        }
    }

    suspend fun resume(limit: Int = 12): List<JellyfinItem> = items(
        url(
            "UserItems", "Resume",
            query = mapOf("userId" to signedIn(), "limit" to "$limit", "mediaTypes" to "Video", "fields" to "Overview,Genres", "enableImageTypes" to "Backdrop,Primary"),
        ),
    )

    /** One item with the signed-in user's resume position. */
    suspend fun item(itemId: String): JellyfinItem {
        val text = send(url("Items", itemId, query = mapOf("userId" to signedIn(), "fields" to "Overview,Genres")))
        return withContext(Dispatchers.IO) { parse(listOf(json.decodeFromString(RawItem.serializer(), text))).single() }
    }

    /** Recently added items; [libraryName] (case-insensitive) narrows it to one library. */
    suspend fun latest(limit: Int = 12, libraryName: String? = null): List<JellyfinItem> {
        val user = signedIn()
        val query = mutableMapOf("userId" to user, "limit" to "$limit", "fields" to "Overview,Genres", "enableImageTypes" to "Backdrop,Primary")
        if (libraryName != null) query["parentId"] = libraryId(user, libraryName)
        return items(url("Items", "Latest", query = query))
    }

    private suspend fun libraryId(user: String, name: String): String {
        val views = json.decodeFromString<ItemsResponse>(send(url("UserViews", query = mapOf("userId" to user)))).items
        return views.firstOrNull { it.name.equals(name, ignoreCase = true) }?.id
            ?: throw IllegalArgumentException("No library named \"$name\". Available: ${views.joinToString { it.name }}")
    }

    suspend fun search(query: String, limit: Int = 24): List<JellyfinItem> = items(
        url(
            "Items",
            query = mapOf(
                "userId" to signedIn(), "searchTerm" to query, "recursive" to "true", "includeItemTypes" to "Movie,Series,Episode",
                "limit" to "$limit", "fields" to "Overview,Genres", "enableImageTypes" to "Backdrop,Primary",
            ),
        ),
    )

    /** Existing callers get every episode by default, fetched in cancellable pages. Explicit limits remain caps. */
    suspend fun episodes(seriesId: String, limit: Int = Int.MAX_VALUE): List<JellyfinItem> {
        require(limit > 0)
        val result = mutableListOf<JellyfinItem>()
        val seen = HashSet<String>()
        var offset = 0
        while (result.size < limit) {
            val page = episodesPage(seriesId, offset, minOf(100, limit - result.size))
            val fresh = page.items.filter { seen.add(it.id) }
            if (page.items.isNotEmpty() && fresh.isEmpty()) throw IOException("Jellyfin repeated an episode page. Retry the library.")
            result += fresh
            offset = page.nextStartIndex ?: break
        }
        return result
    }

    suspend fun libraries(): List<JellyfinItem> = items(url("UserViews", query = mapOf("userId" to signedIn())))

    suspend fun seasons(seriesId: String): List<JellyfinItem> = items(url("Shows", seriesId, "Seasons",
        query = mapOf("userId" to signedIn(), "fields" to "Overview,Genres", "isMissing" to "false")))

    suspend fun episodesPage(seriesId: String, startIndex: Int = 0, limit: Int = 100, seasonId: String? = null): JellyfinPage {
        val query = mutableMapOf("userId" to signedIn(), "fields" to "Overview,Genres", "isMissing" to "false")
        seasonId?.let { query["seasonId"] = it }
        return page(url("Shows", seriesId, "Episodes", query = query), startIndex, limit)
    }

    suspend fun browse(parentId: String? = null, startIndex: Int = 0, limit: Int = 50): JellyfinPage {
        val query = mutableMapOf("userId" to signedIn(), "fields" to "Overview,Genres", "sortBy" to "SortName", "sortOrder" to "Ascending")
        parentId?.let { query["parentId"] = it }
        return page(url("Items", query = query), startIndex, limit)
    }

    suspend fun searchPage(query: String, startIndex: Int = 0, limit: Int = 50): JellyfinPage = page(url("Items", query = mapOf(
        "userId" to signedIn(), "searchTerm" to query, "recursive" to "true", "includeItemTypes" to "Movie,Series,Episode",
        "fields" to "Overview,Genres")), startIndex, limit)

    suspend fun nextUp(seriesId: String? = null, startIndex: Int = 0, limit: Int = 20): JellyfinPage {
        val query = mutableMapOf("userId" to signedIn(), "fields" to "Overview,Genres", "enableResumable" to "true")
        seriesId?.let { query["seriesId"] = it }
        return page(url("Shows", "NextUp", query = query), startIndex, limit)
    }

    /** The immediate next episode in server order, independent of played status. No automatic playback. */
    suspend fun nextEpisode(episode: JellyfinItem): JellyfinItem? {
        val series = episode.seriesId ?: return null
        if (episode.type != "Episode") return null
        val following = items(url("Shows", series, "Episodes", query = mapOf(
            "userId" to signedIn(), "startItemId" to episode.id, "limit" to "2", "isMissing" to "false", "fields" to "Overview,Genres")))
        val current = following.indexOfFirst { it.id == episode.id }
        return if (current >= 0) following.getOrNull(current + 1)?.takeIf { it.playable } else null
    }

    private suspend fun page(endpoint: HttpUrl, start: Int, limit: Int): JellyfinPage {
        require(start >= 0 && limit in 1..500) { "Invalid Jellyfin page (limit 1–500; start >= 0)." }
        val response = json.decodeFromString<ItemsResponse>(send(endpoint.newBuilder()
            .addQueryParameter("startIndex", "$start").addQueryParameter("limit", "$limit").build()))
        val count = response.items.size
        val next = start.toLong() + count
        val total = response.total?.takeIf { it >= 0 }
        // Servers may cap a page below the requested limit. A known total takes precedence over page length.
        val more = count > 0 && next <= Int.MAX_VALUE && if (total != null) next < total else count >= limit
        return JellyfinPage(parse(response.items), start, total, if (more) next.toInt() else null)
    }

    fun imageUrl(itemId: String, type: ImageType, maxWidth: Int): String =
        base.newBuilder().apply {
            addPathSegment("Items"); addPathSegment(itemId); addPathSegment("Images"); addPathSegment(type.name)
            if (type == ImageType.Backdrop) addPathSegment("0")
            addQueryParameter("maxWidth", "$maxWidth")
            addQueryParameter("quality", "90")
            tags["$itemId/${type.name}"]?.let { addQueryParameter("tag", it) }
        }.build().toString()

    fun streamUrl(itemId: String): String =
        url("Videos", itemId, "stream", query = mapOf("static" to "true", "api_key" to (token ?: ""))).toString()

    /** Only same-origin URLs may use Vela's Jellyfin credentials. URL queries/fragments are not inherited. */
    internal fun mediaUrl(value: String): HttpUrl {
        val prefix = base.encodedPath.trimEnd('/')
        val directory = base.newBuilder().encodedPath("$prefix/").build()
        // Jellyfin generates API-root-relative DeliveryUrl values even behind a configured base path.
        val target = if (value.startsWith("//") || value.contains("://") || value.startsWith("$prefix/") && prefix.isNotEmpty()) value else value.trimStart('/')
        val resolved = directory.resolve(target) ?: throw JellyfinPlaybackError("Invalid media address from Jellyfin.")
        if (resolved.scheme != base.scheme || resolved.host != base.host || resolved.port != base.port ||
            resolved.username.isNotEmpty() || resolved.password.isNotEmpty() || !resolved.encodedPath.startsWith("$prefix/")) {
            throw JellyfinPlaybackError("Jellyfin returned a media address on another server.")
        }
        return resolved.newBuilder().removeAllQueryParameters("api_key").fragment(null).build()
    }

    /** Negotiates direct play; no unmeasured transcoding or DRM profile is advertised. */
    suspend fun playback(item: JellyfinItem, capabilities: PlaybackCapabilities, positionMs: Long = item.resumePositionMs): JellyfinPlayback {
        if (!item.playable) throw JellyfinPlaybackError("Choose an available episode or movie to play.")
        val start = positionMs.coerceAtLeast(0).let { pos ->
            val duration = ticksToMilliseconds(item.runTimeTicks ?: 0)
            if (duration > 0 && pos >= duration) 0 else pos
        }
        val response = json.decodeFromString<PlaybackInfoResponse>(send(url("Items", item.id, "PlaybackInfo"), "POST", buildJsonObject {
            put("UserId", signedIn()); put("StartTimeTicks", millisecondsToTicks(start))
            put("AutoOpenLiveStream", false)
            put("EnableDirectPlay", true); put("EnableDirectStream", false); put("EnableTranscoding", false)
            put("DeviceProfile", buildJsonObject {
                put("Name", "Vela Media3 direct play")
                put("DirectPlayProfiles", buildJsonArray { add(buildJsonObject {
                    put("Type", "Video"); put("Container", "mp4,m4v,mov,mkv,webm,ts,mpegts")
                    put("VideoCodec", capabilities.videoCodecs.sorted().joinToString(","))
                    put("AudioCodec", capabilities.audioCodecs.sorted().joinToString(","))
                }) })
                put("TranscodingProfiles", buildJsonArray {})
                put("SubtitleProfiles", buildJsonArray {
                    for (format in listOf("srt", "vtt", "ass", "ssa")) add(buildJsonObject { put("Format", format); put("Method", "External") })
                })
            })
        }))
        if (response.errorCode != null) throw JellyfinPlaybackError("Jellyfin has no compatible stream for this title.")
        val source = response.sources.firstOrNull { it.supportsDirectPlay && !it.requiresOpening && !it.infinite && it.id.isNotBlank() }
            ?: throw JellyfinPlaybackError("No direct-play version is available. Try another version or a Jellyfin client with transcoding.")
        val stream = url("Videos", item.id, "stream", query = buildMap {
            put("static", "true"); put("mediaSourceId", source.id); response.playSessionId?.let { put("playSessionId", it) }
        })
        val subtitles = source.streams.filter { it.type == "Subtitle" && (it.external || it.deliveryMethod == "External") && it.deliveryUrl != null }.mapNotNull { track ->
            val mime = when (track.codec?.lowercase()) { "srt", "subrip" -> "application/x-subrip"; "vtt", "webvtt" -> "text/vtt"; "ass", "ssa" -> "text/x-ssa"; else -> return@mapNotNull null }
            val address = try { mediaUrl(track.deliveryUrl!!) } catch (_: JellyfinPlaybackError) { return@mapNotNull null }
            PlaybackSubtitle(track.index, address.toString(), mime, track.language, track.title, track.default, track.forced)
        }
        return JellyfinPlayback(item, source, response.playSessionId, stream.toString(), start, subtitles)
    }

    private suspend fun report(vararg segments: String, body: JsonObject) { send(url("Sessions", *segments), "POST", body) }

    private fun reportBody(itemId: String, positionTicks: Long, playback: JellyfinPlayback?, paused: Boolean, canSeek: Boolean, audioIndex: Int?, subtitleIndex: Int?) = buildJsonObject {
        put("ItemId", itemId); put("PositionTicks", positionTicks.coerceAtLeast(0)); put("IsPaused", paused); put("CanSeek", canSeek)
        put("PlayMethod", playback?.playMethod ?: "DirectPlay")
        playback?.source?.id?.let { put("MediaSourceId", it) }; playback?.playSessionId?.let { put("PlaySessionId", it) }
        audioIndex?.let { put("AudioStreamIndex", it) }; subtitleIndex?.let { put("SubtitleStreamIndex", it) }
    }

    suspend fun reportStart(itemId: String, positionTicks: Long) = report("Playing", body = reportBody(itemId, positionTicks, null, false, true, null, null))
    suspend fun reportProgress(itemId: String, positionTicks: Long, paused: Boolean) = report("Playing", "Progress", body = reportBody(itemId, positionTicks, null, paused, true, null, null))
    suspend fun reportStart(playback: JellyfinPlayback, positionTicks: Long, paused: Boolean, canSeek: Boolean) = report("Playing", body = reportBody(playback.item.id, positionTicks, playback, paused, canSeek, null, null))
    suspend fun reportProgress(playback: JellyfinPlayback, positionTicks: Long, paused: Boolean, canSeek: Boolean, audioIndex: Int? = null, subtitleIndex: Int? = null) {
        var reportError: IOException? = null
        try { report("Playing", "Progress", body = reportBody(playback.item.id, positionTicks, playback, paused, canSeek, audioIndex, subtitleIndex)) }
        catch (e: JellyfinAuthError) { throw e } catch (e: IOException) { reportError = e }
        // API-key sessions do not update user data. Checkpoint confirmed progress so process death loses at most
        // one reporting interval when the server is reachable. Do not change historical watched/favorite state.
        saveResume(playback.item.id, positionTicks, false)
        reportError?.let { throw it }
    }

    suspend fun reportStopped(itemId: String, positionTicks: Long) = stopped(itemId, positionTicks, null, false, false)
    suspend fun reportStopped(playback: JellyfinPlayback, positionTicks: Long, completed: Boolean, failed: Boolean) = stopped(playback.item.id, positionTicks, playback, completed, failed)

    private suspend fun stopped(itemId: String, positionTicks: Long, playback: JellyfinPlayback?, completed: Boolean, failed: Boolean) {
        // API-key sessions have no user. Preserve the configured user's *exact* confirmed resume point; never
        // guess watched status at a hard-coded percentage. Natural end alone explicitly marks this item played.
        var reportError: IOException? = null
        try { report("Playing", "Stopped", body = buildJsonObject {
            put("ItemId", itemId); put("PositionTicks", positionTicks.coerceAtLeast(0)); put("Failed", failed)
            playback?.source?.id?.let { put("MediaSourceId", it) }; playback?.playSessionId?.let { put("PlaySessionId", it) }
        }) } catch (e: JellyfinAuthError) { throw e } catch (e: IOException) { reportError = e }
        saveResume(itemId, positionTicks, completed && !failed)
        reportError?.let { throw it }
    }

    private suspend fun saveResume(itemId: String, positionTicks: Long, completed: Boolean) {
        val user = signedIn()
        val key = "$base|$user|$itemId"
        val observedAt = Instant.now().toString()
        ResumeCheckpoints.save(key) {
            send(url("UserItems", itemId, "UserData", query = mapOf("userId" to user)), "POST", buildJsonObject {
                put("PlaybackPositionTicks", if (completed) 0 else positionTicks.coerceAtLeast(0))
                put("LastPlayedDate", observedAt)
                if (completed) put("Played", true)
            })
        }
    }

    internal fun scheduleResumeRetry(scope: CoroutineScope) = ResumeCheckpoints.schedule(scope)

    enum class ImageType { Backdrop, Primary }
}

/** Keep the continuation cancellable until the entire bounded body is read on OkHttp's dispatcher. */
private suspend fun <T> Call.awaitBody(read: (Response) -> T): T = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }
        override fun onResponse(call: Call, response: Response) {
            try {
                val value = response.use(read)
                if (!cont.isCancelled) cont.resume(value)
            } catch (e: Exception) {
                if (!cont.isCancelled) cont.resumeWithException(e)
            }
        }
    })
}
