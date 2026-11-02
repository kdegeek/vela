package app.vela.tv.agent

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class AnthropicProviderTest {
    private lateinit var server: MockWebServer

    @Before fun start() { server = MockWebServer().also { it.start() } }
    @After fun stop() { server.close() }

    private val key = "sk-ant-secret-xyz"
    private fun provider() = AnthropicProvider(key, baseUrl = server.url("/").toString().trimEnd('/'))

    private fun reply(body: String, code: Int = 200) {
        server.enqueue(MockResponse.Builder().code(code).body(body).build())
    }

    private val schema = buildJsonObject { put("type", "object") }
    private val tools = listOf(ToolSpec("click", "Click", schema), ToolSpec("press", "Press a key", schema))

    private val message = """
        {"id":"msg_01","type":"message","role":"assistant","model":"claude-sonnet-5-5",
         "content":[{"type":"text","text":"Opening "},{"type":"text","text":"it now."},
           {"type":"tool_use","id":"toolu_a","name":"click","input":{"label":"Netflix"}},
           {"type":"tool_use","id":"toolu_b","name":"press","input":{"key":"HOME"}}],
         "stop_reason":"tool_use","usage":{"input_tokens":12,"output_tokens":9}}
    """.trimIndent()

    private suspend fun sentBody(history: List<Msg>): JsonObject {
        reply(message)
        provider().complete("be brief", history, tools)
        return Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
    }

    @Test fun `sends headers, endpoint and cache markers`() = runTest {
        reply(message)
        provider().complete("be brief", listOf(Msg.User("hi")), tools)
        val req = server.takeRequest()
        assertEquals("/v1/messages", req.url.encodedPath)
        assertEquals(key, req.headers["x-api-key"])
        assertEquals("2023-06-01", req.headers["anthropic-version"])
        assertTrue(req.headers["content-type"]!!.startsWith("application/json"))
        val body = Json.parseToJsonElement(req.body!!.utf8()).jsonObject
        assertEquals("claude-sonnet-5-5", body["model"]!!.jsonPrimitive.content)
        assertEquals(4096, body["max_tokens"]!!.jsonPrimitive.content.toInt())
        val sys = body["system"]!!.jsonArray.single().jsonObject
        assertEquals("be brief", sys["text"]!!.jsonPrimitive.content)
        assertEquals("ephemeral", sys["cache_control"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        val t = body["tools"]!!.jsonArray.map { it.jsonObject }
        assertFalse(t[0].containsKey("cache_control"))
        assertEquals("ephemeral", t[1]["cache_control"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("object", t[0]["input_schema"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test fun `merges tool results and following user text into one user message`() = runTest {
        val body = sentBody(
            listOf(
                Msg.User("open netflix"),
                Msg.Assistant(
                    "On it.",
                    listOf(
                        ToolCall("t1", "click", buildJsonObject { put("label", "Netflix") }),
                        ToolCall("t2", "press", buildJsonObject { put("key", "HOME") }),
                    ),
                ),
                Msg.ToolResult("t1", "click", "clicked", false),
                Msg.ToolResult("t2", "press", "no such key", true),
                Msg.User("now go back"),
            ),
        )
        val msgs = body["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("user", "assistant", "user"), msgs.map { it["role"]!!.jsonPrimitive.content })

        val assistant = msgs[1]["content"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("text", "tool_use", "tool_use"), assistant.map { it["type"]!!.jsonPrimitive.content })
        assertEquals("t1", assistant[1]["id"]!!.jsonPrimitive.content)
        assertEquals("Netflix", assistant[1]["input"]!!.jsonObject["label"]!!.jsonPrimitive.content)

        val last = msgs[2]["content"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("tool_result", "tool_result", "text"), last.map { it["type"]!!.jsonPrimitive.content })
        assertEquals("t1", last[0]["tool_use_id"]!!.jsonPrimitive.content)
        assertFalse(last[0].containsKey("is_error"))
        assertEquals("true", last[1]["is_error"]!!.jsonPrimitive.content)
        assertEquals("now go back", last[2]["text"]!!.jsonPrimitive.content)
    }

    @Test fun `assistant with only tool calls has no text block`() = runTest {
        val body = sentBody(
            listOf(
                Msg.User("x"),
                Msg.Assistant(null, listOf(ToolCall("t1", "click", JsonObject(emptyMap())))),
                Msg.ToolResult("t1", "click", "ok"),
            ),
        )
        val assistant = body["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonArray
        assertEquals(1, assistant.size)
        assertEquals("tool_use", assistant[0].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test fun `parses concatenated text and multiple tool calls`() = runTest {
        reply(message)
        val turn = provider().complete("s", listOf(Msg.User("x")), tools)
        assertEquals("Opening it now.", turn.text)
        assertEquals(listOf("toolu_a", "toolu_b"), turn.calls.map { it.id })
        assertEquals("Netflix", turn.calls[0].arguments["label"]!!.jsonPrimitive.content)
        assertEquals("HOME", turn.calls[1].arguments["key"]!!.jsonPrimitive.content)
    }

    @Test fun `reply with no text has null text`() = runTest {
        reply("""{"content":[{"type":"tool_use","id":"t","name":"click","input":{}}]}""")
        val turn = provider().complete("s", listOf(Msg.User("x")), tools)
        assertNull(turn.text)
        assertEquals(1, turn.calls.size)
    }

    @Test fun `error status carries status and provider message but not the key`() = runTest {
        reply("""{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}""", 401)
        try {
            provider().complete("s", listOf(Msg.User("x")), tools)
            fail("expected ProviderException")
        } catch (e: ProviderException) {
            assertEquals("Anthropic said 401: invalid x-api-key", e.message)
            assertFalse(e.message!!.contains(key))
        }
    }

    @Test fun `unparseable body is a ProviderException`() = runTest {
        reply("not json at all")
        try {
            provider().complete("s", listOf(Msg.User("x")), tools)
            fail("expected ProviderException")
        } catch (e: ProviderException) {
            assertTrue(e.message!!.isNotBlank())
        }
    }
}
