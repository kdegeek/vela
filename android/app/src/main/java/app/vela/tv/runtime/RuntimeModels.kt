package app.vela.tv.runtime

import app.vela.tv.memory.MemoryKind
import app.vela.tv.profile.HouseholdProfile
import kotlinx.serialization.Serializable

@Serializable data class RuntimeStatus(
    val householdError: String? = null, val routineError: String? = null,
    val phoneError: String? = null, val externalError: String? = null,
)
@Serializable data class ProfileList(val revision: Long, val activeProfileId: String, val profiles: List<HouseholdProfile>)
@Serializable data class MemoryDraft(
    val kind: MemoryKind, val text: String, val tags: List<String> = emptyList(), val shareWithAgent: Boolean = true,
)
class RuntimeUnavailableException : Exception("Storage or runtime is unavailable. Retry from Settings.")
class ManagementConflictException : Exception("This item changed or has pending work. Reload before trying again.")
class ManagementNotFoundException : Exception("The requested item was not found.")

class RoutineProfileException : IllegalStateException("Routine profile or policy changed. Edit it from its intended profile before running again.")
