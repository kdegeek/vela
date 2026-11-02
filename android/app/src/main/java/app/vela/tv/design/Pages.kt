package app.vela.tv.design

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable fun VelaPage(title: String, modifier: Modifier = Modifier, breadcrumb: String? = null,
    supporting: String? = null, state: LazyListState = rememberLazyListState(), inset: Boolean = true,
    actions: (@Composable RowScope.() -> Unit)? = null, content: LazyListScope.() -> Unit) {
    LazyColumn(modifier.focusRestorer(), state = state,
        contentPadding = PaddingValues(horizontal = if (inset) VelaSpace.SafeHorizontal else 0.dp,
            vertical = VelaSpace.S), verticalArrangement = Arrangement.spacedBy(VelaSpace.L)) {
        item(key = "vela-page-header", contentType = "header") {
            Column(if (inset) Modifier else Modifier.safeHorizontal(), verticalArrangement = Arrangement.spacedBy(VelaSpace.Xs)) {
                if (breadcrumb != null) Text(breadcrumb, style = Vela.type.meta, color = Vela.colors.inkMuted)
                Text(title, style = Vela.type.headline, color = Vela.colors.ink, modifier = Modifier.semantics { heading() })
                if (supporting != null) Text(supporting, style = Vela.type.body, color = Vela.colors.inkMuted, modifier = Modifier.readingWidth())
                if (actions != null) FlowRow(horizontalArrangement = Arrangement.spacedBy(VelaSpace.S),
                    verticalArrangement = Arrangement.spacedBy(VelaSpace.S)) { actions() }
            }
        }
        content()
    }
}
fun LazyListScope.insetItem(key: Any? = null, contentType: Any? = null, content: @Composable LazyItemScope.() -> Unit) {
    item(key, contentType) { Box(Modifier.safeHorizontal()) { content() } }
}
fun Modifier.safeHorizontal(): Modifier = padding(horizontal = VelaSpace.SafeHorizontal)
fun Modifier.readingWidth(): Modifier = widthIn(max = VelaSize.ReadingWidth)
