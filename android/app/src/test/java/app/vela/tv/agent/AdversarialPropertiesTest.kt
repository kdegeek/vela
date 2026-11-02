package app.vela.tv.agent

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

/** Fixed seeds and bounded inputs: failures identify a reproducible case, never a timed race. */
@OptIn(ExperimentalCoroutinesApi::class)
class AdversarialPropertiesTest {
    @Test fun `invalid envelope anywhere in a generated batch prevents every preparation and effect`() = runTest {
        repeat(24) { case ->
            val seed = 0xBA7C000 + case
            val random = Random(seed)
            val size = random.nextInt(2, 13)
            val original = List(size) { ToolCall("new-$it", "act", buildJsonObject { put("n", it) }) }
            val at = random.nextInt(size)
            val prior = ToolCall("prior", "act", buildJsonObject { put("n", -1) })
            val history = listOf(Msg.User("earlier"), Msg.Assistant(null, listOf(prior)), Msg.ToolResult(prior.id, prior.name, "observed"))
            val mutations = listOf(
                original.toMutableList().apply { this[at] = this[at].copy(id = " \t") },
                original.toMutableList().apply { this[at] = this[at].copy(name = "\n") },
                original.toMutableList().apply { this[at] = this[at].copy(id = this[(at + 1) % size].id) },
                original.toMutableList().apply { this[at] = this[at].copy(id = prior.id) },
                List(random.nextInt(13, 25)) { original[it % size].copy(id = "extra-$it") },
            )
            for ((mutation, batch) in mutations.withIndex()) {
                val label = "seed=$seed mutation=$mutation position=$at size=$size"
                var preparations = 0
                var effects = 0
                val action = object : Tool {
                    override val spec = ToolSpec("act", "record", buildJsonObject { put("type", "object") })
                    override suspend fun prepare(args: JsonObject): PreparedToolAction {
                        preparations++
                        return PreparedToolAction(null) { run(args) }
                    }
                    override suspend fun run(args: JsonObject): ToolOutcome { effects++; return ToolOutcome("observed") }
                }
                val events = mutableListOf<AgentEvent>()
                Agent(provider { Turn("untrusted narration", batch) }, listOf(action), "s", { _, _ -> error(label) }, maxSteps = 1)
                    .run("owner request", history, events::add)
                assertEquals(label, 0, preparations)
                assertEquals(label, 0, effects)
                assertTrue(label, events.single() is AgentEvent.Failed)
                // Rejection must not poison a later valid request using the same tool instance.
                Agent(provider { Turn(null, listOf(original.first())) }, listOf(action), "s", { _, _ -> true }, maxSteps = 1)
                    .run("next owner request")
                assertEquals(label, 1, effects)
            }
        }
    }

    @Test fun `generated mixed conversations retain exact paired exchanges and the latest owner request`() {
        repeat(48) { case ->
            val seed = 0xC017E00 + case
            val random = Random(seed)
            val history = mutableListOf<Msg>()
            val originals = mutableMapOf<String, Pair<ToolCall, Msg.ToolResult>>()
            repeat(random.nextInt(1, 11)) { owner ->
                history += Msg.User("owner-$owner-$seed " + "goal".repeat(random.nextInt(1, 100)))
                repeat(random.nextInt(1, 5)) { round ->
                    val calls = List(random.nextInt(1, 6)) { index ->
                        ToolCall("$owner-$round-$index", "act", buildJsonObject {
                            put("text", "a\\\"界".repeat(listOf(1, 50, 2_000, 8_000)[random.nextInt(4)]))
                        })
                    }
                    history += Msg.Assistant("round-$round", calls)
                    calls.forEach { call ->
                        val result = Msg.ToolResult(call.id, call.name, "r".repeat(listOf(1, 100, 12_000, 24_000)[random.nextInt(4)]), random.nextBoolean())
                        originals[call.id] = call to result
                        history += result
                    }
                }
                history += Msg.Assistant("answer-$owner")
            }
            val maxChars = random.nextInt(12_000, 100_001)
            val maxTurns = random.nextInt(1, 9)
            val label = "seed=$seed maxChars=$maxChars maxTurns=$maxTurns"
            val snapshot = history.toList()
            val trimmed = ConversationWindow.trim(history, maxChars, maxTurns)
            assertTrue(label, trimmed.sumOf { it.toString().length.toLong() } <= maxChars)
            assertEquals(label, history.filterIsInstance<Msg.User>().last(), trimmed.filterIsInstance<Msg.User>().last())
            assertTrue(label, trimmed.first() is Msg.User)
            assertTrue(label, trimmed.filterIsInstance<Msg.User>().size <= maxTurns)
            assertEquals(label, snapshot, history)
            assertEquals(label, trimmed, ConversationWindow.trim(trimmed, maxChars, maxTurns))
            var pending = ArrayDeque<ToolCall>()
            trimmed.forEach { msg -> when (msg) {
                is Msg.User -> assertTrue(label, pending.isEmpty())
                is Msg.Assistant -> {
                    assertTrue(label, pending.isEmpty())
                    msg.calls.forEach { assertEquals(label, originals.getValue(it.id).first, it) }
                    pending = ArrayDeque(msg.calls)
                }
                is Msg.ToolResult -> {
                    assertFalse(label, pending.isEmpty())
                    val call = pending.removeFirst()
                    assertEquals(label, call.id, msg.callId)
                    assertEquals(label, call.name, msg.name)
                    assertEquals(label, originals.getValue(call.id).second, msg)
                }
            } }
            assertTrue(label, pending.isEmpty())
        }
    }

    @Test fun `generated typed integer boundary mutations cannot reach preparation`() = runTest {
        var preparations = 0
        val seen = mutableListOf<Int>()
        val action = object : Tool {
            override val spec = ToolSpec("act", "record integer", Json.parseToJsonElement(
                """{"type":"object","properties":{"n":{"type":"integer"}},"required":["n"]}""",
            ).jsonObject)
            override suspend fun prepare(args: JsonObject): PreparedToolAction {
                preparations++
                return PreparedToolAction(null) { run(args) }
            }
            override suspend fun run(args: JsonObject): ToolOutcome {
                seen += args.getValue("n").jsonPrimitive.int
                return ToolOutcome("observed")
            }
        }
        val executor = ToolExecutor(listOf(action))
        repeat(40) { case ->
            val seed = 0x1A70000 + case
            val random = Random(seed)
            val n = when (case % 3) { 0 -> Int.MIN_VALUE; 1 -> Int.MAX_VALUE; else -> random.nextInt() }
            val invalid = listOf(
                JsonPrimitive(n.toString()),
                Json.parseToJsonElement("$n.5"),
                Json.parseToJsonElement("${if (n == 0) 1 else n}e-20"),
                JsonPrimitive(Int.MAX_VALUE.toLong() + random.nextLong(1, 1_000_000)),
                JsonPrimitive(Int.MIN_VALUE.toLong() - random.nextLong(1, 1_000_000)),
                Json.parseToJsonElement("1e${random.nextInt(20, 1024)}"),
                JsonNull, JsonPrimitive(true),
            )
            val before = preparations
            for ((mutation, value) in invalid.withIndex()) {
                val result = executor.execute(ToolCall("$seed-$mutation", "act", buildJsonObject { put("n", value) }), { _, _ -> error("must not approve") })
                assertTrue("seed=$seed mutation=$mutation value=$value", result.isError)
                assertEquals("seed=$seed mutation=$mutation", before, preparations)
            }
            // Exponent notation can represent an exact integer; do not confuse syntax with a fractional value.
            for (value in listOf(JsonPrimitive(n), Json.parseToJsonElement("${n}e0"))) {
                val valid = executor.execute(ToolCall("valid-$seed", "act", buildJsonObject { put("n", value) }), { _, _ -> true })
                assertFalse("seed=$seed value=$value", valid.isError)
                assertEquals("seed=$seed value=$value", n, seen.last())
            }
        }
        assertEquals(80, preparations)
        assertEquals(80, seen.size)
    }

    @Test fun `generated replacement chains drain once and stale cancellation never stops the successor`() = runTest {
        repeat(24) { case ->
            val seed = 0xD2A1000 + case
            val random = Random(seed)
            val drain = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Turn>()
            var created = 0
            var cleanupCount = 0
            val session = AgentSession(backgroundScope, { confirm ->
                val instance = created++
                Agent(provider {
                    if (instance == 0) try { awaitCancellation() } finally {
                        withContext(NonCancellable) { cleanupCount++; drain.await() }
                    } else finish.await()
                }, emptyList(), "s", confirm)
            }, now = { testScheduler.currentTime })
            val ids = mutableListOf(session.submit("routine-$seed")!!)
            runCurrent()
            repeat(random.nextInt(2, 17)) { replacement ->
                ids += session.ask("owner-$seed-$replacement")
                if (random.nextBoolean()) runCurrent()
                assertTrue("seed=$seed replacement=$replacement", session.running.value)
                assertNull(session.submit("must remain busy"))
            }
            val staleCancel = async { session.cancelAndJoin(ids.first()) }
            runCurrent()
            assertFalse("seed=$seed", staleCancel.isCompleted)
            assertEquals("seed=$seed", 1, created)
            assertEquals("seed=$seed", 1, cleanupCount)
            assertEquals("task:${ids.first()}", session.control.owner.value)
            drain.complete(Unit)
            staleCancel.await()
            runCurrent()
            assertEquals("seed=$seed", 2, created)
            assertEquals("task:${ids.last()}", session.control.owner.value)
            for (id in ids.dropLast(1).shuffled(random)) {
                session.cancelAndJoin(id)
                assertEquals("seed=$seed stale=$id", "canceled", session.await(id).state)
                assertTrue("seed=$seed stale=$id", session.running.value)
                assertEquals("task:${ids.last()}", session.control.owner.value)
            }
            finish.complete(Turn("finished-$seed", emptyList()))
            assertEquals("seed=$seed", "complete", session.await(ids.last()).state)
            runCurrent()
            assertFalse("seed=$seed", session.running.value)
            assertNull(session.control.owner.value)
            assertEquals("finished-$seed", session.lines.value.last().text)
            assertEquals("seed=$seed", 1, cleanupCount)
        }
    }

    private fun provider(turn: suspend () -> Turn) = object : Provider {
        override suspend fun complete(system: String, history: List<Msg>, tools: List<ToolSpec>) = turn()
    }
}
