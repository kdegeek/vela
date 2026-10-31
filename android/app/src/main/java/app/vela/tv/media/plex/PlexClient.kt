package app.vela.tv.media.plex

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer

/** Owner-configured PMS only. No cloud discovery, automatic retries, redirects or playback requests. */
class PlexClient(address: String, private val token: String, private val clientId: String, http: OkHttpClient = defaultHttp,
    private val beforeRead: () -> Unit = {}) {
    private val base = validateAddress(address)
    private val http = http.also {
        require(it.interceptors.isEmpty() && it.networkInterceptors.isEmpty()) { "Plex requires a private HTTP client without interceptors." }
    }.newBuilder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .cookieJar(okhttp3.CookieJar.NO_COOKIES).cache(null)
        .authenticator(okhttp3.Authenticator.NONE).proxyAuthenticator(okhttp3.Authenticator.NONE)
        .callTimeout(15, TimeUnit.SECONDS).connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
        // OkHttp can replay GET on 503 + Retry-After: 0 even with retryOnConnectionFailure(false).
        // Removing that opt-in prevents a hidden follow-up; return the original status as a safe error.
        .addNetworkInterceptor { chain -> chain.proceed(chain.request()).let { response ->
            if (response.code == 503) response.newBuilder().removeHeader("Retry-After").build() else response
        } }.build()
    private val identityLock = Mutex()
    @Volatile private var server: PlexServer? = null
    init {
        require(validToken(token)) { "Invalid Plex credential." }
        require(!base.toString().contains(token)) { "Plex address must not contain its credential." }
        require(clientId.matches(Regex("[A-Za-z0-9._:-]{1,128}"))) { "Invalid Plex client identifier." }
    }
    override fun toString() = "PlexClient([private connection])"

    suspend fun identity(): PlexServer = identityLock.withLock {
        server ?: withContext(Dispatchers.IO) {
            val c = container(get(base, JSON_LIMIT).bytes)
            val id = c.text("machineIdentifier")?.takeIf { it.matches(Regex("[A-Za-z0-9-]{1,128}")) && !it.contains(token) }
                ?: invalid()
            PlexServer(id, c.text("friendlyName")?.safe(200) ?: "Plex server", c.text("version")?.safe(100)).also { server = it }
        }
    }

    /** Checks authenticated root identity AND an authenticated library read; not playback or app availability. */
    suspend fun checkConnection(): PlexConnection = PlexConnection(identity(), libraries())

    suspend fun libraries(page: PlexPageRequest = PlexPageRequest()): PlexPage<PlexLibrary> =
        page(listOf("library", "sections", "all"), page = page, array = "Directory") { raw, _ ->
            PlexLibrary(raw.text("key")?.takeIf(::validId) ?: invalid(), raw.text("title")?.safe(200) ?: "Untitled library",
                raw.text("type")?.safe(40) ?: "unknown")
        }

    suspend fun browse(libraryId: String, page: PlexPageRequest = PlexPageRequest()): PlexPage<PlexItem> =
        section(libraryId, emptyMap(), page)

    /** Documented media-query title-contains filter; section-scoped, not fuzzy/global Plex cloud search. */
    suspend fun search(libraryId: String, query: String, page: PlexPageRequest = PlexPageRequest()): PlexPage<PlexItem> {
        require(query.isNotBlank() && query.length <= 500 && query.none(Char::isISOControl) && !query.contains(token)) { "Invalid Plex search." }
        return section(libraryId, mapOf("title" to query), page)
    }
    suspend fun recent(libraryId: String, page: PlexPageRequest = PlexPageRequest()): PlexPage<PlexItem> =
        section(libraryId, mapOf("sort" to "addedAt:desc"), page)
    suspend fun resume(page: PlexPageRequest = PlexPageRequest()): PlexPage<PlexItem> = hub("home.continue", page)
    suspend fun onDeck(page: PlexPageRequest = PlexPageRequest()): PlexPage<PlexItem> = hub("home.ondeck", page)
    suspend fun item(id: String): PlexItem {
        require(validId(id)) { "Invalid Plex item." }
        return page(listOf("library", "metadata", id), page = PlexPageRequest(0, 1), array = "Metadata", transform = ::item)
            .items.singleOrNull() ?: invalid()
    }

    /** Authenticated image bytes for Coil. Never hand a credential-bearing URL or request object to UI/cache. */
    suspend fun artwork(item: PlexItem, backdrop: Boolean = false): ByteArray? {
        require(item.serverId == identity().machineIdentifier) { "Plex artwork belongs to another server." }
        val path = (if (backdrop) item.art else item.thumb)?.takeIf(::validArtwork) ?: return null
        return get(route(path.trimStart('/').split('/')), ART_LIMIT, image = true).bytes
    }

    private suspend fun section(id: String, query: Map<String, String>, request: PlexPageRequest): PlexPage<PlexItem> {
        require(validId(id)) { "Invalid Plex library." }
        return page(listOf("library", "sections", id, "all"), query, request, "Metadata", id, ::item)
    }

    /** Discover the documented hub key, then page that key. No guessed /library/onDeck endpoint. */
    private suspend fun hub(identifier: String, request: PlexPageRequest): PlexPage<PlexItem> = withContext(Dispatchers.IO) {
        identity()
        val c = container(get(route(listOf("hubs"), mapOf("count" to "1", "identifier" to identifier)), JSON_LIMIT).bytes)
        verifyIdentity(c)
        val hub = c.array("Hub").map { it as? JsonObject ?: invalid() }.firstOrNull {
            it.text("hubIdentifier")?.lowercase() == identifier
        } ?: return@withContext PlexPage(emptyList(), request.offset, 0, null)
        val key = hub.text("key") ?: unsupportedHub()
        // Only same-origin, root-relative discovered hub keys and a numeric type filter are admitted.
        if (!Regex("/hubs/(?:home|sections/[0-9]+)/[A-Za-z][A-Za-z0-9]*(?:\\?type=[0-9]{1,2})?").matches(key) || key.contains(token)) unsupportedHub()
        val path = key.substringBefore('?').trimStart('/').split('/')
        val query = if ('?' in key) mapOf("type" to key.substringAfter("type=")) else emptyMap()
        page(path, query, request, "Metadata", transform = ::item)
    }

    private suspend fun <T> page(path: List<String>, query: Map<String, String> = emptyMap(), page: PlexPageRequest,
        array: String, library: String? = null, transform: (JsonObject, String?) -> T): PlexPage<T> = withContext(Dispatchers.IO) {
        identity()
        val response = get(route(path, query), JSON_LIMIT, page)
        val c = container(response.bytes)
        verifyIdentity(c)
        val rows = c.array(array)
        if (rows.size > 1000 || c.number("size")?.let { it != rows.size.toLong() } == true) invalid()
        val ids = rows.map { (it as? JsonObject)?.text(if (array == "Directory") "key" else "ratingKey") ?: invalid() }
        if (ids.distinct().size != ids.size) invalid()
        val bodyOffset = c.number("offset")?.asInt()
        val bodyTotal = c.number("totalSize")?.asInt()
        if (response.start != null && bodyOffset != null && response.start != bodyOffset ||
            response.total != null && bodyTotal != null && response.total != bodyTotal) invalid()
        val offset = response.start ?: bodyOffset
        val total = response.total ?: bodyTotal
        if (offset != null && offset != page.offset || offset == null && page.offset != 0) invalid()
        if (total != null && (total < page.offset || rows.size > total - page.offset)) invalid()
        val count = rows.size.coerceAtMost(page.limit)
        val next = (page.offset + count).takeIf { offset != null && count > 0 && it <= 1_000_000 && (total == null || it < total) }
        val contextLibrary = library ?: c.text("librarySectionID")?.takeIf(::validId)
        PlexPage(rows.take(count).map { transform(it as? JsonObject ?: invalid(), contextLibrary) }, page.offset, total, next,
            truncated = rows.size > count || offset == null && total != null && total > count)
    }

    private fun item(raw: JsonObject, library: String?): PlexItem = PlexItem(
        server!!.machineIdentifier, raw.text("ratingKey")?.takeIf(::validId) ?: invalid(),
        raw.text("title")?.safe(200)?.takeIf(String::isNotBlank) ?: "Untitled", raw.text("type")?.safe(40) ?: "unknown",
        raw.text("librarySectionID")?.takeIf(::validId) ?: library,
        raw.number("year")?.takeIf { it in 0..9999 }?.toInt(), raw.text("summary")?.safe(4000, multiline = true),
        raw.text("grandparentTitle")?.safe(200)?.takeIf(String::isNotBlank), raw.number("parentIndex")?.asInt(), raw.number("index")?.asInt(),
        raw.number("duration")?.takeIf { it >= 0 }, raw.number("viewOffset")?.takeIf { it >= 0 },
        raw.text("contentRating")?.safe(80), raw.text("thumb")?.takeIf(::validArtwork), raw.text("art")?.takeIf(::validArtwork),
    )
    private fun verifyIdentity(c: JsonObject) {
        if (c.text("machineIdentifier")?.let { it != server!!.machineIdentifier } == true)
            throw PlexException(PlexError.IDENTITY_CHANGED, "Plex server identity changed. Reconnect before browsing.")
    }
    private fun route(path: List<String>, query: Map<String, String> = emptyMap()): HttpUrl = base.newBuilder().apply {
        path.forEach(::addPathSegment)
        query.forEach { (k, v) -> addQueryParameter(k, v) }
    }.build()
    private fun container(bytes: ByteArray): JsonObject = try {
        // Bound parser recursion before JSON tree allocation, including otherwise valid hostile metadata.
        var depth = 0
        var quoted = false
        var escaped = false
        for (byte in bytes) {
            val c = byte.toInt().toChar()
            if (quoted) {
                if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false
            } else when (c) {
                '"' -> quoted = true
                '{', '[' -> if (++depth > 32) invalid()
                '}', ']' -> --depth
            }
        }
        (Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject)?.get("MediaContainer") as? JsonObject ?: invalid()
    } catch (_: Exception) { invalid() }
    private fun JsonObject.text(name: String) = (get(name) as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.number(name: String): Long? {
        if (name !in this) return null
        return text(name)?.takeIf { it.matches(Regex("0|[1-9][0-9]*")) }?.toLongOrNull() ?: invalid()
    }
    private fun JsonObject.array(name: String): JsonArray = get(name)?.let { it as? JsonArray ?: invalid() } ?: JsonArray(emptyList())
    private fun Long.asInt(): Int = if (this in 0..1_000_000) toInt() else invalid()
    private fun String.safe(limit: Int, multiline: Boolean = false) = replace(token, "[redacted]")
        .filter { !it.isISOControl() || multiline && it == '\n' }.take(limit)
    private fun validArtwork(path: String) = !path.contains(token) && Regex("/library/metadata/[0-9]{1,18}/(?:thumb|art)/[0-9]{1,18}").matches(path)

    private data class Body(val bytes: ByteArray, val start: Int?, val total: Int?)
    private suspend fun get(url: HttpUrl, limit: Int, page: PlexPageRequest? = null, image: Boolean = false): Body = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        beforeRead()
        require(!url.toString().contains(token)) { "Plex request address must not contain its credential." }
        suspendCancellableCoroutine { continuation ->
            val request = Request.Builder().url(url).header("X-Plex-Token", token).header("X-Plex-Client-Identifier", clientId)
                .header("X-Plex-Product", "Vela").header("X-Plex-Pms-Api-Version", "1.0.0")
                .header("Accept", if (image) "image/jpeg, image/png, image/webp" else "application/json").apply {
                    page?.let { header("X-Plex-Container-Start", it.offset.toString()); header("X-Plex-Container-Size", it.limit.toString()) }
                }.build()
            val call = http.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(PlexException(PlexError.NETWORK, "Plex could not be reached. Check the saved address and connection."))
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use { r ->
                            when (r.code) {
                                401, 403 -> throw PlexException(PlexError.AUTH, "Plex rejected the saved token. Check access on this server.")
                                in 300..399 -> throw PlexException(PlexError.REDIRECT, "Plex redirected the request. Save the final server address; credentials were not forwarded.")
                            }
                            if (!r.isSuccessful) throw PlexException(PlexError.HTTP, "Plex returned HTTP ${r.code}.")
                            val body = r.body
                            if (body.contentLength() > limit) bodyLimit()
                            if (image && body.contentType()?.toString()?.substringBefore(';') !in setOf("image/jpeg", "image/png", "image/webp")) invalid()
                            val buffer = Buffer()
                            val source = body.source()
                            while (buffer.size <= limit) {
                                if (source.read(buffer, minOf(8192L, limit + 1L - buffer.size)) == -1L) break
                            }
                            if (buffer.size > limit) bodyLimit()
                            fun header(name: String): Int? = r.header(name)?.let { value ->
                                value.takeIf { it.matches(Regex("0|[1-9][0-9]*")) }?.toLongOrNull()?.asInt() ?: invalid()
                            }
                            Body(buffer.readByteArray(), header("X-Plex-Container-Start"), header("X-Plex-Container-Total-Size"))
                        }
                        continuation.resume(result)
                    } catch (e: Exception) {
                        continuation.resumeWithException(if (e is PlexException) e else PlexException(PlexError.NETWORK, "Plex response could not be read."))
                    }
                }
            })
        }
    }
    companion object {
        private const val JSON_LIMIT = 1024 * 1024
        private const val ART_LIMIT = 512 * 1024
        private val defaultHttp by lazy { OkHttpClient() }
        fun validateAddress(address: String): HttpUrl {
            val url = address.trim().toHttpUrlOrNull()
            require(url != null && url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null && address.length <= 2048 && address.none(Char::isISOControl)) {
                "Plex needs an http(s) server address without credentials, query or fragment."
            }
            return url.newBuilder().apply { if (!url.encodedPath.endsWith('/')) addPathSegment("") }.build()
        }
        fun validToken(token: String) = token.length in 1..8192 && token.all { it.code in 33..126 }
        private fun validId(id: String) = id.matches(Regex("[0-9]{1,18}"))
        private fun invalid(): Nothing = throw PlexException(PlexError.INVALID_RESPONSE, "Plex returned invalid or unsupported metadata.")
        private fun bodyLimit(): Nothing = throw PlexException(PlexError.BODY_LIMIT, "Plex response exceeded the size limit.")
        private fun unsupportedHub(): Nothing = throw PlexException(PlexError.UNSUPPORTED_HUB, "This Plex server returned an unsupported hub route.")
    }
}
