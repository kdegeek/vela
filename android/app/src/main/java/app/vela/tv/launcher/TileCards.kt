package app.vela.tv.launcher

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import app.vela.tv.design.*
import app.vela.tv.layout.TileStyle

internal fun TileStyle.cardFormat(): CardFormat = when (this) {
    TileStyle.WIDE -> CardFormat.Wide
    TileStyle.POSTER -> CardFormat.Poster
    TileStyle.APP -> CardFormat.App
}
@Composable internal fun TileCard(tile: Tile, style: TileStyle, onFocused: (Tile) -> Unit,
    onSelect: (Tile) -> Unit, modifier: Modifier = Modifier) {
    val affordance = tileAffordance(tile.action)
    MediaCard(tile.title, style.cardFormat(), { onSelect(tile) }, modifier,
        subtitle = tile.subtitle, image = tile.image, progress = tile.progress,
        glyph = affordance.glyph, kicker = affordance.verb, agentic = affordance.agentic,
        onClickLabel = "${affordance.verb} ${tile.title}", onFocused = { onFocused(tile) })
}
@Composable internal fun TileRow(row: LoadedRow, onFocused: (Tile) -> Unit,
    onSelect: (Tile) -> Unit, modifier: Modifier = Modifier, onRetry: () -> Unit = {}) {
    if (row.error != null) RowStatusStrip(row.spec.title, row.spec.style.cardFormat(), StatusKind.Error,
        row.error, modifier, action = "Retry", onAction = onRetry)
    else CardRow(row.spec.title, row.tiles, Tile::key, modifier, supporting = sourceLabel(row.spec.source)) { tile ->
        TileCard(tile, row.spec.style, onFocused, onSelect, Modifier.focusProperties {
            if (tile.key == row.tiles.firstOrNull()?.key) left = FocusRequester.Cancel
            if (tile.key == row.tiles.lastOrNull()?.key) right = FocusRequester.Cancel
        })
    }
}
