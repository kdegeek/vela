package app.vela.tv.guard

import app.vela.tv.layout.*
import org.junit.Assert.*
import org.junit.Test

class TileGuardsTest {
    @Test fun `generated garage and generic cover actions need approval`() {
        for (id in listOf("cover.garage", "cover.device_123")) {
            val layout = HomeLayout(rows = listOf(RowSpec("house", "House", Source.HomeEntities(listOf(id)))))
            assertEquals(Risk.SECURITY, TileGuards.layout(layout)?.risk)
            assertEquals(Risk.SECURITY, TileGuards.action(TileAction.HomeService("cover", "toggle", id))?.risk)
        }
        assertEquals(Risk.SECURITY, Guard.press("Garage Door", "app.vela.tv")?.risk)
    }
    @Test fun `saved action labels cannot hide lock and purchase effects`() {
        val layout = HomeLayout(rows = listOf(RowSpec("safe", "Safe", Source.Pinned(listOf(
            PinnedItem("Watch a movie", TileAction.HomeService("lock", "unlock", "lock.front")),
        )))))
        assertEquals(Risk.SECURITY, TileGuards.layout(layout)?.risk)
        val purchase = TileAction.OpenUri("https://example.com/checkout")
        assertEquals(Risk.PURCHASE, TileGuards.action(purchase)?.risk)
        assertEquals(Risk.INSTALL, TileGuards.action(TileAction.OpenUri("market://details?id=x"))?.risk)
    }
    @Test fun `resolved link handler participates in actual tile guard`() {
        val action = TileAction.OpenUri("https://ordinary.example/link")
        assertEquals(Risk.INSTALL, TileGuards.action(action) { uri, _ -> Guard.uri(uri, "com.android.vending") }?.risk)
        assertNull(TileGuards.action(TileAction.HomeService("light", "toggle", "light.living_room")))
    }
}
