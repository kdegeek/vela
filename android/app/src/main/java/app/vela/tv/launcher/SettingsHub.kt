package app.vela.tv.launcher

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import app.vela.tv.DeviceStatus
import app.vela.tv.VelaApp
import app.vela.tv.control.NowPlayingAccess
import app.vela.tv.design.*

@Composable internal fun SettingsHub(app: VelaApp, resume: Int, mediaAccess: NowPlayingAccess,
    origin: String?, restore: Int, reportError: (String) -> Unit, openPage: (Page, String) -> Unit,
    nowPlaying: () -> Unit, changed: () -> Unit) {
    val settings by app.settings.settings.collectAsState()
    val status = remember(resume, settings) { DeviceStatus.read(app) }
    val list = rememberLazyListState()
    val origins = remember { listOf("profiles", "routines", "external", "arrange").associateWith { FocusRequester() } }
    // These are the keyed lazy items below, including VelaPage's header.
    val originIndexes = mapOf("profiles" to 4, "routines" to 5, "external" to 7, "arrange" to 24)
    LaunchedEffect(restore) {
        if (restore > 0 && origin in origins) {
            list.scrollToItem(originIndexes.getValue(origin!!))
            withFrameNanos { }
            origins.getValue(origin).requestFocus()
        }
    }
    fun open(action: String) { runCatching { app.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { reportError("Android couldn't open these settings. Try Android settings instead.") } }
    fun update(value: app.vela.tv.settings.VelaSettings) { runCatching { app.settings.update(value) }.onFailure { reportError(it.message ?: "Couldn't save your preferences.") } }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val measured = "${maxWidth.value.toInt()} × ${maxHeight.value.toInt()} dp · text scale ${density.fontScale}"
        VelaPage("Make yourself at home", Modifier.fillMaxSize(), state = list) {
            item("phone-heading") { VelaSectionHeader("Phone") }
            item("phone") { PairCard(app, Modifier.fillMaxWidth()) }
            item("household-heading") { VelaSectionHeader("Household") }
            item("profiles") { VelaListRow("Profiles & memory", Modifier.focusRequester(origins.getValue("profiles")), glyph = VelaGlyph.Profile, onClick = { openPage(Page.PROFILES, "profiles") }) }
            item("routines") { VelaListRow("Routines", Modifier.focusRequester(origins.getValue("routines")), glyph = VelaGlyph.Activity, onClick = { openPage(Page.ROUTINES, "routines") }) }
            item("agent-heading") { VelaSectionHeader("Agent control") }
            item("external") { VelaListRow("External agent control", Modifier.focusRequester(origins.getValue("external")), glyph = VelaGlyph.Link, onClick = { openPage(Page.CONTROL, "external") }) }
            item("now-playing") { VelaListRow("Now Playing", supporting = "Playback apps, queue and controls", glyph = VelaGlyph.Play, onClick = nowPlaying) }
            item("connections-heading") { VelaSectionHeader("Connections & permissions") }
            item("ai") { ConnectionRow("AI", status.agentConfigured, "Set up from your paired phone") }
            item("library") { ConnectionRow("Jellyfin", status.jellyfinConfigured, "Set up from your paired phone") }
            item("plex") { ConnectionRow("Plex", status.plexConfigured, "Set up from your paired phone") }
            item("house") { ConnectionRow("Home Assistant", status.homeConfigured, "Set up from your paired phone") }
            item("tv-control") { VelaListRow(if (status.accessibility) "TV control enabled" else "Enable TV control",
                supporting = if (status.accessibility) "Remote approval keys available" else "Answer approvals on your paired phone",
                glyph = if (status.accessibility) VelaGlyph.Check else VelaGlyph.Caution,
                tone = if (status.accessibility) StatusTone.Success else StatusTone.Caution, onClick = { open(Settings.ACTION_ACCESSIBILITY_SETTINGS) }) }
            item("media-access") {
                val attention = !status.mediaAccess || mediaAccess in setOf(NowPlayingAccess.PERMISSION, NowPlayingAccess.UNAVAILABLE)
                VelaListRow(if (attention) "Now Playing needs attention" else "Media access enabled",
                    supporting = if (attention) "Open details for access settings or to retry." else "Apps that publish media sessions can appear in Now Playing.",
                    glyph = if (attention) VelaGlyph.Caution else VelaGlyph.Check,
                    tone = if (attention) StatusTone.Caution else StatusTone.Success, onClick = nowPlaying)
            }
            item("android-settings") { VelaListRow("Android settings", glyph = VelaGlyph.Settings, onClick = { open(Settings.ACTION_SETTINGS) }) }
            item("appearance-heading") { VelaSectionHeader("Appearance & accessibility") }
            item("theme") { VelaChoiceGroup("Theme", listOf("night", "day"), settings.appearance, { it.replaceFirstChar(Char::uppercase) }, { update(settings.copy(appearance = it)) }) }
            item("motion") { VelaChoiceGroup("Motion", listOf(false, true), settings.reduceMotion, { if (it) "Reduced" else "Standard" }, { update(settings.copy(reduceMotion = it)) }) }
            item("contrast") { VelaChoiceGroup("Contrast", listOf(false, true), settings.highContrast, { if (it) "High" else "Standard" }, { update(settings.copy(highContrast = it)) }) }
            item("home-button") { VelaChoiceGroup("Home button", listOf(true, false), settings.ownHome, { if (it) "Vela" else "System" }, { update(settings.copy(ownHome = it)) }) }
            item("showcase") { AppearanceShowcase() }
            item("home-heading") { VelaSectionHeader("Home screen") }
            item("arrange") { VelaListRow("Arrange home", Modifier.focusRequester(origins.getValue("arrange")), supporting = "Move or remove existing rows", onClick = { openPage(Page.ARRANGE, "arrange") }) }
            item("refresh") { VelaListRow("Refresh home", glyph = VelaGlyph.Refresh, onClick = changed) }
            item("device-heading") { VelaSectionHeader("This device") }
            item("device") { Text("${status.model} · Android API ${status.androidApi} · Vela ${status.version}\nSettings content area: $measured\nApp display metrics: ${status.surfaceWidth} × ${status.surfaceHeight} px. These measurements do not describe the panel.", style = Vela.type.body, color = Vela.colors.inkMuted, modifier = Modifier.readingWidth()) }
        }
    }
}
@Composable private fun ConnectionRow(name: String, configured: Boolean, setup: String) {
    VelaListRow(name, supporting = if (configured) "Configured" else setup,
        glyph = if (configured) VelaGlyph.Check else VelaGlyph.Caution, tone = if (configured) StatusTone.Success else StatusTone.Caution)
}
@Composable private fun AppearanceShowcase() {
    Column(verticalArrangement = Arrangement.spacedBy(VelaSpace.L)) {
        Text("Card appearance", style = Vela.type.section)
        // Visual-only examples: no duplicate focus targets or click handlers.
        CardFormat.entries.forEach { format ->
            FlowRow(Modifier.padding(VelaSpace.S), horizontalArrangement = Arrangement.spacedBy(VelaSpace.L), verticalArrangement = Arrangement.spacedBy(VelaSpace.L)) {
                MediaCardVisual("${format.name} · At rest", format, VelaInteractionState(false, false, false, true))
                MediaCardVisual("${format.name} · Focused", format, VelaInteractionState(true, false, false, true))
            }
        }
    }
}
