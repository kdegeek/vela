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

    /** Tiles the agent or the owner put there by hand. */
    @Serializable @SerialName("pinned")
    data class Pinned(val items: List<PinnedItem>) : Source

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
        ),
    )

    fun encode(layout: HomeLayout): String = json.encodeToString(HomeLayout.serializer(), layout)

    /** Parses and checks a layout. Errors are worded for the agent, which wrote the document. */
    fun decode(text: String): Result<HomeLayout> = runCatching {
        val layout = json.decodeFromString(HomeLayout.serializer(), text)
        require(layout.rows.isNotEmpty()) { "a layout needs at least one row" }
        val dup = layout.rows.groupBy { it.id }.filterValues { it.size > 1 }.keys
        require(dup.isEmpty()) { "row ids must be unique; repeated: ${dup.joinToString()}" }
        require(layout.rows.size <= 20) { "at most 20 rows" }
        layout
    }

    /** The format, for the agent's tool description. */
    const val SCHEMA_HELP = """{"rows":[{"id":"unique","title":"Shown above the row","style":"wide|poster|app","source":SOURCE}]}
SOURCE is one of:
 {"type":"apps","packages":["com.plexapp.android",...] or omitted for all}
 {"type":"jellyfin_resume"}
 {"type":"jellyfin_latest","libraryName":"Movies" or omitted}
 {"type":"jellyfin_search","query":"..."}
 {"type":"home_entities","entityIds":["light.living_room",...]}
 {"type":"pinned","items":[{"title":"...","subtitle":"...","imageUrl":"https://...","action":ACTION}]}
ACTION is one of:
 {"type":"launch_app","packageName":"..."}
 {"type":"open_uri","uri":"...","packageName":"..." or omitted}
 {"type":"play_jellyfin","itemId":"..."}
 {"type":"ask_agent","prompt":"..."}
 {"type":"home_service","domain":"light","service":"toggle","entityId":"light.x"}"""
}
