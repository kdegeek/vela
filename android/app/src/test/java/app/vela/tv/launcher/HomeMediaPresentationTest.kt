package app.vela.tv.launcher

import app.vela.tv.layout.*
import org.junit.Assert.*
import org.junit.Test

class HomeMediaPresentationTest {
    private val first = Tile("jf:first", "First film", TileAction.PlayJellyfin("first"),
        subtitle = "2026 · 100 min", image = "https://example.test/first.jpg", kicker = "Continue watching")
    private val second = Tile("jf:second", "Second film", TileAction.PlayJellyfin("second"))
    private val app = Tile("app:tv.player", "Player", TileAction.LaunchApp("tv.player"))
    private val ask = Tile("pin:tonight:0", "Something light", TileAction.AskAgent("Find a comedy"))
    private val home = Tile("ha:light.room", "Living room", TileAction.HomeService("light", "toggle", "light.room"))

    private fun row(id: String, source: Source, vararg tiles: Tile) = LoadedRow(RowSpec(id, id, source), tiles.toList())
    private val rows = listOf(
        row("tonight", Source.Pinned(emptyList()), ask),
        row("resume", Source.JellyfinResume, first, second),
        row("apps", Source.Apps(), app),
        row("house", Source.HomeEntities(listOf("light.room")), home)
    )

    @Test fun realContentIsFeaturedBeforeAnyFocusWithoutAgentSetup() {
        val selected = homeMediaTile(rows)
        assertSame(first, selected)
        assertEquals("2026 · 100 min", selected?.subtitle)
        assertEquals(first.image, selected?.image)
        assertSame(first.action, selected?.action)
    }

    @Test fun appsHomeAndAskNeverBecomeHeroesEvenWithArtwork() {
        listOf(app, ask, home).forEach { action ->
            val decorated = action.copy(image = "https://example.test/art.jpg", backdrop = "https://example.test/backdrop.jpg")
            val actionsOnly = listOf(row("custom", Source.Pinned(emptyList()), decorated))
            assertNull(homeMediaTile(actionsOnly, decorated, decorated))
        }
        assertNull(homeMediaTile(emptyList()))
    }

    @Test fun mediaHeaderStaysPresentAndRetainsSelectionAcrossAppActionAndShellFocus() {
        var selected = homeMediaTile(rows, second)
        assertSame(second, selected)
        listOf(app, ask, home, null, app).forEach { focused ->
            selected = homeMediaTile(rows, focused, selected)
            assertSame(second, selected)
        }
        assertSame(first, homeMediaTile(rows, first, selected))
    }

    @Test fun movingFromInitialAppFocusToMediaKeepsTheSameHeaderPresence() {
        val initial = homeMediaTile(rows, app)
        assertSame(first, initial)
        assertSame(first, homeMediaTile(rows, first, initial))
        assertSame(first, homeMediaTile(rows, app, initial))
    }

    @Test fun refreshingRebindsToCurrentMetadataAndOriginalAction() {
        val updated = second.copy(title = "Updated title", subtitle = "42 min remaining", progress = .6f)
        val refreshed = listOf(row("resume", Source.JellyfinResume, first, updated))
        val selected = homeMediaTile(refreshed, previous = second)
        assertSame(updated, selected)
        assertEquals(.6f, selected?.progress)
        assertSame(updated.action, selected?.action)
    }

    @Test fun removedOrOldProfileMediaCannotLeakThroughRetainedFocus() {
        val remaining = listOf(row("resume", Source.JellyfinResume, first))
        assertSame(first, homeMediaTile(remaining, second, second))
        assertNull(homeMediaTile(listOf(row("apps", Source.Apps(), app)), second, second))
        assertNull(homeMediaTile(emptyList(), second, second))
    }

    @Test fun failedAndEmptyRowsDoNotCreateAHeader() {
        val failed = row("resume", Source.JellyfinResume, first).copy(error = "Library unavailable")
        assertNull(homeMediaTile(listOf(failed), first, first))
        assertSame(second, homeMediaTile(listOf(failed, row("latest", Source.JellyfinLatest(), second)), first, first))
        assertNull(homeMediaTile(listOf(row("resume", Source.JellyfinResume))))
    }

    @Test fun plexCatalogueAndSavedTitlesDoNotNeedArtworkToHaveMetadata() {
        val plex = Tile("plex:server:1", "A film", TileAction.SearchApp("com.plexapp.android", "A film"),
            subtitle = "2025 · Search in Plex")
        listOf(Source.PlexLibrary("1"), Source.PlexRecent("1"), Source.PlexSearch("1", "film"),
            Source.PlexResume, Source.PlexOnDeck, Source.Watchlist).forEach { source ->
            assertSame(plex, homeMediaTile(listOf(row("media", source, plex))))
        }
        val saved = Tile("saved:0", "Saved film", TileAction.OpenUri("https://example.test/film"))
        assertSame(saved, homeMediaTile(listOf(row("saved", Source.Watchlist, saved))))
    }

    @Test fun pinnedSearchAndLinkShortcutsNeedArtworkBeforeFeaturing() {
        listOf(TileAction.SearchApp("tv.player", "comedies"), TileAction.OpenUri("https://example.test")).forEach { action ->
            val shortcut = Tile("pin:custom:0", "Browse", action)
            val source = Source.Pinned(emptyList())
            assertNull(homeMediaTile(listOf(row("custom", source, shortcut)), shortcut))
            val illustrated = shortcut.copy(image = "https://example.test/poster.jpg")
            assertSame(illustrated, homeMediaTile(listOf(row("custom", source, illustrated))))
        }
    }

    @Test fun focusChoosesTheActualCardWhenTheSameTitleAppearsInTwoRows() {
        val saved = first.copy(kicker = "My list", image = "https://example.test/poster.jpg")
        val duplicates = listOf(row("resume", Source.JellyfinResume, first), row("saved", Source.Watchlist, saved))
        assertSame(saved, homeMediaTile(duplicates, saved, first))
        assertSame(first, homeMediaTile(duplicates, first, saved))
    }

    @Test fun reusedPinnedPositionDoesNotRetainAnUnrelatedAction() {
        val old = first.copy(key = "pin:custom:0")
        val replacement = second.copy(key = old.key)
        val refreshed = listOf(row("resume", Source.JellyfinResume, first), row("custom", Source.Pinned(emptyList()), replacement))
        assertSame(first, homeMediaTile(refreshed, previous = old))
    }

    @Test fun selectingDecorationDoesNotFilterOrReorderOwnerRowsOrRewriteActions() {
        val before = rows.map { it.copy(tiles = it.tiles.toList()) }
        homeMediaTile(rows, app, second)
        assertEquals(before, rows)
        assertEquals(listOf("tonight", "resume", "apps", "house"), rows.map { it.spec.id })
        assertSame(ask, rows.first().tiles.single())
        assertSame(ask.action, rows.first().tiles.single().action)
    }
}
