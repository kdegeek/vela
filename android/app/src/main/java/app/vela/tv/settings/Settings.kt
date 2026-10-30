package app.vela.tv.settings

import android.content.Context
import app.vela.tv.agent.AnthropicProvider
import app.vela.tv.agent.OpenAiProvider
import app.vela.tv.agent.Provider
import app.vela.tv.home.HomeAssistant
import app.vela.tv.layout.HomeLayout
import app.vela.tv.layout.Layouts
import app.vela.tv.media.JellyfinClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.SecureRandom

@Serializable
enum class ProviderKind {
    /** Any OpenAI-compatible endpoint: OpenAI, OpenRouter, Ollama, LM Studio, vLLM, the Vela gateway. */
    @SerialName("openai") OPENAI,
    @SerialName("anthropic") ANTHROPIC,
}

@Serializable
data class VelaSettings(
    val provider: ProviderKind = ProviderKind.ANTHROPIC,
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val jellyfinUrl: String = "",
    val jellyfinToken: String = "",
    val jellyfinUserId: String = "",
    val homeAssistantUrl: String = "",
    val homeAssistantToken: String = "",
    /** Home opens Vela (hold Home to ask). Off by default: the box may already have a home app the owner uses. */
    val ownHome: Boolean = false,
) {
    val agentReady get() = when (provider) {
        ProviderKind.ANTHROPIC -> apiKey.isNotBlank()
        ProviderKind.OPENAI -> baseUrl.isNotBlank() && model.isNotBlank()
    }

    fun makeProvider(): Provider? = if (!agentReady) null else when (provider) {
        ProviderKind.ANTHROPIC -> AnthropicProvider(apiKey, model.ifBlank { "claude-sonnet-5-5" }, baseUrl.ifBlank { "https://api.anthropic.com" })
        ProviderKind.OPENAI -> OpenAiProvider(baseUrl, apiKey, model)
    }

    fun jellyfin(deviceId: String): JellyfinClient? =
        if (jellyfinUrl.isBlank() || jellyfinToken.isBlank()) null
        else JellyfinClient(jellyfinUrl, jellyfinToken, jellyfinUserId.ifBlank { null }, deviceId)

    fun homeAssistant(): HomeAssistant? =
        if (homeAssistantUrl.isBlank() || homeAssistantToken.isBlank()) null else HomeAssistant(homeAssistantUrl, homeAssistantToken)

    /** The same settings with every secret replaced by whether it is set, for the phone page. */
    fun redacted() = copy(
        apiKey = mask(apiKey),
        jellyfinToken = mask(jellyfinToken),
        homeAssistantToken = mask(homeAssistantToken),
    )

    /**
     * Applies an edit from the phone page, where a masked secret means "unchanged". A secret is kept only while the
     * server it belongs to stays the same: pointing a saved key at a new address would send the key there.
     */
    fun merge(edit: VelaSettings) = edit.copy(
        apiKey = if (edit.provider == provider && edit.baseUrl.trim() == baseUrl.trim()) keep(edit.apiKey, apiKey) else unmasked(edit.apiKey),
        jellyfinToken = if (edit.jellyfinUrl.trim() == jellyfinUrl.trim()) keep(edit.jellyfinToken, jellyfinToken) else unmasked(edit.jellyfinToken),
        homeAssistantToken = if (edit.homeAssistantUrl.trim() == homeAssistantUrl.trim()) keep(edit.homeAssistantToken, homeAssistantToken) else unmasked(edit.homeAssistantToken),
    )

    companion object {
        const val MASK = "••••••"
        private fun mask(s: String) = if (s.isBlank()) "" else MASK
        private fun keep(edited: String, old: String) = if (edited == MASK) old else edited
        private fun unmasked(edited: String) = if (edited == MASK) "" else edited
    }
}

/** Settings, the home layout and the phone pairing token, in the app's private storage. */
class SettingsStore(context: Context) {
    private val dir = context.filesDir
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _settings = MutableStateFlow(read("settings.json")?.let { runCatching { json.decodeFromString(VelaSettings.serializer(), it) }.getOrNull() } ?: VelaSettings())
    val settings: StateFlow<VelaSettings> = _settings

    private val _layout = MutableStateFlow(read("layout.json")?.let { Layouts.decode(it).getOrNull() } ?: Layouts.default)
    val layout: StateFlow<HomeLayout> = _layout

    /** Shown on the TV as part of the phone page's address; every phone request must carry it. */
    val pairingToken: String = read("pairing")?.trim()?.takeIf { it.length >= 16 } ?: newToken().also { write("pairing", it) }

    val deviceId: String = read("device-id")?.trim()?.takeIf { it.isNotEmpty() } ?: newToken().also { write("device-id", it) }

    fun update(s: VelaSettings) {
        write("settings.json", json.encodeToString(VelaSettings.serializer(), s))
        _settings.value = s
    }

    fun setLayout(l: HomeLayout) {
        write("layout.json", Layouts.encode(l))
        _layout.value = l
    }

    private fun read(name: String): String? = File(dir, name).takeIf { it.exists() }?.readText()

    private fun write(name: String, text: String) {
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(text)
        tmp.renameTo(File(dir, name))
    }

    private fun newToken(): String {
        val b = ByteArray(16)
        SecureRandom().nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }
}
