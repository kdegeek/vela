package app.vela.tv.network

import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink

/**
 * Stops OkHttp follow-ups (including 503 Retry-After: 0) from transparently replaying a POST.
 * The caller may explicitly start another model request; device/home side effects never retry here.
 */
internal class SingleAttemptBody(private val body: RequestBody) : RequestBody() {
    override fun contentType(): MediaType? = body.contentType()
    override fun contentLength(): Long = body.contentLength()
    override fun isOneShot(): Boolean = true
    override fun writeTo(sink: BufferedSink) = body.writeTo(sink)
}
