package app.vela.tv

import app.vela.tv.control.ScreenText
import app.vela.tv.control.UiNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenTextTest {
    @Test
    fun `skips silent containers and keeps actionable elements`() {
        val tree = UiNode(
            0, "FrameLayout",
            children = listOf(
                UiNode(1, "LinearLayout", children = listOf(
                    UiNode(2, "Button", text = "Play", viewId = "com.x:id/play", clickable = true, focused = true),
                    UiNode(3, "TextView", text = "Severance  \n S2"),
                )),
            ),
        )
        assertEquals(
            "app: com.x\n[2] Button \"Play\" #play {focused,click}\n[3] TextView \"Severance S2\"",
            ScreenText.format("com.x", tree),
        )
    }

    @Test
    fun `says when an app draws its own screen`() {
        val out = ScreenText.format("com.netflix.ninja", UiNode(0, "SurfaceView"))
        assertTrue(out.contains("draws its own screen"))
    }

    @Test
    fun `caps the number of lines`() {
        val tree = UiNode(0, "List", children = (1..50).map { UiNode(it, "Text", text = "item $it") })
        val out = ScreenText.format("p", tree, maxLines = 10)
        assertEquals(10, out.lines().count { it.startsWith("[") })
        assertTrue(out.endsWith("(more elements not shown)"))
    }
}
