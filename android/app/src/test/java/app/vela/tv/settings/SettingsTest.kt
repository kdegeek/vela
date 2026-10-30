package app.vela.tv.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsTest {
    private val saved = VelaSettings(
        provider = ProviderKind.OPENAI, baseUrl = "https://api.openai.com/v1", apiKey = "sk-real", model = "m",
        homeAssistantUrl = "http://ha:8123", homeAssistantToken = "ha-real",
    )

    @Test
    fun `masked secrets stay when their server stays`() {
        val merged = saved.merge(saved.redacted().copy(model = "m2"))
        assertEquals("sk-real", merged.apiKey)
        assertEquals("ha-real", merged.homeAssistantToken)
        assertEquals("m2", merged.model)
    }

    @Test
    fun `a new server address drops the saved secret`() {
        val merged = saved.merge(saved.redacted().copy(baseUrl = "https://evil.example/v1", homeAssistantUrl = "http://other:8123"))
        assertEquals("", merged.apiKey)
        assertEquals("", merged.homeAssistantToken)
    }

    @Test
    fun `a new key with a new server is taken`() {
        val merged = saved.merge(saved.redacted().copy(provider = ProviderKind.ANTHROPIC, baseUrl = "", apiKey = "sk-ant-new"))
        assertEquals("sk-ant-new", merged.apiKey)
    }
}
