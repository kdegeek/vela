package app.vela.tv.network

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Body consumption stays on OkHttp's worker and remains cancellable until it closes. */
internal suspend fun <T> Call.awaitResponse(read: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!continuation.isCancelled) continuation.resumeWithException(e)
        }
        override fun onResponse(call: Call, response: Response) {
            try {
                val value = response.use(read)
                if (!continuation.isCancelled) continuation.resume(value)
            } catch (e: Exception) {
                if (!continuation.isCancelled) continuation.resumeWithException(e)
            }
        }
    })
}
