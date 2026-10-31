package app.vela.tv.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.vela.tv.design.*
import app.vela.tv.layout.TileStyle
import kotlin.math.roundToInt

internal fun TileStyle.cardFormat(): CardFormat = when (this) {
    TileStyle.WIDE -> CardFormat.Wide
    TileStyle.POSTER -> CardFormat.Poster
    TileStyle.APP -> CardFormat.App
}

@Composable internal fun TileCard(tile: Tile, style: TileStyle, onFocused: (Tile) -> Unit,
    onSelect: (Tile) -> Unit, modifier: Modifier = Modifier) {
    val affordance = tileAffordance(tile.action)
    val format = style.cardFormat()
    val watched = displayProgress(tile.progress)
    var focused by remember(tile.key) { mutableStateOf(false) }
    val visible = remember { BringIntoViewRequester() }
    // Reveal the caption too. Android's default focus rectangle covers only the art target.
    LaunchedEffect(focused) { if (focused) visible.bringIntoView() }
    Column(modifier.width(format.width).bringIntoViewRequester(visible)) {
        VelaFocusSurface({ onSelect(tile) }, Modifier.fillMaxWidth().height(format.height).semantics {
            contentDescription = listOfNotNull(tile.title, tile.subtitle?.takeIf { it.isNotBlank() }).joinToString(". ")
            if (watched != null) stateDescription = "${(watched * 100).roundToInt()} percent watched"
        }, treatment = FocusTreatment.Card, shape = VelaShapes.Card,
            onClickLabel = "${affordance.verb} ${tile.title}", onFocusChanged = { value ->
                focused = value
                if (value) onFocused(tile)
            }) {
            VelaArtwork(tile.image, Modifier.fillMaxSize(), tile.title, affordance.glyph, format.artScale,
                agentic = affordance.agentic, progress = watched)
        }
        Column(Modifier.padding(top = VelaSpace.Xs).clearAndSetSemantics {}) {
            Text(tile.title, style = Vela.type.meta, color = if (focused) Vela.colors.ink else Vela.colors.inkMuted,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!tile.subtitle.isNullOrBlank()) Text(tile.subtitle, style = Vela.type.caption,
                color = Vela.colors.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable internal fun TileRow(row: LoadedRow, onFocused: (Tile) -> Unit,
    onSelect: (Tile) -> Unit, modifier: Modifier = Modifier, onRetry: () -> Unit = {}) {
    if (row.error != null) {
        RowStatusStrip(row.spec.title, row.spec.style.cardFormat(), StatusKind.Error,
            row.error, modifier, action = "Retry", onAction = onRetry)
        return
    }
    var focusedKey by remember(row.spec.id) { mutableStateOf<String?>(null) }
    val position = row.tiles.indexOfFirst { it.key == focusedKey }.coerceAtLeast(0) + 1
    Column(modifier) {
        Row(Modifier.fillMaxWidth().safeHorizontal(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(VelaSpace.S)) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(VelaSpace.S)) {
                Text(row.spec.title, Modifier.weight(1f, fill = false).semantics { heading() },
                    style = Vela.type.section, color = Vela.colors.ink)
                sourceLabel(row.spec.source)?.let { source ->
                    Box(Modifier.width(1.dp).height(20.dp).background(Vela.colors.line))
                    Text(source, style = Vela.type.caption, color = Vela.colors.inkMuted)
                }
            }
            if (row.tiles.isNotEmpty()) Box(Modifier.background(Vela.colors.canvas, VelaShapes.Control)
                .padding(horizontal = VelaSpace.Xs, vertical = VelaSpace.Xxs)) {
                Text("$position / ${row.tiles.size}", style = Vela.type.caption, color = Vela.colors.inkMuted)
            }
        }
        LazyRow(Modifier.fillMaxWidth().focusRestorer(),
            contentPadding = PaddingValues(horizontal = VelaSpace.SafeHorizontal, vertical = VelaSpace.S),
            horizontalArrangement = Arrangement.spacedBy(VelaSpace.CardGap)) {
            items(row.tiles, key = Tile::key) { tile ->
                TileCard(tile, row.spec.style, { focusedKey = it.key; onFocused(it) }, onSelect,
                    Modifier.focusProperties {
                        if (tile.key == row.tiles.firstOrNull()?.key) left = FocusRequester.Cancel
                        if (tile.key == row.tiles.lastOrNull()?.key) right = FocusRequester.Cancel
                    })
            }
        }
    }
}
