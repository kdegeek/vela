package app.vela.tv.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .callTimeout(120, TimeUnit.SECONDS)
    .build()

internal class HttpReply(val status: Int, val body: String) {
    val ok get() = status in 200..299
}

/**
 * POSTs [body] as JSON off the main thread, cancelling the call if the coroutine is cancelled.
 * Transport failures become a [ProviderException]; HTTP error statuses are returned for the caller to interpret.
 */
internal suspend fun OkHttpClient.postJson(url: String, headers: Map<String, String>, body: JsonObject): HttpReply =
    withContext(Dispatchers.IO) {
        val httpUrl = try {
            url.toHttpUrl()
        } catch (e: IllegalArgumentException) {
            throw ProviderException("That server address doesn't look right: $url", e)
        }
        val request = Request.Builder().url(httpUrl)
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        try {
            suspendCancellableCoroutine { cont ->
                val call = newCall(request)
                cont.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        cont.resumeWithException(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        try {
                            cont.resume(response.use { HttpReply(it.code, it.body.string()) })
                        } catch (e: IOException) {
                            cont.resumeWithException(e)
                        }
                    }
                })
            }
        } catch (e: IOException) {
            throw ProviderException("Can't reach ${httpUrl.host}: ${e.message ?: e.javaClass.simpleName}", e)
        }
    }

/** Both APIs put the human-readable reason at `error.message`; Ollama-style servers sometimes send `error` as a bare string. */
internal fun errorDetail(body: String): String? {
    val root = runCatching { Json.parseToJsonElement(body) }.getOrNull() as? JsonObject
    val error: JsonElement = root?.get("error") ?: return body.trim().take(200).ifBlank { null }
    return when (error) {
        is JsonPrimitive -> error.contentOrNull
        is JsonObject -> (error["message"] as? JsonPrimitive)?.contentOrNull
        else -> null
    }
}

internal fun HttpReply.requireOk(who: String) {
    if (ok) return
    val detail = errorDetail(body)
    throw ProviderException("$who said $status" + if (detail != null) ": $detail" else "")
}

internal fun HttpReply.parseObject(who: String): JsonObject =
    try {
        Json.parseToJsonElement(body).jsonObject
    } catch (e: Exception) {
        throw ProviderException("$who sent a reply Vela couldn't read", e)
    }
