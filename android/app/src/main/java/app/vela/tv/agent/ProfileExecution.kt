package app.vela.tv.agent

import app.vela.tv.memory.MemoryQuery
import app.vela.tv.profile.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** One profile/revision, pinned for the entire run. Any profile or memory commit invalidates it. */
class ProfileExecution private constructor(private val store: HouseholdStore, private val document: HouseholdDocument) {
    val profile = document.profiles.single { it.id == document.activeProfileId }
    val revision = document.revision
    fun valid(): Boolean = runCatching { store.snapshot().let { it.activeProfileId == profile.id && it.revision == revision } }.getOrDefault(false)
    fun requireCurrent() { if (!valid()) throw ActionInvalidatedException() }
    fun memory(query: String): String {
        requireCurrent()
        val result = store.retrieveMemory(profile.id, MemoryQuery(query, limit = 8, maxCharacters = 4000))
        requireCurrent()
        return result.json
    }
    companion object {
        fun capture(store: HouseholdStore?): ProfileExecution =
            (store ?: error("Household is not ready. Retry after profile storage is available.")).let { ProfileExecution(it, it.snapshot()) }
    }
}

/** Opaque screen/app routes cannot establish trusted library/rating metadata. Fail closed for restricted content. */
object ProfileToolPolicy {
    fun error(profile: HouseholdProfile, tool: String, args: JsonObject): String? {
        ProfilePolicy.canUseAgent(profile).let { if (!it.allowed) return it.reason }
        if (tool in setOf("home_call", "home_states")) ProfilePolicy.canControlHome(profile).let { if (!it.allowed) return it.reason }
        val r = profile.restrictions
        val contentRestricted = profile.kind == ProfileKind.CHILD || r.allowedMediaProviders != null ||
            r.allowedLibraries != null || r.maxAgeRating != null || !r.allowUnrated
        val opaque = tool.startsWith("screen_") || tool in setOf("wait", "remote", "open_link", "app_open", "app_search", "media_control", "media_status")
        if (opaque && (contentRestricted || r.allowedAppPackages != null))
            return "This route cannot establish the content and app access required by this profile."
        if (tool in setOf("app_open", "app_search")) {
            val pkg = (args["package"] as? JsonPrimitive)?.contentOrNull ?: return "Missing app package."
            ProfilePolicy.canOpenApp(profile, pkg).let { if (!it.allowed) return it.reason }
        }
        if (tool.startsWith("jellyfin_")) ProfilePolicy.canPlay(profile, "jellyfin", null, null).let {
            if (!it.allowed) return it.reason
        }
        if (tool.startsWith("plex_")) try {
            app.vela.tv.media.plex.PlexBrowsePolicy.requireAllowed(profile)
        } catch (e: IllegalStateException) { return e.message ?: "Plex is restricted for this profile." }
        return null
    }
}
