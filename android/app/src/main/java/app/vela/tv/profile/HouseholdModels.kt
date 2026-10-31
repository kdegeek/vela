package app.vela.tv.profile

import app.vela.tv.memory.MemoryEntry
import app.vela.tv.memory.validateMemory
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.Collections

object HouseholdLimits {
    const val SCHEMA_VERSION = 2
    const val MAX_PROFILES = 8
    const val MAX_MEMORIES_PER_PROFILE = 100
    const val MAX_MEMORY_CHARACTERS_PER_PROFILE = 100_000
    const val MAX_DOCUMENT_BYTES = 2 * 1024 * 1024
    const val MAX_MEMORY_TEXT = 2_000
}

enum class HouseholdError {
    INVALID_INPUT, NOT_FOUND, CONFLICT, LIMIT_EXCEEDED, ACTIVE_PROFILE, LAST_PROFILE,
    PROFILE_HAS_MEMORY, UNSUPPORTED_VERSION, CORRUPT_STORAGE, STORAGE_FAILURE, SENSITIVE_MEMORY,
}

/** Messages are safe for owner-facing errors; never return parser/IO causes in a phone response. */
class HouseholdException(val code: HouseholdError, message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

internal fun householdRequire(condition: Boolean, message: String, code: HouseholdError = HouseholdError.INVALID_INPUT) {
    if (!condition) throw HouseholdException(code, message)
}

internal fun validId(id: String) = id.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
internal fun validReference(id: String) = id.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))
internal fun validPackageName(name: String) = name.length <= 255 &&
    name.matches(Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+"))
internal fun validText(text: String, maximum: Int, multiline: Boolean = false): Boolean =
    text.isNotBlank() && text.length <= maximum && text == text.trim() && text.none {
        (it.isISOControl() && !(multiline && (it == '\n' || it == '\t'))) || it == '\u2028' || it == '\u2029'
    } && text.withIndex().all { (i, c) ->
        when {
            c.isHighSurrogate() -> i + 1 < text.length && text[i + 1].isLowSurrogate()
            c.isLowSurrogate() -> i > 0 && text[i - 1].isHighSurrogate()
            else -> true
        }
    }

internal fun <T> List<T>.frozen(): List<T> = Collections.unmodifiableList(ArrayList(this))

@Serializable
enum class ProfileKind { @SerialName("adult") ADULT, @SerialName("child") CHILD }

@Serializable
enum class SubtitlePreference { @SerialName("off") OFF, @SerialName("automatic") AUTOMATIC, @SerialName("on") ON }

/** Opaque references into the connection registry. Never put a URL, token or password in either ID. */
@Serializable
data class LibraryRef(val providerId: String, val libraryId: String)

@Serializable
data class ProfilePreferences(
    val preferredMediaProviders: List<String> = emptyList(),
    val preferredLibraries: List<LibraryRef> = emptyList(),
    val audioLanguages: List<String> = emptyList(),
    val subtitleLanguages: List<String> = emptyList(),
    val subtitles: SubtitlePreference = SubtitlePreference.AUTOMATIC,
    val autoplayNext: Boolean = false,
)

/** Null allowlists mean unrestricted; empty allowlists deny everything. Preferences never grant access. */
@Serializable
data class ProfileRestrictions(
    val allowedMediaProviders: List<String>? = null,
    val allowedLibraries: List<LibraryRef>? = null,
    val allowedAppPackages: List<String>? = null,
    val maxAgeRating: Int? = null,
    val allowUnrated: Boolean = true,
    val allowAgentActions: Boolean = true,
    val allowHomeControl: Boolean = true,
)

@Serializable
data class ProfileDraft(
    val displayName: String,
    val kind: ProfileKind = ProfileKind.ADULT,
    val preferences: ProfilePreferences = ProfilePreferences(),
    val restrictions: ProfileRestrictions = ProfileRestrictions(),
)

@Serializable
data class HouseholdProfile(
    val id: String,
    val displayName: String,
    val kind: ProfileKind,
    val preferences: ProfilePreferences,
    val restrictions: ProfileRestrictions,
    val createdAt: Long,
    val updatedAt: Long,
) {
    fun draft() = ProfileDraft(displayName, kind, preferences, restrictions)
}

@Serializable
data class HouseholdDocument(
    val version: Int = HouseholdLimits.SCHEMA_VERSION,
    val revision: Long,
    val activeProfileId: String,
    val profiles: List<HouseholdProfile>,
    val memories: List<MemoryEntry>,
) {
    val activeProfile: HouseholdProfile get() = profiles.first { it.id == activeProfileId }
}

internal fun validateDraft(draft: ProfileDraft) {
    householdRequire(validText(draft.displayName, 60), "Profile name needs 1–60 trimmed characters without control characters.")
    fun references(values: List<String>, max: Int) {
        householdRequire(values.size <= max && values.distinct().size == values.size && values.all(::validReference), "Invalid or repeated connection reference.")
    }
    fun libraries(values: List<LibraryRef>) {
        householdRequire(values.size <= 32 && values.distinct().size == values.size && values.all {
            validReference(it.providerId) && validReference(it.libraryId)
        }, "Invalid or repeated library reference.")
    }
    with(draft.preferences) {
        references(preferredMediaProviders, 16)
        libraries(preferredLibraries)
        for (languages in listOf(audioLanguages, subtitleLanguages)) {
            householdRequire(languages.size <= 8 && languages.map { it.lowercase(java.util.Locale.ROOT) }.distinct().size == languages.size && languages.all {
                it.length <= 64 && it.matches(Regex("[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*"))
            }, "Invalid or repeated language tag.")
        }
    }
    with(draft.restrictions) {
        allowedMediaProviders?.let { references(it, 16) }
        allowedLibraries?.let(::libraries)
        allowedAppPackages?.let { apps ->
            householdRequire(apps.size <= 100 && apps.distinct().size == apps.size && apps.all(::validPackageName), "Invalid or repeated app package.")
        }
        householdRequire(maxAgeRating == null || maxAgeRating in 0..21, "Age rating must be between 0 and 21.")
        if (draft.kind == ProfileKind.CHILD) {
            householdRequire(maxAgeRating != null && maxAgeRating in 0..17 && !allowUnrated &&
                allowedMediaProviders != null && allowedLibraries != null && allowedAppPackages != null,
                "Child profiles require a rating ceiling, deny unrated content, and explicit provider, library and app allowlists.")
        }
    }
}

internal fun validateDocument(document: HouseholdDocument) {
    householdRequire(document.version == HouseholdLimits.SCHEMA_VERSION, "Unsupported household schema.", HouseholdError.UNSUPPORTED_VERSION)
    householdRequire(document.revision >= 0, "Invalid household revision.")
    householdRequire(document.profiles.size in 1..HouseholdLimits.MAX_PROFILES, "A household needs 1–8 profiles.", HouseholdError.LIMIT_EXCEEDED)
    householdRequire(document.profiles.map { it.id }.distinct().size == document.profiles.size, "Profile IDs must be unique.")
    householdRequire(document.profiles.any { it.id == document.activeProfileId }, "Active profile does not exist.")
    document.profiles.forEach {
        householdRequire(validId(it.id), "Invalid profile ID.")
        householdRequire(it.createdAt >= 0 && it.updatedAt >= it.createdAt, "Invalid profile timestamps.")
        validateDraft(it.draft())
    }
    householdRequire(document.memories.size <= HouseholdLimits.MAX_PROFILES * HouseholdLimits.MAX_MEMORIES_PER_PROFILE, "Too many memories.", HouseholdError.LIMIT_EXCEEDED)
    householdRequire(document.memories.map { it.id }.distinct().size == document.memories.size, "Memory IDs must be unique.")
    val profileIds = document.profiles.map { it.id }.toSet()
    document.memories.forEach {
        validateMemory(it)
        householdRequire(it.profileId in profileIds, "Memory refers to a missing profile.")
    }
    document.memories.groupBy { it.profileId }.values.forEach { entries ->
        householdRequire(entries.size <= HouseholdLimits.MAX_MEMORIES_PER_PROFILE &&
            entries.sumOf { it.text.length } <= HouseholdLimits.MAX_MEMORY_CHARACTERS_PER_PROFILE,
            "Profile memory limit reached. Edit or delete existing memories first.", HouseholdError.LIMIT_EXCEEDED)
    }
}

internal fun HouseholdProfile.frozen() = copy(
    preferences = preferences.copy(
        preferredMediaProviders = preferences.preferredMediaProviders.frozen(),
        preferredLibraries = preferences.preferredLibraries.frozen(),
        audioLanguages = preferences.audioLanguages.frozen(),
        subtitleLanguages = preferences.subtitleLanguages.frozen(),
    ),
    restrictions = restrictions.copy(
        allowedMediaProviders = restrictions.allowedMediaProviders?.frozen(),
        allowedLibraries = restrictions.allowedLibraries?.frozen(),
        allowedAppPackages = restrictions.allowedAppPackages?.frozen(),
    ),
)

internal fun HouseholdDocument.frozen() = copy(
    profiles = profiles.map { it.frozen() }.frozen(),
    memories = memories.map { it.copy(tags = it.tags.frozen()) }.frozen(),
)
