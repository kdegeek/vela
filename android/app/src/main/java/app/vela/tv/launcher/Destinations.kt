package app.vela.tv.launcher

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.ImeAction
import app.vela.tv.VelaApp
import app.vela.tv.design.*
import app.vela.tv.layout.*
import kotlinx.coroutines.*

@Composable internal fun RowsPage(app: VelaApp, spec: RowSpec, refresh: Int, onSelect: (Tile) -> Unit) {
    val data = remember(app) { HomeData(app) }
    val settings by app.settings.settings.collectAsState()
    val saved by app.settings.watchlist.collectAsState()
    var row by remember(spec) { mutableStateOf<LoadedRow?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(spec, settings, saved, refresh, retry) { row = data.load(spec) }
    CardGrid(spec.style.cardFormat(), Modifier.fillMaxSize()) {
        fullSpanItem("heading") { Text(spec.title, style = Vela.type.headline, modifier = Modifier.semantics { heading() }) }
        val loaded = row
        when {
            loaded == null -> fullSpanItem("loading") { LoadingState("Loading ${spec.title.lowercase()}…") }
            loaded.error != null -> fullSpanItem("error") { StatusPanel("Connection needs attention", loaded.error, kind = StatusKind.Error, action = "Retry", onAction = { retry++ }) }
            loaded.tiles.isEmpty() -> fullSpanItem("empty") { EmptyState(if (spec.source == Source.Watchlist) "Save something for later" else "No apps found",
                if (spec.source == Source.Watchlist) "Open a title in Home or Search and choose Save to my list." else "Installed TV apps will appear here.") }
            else -> items(loaded.tiles, key = { it.key }) { tile -> TileCard(tile, spec.style, {}, onSelect) }
        }
    }
}

@Composable internal fun SearchPage(app: VelaApp, refresh: Int, onSelect: (Tile) -> Unit, ask: (String) -> Unit, onSettings: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf("") }
    val data = remember(app) { HomeData(app) }
    var apps by remember { mutableStateOf<LoadedRow?>(null) }
    var library by remember { mutableStateOf<LoadedRow?>(null) }
    var loading by remember { mutableStateOf(false) }
    val settings by app.settings.settings.collectAsState()
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(submitted, settings, retry, refresh) {
        if (submitted.isBlank()) return@LaunchedEffect
        loading = true
        try { coroutineScope {
            val appResult = async { data.load(RowSpec("search-apps", "Apps", Source.Apps(), TileStyle.APP)).let { it.copy(tiles = it.tiles.filter { t -> t.title.contains(submitted, true) }) } }
            val libraryResult = async { data.load(RowSpec("search-library", "Your library", Source.JellyfinSearch(submitted), TileStyle.POSTER)) }
            apps = appResult.await(); library = libraryResult.await()
        } } finally { loading = false }
    }
    VelaPage("Search", Modifier.fillMaxSize().imePadding(), inset = false) {
        insetItem("query") {
            Row(horizontalArrangement = Arrangement.spacedBy(VelaSpace.S), verticalAlignment = Alignment.CenterVertically) {
                VelaTextField(query, { query = it }, "Search titles and apps", Modifier.weight(1f), maxLength = 500,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { submitted = query.trim(); retry++ }))
                VelaButton("Search", enabled = query.isNotBlank(), variant = ButtonVariant.Primary) { submitted = query.trim(); retry++ }
            }
        }
        if (submitted.isBlank()) insetItem("empty") { EmptyState("What are you in the mood for?", "Search your Jellyfin library and installed apps, or ask Vela to find something across your streaming apps.") }
        else {
            insetItem("status") { if (loading) InlineStatus("Searching for “$submitted”…") }
            if (apps == null) item("apps-loading") { SkeletonRow("Apps", CardFormat.App) }
            if (library == null) item("library-loading") { SkeletonRow("Your library", CardFormat.Poster) }
            for (row in listOfNotNull(apps, library)) item(row.spec.id) {
                when {
                    row.spec.id == "search-library" && settings.jellyfin(app.settings.deviceId) == null -> Box(Modifier.safeHorizontal()) {
                        EmptyState("Connect your library", "Add Jellyfin from your paired phone in Settings. Installed app search is available now.", "Settings", onSettings)
                    }
                    row.error != null || row.tiles.isNotEmpty() -> TileRow(row, {}, onSelect, onRetry = { retry++ })
                    else -> RowStatusStrip(row.spec.title, row.spec.style.cardFormat(), StatusKind.Empty, "No matches for “$submitted”.")
                }
            }
            insetItem("ask") { VelaButton("Ask Vela to find “${submitted.take(36)}”", variant = ButtonVariant.Agent) { ask("Find $submitted to watch. Ask me which title if ambiguous.") } }
        }
    }
}

@Composable internal fun HousePage(app: VelaApp, refresh: Int, onSelect: (Tile) -> Unit, onSettings: () -> Unit, reload: () -> Unit) {
    val settings by app.settings.settings.collectAsState()
    var entities by remember { mutableStateOf<List<app.vela.tv.home.EntityState>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(settings, refresh) {
        error = null
        try { entities = settings.homeAssistant()?.states()?.sortedBy { it.friendlyName ?: it.entityId }.orEmpty() }
        catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message ?: "Couldn't reach Home Assistant." }
    }
    VelaPage("A home that listens", Modifier.fillMaxSize(), actions = { VelaButton("Refresh", onClick = reload) }) {
        when {
            settings.homeAssistant() == null -> item("setup") { EmptyState("Connect your home", "Add Home Assistant from your paired phone in Settings to see devices and run scenes.", "Settings", onSettings) }
            error != null -> item("error") { StatusPanel("Home Assistant is unavailable", error.orEmpty(), kind = StatusKind.Error, action = "Retry", onAction = reload) }
            entities == null -> item("loading") { LoadingState("Loading your home devices…") }
            entities!!.isEmpty() -> item("empty") { EmptyState("No devices yet", "Devices connected to Home Assistant will appear here.") }
            else -> items(entities!!.take(150), key = { it.entityId }) { entity ->
                val domain = entity.entityId.substringBefore('.')
                val direct = domain in setOf("light", "switch", "fan", "input_boolean") && entity.state !in setOf("unknown", "unavailable")
                VelaListRow(entity.friendlyName ?: entity.entityId, supporting = "$domain · ${entity.state}", glyph = VelaGlyph.House) {
                    VelaButton(if (direct) "Toggle" else "Ask Vela", variant = if (direct) ButtonVariant.Secondary else ButtonVariant.Agent) {
                        onSelect(Tile("ha:${entity.entityId}", entity.friendlyName ?: entity.entityId,
                            if (direct) TileAction.HomeService(domain, "toggle", entity.entityId) else TileAction.AskAgent("Help me control ${entity.entityId}. Current state is ${entity.state}.")))
                    }
                }
            }
        }
    }
}
