package app.vela.tv.guard

/**
 * The only things the agent asks about first. Everything else it just does. The point is on-screen text (a title, a
 * comment, a web page) talking the agent into something costly, not distrust of the owner.
 */
enum class Risk(val label: String) {
    PURCHASE("spend money"),
    SECURITY("change a lock, alarm, garage door or camera"),
    ACCOUNT("change an account or password"),
    INSTALL("install an app"),
    RESET("reset or erase the device"),
}

data class GuardHit(val risk: Risk, val reason: String)

object Guard {
    private val purchase = Regex(
        """\b(buy|rent|purchase|pre-?order|subscribe|start (your )?(free )?trial|free trial|try (it )?free|upgrade now|pay|pay now|checkout|check out|place order|add to cart|join now|sign up)\b|[$€£¥]\s?\d""",
        RegexOption.IGNORE_CASE,
    )
    private val account = Regex(
        """\b(sign out|log ?out|delete (my |your )?account|remove account|deactivate|change (password|email|pin)|reset (password|pin)|switch account)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val install = Regex("""\b(install|reinstall|download app)\b""", RegexOption.IGNORE_CASE)
    private val storeInstall = Regex("""^\s*(get|download|install|update)\s*$""", RegexOption.IGNORE_CASE)
    private val reset = Regex(
        """\b(factory (data )?reset|reset to factory|erase (everything|all)|wipe|clear (all )?data)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val securityDomains = setOf("lock", "alarm_control_panel", "camera", "siren")
    private val systemDomains = setOf("homeassistant", "hassio", "system_log", "recorder", "backup", "update")
    private val securityEntity = Regex("""garage|gate|lock|alarm|front_door|back_door|camera|siren""", RegexOption.IGNORE_CASE)

    /**
     * Everyday domains that run without asking unless the entity looks like a security device. Scenes, scripts and
     * automations are opaque (one could unlock a door), but "movie mode" must not nag every evening: they pass unless
     * named like a security device. Any domain not listed here asks first.
     */
    private val everyday = setOf(
        "light", "switch", "media_player", "remote", "fan", "climate", "humidifier", "vacuum", "cover",
        "scene", "script", "automation", "input_boolean", "input_number", "input_select", "input_button", "button",
        "notify", "tts", "number", "select",
    )
    private val stores = setOf("com.android.vending", "com.amazon.venezia")
    private val installers = setOf("com.android.packageinstaller", "com.google.android.packageinstaller")

    /** Pressing an element that says [text], in app [pkg]. */
    fun press(text: String?, pkg: String?): GuardHit? {
        if (pkg in installers) return GuardHit(Risk.INSTALL, "the app installer is open")
        val t = text?.trim().orEmpty()
        if (t.isEmpty()) return null
        val quoted = "\"${t.take(80)}\""
        return when {
            reset.containsMatchIn(t) -> GuardHit(Risk.RESET, quoted)
            purchase.containsMatchIn(t) -> GuardHit(Risk.PURCHASE, quoted)
            account.containsMatchIn(t) -> GuardHit(Risk.ACCOUNT, quoted)
            install.containsMatchIn(t) -> GuardHit(Risk.INSTALL, quoted)
            pkg in stores && storeInstall.matches(t) -> GuardHit(Risk.INSTALL, "$quoted in the app store")
            else -> null
        }
    }

    /** Calling Home Assistant service [domain].[service] on [entityIds]. */
    fun homeService(domain: String, service: String, entityIds: List<String>): GuardHit? {
        // "all" (and "none") are Home Assistant's whole-domain targets: judged the same as naming no entity.
        val wholeDomain = entityIds.isEmpty() || entityIds.any { it.trim().lowercase() in setOf("all", "none") }
        val target = if (wholeDomain) "everything in $domain" else entityIds.joinToString()
        val d = domain.lowercase()
        return when {
            d in systemDomains -> GuardHit(Risk.RESET, "$domain.$service changes Home Assistant itself")
            d in securityDomains -> GuardHit(Risk.SECURITY, "$domain.$service on $target")
            entityIds.any { securityEntity.containsMatchIn(it) } -> GuardHit(Risk.SECURITY, "$domain.$service on $target")
            d == "cover" && wholeDomain -> GuardHit(Risk.SECURITY, "$domain.$service on every cover, garage doors included")
            d in setOf("script", "automation", "scene", "button", "input_button") && wholeDomain ->
                GuardHit(Risk.SECURITY, "$domain.$service on every $domain at once")
            d !in everyday -> GuardHit(Risk.SECURITY, "$domain.$service isn't an everyday device")
            else -> null
        }
    }

    private val storeLink = Regex(
        """^\s*(market:|amzn:|intent:.*package=(com\.android\.vending|com\.amazon\.venezia))|play\.google\.com/store|amazon\.[a-z.]+/(gp/mas|.*appstore)|\.apk(\?|#|$)""",
        RegexOption.IGNORE_CASE,
    )
    private val payLink = Regex("""checkout|/buy|/purchase|/subscribe|paypal\.com|pay\.google""", RegexOption.IGNORE_CASE)

    /**
     * Opening a link. [handler] is the package Android resolved the link to, when known: whatever the link looks like,
     * landing in an app store or installer counts as an install.
     */
    fun uri(uri: String, handler: String? = null): GuardHit? = when {
        handler in stores || handler in installers -> GuardHit(Risk.INSTALL, "the link opens $handler")
        storeLink.containsMatchIn(uri) -> GuardHit(Risk.INSTALL, "opens an app store page or app download")
        payLink.containsMatchIn(uri) -> GuardHit(Risk.PURCHASE, "the link looks like a payment page")
        else -> null
    }
}
