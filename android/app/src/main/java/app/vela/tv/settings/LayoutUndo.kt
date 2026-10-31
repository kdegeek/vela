package app.vela.tv.settings

import app.vela.tv.layout.HomeLayout
import app.vela.tv.layout.Layouts
import app.vela.tv.layout.Source
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Collections

/**
 * One session-only opportunity to undo a committed layout change.
 *
 * This instance is its unique identity: retain and pass the exact object to
 * [SettingsStore.undoLayout]. Equal documents do not make receipts interchangeable.
 * [previous] and all its collections are immutable snapshots; there is no copy/redo API.
 */
class LayoutUndo internal constructor(previous: HomeLayout) {
    val previous: HomeLayout = immutableLayout(previous)
}

/** Android-free state machine. [commit] must persist and verify before returning, or throw. */
internal class LayoutHistory(initial: HomeLayout, private val commit: (String) -> Unit) {
    private var current = immutableLayout(initial)
    private var undo: LayoutUndo? = null
    private val _layout = MutableStateFlow(current)
    val layout: StateFlow<HomeLayout> = _layout.asStateFlow()

    private val _undo = MutableStateFlow<LayoutUndo?>(null)
    val layoutUndo: StateFlow<LayoutUndo?> = _undo.asStateFlow()

    @Synchronized
    fun setLayout(layout: HomeLayout) {
        val next = immutableLayout(layout)
        val receipt = if (next == current) undo else LayoutUndo(current)
        // Even identical saves retain SettingsStore's verified-write behavior.
        commit(Layouts.encode(next))
        current = next
        undo = receipt
        publish()
    }

    @Synchronized
    fun undoLayout(expected: LayoutUndo) {
        check(undo === expected) { "Home changed; this Undo is no longer available." }
        commit(Layouts.encode(expected.previous))
        current = expected.previous
        undo = null
        publish()
    }

    private fun publish() {
        // Authoritative state changes before either flow notifies collectors. Read it afresh
        // for each publication, including if an immediate collector re-enters this monitor.
        _layout.value = current
        _undo.value = undo
    }
}

/** Validate/copy through the real codec, then freeze every collection in the decoded model. */
private fun immutableLayout(layout: HomeLayout): HomeLayout {
    val decoded = Layouts.decode(Layouts.encode(layout)).getOrThrow()
    return decoded.copy(rows = decoded.rows.map { row ->
        val source = when (val source = row.source) {
            is Source.Apps -> source.copy(packages = source.packages?.immutableCopy())
            is Source.Pinned -> source.copy(items = source.items.immutableCopy())
            is Source.HomeEntities -> source.copy(entityIds = source.entityIds.immutableCopy())
            is Source.JellyfinLatest, is Source.JellyfinSearch,
            is Source.PlexLibrary, is Source.PlexRecent, is Source.PlexSearch,
            Source.JellyfinResume, Source.PlexResume, Source.PlexOnDeck, Source.Watchlist -> source
        }
        row.copy(source = source)
    }.immutableCopy())
}

private fun <T> List<T>.immutableCopy(): List<T> = Collections.unmodifiableList(ArrayList(this))
