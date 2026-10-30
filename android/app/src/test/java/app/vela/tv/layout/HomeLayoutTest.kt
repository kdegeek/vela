package app.vela.tv.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeLayoutTest {
    @Test
    fun `round trips with type discriminators`() {
        val layout = HomeLayout(
            rows = listOf(
                RowSpec("apps", "Apps", Source.Apps(listOf("com.plexapp.android")), TileStyle.APP),
                RowSpec("night", "Tonight", Source.Pinned(listOf(PinnedItem("Something light", TileAction.AskAgent("put on a comedy"))))),
                RowSpec("lights", "Lights", Source.HomeEntities(listOf("light.lamp"))),
            ),
        )
        val text = Layouts.encode(layout)
        assertTrue(text.contains("\"type\":\"ask_agent\""))
        assertEquals(layout, Layouts.decode(text).getOrThrow())
    }

    @Test
    fun `reads what an agent would write`() {
        val text = """{"rows":[{"id":"r","title":"Resume","source":{"type":"jellyfin_resume"}},
            {"id":"s","title":"Ghibli","style":"poster","source":{"type":"jellyfin_search","query":"Ghibli"},"extra":1}]}"""
        val layout = Layouts.decode(text).getOrThrow()
        assertEquals(Source.JellyfinResume, layout.rows[0].source)
        assertEquals(TileStyle.POSTER, layout.rows[1].style)
    }

    @Test
    fun `rejects duplicate ids, empty layouts and unknown sources`() {
        val dup = """{"rows":[{"id":"a","title":"A","source":{"type":"jellyfin_resume"}},{"id":"a","title":"B","source":{"type":"apps"}}]}"""
        assertTrue(Layouts.decode(dup).exceptionOrNull()!!.message!!.contains("repeated: a"))
        assertTrue(Layouts.decode("""{"rows":[]}""").isFailure)
        assertTrue(Layouts.decode("""{"rows":[{"id":"a","title":"A","source":{"type":"netflix"}}]}""").isFailure)
    }

    @Test
    fun `the default layout is valid`() {
        assertEquals(Layouts.default, Layouts.decode(Layouts.encode(Layouts.default)).getOrThrow())
    }
}
