package app.vela.tv.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.semantics.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp

@Composable fun VelaSectionHeader(title: String, modifier: Modifier = Modifier, supporting: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(VelaSpace.M), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Vela.type.section, color = Vela.colors.ink, modifier = Modifier.semantics { heading() })
            if (supporting != null) Text(supporting, style = Vela.type.meta, color = Vela.colors.inkMuted)
        }
        trailing?.invoke(this)
    }
}

@Composable fun <T> CardRow(title: String, items: List<T>, key: (T) -> Any, modifier: Modifier = Modifier,
    supporting: String? = null, state: LazyListState = rememberLazyListState(),
    card: @Composable LazyItemScope.(T) -> Unit) {
    Column(modifier) {
        VelaSectionHeader(title, Modifier.safeHorizontal(), supporting)
        LazyRow(Modifier.fillMaxWidth().focusRestorer(), state,
            contentPadding = PaddingValues(horizontal = VelaSpace.SafeHorizontal, vertical = VelaSpace.HeaderToArt),
            horizontalArrangement = Arrangement.spacedBy(VelaSpace.CardGap)) {
            items(items, key = key, itemContent = card)
        }
    }
}

@Composable fun RowStatusStrip(title: String, format: CardFormat, kind: StatusKind, message: String,
    modifier: Modifier = Modifier, action: String? = null, onAction: () -> Unit = {}) {
    Column(modifier.heightIn(min = rowMinimumHeight(format))) {
        VelaSectionHeader(title, Modifier.safeHorizontal())
        Column(Modifier.safeHorizontal().padding(vertical = VelaSpace.HeaderToArt), verticalArrangement = Arrangement.spacedBy(VelaSpace.S)) {
            InlineStatus(message, tone = kind.tone)
            if (action != null) VelaButton(action, onClick = onAction)
        }
    }
}
@Composable fun SkeletonRow(title: String, format: CardFormat, modifier: Modifier = Modifier, count: Int = 5) {
    Column(modifier.heightIn(min = rowMinimumHeight(format)).semantics { stateDescription = "Loading" }) {
        VelaSectionHeader(title, Modifier.safeHorizontal())
        LazyRow(contentPadding = PaddingValues(horizontal = VelaSpace.SafeHorizontal, vertical = VelaSpace.HeaderToArt),
            horizontalArrangement = Arrangement.spacedBy(VelaSpace.CardGap), userScrollEnabled = false) {
            items(count.coerceIn(0, 20)) {
                Column(Modifier.width(format.width).clearAndSetSemantics {}) {
                    Box(Modifier.fillMaxWidth().height(format.height).background(Vela.colors.surfaceRaised, VelaShapes.Card)
                        .then(if (Vela.highContrast) Modifier.border(1.dp, Vela.colors.lineStrong, VelaShapes.Card) else Modifier))
                    Spacer(Modifier.height(VelaSpace.ArtToLabel))
                    Text(" ", style = Vela.type.meta, minLines = 2, modifier = Modifier.heightIn(min = VelaSize.CardLabel))
                }
            }
        }
    }
}
@Composable fun CardGrid(format: CardFormat, modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(), content: LazyGridScope.() -> Unit) {
    LazyVerticalGrid(GridCells.Adaptive(format.width + VelaSpace.CardGap), modifier.focusRestorer(), state,
        contentPadding = PaddingValues(horizontal = VelaSpace.SafeHorizontal, vertical = VelaSpace.S),
        horizontalArrangement = Arrangement.spacedBy(VelaSpace.CardGap), verticalArrangement = Arrangement.spacedBy(VelaSpace.RowGap),
        content = content)
}
fun LazyGridScope.fullSpanItem(key: Any? = null, content: @Composable LazyGridItemScope.() -> Unit) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }, content = content)
}

/** Base formula plus scaled header/label lines; artwork dimensions never grow with font scale. */
@Composable private fun rowMinimumHeight(format: CardFormat) = with(LocalDensity.current) {
    format.rowHeight + (64.sp.toDp() - 64.dp).coerceAtLeast(0.dp)
}
