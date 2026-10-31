package app.vela.tv.mcp

import app.vela.tv.agent.ToolOutcome
import app.vela.tv.guard.GuardHit
import app.vela.tv.guard.Risk
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class McpServiceTest {
    @Test fun `legacy initialize negotiates capabilities and gates operations on initialized`() = runTest {
        val f = McpFixture()
        f.service(this).use { service ->
            val init = service.prepare(f.principal, rpc("initialize", params = Json.parseToJsonElement("""{
                "protocolVersion":"future-version","capabilities":{"sampling":{}},"clientInfo":{"name":"desktop","version":"1"}
            }""").jsonObject), emptyMap()) as McpService.Reply
            val result = init.body!!.getValue("result").jsonObject
            assertEquals(McpProtocol.LEGACY, result.string("protocolVersion"))
            assertEquals(setOf("tools"), result.getValue("capabilities").jsonObject.keys)
            val headers = mapOf("MCP-Session-Id" to init.sessionId!!)
            val blocked = service.prepare(f.principal, rpc("tools/list"), headers) as McpService.Reply
            assertEquals(-32600, blocked.body!!.errorCode())
            assertEquals(202, (service.prepare(f.principal, rpc("notifications/initialized", null), headers) as McpService.Reply).status)
            val list = service.prepare(f.principal, rpc("tools/list"), headers) as McpService.Reply
            assertEquals("remote", list.body!!.getValue("result").jsonObject.getValue("tools").jsonArray.single().jsonObject.string("name"))
            assertFalse(list.body.toString().contains("resultType"))
        }
    }

    @Test fun `modern discover and direct tool call need no model or session`() = runTest {
        val f = McpFixture()
        f.service(this).use { service ->
            val discover = service.prepare(f.principal, rpc("server/discover", params = modernParams()), modernHeaders("server/discover")) as McpService.Reply
            assertNull(discover.sessionId)
            val discovery = discover.body!!.getValue("result").jsonObject
            assertEquals("complete", discovery.string("resultType"))
            assertTrue(McpProtocol.CURRENT in discovery.getValue("supportedVersions").jsonArray.map { it.jsonPrimitive.content })
            val call = service.modernCall(f.principal) as McpService.Invocation
            val result = call.await()!!.getValue("result").jsonObject
            assertEquals("complete", result.string("resultType"))
            assertEquals(false, result.getValue("isError").jsonPrimitive.boolean)
            assertTrue(McpProtocol.SERVER_META in result.getValue("_meta").jsonObject)
            assertEquals(1, f.effects)
        }
    }

    @Test fun `modern metadata and required mirrored headers are validated before effects`() = runTest {
        val f = McpFixture()
        f.service(this).use { service ->
            val valid = modernParams("name" to JsonPrimitive("remote"), "arguments" to f.args)
            for (headers in listOf(modernHeaders("tools/call"), modernHeaders("ping", "remote"), modernHeaders("tools/call", "other"), emptyMap())) {
                val bad = service.prepare(f.principal, rpc("tools/call", params = valid), headers) as McpService.Reply
                assertEquals(400, bad.status); assertEquals(-32020, bad.body!!.errorCode())
            }
            val metaMissing = service.prepare(f.principal, rpc("tools/list"), modernHeaders("tools/list")) as McpService.Reply
            assertEquals(-32602, metaMissing.body!!.errorCode())
            val params = Json.parseToJsonElement("""{"_meta":{"${McpProtocol.VERSION_META}":"2030-01-01","${McpProtocol.CAPABILITIES_META}":{}}}""").jsonObject
            val unsupported = service.prepare(f.principal, rpc("tools/list", params = params), modernHeaders("tools/list") + ("MCP-Protocol-Version" to "2030-01-01")) as McpService.Reply
            assertEquals(-32022, unsupported.body!!.errorCode())
            assertTrue(unsupported.body.getValue("error").jsonObject.getValue("data").jsonObject.getValue("supported").jsonArray.isNotEmpty())
            assertEquals(0, f.effects)
        }
    }

    @Test fun `modern names accept official base64 header encoding`() = runTest {
        val f = McpFixture()
        f.service(this).use { service ->
            val exchange = service.prepare(f.principal, rpc("tools/call", params = modernParams("name" to JsonPrimitive("remote"), "arguments" to f.args)),
                modernHeaders("tools/call", "=?base64?cmVtb3Rl?=")) as McpService.Invocation
            assertNotNull(exchange.await()); assertEquals(1, f.effects)
        }
    }

    @Test fun `scope controls discovery and calls and unknown methods return protocol errors`() = runTest {
        val f = McpFixture(); val scoped = f.principal.copy(allowedTools = emptySet())
        f.service(this).use { service ->
            val list = service.prepare(scoped, rpc("tools/list", params = modernParams()), modernHeaders("tools/list")) as McpService.Reply
            assertTrue(list.body!!.getValue("result").jsonObject.getValue("tools").jsonArray.isEmpty())
            val unavailable = service.modernCall(scoped) as McpService.Reply
            assertEquals(-32602, unavailable.body!!.errorCode())
            val unknown = service.prepare(f.principal, rpc("resources/list", params = modernParams()), modernHeaders("resources/list")) as McpService.Reply
            assertEquals(404, unknown.status); assertEquals(-32601, unknown.body!!.errorCode())
            val cursor = service.prepare(f.principal, rpc("tools/list", params = modernParams("cursor" to JsonPrimitive("bad"))), modernHeaders("tools/list")) as McpService.Reply
            assertEquals(-32602, cursor.body!!.errorCode()); assertEquals(0, f.effects)
        }
    }

    @Test fun `malformed calls and id-less calls never run tools`() = runTest {
        val f = McpFixture()
        f.service(this).use { service ->
            for (args in listOf(JsonObject(emptyMap()), buildJsonObject { put("key", "invalid") }, buildJsonObject { put("key", "up"); put("times", 1.1) })) {
                assertEquals(-32602, (service.modernCall(f.principal, args = args) as McpService.Reply).body!!.errorCode())
            }
            val noId = service.prepare(f.principal, rpc("tools/call", null, modernParams("name" to JsonPrimitive("remote"), "arguments" to f.args)), modernHeaders("tools/call", "remote")) as McpService.Reply
            assertEquals(202, noId.status); assertNull(noId.body); assertEquals(0, f.checks)
        }
    }

    @Test fun `duplicate active IDs fail without replaying and tool failures stay in result`() = runTest {
        val f = McpFixture(); val gate = CompletableDeferred<Unit>(); f.run = { gate.await(); ToolOutcome("denied", true) }
        f.service(this).use { service ->
            val first = service.modernCall(f.principal) as McpService.Invocation
            runCurrent()
            val duplicate = service.modernCall(f.principal) as McpService.Reply
            assertEquals(-32600, duplicate.body!!.errorCode()); assertEquals(1, f.effects)
            gate.complete(Unit)
            assertTrue(first.await()!!.getValue("result").jsonObject.getValue("isError").jsonPrimitive.boolean)
        }
    }

    @Test fun `legacy cancellation is session and credential bound and stops suspended approval`() = runTest {
        val f = McpFixture(); f.check = { GuardHit(Risk.PURCHASE, "Buy") }; f.approval = { awaitCancellation() }
        f.service(this).use { service ->
            val session = service.open(f.principal); val other = service.open(f.principal)
            val headers = mapOf("MCP-Session-Id" to session)
            val action = service.prepare(f.principal, rpc("tools/call", params = buildJsonObject { put("name", "remote"); put("arguments", f.args) }), headers) as McpService.Invocation
            runCurrent(); assertEquals(1, f.approvals)
            val cancellation = rpc("notifications/cancelled", null, buildJsonObject { put("requestId", 1) })
            service.prepare(f.principal, cancellation, mapOf("MCP-Session-Id" to other)); runCurrent()
            assertFalse(action.result.isCompleted)
            assertEquals(404, (service.prepare(f.principal.copy(credentialId = "other"), cancellation, headers) as McpService.Reply).status)
            assertEquals(202, (service.prepare(f.principal, cancellation, headers) as McpService.Reply).status)
            assertNull(action.await()); assertEquals(0, f.effects); assertNull(f.control.owner.value)
        }
    }

    @Test fun `session expiry limits version mismatch and deletion are bounded`() = runTest {
        val f = McpFixture(); var now = 0L
        McpService(f.backend(), this, sessionIdleMs = 100, maxSessions = 1, clockMs = { now }).use { service ->
            val session = service.open(f.principal)
            val wrongVersion = service.prepare(f.principal, rpc("ping"), mapOf("MCP-Session-Id" to session, "MCP-Protocol-Version" to "old")) as McpService.Reply
            assertEquals(400, wrongVersion.status)
            val params = Json.parseToJsonElement("""{"protocolVersion":"${McpProtocol.LEGACY}","capabilities":{},"clientInfo":{"name":"test","version":"1"}}""").jsonObject
            assertEquals(429, (service.prepare(f.principal, rpc("initialize", params = params), emptyMap()) as McpService.Reply).status)
            now = 101
            assertEquals(404, (service.prepare(f.principal, rpc("ping"), mapOf("MCP-Session-Id" to session)) as McpService.Reply).status)
            val fresh = service.open(f.principal)
            assertEquals(204, service.terminate(f.principal, fresh, McpProtocol.LEGACY).status)
            assertEquals(404, service.terminate(f.principal, fresh, McpProtocol.LEGACY).status)
            assertEquals(405, service.terminate(f.principal, null, McpProtocol.CURRENT).status)
        }
    }

    @Test fun `inflight limits timeout local stop and close stop pending actions`() = runTest {
        val f = McpFixture(); f.run = { awaitCancellation() }
        McpService(f.backend(), this, maxInFlight = 1, requestTimeoutMs = 10).use { service ->
            val first = service.modernCall(f.principal) as McpService.Invocation
            assertEquals(429, (service.modernCall(f.principal, id = 2) as McpService.Reply).status)
            assertTrue(first.await()!!.getValue("result").jsonObject.getValue("isError").jsonPrimitive.boolean)
            assertNull(f.control.owner.value)
            val next = service.modernCall(f.principal, id = 3) as McpService.Invocation
            runCurrent(); service.cancelAll(); assertNull(next.await()); assertNull(f.control.owner.value)
            val last = service.modernCall(f.principal, id = 4) as McpService.Invocation
            runCurrent(); service.close(); assertNull(last.await())
        }
    }

    @Test fun `competing controller gets a busy error rather than running stale input`() = runTest {
        val f = McpFixture()
        f.service(this).use { service -> f.control.withControl("phone") {
            val action = service.modernCall(f.principal) as McpService.Invocation
            assertEquals(McpProtocol.BUSY, action.await()!!.errorCode()); assertEquals(0, f.checks)
        } }
    }

    @Test fun `per credential rate limit applies to cheap discovery requests`() = runTest {
        val f = McpFixture()
        f.service(this).use { service ->
            repeat(240) { assertEquals(200, (service.prepare(f.principal, rpc("ping", params = modernParams()), modernHeaders("ping")) as McpService.Reply).status) }
            assertEquals(429, (service.prepare(f.principal, rpc("ping", params = modernParams()), modernHeaders("ping")) as McpService.Reply).status)
        }
    }
}
