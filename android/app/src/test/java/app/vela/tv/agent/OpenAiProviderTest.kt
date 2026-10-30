package app.vela.tv.agent

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
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

class OpenAiProviderTest {
    private lateinit var server: MockWebServer

    @Before fun start() { server = MockWebServer().also { it.start() } }
    @After fun stop() { server.close() }

    private fun provider(key: String? = "sk-secret-123", path: String = "/v1/") =
        OpenAiProvider(server.url(path).toString(), key, "gpt-test")

    private fun reply(body: String, code: Int = 200) {
        server.enqueue(MockResponse.Builder().code(code).body(body).build())
    }

    private val tool = ToolSpec(
        "click", "Click a thing",
        buildJsonObject { put("type", "object") },
    )

    private val completion = """
        {"id":"chatcmpl-9","object":"chat.completion","model":"gpt-test","choices":[{"index":0,
         "finish_reason":"tool_calls","message":{"role":"assistant","content":"Opening it now.",
         "tool_calls":[
           {"id":"call_a","type":"function","function":{"name":"click","arguments":"{\"label\":\"Netflix\"}"}},
           {"id":"call_b","type":"function","function":{"name":"press","arguments":"{\"key\":\"HOME\"}"}}]}}],
         "usage":{"prompt_tokens":10,"completion_tokens":5}}
    """.trimIndent()

    @Test fun `converts a full history and posts to chat completions`() = runTest {
        reply(completion)
        val history = listOf(
            Msg.User("open netflix"),
            Msg.Assistant(null, listOf(ToolCall("call_1", "click", buildJsonObject { put("label", "Netflix") }))),
            Msg.ToolResult("call_1", "click", "clicked", false),
            Msg.User("and then go home"),
        )
        provider().complete("be brief", history, listOf(tool))

        val req = server.takeRequest()
        assertEquals("/v1/chat/completions", req.url.encodedPath)
        assertEquals("Bearer sk-secret-123", req.headers["Authorization"])
        val body = Json.parseToJsonElement(req.body!!.utf8()).jsonObject
        assertEquals("gpt-test", body["model"]!!.jsonPrimitive.content)
        assertEquals(4096, body["max_tokens"]!!.jsonPrimitive.content.toInt())
        val msgs = body["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("system", "user", "assistant", "tool", "user"), msgs.map { it["role"]!!.jsonPrimitive.content })
        assertEquals("be brief", msgs[0]["content"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, msgs[2]["content"])
        val call = msgs[2]["tool_calls"]!!.jsonArray[0].jsonObject
        assertEquals("call_1", call["id"]!!.jsonPrimitive.content)
        assertEquals("function", call["type"]!!.jsonPrimitive.content)
        val fn = call["function"]!!.jsonObject
        assertEquals("click", fn["name"]!!.jsonPrimitive.content)
        assertEquals("""{"label":"Netflix"}""", fn["arguments"]!!.jsonPrimitive.content)
        assertEquals("call_1", msgs[3]["tool_call_id"]!!.jsonPrimitive.content)
        assertEquals("clicked", msgs[3]["content"]!!.jsonPrimitive.content)
        val spec = body["tools"]!!.jsonArray[0].jsonObject
        assertEquals("function", spec["type"]!!.jsonPrimitive.content)
        assertEquals("click", spec["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("object", spec["function"]!!.jsonObject["parameters"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test fun `no Authorization header without a key and no tools key when none`() = runTest {
        reply("""{"choices":[{"message":{"role":"assistant","content":"hi"}}]}""")
        provider(key = "  ").complete("s", listOf(Msg.User("yo")), emptyList())
        val req = server.takeRequest()
        assertNull(req.headers["Authorization"])
        val body = Json.parseToJsonElement(req.body!!.utf8()).jsonObject
        assertFalse(body.containsKey("tools"))
    }

    @Test fun `assistant without calls omits tool_calls`() = runTest {
        reply("""{"choices":[{"message":{"content":"ok"}}]}""")
        provider().complete("s", listOf(Msg.User("a"), Msg.Assistant("hello"), Msg.User("b")), emptyList())
        val body = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        val assistant = body["messages"]!!.jsonArray[2].jsonObject
        assertFalse(assistant.containsKey("tool_calls"))
        assertEquals("hello", assistant["content"]!!.jsonPrimitive.content)
    }

    @Test fun `parses text and multiple tool calls`() = runTest {
        reply(completion)
        val turn = provider().complete("s", listOf(Msg.User("x")), listOf(tool))
        assertEquals("Opening it now.", turn.text)
        assertEquals(listOf("call_a", "call_b"), turn.calls.map { it.id })
        assertEquals(listOf("click", "press"), turn.calls.map { it.name })
        assertEquals("Netflix", turn.calls[0].arguments["label"]!!.jsonPrimitive.content)
        assertEquals("HOME", turn.calls[1].arguments["key"]!!.jsonPrimitive.content)
    }

    @Test fun `null content and blank or invalid arguments do not crash`() = runTest {
        reply(
            """{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[
              {"id":"c1","type":"function","function":{"name":"a","arguments":""}},
              {"id":"c2","type":"function","function":{"name":"b","arguments":"{not json"}}]}}]}""",
        )
        val turn = provider().complete("s", listOf(Msg.User("x")), listOf(tool))
        assertNull(turn.text)
        assertEquals(JsonObject(emptyMap()), turn.calls[0].arguments)
        assertEquals("{not json", turn.calls[1].arguments["_raw"]!!.jsonPrimitive.content)
    }

    @Test fun `error status carries status and provider message but not the key`() = runTest {
        reply("""{"error":{"message":"Incorrect API key provided: sk-sec***","type":"invalid_request_error"}}""", 401)
        try {
            provider().complete("s", listOf(Msg.User("x")), emptyList())
            fail("expected ProviderException")
        } catch (e: ProviderException) {
            assertTrue(e.message!!, e.message!!.contains("401"))
            assertTrue(e.message!!, e.message!!.contains("Incorrect API key provided"))
            assertFalse(e.message!!.contains("sk-secret-123"))
        }
    }

    @Test fun `unparseable body is a ProviderException`() = runTest {
        reply("<html>oops</html>")
        try {
            provider().complete("s", listOf(Msg.User("x")), emptyList())
            fail("expected ProviderException")
        } catch (e: ProviderException) {
            assertTrue(e.message!!.isNotBlank())
        }
    }

    @Test fun `unreachable host is a ProviderException without the key`() = runTest {
        val url = server.url("/v1").toString()
        server.close()
        try {
            OpenAiProvider(url, "sk-secret-123", "m").complete("s", listOf(Msg.User("x")), emptyList())
            fail("expected ProviderException")
        } catch (e: ProviderException) {
            assertTrue(e.message!!, e.message!!.startsWith("Can't reach"))
            assertFalse(e.message!!.contains("sk-secret-123"))
        }
    }
}
