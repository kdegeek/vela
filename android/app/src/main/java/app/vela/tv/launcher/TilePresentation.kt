package app.vela.tv.launcher

import app.vela.tv.design.VelaGlyph
import app.vela.tv.layout.*

internal data class TileAffordance(val verb: String, val glyph: VelaGlyph?, val agentic: Boolean)
internal fun tileAffordance(action: TileAction): TileAffordance = when (action) {
    is TileAction.LaunchApp -> TileAffordance("Open", null, false)
    is TileAction.SearchApp -> TileAffordance("Search in app", VelaGlyph.Search, false)
    is TileAction.OpenUri -> TileAffordance("Open", VelaGlyph.Link, false)
    is TileAction.PlayJellyfin -> TileAffordance("Open details", null, false)
    is TileAction.AskAgent -> TileAffordance("Ask Vela", VelaGlyph.Ask, true)
    is TileAction.HomeService -> TileAffordance(action.service.replaceFirstChar { it.uppercase() }, VelaGlyph.House, false)
}
internal fun sourceLabel(source: Source): String? = when (source) {
    is Source.Apps, Source.Watchlist -> null
    Source.JellyfinResume, is Source.JellyfinLatest, is Source.JellyfinSearch -> "Jellyfin"
    is Source.PlexLibrary, is Source.PlexRecent, is Source.PlexSearch, Source.PlexResume, Source.PlexOnDeck -> "Plex · search in app"
    is Source.HomeEntities -> "Home Assistant"
    is Source.Pinned -> "Pinned"
}
