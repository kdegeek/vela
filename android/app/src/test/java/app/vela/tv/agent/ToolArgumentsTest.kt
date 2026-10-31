package app.vela.tv.agent

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ToolArgumentsTest {
    private val spec = ToolSpec("key", "key", Json.parseToJsonElement("""{"type":"object","properties":{"key":{"type":"string","enum":["up","down"]},"times":{"type":"integer"},"data":{"type":"object"}},"required":["key"]}""").jsonObject)
    private fun error(args: String) = ToolArguments.error(spec, Json.parseToJsonElement(args).jsonObject)
    @Test fun `valid arguments pass`() { assertNull(error("""{"key":"up","times":2,"data":{}}""")) }
    @Test fun `invalid enum never turns into a select press`() { assertNotNull(error("""{"key":"reset"}""")) }
    @Test fun `numbers cannot be strings or fractional or overflow Int`() {
        for (value in listOf("\"2\"", "2.5", "2147483648", "null", "true")) assertNotNull(value, error("""{"key":"up","times":$value}"""))
    }
    @Test fun `wrong object type is rejected`() { assertNotNull(error("""{"key":"up","data":[]}""")) }
    @Test fun `unknown arguments are rejected`() { assertNotNull(error("""{"key":"up","anything":true}""")) }
    @Test fun `malformed provider arguments are always rejected`() { assertNotNull(error("""{"_raw":"not json"}""")) }
    @Test fun `empty required strings and oversized values are rejected`() {
        assertNotNull(error("""{"key":""}"""))
        assertNotNull(error("""{"key":"${"a".repeat(64001)}"}"""))
    }
}
