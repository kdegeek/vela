package app.vela.tv.control

import app.vela.tv.guard.Guard
import app.vela.tv.guard.Risk
import org.junit.Assert.*
import org.junit.Test

class PressTargetTest {
    private class Node(var text: String = "", val clickable: Boolean = false) {
        var parent: Node? = null
        val children = mutableListOf<Node>()
        fun child(node: Node) = node.also { it.parent = this; children += it }
    }
    private fun label(node: Node, depth: Int = 0): String =
        (listOf(node.text) + if (depth > 3) emptyList() else node.children.map { label(it, depth + 1) })
            .filter(String::isNotBlank).distinct().joinToString(" ")
    private fun dispatched(node: Node): Node {
        var current: Node? = node
        while (current != null && !current.clickable) current = current.parent
        return current ?: node
    }
    @Test fun `deep selected purchase label survives binding to a clickable ancestor`() {
        val card = Node("Movie card", true)
        var parent = card
        repeat(5) { parent = parent.child(Node()) }
        val selected = parent.child(Node("Buy now $9.99"))
        assertNull(Guard.press(label(card), "streaming.app"))
        val binding = PressTarget.capture(selected, dispatched(selected)) { label(it) }
        assertEquals(Risk.PURCHASE, Guard.press(binding.riskLabel, "streaming.app")?.risk)
        assertTrue(binding.valid({ label(it) }, ::dispatched))
    }
    @Test fun `selected text and dispatch ancestor text both invalidate an approval`() {
        val card = Node("Movie card", true)
        val selected = card.child(Node("Buy now"))
        val binding = PressTarget.capture(selected, card) { label(it) }
        selected.text = "Buy a different movie"
        assertFalse(binding.valid({ label(it) }, ::dispatched))
        selected.text = "Buy now"
        card.text = "Another movie card"
        assertFalse(binding.valid({ label(it) }, ::dispatched))
    }
    @Test fun `reparented leaf cannot redirect an approved press to an identical card`() {
        val first = Node("Card", true)
        val selected = first.child(Node("Buy now"))
        val binding = PressTarget.capture(selected, first) { label(it) }
        val second = Node("Card", true)
        second.child(selected)
        assertFalse(binding.valid({ label(it) }, ::dispatched))
    }
}
