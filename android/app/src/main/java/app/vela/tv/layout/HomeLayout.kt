package app.vela.tv.layout

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The composable home screen: an ordered list of rows, each filled from a source. The agent reads and rewrites this
 * document (tools `layout_get` / `layout_set`); the owner can too, from the phone page.
 */
@Serializable
data class HomeLayout(val version: Int = 1, val rows: List<RowSpec>)

@Serializable
data class RowSpec(val id: String, val title: String, val source: Source, val style: TileStyle = TileStyle.WIDE)

@Serializable
enum class TileStyle {
    @SerialName("wide") WIDE,
    @SerialName("poster") POSTER,
    @SerialName("app") APP,
}

@Serializable
sealed interface Source {
    /** Installed TV apps; [packages] picks and orders them, null means all. */
    @Serializable @SerialName("apps")
    data class Apps(val packages: List<String>? = null) : Source

    @Serializable @SerialName("jellyfin_resume")
    data object JellyfinResume : Source

    @Serializable @SerialName("jellyfin_latest")
    data class JellyfinLatest(val libraryName: String? = null) : Source

    @Serializable @SerialName("jellyfin_search")
    data class JellyfinSearch(val query: String) : Source

    @Serializable @SerialName("plex_library")
    data class PlexLibrary(val libraryId: String) : Source

    @Serializable @SerialName("plex_recent")
    data class PlexRecent(val libraryId: String) : Source

    @Serializable @SerialName("plex_search")
    data class PlexSearch(val libraryId: String, val query: String) : Source

    @Serializable @SerialName("plex_resume")
    data object PlexResume : Source

    @Serializable @SerialName("plex_on_deck")
    data object PlexOnDeck : Source

    /** Tiles the agent or the owner put there by hand. */
    @Serializable @SerialName("pinned")
    data class Pinned(val items: List<PinnedItem>) : Source

    @Serializable @SerialName("watchlist")
    data object Watchlist : Source

    /** Home Assistant entities, shown with their state; choosing one toggles it. */
    @Serializable @SerialName("home_entities")
    data class HomeEntities(val entityIds: List<String>) : Source
}

@Serializable
data class PinnedItem(val title: String, val action: TileAction, val subtitle: String? = null, val imageUrl: String? = null)

@Serializable
sealed interface TileAction {
    @Serializable @SerialName("launch_app")
    data class LaunchApp(val packageName: String) : TileAction

    /** Search handoff only: the existing guarded app_search tool owns dispatch and profile checks. */
    @Serializable @SerialName("search_app")
    data class SearchApp(val packageName: String, val query: String) : TileAction

    @Serializable @SerialName("open_uri")
    data class OpenUri(val uri: String, val packageName: String? = null) : TileAction

    @Serializable @SerialName("play_jellyfin")
    data class PlayJellyfin(val itemId: String) : TileAction

    /** Hands [prompt] to the agent, e.g. "put on something light". */
    @Serializable @SerialName("ask_agent")
    data class AskAgent(val prompt: String) : TileAction

    @Serializable @SerialName("home_service")
    data class HomeService(val domain: String, val service: String, val entityId: String) : TileAction
}

object Layouts {
    val json = Json {
        classDiscriminator = "type"
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    val default = HomeLayout(
        rows = listOf(
            RowSpec("apps", "Apps", Source.Apps(), TileStyle.APP),
            RowSpec("resume", "Continue watching", Source.JellyfinResume),
            RowSpec("latest", "Recently added", Source.JellyfinLatest(), TileStyle.POSTER),
            RowSpec("watchlist", "My list", Source.Watchlist),
        ),
    )

    fun encode(layout: HomeLayout): String = json.encodeToString(HomeLayout.serializer(), layout)

    /** Parses and checks a layout. Errors are worded for the agent, which wrote the document. */
    fun decode(text: String): Result<HomeLayout> = runCatching {
        require(text.length <= 256_000) { "layout is too large" }
        val layout = json.decodeFromString(HomeLayout.serializer(), text)
        require(layout.rows.isNotEmpty()) { "a layout needs at least one row" }
        val dup = layout.rows.groupBy { it.id }.filterValues { it.size > 1 }.keys
        require(dup.isEmpty()) { "row ids must be unique; repeated: ${dup.joinToString()}" }
        require(layout.rows.size <= 20) { "at most 20 rows" }
        require(layout.version == 1) { "unsupported layout version ${layout.version}" }
        layout.rows.forEach { row ->
            require(row.id.matches(Regex("[a-zA-Z0-9_-]{1,64}"))) { "row ids need 1–64 letters, digits, - or _" }
            require(row.title.isNotBlank() && row.title.length <= 120) { "row titles need 1–120 characters" }
            when (val source = row.source) {
                is Source.Apps -> require(source.packages == null || (source.packages.size <= 100 && source.packages.distinct().size == source.packages.size)) { "app packages must be unique; at most 100" }
                is Source.Pinned -> {
                    require(source.items.size <= 100) { "at most 100 pinned tiles per row" }
                    source.items.forEach { item ->
                        require(item.title.isNotBlank() && item.title.length <= 200) { "tile titles need 1–200 characters" }
                        when (val action = item.action) {
                            is TileAction.AskAgent -> require(action.prompt.isNotBlank() && action.prompt.length <= 8000) { "invalid tile prompt" }
                            is TileAction.LaunchApp -> require(action.packageName.isNotBlank()) { "missing package" }
                            is TileAction.SearchApp -> {
                                require(action.packageName.length <= 255 && action.packageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+"))) { "invalid search app package" }
                                require(action.query.isNotBlank() && action.query.length <= 500 && action.query.none(Char::isISOControl)) { "invalid app search query" }
                            }
                            is TileAction.PlayJellyfin -> require(action.itemId.isNotBlank()) { "missing item id" }
                            is TileAction.OpenUri -> require(action.uri.isNotBlank() && action.uri.length <= 8192) { "invalid tile link" }
                            is TileAction.HomeService -> require(action.domain.isNotBlank() && action.service.isNotBlank() && action.entityId.isNotBlank()) { "home tiles need a domain, service and entity" }
                        }
                    }
                }
                is Source.HomeEntities -> require(source.entityIds.size <= 100 && source.entityIds.distinct().size == source.entityIds.size) { "entity ids must be unique; at most 100" }
                is Source.JellyfinSearch -> require(source.query.isNotBlank() && source.query.length <= 500) { "search query needs 1–500 characters" }
                is Source.PlexLibrary -> plexLibrary(source.libraryId)
                is Source.PlexRecent -> plexLibrary(source.libraryId)
                is Source.PlexSearch -> {
                    plexLibrary(source.libraryId)
                    require(source.query.isNotBlank() && source.query.length <= 500 && source.query.none(Char::isISOControl)) { "Plex search needs 1–500 characters without controls" }
                }
                else -> Unit
            }
        }
        layout
    }

    private fun plexLibrary(id: String) = require(id.matches(Regex("[0-9]{1,18}"))) { "Plex needs a numeric library id" }

    /** The format, for the agent's tool description. */
    const val SCHEMA_HELP = """{"rows":[{"id":"unique","title":"Shown above the row","style":"wide|poster|app","source":SOURCE}]}
SOURCE is one of:
 {"type":"apps","packages":["com.plexapp.android",...] or omitted for all}
 {"type":"watchlist"}
 {"type":"jellyfin_resume"}
 {"type":"jellyfin_latest","libraryName":"Movies" or omitted}
 {"type":"jellyfin_search","query":"..."}
 {"type":"plex_library","libraryId":"1"}
 {"type":"plex_recent","libraryId":"1"}
 {"type":"plex_search","libraryId":"1","query":"..."}
 {"type":"plex_resume"}
 {"type":"plex_on_deck"}
 {"type":"home_entities","entityIds":["light.living_room",...]}
 {"type":"pinned","items":[{"title":"...","subtitle":"...","imageUrl":"https://...","action":ACTION}]}
ACTION is one of:
 {"type":"launch_app","packageName":"..."}
 {"type":"search_app","packageName":"com.plexapp.android","query":"..."} (search handoff, not playback)
 {"type":"open_uri","uri":"...","packageName":"..." or omitted}
 {"type":"play_jellyfin","itemId":"..."}
 {"type":"ask_agent","prompt":"..."}
 {"type":"home_service","domain":"light","service":"toggle","entityId":"light.x"}"""
}
