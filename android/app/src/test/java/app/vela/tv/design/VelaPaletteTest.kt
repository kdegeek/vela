package app.vela.tv.design

import org.junit.Assert.*
import org.junit.Test

class VelaPaletteTest {
    private val palettes = listOf(VelaPalettes.Night, VelaPalettes.Day, VelaPalettes.NightHighContrast, VelaPalettes.DayHighContrast)

    @Test fun `WCAG reference pairs and alpha independent calculation`() {
        assertEquals(21.0, contrastRatio(0xFF000000, 0xFFFFFFFF), 0.000001)
        assertEquals(1.0, contrastRatio(0xFF74511C, 0x0074511C), 0.000001)
        assertEquals(4.47809, contrastRatio(0xFF777777, 0xFFFFFFFF), 0.00001)
        assertEquals(contrastRatio(0xFF123456, 0xFFABCDEF), contrastRatio(0xFFABCDEF, 0xFF123456), 0.000001)
    }

    @Test fun `theme selection uses exact day and safely defaults unknown settings to night`() {
        assertSame(VelaPalettes.Day, VelaPalettes.select("day", false))
        assertSame(VelaPalettes.DayHighContrast, VelaPalettes.select("day", true))
        for (value in listOf("night", "", "system", "DAY", "unknown")) {
            assertSame(VelaPalettes.Night, VelaPalettes.select(value, false))
            assertSame(VelaPalettes.NightHighContrast, VelaPalettes.select(value, true))
        }
        assertSame(VelaPalettes.Night, VelaPalettes.overlay(false))
        assertSame(VelaPalettes.NightHighContrast, VelaPalettes.overlay(true))
    }

    @Test fun `all rendered paragraph and label pairs meet standard or high contrast text floor`() {
        for (p in palettes) {
            val floor = if (p.highContrast) 7.0 else 4.5
            val surfaces = listOf(p.canvas, p.surface, p.surfaceRaised, p.surfaceFocused, p.agentContainer)
            for (bg in surfaces) {
                checkPair("primary text", p.ink, bg, floor)
                checkPair("supporting text", p.inkMuted, bg, floor)
            }
            // Subtle text is deliberately never used on a focused row fill.
            for (bg in listOf(p.canvas, p.surface, p.surfaceRaised)) checkPair("metadata or disabled", p.inkSubtle, bg, floor)
            for ((name, ink, fill) in listOf(
                Triple("accent", p.onAccent, p.accent), Triple("pressed accent", p.onAccent, p.accentStrong),
                Triple("agent", p.onAgent, p.agent), Triple("agent resting", p.agent, p.agentContainer),
                Triple("danger", p.onDanger, p.danger), Triple("danger resting", p.danger, p.surfaceRaised),
            )) checkPair(name, ink, fill, floor)
            for (status in listOf(p.agent, p.success, p.caution, p.danger)) {
                for (bg in listOf(p.canvas, p.surface, p.surfaceRaised)) checkPair("status", status, bg, floor)
            }
        }
    }

    @Test fun `essential boundaries focus and accent have nontext contrast`() {
        for (p in palettes) {
            for (bg in listOf(p.canvas, p.surface, p.surfaceRaised, p.surfaceFocused)) {
                checkPair("essential boundary", p.lineStrong, bg, 3.0)
                checkPair("focus ring", p.focusRing, bg, 3.0)
            }
            checkPair("focus fill change", p.accent, p.surfaceRaised, 3.0)
        }
    }

    @Test fun `every palette role is opaque including high contrast disabled labels`() {
        for (p in palettes) {
            val roles = listOf(p.canvas, p.surface, p.surfaceRaised, p.surfaceFocused, p.ink, p.inkMuted, p.inkSubtle,
                p.line, p.lineStrong, p.accent, p.onAccent, p.accentStrong, p.focusRing, p.agent, p.onAgent,
                p.agentContainer, p.caution, p.danger, p.onDanger, p.success)
            roles.forEach { assertEquals(255L, (it ushr 24) and 255) }
        }
        assertFalse(VelaPalettes.Day.highContrast)
        assertFalse(VelaPalettes.Night.highContrast)
        assertTrue(VelaPalettes.DayHighContrast.highContrast)
        assertTrue(VelaPalettes.NightHighContrast.highContrast)
    }

    @Test fun `scanner palette has strong contrast independent of appearance`() {
        checkPair("QR", VelaPalettes.QR_DARK, VelaPalettes.QR_LIGHT, 15.0)
    }

    private fun checkPair(label: String, foreground: Long, background: Long, floor: Double) {
        val ratio = contrastRatio(foreground, background)
        assertTrue("$label #${foreground.toString(16)} on #${background.toString(16)} = $ratio, required $floor", ratio >= floor)
    }
}
