package app.vela.tv.guard

import app.vela.tv.layout.HomeLayout
import app.vela.tv.layout.Source
import app.vela.tv.layout.TileAction

object TileGuards {
    fun action(action: TileAction, link: (String, String?) -> GuardHit? = Guard::uri): GuardHit? = when (action) {
        is TileAction.HomeService -> Guard.homeService(action.domain, action.service, listOf(action.entityId))
        is TileAction.OpenUri -> link(action.uri, action.packageName)
        else -> null
    }

    fun layout(layout: HomeLayout, link: (String, String?) -> GuardHit? = Guard::uri): GuardHit? =
        layout.rows.asSequence().flatMap { row -> when (val source = row.source) {
            is Source.Pinned -> source.items.asSequence().map { it.action }
            is Source.HomeEntities -> source.entityIds.asSequence().map { TileAction.HomeService(it.substringBefore('.'), "toggle", it) }
            else -> emptySequence()
        } }.mapNotNull { action(it, link) }.firstOrNull()
}
