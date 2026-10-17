package pro.potoki.bekon.agent.access

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Who may use the phone, per session.
 *
 * 1. A client without a token names itself (X-Bekon-Client-Id / clientInfo.uniqueId) and the owner is asked on the phone.
 * 2. On Allow the gate issues a random session token (32 bytes, base64url) bound to that client id.
 * 3. Every later request must carry `Authorization: Bearer <token>` (or `X-Bekon-Token`). An approved id without a valid
 *    token gets 401 — the owner is never re-prompted silently.
 * Tokens live in memory only: they die on Stop / session end ([reset]) and when the owner approves a replacement agent.
 * Nothing is remembered across sessions. Pause rejects everyone.
 */
class ClientGate(
    private val clients: TrustedClients,
    private val paused: () -> Boolean,
    private val random: SecureRandom = SecureRandom(),
    private val ask: (clientId: String, ip: String, replacing: String?) -> Boolean,
) {
    sealed class Decision {
        /** [issuedToken] is non-null only on the request that was just approved: hand it to the client. */
        data class Allow(val issuedToken: String?) : Decision()
        data class Reject(val reason: String, val status: Int = 403) : Decision()
    }

    /** clientId -> token for the current session (at most one: one agent at a time). */
    private val tokens = HashMap<String, String>()

    fun check(clientId: String?, ip: String, token: String?): Decision {
        if (paused()) return Decision.Reject(PAUSED)
        val id = clientId?.trim()?.takeIf { it.isNotEmpty() } ?: return Decision.Reject(MISSING_ID)
        if (AgentConnections.wasReplaced(id)) return Decision.Reject(REPLACED)
        val presented = token?.trim()?.takeIf { it.isNotEmpty() }
        val expected = synchronized(this) { tokens[id] }
        if (presented != null) {
            if (expected != null && constantTimeEquals(expected, presented)) {
                AgentConnections.mark(id)
                return Decision.Allow(null)
            }
            return Decision.Reject(INVALID_TOKEN, 401)
        }
        if (expected != null) return Decision.Reject(TOKEN_REQUIRED, 401)

        // No token yet: the owner decides. The blocking ask() runs outside the lock so a second request from the
        // same id joins the pending decision; both approved requests then receive the same token.
        val other = AgentConnections.active()?.takeIf { it != id && !AgentConnections.isIdle() }
        val auto = other == null && clients.autoAcceptNext() && synchronized(this) { tokens.isEmpty() } && AgentConnections.active() == null
        if (!auto && !ask(id, ip, other)) return Decision.Reject(if (other != null) BUSY else DENIED)
        val issued = issue(id)
        AgentConnections.mark(id)
        return Decision.Allow(issued)
    }

    /** Compatibility helper: reason string or null when allowed. */
    fun rejection(clientId: String?, ip: String, token: String? = null): String? =
        (check(clientId, ip, token) as? Decision.Reject)?.reason

    /** Session end / Stop / owner revoke: every token is invalid from now on. */
    @Synchronized fun reset() { tokens.clear() }

    /** Path-token auth for link-control: constant-time match against the issued session token. Returns clientId or null. */
    @Synchronized fun tokenFor(clientId: String): String? = tokens[clientId]

    @Synchronized fun clientForToken(token: String?): String? {
        val t = token?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        for ((id, expected) in tokens) if (constantTimeEquals(expected, t)) return id
        return null
    }

    @Synchronized private fun issue(id: String): String {
        tokens[id]?.let { return it }
        tokens.clear() // one agent at a time: approving a replacement revokes the previous agent's token
        val bytes = ByteArray(32).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).also { tokens[id] = it }
    }

    companion object {
        const val HEADER = "X-Bekon-Client-Id"
        const val TOKEN_HEADER = "X-Bekon-Token"
        const val TOKEN_FIELD = "bekon_session_token"

        fun constantTimeEquals(a: String, b: String): Boolean =
            MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

        /** Token from `Authorization: Bearer …` or `X-Bekon-Token`. */
        fun tokenFrom(authorization: String?, xToken: String?): String? {
            val bearer = authorization?.trim()?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
                ?.substring(7)?.trim()?.takeIf { it.isNotEmpty() }
            return bearer ?: xToken?.trim()?.takeIf { it.isNotEmpty() }
        }

        const val PAUSED = "device is being used by user so access declined."
        const val MISSING_ID =
            "Send a stable uniqueId at the start of every connection. " +
                "Put it in the X-Bekon-Client-Id header on every request, " +
                "and in initialize params as clientInfo.uniqueId."
        const val DENIED = "access declined"
        const val BUSY = "another agent is connected to this phone and the owner did not replace it. Only one agent at a time; ask the owner."
        const val REPLACED = "this agent was replaced by another agent on this phone; access declined until the owner restarts the session."
        const val TOKEN_REQUIRED =
            "unauthorized: this client id was already approved in this session and must send its session token " +
                "(Authorization: Bearer <bekon_session_token>, issued in the approved response and the X-Bekon-Token header). " +
                "If you lost it, ask the owner to Stop and Start the session (or use 'Disconnect agent' in the app) and connect again."
        const val INVALID_TOKEN =
            "unauthorized: invalid or expired session token. Tokens end when the session stops or another agent is approved. " +
                "Call initialize again WITHOUT a token (with your X-Bekon-Client-Id) so the owner can approve you, then use the new token."
        const val TOKEN_ISSUED =
            "Approved by the owner. From now on send 'Authorization: Bearer <bekon_session_token>' (or header X-Bekon-Token) " +
                "together with X-Bekon-Client-Id on EVERY request of this session. The token is valid only until the session stops " +
                "or another agent is approved; after that you get 401 and must initialize again without a token."
        const val INSTRUCTIONS =
            "Always identify yourself at the start with a stable uniqueId: header X-Bekon-Client-Id on every request and " +
                "clientInfo.uniqueId in initialize. The owner approves you on the phone; the approved response carries " +
                "bekon_session_token (also in the X-Bekon-Token response header). Send 'Authorization: Bearer <token>' on every " +
                "later request. Tokens are per session and are never remembered: each new session needs a new approval. " +
                "Only ONE agent can be connected at a time: a second id must be approved by the owner as a replacement, which revokes " +
                "the previous agent's token. " +
                "After you connect, call shortcuts before hunting through the home screen. " +
                "The list may already open the app or page this task needs. " +
                "shortcut_open launches one by id. " +
                "shortcut_add saves an app (target is the package name) or an https url you will open again. " +
                "shortcut_remove deletes one by id."
    }
}

/** Only the developer-build auto-accept toggle remains; approvals are not persisted. */
interface TrustedClients {
    fun autoAcceptNext(): Boolean
}
