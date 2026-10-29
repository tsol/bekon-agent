package pro.potoki.bekon.agent.access

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Who may use the phone, per session.
 *
 * 1. A client without a token names itself (X-Bekon-Client-Id / clientInfo.uniqueId) and the owner is asked on the phone.
 * 2. On Allow the gate issues a random session token (32 bytes, base64url) bound to that client id.
 * 3. Every later request must carry `Authorization: Bearer <token>` (or `X-Bekon-Token`). An approved id that lost its
 *    token and initializes WITHOUT one gets a NEW owner prompt for the same id (lost-token recovery): on Allow a fresh
 *    token replaces the old one; on Deny the old session is untouched. The token is never re-issued silently.
 * Tokens live in memory only: they die on Stop / session end ([reset]) and when the owner approves a replacement agent.
 * Nothing is remembered across sessions. Pause rejects everyone.
 */
class ClientGate(
    private val clients: TrustedClients,
    private val paused: () -> Boolean,
    private val random: SecureRandom = SecureRandom(),
    /** How long one request waits for the owner before it is answered `approval_pending` (the prompt stays open). */
    private val holdMs: Long = HOLD_MS,
    private val ask: (clientId: String, ip: String, replacing: String?) -> Boolean,
) {
    sealed class Decision {
        /** [issuedToken] is non-null only on the request that was just approved: hand it to the client. */
        data class Allow(val issuedToken: String?) : Decision()
        data class Reject(val reason: String, val status: Int = 403) : Decision()
        /**
         * The owner has not answered yet. [token] is this client's session token: handed out NOW (so a stateless agent
         * whose HTTP call times out never loses it) but rejected everywhere until the owner taps Allow.
         */
        data class Pending(val token: String) : Decision()
    }

    /** clientId -> token for the current session (at most one: one agent at a time). */
    private val tokens = HashMap<String, String>()

    /** The single open owner prompt (null = none). Its [token] becomes the session token only on Allow. */
    private class Prompt(val id: String, val token: String, val gen: Int) {
        val done = java.util.concurrent.CountDownLatch(1)
        @Volatile var allowed: Boolean? = null
    }
    private var prompt: Prompt? = null
    /** Session generation: [reset] bumps it so a late Allow from a stopped session never issues anything. */
    private var gen = 0
    /** Recently refused pending tokens -> reason, so their holder hears "declined" instead of a bare 401 (bounded). */
    private val closed = object : LinkedHashMap<String, String>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 16
    }

    /** Read-only liveness of [clientId]'s session for [token]. Never asks the owner, never issues or extends anything. */
    enum class Status { ALIVE, PAUSED, PENDING, NO_TOKEN, INVALID, REPLACED, MISSING_ID }

    fun status(clientId: String?, token: String?): Status {
        val id = clientId?.trim()?.takeIf { it.isNotEmpty() } ?: return Status.MISSING_ID
        if (AgentConnections.wasReplaced(id)) return Status.REPLACED
        val presented = token?.trim()?.takeIf { it.isNotEmpty() } ?: return Status.NO_TOKEN
        val (expected, p) = synchronized(this) { tokens[id] to prompt }
        if (expected != null && constantTimeEquals(expected, presented)) return if (paused()) Status.PAUSED else Status.ALIVE
        if (p != null && p.id == id && constantTimeEquals(p.token, presented)) return Status.PENDING
        return Status.INVALID
    }

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
            // The holder of a still-pending token polls its own approval (no new prompt). Polls answer within
            // POLL_HOLD_MS so even a client with a short HTTP timeout always sees the reply.
            val p = synchronized(this) { prompt?.takeIf { it.id == id && constantTimeEquals(it.token, presented) } }
            if (p != null) return await(p, minOf(holdMs, POLL_HOLD_MS))
            synchronized(this) { closed[presented] }?.let { return Decision.Reject(it) }
            return Decision.Reject(INVALID_TOKEN, 401)
        }
        // An approved id WITHOUT its token never gets it back silently (the id is not a secret). Lost-token recovery:
        // the owner is asked again (every time) for the same id; Allow replaces the old token with a new one.
        if (expected != null) {
            val p = synchronized(this) {
                val cur = prompt
                if (cur != null && cur.id != id) return Decision.Reject(APPROVAL_PENDING)
                cur ?: startPrompt(id, ip, null, reissue = true)
            }
            return await(p)
        }

        val other = AgentConnections.active()?.takeIf { it != id && !AgentConnections.isIdle() }
        val auto = other == null && clients.autoAcceptNext() && synchronized(this) { tokens.isEmpty() } && AgentConnections.active() == null
        if (auto) {
            val issued = issue(id)
            AgentConnections.mark(id)
            return Decision.Allow(issued)
        }
        // At most ONE owner prompt at a time: while it is open, other new ids are told to wait instead of stacking
        // more prompts. The same id joins it (as before: the owner is approving this id right now) and gets its token.
        val p = synchronized(this) {
            val cur = prompt
            if (cur != null && cur.id != id) return Decision.Reject(APPROVAL_PENDING)
            cur ?: startPrompt(id, ip, other)
        }
        return await(p)
    }

    /** Caller holds the lock. The owner prompt runs on its own thread, so it outlives any single HTTP request. */
    private fun startPrompt(id: String, ip: String, other: String?, reissue: Boolean = false): Prompt {
        val p = Prompt(id, newToken(), gen)
        prompt = p
        Thread {
            // replacing == id tells the prompt UI this is the same agent asking again (it lost its key).
            val ok = try { ask(id, ip, if (reissue) id else other) } catch (_: Throwable) { false }
            val approved = synchronized(this) {
                if (prompt === p) prompt = null
                val live = ok && p.gen == gen
                if (live) {
                    tokens.clear(); tokens[id] = p.token // one agent at a time: replacement revokes the old token
                    // New session token = new agent session: pill-menu events of an earlier session are never delivered.
                    pro.potoki.bekon.agent.status.UserEvents.newSession()
                }
                else closed[p.token] = if (!ok && other != null) BUSY else PROMPT_CLOSED
                p.allowed = live
                live
            }
            if (approved) AgentConnections.mark(id)
            p.done.countDown()
        }.apply { isDaemon = true; name = "bekon-approval"; start() }
        return p
    }

    private fun await(p: Prompt, ms: Long = holdMs): Decision {
        try { p.done.await(ms, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: InterruptedException) {}
        return when (p.allowed) {
            null -> Decision.Pending(p.token)
            true -> Decision.Allow(p.token)
            false -> Decision.Reject(synchronized(this) { closed[p.token] } ?: PROMPT_CLOSED)
        }
    }

    /** Compatibility helper: reason string or null when allowed. */
    fun rejection(clientId: String?, ip: String, token: String? = null): String? =
        (check(clientId, ip, token) as? Decision.Reject)?.reason

    /** Session end / Stop / owner revoke: every token is invalid from now on. */
    @Synchronized fun reset() {
        tokens.clear(); gen++; prompt?.let { closed[it.token] = PROMPT_CLOSED }; prompt = null
        pro.potoki.bekon.agent.status.UserEvents.newSession()
    }

    fun isPaused(): Boolean = paused()

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
        pro.potoki.bekon.agent.status.UserEvents.newSession()
        return newToken().also { tokens[id] = it }
    }

    private fun newToken(): String {
        val bytes = ByteArray(32).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
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
        /** Default time one request waits for the owner's tap before answering approval_pending. */
        const val HOLD_MS = 10_000L
        /** How long a poll with a pending token waits for the owner before answering approval_pending again. */
        const val POLL_HOLD_MS = 2_000L
        const val PENDING_CODE = -32002
        const val PROMPT_CLOSED =
            "access declined: the owner tapped Deny or did not answer in time (the prompt lasts about 2 minutes), or " +
                "tapped 'Disconnect agent' or 'Stop'. This pending token is void. Ask the human, then call initialize again WITHOUT a token to show a new prompt."
        const val APPROVAL_PENDING =
            "approval_pending: the owner is already being asked to approve another client id. Do not retry with new ids. " +
                "Use ONE stable uniqueId (save it to a file/config and reuse it across runs), wait, then retry."
        const val PENDING_MESSAGE =
            "approval_pending: the owner has not tapped Allow on the phone yet. SAVE error.data.bekon_session_token NOW " +
                "(also in the X-Bekon-Token header) together with your uniqueId. Then call initialize again every 3-5 s " +
                "with the SAME uniqueId and 'Authorization: Bearer <that token>' (each poll answers within ~2 s). When the owner " +
                "taps Allow you get a normal initialize result with approval=approved, and the same token works for every call. " +
                "Do NOT initialize without the token again: that is refused once you are approved."
        const val BUSY = "another agent is connected to this phone and the owner did not replace it. Only one agent at a time; ask the owner."
        const val REPLACED = "this agent was replaced by another agent on this phone; access declined until the owner restarts the session."
        const val TOKEN_REQUIRED =
            "unauthorized: this client id was already approved in this session and must send its session token " +
                "(Authorization: Bearer <bekon_session_token>). The token was handed out in the FIRST initialize response " +
                "(approved, or approval_pending in error.data) and in its X-Bekon-Token header; the id alone never gets it again. " +
                "If you lost it, call initialize again WITHOUT a token: the owner gets a new Allow prompt for the same id " +
                "(the new token replaces the old one). The owner can also tap 'Disconnect agent' (pill menu or the Bekon card)."
        const val INVALID_TOKEN =
            "unauthorized: invalid or expired session token. Tokens end when the owner taps 'Disconnect agent' or 'Stop', " +
                "or another agent is approved. " +
                "Call initialize again WITHOUT a token (with your X-Bekon-Client-Id) so the owner can approve you, then use the new token."
        const val TOKEN_ISSUED =
            "Approved by the owner. Keep sending 'Authorization: Bearer <bekon_session_token>' (or header X-Bekon-Token) " +
                "together with X-Bekon-Client-Id on EVERY request of this session. The token is valid only until the session stops " +
                "or another agent is approved; after that you get 401 and must initialize again without a token."
        /** For agents whose every tool call is a new process (no shared memory). */
        /** Where the token is, in one place: shared by the guide, the instructions and the errors. */
        const val TOKEN_CONTRACT =
            "Token contract: every initialize answer carries bekon_session_token, also in the X-Bekon-Token response " +
                "header. APPROVED (the owner tapped Allow within the ~10 s wait, or you polled after Allow): " +
                "result.bekon_session_token with result.approval = 'approved'. PENDING: error.code -32002 with " +
                "error.data.bekon_session_token. Agents must handle both shapes."
        const val STATELESS_INSTRUCTIONS =
            "Stateless agents (every call is a new process): generate your uniqueId ONCE and save it to a file/config; the " +
                "FIRST initialize response always carries bekon_session_token (result.bekon_session_token when approved, " +
                "error.data.bekon_session_token when approval_pending, and the X-Bekon-Token header): save it at once, before anything else. Never mint a new id per call (each new id is a new " +
                "owner prompt). While pending, repeat initialize with the saved id + token until approval=approved. Later, call " +
                "session_info with the saved id + token (never prompts) to check the session; only if it says invalid (or you " +
                "lost the token), initialize again WITHOUT a token: the owner gets a new Allow prompt."
        private val SHORTCUT_INSTRUCTIONS: String =
            if (pro.potoki.bekon.agent.BuildConfig.GUIDANCE_MODE) "" else (
                " After you connect, call shortcuts before hunting through the home screen. " +
                "The list may already open the app or page this task needs. " +
                "shortcut_open launches one by id. " +
                "shortcut_add saves an app (target is the package name) or an https url you will open again. " +
                "shortcut_remove deletes one by id."
            )
        val INSTRUCTIONS: String =
            "Always identify yourself at the start with a stable uniqueId: header X-Bekon-Client-Id on every request and " +
                "clientInfo.uniqueId in initialize. The first initialize answers within ~10 s and ALWAYS carries " +
                "bekon_session_token (also the X-Bekon-Token header): either approved, or error code -32002 approval_pending " +
                "with the token in error.data while the owner decides. " + TOKEN_CONTRACT + " Save the token, then send 'Authorization: Bearer <token>' " +
                "on every later request (while pending: repeat initialize with it every 3-5 s until approval=approved). Tokens are per session and are never remembered: each new session needs a new approval. " +
                "Only ONE agent can be connected at a time: a second id must be approved by the owner as a replacement, which revokes " +
                "the previous agent's token. " + STATELESS_INSTRUCTIONS + (if (pro.potoki.bekon.agent.BuildConfig.GUIDANCE_MODE) "" else SHORTCUT_INSTRUCTIONS)
    }
}

/** Only the developer-build auto-accept toggle remains; approvals are not persisted. */
interface TrustedClients {
    fun autoAcceptNext(): Boolean
}
