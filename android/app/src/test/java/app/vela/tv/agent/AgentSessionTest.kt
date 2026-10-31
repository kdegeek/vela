package app.vela.tv.agent

import app.vela.tv.guard.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AgentSessionTest {
    @Test fun `replacing a task cannot let old cleanup clear new state`() = runTest {
        val gate = CompletableDeferred<Turn>()
        var instances = 0
        val session = AgentSession(backgroundScope, { confirm ->
            val n = instances++
            Agent(object : Provider { override suspend fun complete(system: String, history: List<Msg>, tools: List<ToolSpec>): Turn {
                if (n == 0) awaitCancellation()
                return gate.await()
            } }, emptyList(), "s", confirm)
        })
        session.ask("first"); runCurrent()
        session.ask("second"); runCurrent()
        assertTrue(session.running.value)
        assertEquals(listOf("canceled", "running"), session.tasks.value.map { it.state })
        gate.complete(Turn("second done", emptyList())); runCurrent()
        assertFalse(session.running.value)
        assertEquals("complete", session.tasks.value.last().state)
        assertEquals("second done", session.lines.value.last().text)
    }
    @Test fun `stale phone approval cannot approve a new prompt`() = runTest {
        var callIndex = 0
        val tool = object : Tool {
            override val spec = ToolSpec("buy", "buy", buildJsonObject { put("type", "object") })
            override suspend fun check(args: JsonObject) = GuardHit(Risk.PURCHASE, "rent")
            override suspend fun run(args: JsonObject) = ToolOutcome("bought")
        }
        val session = AgentSession(backgroundScope, { confirm ->
            Agent(object : Provider { override suspend fun complete(system: String, history: List<Msg>, tools: List<ToolSpec>) =
                Turn(null, listOf(ToolCall("c${callIndex++}", "buy", buildJsonObject {}))) }, listOf(tool), "s", confirm)
        })
        session.ask("one"); runCurrent()
        val first = session.pending.value!!.id
        session.ask("two"); runCurrent()
        val second = session.pending.value!!.id
        assertNotEquals(first, second)
        assertFalse(session.answer(true, first))
        assertEquals(second, session.pending.value!!.id)
        assertTrue(session.answer(false, second)); runCurrent()
        assertFalse(session.running.value)
        assertNull(session.pending.value)
    }
    @Test fun `stop clears busy state and approvals immediately`() = runTest {
        val session = AgentSession(backgroundScope, { confirm -> Agent(object : Provider {
            override suspend fun complete(system: String, history: List<Msg>, tools: List<ToolSpec>): Turn = awaitCancellation()
        }, emptyList(), "s", confirm) })
        session.ask("work"); runCurrent(); session.stop()
        assertFalse(session.running.value)
        assertEquals("canceled", session.tasks.value.single().state)
        runCurrent()
        assertEquals("canceled", session.tasks.value.single().state)
    }
    @Test fun `missing provider is a failed task with recovery instructions`() = runTest {
        val session = AgentSession(backgroundScope, { null })
        session.ask("hi"); runCurrent()
        assertFalse(session.running.value)
        assertEquals("failed", session.tasks.value.single().state)
        assertTrue(session.lines.value.last().text.contains("Settings"))
    }
    @Test fun `context eviction preserves complete user turns`() {
        val first = listOf(Msg.User("a"), Msg.Assistant(null, listOf(ToolCall("c", "x", buildJsonObject {}))), Msg.ToolResult("c", "x", "large".repeat(100)))
        val last = listOf(Msg.User("b"), Msg.Assistant("done"))
        assertEquals(last, ConversationWindow.trim(first + last, maxChars = 100))
    }
}
