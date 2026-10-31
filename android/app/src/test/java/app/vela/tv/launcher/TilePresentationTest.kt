package app.vela.tv.launcher

import app.vela.tv.design.VelaGlyph
import app.vela.tv.layout.*
import org.junit.Assert.*
import org.junit.Test

class TilePresentationTest {
    @Test fun libraryAndPlexDoNotPromiseImmediatePlayback() {
        val details = tileAffordance(TileAction.PlayJellyfin("movie-1"))
        val search = tileAffordance(TileAction.SearchApp("com.plexapp.android", "Arrival"))
        assertEquals("Open details", details.verb)
        assertEquals("Search in app", search.verb)
        assertEquals(VelaGlyph.Search, search.glyph)
        assertFalse(search.agentic)
        listOf(Source.PlexLibrary("1"), Source.PlexRecent("1"), Source.PlexSearch("1", "Arrival"), Source.PlexResume, Source.PlexOnDeck)
            .forEach { assertEquals("Plex · search in app", sourceLabel(it)) }
    }
    @Test fun onlyAskTilesUseAgentTreatment() {
        val ask = tileAffordance(TileAction.AskAgent("Set the mood"))
        assertTrue(ask.agentic); assertEquals("Ask Vela", ask.verb); assertEquals(VelaGlyph.Ask, ask.glyph)
        listOf(TileAction.LaunchApp("app.tv"), TileAction.OpenUri("https://example.com"), TileAction.PlayJellyfin("1"),
            TileAction.SearchApp("app.tv", "query"), TileAction.HomeService("light", "toggle", "light.room"))
            .forEach { assertFalse(tileAffordance(it).agentic) }
    }
    @Test fun homeServiceDescribesTheActualService() {
        assertEquals("Toggle", tileAffordance(TileAction.HomeService("light", "toggle", "light.room")).verb)
        val scene = tileAffordance(TileAction.HomeService("scene", "turn_on", "scene.evening"))
        assertEquals("Turn_on", scene.verb); assertEquals(VelaGlyph.House, scene.glyph)
        assertEquals("Open", tileAffordance(TileAction.OpenUri("app://item")).verb)
        assertEquals(VelaGlyph.Link, tileAffordance(TileAction.OpenUri("app://item")).glyph)
    }
    @Test fun sourcesHaveUserFacingLabelsWithoutPrivateQueriesOrEntityIds() {
        assertNull(sourceLabel(Source.Apps(listOf("private.package"))))
        assertNull(sourceLabel(Source.Watchlist))
        listOf(Source.JellyfinResume, Source.JellyfinLatest("private library"), Source.JellyfinSearch("private query"))
            .forEach { assertEquals("Jellyfin", sourceLabel(it)) }
        assertEquals("Home Assistant", sourceLabel(Source.HomeEntities(listOf("lock.private"))))
        assertEquals("Pinned", sourceLabel(Source.Pinned(emptyList())))
    }
}
