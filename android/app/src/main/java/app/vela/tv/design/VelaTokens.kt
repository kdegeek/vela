package app.vela.tv.design

import androidx.compose.animation.core.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Immutable class VelaColors(
    val canvas: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val surfaceFocused: Color,
    val ink: Color,
    val inkMuted: Color,
    val inkSubtle: Color,
    val line: Color,
    val lineStrong: Color,
    val accent: Color,
    val onAccent: Color,
    val accentStrong: Color,
    val focusRing: Color,
    val agent: Color,
    val onAgent: Color,
    val agentContainer: Color,
    val caution: Color,
    val danger: Color,
    val onDanger: Color,
    val success: Color,
    val highContrast: Boolean,
)
fun VelaPaletteValues.toColors() = VelaColors(
    canvas = Color(canvas),
    surface = Color(surface),
    surfaceRaised = Color(surfaceRaised),
    surfaceFocused = Color(surfaceFocused),
    ink = Color(ink),
    inkMuted = Color(inkMuted),
    inkSubtle = Color(inkSubtle),
    line = Color(line),
    lineStrong = Color(lineStrong),
    accent = Color(accent),
    onAccent = Color(onAccent),
    accentStrong = Color(accentStrong),
    focusRing = Color(focusRing),
    agent = Color(agent),
    onAgent = Color(onAgent),
    agentContainer = Color(agentContainer),
    caution = Color(caution),
    danger = Color(danger),
    onDanger = Color(onDanger),
    success = Color(success),
    highContrast = highContrast,
)
val LocalVelaColors = staticCompositionLocalOf { VelaPalettes.Night.toColors() }

object VelaSpace {
    val Xxs = 4.dp; val Xs = 8.dp; val S = 12.dp; val M = 16.dp; val L = 24.dp; val Xl = 32.dp; val Xxl = 48.dp
    val SafeHorizontal = 48.dp; val SafeVertical = 27.dp
    val CardGap = 16.dp; val RowGap = 24.dp; val HeaderToArt = 12.dp; val ArtToLabel = 12.dp
}
object VelaSize {
    val MinTarget = 44.dp; val ListRow = 56.dp; val TopBar = 52.dp
    val Hero = 96.dp; val HeroCompact = 52.dp; val NowPlayingBar = 64.dp; val NowPlayingArt = 48.dp
    val CardLabel = 40.dp; val Progress = 4.dp
    val DialogWidth = 640.dp; val DialogHeight = 460.dp; val SheetWidth = 720.dp; val SheetHeight = 480.dp
    val ReadingWidth = 560.dp
    val Glyph = 20.dp; val GlyphList = 24.dp; val GlyphStatus = 32.dp
    val CardRing = 3.dp; val CardRingOffset = 3.dp; val ControlRing = 2.dp; val ControlRingOffset = 2.dp
    val HighContrastRing = 4.dp
}
object VelaShapes {
    val Card = RoundedCornerShape(12.dp); val Panel = RoundedCornerShape(20.dp)
    val Control = RoundedCornerShape(50); val Field = RoundedCornerShape(10.dp); val Thumb = RoundedCornerShape(8.dp)
}
object VelaMotion {
    const val FocusMs = 150; const val PressMs = 100; const val BackdropSettleMs = 300L; const val BackdropFadeMs = 400
    const val FocusScale = 1.05f; const val PressScale = 1.02f
}
@Immutable class VelaTypography(val display: TextStyle, val headline: TextStyle, val title: TextStyle,
    val section: TextStyle, val body: TextStyle, val meta: TextStyle, val label: TextStyle,
    val caption: TextStyle, val numeric: TextStyle)
private fun textStyle(size: Int, line: Int, weight: FontWeight = FontWeight.Normal, serif: Boolean = false) = TextStyle(
    fontFamily = if (serif) FontFamily.Serif else FontFamily.SansSerif,
    fontSize = size.sp, lineHeight = line.sp, fontWeight = weight,
)
val VelaType = VelaTypography(
    display = textStyle(34, 40, serif = true), headline = textStyle(28, 34, serif = true),
    title = textStyle(22, 28, FontWeight.SemiBold), section = textStyle(18, 24, FontWeight.SemiBold),
    body = textStyle(16, 24), meta = textStyle(14, 20), label = textStyle(15, 20, FontWeight.SemiBold),
    caption = textStyle(12, 16, FontWeight.Medium), numeric = textStyle(14, 20).copy(fontFeatureSettings = "tnum"),
)
object Vela {
    val colors: VelaColors @Composable @ReadOnlyComposable get() = LocalVelaColors.current
    val type: VelaTypography get() = VelaType
    val reducedMotion: Boolean @Composable @ReadOnlyComposable get() = LocalReducedMotion.current
    val highContrast: Boolean @Composable @ReadOnlyComposable get() = LocalHighContrast.current
}
@Composable fun <T> velaTween(durationMs: Int): FiniteAnimationSpec<T> =
    if (Vela.reducedMotion) snap() else tween(durationMs, easing = FastOutSlowInEasing)

internal fun VelaColors.tone(tone: StatusTone): Color = when (tone) {
    StatusTone.Neutral -> inkMuted
    StatusTone.Agent -> agent
    StatusTone.Success -> success
    StatusTone.Caution -> caution
    StatusTone.Danger -> danger
}
