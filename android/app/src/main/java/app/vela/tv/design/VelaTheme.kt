package app.vela.tv.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import app.vela.tv.settings.VelaSettings

/** Semantic tokens shared by every TV surface. dp is independent of the panel's pixel resolution. */
object VelaDesign {
    val SpaceSmall = 8.dp
    val Space = 16.dp
    val SafeHorizontal = 40.dp
    val SafeVertical = 24.dp
    val CardShape = RoundedCornerShape(12.dp)
    val PanelShape = RoundedCornerShape(20.dp)
    val PillShape = RoundedCornerShape(50)
    val MinimumTarget = 44.dp
    const val FocusDuration = 160
    const val BackdropDuration = 600
    val Night = darkColorScheme(
        primary = Color(0xFFE5CA91), onPrimary = Color(0xFF252012),
        background = Color(0xFF0E1024), onBackground = Color(0xFFF6F1E7),
        surface = Color(0xFF1B2036), onSurface = Color(0xFFF6F1E7),
        surfaceVariant = Color(0xFF2C324A), onSurfaceVariant = Color(0xFFC3C5D3),
        error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
        outline = Color(0xFF8D91A5),
    )
    val Day = lightColorScheme(
        primary = Color(0xFF74511C), onPrimary = Color.White,
        background = Color(0xFFF6F1E7), onBackground = Color(0xFF171B2C),
        surface = Color(0xFFFFFBF3), onSurface = Color(0xFF171B2C),
        surfaceVariant = Color(0xFFE6E2DB), onSurfaceVariant = Color(0xFF434758),
        error = Color(0xFFA82428), onError = Color.White, outline = Color(0xFF666A7C),
    )
    val Type = Typography(
        displaySmall = TextStyle(fontFamily = FontFamily.Serif, fontSize = 36.sp, lineHeight = 40.sp),
        headlineMedium = TextStyle(fontFamily = FontFamily.Serif, fontSize = 26.sp, lineHeight = 32.sp),
        titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
        bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 23.sp),
        bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
        labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    )
}
val LocalReducedMotion = staticCompositionLocalOf { false }
val LocalHighContrast = staticCompositionLocalOf { false }

@Composable fun VelaTheme(settings: VelaSettings, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalReducedMotion provides settings.reduceMotion, LocalHighContrast provides settings.highContrast) {
        MaterialTheme(colorScheme = if (settings.appearance == "day") VelaDesign.Day else VelaDesign.Night,
            typography = VelaDesign.Type, shapes = Shapes(medium = VelaDesign.CardShape, large = VelaDesign.PanelShape), content = content)
    }
}

/** Focus is a high-contrast border AND a fill change; selection is separate from focus. */
@Composable fun VelaButton(label: String, modifier: Modifier = Modifier, selected: Boolean = false, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val c = MaterialTheme.colorScheme
    Text(label, style = MaterialTheme.typography.labelLarge,
        color = if (focused) c.onPrimary else c.onSurface,
        modifier = modifier.heightIn(min = VelaDesign.MinimumTarget).clip(VelaDesign.PillShape)
            .background(if (focused) c.primary else if (selected) c.surfaceVariant else c.surface)
            .border(if (focused) 2.dp else 1.dp, if (focused || selected) c.primary else c.outline.copy(alpha = 0.35f), VelaDesign.PillShape)
            .clickable(interactionSource = source, indication = ripple(), role = Role.Button, onClickLabel = label, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp))
}

@Composable fun EmptyState(title: String, message: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().clip(VelaDesign.PanelShape).background(MaterialTheme.colorScheme.surface).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
        Text(message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (action != null) VelaButton(action, onClick = onAction)
    }
}
