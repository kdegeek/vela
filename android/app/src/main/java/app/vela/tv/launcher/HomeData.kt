package app.vela.tv.launcher

import app.vela.tv.VelaApp
import app.vela.tv.agent.TvTools
import app.vela.tv.layout.RowSpec
import app.vela.tv.layout.Source
import app.vela.tv.layout.TileAction
import app.vela.tv.layout.TileStyle
import app.vela.tv.media.JellyfinClient.ImageType
import app.vela.tv.media.JellyfinItem
import app.vela.tv.agent.ProfileExecution
import app.vela.tv.media.plex.PlexBrowsePolicy
import app.vela.tv.media.plex.PlexItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
            is Source.PlexLibrary, is Source.PlexRecent, is Source.PlexSearch, is Source.PlexResume, is Source.PlexOnDeck -> plexTiles(spec)
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

    private suspend fun plexTiles(spec: RowSpec): List<Tile> {
        val profile = ProfileExecution.capture(app.householdOrNull())
        PlexBrowsePolicy.requireAllowed(profile.profile)
        val settings = app.settings.settings.value
        fun requireCurrent() {
            profile.requireCurrent()
            check(app.settings.settings.value === settings) { "Plex settings changed. Reload this row." }
        }
        val plex = settings.plex(app.settings.deviceId, beforeRead = {
            requireCurrent()
            PlexBrowsePolicy.requireAllowed(profile.profile)
        }) ?: error("Plex isn't set up. Add its server address and token from your paired phone.")
        requireCurrent()
        val page = when (val source = spec.source) {
            is Source.PlexLibrary -> plex.browse(source.libraryId)
            is Source.PlexRecent -> plex.recent(source.libraryId)
            is Source.PlexSearch -> plex.search(source.libraryId, source.query)
            is Source.PlexResume -> plex.resume()
            is Source.PlexOnDeck -> plex.onDeck()
            else -> error("Invalid Plex source.")
        }
        requireCurrent()
        // Decoration has one shared budget. Missing/slow/invalid art leaves readable title cards.
        val images = mutableMapOf<String, ByteArray>()
        withTimeoutOrNull(3_000) {
            page.items.take(6).forEach { item ->
                requireCurrent()
                val image = try { plex.artwork(item) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                if (image != null) images[item.id] = image
            }
        }
        requireCurrent()
        return page.items.map { it.plexTile(images[it.id]) }
    }

    private fun PlexItem.plexTile(image: ByteArray?): Tile = Tile(
        key = "plex:$serverId:$id", title = seriesTitle ?: title,
        action = TileAction.SearchApp(PlexBrowsePolicy.APP_PACKAGE, (seriesTitle ?: title).take(500)),
        subtitle = listOfNotNull(if (seriesTitle != null) title else year?.toString(), "Search in Plex").joinToString(" · "),
        image = image, kicker = "Plex · Search in app", overview = summary,
        progress = progress,
    )

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

    /** Every activation (human, phone or accessibility) enters the same guarded controller. */
    suspend fun perform(action: TileAction) {
        app.runner.performTile(action)
    }
}
