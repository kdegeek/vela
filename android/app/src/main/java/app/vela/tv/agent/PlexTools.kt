package app.vela.tv.agent

import app.vela.tv.media.plex.*
import kotlinx.serialization.json.*

/** Bounded metadata discovery. Native app_search remains the explicit playback handoff. */
internal class PlexTools(private val client: () -> PlexClient) {
    fun all(): List<Tool> = listOf(
        read("plex_libraries", "Discover Plex library IDs and names before browsing or composing Plex home rows.") { c, a ->
            page(c.libraries(request(a))) { library -> buildJsonObject {
                put("library_id", library.id); put("title", library.title); put("type", library.type)
            } }
        },
        read("plex_browse", "Browse one Plex library, optionally with newest additions first.",
            fields = mapOf("library_id" to string(), "mode" to mode("all", "recent")), required = listOf("library_id")) { c, a ->
            items(if (a.text("mode") == "recent") c.recent(a.getValue("library_id").jsonPrimitive.content, request(a))
                else c.browse(a.getValue("library_id").jsonPrimitive.content, request(a)))
        },
        read("plex_search", "Find titles in a discovered Plex library. Results are metadata; use app_search for native Plex search, not a playback-success claim.",
            fields = mapOf("library_id" to string(), "query" to string()), required = listOf("library_id", "query")) { c, a ->
            items(c.search(a.getValue("library_id").jsonPrimitive.content, a.getValue("query").jsonPrimitive.content, request(a)))
        },
        read("plex_resume", "Read Plex Continue Watching or On Deck metadata.", fields = mapOf("mode" to mode("continue", "on_deck"))) { c, a ->
            items(if (a.text("mode") == "on_deck") c.onDeck(request(a)) else c.resume(request(a)))
        },
    )

    private fun request(args: JsonObject): PlexPageRequest {
        val limit = args["limit"]?.jsonPrimitive?.int ?: 20
        require(limit in 1..20) { "Plex tools return 1–20 items per page." }
        return PlexPageRequest(args["offset"]?.jsonPrimitive?.int ?: 0, limit)
    }
    private fun JsonObject.text(key: String) = this[key]?.jsonPrimitive?.contentOrNull
    private fun string() = buildJsonObject { put("type", "string") }
    private fun mode(vararg values: String) = buildJsonObject { put("type", "string"); put("enum", JsonArray(values.map(::JsonPrimitive))) }
    private fun read(name: String, description: String, fields: Map<String, JsonObject> = emptyMap(), required: List<String> = emptyList(),
                     action: suspend (PlexClient, JsonObject) -> String): Tool = object : Tool {
        override val spec = ToolSpec(name, description, buildJsonObject {
            put("type", "object"); put("additionalProperties", false)
            putJsonObject("properties") {
                putJsonObject("offset") { put("type", "integer"); put("minimum", 0); put("maximum", 1_000_000) }
                putJsonObject("limit") { put("type", "integer"); put("minimum", 1); put("maximum", 20) }
                fields.forEach { (key, value) -> put(key, value) }
            }
            put("required", JsonArray(required.map(::JsonPrimitive)))
        })
        override suspend fun run(args: JsonObject) = ToolOutcome(action(client(), args))
    }
    private fun items(value: PlexPage<PlexItem>) = page(value) { item -> buildJsonObject {
        put("server_id", item.serverId); put("item_id", item.id); put("library_id", item.libraryId)
        put("title", item.title); put("type", item.type); put("year", item.year)
        put("series", item.seriesTitle); put("resume_ms", item.resumePositionMs)
    } }
    private fun <T> page(value: PlexPage<T>, encode: (T) -> JsonObject) = buildJsonObject {
        put("offset", value.offset); put("next_offset", value.nextOffset); put("total", value.total); put("truncated", value.truncated)
        put("items", JsonArray(value.items.map(encode)))
    }.toString()
}
