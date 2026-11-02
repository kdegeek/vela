package app.vela.tv.design

import kotlin.math.pow

/** Smoke / pearl sRGB roles (opaque fallbacks), shared with the native accessibility overlay. */
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
        canvas = 0xFF080D14,
        surface = 0xFF131D27,
        surfaceRaised = 0xFF28313C,
        surfaceFocused = 0xFF35404C,
        ink = 0xFFF9F6EF,
        inkMuted = 0xFFB9BFC8,
        inkSubtle = 0xFFA2ACB9,
        line = 0xFF3B444E,
        lineStrong = 0xFF9EA9B5,
        accent = 0xFFE3CAA1,
        onAccent = 0xFF221C13,
        accentStrong = 0xFFF1DDBC,
        focusRing = 0xFFF9F6EF,
        agent = 0xFFBCC0F4,
        onAgent = 0xFF171B35,
        agentContainer = 0xFF252B3B,
        caution = 0xFFEBD29E,
        danger = 0xFFFFB4AB,
        onDanger = 0xFF690005,
        success = 0xFFA6D7B1,
        highContrast = false,
    )
    val Day = VelaPaletteValues(
        canvas = 0xFFF1EEE5,
        surface = 0xFFFAF7EF,
        surfaceRaised = 0xFFDEDCD3,
        surfaceFocused = 0xFFD4D5D1,
        ink = 0xFF19222E,
        inkMuted = 0xFF485364,
        inkSubtle = 0xFF546072,
        line = 0xFFD2CEC4,
        lineStrong = 0xFF68717C,
        accent = 0xFF786144,
        onAccent = 0xFFFFFFFF,
        accentStrong = 0xFF5D4932,
        focusRing = 0xFF19222E,
        agent = 0xFF4946A4,
        onAgent = 0xFFFFFFFF,
        agentContainer = 0xFFE2E1EE,
        caution = 0xFF795321,
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
