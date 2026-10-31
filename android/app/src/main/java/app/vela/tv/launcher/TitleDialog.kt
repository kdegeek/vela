package app.vela.tv.launcher

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import app.vela.tv.VelaApp
import app.vela.tv.design.*
import app.vela.tv.layout.PinnedItem
import app.vela.tv.layout.TileAction
import app.vela.tv.media.JellyfinItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun TitleDialog(app: VelaApp, tile: Tile, dismiss: () -> Unit, changed: () -> Unit, opening: Boolean = false, play: (TileAction) -> Unit) {
    val saved by app.settings.watchlist.collectAsState()
    val settings by app.settings.settings.collectAsState()
    val isSaved = saved.any { it.action == tile.action }
    val action = tile.action
    var item by remember(tile.key) { mutableStateOf<JellyfinItem?>(null) }
    var episodes by remember(tile.key) { mutableStateOf<List<JellyfinItem>>(emptyList()) }
    var seasons by remember(tile.key) { mutableStateOf<List<JellyfinItem>>(emptyList()) }
    var selectedSeason by remember(tile.key) { mutableStateOf<String?>(null) }
    var episodesLoading by remember(tile.key) { mutableStateOf(false) }
    var episodesError by remember(tile.key) { mutableStateOf<String?>(null) }
    var nextPage by remember(tile.key) { mutableStateOf<Int?>(null) }
    var episodeRetry by remember(tile.key) { mutableIntStateOf(0) }
    var moreLoading by remember(tile.key) { mutableStateOf(false) }
    var loading by remember(tile.key) { mutableStateOf(action is TileAction.PlayJellyfin) }
    var error by remember(tile.key) { mutableStateOf<String?>(null) }
    var saveError by remember(tile.key) { mutableStateOf<String?>(null) }
    var retry by remember(tile.key) { mutableIntStateOf(0) }
    val back = remember { FocusRequester() }
    LaunchedEffect(tile.key, retry, settings) {
        if (action !is TileAction.PlayJellyfin) return@LaunchedEffect
        loading = true; error = null; item = null; episodes = emptyList(); seasons = emptyList()
        try {
            val jf = settings.jellyfin(app.settings.deviceId) ?: error("Connect Jellyfin from your phone in Settings.")
            val loaded = jf.item(action.itemId)
            item = loaded
            if (loaded.type == "Series") seasons = jf.seasons(action.itemId)
        } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message ?: "Couldn't load this title." }
        finally { loading = false }
    }
    LaunchedEffect(item?.id, selectedSeason, episodeRetry, settings) {
        val series = item?.takeIf { it.type == "Series" } ?: return@LaunchedEffect
        episodesLoading = true; episodesError = null; episodes = emptyList(); nextPage = null
        try {
            val jf = settings.jellyfin(app.settings.deviceId) ?: error("Connect Jellyfin from your phone in Settings.")
            val page = jf.episodesPage(series.id, limit = 50, seasonId = selectedSeason)
            episodes = page.items.distinctBy { it.id }; nextPage = page.nextStartIndex
        } catch (e: CancellationException) { throw e } catch (e: Exception) { episodesError = e.message ?: "Couldn't load episodes." }
        finally { episodesLoading = false }
    }
    // A page belongs to its series and season. Leaving that filter cancels its in-flight append.
    val pageScope = rememberCoroutineScope()
    var moreJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var moreGeneration by remember { mutableIntStateOf(0) }
    DisposableEffect(item?.id, selectedSeason, episodeRetry, settings) {
        onDispose { moreGeneration++; moreJob?.cancel(); moreLoading = false }
    }
    fun loadMore() {
        val offset = nextPage ?: return
        val seriesId = item?.id ?: return
        if (moreLoading) return
        val season = selectedSeason
        val connection = settings
        val generation = ++moreGeneration
        moreLoading = true; episodesError = null
        moreJob = pageScope.launch {
            try {
                val jf = settings.jellyfin(app.settings.deviceId) ?: error("Connect Jellyfin from your phone in Settings.")
                val page = jf.episodesPage(seriesId, startIndex = offset, limit = 50, seasonId = season)
                if (generation != moreGeneration || item?.id != seriesId || selectedSeason != season || settings != connection) return@launch
                val known = episodes.map { it.id }.toSet()
                val fresh = page.items.filterNot { it.id in known }.distinctBy { it.id }
                check(page.items.isEmpty() || fresh.isNotEmpty()) { "The library repeated an episode page. Try refreshing this title." }
                episodes = episodes + fresh; nextPage = page.nextStartIndex
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                if (generation == moreGeneration && selectedSeason == season) episodesError = e.message ?: "Couldn't load more episodes."
            } finally { if (generation == moreGeneration) moreLoading = false }
        }
    }
    val content = titleContent(loading, error != null, item?.type, item?.missing == true)
    VelaSheet(item?.name ?: tile.title, dismiss, back, headerActions = {
        VelaButton("Back", Modifier.focusRequester(back), onClick = dismiss)
        VelaButton(if (isSaved) "Remove from my list" else "Save to my list", enabled = !opening) {
            runCatching {
                val pin = PinnedItem(tile.title, tile.action, tile.subtitle, tile.image as? String)
                if (isSaved) app.settings.removeTitle(pin) else app.settings.saveTitle(pin)
                saveError = null; changed()
            }.onFailure { saveError = it.message ?: "Couldn't save your list. Try again." }
        }
    }) {
        tile.subtitle?.takeIf { it.isNotBlank() }?.let { subtitle -> item("subtitle") { Text(subtitle, color = Vela.colors.accent, maxLines = 2, overflow = TextOverflow.Ellipsis) } }
        item("overview") { Text(item?.overview?.takeIf { it.isNotBlank() } ?: tile.overview ?: if (action is TileAction.OpenUri) "Open this title in its app." else "Details from your library.", maxLines = 4, overflow = TextOverflow.Ellipsis) }
        saveError?.let { message -> item("save-error") { Text(message, color = Vela.colors.danger, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) } }
        if (opening) item("opening") { LoadingState("Opening your selection…") }
        if (action is TileAction.OpenUri) item("open") { VelaButton("Open in app", enabled = !opening, busy = opening, variant = ButtonVariant.Primary) { play(action) } }
        if (action is TileAction.PlayJellyfin) when (content) {
            TitleContent.LOADING -> item("loading") { LoadingState("Loading title details…") }
            TitleContent.ERROR -> item("error") { EmptyState("Couldn't load this title", error.orEmpty(), "Retry") { retry++ } }
            TitleContent.EPISODES -> {
                item("episodes") { Text("Episodes", style = Vela.type.section, modifier = Modifier.semantics { heading() }) }
                if (seasons.isNotEmpty()) item("seasons") {
                    LazyRow(Modifier.focusRestorer(), horizontalArrangement = Arrangement.spacedBy(VelaSpace.Xs), contentPadding = PaddingValues(VelaSpace.Xxs)) {
                        item("all") { VelaChoiceChip("All seasons", selectedSeason == null, { selectedSeason = null }) }
                        items(seasons, key = { it.id }) { season -> VelaChoiceChip(season.name, selectedSeason == season.id, { selectedSeason = season.id }) }
                    }
                }
                if (episodesLoading) item("episodes-loading") { LoadingState("Loading episodes…") }
                if (!episodesLoading && episodes.isEmpty() && episodesError == null) item("empty") { EmptyState("No episodes available", "Refresh after adding episodes, or choose another season.", "Refresh") { episodeRetry++ } }
                items(episodes, key = { "episode:${it.id}" }) { episode ->
                    val context = listOfNotNull(episode.seasonNumber?.let { "Season $it" }, episode.episodeNumber?.let { "Episode $it" }).joinToString(" · ")
                    VelaButton(listOf(context.takeIf { it.isNotBlank() }, episode.name, if (!episode.playable) "Unavailable" else if (episode.resumePositionMs > 0) "Resume" else "Play").filterNotNull().joinToString(" · "),
                        Modifier.fillMaxWidth(), enabled = !opening && episode.playable, busy = opening, variant = ButtonVariant.Primary) { play(TileAction.PlayJellyfin(episode.id)) }
                }
                episodesError?.let { message -> item("episodes-error") {
                    Text(message, color = Vela.colors.danger, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    VelaButton(if (nextPage == null) "Retry episodes" else "Refresh episodes") { episodeRetry++ }
                } }
                if (nextPage != null) item("more-episodes") {
                    VelaButton(if (moreLoading) "Loading more episodes…" else if (episodesError != null) "Retry more episodes" else "More episodes", enabled = !opening, busy = moreLoading) { loadMore() }
                }
            }
            TitleContent.PLAYABLE -> item("play") { VelaButton(if ((item?.resumePositionMs ?: 0L) > 0L) "Resume" else "Play", enabled = !opening, busy = opening, variant = ButtonVariant.Primary) { play(action) } }
            TitleContent.UNSUPPORTED -> item("unsupported") { EmptyState("This title can't play here", "Choose a movie or episode from your library. You can still save this title for later.") }
        }
    }
}
