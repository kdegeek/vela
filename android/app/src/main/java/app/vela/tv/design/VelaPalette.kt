package app.vela.tv.design

import kotlin.math.pow

/** Opaque sRGB roles, shared with the native accessibility overlay. */
data class VelaPaletteValues(
    val canvas: Long,
    val surface: Long,
    val surfaceRaised: Long,
    val surfaceFocused: Long,
    val ink: Long,
    val inkMuted: Long,
    val inkSubtle: Long,
    val line: Long,
    val lineStrong: Long,
    val accent: Long,
    val onAccent: Long,
    val accentStrong: Long,
    val focusRing: Long,
    val agent: Long,
    val onAgent: Long,
    val agentContainer: Long,
    val caution: Long,
    val danger: Long,
    val onDanger: Long,
    val success: Long,
    val highContrast: Boolean,
)

object VelaPalettes {
    val Night = VelaPaletteValues(
        canvas = 0xFF0E1024,
        surface = 0xFF1B2036,
        surfaceRaised = 0xFF262C47,
        surfaceFocused = 0xFF343C60,
        ink = 0xFFF6F1E7,
        inkMuted = 0xFFC3C5D3,
        inkSubtle = 0xFF9A9EB4,
        line = 0xFF363C58,
        lineStrong = 0xFF8D91A5,
        accent = 0xFFE5CA91,
        onAccent = 0xFF231C0C,
        accentStrong = 0xFFF3DFB2,
        focusRing = 0xFFF6F1E7,
        agent = 0xFFAEB9FF,
        onAgent = 0xFF11163C,
        agentContainer = 0xFF262E63,
        caution = 0xFFFFB870,
        danger = 0xFFFFB4AB,
        onDanger = 0xFF690005,
        success = 0xFF9FD8A8,
        highContrast = false,
    )
    val Day = VelaPaletteValues(
        canvas = 0xFFF6F1E7,
        surface = 0xFFFFFBF3,
        surfaceRaised = 0xFFEAE4D8,
        surfaceFocused = 0xFFDCD4C4,
        ink = 0xFF171B2C,
        inkMuted = 0xFF434758,
        inkSubtle = 0xFF5C6073,
        line = 0xFFD8D1C3,
        lineStrong = 0xFF6B6F80,
        accent = 0xFF74511C,
        onAccent = 0xFFFFFFFF,
        accentStrong = 0xFF5A3E12,
        focusRing = 0xFF171B2C,
        agent = 0xFF3A4AB5,
        onAgent = 0xFFFFFFFF,
        agentContainer = 0xFFDEE2FF,
        caution = 0xFF8A4B00,
        danger = 0xFFA82428,
        onDanger = 0xFFFFFFFF,
        success = 0xFF2E6A3A,
        highContrast = false,
    )
    val NightHighContrast = VelaPaletteValues(
        canvas = 0xFF000000,
        surface = 0xFF000000,
        surfaceRaised = 0xFF1C1C1C,
        surfaceFocused = 0xFF2A2A2A,
        ink = 0xFFFFFFFF,
        inkMuted = 0xFFE8E8E8,
        inkSubtle = 0xFFD0D0D0,
        line = 0xFFBDBDBD,
        lineStrong = 0xFFFFFFFF,
        accent = 0xFFFFD966,
        onAccent = 0xFF000000,
        accentStrong = 0xFFFFE699,
        focusRing = 0xFFFFFFFF,
        agent = 0xFFBCC6FF,
        onAgent = 0xFF000000,
        agentContainer = 0xFF1C1C1C,
        caution = 0xFFFFC285,
        danger = 0xFFFF9C94,
        onDanger = 0xFF000000,
        success = 0xFF93E6A2,
        highContrast = true,
    )
    val DayHighContrast = VelaPaletteValues(
        canvas = 0xFFFFFFFF,
        surface = 0xFFFFFFFF,
        surfaceRaised = 0xFFEDEDED,
        surfaceFocused = 0xFFDADADA,
        ink = 0xFF000000,
        inkMuted = 0xFF1F1F1F,
        inkSubtle = 0xFF333333,
        line = 0xFF4D4D4D,
        lineStrong = 0xFF000000,
        accent = 0xFF5A3A00,
        onAccent = 0xFFFFFFFF,
        accentStrong = 0xFF3F2800,
        focusRing = 0xFF000000,
        agent = 0xFF1C2B94,
        onAgent = 0xFFFFFFFF,
        agentContainer = 0xFFEDEDED,
        caution = 0xFF6B3600,
        danger = 0xFF8C0009,
        onDanger = 0xFFFFFFFF,
        success = 0xFF1A5126,
        highContrast = true,
    )
    fun select(appearance: String, highContrast: Boolean): VelaPaletteValues = when {
        appearance == "day" -> if (highContrast) DayHighContrast else Day
        else -> overlay(highContrast)
    }
    fun overlay(highContrast: Boolean): VelaPaletteValues = if (highContrast) NightHighContrast else Night
    const val QR_DARK: Long = 0xFF0E1024
    const val QR_LIGHT: Long = 0xFFF6F1E7
}

/** WCAG 2.x contrast of opaque sRGB ARGB colours; alpha is intentionally ignored. */
fun contrastRatio(foreground: Long, background: Long): Double {
    fun luminance(argb: Long): Double {
        fun linear(shift: Int): Double {
            val c = ((argb shr shift) and 255).toDouble() / 255
            return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * linear(16) + 0.7152 * linear(8) + 0.0722 * linear(0)
    }
    val a = luminance(foreground)
    val b = luminance(background)
    return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
}
