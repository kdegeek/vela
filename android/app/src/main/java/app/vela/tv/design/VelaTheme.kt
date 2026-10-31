package app.vela.tv.design

import androidx.compose.material3.*
import androidx.compose.runtime.*
import app.vela.tv.settings.VelaSettings

val LocalReducedMotion = staticCompositionLocalOf { false }
val LocalHighContrast = staticCompositionLocalOf { false }

private fun VelaColors.scheme(day: Boolean): ColorScheme {
    val base = if (day) lightColorScheme() else darkColorScheme()
    return base.copy(primary = accent, onPrimary = onAccent, primaryContainer = surfaceRaised, onPrimaryContainer = ink,
        background = canvas, onBackground = ink, surface = surface, onSurface = ink,
        surfaceVariant = surfaceRaised, onSurfaceVariant = inkMuted, surfaceTint = surface,
        surfaceBright = surfaceFocused, surfaceDim = canvas, surfaceContainer = surface,
        surfaceContainerHigh = surfaceRaised, surfaceContainerHighest = surfaceFocused,
        surfaceContainerLow = surface, surfaceContainerLowest = canvas,
        outline = lineStrong, outlineVariant = line, error = danger, onError = onDanger,
        errorContainer = surfaceRaised, onErrorContainer = danger,
        secondary = agent, onSecondary = onAgent, secondaryContainer = agentContainer, onSecondaryContainer = ink,
        tertiary = accent, onTertiary = onAccent, tertiaryContainer = surfaceRaised, onTertiaryContainer = ink,
        inverseSurface = ink, inverseOnSurface = canvas, inversePrimary = onAccent)
}
private val MaterialType = Typography(
    displayLarge = VelaType.display, displayMedium = VelaType.display, displaySmall = VelaType.display,
    headlineLarge = VelaType.headline, headlineMedium = VelaType.headline, headlineSmall = VelaType.headline,
    titleLarge = VelaType.title, titleMedium = VelaType.section, titleSmall = VelaType.section,
    bodyLarge = VelaType.body, bodyMedium = VelaType.meta, bodySmall = VelaType.caption,
    labelLarge = VelaType.label, labelMedium = VelaType.label, labelSmall = VelaType.caption,
)

@Composable fun VelaTheme(settings: VelaSettings, content: @Composable () -> Unit) {
    val colors = remember(settings.appearance, settings.highContrast) {
        VelaPalettes.select(settings.appearance, settings.highContrast).toColors()
    }
    val scheme = remember(colors) { colors.scheme(settings.appearance == "day") }
    CompositionLocalProvider(LocalVelaColors provides colors, LocalReducedMotion provides settings.reduceMotion,
        LocalHighContrast provides settings.highContrast, LocalContentColor provides colors.ink) {
        MaterialTheme(colorScheme = scheme, typography = MaterialType,
            shapes = Shapes(small = VelaShapes.Field, medium = VelaShapes.Card, large = VelaShapes.Panel), content = content)
    }
}
