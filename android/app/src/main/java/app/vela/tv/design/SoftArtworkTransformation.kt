package app.vela.tv.design

import android.graphics.Bitmap
import androidx.core.graphics.scale
import coil3.size.Size
import coil3.transform.Transformation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Coil caches the resulting small software bitmap with this versioned transformation key. */
internal class SoftArtworkTransformation : Transformation() {
    override val cacheKey = "vela-ambient-480x270-box8x3-premultiplied-v1"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap = withContext(processing) {
        coroutineContext.ensureActive()
        val (width, height) = SoftArtwork.boundedSize(input.width, input.height)
        val bounded = input.scale(width, height, true)
        try {
            val pixels = IntArray(width * height)
            bounded.getPixels(pixels, 0, width, 0, 0, width, height)
            val context = coroutineContext
            val soft = SoftArtwork.softenArgb(pixels, width, height) { context.ensureActive() }
            Bitmap.createBitmap(soft, width, height, Bitmap.Config.ARGB_8888)
        } finally {
            // The input/cache belongs to Coil. Never recycle or change it (including aliasing).
            if (bounded !== input) bounded.recycle()
        }
    }

    private companion object {
        // One bounded CPU job at a time, even while old/new requests crossfade.
        val processing = Dispatchers.Default.limitedParallelism(1)
    }
}
