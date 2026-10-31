package app.vela.tv.design

import org.junit.Assert.*
import org.junit.Test

class GlassMaterialTest {
    @Test fun `material compositing handles opaque transparent and rounded sRGB references`() {
        assertEquals(0xFF000000, compositeMaterial(0xFF000000, 0xFFFFFFFF, 1f))
        assertEquals(0xFFFFFFFF, compositeMaterial(0xFF000000, 0xFFFFFFFF, 0f))
        assertEquals(0xFF808080, compositeMaterial(0xFF000000, 0xFFFFFFFF, 0.5f))
    }

    @Test fun `reading surfaces preserve text contrast over dark bright and saturated imagery`() {
        for (palette in listOf(VelaPalettes.Night, VelaPalettes.Day)) {
            for (dense in listOf(false, true)) {
                for (r in listOf(0, 64, 128, 192, 255)) for (g in listOf(0, 64, 128, 192, 255)) for (b in listOf(0, 64, 128, 192, 255)) {
                    val image = 0xFF000000L or (r.toLong() shl 16) or (g.toLong() shl 8) or b.toLong()
                    val tint = compositeMaterial(palette.surface, image, glassOpacity(dense))
                    // Include the brightest leading reflection, not just the flat tint.
                    for (background in listOf(tint, compositeMaterial(0xFFFFEED8, tint, GLASS_REFLECTION_ALPHA))) {
                        for (ink in listOf(palette.ink, palette.inkMuted, palette.agent, palette.danger)) {
                            assertTrue("Text on ${if (dense) "dense" else "regular"} material: ${contrastRatio(ink, background)}",
                                contrastRatio(ink, background) >= 4.5)
                        }
                        assertTrue(contrastRatio(palette.focusRing, background) >= 3.0)
                    }
                }
            }
        }
    }
}

/** Opaque sRGB result of a single material layer; also used to check worst-case imagery. */
private fun compositeMaterial(foreground: Long, background: Long, opacity: Float): Long {
    require(opacity.isFinite() && opacity in 0f..1f)
    var result = 0xFF000000L
    for (shift in intArrayOf(16, 8, 0)) {
        val a = (foreground shr shift) and 255
        val b = (background shr shift) and 255
        val channel = (a * opacity + b * (1f - opacity) + 0.5f).toLong().coerceIn(0L, 255L)
        result = result or (channel shl shift)
    }
    return result
}
