package app.vela.tv.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.unit.dp

/** A material only: never adds input, focus, semantics, or application effects. */
@Composable fun VelaGlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = VelaShapes.Panel,
    dense: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier.velaGlassMaterial(Vela.colors, shape, dense), content = content)
}

/** Shared by panels that already own their layout/semantics. Children keep flat fills. */
internal fun Modifier.velaGlassMaterial(
    colors: VelaColors,
    shape: Shape = VelaShapes.Panel,
    dense: Boolean = false,
): Modifier {
    if (colors.highContrast) return background(colors.surface, shape).border(1.dp, colors.lineStrong, shape)
    val opacity = glassOpacity(dense)
    return shadow(14.dp, shape, clip = false, ambientColor = Color.Black.copy(alpha = 0.18f),
        spotColor = Color.Black.copy(alpha = 0.22f))
        .background(colors.surface.copy(alpha = opacity), shape)
        .drawWithCache {
            val outline = shape.createOutline(size, layoutDirection, this)
            val reflection = Brush.linearGradient(
                0f to Color(0xFFFFEED8).copy(alpha = GLASS_REFLECTION_ALPHA),
                0.23f to Color.Transparent,
                0.82f to Color.Transparent,
                1f to colors.agent.copy(alpha = 0.025f),
                start = Offset.Zero, end = Offset(size.width, size.height),
            )
            onDrawBehind { drawOutline(outline, reflection) }
        }
        .border(1.dp, Brush.linearGradient(
            0f to Color(0xFFF4E2C8).copy(alpha = 0.55f),
            0.28f to colors.ink.copy(alpha = 0.19f),
            0.65f to colors.line.copy(alpha = 0.50f),
            1f to colors.ink.copy(alpha = 0.10f),
        ), shape)
}

/** Still, network-free atmosphere for management and homes without artwork. */
@Composable fun VelaAmbientBackground(modifier: Modifier = Modifier) {
    val c = Vela.colors
    Box(modifier.background(c.canvas).then(if (c.highContrast) Modifier else Modifier.drawWithCache {
        val warm = Brush.radialGradient(listOf(c.accent.copy(alpha = 0.10f), Color.Transparent),
            center = Offset(size.width * 0.83f, size.height * 0.16f), radius = size.maxDimension * 0.72f)
        val cool = Brush.radialGradient(listOf(c.agent.copy(alpha = 0.065f), Color.Transparent),
            center = Offset(size.width * 0.16f, size.height * 0.95f), radius = size.maxDimension * 0.64f)
        onDrawBehind { drawRect(warm); drawRect(cool) }
    }))
}
