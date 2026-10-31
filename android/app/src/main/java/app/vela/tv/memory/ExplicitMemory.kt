package app.vela.tv.memory

import app.vela.tv.profile.HouseholdError
import app.vela.tv.profile.HouseholdLimits
import app.vela.tv.profile.frozen
import app.vela.tv.profile.householdRequire
import app.vela.tv.profile.validId
import app.vela.tv.profile.validText
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Locale

@Serializable
enum class MemoryKind { @SerialName("preference") PREFERENCE, @SerialName("fact") FACT }

/** Selected by the trusted integration, never inferred from screen text or a model response. */
@Serializable
enum class OwnerMemorySource {
    @SerialName("owner_tv") TV,
    @SerialName("owner_phone") PHONE,
    @SerialName("owner_confirmed_agent") CONFIRMED_AGENT,
}

/** An explicit save/edit gesture or an approval of the exact proposed text must precede this call. */
@Serializable
data class OwnerMemoryWrite(
    val kind: MemoryKind,
    val text: String,
    val source: OwnerMemorySource,
    val ownerConfirmed: Boolean,
    val tags: List<String> = emptyList(),
    val shareWithAgent: Boolean = true,
)

@Serializable
data class MemoryEntry(
    val id: String,
    val profileId: String,
    val kind: MemoryKind,
    val text: String,
    val source: OwnerMemorySource,
    val tags: List<String> = emptyList(),
    val shareWithAgent: Boolean = true,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
data class MemoryPage(val profileId: String, val revision: Long, val total: Int, val entries: List<MemoryEntry>)

data class MemoryQuery(val text: String = "", val limit: Int = 8, val maxCharacters: Int = 4_000)

@Serializable
data class AgentMemoryEntry(val id: String, val kind: MemoryKind, val text: String, val tags: List<String>)

/** Data for a model message/tool result, never system instructions or an authorization to act. */
@Serializable
data class AgentMemoryContext(
    val profileId: String,
    val revision: Long,
    val entries: List<AgentMemoryEntry>,
    val hasMore: Boolean,
)

/** [json] is the complete context, including envelope/escaping, within the requested character budget. */
data class RetrievedMemory(val context: AgentMemoryContext, val json: String)

internal fun validateOwnerWrite(write: OwnerMemoryWrite) {
    householdRequire(write.ownerConfirmed, "Memory requires the owner's explicit save or confirmation.")
    validateContent(write.text, write.tags)
}

internal fun validateMemory(entry: MemoryEntry) {
    householdRequire(validId(entry.id) && validId(entry.profileId), "Invalid memory or profile ID.")
    householdRequire(entry.createdAt >= 0 && entry.updatedAt >= entry.createdAt, "Invalid memory timestamps.")
    validateContent(entry.text, entry.tags)
}

private fun validateContent(text: String, tags: List<String>) {
    householdRequire(validText(text, HouseholdLimits.MAX_MEMORY_TEXT, multiline = true), "Memory needs 1–2000 trimmed characters without unsafe control characters.")
    householdRequire(tags.size <= 8 && tags.distinct().size == tags.size && tags.all {
        it.matches(Regex("[a-z0-9][a-z0-9_-]{0,31}"))
    }, "Memory needs at most eight unique lowercase tags, each 1–32 letters, digits, - or _.")
    // A backstop, not a credential detector. The integration must never feed credentials/observations here.
    val sensitive = listOf(
        Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----"),
        Regex("(?i)\\bbearer\\s+[a-z0-9._~+/-]{8,}"),
        Regex("\\bsk-[A-Za-z0-9_-]{16,}"),
        Regex("(?i)\\b(?:api[_ -]?key|access[_ -]?token|password|secret|authorization)\\s*(?:[:=]|is\\b)\\s*\\S+"),
    ).any { it.containsMatchIn(text) }
    householdRequire(!sensitive, "Store credentials in Connections, not household memory.", HouseholdError.SENSITIVE_MEMORY)
}

/** Bounded, deterministic lexical retrieval. No embedding service, background learning or cross-profile search. */
internal object MemoryRetrieval {
    private val json = Json { encodeDefaults = true }

    fun retrieve(profileId: String, revision: Long, memories: List<MemoryEntry>, query: MemoryQuery): RetrievedMemory {
        householdRequire(query.text.length <= 500 && query.limit in 0..20 && query.maxCharacters in 256..8_000,
            "Memory retrieval allows a 500-character query, 0–20 entries and a 256–8000 character budget.")
        val terms = Regex("[\\p{L}\\p{N}]{1,64}").findAll(query.text.lowercase(Locale.ROOT))
            .take(20).map { it.value }.distinct().toList()
        val candidates = memories.asSequence().filter { it.profileId == profileId && it.shareWithAgent }
            .map { entry ->
                val text = entry.text.lowercase(Locale.ROOT)
                entry to terms.sumOf { term -> (if (entry.tags.any { term in it }) 4 else 0) + (if (term in text) 1 else 0) }
            }
            .filter { query.text.isBlank() || (terms.isNotEmpty() && it.second > 0) }
            .sortedWith(compareByDescending<Pair<MemoryEntry, Int>> { it.second }.thenByDescending { it.first.updatedAt }.thenBy { it.first.id })
            .map { AgentMemoryEntry(it.first.id, it.first.kind, it.first.text, it.first.tags.frozen()) }.toList()
        val selected = mutableListOf<AgentMemoryEntry>()
        for (entry in candidates) {
            if (selected.size == query.limit) break
            // Use the longer hasMore=false encoding, then check the final envelope below too.
            val proposal = AgentMemoryContext(profileId, revision, selected + entry, hasMore = false)
            if (json.encodeToString(AgentMemoryContext.serializer(), proposal).length <= query.maxCharacters) selected.add(entry)
        }
        val context = AgentMemoryContext(profileId, revision, selected.frozen(), selected.size < candidates.size)
        val encoded = json.encodeToString(AgentMemoryContext.serializer(), context)
        householdRequire(encoded.length <= query.maxCharacters, "Memory context envelope exceeds the budget.")
        return RetrievedMemory(context, encoded)
    }
}
