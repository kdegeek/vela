package app.vela.tv.launcher

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.vela.tv.DeviceStatus
import app.vela.tv.VelaApp
import app.vela.tv.design.*
import app.vela.tv.layout.*
import kotlinx.coroutines.*
import java.text.DateFormat
import java.util.Date

private enum class Page(val label: String) {
    HOME("Home"), SEARCH("Search"), APPS("Apps"), LIST("My list"), HOUSE("House"), ACTIVITY("Activity"), SETTINGS("Settings")
}

@Composable internal fun TvShell(app: VelaApp, resume: Int) {
    val settings by app.settings.settings.collectAsState()
    var selected by rememberSaveable { mutableStateOf(Page.HOME) }
    var refresh by remember { mutableIntStateOf(0) }
    var tile by remember { mutableStateOf<Tile?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val data = remember { HomeData(app) }
    val first = remember { FocusRequester() }
    fun select(t: Tile) {
        if (t.action is TileAction.PlayJellyfin || t.action is TileAction.OpenUri) tile = t
        else scope.launch {
            try { data.perform(t.action) } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message ?: "That action could not be completed." }
        }
    }
    BackHandler(enabled = tile == null) {
        if (selected != Page.HOME) selected = Page.HOME else scope.launch { runCatching { first.requestFocus() } }
    }
    VelaTheme(settings) {
        val colors = MaterialTheme.colorScheme
        Surface(Modifier.fillMaxSize(), color = colors.background) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Vela", style = MaterialTheme.typography.headlineMedium, fontFamily = FontFamily.Serif)
                    Spacer(Modifier.weight(1f))
                    val running by app.runner.running.collectAsState()
                    Text(if (running) "Working for you…" else "A little less searching. A little more living.", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    Spacer(Modifier.width(20.dp))
                    VelaButton("Ask Vela") { app.startActivity(Intent(app, AskActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
                LazyRow(Modifier.fillMaxWidth().padding(bottom = 8.dp), contentPadding = PaddingValues(horizontal = 40.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(Page.entries) { page ->
                        VelaButton(page.label, if (page == Page.HOME) Modifier.focusRequester(first) else Modifier, selected == page) { selected = page }
                    }
                }
                LaunchedEffect(Unit) { first.requestFocus() }
                Box(Modifier.weight(1f)) {
                    when (selected) {
                        Page.HOME -> HomeContent(app, resume + refresh, ::select) { selected = Page.SETTINGS }
                        Page.SEARCH -> SearchPage(app, ::select)
                        Page.APPS -> RowsPage(app, RowSpec("all-apps", "Your apps", Source.Apps(), TileStyle.APP), resume + refresh, ::select)
                        Page.LIST -> RowsPage(app, RowSpec("my-list", "My list", Source.Watchlist), resume + refresh, ::select)
                        Page.HOUSE -> HousePage(app, resume + refresh, ::select) { refresh++ }
                        Page.ACTIVITY -> ActivityPage(app)
                        Page.SETTINGS -> SettingsPage(app, resume) { refresh++ }
                    }
                }
            }
        }
        tile?.let { chosen -> TitleDialog(app, chosen, { tile = null }, { refresh++ }) { action ->
            scope.launch {
                try { data.perform(action); tile = null } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message ?: "Couldn't open this title." }
            }
        } }
        error?.let { message -> AlertDialog(onDismissRequest = { error = null }, title = { Text("Couldn't complete that") },
            text = { Text(message) }, confirmButton = { VelaButton("Dismiss") { error = null } }) }
    }
}

@Composable private fun RowsPage(app: VelaApp, spec: RowSpec, refresh: Int, onSelect: (Tile) -> Unit) {
    val data = remember { HomeData(app) }
    val settings by app.settings.settings.collectAsState()
    val saved by app.settings.watchlist.collectAsState()
    var row by remember(spec) { mutableStateOf<LoadedRow?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(spec, settings, saved, refresh, retry) { row = null; row = data.load(spec) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 40.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text(spec.title, style = MaterialTheme.typography.headlineMedium) }
        val loaded = row
        when {
            loaded == null -> item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            loaded.error != null -> item { EmptyState("Connection needs attention", loaded.error, "Retry", { retry++ }) }
            loaded.tiles.isEmpty() -> item { EmptyState(if (spec.source == Source.Watchlist) "Save something for later" else "No apps found", if (spec.source == Source.Watchlist) "Open a title in Home or Search and choose Save to my list." else "Installed TV apps will appear here.") }
            else -> items(loaded.tiles.chunked(4)) { tiles ->
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) { tiles.forEach { TileCard(it, spec.style, null, {}, onSelect) } }
            }
        }
    }
}

@Composable private fun SearchPage(app: VelaApp, onSelect: (Tile) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf("") }
    val data = remember { HomeData(app) }
    var apps by remember { mutableStateOf<LoadedRow?>(null) }
    var library by remember { mutableStateOf<LoadedRow?>(null) }
    val settings by app.settings.settings.collectAsState()
    LaunchedEffect(submitted, settings) {
        apps = null; library = null
        if (submitted.isBlank()) return@LaunchedEffect
        coroutineScope {
            launch { apps = data.load(RowSpec("search-apps", "Apps", Source.Apps(), TileStyle.APP)).let { it.copy(tiles = it.tiles.filter { t -> t.title.contains(submitted, true) }) } }
            launch { library = data.load(RowSpec("search-library", "Your library", Source.JellyfinSearch(submitted), TileStyle.POSTER)) }
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 40.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(query, { query = it.take(500) }, Modifier.weight(1f), label = { Text("Search titles and apps") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { submitted = query.trim() }))
                VelaButton("Search") { submitted = query.trim() }
            }
        }
        if (submitted.isBlank()) item { EmptyState("What are you in the mood for?", "Search your Jellyfin library and installed apps, or ask Vela to find something across your streaming apps.") }
        else {
            if (apps == null || library == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            for (row in listOfNotNull(apps, library)) {
                item(row.spec.id) {
                    if (row.error != null) EmptyState(row.spec.title, row.error)
                    else if (row.tiles.isEmpty()) EmptyState(row.spec.title, "No matches for “$submitted”.")
                    else TileRow(row, null, {}, onSelect)
                }
            }
            item { VelaButton("Ask Vela to find “${submitted.take(36)}”") { app.runner.ask("Find $submitted to watch. Ask me which title if ambiguous.") } }
        }
    }
}

@Composable private fun HousePage(app: VelaApp, refresh: Int, onSelect: (Tile) -> Unit, reload: () -> Unit) {
    val settings by app.settings.settings.collectAsState()
    var entities by remember { mutableStateOf<List<app.vela.tv.home.EntityState>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(settings, refresh) {
        entities = null; error = null
        try { entities = settings.homeAssistant()?.states()?.sortedBy { it.friendlyName ?: it.entityId }.orEmpty() }
        catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 40.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Row(verticalAlignment = Alignment.CenterVertically) { Text("A home that listens", style = MaterialTheme.typography.headlineMedium); Spacer(Modifier.weight(1f)); VelaButton("Refresh", onClick = reload) } }
        when {
            settings.homeAssistantUrl.isBlank() -> item { EmptyState("Connect your home", "Add Home Assistant from Settings → Connect phone to see devices and run scenes.") }
            error != null -> item { EmptyState("Home Assistant is unavailable", error.orEmpty(), "Retry", reload) }
            entities == null -> item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            entities!!.isEmpty() -> item { EmptyState("No devices yet", "Devices connected to Home Assistant will appear here.") }
            else -> items(entities!!.take(150), key = { it.entityId }) { entity ->
                val domain = entity.entityId.substringBefore('.')
                val direct = domain in setOf("light", "switch", "fan", "input_boolean") && entity.state !in setOf("unknown", "unavailable")
                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(entity.friendlyName ?: entity.entityId, style = MaterialTheme.typography.titleMedium); Text("$domain · ${entity.state}", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    VelaButton(if (direct) "Toggle" else "Ask Vela") {
                        onSelect(Tile("ha:${entity.entityId}", entity.friendlyName ?: entity.entityId,
                            if (direct) TileAction.HomeService(domain, "toggle", entity.entityId) else TileAction.AskAgent("Help me control ${entity.entityId}. Current state is ${entity.state}.")))
                    }
                }
            }
        }
    }
}

@Composable private fun ActivityPage(app: VelaApp) {
    val tasks by app.runner.tasks.collectAsState()
    val busy by app.runner.running.collectAsState()
    val lines by app.runner.lines.collectAsState()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 40.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Row(verticalAlignment = Alignment.CenterVertically) { Text("Working for you", style = MaterialTheme.typography.headlineMedium); Spacer(Modifier.weight(1f)); if (busy) VelaButton("Stop task") { app.runner.stop() } else VelaButton("Clear activity") { app.runner.clear() } } }
        if (tasks.isEmpty()) item { EmptyState("Nothing to catch up on", "Ask Vela to play a show, change your home screen, or help around the house. Activity stays on this device and clears when Vela restarts.") }
        if (busy) item { EmptyState("Current task", lines.lastOrNull()?.text ?: "Starting…") }
        items(tasks.asReversed(), key = { it.id }) { task ->
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(task.task, style = MaterialTheme.typography.titleMedium)
                Text("${task.state.replaceFirstChar { it.uppercase() }} · ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(task.startedAt))}", color = MaterialTheme.colorScheme.primary)
                task.detail?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (task.state != "running") VelaButton("Ask again") { app.runner.ask(task.task) }
            }
        }
    }
}

@Composable private fun SettingsPage(app: VelaApp, resume: Int, changed: () -> Unit) {
    val settings by app.settings.settings.collectAsState()
    val status = remember(resume, settings) { DeviceStatus.read(app) }
    fun open(action: String) { runCatching { app.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 40.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text("Make yourself at home", style = MaterialTheme.typography.headlineMedium) }
        item { PairCard(app, Modifier.fillMaxWidth().height(164.dp)) }
        item {
            Text("Connections & permissions", style = MaterialTheme.typography.titleLarge)
            Text("AI ${if (status.agentConfigured) "configured" else "needs setup"} · Jellyfin ${if (status.jellyfinConfigured) "configured" else "needs setup"} · Home Assistant ${if (status.homeConfigured) "configured" else "needs setup"}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            VelaButton(if (status.accessibility) "TV control enabled" else "Enable TV control") { open(Settings.ACTION_ACCESSIBILITY_SETTINGS) }
            VelaButton(if (status.mediaAccess) "Media access enabled" else "Enable media access") { open(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) }
            VelaButton("Android settings") { open(Settings.ACTION_SETTINGS) }
        } }
        item { Text("Appearance & accessibility", style = MaterialTheme.typography.titleLarge) }
        item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            VelaButton(if (settings.appearance == "night") "Theme: Night" else "Theme: Day") { app.settings.update(settings.copy(appearance = if (settings.appearance == "night") "day" else "night")) }
            VelaButton(if (settings.reduceMotion) "Motion: Reduced" else "Motion: Standard") { app.settings.update(settings.copy(reduceMotion = !settings.reduceMotion)) }
            VelaButton(if (settings.highContrast) "Contrast: High" else "Contrast: Standard") { app.settings.update(settings.copy(highContrast = !settings.highContrast)) }
        } }
        item { VelaButton(if (settings.ownHome) "Home button: Vela" else "Home button: System") { app.settings.update(settings.copy(ownHome = !settings.ownHome)) } }
        item { EmptyState("Your device", "${status.model} · Android API ${status.androidApi} · Vela ${status.version}\nApp surface: ${status.surfaceWidth} × ${status.surfaceHeight}. This is the app surface, not a claim about the panel.\n${if (status.nativeDpad) "Native remote actions available." else "Accessibility click fallback; verify on your device."}") }
        item { VelaButton("Refresh home", onClick = changed) }
    }
}
