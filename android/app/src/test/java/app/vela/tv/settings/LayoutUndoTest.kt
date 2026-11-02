package app.vela.tv.settings

import app.vela.tv.layout.HomeLayout
import app.vela.tv.layout.Layouts
import app.vela.tv.layout.PinnedItem
import app.vela.tv.layout.RowSpec
import app.vela.tv.layout.Source
import app.vela.tv.layout.TileAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class LayoutUndoTest {
    private val a = layout("A")
    private val b = layout("B")
    private val c = layout("C")

    @Test fun `initial state has no undo and does not write`() {
        val store = Store(a)
        assertEquals(a, store.history.layout.value)
        assertNotSame(a, store.history.layout.value)
        assertNull(store.history.layoutUndo.value)
        assertTrue(store.attempts.isEmpty())
        assertFalse(store.history.layout is MutableStateFlow<*>)
        assertFalse(store.history.layoutUndo is MutableStateFlow<*>)
    }

    @Test fun `save and undo publish only after verified commits and undo has no redo`() {
        val store = Store(a)
        store.beforeCommit = {
            assertEquals(a, store.history.layout.value)
            assertNull(store.history.layoutUndo.value)
        }
        store.history.setLayout(b)
        val receipt = store.receipt()
        assertEquals(a, receipt.previous)
        assertEquals(b, store.history.layout.value)
        assertEquals(Layouts.encode(b), store.persisted)

        store.beforeCommit = {
            assertEquals(b, store.history.layout.value)
            assertSame(receipt, store.history.layoutUndo.value)
        }
        store.history.undoLayout(receipt)
        assertEquals(a, store.history.layout.value)
        assertEquals(Layouts.encode(a), store.persisted)
        assertNull(store.history.layoutUndo.value)
        assertThrows(IllegalStateException::class.java) { store.history.undoLayout(receipt) }
        assertEquals(listOf(Layouts.encode(b), Layouts.encode(a)), store.attempts)
    }

    @Test fun `only the immediately preceding layout can be restored`() {
        val store = Store(a)
        store.history.setLayout(b)
        val first = store.receipt()
        store.history.setLayout(c)
        val latest = store.receipt()
        assertEquals(b, latest.previous)
        assertThrows(IllegalStateException::class.java) { store.history.undoLayout(first) }
        assertEquals(2, store.attempts.size)
        store.history.undoLayout(latest)
        assertEquals(b, store.history.layout.value)
        assertNull(store.history.layoutUndo.value)
    }

    @Test fun `identical saves still verify persistence and preserve the exact receipt`() {
        val store = Store(a)
        store.history.setLayout(a.copy())
        assertNull(store.history.layoutUndo.value)
        store.history.setLayout(b)
        val receipt = store.receipt()
        store.history.setLayout(Layouts.decode(Layouts.encode(b)).getOrThrow())
        assertSame(receipt, store.history.layoutUndo.value)
        assertEquals(3, store.attempts.size)
        store.history.undoLayout(receipt)
        store.history.setLayout(a)
        assertNull(store.history.layoutUndo.value)
    }

    @Test fun `ABA and equal-document receipts never revive stale undo`() {
        val store = Store(a)
        store.history.setLayout(b)
        val stale = store.receipt()
        store.history.setLayout(a)
        assertThrows(IllegalStateException::class.java) { store.history.undoLayout(stale) }
        store.history.setLayout(b)
        val latest = store.receipt()
        assertEquals(stale.previous, latest.previous)
        assertNotSame(stale, latest)
        assertNotEquals(stale, latest)
        assertThrows(IllegalStateException::class.java) { store.history.undoLayout(stale) }
        assertEquals(3, store.attempts.size)
        assertSame(latest, store.history.layoutUndo.value)
        store.history.undoLayout(latest)
        assertEquals(a, store.history.layout.value)
    }

    @Test fun `an equal receipt from another store or constructed in the module is refused`() {
        val store = Store(a)
        val other = Store(a)
        store.history.setLayout(b)
        other.history.setLayout(b)
        val receipt = store.receipt()
        for (foreign in listOf(other.receipt(), LayoutUndo(a))) {
            assertEquals(receipt.previous, foreign.previous)
            assertThrows(IllegalStateException::class.java) { store.history.undoLayout(foreign) }
        }
        assertSame(receipt, store.history.layoutUndo.value)
        assertEquals(1, store.attempts.size)
    }

    @Test fun `failed first save does not create an undo`() {
        val store = Store(a)
        store.failure = Failure.WRITE
        assertThrows(IOException::class.java) { store.history.setLayout(b) }
        assertEquals(a, store.history.layout.value)
        assertNull(store.history.layoutUndo.value)
        assertEquals(Layouts.encode(a), store.persisted)
    }

    @Test fun `write and read-back failures preserve current state and receipt on saves`() {
        for (failure in Failure.entries) {
            val store = Store(a)
            store.history.setLayout(b)
            val current = store.history.layout.value
            val receipt = store.receipt()
            store.failure = failure
            assertThrows(IOException::class.java) { store.history.setLayout(c) }
            assertSame(current, store.history.layout.value)
            assertSame(receipt, store.history.layoutUndo.value)
            store.failure = null
            store.history.setLayout(c)
            assertEquals(b, store.receipt().previous)
            assertNotSame(receipt, store.history.layoutUndo.value)
        }
    }

    @Test fun `failed identical save preserves its receipt`() {
        val store = Store(a)
        store.history.setLayout(b)
        val receipt = store.receipt()
        store.failure = Failure.VERIFY
        assertThrows(IOException::class.java) { store.history.setLayout(b) }
        assertEquals(b, store.history.layout.value)
        assertSame(receipt, store.history.layoutUndo.value)
    }

    @Test fun `write and read-back failures leave undo retryable`() {
        for (failure in Failure.entries) {
            val store = Store(a)
            store.history.setLayout(b)
            val current = store.history.layout.value
            val receipt = store.receipt()
            store.failure = failure
            assertThrows(IOException::class.java) { store.history.undoLayout(receipt) }
            assertSame(current, store.history.layout.value)
            assertSame(receipt, store.history.layoutUndo.value)
            store.failure = null
            store.history.undoLayout(receipt)
            assertEquals(a, store.history.layout.value)
            assertNull(store.history.layoutUndo.value)
        }
    }

    @Test fun `real codec validation rejects invalid input without writing or replacing undo`() {
        val store = Store(a)
        store.history.setLayout(b)
        val receipt = store.receipt()
        val invalid = listOf(
            HomeLayout(rows = emptyList()),
            a.copy(version = 2),
            a.copy(rows = a.rows + a.rows),
            HomeLayout(rows = listOf(RowSpec("bad id", "Bad", Source.Watchlist))),
            HomeLayout(rows = listOf(RowSpec("pinned", "Pinned", Source.Pinned(listOf(
                PinnedItem("Invalid", TileAction.AskAgent("")),
            ))))),
            HomeLayout(rows = listOf(RowSpec("apps", "Apps", Source.Apps(listOf("same", "same"))))),
            HomeLayout(rows = listOf(RowSpec("huge", "Huge", Source.JellyfinSearch("x".repeat(256_000))))),
        )
        for (layout in invalid) {
            assertTrue(Layouts.decode(Layouts.encode(layout)).isFailure)
            assertThrows(IllegalArgumentException::class.java) { store.history.setLayout(layout) }
            assertSame(receipt, store.history.layoutUndo.value)
            assertEquals(b, store.history.layout.value)
        }
        assertEquals(1, store.attempts.size)
    }

    @Test fun `mutable initial and edited inputs cannot change current or undo snapshots`() {
        val initial = MutableLayout("Initial")
        val original = Layouts.decode(Layouts.encode(initial.value)).getOrThrow()
        val store = Store(initial.value)
        initial.mutate()
        assertEquals(original, store.history.layout.value)

        val edit = MutableLayout("Edited")
        val edited = Layouts.decode(Layouts.encode(edit.value)).getOrThrow()
        store.beforeCommit = { edit.mutate() }
        store.history.setLayout(edit.value)
        val receipt = store.receipt()
        assertEquals(edited, store.history.layout.value)
        assertEquals(Layouts.encode(edited), store.persisted)
        assertEquals(original, receipt.previous)
        store.beforeCommit = {}
        store.history.undoLayout(receipt)
        assertEquals(original, store.history.layout.value)
        assertEquals(Layouts.encode(original), store.persisted)
    }

    @Test fun `current and receipt collections resist mutable casts at every model list`() {
        val initial = MutableLayout("Initial")
        val store = Store(initial.value)
        assertFrozen(store.history.layout.value)
        store.history.setLayout(MutableLayout("Edited").value)
        val receipt = store.receipt()
        assertFrozen(store.history.layout.value)
        assertFrozen(receipt.previous)
        val previousText = Layouts.encode(receipt.previous)
        store.history.undoLayout(receipt)
        assertEquals(previousText, store.persisted)
        assertFrozen(store.history.layout.value)
    }

    @Test fun `receipt itself copies inputs defensively`() {
        val input = MutableLayout("Receipt")
        val expected = Layouts.encode(input.value)
        val receipt = LayoutUndo(input.value)
        input.mutate()
        assertEquals(expected, Layouts.encode(receipt.previous))
        assertFrozen(receipt.previous)
    }

    @Test fun `restarting from persisted layout drops undo and rejects the prior session receipt`() {
        val store = Store(a)
        store.history.setLayout(b)
        val receipt = store.receipt()
        val restarted = Store(Layouts.decode(store.persisted).getOrThrow())
        assertEquals(b, restarted.history.layout.value)
        assertNull(restarted.history.layoutUndo.value)
        assertThrows(IllegalStateException::class.java) { restarted.history.undoLayout(receipt) }
        restarted.history.setLayout(Layouts.default)
        assertEquals(b, restarted.receipt().previous)
    }

    @Test(timeout = 10_000)
    fun `a waiting undo rechecks its receipt after an in-flight save commits`() {
        val enteredCommit = CountDownLatch(1)
        val releaseCommit = CountDownLatch(1)
        val undoStarted = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val store = Store(a)
        store.history.setLayout(b)
        val receipt = store.receipt()
        store.beforeCommit = {
            enteredCommit.countDown()
            assertTrue(releaseCommit.await(3, TimeUnit.SECONDS))
        }
        try {
            val save = pool.submit { store.history.setLayout(c) }
            assertTrue(enteredCommit.await(3, TimeUnit.SECONDS))
            assertEquals(b, store.history.layout.value)
            assertSame(receipt, store.history.layoutUndo.value)
            val undo = pool.submit(Callable {
                undoStarted.countDown()
                runCatching { store.history.undoLayout(receipt) }.exceptionOrNull()
            })
            assertTrue(undoStarted.await(3, TimeUnit.SECONDS))
            assertThrows(TimeoutException::class.java) { undo.get(100, TimeUnit.MILLISECONDS) }
            releaseCommit.countDown()
            save.get(3, TimeUnit.SECONDS)
            assertTrue(undo.get(3, TimeUnit.SECONDS) is IllegalStateException)
            assertEquals(c, store.history.layout.value)
            assertEquals(b, store.receipt().previous)
            assertEquals(listOf(Layouts.encode(b), Layouts.encode(c)), store.attempts)
        } finally {
            releaseCommit.countDown()
            pool.shutdownNow()
        }
    }

    @Test(timeout = 10_000)
    fun `a save waiting for undo uses the restored layout as its previous snapshot`() {
        val enteredCommit = CountDownLatch(1)
        val releaseCommit = CountDownLatch(1)
        val saveStarted = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val store = Store(a)
        store.history.setLayout(b)
        val receipt = store.receipt()
        store.beforeCommit = {
            if (it == Layouts.encode(a)) {
                enteredCommit.countDown()
                assertTrue(releaseCommit.await(3, TimeUnit.SECONDS))
            }
        }
        try {
            val undo = pool.submit { store.history.undoLayout(receipt) }
            assertTrue(enteredCommit.await(3, TimeUnit.SECONDS))
            assertEquals(b, store.history.layout.value)
            assertSame(receipt, store.history.layoutUndo.value)
            val save = pool.submit {
                saveStarted.countDown()
                store.history.setLayout(c)
            }
            assertTrue(saveStarted.await(3, TimeUnit.SECONDS))
            assertThrows(TimeoutException::class.java) { save.get(100, TimeUnit.MILLISECONDS) }
            releaseCommit.countDown()
            undo.get(3, TimeUnit.SECONDS)
            save.get(3, TimeUnit.SECONDS)
            assertEquals(c, store.history.layout.value)
            assertEquals(a, store.receipt().previous)
            assertEquals(listOf(Layouts.encode(b), Layouts.encode(a), Layouts.encode(c)), store.attempts)
        } finally {
            releaseCommit.countDown()
            pool.shutdownNow()
        }
    }

    @Test(timeout = 10_000)
    fun `competing undo calls consume a receipt only once`() {
        val store = Store(a)
        store.history.setLayout(b)
        val receipt = store.receipt()
        val barrier = CyclicBarrier(2)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val attempts = (1..2).map {
                pool.submit(Callable {
                    barrier.await(3, TimeUnit.SECONDS)
                    runCatching { store.history.undoLayout(receipt) }.exceptionOrNull()
                })
            }.map { it.get(3, TimeUnit.SECONDS) }
            assertEquals(1, attempts.count { it == null })
            assertEquals(1, attempts.count { it is IllegalStateException })
            assertEquals(listOf(Layouts.encode(b), Layouts.encode(a)), store.attempts)
            assertEquals(a, store.history.layout.value)
            assertNull(store.history.layoutUndo.value)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test fun `immediate collectors cannot use a receipt invalidated before flow publication`() = runBlocking {
        val store = Store(a)
        store.history.setLayout(b)
        val stale = store.receipt()
        var checked = false
        val observer = launch(Dispatchers.Unconfined) {
            store.history.layout.collect { layout ->
                if (layout == c) {
                    assertThrows(IllegalStateException::class.java) { store.history.undoLayout(stale) }
                    checked = true
                }
            }
        }
        try {
            store.history.setLayout(c)
            assertTrue(checked)
            assertEquals(c, store.history.layout.value)
            assertEquals(b, store.receipt().previous)
            assertEquals(2, store.attempts.size)
        } finally {
            observer.cancel()
        }
    }

    @Test fun `immediate collector edits cannot leave an older receipt published`() = runBlocking {
        val store = Store(a)
        val observer = launch(Dispatchers.Unconfined) {
            store.history.layout.collect { if (it == b) store.history.setLayout(c) }
        }
        try {
            store.history.setLayout(b)
            assertEquals(c, store.history.layout.value)
            assertEquals(b, store.receipt().previous)
            assertEquals(Layouts.encode(c), store.persisted)
        } finally {
            observer.cancel()
        }
    }

    private class Store(initial: HomeLayout) {
        val attempts = mutableListOf<String>()
        var persisted = Layouts.encode(initial)
        var failure: Failure? = null
        var beforeCommit: (String) -> Unit = {}
        val history = LayoutHistory(initial) { text ->
            attempts += text
            beforeCommit(text)
            if (failure == Failure.WRITE) throw IOException("Injected write failure")
            persisted = text
            if (failure == Failure.VERIFY) throw IOException("Injected read-back verification failure")
        }

        fun receipt(): LayoutUndo = requireNotNull(history.layoutUndo.value)
    }

    private enum class Failure { WRITE, VERIFY }

    private class MutableLayout(title: String) {
        val packages = mutableListOf("app.one", "app.two")
        val pins = mutableListOf(PinnedItem("First", TileAction.LaunchApp("app.one")))
        val entities = mutableListOf("light.one", "light.two")
        val rows = mutableListOf(
            RowSpec("apps", title, Source.Apps(packages)),
            RowSpec("pins", "Pinned", Source.Pinned(pins)),
            RowSpec("home", "Home", Source.HomeEntities(entities)),
        )
        val value = HomeLayout(rows = rows)

        fun mutate() {
            packages.clear()
            pins.clear()
            entities.clear()
            rows.clear()
        }
    }

    private fun assertFrozen(layout: HomeLayout) {
        val before = Layouts.encode(layout)
        assertThrows(UnsupportedOperationException::class.java) { (layout.rows as MutableList).clear() }
        for (row in layout.rows) {
            val list = when (val source = row.source) {
                is Source.Apps -> requireNotNull(source.packages)
                is Source.Pinned -> source.items
                is Source.HomeEntities -> source.entityIds
                else -> error("Unexpected fixture source")
            }
            assertThrows(UnsupportedOperationException::class.java) { (list as MutableList).clear() }
        }
        assertEquals(before, Layouts.encode(layout))
    }

    private fun layout(title: String) = HomeLayout(rows = listOf(RowSpec("apps", title, Source.Apps())))
}
