package app.vela.tv.design

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation

@Composable fun VelaTextField(value: String, onValueChange: (String) -> Unit, label: String,
    modifier: Modifier = Modifier, supporting: String? = null, error: String? = null, placeholder: String? = null,
    enabled: Boolean = true, readOnly: Boolean = false, secret: Boolean = false, revealSecret: Boolean = false,
    singleLine: Boolean = true, minLines: Int = 1, maxLines: Int = if (singleLine) 1 else 8, maxLength: Int = 8000,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default, keyboardActions: KeyboardActions = KeyboardActions.Default) {
    val c = Vela.colors
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val countMin = if (singleLine) 1 else minLines.coerceAtLeast(1)
    val countMax = if (singleLine) 1 else maxLines.coerceAtLeast(countMin)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(VelaSpace.Xs)) {
        OutlinedTextField(value, { if (enabled && !readOnly) onValueChange(it.take(maxLength.coerceAtLeast(0))) },
            modifier = Modifier.fillMaxWidth().velaFocusRing(focused, VelaShapes.Field, c.focusRing,
                if (c.highContrast) VelaSize.HighContrastRing else VelaSize.ControlRing, VelaSize.ControlRingOffset)
                .semantics { if (!enabled) disabled() },
            // Read-only retains the field's existing single focus target while making it unavailable.
            enabled = true, readOnly = readOnly || !enabled, textStyle = Vela.type.body,
            label = { Text(label, style = Vela.type.label) },
            placeholder = placeholder?.let { text -> { Text(text, style = Vela.type.body) } },
            isError = error != null, visualTransformation = if (secret && !revealSecret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = keyboardOptions, keyboardActions = if (enabled && !readOnly) keyboardActions else KeyboardActions.Default, singleLine = singleLine,
            minLines = countMin, maxLines = countMax, interactionSource = source, shape = VelaShapes.Field,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = if (enabled) c.ink else c.inkMuted, unfocusedTextColor = if (enabled) c.ink else c.inkSubtle,
                focusedContainerColor = c.surfaceFocused, unfocusedContainerColor = c.canvas,
                errorContainerColor = if (focused) c.surfaceFocused else c.canvas,
                cursorColor = c.accent, errorCursorColor = c.danger,
                // The shared outset ring is the sole focused outline; fill and cursor also change.
                focusedBorderColor = Color.Transparent, unfocusedBorderColor = c.lineStrong,
                errorBorderColor = if (focused) Color.Transparent else c.danger,
                focusedLabelColor = c.ink, unfocusedLabelColor = c.inkMuted, errorLabelColor = c.danger,
                focusedPlaceholderColor = c.inkMuted, unfocusedPlaceholderColor = c.inkSubtle, errorTextColor = c.ink))
        if (error != null) InlineStatus(error, tone = StatusTone.Danger)
        else if (supporting != null) Text(supporting, style = Vela.type.body, color = c.inkMuted, modifier = Modifier.readingWidth())
    }
}
@Composable fun FieldShell(label: String, modifier: Modifier = Modifier, supporting: String? = null,
    error: String? = null, content: @Composable () -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(VelaSpace.Xs)) {
        Text(label, style = Vela.type.label, color = Vela.colors.ink)
        content()
        if (error != null) InlineStatus(error, tone = StatusTone.Danger)
        else if (supporting != null) Text(supporting, style = Vela.type.body, color = Vela.colors.inkMuted, modifier = Modifier.readingWidth())
    }
}
