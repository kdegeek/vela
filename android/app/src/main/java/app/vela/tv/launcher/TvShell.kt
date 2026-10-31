package app.vela.tv.launcher

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.vela.tv.DeviceStatus
import app.vela.tv.VelaApp
import app.vela.tv.profile.HouseholdState
import app.vela.tv.design.*
import app.vela.tv.layout.*
import kotlinx.coroutines.*
import java.text.DateFormat
import java.util.Date

private enum class Page(val label: String) {
    HOME("Home"), SEARCH("Search"), APPS("Apps"), LIST("My list"), HOUSE("House"), ACTIVITY("Activity"), SETTINGS("Settings"), PROFILES("Profiles"), ROUTINES("Routines"), CONTROL("External control")
}

@Composable internal fun TvShell(app: VelaApp, resume: Int) {
    val settings by app.settings.settings.collectAsState()
    var selected by rememberSaveable { mutableStateOf(Page.HOME) }
    var refresh by remember { mutableIntStateOf(0) }
    var tile by remember { mutableStateOf<Tile?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var failedAction by remember { mutableStateOf<TileAction?>(null) }
    var opening by remember { mutableStateOf(false) }
    var nowPlayingDetails by rememberSaveable { mutableStateOf(false) }
    val pages = rememberSaveableStateHolder()
    val scope = rememberCoroutineScope()
    val data = remember { HomeData(app) }
    val first = remember { FocusRequester() }
    val navigation = rememberLazyListState()
    val household by app.householdReady.collectAsState()
    val householdState = household?.state?.collectAsState()?.value
    val identity = (householdState as? HouseholdState.Ready)?.document?.activeProfile?.displayName
    val runtime by app.runtimeStatus.collectAsState()
    val revision = (householdState as? HouseholdState.Ready)?.document?.revision
    LaunchedEffect(revision) { tile = null; failedAction = null; refresh++ }
    fun perform(action: TileAction, closeTitle: Boolean = false) {
        if (opening) return
        opening = true
        val originatingTile = tile?.key
        scope.launch {
            try { data.perform(action); if (closeTitle && tile?.key == originatingTile) tile = null; failedAction = null }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { failedAction = action.takeIf { it is TileAction.LaunchApp || it is TileAction.OpenUri || it is TileAction.PlayJellyfin }; error = e.message ?: "That action could not be completed." }
            finally { opening = false }
        }
    }
    fun select(t: Tile) {
        if (t.action is TileAction.PlayJellyfin || t.action is TileAction.OpenUri) tile = t else perform(t.action)
    }
    fun ask(prompt: String) {
        runCatching { app.runner.ask(prompt); selected = Page.ACTIVITY }
            .onFailure { failedAction = null; error = it.message ?: "Couldn't start the task." }
    }
    BackHandler(enabled = tile == null && error == null && !nowPlayingDetails) {
        selected = Page.HOME
        scope.launch { navigation.scrollToItem(0); withFrameNanos { }; first.requestFocus() }
    }
    VelaTheme(settings) {
        val colors = MaterialTheme.colorScheme
        Surface(Modifier.fillMaxSize(), color = colors.background) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Vela", style = MaterialTheme.typography.headlineMedium, fontFamily = FontFamily.Serif)
                    Spacer(Modifier.width(24.dp))
                    val running by app.runner.running.collectAsState()
                    Text(if (running) "Working for you…" else "A little less searching. A little more living.", modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    Spacer(Modifier.width(20.dp))
                    VelaButton(identity?.let { "Profile: $it" } ?: if (runtime.householdError != null || householdState is HouseholdState.Unavailable) "Profiles unavailable" else "Profiles loading…", Modifier.widthIn(max = 220.dp)) { selected = Page.PROFILES }
                    Spacer(Modifier.width(12.dp))
                    VelaButton("Ask Vela") { app.startActivity(Intent(app, AskActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
                LazyRow(Modifier.fillMaxWidth().focusRestorer().padding(bottom = 8.dp), state = navigation, contentPadding = PaddingValues(horizontal = 40.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(Page.entries, key = { it.name }) { page ->
                        VelaButton(page.label, if (page == Page.HOME) Modifier.focusRequester(first) else Modifier, selected == page) { selected = page }
                    }
                }
                LaunchedEffect(Unit) { first.requestFocus() }
                Box(Modifier.weight(1f)) {
                    pages.SaveableStateProvider(selected.name) {
                        when (selected) {
                            Page.HOME -> HomeContent(app, resume + refresh, ::select) { selected = Page.SETTINGS }
                            Page.SEARCH -> SearchPage(app, refresh, ::select, ::ask) { selected = Page.SETTINGS }
                            Page.APPS -> RowsPage(app, RowSpec("all-apps", "Your apps", Source.Apps(), TileStyle.APP), resume + refresh, ::select)
                            Page.LIST -> RowsPage(app, RowSpec("my-list", "My list", Source.Watchlist), resume + refresh, ::select)
                            Page.HOUSE -> HousePage(app, resume + refresh, ::select, { selected = Page.SETTINGS }) { refresh++ }
                            Page.ACTIVITY -> ActivityPage(app, ::ask)
                            Page.SETTINGS -> SettingsPage(app, resume, { failedAction = null; error = it },
                                { selected = Page.PROFILES }, { selected = Page.ROUTINES }, { selected = Page.CONTROL },
                                { nowPlayingDetails = true }) { refresh++ }
                            Page.PROFILES -> ProfilesPage(app)
                            Page.ROUTINES -> RoutinesPage(app)
                            Page.CONTROL -> ExternalControlPage(app)
                        }
                    }
                }
                NowPlayingSurface(app, resume, nowPlayingDetails, { nowPlayingDetails = true }, { nowPlayingDetails = false })
            }
        }
        tile?.let { chosen -> TitleDialog(app, chosen, { tile = null }, { refresh++ }, opening) { action -> perform(action, true) } }
        error?.let { message -> AlertDialog(onDismissRequest = { error = null }, title = { Text("Couldn't complete that") },
            text = { Text(message) }, confirmButton = {
                failedAction?.let { action -> VelaButton("Retry") { error = null; perform(action, tile != null) } }
            }, dismissButton = { VelaButton("Back") { error = null } }) }

    }
}

@Composable private fun RowsPage(app: VelaApp, spec: RowSpec, refresh: Int, onSelect: (Tile) -> Unit) {
    val data = remember { HomeData(app) }
    val settings by app.settings.settings.collectAsState()
    val saved by app.settings.watchlist.collectAsState()
    var row by remember(spec) { mutableStateOf<LoadedRow?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(spec, settings, saved, refresh, retry) { row = data.load(spec) }
    val cardWidth = when (spec.style) { TileStyle.POSTER -> 112.dp; TileStyle.APP -> 160.dp; TileStyle.WIDE -> 224.dp }
    LazyVerticalGrid(columns = GridCells.Adaptive(cardWidth + 16.dp), modifier = Modifier.fillMaxSize().focusRestorer(),
        contentPadding = PaddingValues(horizontal = 40.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) { Text(spec.title, modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium) }
        val loaded = row
        when {
            loaded == null -> item(span = { GridItemSpan(maxLineSpan) }) { LoadingState("Loading ${spec.title.lowercase()}…") }
            loaded.error != null -> item(span = { GridItemSpan(maxLineSpan) }) { EmptyState("Connection needs attention", loaded.error, "Retry", { retry++ }) }
            loaded.tiles.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) { EmptyState(if (spec.source == Source.Watchlist) "Save something for later" else "No apps found", if (spec.source == Source.Watchlist) "Open a title in Home or Search and choose Save to my list." else "Installed TV apps will appear here.") }
            else -> items(loaded.tiles, key = { it.key }) { tile -> TileCard(tile, spec.style, null, {}, onSelect) }
        }
    }
}

@Composable private fun SearchPage(app: VelaApp, refresh: Int, onSelect: (Tile) -> Unit, ask: (String) -> Unit, onSettings: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf("") }
    val data = remember { HomeData(app) }
    var apps by remember { mutableStateOf<LoadedRow?>(null) }
    var library by remember { mutableStateOf<LoadedRow?>(null) }
    val settings by app.settings.settings.collectAsState()
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(submitted, settings, retry, refresh) {
        apps = null; library = null
        if (submitted.isBlank()) return@LaunchedEffect
        coroutineScope {
            launch { apps = data.load(RowSpec("search-apps", "Apps", Source.Apps(), TileStyle.APP)).let { it.copy(tiles = it.tiles.filter { t -> t.title.contains(submitted, true) }) } }
            launch { library = data.load(RowSpec("search-library", "Your library", Source.JellyfinSearch(submitted), TileStyle.POSTER)) }
        }
    }
    LazyColumn(Modifier.fillMaxSize().focusRestorer(), state = rememberLazyListState(), contentPadding = PaddingValues(horizontal = 40.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(query, { query = it.take(500) }, Modifier.weight(1f), label = { Text("Search titles and apps") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { submitted = query.trim(); retry++ }))
                VelaButton("Search", enabled = query.isNotBlank()) { submitted = query.trim(); retry++ }
            }
        }
        if (submitted.isBlank()) item { EmptyState("What are you in the mood for?", "Search your Jellyfin library and installed apps, or ask Vela to find something across your streaming apps.") }
        else {
            if (apps == null || library == null) item { LoadingState("Searching for “$submitted”…") }
            for (row in listOfNotNull(apps, library)) {
                item(row.spec.id) {
                    if (row.spec.id == "search-library" && settings.jellyfin(app.settings.deviceId) == null) EmptyState("Connect your library", "Add Jellyfin from your paired phone in Settings. Installed app search is available now.", "Settings", onSettings)
                    else if (row.error != null) EmptyState(row.spec.title, row.error, "Retry") { retry++ }
                    else if (row.tiles.isEmpty()) EmptyState(row.spec.title, "No matches for “$submitted”.")
                    else TileRow(row, null, {}, onSelect)
                }
            }
            item { VelaButton("Ask Vela to find “${submitted.take(36)}”") { ask("Find $submitted to watch. Ask me which title if ambiguous.") } }
        }
    }
}

@Composable private fun HousePage(app: VelaApp, refresh: Int, onSelect: (Tile) -> Unit, onSettings: () -> Unit, reload: () -> Unit) {
    val settings by app.settings.settings.collectAsState()
    var entities by remember { mutableStateOf<List<app.vela.tv.home.EntityState>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(settings, refresh) {
        entities = null; error = null
        try { entities = settings.homeAssistant()?.states()?.sortedBy { it.friendlyName ?: it.entityId }.orEmpty() }
        catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message ?: "Couldn't reach Home Assistant." }
    }
    LazyColumn(Modifier.fillMaxSize().focusRestorer(), state = rememberLazyListState(), contentPadding = PaddingValues(horizontal = 40.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Row(verticalAlignment = Alignment.CenterVertically) { Text("A home that listens", style = MaterialTheme.typography.headlineMedium); Spacer(Modifier.weight(1f)); VelaButton("Refresh", onClick = reload) } }
        when {
            settings.homeAssistant() == null -> item { EmptyState("Connect your home", "Add Home Assistant from your paired phone in Settings to see devices and run scenes.", "Settings", onSettings) }
            error != null -> item { EmptyState("Home Assistant is unavailable", error.orEmpty(), "Retry", reload) }
            entities == null -> item { LoadingState("Loading your home devices…") }
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

@Composable private fun ActivityPage(app: VelaApp, ask: (String) -> Unit) {
    val tasks by app.runner.tasks.collectAsState()
    val busy by app.runner.running.collectAsState()
    val lines by app.runner.lines.collectAsState()
    LazyColumn(Modifier.fillMaxSize().focusRestorer(), state = rememberLazyListState(), contentPadding = PaddingValues(horizontal = 40.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Row(verticalAlignment = Alignment.CenterVertically) { Text("Working for you", style = MaterialTheme.typography.headlineMedium); Spacer(Modifier.weight(1f)); if (busy) VelaButton("Stop task") { app.runner.stop() } else VelaButton("Clear activity") { app.runner.clear() } } }
        if (tasks.isEmpty()) item { EmptyState("Nothing to catch up on", "Ask Vela to play a show, change your home screen, or help around the house. Activity stays on this device and clears when Vela restarts.") }
        if (busy) item { EmptyState("Current task", lines.lastOrNull()?.text ?: "Starting…") }
        items(tasks.asReversed(), key = { it.id }) { task ->
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(task.task, style = MaterialTheme.typography.titleMedium)
                Text("${task.state.replaceFirstChar { it.uppercase() }} · ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(task.startedAt))}", color = MaterialTheme.colorScheme.primary)
                task.detail?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (task.state != "running") VelaButton("Ask again") { ask(task.task) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun SettingsPage(app: VelaApp, resume: Int, reportError: (String) -> Unit, profiles: () -> Unit, routines: () -> Unit, external: () -> Unit, nowPlaying: () -> Unit, changed: () -> Unit) {
    val settings by app.settings.settings.collectAsState()
    val status = remember(resume, settings) { DeviceStatus.read(app) }
    fun open(action: String) { runCatching { app.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { reportError("Android couldn't open these settings. Try Android settings instead.") } }
    fun update(value: app.vela.tv.settings.VelaSettings) { runCatching { app.settings.update(value) }.onFailure { reportError(it.message ?: "Couldn't save your preferences.") } }
    LazyColumn(Modifier.fillMaxSize().focusRestorer(), state = rememberLazyListState(), contentPadding = PaddingValues(horizontal = 40.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text("Make yourself at home", style = MaterialTheme.typography.headlineMedium) }
        item { PairCard(app, Modifier.fillMaxWidth()) }
        item { FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            VelaButton("Profiles & explicit memory", onClick = profiles)
            VelaButton("Durable routines", onClick = routines)
            VelaButton("External agent control", onClick = external)
            VelaButton("Now Playing", onClick = nowPlaying)
        } }
        item {
            Text("Connections & permissions", style = MaterialTheme.typography.titleLarge)
            Text("AI ${if (status.agentConfigured) "configured" else "needs setup"} · Jellyfin ${if (status.jellyfinConfigured) "configured" else "needs setup"} · Home Assistant ${if (status.homeConfigured) "configured" else "needs setup"}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item { FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            VelaButton(if (status.accessibility) "TV control enabled" else "Enable TV control") { open(Settings.ACTION_ACCESSIBILITY_SETTINGS) }
            VelaButton(if (status.mediaAccess) "Media access enabled" else "Enable media access") { open(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) }
            VelaButton("Android settings") { open(Settings.ACTION_SETTINGS) }
        } }
        item { Text("Appearance & accessibility", style = MaterialTheme.typography.titleLarge) }
        item { FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            VelaButton(if (settings.appearance == "night") "Theme: Night" else "Theme: Day") { update(settings.copy(appearance = if (settings.appearance == "night") "day" else "night")) }
            VelaButton(if (settings.reduceMotion) "Motion: Reduced" else "Motion: Standard") { update(settings.copy(reduceMotion = !settings.reduceMotion)) }
            VelaButton(if (settings.highContrast) "Contrast: High" else "Contrast: Standard") { update(settings.copy(highContrast = !settings.highContrast)) }
        } }
        item { VelaButton(if (settings.ownHome) "Home button: Vela" else "Home button: System") { update(settings.copy(ownHome = !settings.ownHome)) } }
        item { EmptyState("Your device", "${status.model} · Android API ${status.androidApi} · Vela ${status.version}\nApp surface: ${status.surfaceWidth} × ${status.surfaceHeight}. This is the app surface, not a claim about the panel.\n${if (status.nativeDpad) "Native remote actions available." else "Accessibility click fallback; verify on your device."}") }
        item { VelaButton("Refresh home", onClick = changed) }
    }
}
