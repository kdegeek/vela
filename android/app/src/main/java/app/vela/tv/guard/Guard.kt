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
    private val securityDomains = setOf("lock", "alarm_control_panel", "camera")
    private val securityEntity = Regex("""garage|gate|lock|alarm|front_door|back_door""", RegexOption.IGNORE_CASE)
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
        val target = entityIds.joinToString().ifEmpty { "everything in $domain" }
        if (domain in securityDomains) return GuardHit(Risk.SECURITY, "$domain.$service on $target")
        if (entityIds.any { securityEntity.containsMatchIn(it) }) return GuardHit(Risk.SECURITY, "$domain.$service on $target")
        return null
    }

    /** Opening a link: store pages and payment links count as installs or purchases. */
    fun uri(uri: String): GuardHit? = when {
        uri.startsWith("market:") || uri.startsWith("amzn://apps") -> GuardHit(Risk.INSTALL, "opens an app store page")
        Regex("""(checkout|/buy|/purchase|/subscribe|paypal\.com|pay\.google)""", RegexOption.IGNORE_CASE).containsMatchIn(uri) ->
            GuardHit(Risk.PURCHASE, "the link looks like a payment page")
        else -> null
    }
}
