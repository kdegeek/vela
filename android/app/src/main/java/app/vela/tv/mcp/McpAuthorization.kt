package app.vela.tv.mcp

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Not serializable; no raw token is retained or included in toString. Persist generated tokens only in app-private storage. */
class McpCredential(subject: String, token: String, allowedTools: Set<String>? = null) {
    private val digest = sha256(token)
    val principal = McpPrincipal(subject, Base64.getUrlEncoder().withoutPadding().encodeToString(digest), allowedTools?.toSet())
    init {
        require(subject.isNotBlank() && subject.length <= 100)
        require(token.matches(Regex("[A-Za-z0-9_-]{43,256}"))) { "Use a locally generated token with at least 256 bits of entropy." }
    }
    internal fun matches(token: String) = MessageDigest.isEqual(digest, sha256(token))
    override fun toString() = "McpCredential(redacted)"

    companion object {
        fun generateToken(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
        private fun sha256(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
    }
}

data class McpPrincipal(val subject: String, val credentialId: String, val allowedTools: Set<String>?) {
    fun permits(tool: String) = allowedTools == null || tool in allowedTools
}

/** An empty credential set disables access. Read the current snapshot on every request to support local revocation. */
class McpBearerAuthorization(private val credentials: () -> List<McpCredential>) {
    fun authenticate(header: String?): McpPrincipal? {
        if (header == null || header.length > 300) return null
        val parts = header.split(' ')
        if (parts.size != 2 || !parts[0].equals("Bearer", ignoreCase = true)) return null
        if (!parts[1].matches(Regex("[A-Za-z0-9_-]{43,256}"))) return null
        var found: McpPrincipal? = null
        for (credential in credentials()) if (credential.matches(parts[1])) found = credential.principal
        return found
    }
}
