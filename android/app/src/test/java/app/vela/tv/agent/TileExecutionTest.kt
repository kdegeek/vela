package app.vela.tv.agent

import app.vela.tv.guard.*
import app.vela.tv.layout.TileAction
import app.vela.tv.integrations.ControlBusyException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TileExecutionTest {
    @Test fun `actual generated and saved actions require approval on every activation`() = runTest {
        var effects = 0
        val tools = listOf(object : Tool {
            override val spec = ToolSpec("home_call", "home", buildJsonObject {})
            override suspend fun check(args: JsonObject) = Guard.homeService(args["domain"]!!.jsonPrimitive.content,
                args["service"]!!.jsonPrimitive.content, listOf(args["entity_id"]!!.jsonPrimitive.content))
            override suspend fun run(args: JsonObject): ToolOutcome { effects++; return ToolOutcome("done") }
        }, object : Tool {
            override val spec = ToolSpec("open_link", "open", buildJsonObject {})
            override suspend fun check(args: JsonObject) = Guard.uri(args["uri"]!!.jsonPrimitive.content)
            override suspend fun run(args: JsonObject): ToolOutcome { effects++; return ToolOutcome("done") }
        })
        var prompts = 0
        val executor = ToolExecutor(tools)
        for (action in listOf(TileAction.HomeService("cover", "toggle", "cover.device_123"),
            TileAction.HomeService("lock", "unlock", "lock.front"), TileAction.OpenUri("https://example.com/checkout"))) {
            repeat(2) {
                val result = runCatching { TileCalls.perform(action) { call ->
                    executor.execute(call, { _, _ -> prompts++; false })
                } }
                assertTrue(result.isFailure)
                assertTrue(result.exceptionOrNull()!!.message!!.contains("owner said no"))
            }
        }
        assertEquals(6, prompts)
        assertEquals(0, effects)
    }
    @Test fun `active task rejects human phone or synthetic tile without queueing approval`() = runTest {
        val session = AgentSession(backgroundScope, { confirm -> Agent(object : Provider {
            override suspend fun complete(system: String, history: List<Msg>, tools: List<ToolSpec>): Turn = awaitCancellation()
        }, emptyList(), "s", confirm) })
        session.ask("active"); runCurrent()
        var effects = 0
        repeat(3) {
            val failure = runCatching { TileCalls.perform(TileAction.HomeService("lock", "unlock", "lock.front")) {
                session.withController("tile") { effects++; ToolOutcome("must not execute") }
            } }.exceptionOrNull()
            assertTrue(failure is ControlBusyException)
        }
        assertEquals(0, effects)
        assertNull(session.pending.value)
        session.stop(); runCurrent()
    }
}
