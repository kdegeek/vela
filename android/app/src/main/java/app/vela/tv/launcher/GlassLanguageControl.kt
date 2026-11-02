package app.vela.tv.launcher

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.vela.tv.design.*
import app.vela.tv.profile.SubtitlePreference

/** The picker edits the existing form draft. Choosing Back never rewrites tags or subtitle intent. */
@Composable internal fun GlassLanguageControl(
    label: String, raw: String, enabled: Boolean, modifier: Modifier = Modifier,
    intent: SubtitlePreference? = null, changeIntent: (SubtitlePreference) -> Unit = {}, update: (String) -> Unit,
) {
    var choosing by rememberSaveable { mutableStateOf(false) }
    var custom by rememberSaveable { mutableStateOf(false) }
    val mode = intent?.name?.lowercase()?.replaceFirstChar(Char::uppercase)
    val summary = when {
        mode == null -> GlassLanguages.label(raw)
        raw.isBlank() -> mode
        else -> "$mode · ${GlassLanguages.label(raw)}"
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(VelaSpace.Xxs)) {
        Text(label, style = Vela.type.meta, color = Vela.colors.inkMuted)
        VelaButton(summary.take(48) + if (summary.length > 48) "…" else "", Modifier.fillMaxWidth().semantics { contentDescription = "$label: $summary" }, enabled = enabled) { choosing = true; custom = false }
    }
    if (choosing) {
        val back = remember { FocusRequester() }
        VelaDialog(label, { choosing = false }, back, actions = {
            VelaButton("Back", Modifier.focusRequester(back)) { choosing = false }
        }) {
            if (intent != null) VelaChoiceGroup("Subtitles", SubtitlePreference.entries, intent,
                { it.name.lowercase().replaceFirstChar(Char::uppercase) }, changeIntent, enabled = enabled)
            Text("Current: ${GlassLanguages.label(raw)}", style = Vela.type.body, color = Vela.colors.ink)
            if (custom) ManagementField("Language tags · in preference order, comma-separated", raw, enabled, 520) { update(it) }
            else {
                GlassLanguages.choices.forEach { (tag, title) ->
                    VelaListRow(title, supporting = tag.takeIf(String::isNotEmpty), enabled = enabled,
                        onClick = { update(GlassLanguages.choose(raw, tag)); choosing = false })
                }
            }
            VelaButton(if (custom) "Show language choices" else "Custom language tags…", enabled = enabled, variant = ButtonVariant.Quiet) { custom = !custom }
            Text("Choose a language or keep exact regional/custom tags. Availability depends on the title. Changes are saved only with Save profile.",
                style = Vela.type.meta, color = Vela.colors.inkMuted)
        }
    }
}
