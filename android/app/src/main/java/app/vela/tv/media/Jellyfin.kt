package app.vela.tv.media

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
) {
    private val hasRun get() = runTimeTicks != null && runTimeTicks > 0
    private val hasPos get() = hasRun && positionTicks != null && positionTicks > 0

    val runtimeMinutes: Int? get() = if (hasRun) Math.round(runTimeTicks!!.toDouble() / TICKS_PER_MIN).toInt() else null

    /** How far through the item the viewer is, 0..1; null when there is no resume point. */
    val progress: Double? get() = if (hasPos) minOf(1.0, positionTicks!!.toDouble() / runTimeTicks!!) else null

    val remainingMinutes: Int?
        get() = if (hasPos) maxOf(0L, Math.ceilDiv(runTimeTicks!! - positionTicks!!, TICKS_PER_MIN)).toInt() else null
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
)

@Serializable
private class RawUserData(@SerialName("PlaybackPositionTicks") val positionTicks: Long? = null)

@Serializable
private class ItemsResponse(@SerialName("Items") val items: List<RawItem> = emptyList())

@Serializable
private class AuthResponse(@SerialName("AccessToken") val accessToken: String, @SerialName("User") val user: AuthUser)

@Serializable
private class AuthUser(@SerialName("Id") val id: String)

@Serializable
private class RunTime(@SerialName("RunTimeTicks") val runTimeTicks: Long? = null)

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
    private val http = client.newBuilder().callTimeout(10, TimeUnit.SECONDS).build()

    /** Image tags seen in listings, used to make image URLs cache-correct. */
    private val tags = ConcurrentHashMap<String, String>()

    /** Header for every request, including the token when we have one. */
    fun authHeader(): String {
        val head = """MediaBrowser Client="Vela", Device="Vela", DeviceId="$deviceId", Version="0.1.0""""
        return token?.let { """$head, Token="$it"""" } ?: head
    }

    private fun url(vararg segments: String, query: Map<String, String> = emptyMap()): HttpUrl =
        base.newBuilder().apply {
            segments.forEach { addPathSegment(it) }
            query.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()

    private suspend fun send(url: HttpUrl, method: String = "GET", body: JsonObject? = null): String {
        val request = Request.Builder().url(url)
            .header("Authorization", authHeader())
            .method(method, body?.toString()?.toRequestBody(JSON_TYPE) ?: if (method == "GET") null else ByteArray(0).toRequestBody())
            .build()
        return http.newCall(request).await().use { res ->
            when {
                res.code == 401 || res.code == 403 -> throw JellyfinAuthError("Jellyfin rejected the sign-in (${res.code})")
                !res.isSuccessful -> throw IOException("Jellyfin $method ${url.encodedPath} failed: ${res.code}")
                else -> res.body.string()
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

    private suspend fun report(vararg segments: String, body: JsonObject) {
        send(url("Sessions", *segments), "POST", body)
    }

    suspend fun reportStart(itemId: String, positionTicks: Long) = report("Playing", body = buildJsonObject {
        put("ItemId", itemId); put("PositionTicks", positionTicks); put("CanSeek", true); put("PlayMethod", "DirectPlay")
    })

    suspend fun reportProgress(itemId: String, positionTicks: Long, paused: Boolean) = report("Playing", "Progress", body = buildJsonObject {
        put("ItemId", itemId); put("PositionTicks", positionTicks); put("IsPaused", paused); put("CanSeek", true); put("PlayMethod", "DirectPlay")
    })

    /**
     * Reports the stop, then writes the user's resume point directly. Vela signs in with an API key, which has no user,
     * so Jellyfin applies the session reports to no one's user data. Mirrors Jellyfin's defaults: past 90% is played,
     * under 5% starts over.
     */
    suspend fun reportStopped(itemId: String, positionTicks: Long) {
        report("Playing", "Stopped", body = buildJsonObject { put("ItemId", itemId); put("PositionTicks", positionTicks) })
        val user = userId ?: return
        val run = try {
            json.decodeFromString<RunTime>(send(url("Items", itemId, query = mapOf("userId" to user)))).runTimeTicks
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null // runtime unknown: keep the position as it is
        }
        val pct = if (run != null && run > 0) positionTicks.toDouble() / run else null
        val data = if (pct != null && pct >= 0.9) buildJsonObject { put("Played", true); put("PlaybackPositionTicks", 0) }
        else buildJsonObject { put("PlaybackPositionTicks", if (pct != null && pct < 0.05) 0L else positionTicks) }
        send(url("UserItems", itemId, "UserData", query = mapOf("userId" to user)), "POST", data)
    }

    enum class ImageType { Backdrop, Primary }
}

/** Suspends until the response arrives; cancelling the coroutine cancels the call. Runs the request off the main thread. */
internal suspend fun Call.await(): Response = withContext(Dispatchers.IO) {
    suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!cont.isCancelled) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                cont.resume(response) { _, value, _ -> value.close() }
            }
        })
    }
}
