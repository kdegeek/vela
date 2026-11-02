package app.vela.tv.design

import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class SoftArtworkTest {
    @Test fun `large landscape portrait and extreme aspect ratios fit the pixel budget`() {
        for ((w, h) in listOf(3840 to 2160, 4000 to 6000, Int.MAX_VALUE to 1, 1 to Int.MAX_VALUE, 1 to 1, 320 to 180)) {
            val (width, height) = SoftArtwork.boundedSize(w, h)
            assertTrue(width in 1..SoftArtwork.MAX_WIDTH)
            assertTrue(height in 1..SoftArtwork.MAX_HEIGHT)
            assertTrue(width * height <= 129_600)
            if (w <= 480 && h <= 270) assertEquals(w to h, width to height)
            // Rounding to a pixel may change the ratio by at most one output pixel.
            val scale = minOf(1.0, 480.0 / w, 270.0 / h)
            assertTrue(kotlin.math.abs(width - w * scale) <= 1.0)
            assertTrue(kotlin.math.abs(height - h * scale) <= 1.0)
        }
    }

    @Test fun `constant colours and one pixel edges remain constant without modifying foreground pixels`() {
        for (color in listOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF2796D1.toInt())) {
            for ((w, h) in listOf(1 to 1, 1 to 70, 80 to 1, 90 to 80)) {
                val input = IntArray(w * h) { color }
                val original = input.clone()
                assertArrayEquals(input, SoftArtwork.softenArgb(input, w, h) {})
                assertArrayEquals(original, input)
            }
        }
    }

    @Test fun `hard image edges soften into bounded intermediate colours symmetrically`() {
        val width = 120
        val pixels = IntArray(width) { if (it < width / 2) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        val soft = SoftArtwork.softenArgb(pixels, width, 1) {}
        assertEquals(0xFF000000.toInt(), soft.first())
        assertEquals(0xFFFFFFFF.toInt(), soft.last())
        for (i in 1 until width) assertTrue((soft[i] and 255) >= (soft[i - 1] and 255))
        assertTrue((soft[59] and 255) in 1..254)
        assertTrue((soft[60] and 255) in 1..254)
        assertTrue(kotlin.math.abs((soft[59] and 255) + (soft[60] and 255) - 255) <= 3)
        assertTrue(soft.all { it ushr 24 == 255 })
        assertArrayEquals(soft, SoftArtwork.softenArgb(pixels, 1, width) {})
    }

    @Test fun `invisible coloured pixels cannot bleed into opaque neighbours`() {
        val pixels = IntArray(80) { if (it < 40) 0x00FF0000 else 0xFF0000FF.toInt() }
        val soft = SoftArtwork.softenArgb(pixels, 80, 1) {}
        assertTrue(soft.all { (it ushr 16) and 255 == 0 })
        assertTrue(soft.all { (it ushr 8) and 255 == 0 })
        assertTrue(soft.any { it ushr 24 in 1..254 && it and 255 == 255 })
        assertEquals(0, soft.first())
        assertEquals(0xFF0000FF.toInt(), soft.last())
    }

    @Test fun `a canceled image job exits during processing and leaves its source intact`() {
        val pixels = IntArray(480 * 270) { 0xFF2796D1.toInt() }
        val original = pixels.clone()
        val job = Job()
        var checkpoints = 0
        try {
            SoftArtwork.softenArgb(pixels, 480, 270) {
                checkpoints++
                if (checkpoints == 300) job.cancel()
                job.ensureActive()
            }
            fail("Canceled artwork must not be published")
        } catch (_: CancellationException) {
            assertEquals(300, checkpoints)
            assertArrayEquals(original, pixels)
        }
    }

    @Test fun `invalid or unbounded pixel buffers are rejected before processing`() {
        for (invoke in listOf<() -> Unit>(
            { SoftArtwork.boundedSize(0, 2) }, { SoftArtwork.boundedSize(2, -1) },
            { SoftArtwork.softenArgb(IntArray(1), 480, 270) {} },
            { SoftArtwork.softenArgb(IntArray(481), 481, 1) {} },
            { SoftArtwork.softenArgb(IntArray(271), 1, 271) {} },
        )) {
            try { invoke(); fail("Invalid dimensions accepted") } catch (_: IllegalArgumentException) { }
        }
    }
}
