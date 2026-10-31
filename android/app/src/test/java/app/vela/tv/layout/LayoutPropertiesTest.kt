package app.vela.tv.layout

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class LayoutPropertiesTest {
    @Test fun `generated compositions preserve row tile order and escaped data through repeated round trips`() {
        repeat(48) { case ->
            val seed = 0x1A90100 + case
            val random = Random(seed)
            val layout = generated(random, seed)
            val variants = listOf(layout, layout.copy(rows = layout.rows.shuffled(random)), layout.copy(rows = layout.rows.take(random.nextInt(1, layout.rows.size + 1))))
            for ((variant, value) in variants.withIndex()) {
                val label = "seed=$seed variant=$variant"
                val wire = Layouts.encode(value)
                val decoded = Layouts.decode(wire).getOrThrow()
                assertEquals(label, value, decoded)
                assertEquals(label, wire, Layouts.encode(decoded))
                assertEquals(label, value.rows.map { it.id }, decoded.rows.map { it.id })
                assertEquals(label, decoded, Layouts.decode(Layouts.encode(decoded)).getOrThrow())
            }
        }
    }

    @Test fun `mutating any generated row fails locally and never poisons a later valid decode`() {
        repeat(32) { case ->
            val seed = 0xBAD1A00 + case
            val random = Random(seed)
            val layout = generated(random, seed)
            val wire = Layouts.encode(layout)
            val obj = Json.parseToJsonElement(wire).jsonObject
            val rows = obj.getValue("rows").jsonArray
            val at = random.nextInt(rows.size)
            fun replaceRow(change: (JsonObject) -> JsonObject): String = JsonObject(obj + ("rows" to JsonArray(
                rows.mapIndexed { index, row -> if (index == at) change(row.jsonObject) else row },
            ))).toString()
            val bad = listOf(
                replaceRow { JsonObject(it + ("id" to JsonPrimitive("../row-$seed"))) },
                replaceRow { JsonObject(it + ("title" to JsonPrimitive("t".repeat(121)))) },
                replaceRow { JsonObject(it - "source") },
                replaceRow { JsonObject(it + ("source" to buildJsonObject { put("type", "unknown-$seed") })) },
                JsonObject(obj + ("version" to JsonPrimitive(Int.MAX_VALUE.toLong() + seed))).toString(),
                // A valid prefix cannot make an over-limit document acceptable.
                wire + " ".repeat(256_001 - wire.length),
            )
            for ((mutation, text) in bad.withIndex()) {
                val label = "seed=$seed mutation=$mutation row=$at"
                assertTrue(label, Layouts.decode(text).isFailure)
                assertEquals(label, layout, Layouts.decode(wire).getOrThrow())
                assertEquals(label, wire, Layouts.encode(layout))
            }
        }
    }

    @Test fun `nested and truncated hostile action values cannot become executable tiles`() {
        repeat(24) { case ->
            val seed = 0xDEE9100 + case
            val random = Random(seed)
            val depth = random.nextInt(32, 129)
            val layout = HomeLayout(rows = listOf(RowSpec("safe", "Quoted \\\"界\n🎬", Source.Pinned(listOf(
                PinnedItem("Ask", TileAction.AskAgent("owner request-$seed")),
                PinnedItem("Open", TileAction.OpenUri("https://example.test/?q=$seed")),
            )))))
            val wire = Layouts.encode(layout)
            val nested = "[".repeat(depth) + JsonPrimitive("not an action } ] \\\"").toString() + "]".repeat(depth)
            val marker = JsonPrimitive("owner request-$seed").toString()
            val malformed = listOf(
                wire.replace(marker, nested),
                wire.replace(marker, "{\"prompt\":$nested}"),
                wire.replace(marker, nested.dropLast(1)),
                wire.take(random.nextInt(1, wire.length)),
            )
            for ((mutation, text) in malformed.withIndex()) {
                val label = "seed=$seed mutation=$mutation depth=$depth"
                assertTrue(label, Layouts.decode(text).isFailure)
                assertEquals(label, layout, Layouts.decode(wire).getOrThrow())
            }
        }
    }

    private fun generated(random: Random, seed: Int): HomeLayout = HomeLayout(rows = List(random.nextInt(2, 21)) { row ->
        val text = "row-$row-$seed \\\"界\n🎬"
        val source = when (row % 4) {
            0 -> Source.Apps(List(random.nextInt(0, 12)) { "app.test.p$it" })
            1 -> Source.Watchlist
            2 -> Source.HomeEntities(List(random.nextInt(0, 12)) { "light.test_$it" })
            else -> Source.Pinned(List(random.nextInt(1, 12)) { tile ->
                val action = when (tile % 4) {
                    0 -> TileAction.AskAgent("request-$tile $text")
                    1 -> TileAction.OpenUri("https://example.test/?q=$seed&tile=$tile", "app.test.player")
                    2 -> TileAction.LaunchApp("app.test.p$tile")
                    else -> TileAction.HomeService("light", "toggle", "light.test_$tile")
                }
                PinnedItem("tile-$tile $text", action, subtitle = if (random.nextBoolean()) text else null)
            })
        }
        RowSpec("row_${seed}_$row", text, source, TileStyle.entries[random.nextInt(TileStyle.entries.size)])
    })
}
