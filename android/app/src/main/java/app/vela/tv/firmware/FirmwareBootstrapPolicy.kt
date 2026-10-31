package app.vela.tv.firmware

/** Per-user Settings.Secure keys; an explicit provisioner choice overrides the image default. */
internal object FirmwareSettings {
    const val OWNER_OPT_IN = "vela_firmware_accessibility_opt_in"
    const val ATTEMPTED = "vela_firmware_accessibility_attempted_v1"
    const val USER_SETUP_COMPLETE = "user_setup_complete" // Android 15 @SystemApi setting name.
    const val TV_USER_SETUP_COMPLETE = "tv_user_setup_complete"
    const val HANDS_COMPONENT = "app.vela.tv/app.vela.tv.control.HandsService"
}

internal data class FirmwareEnvironment(
    val firmwareBuild: Boolean,
    val supportedPlatform: Boolean,
    val systemApp: Boolean,
    val ownerUser: Boolean,
    val userUnlocked: Boolean,
    val deviceLocked: Boolean,
    val writeSecureSettingsGranted: Boolean,
    val mediaContentControlGranted: Boolean,
    val deviceProvisioned: String?,
    val userSetupComplete: String?,
    val tvUserSetupComplete: String?,
    val ownerOptIn: String?,
    val imageEnrollmentDefault: Boolean = false,
) {
    val mayEnroll: Boolean get() = firmwareBuild && supportedPlatform && systemApp && ownerUser &&
        userUnlocked && !deviceLocked && writeSecureSettingsGranted && mediaContentControlGranted &&
        deviceProvisioned == "1" && userSetupComplete == "1" &&
        (ownerOptIn == "1" || (ownerOptIn == null && imageEnrollmentDefault)) &&
        // Some TV provisioners do not use this optional AOSP setting. An explicit incomplete or
        // unrecognised value still blocks enrollment when setup has been restarted.
        (tvUserSetupComplete == null || tvUserSetupComplete == "1")
}

/** Preserves every unrelated byte, including order, short names, duplicates and empty tokens. */
internal fun appendHandsService(enabled: String?): String {
    val full = FirmwareSettings.HANDS_COMPONENT
    val short = "app.vela.tv/.control.HandsService"
    if (enabled?.split(':')?.any { it == full || it == short } == true) return enabled
    return if (enabled.isNullOrEmpty()) full else "$enabled:$full"
}

internal interface FirmwareBootstrapStore {
    fun environment(): FirmwareEnvironment
    fun attempted(): String?
    /** Must persist and read back the marker BEFORE any accessibility write. Never erase it. */
    fun markAttempted(): Boolean
    fun enabledServices(): String?
    fun putEnabledServices(value: String): Boolean
}

internal enum class FirmwareBootstrapResult { WAITING, ALREADY_ATTEMPTED, ALREADY_ENABLED, ENABLED, FAILED }

/** One enrollment opportunity, not a repair daemon. Ambiguity/failure never authorizes a retry. */
internal fun enrollFirmwareHands(store: FirmwareBootstrapStore): FirmwareBootstrapResult = try {
    when {
        !store.environment().mayEnroll -> FirmwareBootstrapResult.WAITING
        store.attempted() != null -> FirmwareBootstrapResult.ALREADY_ATTEMPTED
        else -> {
            val before = store.enabledServices()
            val after = appendHandsService(before)
            if (!store.markAttempted() || store.attempted() != "1") FirmwareBootstrapResult.FAILED
            else if (before == after) FirmwareBootstrapResult.ALREADY_ENABLED
            // Don't overwrite a Settings edit or enroll after consent/provisioning/lock state changed.
            // Settings has no public compare-and-set; the provisioner must serialize this handoff.
            else if (!store.environment().mayEnroll || store.enabledServices() != before) FirmwareBootstrapResult.FAILED
            else if (store.putEnabledServices(after) && store.enabledServices() == after) FirmwareBootstrapResult.ENABLED
            else FirmwareBootstrapResult.FAILED
        }
    }
} catch (_: Exception) {
    FirmwareBootstrapResult.FAILED
}
