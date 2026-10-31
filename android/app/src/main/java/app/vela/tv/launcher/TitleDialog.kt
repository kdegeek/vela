package app.vela.tv.launcher

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.vela.tv.VelaApp
import app.vela.tv.design.VelaButton
import app.vela.tv.layout.PinnedItem
import app.vela.tv.layout.TileAction
import app.vela.tv.media.JellyfinItem
import kotlinx.coroutines.CancellationException

@Composable internal fun TitleDialog(app: VelaApp, tile: Tile, dismiss: () -> Unit, changed: () -> Unit, play: (TileAction) -> Unit) {
    val saved by app.settings.watchlist.collectAsState()
    val isSaved = saved.any { it.action == tile.action }
    val action = tile.action
    var item by remember(tile.key) { mutableStateOf<JellyfinItem?>(null) }
    var episodes by remember(tile.key) { mutableStateOf<List<JellyfinItem>>(emptyList()) }
    var loading by remember(tile.key) { mutableStateOf(action is TileAction.PlayJellyfin) }
    var error by remember(tile.key) { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(tile.key, retry) {
        if (action !is TileAction.PlayJellyfin) return@LaunchedEffect
        loading = true; error = null
        try {
            val jf = app.settings.settings.value.jellyfin(app.settings.deviceId) ?: error("Connect Jellyfin in Settings.")
            item = jf.item(action.itemId)
            if (item?.type == "Series") episodes = jf.episodes(action.itemId)
        } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message ?: "Couldn't load this title." }
        finally { loading = false }
    }
    AlertDialog(onDismissRequest = dismiss, title = { Text(item?.name ?: tile.title) },
        text = {
            LazyColumn(Modifier.heightIn(max = 260.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Text(tile.subtitle.orEmpty(), color = MaterialTheme.colorScheme.primary) }
                item { Text(item?.overview ?: tile.overview ?: "Choose Open to continue in the app.") }
                if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error); VelaButton("Retry") { retry++ } } }
                if (!loading && item?.type == "Series" && episodes.isEmpty() && error == null) item { Text("No episodes available in this library.") }
                items(episodes, key = { it.id }) { episode ->
                    VelaButton("${if (episode.positionTicks != null && episode.positionTicks > 0) "Resume" else "Play"} · ${episode.name}") { play(TileAction.PlayJellyfin(episode.id)) }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VelaButton(if (isSaved) "Remove from my list" else "Save to my list") {
                    val pin = PinnedItem(tile.title, tile.action, tile.subtitle, tile.image as? String)
                    if (isSaved) app.settings.removeTitle(pin) else app.settings.saveTitle(pin)
                    changed()
                }
                if (action !is TileAction.PlayJellyfin || (!loading && error == null && item?.type in setOf("Movie", "Episode", "Video"))) {
                    VelaButton(if ((item?.positionTicks ?: 0L) > 0) "Resume" else "Open") { play(action) }
                }
            }
        }, dismissButton = { VelaButton("Back", onClick = dismiss) })
}
