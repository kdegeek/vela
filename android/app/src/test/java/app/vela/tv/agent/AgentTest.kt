package app.vela.tv.agent

import app.vela.tv.guard.GuardHit
import app.vela.tv.guard.Risk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTest {
    /** Plays back scripted turns and records what it was sent. */
    private class Script(vararg turns: Turn) : Provider {
        val queue = ArrayDeque(turns.toList())
        val seen = mutableListOf<List<Msg>>()
        override suspend fun complete(system: String, history: List<Msg>, tools: List<ToolSpec>): Turn {
            seen += history.toList()
            return queue.removeFirstOrNull() ?: Turn("out of script", emptyList())
        }
    }

    private class Echo(private val risk: GuardHit? = null) : Tool {
        val ran = mutableListOf<JsonObject>()
        override val spec = ToolSpec("echo", "echoes", buildJsonObject { put("type", "object") })
        override suspend fun check(args: JsonObject) = risk
        override suspend fun run(args: JsonObject): ToolOutcome {
            ran += args
            return ToolOutcome("echo ${args["v"]?.jsonPrimitive?.content}")
        }
    }

    private fun call(id: String, v: String, name: String = "echo") = ToolCall(id, name, JsonObject(mapOf("v" to JsonPrimitive(v))))

    @Test
    fun `runs tools until the model stops calling them`() = runTest {
        val p = Script(Turn(null, listOf(call("1", "a"))), Turn("done", emptyList()))
        val echo = Echo()
        val events = mutableListOf<AgentEvent>()
        val msgs = Agent(p, listOf(echo), "sys", { _, _ -> true }).run("go", events = { events += it })
        assertEquals(1, echo.ran.size)
        val result = msgs.filterIsInstance<Msg.ToolResult>().single()
        assertEquals("echo a", result.content)
        assertEquals(AgentEvent.Done("done"), events.last())
        assertEquals(4, msgs.size) // user, assistant call, tool result, assistant answer
    }

    @Test
    fun `unknown tools and thrown errors go back to the model`() = runTest {
        val boom = object : Tool {
            override val spec = ToolSpec("boom", "fails", buildJsonObject { put("type", "object") })
            override suspend fun run(args: JsonObject): ToolOutcome = error("no screen")
        }
        val p = Script(Turn(null, listOf(call("1", "x", "nope"), call("2", "x", "boom"))), Turn("ok", emptyList()))
        val msgs = Agent(p, listOf(boom), "sys", { _, _ -> true }).run("go")
        val results = msgs.filterIsInstance<Msg.ToolResult>()
        assertTrue(results.all { it.isError })
        assertTrue(results[0].content.contains("no tool called nope"))
        assertTrue(results[1].content.contains("no screen"))
    }

    @Test
    fun `a declined guarded call does not run`() = runTest {
        val echo = Echo(GuardHit(Risk.PURCHASE, "\"Rent \$3.99\""))
        val p = Script(Turn(null, listOf(call("1", "a"))), Turn("ok", emptyList()))
        val msgs = Agent(p, listOf(echo), "sys", { _, hit -> hit.risk != Risk.PURCHASE }).run("rent it")
        assertTrue(echo.ran.isEmpty())
        val r = msgs.filterIsInstance<Msg.ToolResult>().single()
        assertTrue(r.isError && r.content.contains("spend money"))
    }

    @Test
    fun `stops at the step limit`() = runTest {
        val p = Script(*Array(10) { Turn(null, listOf(call("$it", "a"))) })
        val events = mutableListOf<AgentEvent>()
        Agent(p, listOf(Echo()), "sys", { _, _ -> true }, maxSteps = 3).run("loop", events = { events += it })
        assertEquals(3, p.seen.size)
        assertTrue(events.last() is AgentEvent.Failed)
    }

    @Test
    fun `a provider error ends the run with a message`() = runTest {
        val p = object : Provider {
            override suspend fun complete(system: String, history: List<Msg>, tools: List<ToolSpec>): Turn =
                throw ProviderException("Anthropic said 401: invalid x-api-key")
        }
        val events = mutableListOf<AgentEvent>()
        Agent(p, emptyList(), "sys", { _, _ -> true }).run("hi", events = { events += it })
        assertEquals(AgentEvent.Failed("Anthropic said 401: invalid x-api-key"), events.single())
    }
}
