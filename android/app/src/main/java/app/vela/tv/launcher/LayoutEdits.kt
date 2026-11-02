package app.vela.tv.launcher

import app.vela.tv.layout.HomeLayout

/** Owner IDs share a lazy list with shell chrome, but never its key namespace. */
internal fun ownerRowKey(id: String): String = "row:$id"

/** Edits preserve every row's identity, source, action and presentation. */
internal object LayoutEdits {
    fun move(layout: HomeLayout, rowId: String, delta: Int): HomeLayout {
        val index = layout.rows.indexOfFirst { it.id == rowId }
        require(index >= 0) { "That row no longer exists. Reload your home." }
        val destination = (index.toLong() + delta).coerceIn(0L, layout.rows.lastIndex.toLong()).toInt()
        if (index == destination) return layout
        val rows = layout.rows.toMutableList()
        rows.add(destination, rows.removeAt(index))
        return layout.copy(rows = rows)
    }
    fun remove(layout: HomeLayout, rowId: String): HomeLayout {
        require(layout.rows.any { it.id == rowId }) { "That row no longer exists. Reload your home." }
        require(layout.rows.size > 1) { "Keep at least one row on your home." }
        return layout.copy(rows = layout.rows.filterNot { it.id == rowId })
    }
}
