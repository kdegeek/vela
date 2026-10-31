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
import app.vela.tv.profile.HouseholdState

@Composable internal fun SettingsHub(app: VelaApp, resume: Int, mediaAccess: NowPlayingAccess,
    restoreOrigin: String?, restored: (String) -> Unit, reportError: (String) -> Unit, openPage: (Page, String) -> Unit,
    nowPlaying: () -> Unit, changed: () -> Unit) {
    val settings by app.settings.settings.collectAsState()
    val status = remember(resume, settings) { DeviceStatus.read(app) }
    val list = rememberLazyListState()
    val origins = remember { listOf("profiles", "routines", "external", "arrange").associateWith { FocusRequester() } }
    // This is also the composition order below: restoration addresses the real section key.
    val sections = listOf("profiles", "appearance", "arrange", "phone", "routines", "connections", "external", "device")
    LaunchedEffect(restoreOrigin) {
        if (restoreOrigin in origins) {
            val origin = restoreOrigin!!
            list.scrollToItem(sections.indexOf(origin) + 1)
            withFrameNanos { }
            if (origins.getValue(origin).requestFocus()) restored(origin)
        }
    }
    fun open(action: String) { runCatching { app.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { reportError("Android couldn't open these settings. Try Android settings instead.") } }
    fun update(value: app.vela.tv.settings.VelaSettings) { runCatching { app.settings.update(value) }.onFailure { reportError(it.message ?: "Couldn't save your preferences.") } }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val measured = "${maxWidth.value.toInt()} × ${maxHeight.value.toInt()} dp · text scale ${density.fontScale}"
        GlassDestinationPage("Make yourself at home", Modifier.fillMaxSize(), state = list) {
            sections.forEach { section -> item(section) {
                when (section) {
                    "profiles" -> GlassManagementGroup {
                        val householdStore by app.householdReady.collectAsState()
                        val household = householdStore?.state?.collectAsState()?.value
                        VelaSectionHeader("People at home")
                        VelaListRow((household as? HouseholdState.Ready)?.document?.activeProfile?.displayName ?: "Profiles & memory",
                            Modifier.focusRequester(origins.getValue("profiles")),
                            supporting = "Preferences and what Vela remembers · Home and My list are shared",
                            glyph = VelaGlyph.Profile, onClick = { openPage(Page.PROFILES, "profiles") })
                    }
                    "appearance" -> GlassManagementGroup {
                        VelaSectionHeader("Appearance & accessibility")
                        VelaChoiceGroup("Theme", listOf("night", "day"), settings.appearance, { it.replaceFirstChar(Char::uppercase) }, { update(settings.copy(appearance = it)) })
                        VelaChoiceGroup("Motion", listOf(false, true), settings.reduceMotion, { if (it) "Reduced" else "Standard" }, { update(settings.copy(reduceMotion = it)) })
                        VelaChoiceGroup("Contrast", listOf(false, true), settings.highContrast, { if (it) "High" else "Standard" }, { update(settings.copy(highContrast = it)) })
                        VelaChoiceGroup("Home button", listOf(true, false), settings.ownHome, { if (it) "Vela" else "System" }, { update(settings.copy(ownHome = it)) })
                    }
                    "arrange" -> GlassManagementGroup {
                        VelaSectionHeader("Home screen")
                        VelaListRow("Arrange home", Modifier.focusRequester(origins.getValue("arrange")), supporting = "Move or remove existing rows", onClick = { openPage(Page.ARRANGE, "arrange") })
                        VelaListRow("Refresh home", glyph = VelaGlyph.Refresh, onClick = changed)
                    }
                    "phone" -> PairCard(app, Modifier.fillMaxWidth())
                    "routines" -> GlassManagementGroup {
                        VelaListRow("Routines", Modifier.focusRequester(origins.getValue("routines")), supporting = "Saved tasks and schedules", glyph = VelaGlyph.Activity, onClick = { openPage(Page.ROUTINES, "routines") })
                    }
                    "connections" -> GlassManagementGroup {
                        VelaSectionHeader("Connections & permissions")
                        VelaListRow("Now Playing", supporting = "Playback apps, queue and controls", glyph = VelaGlyph.Play, onClick = nowPlaying)
                        ConnectionRow("AI", status.agentConfigured, "Set up from your paired phone")
                        ConnectionRow("Jellyfin", status.jellyfinConfigured, "Set up from your paired phone")
                        ConnectionRow("Plex", status.plexConfigured, "Set up from your paired phone")
                        ConnectionRow("Home Assistant", status.homeConfigured, "Set up from your paired phone")
                        VelaListRow(if (status.accessibility) "TV control enabled" else "Enable TV control",
                            supporting = if (status.accessibility) "Remote approval keys available" else "Answer approvals on your paired phone",
                            glyph = if (status.accessibility) VelaGlyph.Check else VelaGlyph.Caution,
                            tone = if (status.accessibility) StatusTone.Success else StatusTone.Caution, onClick = { open(Settings.ACTION_ACCESSIBILITY_SETTINGS) })
                        val attention = !status.mediaAccess || mediaAccess in setOf(NowPlayingAccess.PERMISSION, NowPlayingAccess.UNAVAILABLE)
                        VelaListRow(if (attention) "Now Playing needs attention" else "Media access enabled",
                            supporting = if (attention) "Open details for access settings or to retry." else "Apps that publish media sessions can appear in Now Playing.",
                            glyph = if (attention) VelaGlyph.Caution else VelaGlyph.Check,
                            tone = if (attention) StatusTone.Caution else StatusTone.Success, onClick = nowPlaying)
                    }
                    "external" -> GlassManagementGroup {
                        VelaSectionHeader("Advanced control")
                        VelaListRow("External agent control", Modifier.focusRequester(origins.getValue("external")), supporting = "Dedicated credential and loopback listener", glyph = VelaGlyph.Link, onClick = { openPage(Page.CONTROL, "external") })
                    }
                    "device" -> GlassManagementGroup {
                        VelaSectionHeader("This device")
                        VelaListRow("Android settings", glyph = VelaGlyph.Settings, onClick = { open(Settings.ACTION_SETTINGS) })
                        Text("${status.model} · Android API ${status.androidApi} · Vela ${status.version}\nSettings content area: $measured\nApp display metrics: ${status.surfaceWidth} × ${status.surfaceHeight} px. These measurements do not describe the panel.", style = Vela.type.meta, color = Vela.colors.inkMuted, modifier = Modifier.readingWidth())
                    }
                }
            } }
        }
    }
}
@Composable private fun ConnectionRow(name: String, configured: Boolean, setup: String) {
    VelaListRow(name, supporting = if (configured) "Configured" else setup,
        glyph = if (configured) VelaGlyph.Check else VelaGlyph.Caution, tone = if (configured) StatusTone.Success else StatusTone.Caution)
}
