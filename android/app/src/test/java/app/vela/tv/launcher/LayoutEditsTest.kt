package app.vela.tv.launcher

import app.vela.tv.layout.*
import org.junit.Assert.*
import org.junit.Test

class LayoutEditsTest {
    private val pinned = RowSpec("owner-row", "Evening", Source.Pinned(listOf(
        PinnedItem("Ask", TileAction.AskAgent("Set the mood")),
        PinnedItem("Lights", TileAction.HomeService("light", "toggle", "light.room")),
        PinnedItem("Plex", TileAction.SearchApp("com.plexapp.android", "Arrival"))
    )), TileStyle.POSTER)
    private val layout = HomeLayout(rows = listOf(Layouts.default.rows[0], pinned, Layouts.default.rows[1]))

    @Test fun moveChangesOnlyOrderAndRetainsEverySourceActionAndStableId() {
        val moved = LayoutEdits.move(layout, pinned.id, -1)
        assertEquals(listOf("owner-row", "apps", "resume"), moved.rows.map { it.id })
        assertSame(pinned, moved.rows[0])
        assertEquals(layout.rows.toSet(), moved.rows.toSet())
        assertEquals(layout, LayoutEdits.move(moved, pinned.id, 1))
        assertEquals(listOf("apps", "owner-row", "resume"), layout.rows.map { it.id })
    }
    @Test fun hugeDeltasClampWithoutOverflowAndBoundaryMovesAreNoOps() {
        assertEquals("owner-row", LayoutEdits.move(layout, pinned.id, Int.MAX_VALUE).rows.last().id)
        assertEquals("owner-row", LayoutEdits.move(layout, pinned.id, Int.MIN_VALUE).rows.first().id)
        assertSame(layout, LayoutEdits.move(layout, "apps", -1))
        assertSame(layout, LayoutEdits.move(layout, "resume", 1))
        assertSame(layout, LayoutEdits.move(layout, pinned.id, 0))
    }
    @Test fun removePreservesRemainingRowsAndRefusesTheLastRow() {
        val removed = LayoutEdits.remove(layout, pinned.id)
        assertEquals(listOf("apps", "resume"), removed.rows.map { it.id })
        assertEquals(layout.rows.filterNot { it.id == pinned.id }, removed.rows)
        assertThrows(IllegalArgumentException::class.java) { LayoutEdits.remove(HomeLayout(rows = listOf(pinned)), pinned.id) }
    }
    @Test fun staleUnknownIdentityFailsInsteadOfEditingAnUnrelatedRow() {
        assertThrows(IllegalArgumentException::class.java) { LayoutEdits.move(layout, "gone", 1) }
        assertThrows(IllegalArgumentException::class.java) { LayoutEdits.remove(layout, "gone") }
    }
    @Test fun successiveEditsDeriveFromLatestLayout() {
        val moved = LayoutEdits.move(layout, pinned.id, 1)
        val removed = LayoutEdits.remove(moved, "apps")
        assertEquals(listOf("resume", "owner-row"), removed.rows.map { it.id })
        assertEquals(pinned, removed.rows.last())
    }
    @Test fun editedDefaultAndAgentLayoutsSurviveTheRealStorageValidator() {
        listOf(Layouts.default, layout).forEach { original ->
            val moved = LayoutEdits.move(original, original.rows.first().id, 1)
            val removed = LayoutEdits.remove(moved, moved.rows.last().id)
            assertEquals(moved, Layouts.decode(Layouts.encode(moved)).getOrThrow())
            assertEquals(removed, Layouts.decode(Layouts.encode(removed)).getOrThrow())
        }
    }
    @Test fun codecRejectsInvalidDocumentEvenAfterAnOtherwiseValidEdit() {
        val badVersion = LayoutEdits.move(layout.copy(version = 2), pinned.id, -1)
        assertTrue(Layouts.decode(Layouts.encode(badVersion)).isFailure)
        val duplicate = layout.copy(rows = listOf(pinned, pinned))
        assertTrue(Layouts.decode(Layouts.encode(LayoutEdits.move(duplicate, pinned.id, 1))).isFailure)
        assertTrue(Layouts.decode(Layouts.encode(HomeLayout(rows = emptyList()))).isFailure)
    }
    @Test fun validOwnerIdsCannotCollideWithHomeOrEditorChrome() {
        val chrome = listOf("hero", "pair", "empty", "arrange", "status", "defaults", "vela-page-header")
        val checked = Layouts.decode(Layouts.encode(HomeLayout(rows = chrome.map { pinned.copy(id = it) }))).getOrThrow()
        val keys = chrome + checked.rows.map { ownerRowKey(it.id) }
        assertEquals(keys.size, keys.distinct().size)
        assertEquals(chrome, checked.rows.map { it.id }) // Persisted identity is retained for edits and focus.
    }
}
