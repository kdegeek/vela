package app.vela.tv.design

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Dp

@Immutable data class VelaInteractionState(val focused: Boolean, val pressed: Boolean, val selected: Boolean, val enabled: Boolean)
enum class FocusTreatment { Control, Card, Row }

@Composable fun VelaFocusSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean? = null,
    treatment: FocusTreatment = FocusTreatment.Control,
    shape: Shape = VelaShapes.Control,
    role: Role = Role.Button,
    onClickLabel: String? = null,
    onFocusChanged: (Boolean) -> Unit = {},
    content: @Composable BoxScope.(VelaInteractionState) -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val activation = remember { ActivationGate() }
    activation.availability(enabled)
    val focused by source.collectIsFocusedAsState()
    val rawPressed by source.collectIsPressedAsState()
    val pressed = enabled && rawPressed
    val state = VelaInteractionState(focused, pressed, selected == true, enabled)
    val target = when {
        Vela.reducedMotion || treatment != FocusTreatment.Card -> 1f
        pressed -> VelaMotion.PressScale
        focused -> VelaMotion.FocusScale
        else -> 1f
    }
    val animatedScale by animateFloatAsState(target, velaTween(if (pressed) VelaMotion.PressMs else VelaMotion.FocusMs), label = "card focus")
    val scale = if (Vela.reducedMotion) 1f else animatedScale
    val ringWidth = if (Vela.highContrast) VelaSize.HighContrastRing else
        if (treatment == FocusTreatment.Control) VelaSize.ControlRing else VelaSize.CardRing
    val ringOffset = if (treatment == FocusTreatment.Card) VelaSize.CardRingOffset else VelaSize.ControlRingOffset
    Box(modifier.graphicsLayer { scaleX = scale; scaleY = scale; clip = false }
        .velaFocusRing(focused, shape, Vela.colors.focusRing, ringWidth, ringOffset)
        .onFocusChanged { onFocusChanged(it.isFocused) }
        .guardActivation(activation)
        .semantics {
            if (!enabled) disabled()
            if (selected != null) this.selected = selected
            // Accessibility activation is a fresh action rather than a physical held press.
            onClick(onClickLabel) {
                if (enabled) { onClick(); true } else false
            }
        }
        // One clickable owns the only focus target. Keep it enabled so temporary unavailability
        // never ejects focus. Authorize only a complete, continuously available physical gesture.
        .clickable(interactionSource = source, indication = null, enabled = true, role = role,
            onClickLabel = onClickLabel, onClick = { if (enabled && activation.takeClick()) onClick() }), propagateMinConstraints = true) {
        content(state)
    }
}

/** The stroke's inner edge starts [offset] beyond the original outline. No clipping layer. */
fun Modifier.velaFocusRing(visible: Boolean, shape: Shape, color: Color, width: Dp, offset: Dp): Modifier =
    if (!visible) this else drawWithCache {
        val stroke = width.toPx()
        val outset = offset.toPx() + stroke / 2f
        val outline = shape.createOutline(size, layoutDirection, this)
        val expanded = when (outline) {
            is Outline.Rectangle -> Outline.Rectangle(androidx.compose.ui.geometry.Rect(
                -outset, -outset, size.width + outset, size.height + outset))
            is Outline.Rounded -> {
                val r = outline.roundRect
                fun CornerRadius.outset() = CornerRadius(x + outset, y + outset)
                Outline.Rounded(androidx.compose.ui.geometry.RoundRect(
                    r.left - outset, r.top - outset, r.right + outset, r.bottom + outset,
                    r.topLeftCornerRadius.outset(), r.topRightCornerRadius.outset(),
                    r.bottomRightCornerRadius.outset(), r.bottomLeftCornerRadius.outset()))
            }
            is Outline.Generic -> shape.createOutline(Size(size.width + 2 * outset, size.height + 2 * outset), layoutDirection, this)
        }
        onDrawWithContent {
            drawContent()
            if (outline is Outline.Generic) {
                translate(-outset, -outset) { drawOutline(expanded, color, style = Stroke(stroke)) }
            } else drawOutline(expanded, color, style = Stroke(stroke))
        }
    }
