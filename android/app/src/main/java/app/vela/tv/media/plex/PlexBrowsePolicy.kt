package app.vela.tv.media.plex

import app.vela.tv.profile.HouseholdProfile
import app.vela.tv.profile.ProfileKind
import app.vela.tv.profile.ProfilePolicy

/** Metadata lacks a trusted normalized age rating; app search cannot bind exact content. Fail closed before IO. */
object PlexBrowsePolicy {
    const val APP_PACKAGE = "com.plexapp.android"
    fun requireAllowed(profile: HouseholdProfile) {
        val media = ProfilePolicy.canPlay(profile, "plex", null, null)
        check(media.allowed) { media.reason ?: "Plex is restricted for this profile." }
        val app = ProfilePolicy.canOpenApp(profile, APP_PACKAGE)
        check(app.allowed) { app.reason ?: "Plex is restricted for this profile." }
        val agent = ProfilePolicy.canUseAgent(profile)
        check(agent.allowed) { agent.reason ?: "App search is disabled for this profile." }
        val r = profile.restrictions
        check(profile.kind != ProfileKind.CHILD && r.allowedMediaProviders == null && r.allowedLibraries == null &&
            r.maxAgeRating == null && r.allowUnrated && r.allowedAppPackages == null) {
            "Plex app search cannot verify the content access required by this profile."
        }
    }
}
