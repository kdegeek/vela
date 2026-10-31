package app.vela.tv.agent

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AgentReliabilityTest {
    private val call = ToolCall("c", "act", buildJsonObject { put("id", 1) })
    private class Action : Tool {
        var ran = 0
        override val spec = ToolSpec("act", "act", Json.parseToJsonElement("""{"type":"object","properties":{"id":{"type":"integer"}},"required":["id"],"additionalProperties":false}""").jsonObject)
        override suspend fun run(args: JsonObject): ToolOutcome { ran++; return ToolOutcome("acted") }
    }
    @Test fun `missing required arguments never execute`() = runTest {
        val action = Action()
        var n = 0
        val p = object : Provider { override suspend fun complete(system: String, history: List<Msg>, tools: List<ToolSpec>) =
            if (n++ == 0) Turn(null, listOf(call.copy(arguments = buildJsonObject {}))) else Turn("done", emptyList()) }
        val result = Agent(p, listOf(action), "s", { _, _ -> true }).run("go")
        assertEquals(0, action.ran)
        assertTrue(result.filterIsInstance<Msg.ToolResult>().single().isError)
    }
    @Test fun `duplicate call ids cannot execute twice`() = runTest {
        val action = Action()
        val p = object : Provider { override suspend fun complete(system: String, history: List<Msg>, tools: List<ToolSpec>) = Turn(null, listOf(call, call)) }
        val events = mutableListOf<AgentEvent>()
        Agent(p, listOf(action), "s", { _, _ -> true }, maxSteps = 1).run("go", events = { events += it })
        assertEquals(0, action.ran)
        assertTrue(events.last() is AgentEvent.Failed)
    }

    @Test fun `denial stops all remaining calls in that batch`() = runTest {
        val action = Action()
        val guarded = object : Tool {
            override val spec = ToolSpec("guarded", "guarded", buildJsonObject { put("type", "object") })
            override suspend fun check(args: JsonObject) = app.vela.tv.guard.GuardHit(app.vela.tv.guard.Risk.PURCHASE, "rent")
            override suspend fun run(args: JsonObject): ToolOutcome = error("must not execute")
        }
        val p = object : Provider { override suspend fun complete(system: String, history: List<Msg>, tools: List<ToolSpec>) =
            Turn(null, listOf(ToolCall("g", "guarded", buildJsonObject {}), call)) }
        val result = Agent(p, listOf(guarded, action), "s", { _, _ -> false }).run("go")
        assertEquals(0, action.ran)
        assertEquals(2, result.filterIsInstance<Msg.ToolResult>().size)
        assertTrue(result.filterIsInstance<Msg.ToolResult>().all { it.isError })
    }
    @Test fun `tool timeout returns unknown-effect error without automatic replay`() = runTest {
        var attempts = 0
        val slow = object : Tool {
            override val spec = ToolSpec("act", "slow", buildJsonObject { put("type", "object") })
            override suspend fun run(args: JsonObject): ToolOutcome { attempts++; delay(1000); return ToolOutcome("late") }
        }
        var n = 0
        val p = object : Provider { override suspend fun complete(system: String, history: List<Msg>, tools: List<ToolSpec>) =
            if (n++ == 0) Turn(null, listOf(call)) else Turn("stopped", emptyList()) }
        val result = Agent(p, listOf(slow), "s", { _, _ -> true }, toolTimeoutMs = 10).run("go")
        assertEquals(1, attempts)
        assertTrue(result.filterIsInstance<Msg.ToolResult>().single().content.contains("effect is unknown"))
    }
    @Test fun `a stuck model stops repeating identical actions`() = runTest {
        var n = 0
        val action = Action()
        val p = object : Provider { override suspend fun complete(system: String, history: List<Msg>, tools: List<ToolSpec>) = Turn(null, listOf(call.copy(id = "c${n++}"))) }
        val events = mutableListOf<AgentEvent>()
        Agent(p, listOf(action), "s", { _, _ -> true }).run("go", events = { events += it })
        assertEquals(4, action.ran)
        assertTrue((events.last() as AgentEvent.Failed).message.contains("repeating"))
    }
    @Test fun `a model cannot claim completion with an empty response`() = runTest {
        val p = object : Provider { override suspend fun complete(system: String, history: List<Msg>, tools: List<ToolSpec>) = Turn(null, emptyList()) }
        val events = mutableListOf<AgentEvent>()
        Agent(p, emptyList(), "s", { _, _ -> true }).run("go", events = { events += it })
        assertTrue(events.single() is AgentEvent.Failed)
    }
    @Test fun `long tasks evict complete tool exchanges to stay within context`() {
        val messages = listOf(Msg.User("goal")) + (1..40).flatMap { n -> listOf(
            Msg.Assistant(null, listOf(call.copy(id = "$n"))), Msg.ToolResult("$n", "act", "x".repeat(24000))) }
        val trimmed = ConversationWindow.trim(messages)
        assertTrue(trimmed.sumOf { it.toString().length } <= 100000)
        assertEquals(Msg.User("goal"), trimmed.first())
        val calls = trimmed.filterIsInstance<Msg.Assistant>().flatMap { it.calls }.map { it.id }
        assertEquals(calls, trimmed.filterIsInstance<Msg.ToolResult>().map { it.callId })
    }
}
