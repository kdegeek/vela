package app.vela.tv.launcher

import android.content.Intent
import android.net.Uri
import app.vela.tv.VelaApp
import app.vela.tv.agent.TvTools
import app.vela.tv.layout.RowSpec
import app.vela.tv.layout.Source
import app.vela.tv.layout.TileAction
import app.vela.tv.layout.TileStyle
import app.vela.tv.media.JellyfinClient.ImageType
import app.vela.tv.media.JellyfinItem
import app.vela.tv.player.PlayerActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** One tile, ready to draw. [image] is anything Coil can load: a URL or a Drawable (app banners). */
data class Tile(
    val key: String,
    val title: String,
    val action: TileAction,
    val subtitle: String? = null,
    val image: Any? = null,
    val backdrop: String? = null,
    val kicker: String? = null,
    val overview: String? = null,
    val progress: Float? = null,
)

data class LoadedRow(val spec: RowSpec, val tiles: List<Tile>, val error: String? = null)

/** Fills layout rows from their sources. A source that fails shows its error in place of tiles; the rest still load. */
class HomeData(private val app: VelaApp) {
    private val tools = TvTools(app)

    suspend fun load(spec: RowSpec): LoadedRow = try {
        LoadedRow(spec, withContext(Dispatchers.IO) { tiles(spec) })
    } catch (e: CancellationException) { throw e
    } catch (e: Exception) {
        LoadedRow(spec, emptyList(), e.message ?: e.toString())
    }

    private suspend fun tiles(spec: RowSpec): List<Tile> {
        val s = app.settings.settings.value
        return when (val src = spec.source) {
            is Source.Apps -> {
                val installed = tools.tvApps()
                val chosen = src.packages?.mapNotNull { p -> installed.firstOrNull { it.first == p } } ?: installed
                chosen.map { (pkg, label) ->
                    val banner = runCatching { app.packageManager.getApplicationBanner(pkg) ?: app.packageManager.getApplicationIcon(pkg) }.getOrNull()
                    Tile("app:$pkg", label, TileAction.LaunchApp(pkg), image = banner)
                }
            }
            is Source.JellyfinResume -> jellyfin(spec)?.let { jf -> jf.resume(20).map { it.tile(spec, "Continue watching", jf) } }.orEmpty()
            is Source.JellyfinLatest -> jellyfin(spec)?.let { jf -> jf.latest(20, src.libraryName).map { it.tile(spec, "Recently added", jf) } }.orEmpty()
            is Source.JellyfinSearch -> jellyfin(spec)?.let { jf -> jf.search(src.query, 20).map { it.tile(spec, spec.title, jf) } }.orEmpty()
            is Source.Watchlist -> app.settings.watchlist.value.mapIndexed { i, p ->
                Tile("saved:$i", p.title, p.action, p.subtitle, p.imageUrl, p.imageUrl, "My list")
            }
            is Source.Pinned -> src.items.mapIndexed { i, p ->
                Tile("pin:${spec.id}:$i", p.title, p.action, subtitle = p.subtitle, image = p.imageUrl, backdrop = p.imageUrl, kicker = spec.title)
            }
            is Source.HomeEntities -> {
                val ha = s.homeAssistant() ?: throw IllegalStateException("Home Assistant isn't set up")
                src.entityIds.mapNotNull { id ->
                    ha.state(id)?.let { e ->
                        Tile("ha:$id", e.friendlyName ?: id, TileAction.HomeService(id.substringBefore('.'), "toggle", id), subtitle = e.state, kicker = "Home")
                    }
                }
            }
        }
    }

    private fun jellyfin(spec: RowSpec) = app.settings.settings.value.jellyfin(app.settings.deviceId)
        ?: if (spec.id in setOf("resume", "latest")) null else throw IllegalStateException("Jellyfin isn't set up")

    private fun JellyfinItem.tile(spec: RowSpec, kicker: String, jf: app.vela.tv.media.JellyfinClient): Tile {
        val poster = spec.style == TileStyle.POSTER
        val art = when {
            poster && hasPrimary -> jf.imageUrl(id, ImageType.Primary, 704)
            hasBackdrop -> jf.imageUrl(id, ImageType.Backdrop, 1280)
            hasPrimary -> jf.imageUrl(id, ImageType.Primary, 704)
            else -> null
        }
        val meta = listOfNotNull(year?.toString(), runtimeMinutes?.let { "$it min" }, genres.take(2).joinToString(" · ").ifEmpty { null })
        return Tile(
            key = "jf:$id",
            title = seriesName ?: name,
            subtitle = if (seriesName != null) name else meta.joinToString(" · "),
            action = TileAction.PlayJellyfin(id),
            image = art,
            backdrop = if (hasBackdrop) jf.imageUrl(id, ImageType.Backdrop, 3840) else null,
            kicker = kicker,
            overview = overview,
            progress = progress?.toFloat(),
        )
    }

    /** Runs a tile's action. The owner chose it with the remote, so no guard applies. */
    suspend fun perform(action: TileAction) {
        when (action) {
            is TileAction.LaunchApp -> (app.packageManager.getLeanbackLaunchIntentForPackage(action.packageName)
                ?: app.packageManager.getLaunchIntentForPackage(action.packageName) ?: error("That app is not installed." )).let { start(it) }
            is TileAction.OpenUri -> start(Intent(Intent.ACTION_VIEW, Uri.parse(action.uri)).apply { action.packageName?.let { setPackage(it) } })
            is TileAction.PlayJellyfin -> start(PlayerActivity.intent(app, action.itemId))
            is TileAction.AskAgent -> app.runner.ask(action.prompt)
            is TileAction.HomeService -> withContext(Dispatchers.IO) {
                (app.settings.settings.value.homeAssistant() ?: error("Connect Home Assistant in Settings.")).call(
                    action.domain, action.service,
                    buildJsonObject { put("entity_id", JsonArray(listOf(JsonPrimitive(action.entityId)))) },
                )
            }
        }
    }

    private fun start(i: Intent) = app.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
