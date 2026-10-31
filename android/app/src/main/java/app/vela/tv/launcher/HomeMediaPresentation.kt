package app.vela.tv.launcher

import app.vela.tv.layout.Source
import app.vela.tv.layout.TileAction

/**
 * Media alone owns Home's header, independently of agent setup. Keep the last media selection
 * while focusing apps/actions or the shell so the rows do not jump. Resolve against current rows
 * on refresh: removed titles and old-profile tiles must never survive without a current match.
 * This selects decoration only; every row and its original action still renders through TileRow.
 */
internal fun homeMediaTile(rows: List<LoadedRow>, focused: Tile? = null, previous: Tile? = null): Tile? {
    val media = rows.filter { it.error == null }.flatMap { row ->
        row.tiles.filter { tile -> isHomeMedia(tile, row.spec.source) }
    }
    fun current(tile: Tile?): Tile? = tile?.let { old ->
        // The same title may appear in multiple rows with different artwork/metadata.
        media.firstOrNull { it === old }
            ?: media.firstOrNull { it.key == old.key && it.action == old.action }
    }
    return current(focused) ?: current(previous) ?: media.firstOrNull()
}

private fun isHomeMedia(tile: Tile, source: Source): Boolean = when (tile.action) {
    is TileAction.LaunchApp, is TileAction.HomeService, is TileAction.AskAgent -> false
    is TileAction.PlayJellyfin -> true
    is TileAction.SearchApp, is TileAction.OpenUri -> when (source) {
        is Source.PlexLibrary, is Source.PlexRecent, is Source.PlexSearch,
        Source.PlexResume, Source.PlexOnDeck, Source.Watchlist -> true
        // Pinned links/searches without artwork are action shortcuts, not a featured title.
        else -> tile.image != null || !tile.backdrop.isNullOrBlank()
    }
}
