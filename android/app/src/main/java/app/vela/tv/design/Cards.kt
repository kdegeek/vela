package app.vela.tv.design

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

enum class CardFormat(val width: Dp, val height: Dp, val artScale: ContentScale) {
    Wide(224.dp, 126.dp, ContentScale.Crop), Poster(112.dp, 168.dp, ContentScale.Crop), App(160.dp, 90.dp, ContentScale.Fit);
    val rowHeight: Dp get() = 24.dp + VelaSpace.HeaderToArt + height + VelaSpace.ArtToLabel + VelaSize.CardLabel + 12.dp
}

@Composable private fun CardLabel(title: String, focused: Boolean, enabled: Boolean) {
    Column(Modifier.padding(top = VelaSpace.ArtToLabel).clearAndSetSemantics {}) {
        Text(title, color = if (!enabled) Vela.colors.inkSubtle else if (focused) Vela.colors.ink else Vela.colors.inkMuted, style = Vela.type.meta,
            minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.heightIn(min = VelaSize.CardLabel))
    }
}

@Composable fun MediaCardVisual(title: String, format: CardFormat, state: VelaInteractionState,
    modifier: Modifier = Modifier, image: Any? = null, progress: Float? = null, glyph: VelaGlyph? = null,
    kicker: String? = null, agentic: Boolean = false) {
    val scale = when {
        Vela.reducedMotion -> 1f
        state.pressed && state.enabled -> VelaMotion.PressScale
        state.focused -> VelaMotion.FocusScale
        else -> 1f
    }
    Column(modifier.width(format.width)) {
        VelaArtwork(image, Modifier.fillMaxWidth().height(format.height)
            .graphicsLayer { scaleX = scale; scaleY = scale; clip = false }
            .velaFocusRing(state.focused, VelaShapes.Card, Vela.colors.focusRing,
                if (Vela.highContrast) VelaSize.HighContrastRing else VelaSize.CardRing, VelaSize.CardRingOffset),
            listOfNotNull(kicker, title).joinToString("\n"), glyph, format.artScale, agentic = agentic, progress = progress)
        CardLabel(title, state.focused, state.enabled)
    }
}

@Composable fun MediaCard(
    title: String,
    format: CardFormat,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    image: Any? = null,
    progress: Float? = null,
    glyph: VelaGlyph? = null,
    kicker: String? = null,
    agentic: Boolean = false,
    enabled: Boolean = true,
    onClickLabel: String = "Open $title",
    onFocused: () -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    val watched = displayProgress(progress)
    Column(modifier.width(format.width)) {
        VelaFocusSurface(onClick, Modifier.fillMaxWidth().height(format.height).semantics {
            contentDescription = listOfNotNull(title, subtitle?.takeIf { it.isNotBlank() }).joinToString(". ")
            if (watched != null) stateDescription = "${(watched * 100).roundToInt()} percent watched"
        }, enabled = enabled, treatment = FocusTreatment.Card, shape = VelaShapes.Card, onClickLabel = onClickLabel,
            onFocusChanged = { value -> if (focused != value) { focused = value; if (value) onFocused() } }) {
            VelaArtwork(image, Modifier.fillMaxSize(), listOfNotNull(kicker, title).joinToString("\n"), glyph, format.artScale, agentic = agentic, progress = watched)
        }
        CardLabel(title, focused, enabled)
    }
}
