package app.vela.tv.profile

/** Pure decisions to enforce at every playback/app/home/tool boundary, including direct phone operations. */
object ProfilePolicy {
    data class Decision(val allowed: Boolean, val reason: String? = null)

    fun canPlay(profile: HouseholdProfile, providerId: String, libraryId: String?, ageRating: Int?): Decision {
        validateDraft(profile.draft())
        householdRequire(validReference(providerId) && (libraryId == null || validReference(libraryId)), "Invalid media target.")
        householdRequire(ageRating == null || ageRating in 0..21, "Invalid normalized content age rating.")
        val r = profile.restrictions
        return when {
            r.allowedMediaProviders != null && providerId !in r.allowedMediaProviders -> deny("Provider is not allowed for this profile.")
            r.allowedLibraries != null && (libraryId == null || LibraryRef(providerId, libraryId) !in r.allowedLibraries) -> deny("Library is not allowed for this profile.")
            ageRating == null && !r.allowUnrated -> deny("Unrated content is not allowed for this profile.")
            ageRating != null && r.maxAgeRating != null && ageRating > r.maxAgeRating -> deny("Content exceeds this profile's age rating.")
            else -> Decision(true)
        }
    }

    fun canOpenApp(profile: HouseholdProfile, packageName: String): Decision {
        validateDraft(profile.draft())
        householdRequire(validPackageName(packageName), "Invalid app package.")
        return if (profile.restrictions.allowedAppPackages?.contains(packageName) != false) Decision(true)
        else deny("App is not allowed for this profile.")
    }

    fun canUseAgent(profile: HouseholdProfile): Decision {
        validateDraft(profile.draft())
        return if (profile.restrictions.allowAgentActions) Decision(true) else deny("Agent actions are disabled for this profile.")
    }

    fun canControlHome(profile: HouseholdProfile): Decision {
        validateDraft(profile.draft())
        return if (profile.restrictions.allowHomeControl) Decision(true) else deny("Home control is disabled for this profile.")
    }

    private fun deny(reason: String) = Decision(false, reason)
}
