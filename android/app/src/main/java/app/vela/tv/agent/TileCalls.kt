package app.vela.tv.agent

import app.vela.tv.layout.TileAction
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/** The actual saved/generated action determines the tool and arguments; display text never grants permission. */
object TileCalls {
    fun call(action: TileAction): ToolCall = ToolCall(UUID.randomUUID().toString(), when (action) {
        is TileAction.HomeService -> "home_call"
        is TileAction.OpenUri -> "open_link"
        is TileAction.LaunchApp -> "app_open"
        is TileAction.SearchApp -> "app_search"
        is TileAction.PlayJellyfin -> "jellyfin_play"
        is TileAction.AskAgent -> error("Ask actions submit a normal task.")
    }, buildJsonObject {
        when (action) {
            is TileAction.HomeService -> { put("domain", action.domain); put("service", action.service); put("entity_id", action.entityId) }
            is TileAction.OpenUri -> { put("uri", action.uri); action.packageName?.let { put("package", it) } }
            is TileAction.LaunchApp -> put("package", action.packageName)
            is TileAction.SearchApp -> { put("package", action.packageName); put("query", action.query) }
            is TileAction.PlayJellyfin -> put("item_id", action.itemId)
            is TileAction.AskAgent -> Unit
        }
    })

    suspend fun perform(action: TileAction, execute: suspend (ToolCall) -> ToolOutcome) {
        val result = execute(call(action))
        check(!result.isError) { result.content }
    }
}
