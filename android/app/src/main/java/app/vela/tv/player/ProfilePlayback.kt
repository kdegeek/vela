package app.vela.tv.player

import app.vela.tv.profile.HouseholdProfile
import app.vela.tv.profile.ProfilePolicy
import app.vela.tv.profile.ProfilePreferences

/** No policy is inferred from opaque third-party metadata. Unknown library/rating fails closed when restricted. */
internal data class ProfilePlaybackPlan(val profileId: String, val preferences: ProfilePreferences) {
    val storageName: String get() = "vela-playback-$profileId"
    companion object {
        fun forProfile(profile: HouseholdProfile): ProfilePlaybackPlan {
            require(profile.id.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))) { "Invalid playback profile." }
            val decision = ProfilePolicy.canPlay(profile, "jellyfin", libraryId = null, ageRating = null)
            require(decision.allowed) { decision.reason ?: "Playback is unavailable for this profile." }
            return ProfilePlaybackPlan(profile.id, profile.preferences)
        }
    }
}
