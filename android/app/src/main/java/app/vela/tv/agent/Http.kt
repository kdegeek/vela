package app.vela.tv.agent

import app.vela.tv.network.SingleAttemptBody

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Never forward credentials to an HTTP redirect target. The owner must configure the final endpoint. */
internal fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS).callTimeout(120, TimeUnit.SECONDS)
    .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()
internal class HttpReply(val status: Int, val body: String, val retryAfter: String? = null) {
    val ok get() = status in 200..299
}

/** Retry only explicit transient model HTTP responses. Never retry a TV/home action or an uncertain transport failure. */
internal suspend fun OkHttpClient.postJson(url: String, headers: Map<String, String>, body: JsonObject): HttpReply = withContext(Dispatchers.IO) {
    val httpUrl = try { url.toHttpUrl() } catch (e: IllegalArgumentException) { throw ProviderException("That model server address is invalid.", e) }
    val request = Request.Builder().url(httpUrl).apply { headers.forEach { (k, v) -> header(k, v) } }
        .post(SingleAttemptBody(body.toString().toRequestBody("application/json".toMediaType()))).build()
    try {
        var reply: HttpReply? = null
        repeat(3) { attempt ->
            val received = suspendCancellableCoroutine<HttpReply> { cont ->
                // Also disable redirects on injected clients used in tests/custom endpoints.
                val call = newBuilder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build().newCall(request)
                cont.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) { if (!cont.isCancelled) cont.resumeWithException(e) }
                    override fun onResponse(call: Call, response: Response) {
                        try {
                            val result = response.use {
                                val source = it.body.source()
                                source.request(4_194_305)
                                if (source.buffer.size > 4_194_304) throw IOException("Model response exceeded 4 MiB")
                                HttpReply(it.code, source.readUtf8(), it.header("Retry-After"))
                            }
                            cont.resume(result)
                        } catch (e: IOException) { if (!cont.isCancelled) cont.resumeWithException(e) }
                    }
                })
            }
            reply = received
            if (received.status !in setOf(429, 502, 503, 504) || attempt == 2) return@withContext received
            val retrySeconds = received.retryAfter?.toLongOrNull()
            // A long retry belongs to the owner, not a hidden many-minute retry loop.
            if (retrySeconds != null && retrySeconds > 10) return@withContext received
            delay((retrySeconds?.coerceAtLeast(0)?.times(1000) ?: (1000L shl attempt)).coerceAtMost(10_000))
        }
        reply!!
    } catch (e: IOException) { throw ProviderException("Can't reach ${httpUrl.host}: ${e.message ?: e.javaClass.simpleName}", e) }
}

internal fun errorDetail(body: String): String? {
    val root = runCatching { Json.parseToJsonElement(body) }.getOrNull() as? JsonObject
    val error = root?.get("error") ?: return null // never echo an HTML proxy page or credentials in arbitrary bodies
    return when (error) {
        is JsonPrimitive -> error.contentOrNull
        is JsonObject -> (error["message"] as? JsonPrimitive)?.contentOrNull
        else -> null
    }
}
internal fun HttpReply.requireOk(who: String, secret: String? = null) {
    if (ok) return
    val detail = errorDetail(body)?.let { if (!secret.isNullOrBlank()) it.replace(secret, "[redacted]") else it }?.take(200)
    throw ProviderException("$who said $status" + if (detail != null) ": $detail" else "")
}
internal fun HttpReply.parseObject(who: String): JsonObject = try { Json.parseToJsonElement(body).jsonObject }
    catch (e: Exception) { throw ProviderException("$who sent a reply Vela couldn't read", e) }
