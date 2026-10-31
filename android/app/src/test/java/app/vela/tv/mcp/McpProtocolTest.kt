package app.vela.tv.mcp

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class McpProtocolTest {
    @Test fun `reject malformed envelopes and batching without exposing payloads`() {
        for (body in listOf("private-secret", "[]", "null", "{}", """{"jsonrpc":"1.0","id":1,"method":"ping"}""",
            """{"jsonrpc":"2.0","id":null,"method":"ping"}""", """{"jsonrpc":"2.0","id":true,"method":"ping"}""",
            """{"jsonrpc":"2.0","id":1.5,"method":"ping"}""", """{"jsonrpc":"2.0","id":9007199254740992,"method":"ping"}""",
            """{"jsonrpc":"2.0","id":1,"method":"ping","params":[]}""", """{"jsonrpc":"2.0","id":1,"method":"ping","result":{}}""")) {
            val fault = assertThrows(McpFault::class.java) { McpProtocol.parse(body) }
            assertFalse(fault.response().toString().contains("private-secret"))
        }
    }

    @Test fun `duplicate keys including escaped keys are rejected before parsing`() {
        for (body in listOf("""{"jsonrpc":"2.0","id":1,"method":"ping","method":"tools/call"}""",
            """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"arguments":{"key":"up","\u006bey":"select"}}}""")) {
            assertEquals(-32700, assertThrows(McpFault::class.java) { McpProtocol.parse(body) }.code)
        }
    }

    @Test fun `byte limit and nesting limit include metadata and chunked-equivalent text`() {
        assertEquals(413, assertThrows(McpFault::class.java) { McpProtocol.parse("界".repeat(100_000)) }.status)
        val deep = """{"jsonrpc":"2.0","id":1,"method":"ping","params":{"a":${"[".repeat(40)}0${"]".repeat(40)}}}"""
        assertEquals(-32700, assertThrows(McpFault::class.java) { McpProtocol.parse(deep) }.code)
    }

    @Test fun `string numeric and negative request IDs retain their types`() {
        for (id in listOf(JsonPrimitive("1"), JsonPrimitive(1), JsonPrimitive(-2))) {
            assertEquals(id, McpProtocol.parse(rpc("ping", id)).id)
        }
        assertNull(McpProtocol.parse(rpc("ping", null)).id)
    }

    @Test fun `token snapshots support revocation scoping and redact secrets`() {
        val token = McpCredential.generateToken()
        val credential = McpCredential("desktop", token, setOf("screen_read"))
        var current = listOf(credential)
        val auth = McpBearerAuthorization { current }
        for (header in listOf(null, token, "Bearer wrong", "Bearer $token extra", "Basic $token")) assertNull(auth.authenticate(header))
        val principal = auth.authenticate("Bearer $token")!!
        assertTrue(principal.permits("screen_read")); assertFalse(principal.permits("remote"))
        assertFalse(credential.toString().contains(token))
        current = emptyList()
        assertNull(auth.authenticate("Bearer $token"))
        assertNotEquals(token, McpCredential.generateToken())
        assertThrows(IllegalArgumentException::class.java) { McpCredential("desktop", "") }
    }
}
