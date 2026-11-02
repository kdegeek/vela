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
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import coil3.request.allowHardware
import coil3.request.transformations
import android.content.Context
import kotlinx.coroutines.Dispatchers
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

/** A small independent LRU shares the installed loader's fetchers/disk cache, not foreground pixels. */
private object AmbientArtworkLoader {
    @Volatile private var loader: ImageLoader? = null
    fun get(context: Context): ImageLoader = loader ?: synchronized(this) {
        loader ?: SingletonImageLoader.get(context.applicationContext).newBuilder()
            .memoryCache(MemoryCache.Builder().maxSizeBytes(SoftArtwork.CACHE_BYTES)
                .weakReferencesEnabled(false).build())
            .build().also { loader = it }
    }
}

@Composable fun VelaBackdrop(model: Any?, modifier: Modifier = Modifier) {
    val c = Vela.colors
    // No loader/request/animation exists in this branch; switching HC cancels old composition work.
    if (Vela.highContrast || c.highContrast) {
        Box(modifier.background(c.canvas))
        return
    }
    var settled by remember { mutableStateOf<Any?>(null) }
    LaunchedEffect(model) {
        if (model != null) delay(VelaMotion.BackdropSettleMs)
        settled = model
    }
    val reduced = Vela.reducedMotion
    val context = LocalPlatformContext.current
    val loader = remember(context) { AmbientArtworkLoader.get(context) }
    val request = remember(settled, context) {
        settled?.let {
            ImageRequest.Builder(context).data(it)
                .size(SoftArtwork.MAX_WIDTH, SoftArtwork.MAX_HEIGHT)
                .scale(Scale.FIT).precision(Precision.EXACT).allowHardware(false)
                .decoderCoroutineContext(Dispatchers.Default)
                .transformations(SoftArtworkTransformation()).build()
        }
    }
    val day = c.canvas.red > 0.5f
    Box(modifier.clip(androidx.compose.ui.graphics.RectangleShape).background(c.canvas)) {
        if (reduced) {
            request?.let { AsyncImage(it, null, modifier = Modifier.matchParentSize(), imageLoader = loader, contentScale = ContentScale.Crop) }
        } else Crossfade(request, animationSpec = tween(VelaMotion.BackdropFadeMs, easing = LinearEasing),
            modifier = Modifier.matchParentSize(), label = "backdrop") { image ->
            image?.let { AsyncImage(it, null, modifier = Modifier.fillMaxSize(), imageLoader = loader, contentScale = ContentScale.Crop) }
        }
        // Theme-specific left/foot reading zones; art remains most visible at upper trailing edge.
        Box(Modifier.matchParentSize().background(Brush.horizontalGradient(
            0f to c.canvas,
            0.22f to c.canvas.copy(alpha = if (day) 0.93f else 0.95f),
            0.52f to c.canvas.copy(alpha = if (day) 0.40f else 0.76f),
            1f to c.canvas.copy(alpha = if (day) 0.05f else 0.28f))))
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(
            0f to c.canvas.copy(alpha = 0.05f),
            0.48f to c.canvas.copy(alpha = 0.20f),
            0.78f to c.canvas.copy(alpha = 0.95f), 1f to c.canvas)))
    }
}
