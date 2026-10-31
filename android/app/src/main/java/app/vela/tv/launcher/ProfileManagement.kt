package app.vela.tv.launcher

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import app.vela.tv.VelaApp
import app.vela.tv.design.*
import app.vela.tv.memory.*
import app.vela.tv.profile.*
import app.vela.tv.runtime.MemoryDraft
import app.vela.tv.runtime.OwnerManagement
import app.vela.tv.runtime.ProfileList
import kotlinx.coroutines.CancellationException

@Composable internal fun ProfilesPage(app: VelaApp) {
    val management = app.management
    val owner by management.busy.collectAsState()
    val actions = rememberManagementActions()
    var profiles by remember { mutableStateOf<ProfileList?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf(false) }
    var original by remember { mutableStateOf<HouseholdProfile?>(null) }
    var editRevision by remember { mutableLongStateOf(0) }
    var memoryProfile by remember { mutableStateOf<HouseholdProfile?>(null) }
    suspend fun refresh() { profiles = management.profiles(); loadError = null }
    val readyStore by app.householdReady.collectAsState()
    LaunchedEffect(readyStore) {
        try {
            val store = readyStore ?: app.householdStore()
            store.state.collect { try { refresh() } catch (e: Exception) { if (e is CancellationException) throw e; profiles = null; loadError = e.message ?: "Household storage needs attention." } }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { loadError = e.message ?: "Could not initialize household profiles." }
    }
    if (memoryProfile != null) {
        key(memoryProfile!!.id) { MemoryManagementPage(management, memoryProfile!!) { memoryProfile = null } }
        return
    }
    if (editing) {
        key(original?.id, editRevision) {
            ProfileEditor(original, original?.id == profiles?.activeProfileId, actions, { editing = false }) { name, audio, subtitles, intent, autoplay ->
                val draft = ManagementForms.profile(original, name, audio, subtitles, intent, autoplay)
                actions.run {
                    if (original == null) management.createProfile(editRevision, draft)
                    else management.editProfile(editRevision, original!!.id, draft)
                    editing = false; refresh()
                }
            }
        }
        return
    }
    ManagementList("People at home", actions) {
        item("profile-actions") { ManagementButtons {
            VelaButton("Add a person", enabled = !actions.busy && profiles != null, variant = ButtonVariant.Primary) { original = null; editRevision = profiles!!.revision; editing = true }
            VelaButton("Refresh profiles", enabled = !actions.busy, glyph = VelaGlyph.Refresh, variant = ButtonVariant.Quiet) { actions.run { refresh() } }
        } }
        item("profile-explanation") { ManagementText("Each profile has its own preferences and saved memories. Profiles have no PIN protection. Home rows and My list are shared.") }
        owner?.let { active -> item("profile-owner") { OwnerControlStatus(management, active) } }
        loadError?.let { item("profile-error") { ManagementError(it) } }
        if (profiles == null && loadError == null) item("profile-loading") { LoadingState("Loading household profiles…") }
        profiles?.let { displayed ->
            item("profile-heading") { VelaSectionHeader("Profiles") }
            items(displayed.profiles, key = { it.id }) { profile ->
                GlassManagementGroup {
                    VelaListRow(profile.displayName, glyph = VelaGlyph.Profile,
                        supporting = "${if (profile.id == displayed.activeProfileId) "Current profile · " else ""}${profile.kind.name.lowercase()} · subtitles ${profile.preferences.subtitles.name.lowercase()} · autoplay ${if (profile.preferences.autoplayNext) "on" else "off"}")
                    ManagementButtons {
                        VelaButton("Preferences", enabled = !actions.busy, variant = ButtonVariant.Primary) { original = profile; editRevision = displayed.revision; editing = true }
                        VelaButton("What Vela remembers", enabled = !actions.busy) { memoryProfile = profile }
                        if (profile.id != displayed.activeProfileId) VelaButton("Switch here…", enabled = !actions.busy) {
                            actions.confirm("Switch to ${profile.displayName}? Current work stops and prior profile conversation and approvals clear.") { management.switchProfile(displayed.revision, profile.id); refresh() }
                        }
                        if (profile.id != displayed.activeProfileId && displayed.profiles.size > 1) VelaButton("Delete profile…", enabled = !actions.busy, variant = ButtonVariant.Danger) {
                            actions.confirm("Permanently delete ${profile.displayName} and ALL of its explicit memories? Switch away from active profiles before deleting them.", danger = true) { management.deleteProfile(displayed.revision, profile.id, true); refresh() }
                        }
                    }
                }
            }
        }
        item("profile-maintenance") { GlassManagementGroup {
            VelaSectionHeader("Maintenance")
            VelaButton("Recover household storage…", enabled = !actions.busy, variant = ButtonVariant.Quiet) {
                actions.confirm("Stop current work and reconcile saved household storage? No data is reset or deleted.") { profiles = management.reloadHousehold(); loadError = null }
            }
        } }
    }
}

@Composable private fun ProfileEditor(original: HouseholdProfile?, current: Boolean, actions: ManagementActions, close: () -> Unit,
    save: (String, String, String, SubtitlePreference, Boolean) -> Unit) {
    var name by rememberSaveable { mutableStateOf(original?.displayName.orEmpty()) }
    var audio by rememberSaveable { mutableStateOf(original?.preferences?.audioLanguages?.joinToString(", ").orEmpty()) }
    var subtitles by rememberSaveable { mutableStateOf(original?.preferences?.subtitleLanguages?.joinToString(", ").orEmpty()) }
    var intent by rememberSaveable { mutableStateOf(original?.preferences?.subtitles ?: SubtitlePreference.AUTOMATIC) }
    var autoplay by rememberSaveable { mutableStateOf(original?.preferences?.autoplayNext ?: false) }
    BackHandler(!actions.busy, close)
    GlassManagementEditor("Make yourself at home", actions, close, "Save profile", name.isNotBlank(),
        save = { save(name, audio, subtitles, intent, autoplay) }, person = {
            Column(verticalArrangement = Arrangement.spacedBy(VelaSpace.M)) {
                Box(Modifier.size(60.dp).background(Vela.colors.surfaceRaised, CircleShape), contentAlignment = Alignment.Center) {
                    Text(original?.displayName?.let { it.substring(0, it.offsetByCodePoints(0, 1)).uppercase(java.util.Locale.ROOT) } ?: "+", style = Vela.type.title, color = Vela.colors.ink)
                }
                Text(if (original == null) "New adult profile" else if (current) "Current person" else "Person", style = Vela.type.meta, color = Vela.colors.inkMuted)
                Text(original?.displayName ?: "Add a person", style = Vela.type.title, color = Vela.colors.ink, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text("Your preferences.\nA shared home.", style = Vela.type.body, color = Vela.colors.inkMuted)
                Text("Preferences", style = Vela.type.label, color = Vela.colors.ink)
            }
        }) {
        item("profile-editor-name") { ManagementField("Display name", name, !actions.busy, 60) { name = it } }
        item("profile-editor-languages") {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                if (maxWidth >= 440.dp) Row(horizontalArrangement = Arrangement.spacedBy(VelaSpace.S)) {
                    GlassLanguageControl("Preferred audio", audio, !actions.busy, Modifier.weight(1f)) { audio = it }
                    GlassLanguageControl("Subtitles", subtitles, !actions.busy, Modifier.weight(1f), intent, { intent = it }) { subtitles = it }
                } else Column(verticalArrangement = Arrangement.spacedBy(VelaSpace.S)) {
                    GlassLanguageControl("Preferred audio", audio, !actions.busy) { audio = it }
                    GlassLanguageControl("Subtitles", subtitles, !actions.busy, intent = intent, changeIntent = { intent = it }) { subtitles = it }
                }
            }
        }
        item("profile-editor-autoplay") { VelaToggleRow("Autoplay next episode", autoplay, { autoplay = it }, enabled = !actions.busy) }
        item("profile-editor-explanation") {
            Text("Anyone in the room can switch; profiles have no PIN. Home rows and My list are shared. Language availability depends on the title.",
                style = Vela.type.meta, color = Vela.colors.inkMuted, modifier = Modifier.readingWidth())
        }
    }
}

@Composable private fun MemoryManagementPage(management: OwnerManagement, profile: HouseholdProfile, close: () -> Unit) {
    val owner by management.busy.collectAsState()
    val actions = rememberManagementActions()
    var page by remember { mutableStateOf<MemoryPage?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf(false) }
    var original by remember { mutableStateOf<MemoryEntry?>(null) }
    var editRevision by remember { mutableLongStateOf(0) }
    var exported by remember { mutableStateOf<String?>(null) }
    suspend fun refresh(append: Boolean = false) {
        val before = page
        val loaded = management.memories(profile.id, if (append) before!!.entries.size else 0, 20, if (append) before!!.revision else null)
        page = if (append) loaded.copy(entries = before!!.entries + loaded.entries) else loaded
        error = null
    }
    LaunchedEffect(profile.id) { try { refresh() } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message ?: "Could not load memory." } }
    BackHandler(!actions.busy) { if (exported != null) exported = null else if (editing) editing = false else close() }
    exported?.let { json ->
        val hideFocus = Modifier.rememberManagementEntryFocus()
        ManagementList("Private export · ${profile.displayName}", actions) {
            item("export-hide") { VelaButton("Hide export", hideFocus) { exported = null } }
            item("export-explanation") { ManagementText("Includes private entries. For a JSON download use Export on the paired phone. This export is not sent to the agent.") }
            item("export-content") { VelaTextField(json, {}, "Profile export", Modifier.fillMaxWidth(), readOnly = true, singleLine = false, minLines = 4, maxLines = 12) }
            item("export-hide-end") { VelaButton("Hide export") { exported = null } }
        }
        return
    }
    if (editing) {
        key(original?.id, editRevision) {
            MemoryEditor(profile.displayName, original, actions, { editing = false }) { draft ->
                actions.run { management.saveMemory(editRevision, profile.id, draft, original?.id, OwnerMemorySource.TV); editing = false; refresh() }
            }
        }
        return
    }
    val backFocus = Modifier.rememberManagementEntryFocus()
    ManagementList("Explicit memory · ${profile.displayName}", actions) {
        item("memory-actions") { ManagementButtons {
            VelaButton("Back", backFocus, enabled = !actions.busy, onClick = close)
            VelaButton("Reload memory", enabled = !actions.busy, glyph = VelaGlyph.Refresh) { actions.run { refresh() } }
            VelaButton("New memory", enabled = !actions.busy && page != null, variant = ButtonVariant.Primary) { original = null; editRevision = page!!.revision; editing = true }
            VelaButton("Clear all memory…", enabled = !actions.busy && page != null, variant = ButtonVariant.Danger) { actions.confirm("Permanently clear ALL explicit memory for ${profile.displayName}?", danger = true) { management.clearMemories(page!!.revision, profile.id, true); refresh() } }
            VelaButton("Export profile…", enabled = !actions.busy) { actions.confirm("Show a private export of ${profile.displayName}, including entries not shared with the agent, on this screen?") { exported = management.exportProfile(profile.id) } }
        } }
        item("memory-explanation") { ManagementText("Only words you explicitly Save are remembered. Never enter credentials. Shared entries may be sent to your AI provider. Memory cannot grant permissions. Saving, editing or removing used memory invalidates prior agent context.", "Explicit memory") }
        owner?.let { active -> item("memory-owner") { OwnerControlStatus(management, active) } }
        error?.let { item("memory-error") { ManagementError(it) } }
        val displayed = page
        when {
            displayed == null && error == null -> item("memory-loading") { LoadingState("Loading explicit memory…") }
            displayed?.entries?.isEmpty() == true -> item("memory-empty") { EmptyState("No explicit memory", "No explicit memory saved for this profile.") }
        }
        displayed?.let { loaded ->
            items(loaded.entries, key = { it.id }) { entry ->
                GlassManagementGroup {
                    VelaListRow(entry.kind.name.lowercase().replaceFirstChar { it.titlecase() }, glyph = VelaGlyph.Bookmark,
                        supporting = "${entry.tags.joinToString(", ")} · ${if (entry.shareWithAgent) "shared with agent" else "private to owner"}")
                    ManagementText(entry.text, "Saved words · ${profile.displayName}")
                    ManagementButtons {
                        VelaButton("Edit exact words", enabled = !actions.busy) { original = entry; editRevision = loaded.revision; editing = true }
                        VelaButton("Delete…", enabled = !actions.busy, variant = ButtonVariant.Danger) { actions.confirm("Permanently delete these saved words for ${profile.displayName}?", danger = true) { management.deleteMemory(loaded.revision, profile.id, entry.id); refresh() } }
                    }
                }
            }
            if (loaded.entries.size < loaded.total) item("memory-more") { VelaButton("Load more (${loaded.entries.size} of ${loaded.total})", enabled = !actions.busy) { actions.run { refresh(true) } } }
        }
    }
}

@Composable private fun MemoryEditor(profileLabel: String, original: MemoryEntry?, actions: ManagementActions, close: () -> Unit, save: (MemoryDraft) -> Unit) {
    var text by rememberSaveable { mutableStateOf(original?.text.orEmpty()) }
    var tags by rememberSaveable { mutableStateOf(original?.tags?.joinToString(", ").orEmpty()) }
    var kind by rememberSaveable { mutableStateOf(original?.kind ?: MemoryKind.PREFERENCE) }
    var shared by rememberSaveable { mutableStateOf(original?.shareWithAgent ?: false) }
    fun submit() = save(MemoryDraft(kind, text.trim(), ManagementForms.words(tags), shared))
    GlassManagementEditor("Save exact words · $profileLabel", actions, close, "Save these words", text.isNotBlank(), save = { submit() }) {
        item("memory-editor-kind") { VelaChoiceGroup("Memory kind", MemoryKind.entries, kind, { it.name.lowercase() }, { kind = it }, enabled = !actions.busy) }
        item("memory-editor-words") { ManagementField("Exact words · no credentials", text, !actions.busy, 2000, true) { text = it } }
        item("memory-editor-tags") { ManagementField("Tags · comma-separated lowercase words", tags, !actions.busy, 264) { tags = it } }
        item("memory-editor-sharing") { VelaToggleRow("Share with agent", shared, { shared = it }, enabled = !actions.busy) }
        item("memory-editor-explanation") { ManagementText("Save is deliberate. A conflict leaves this draft unchanged. Return, reload the list and reopen the editor to review the current entry before reapplying an edit.") }
    }
}
