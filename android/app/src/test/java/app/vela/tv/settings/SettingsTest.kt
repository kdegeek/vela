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

    @Test fun `server addresses cannot hide credentials or alter URL routing`() {
        for (url in listOf("ftp://server", "https://user:pass@server", "https://server/?api_key=secret", "https://server/#fragment", "not a url")) {
            org.junit.Assert.assertTrue(url, runCatching { saved.copy(baseUrl = url).validated() }.isFailure)
        }
    }
    @Test fun `private LAN servers and reverse proxy paths are accepted`() {
        assertEquals("http://ha.local:8123/proxy", saved.copy(baseUrl = "http://ha.local:8123/proxy").validated().baseUrl)
    }
    @Test fun `jellyfin requires an explicit user for progress and browsing`() {
        org.junit.Assert.assertNull(VelaSettings(jellyfinUrl = "http://jf", jellyfinToken = "t").jellyfin("device"))
    }
    @Test fun `invalid appearance and credential newlines are rejected`() {
        org.junit.Assert.assertTrue(runCatching { saved.copy(appearance = "unknown").validated() }.isFailure)
        org.junit.Assert.assertTrue(runCatching { saved.copy(apiKey = "a\nb").validated() }.isFailure)
    }

    @Test fun `Plex is optional masked and never included in settings display`() {
        val saved = VelaSettings(plexUrl = "http://plex.local:32400", plexToken = "plex-secret")
        assertEquals(VelaSettings.MASK, saved.redacted().plexToken)
        assertEquals("plex-secret", saved.merge(saved.redacted()).plexToken)
        assertEquals("plex-secret", saved.merge(saved.redacted().copy(plexUrl = " http://plex.local:32400 ")).plexToken)
        assertEquals("", saved.merge(saved.redacted().copy(plexToken = "")).plexToken)
        assertEquals("", saved.merge(saved.redacted().copy(plexUrl = "http://other:32400")).plexToken)
        assertEquals("new-token", saved.merge(saved.redacted().copy(plexUrl = "http://other:32400", plexToken = "new-token")).plexToken)
        org.junit.Assert.assertFalse(saved.toString().contains("plex-secret"))
        org.junit.Assert.assertNull(VelaSettings().plex("device"))
        org.junit.Assert.assertNull(saved.copy(plexToken = "").plex("device"))
        org.junit.Assert.assertNotNull(saved.plex("device"))
    }
    @Test fun `Plex rejects credential addresses unsafe tokens and oversized addresses`() {
        for (url in listOf("https://user:pass@plex", "http://plex/?X-Plex-Token=token", "http://plex/#secret", "ftp://plex", "http://plex/" + "a".repeat(2048))) {
            org.junit.Assert.assertTrue(runCatching { VelaSettings(plexUrl = url).validated() }.isFailure)
        }
        for (token in listOf("a\nb", "a\rb", "a\tb", "bad token", "a".repeat(8193))) {
            org.junit.Assert.assertTrue(runCatching { VelaSettings(plexToken = token).validated() }.isFailure)
        }
        assertEquals("http://plex/proxy", VelaSettings(plexUrl = "http://plex/proxy").validated().plexUrl)
    }
}
