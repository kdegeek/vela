package app.vela.tv.launcher

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
            ProfileEditor(original, actions, { editing = false }) { name, audio, subtitles, intent, autoplay ->
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
    ManagementList("Household profiles & memory", actions) {
        item { Text("Each profile has its own preferences and saved memories. Profiles have no PIN protection. Home rows and My list are shared.") }
        item { OwnerControlStatus(management) }
        item { ManagementButtons {
            VelaButton("Refresh profiles", enabled = !actions.busy) { actions.run { refresh() } }
            VelaButton("Recover household storage…", enabled = !actions.busy) { actions.confirm("Stop current work and reconcile saved household storage? No data is reset or deleted.") { profiles = management.reloadHousehold(); loadError = null } }
            VelaButton("New adult profile", enabled = !actions.busy && profiles != null) { original = null; editRevision = profiles!!.revision; editing = true }
        } }
        loadError?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        if (profiles == null && loadError == null) item { LoadingState("Loading household profiles…") }
        profiles?.let { displayed ->
            items(displayed.profiles, key = { it.id }) { profile ->
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(profile.displayName + if (profile.id == displayed.activeProfileId) " · current profile" else "", style = MaterialTheme.typography.titleLarge)
                    Text("${profile.kind.name.lowercase()} · ${profile.id.take(8)} · subtitles ${profile.preferences.subtitles.name.lowercase()} · autoplay ${if (profile.preferences.autoplayNext) "on" else "off"}")
                    ManagementButtons {
                        VelaButton("Preferences", enabled = !actions.busy) { original = profile; editRevision = displayed.revision; editing = true }
                        VelaButton("Explicit memory", enabled = !actions.busy) { memoryProfile = profile }
                        if (profile.id != displayed.activeProfileId) VelaButton("Switch here…", enabled = !actions.busy) {
                            actions.confirm("Switch to ${profile.displayName}? Current work stops and prior profile conversation and approvals clear.") { management.switchProfile(displayed.revision, profile.id); refresh() }
                        }
                        if (profile.id != displayed.activeProfileId && displayed.profiles.size > 1) VelaButton("Delete profile…", enabled = !actions.busy) {
                            actions.confirm("Permanently delete ${profile.displayName} and ALL of its explicit memories? Switch away from active profiles before deleting them.") { management.deleteProfile(displayed.revision, profile.id, true); refresh() }
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable private fun ProfileEditor(original: HouseholdProfile?, actions: ManagementActions, close: () -> Unit,
    save: (String, String, String, SubtitlePreference, Boolean) -> Unit) {
    var name by rememberSaveable { mutableStateOf(original?.displayName.orEmpty()) }
    var audio by rememberSaveable { mutableStateOf(original?.preferences?.audioLanguages?.joinToString(", ").orEmpty()) }
    var subtitles by rememberSaveable { mutableStateOf(original?.preferences?.subtitleLanguages?.joinToString(", ").orEmpty()) }
    var intent by rememberSaveable { mutableStateOf(original?.preferences?.subtitles ?: SubtitlePreference.AUTOMATIC) }
    var autoplay by rememberSaveable { mutableStateOf(original?.preferences?.autoplayNext ?: false) }
    BackHandler(!actions.busy, close)
    ManagementList(if (original == null) "New adult profile" else "Preferences · ${original.displayName}", actions) {
        item { ManagementButtons {
            VelaButton("Back", enabled = !actions.busy, onClick = close)
            VelaButton("Save profile", enabled = !actions.busy && name.isNotBlank()) { save(name, audio, subtitles, intent, autoplay) }
        } }
        item { ManagementField("Display name", name, !actions.busy, 60) { name = it } }
        item { ManagementField("Audio languages · comma-separated tags, e.g. en, es", audio, !actions.busy, 520) { audio = it } }
        item { ManagementField("Subtitle languages · comma-separated tags", subtitles, !actions.busy, 520) { subtitles = it } }
        item { Text("Subtitle intent"); ManagementButtons { SubtitlePreference.entries.forEach { value -> VelaButton(value.name.lowercase(), selected = intent == value, enabled = !actions.busy) { intent = value } } } }
        item { VelaButton("Autoplay next episode: ${if (autoplay) "on" else "off"}", enabled = !actions.busy) { autoplay = !autoplay } }
        item { Text("Language availability depends on the title. Your library preferences and access restrictions stay the same.") }
        item { VelaButton("Save profile", enabled = !actions.busy && name.isNotBlank()) { save(name, audio, subtitles, intent, autoplay) } }
    }
}

@Composable private fun MemoryManagementPage(management: OwnerManagement, profile: HouseholdProfile, close: () -> Unit) {
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
        ManagementList("Private export · ${profile.displayName}", actions) {
            item { VelaButton("Hide export") { exported = null } }
            item { Text("Includes private entries. For a JSON download use Export on the paired phone. This export is not sent to the agent.") }
            item { OutlinedTextField(json, {}, Modifier.fillMaxWidth(), label = { Text("Profile export") }, readOnly = true, minLines = 4, maxLines = 12) }
        }
        return
    }
    if (editing) {
        key(original?.id, editRevision) {
            MemoryEditor("${profile.displayName} · ${profile.id.take(8)}", original, actions, { editing = false }) { draft ->
                actions.run { management.saveMemory(editRevision, profile.id, draft, original?.id, OwnerMemorySource.TV); editing = false; refresh() }
            }
        }
        return
    }
    ManagementList("Explicit memory · ${profile.displayName}", actions) {
        item { Text("Only words you explicitly Save are remembered. Never enter credentials. Shared entries may be sent to your AI provider. Memory cannot grant permissions. Saving, editing or removing used memory invalidates prior agent context.") }
        item { OwnerControlStatus(management) }
        item { ManagementButtons {
            VelaButton("Back", enabled = !actions.busy, onClick = close)
            VelaButton("Reload memory", enabled = !actions.busy) { actions.run { refresh() } }
            VelaButton("New memory", enabled = !actions.busy && page != null) { original = null; editRevision = page!!.revision; editing = true }
            VelaButton("Clear all memory…", enabled = !actions.busy && page != null) { actions.confirm("Permanently clear ALL explicit memory for ${profile.displayName}?") { management.clearMemories(page!!.revision, profile.id, true); refresh() } }
            VelaButton("Export profile…", enabled = !actions.busy) { actions.confirm("Show a private export of ${profile.displayName}, including entries not shared with the agent, on this screen?") { exported = management.exportProfile(profile.id) } }
        } }
        error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        val displayed = page
        when {
            displayed == null && error == null -> item { LoadingState("Loading explicit memory…") }
            displayed?.entries?.isEmpty() == true -> item { Text("No explicit memory saved for this profile.") }
        }
        displayed?.let { loaded ->
            items(loaded.entries, key = { it.id }) { entry ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(entry.kind.name.lowercase(), style = MaterialTheme.typography.titleMedium)
                    Text(entry.text)
                    Text("${entry.tags.joinToString(", ")} · ${if (entry.shareWithAgent) "shared with agent" else "private to owner"}")
                    ManagementButtons {
                        VelaButton("Edit exact words", enabled = !actions.busy) { original = entry; editRevision = loaded.revision; editing = true }
                        VelaButton("Delete…", enabled = !actions.busy) { actions.confirm("Permanently delete these saved words for ${profile.displayName}?") { management.deleteMemory(loaded.revision, profile.id, entry.id); refresh() } }
                    }
                    HorizontalDivider()
                }
            }
            if (loaded.entries.size < loaded.total) item { VelaButton("Load more (${loaded.entries.size} of ${loaded.total})", enabled = !actions.busy) { actions.run { refresh(true) } } }
        }
    }
}

@Composable private fun MemoryEditor(profileLabel: String, original: MemoryEntry?, actions: ManagementActions, close: () -> Unit, save: (MemoryDraft) -> Unit) {
    var text by rememberSaveable { mutableStateOf(original?.text.orEmpty()) }
    var tags by rememberSaveable { mutableStateOf(original?.tags?.joinToString(", ").orEmpty()) }
    var kind by rememberSaveable { mutableStateOf(original?.kind ?: MemoryKind.PREFERENCE) }
    var shared by rememberSaveable { mutableStateOf(original?.shareWithAgent ?: false) }
    fun submit() = save(MemoryDraft(kind, text.trim(), ManagementForms.words(tags), shared))
    ManagementList("Save exact words · $profileLabel", actions) {
        item { ManagementButtons { VelaButton("Back", enabled = !actions.busy, onClick = close); VelaButton("Save these words", enabled = !actions.busy && text.isNotBlank()) { submit() } } }
        item { ManagementButtons { MemoryKind.entries.forEach { value -> VelaButton(value.name.lowercase(), selected = value == kind, enabled = !actions.busy) { kind = value } } } }
        item { ManagementField("Exact words · no credentials", text, !actions.busy, 2000, true) { text = it } }
        item { ManagementField("Tags · comma-separated lowercase words", tags, !actions.busy, 264) { tags = it } }
        item { VelaButton("Share with agent: ${if (shared) "yes" else "no"}", enabled = !actions.busy) { shared = !shared } }
        item { Text("Save is deliberate. A conflict leaves this draft unchanged. Return, reload the list and reopen the editor to review the current entry before reapplying an edit.") }
        item { VelaButton("Save these words", enabled = !actions.busy && text.isNotBlank()) { submit() } }
    }
}
