package app.vela.tv.control

import app.vela.tv.agent.ProfileToolPolicy
import app.vela.tv.profile.HouseholdState
import kotlinx.serialization.json.JsonObject

/** Use the actual tool policy for reads as well as actions; unavailable household state fails closed. */
internal fun nowPlayingPolicyError(state: HouseholdState?): String? {
    return try {
        val profile = (state as? HouseholdState.Ready)?.document?.activeProfile
            ?: return "Profiles are unavailable. Open Profiles to recover before viewing playback."
        ProfileToolPolicy.error(profile, "media_status", JsonObject(emptyMap()))
            ?: ProfileToolPolicy.error(profile, "media_control", JsonObject(emptyMap()))
    } catch (_: Exception) {
        "Profile access could not be verified. Open Profiles to recover before viewing playback."
    }
}
