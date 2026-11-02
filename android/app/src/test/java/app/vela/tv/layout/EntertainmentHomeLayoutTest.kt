package app.vela.tv.layout

import org.junit.Assert.*
import org.junit.Test

class EntertainmentHomeLayoutTest {
    @Test fun newHomeStartsWithContinueWatchingThenAppsRecentAndSavedTitles() {
        assertEquals(listOf("resume", "apps", "latest", "watchlist"), Layouts.default.rows.map { it.id })
        assertEquals(listOf("Continue watching", "Apps", "Recently added", "My list"), Layouts.default.rows.map { it.title })
        assertEquals(listOf(Source.JellyfinResume, Source.Apps(), Source.JellyfinLatest(), Source.Watchlist),
            Layouts.default.rows.map { it.source })
        assertEquals(listOf(TileStyle.WIDE, TileStyle.APP, TileStyle.POSTER, TileStyle.WIDE), Layouts.default.rows.map { it.style })
        assertEquals(Layouts.default, Layouts.decode(Layouts.encode(Layouts.default)).getOrThrow())
    }

    @Test fun savedOwnerLayoutKeepsAskRowsCustomSourcesAndItsOriginalOrder() {
        val saved = HomeLayout(rows = listOf(
            RowSpec("tonight", "Tonight", Source.Pinned(listOf(
                PinnedItem("Something light", TileAction.AskAgent("Find a comedy"))
            ))),
            RowSpec("custom-plex", "Weekend films", Source.PlexSearch("1", "comedy"), TileStyle.POSTER),
            RowSpec("apps", "My apps", Source.Apps(listOf("tv.player")), TileStyle.APP),
            RowSpec("house", "Lights", Source.HomeEntities(listOf("light.room"))),
            RowSpec("resume", "Keep watching", Source.JellyfinResume)
        ))
        val restored = Layouts.decode(Layouts.encode(saved)).getOrThrow()
        assertEquals(saved, restored)
        assertEquals(listOf("tonight", "custom-plex", "apps", "house", "resume"), restored.rows.map { it.id })
    }
}
