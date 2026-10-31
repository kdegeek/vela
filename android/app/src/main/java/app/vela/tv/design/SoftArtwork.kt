package app.vela.tv.design

import kotlin.math.roundToInt

/** CPU-only ambient pixels. Foreground images never enter this pipeline. */
internal object SoftArtwork {
    const val MAX_WIDTH = 480
    const val MAX_HEIGHT = 270
    const val CACHE_BYTES = 4L * 1024 * 1024
    private const val RADIUS = 8
    private const val PASSES = 3

    fun boundedSize(width: Int, height: Int): Pair<Int, Int> {
        require(width > 0 && height > 0)
        val scale = minOf(1.0, MAX_WIDTH.toDouble() / width, MAX_HEIGHT.toDouble() / height)
        return (width * scale).roundToInt().coerceIn(1, MAX_WIDTH) to
            (height * scale).roundToInt().coerceIn(1, MAX_HEIGHT)
    }

    /** Three separable box passes, clamped edges and premultiplied alpha, O(pixels).
     * Never changes the caller's pixels. Cancellation is checked at each scanline, including
     * conversion. Two bounded work arrays are reused for all passes, never per frame. */
    fun softenArgb(pixels: IntArray, width: Int, height: Int, checkCancellation: () -> Unit): IntArray {
        require(width in 1..MAX_WIDTH && height in 1..MAX_HEIGHT)
        require(pixels.size == width * height)
        checkCancellation()
        val work = IntArray(pixels.size)
        val scratch = IntArray(pixels.size)
        for (y in 0 until height) {
            checkCancellation()
            for (x in 0 until width) {
                val i = y * width + x
                val p = pixels[i]
                val a = p ushr 24
                fun premultiply(shift: Int) = (((p ushr shift) and 255) * a + 127) / 255
                work[i] = (a shl 24) or (premultiply(16) shl 16) or (premultiply(8) shl 8) or premultiply(0)
            }
        }
        repeat(PASSES) {
            pass(work, scratch, width, height, horizontal = true, checkCancellation)
            pass(scratch, work, width, height, horizontal = false, checkCancellation)
        }
        for (y in 0 until height) {
            checkCancellation()
            for (x in 0 until width) {
                val i = y * width + x
                val p = work[i]
                val a = p ushr 24
                fun unpremultiply(shift: Int) = if (a == 0) 0 else
                    ((((p ushr shift) and 255) * 255 + a / 2) / a).coerceAtMost(255)
                work[i] = (a shl 24) or (unpremultiply(16) shl 16) or (unpremultiply(8) shl 8) or unpremultiply(0)
            }
        }
        checkCancellation()
        return work
    }

    private fun pass(source: IntArray, target: IntArray, width: Int, height: Int,
        horizontal: Boolean, checkCancellation: () -> Unit) {
        val length = if (horizontal) width else height
        val lines = if (horizontal) height else width
        val step = if (horizontal) 1 else width
        val count = RADIUS * 2 + 1
        for (line in 0 until lines) {
            checkCancellation()
            val start = if (horizontal) line * width else line
            var a = 0; var r = 0; var g = 0; var b = 0
            fun add(pixel: Int, sign: Int) {
                a += (pixel ushr 24) * sign
                r += ((pixel ushr 16) and 255) * sign
                g += ((pixel ushr 8) and 255) * sign
                b += (pixel and 255) * sign
            }
            for (i in -RADIUS..RADIUS) add(source[start + i.coerceIn(0, length - 1) * step], 1)
            for (i in 0 until length) {
                target[start + i * step] = (((a + count / 2) / count) shl 24) or
                    (((r + count / 2) / count) shl 16) or (((g + count / 2) / count) shl 8) or ((b + count / 2) / count)
                add(source[start + (i - RADIUS).coerceIn(0, length - 1) * step], -1)
                add(source[start + (i + RADIUS + 1).coerceIn(0, length - 1) * step], 1)
            }
        }
    }
}
