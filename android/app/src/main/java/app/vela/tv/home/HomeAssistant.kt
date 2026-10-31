package app.vela.tv.home

import app.vela.tv.network.awaitResponse
import app.vela.tv.network.SingleAttemptBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class EntityState(val entityId: String, val state: String, val friendlyName: String?, val attributes: JsonObject)

/** Home Assistant rejected the long-lived access token. */
class HomeAssistantAuthError(message: String) : IOException(message)

class HomeAssistant(
    baseUrl: String,
    private val token: String,
    client: OkHttpClient = OkHttpClient(),
) {
    private val base = baseUrl.trimEnd('/').toHttpUrl()
    private val http = client.newBuilder().callTimeout(10, TimeUnit.SECONDS).retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()
    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun send(segments: List<String>, method: String = "GET", body: JsonObject? = null): String =
        sendOrNull(segments, method, body, nullOn404 = false)!!

    private suspend fun sendOrNull(segments: List<String>, method: String, body: JsonObject?, nullOn404: Boolean): String? = withContext(Dispatchers.IO) {
        val url = base.newBuilder().apply { segments.forEach { addPathSegment(it) } }.build()
        val request = Request.Builder().url(url)
            .header("Authorization", "Bearer $token")
            .method(method, if (method == "GET") null else SingleAttemptBody((body ?: JsonObject(emptyMap())).toString().toRequestBody(JSON_TYPE)))
            .build()
        http.newCall(request).awaitResponse { res ->
            val source = res.body.source()
            source.request(8_388_609)
            if (source.buffer.size > 8_388_608) throw IOException("Home Assistant response exceeded 8 MiB")
            val text = source.readUtf8()
            when {
                res.code == 401 -> throw HomeAssistantAuthError("Home Assistant rejected the access token (401)")
                res.code == 404 && nullOn404 -> null
                !res.isSuccessful -> throw IOException("Home Assistant $method ${url.encodedPath} failed: ${res.code} ${text.replace(token, "[redacted]").take(200)}")
                else -> text
            }
        }
    }

    suspend fun states(domain: String? = null): List<EntityState> =
        parseList(send(listOf("api", "states"))).filter { domain == null || it.entityId.startsWith("$domain.") }

    /** The entity's state, or null when Home Assistant doesn't know it. */
    suspend fun state(entityId: String): EntityState? {
        val text = sendOrNull(listOf("api", "states", entityId), "GET", null, nullOn404 = true) ?: return null
        return parse(json.parseToJsonElement(text).jsonObject)
    }

    /** Calls a service and returns the states it changed. */
    suspend fun call(domain: String, service: String, data: JsonObject): List<EntityState> =
        parseList(send(listOf("api", "services", domain, service), "POST", data))

    private fun parseList(text: String): List<EntityState> =
        (json.parseToJsonElement(text) as? JsonArray).orEmpty().map { parse(it.jsonObject) }

    private fun parse(o: JsonObject): EntityState {
        val attrs = o["attributes"] as? JsonObject ?: JsonObject(emptyMap())
        return EntityState(
            entityId = o.getValue("entity_id").jsonPrimitive.content,
            state = o["state"]?.jsonPrimitive?.contentOrNull ?: "unknown",
            friendlyName = attrs["friendly_name"]?.jsonPrimitive?.contentOrNull,
            attributes = attrs,
        )
    }

    private companion object {
        val JSON_TYPE = "application/json".toMediaType()
    }
}
