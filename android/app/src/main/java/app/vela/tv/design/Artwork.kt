package app.vela.tv.design

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.size.Scale
import coil3.size.Precision
import coil3.compose.LocalPlatformContext
import kotlinx.coroutines.delay

@Composable fun ArtworkFrame(
    modifier: Modifier = Modifier,
    shape: Shape = VelaShapes.Card,
    showFallback: Boolean = true,
    fallbackTitle: String? = null,
    glyph: VelaGlyph? = null,
    agentic: Boolean = false,
    progress: Float? = null,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val c = Vela.colors
    val watched = displayProgress(progress)
    Box(modifier.clip(shape).background(if (agentic) c.agentContainer else c.surfaceRaised)
        .then(if (c.highContrast) Modifier.border(1.dp, c.lineStrong, shape) else Modifier)) {
        if (showFallback) Column(Modifier.align(Alignment.Center).padding(VelaSpace.S).clearAndSetSemantics {},
            verticalArrangement = Arrangement.spacedBy(VelaSpace.Xs), horizontalAlignment = Alignment.CenterHorizontally) {
            if (glyph != null) Glyph(glyph, null, tint = if (agentic) c.agent else c.inkMuted, size = VelaSize.GlyphStatus)
            if (fallbackTitle != null) Text(fallbackTitle, color = c.inkMuted, style = Vela.type.meta,
                textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        content()
        if (watched != null) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(VelaSize.Progress)
            .background(if (c.highContrast) c.canvas else c.canvas.copy(alpha = 0.6f))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(watched).background(c.accent))
        }
    }
}

@Composable fun VelaArtwork(model: Any?, modifier: Modifier = Modifier, fallbackTitle: String? = null,
    glyph: VelaGlyph? = null, contentScale: ContentScale = ContentScale.Crop, shape: Shape = VelaShapes.Card,
    agentic: Boolean = false, progress: Float? = null) {
    // key disposes the previous request's callbacks, so a late old success cannot remove a new fallback.
    key(model) {
        var succeeded by remember { mutableStateOf(false) }
        ArtworkFrame(modifier, shape, !succeeded, fallbackTitle, glyph, agentic, progress) {
            if (model != null) AsyncImage(model, contentDescription = null, modifier = Modifier.matchParentSize(),
                contentScale = contentScale, onLoading = { succeeded = false },
                onSuccess = { succeeded = true }, onError = { succeeded = false })
        }
    }
}

@Composable fun VelaBackdrop(model: Any?, modifier: Modifier = Modifier) {
    // Return before creating request/animation state: HC must not load hidden ambient artwork.
    if (Vela.highContrast) return
    var settled by remember { mutableStateOf<Any?>(null) }
    LaunchedEffect(model) { delay(VelaMotion.BackdropSettleMs); settled = model }
    val c = Vela.colors
    val reduced = Vela.reducedMotion
    val context = LocalPlatformContext.current
    // Ambient art is intentionally bounded to one 1080p decode per transition side, even on 4K
    // output. Foreground cards use AsyncImage's measured constraints instead of source dimensions.
    val request = remember(settled, context) {
        settled?.let { ImageRequest.Builder(context).data(it).size(1920, 1080).scale(Scale.FIT).precision(Precision.EXACT).build() }
    }
    Box(modifier.clip(androidx.compose.ui.graphics.RectangleShape)) {
        if (reduced) {
            request?.let { AsyncImage(it, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop) }
        } else Crossfade(request, animationSpec = tween(VelaMotion.BackdropFadeMs, easing = LinearEasing),
            modifier = Modifier.matchParentSize(), label = "backdrop") { image ->
            image?.let { AsyncImage(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        }
        Box(Modifier.matchParentSize().background(Brush.horizontalGradient(
            0f to c.canvas, 0.45f to c.canvas.copy(alpha = 0.92f), 1f to c.canvas.copy(alpha = 0.35f))))
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(
            0.55f to c.canvas.copy(alpha = 0f), 1f to c.canvas)))
    }
}
