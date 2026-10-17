package pro.potoki.bekon.agent.access

/**
 * Who may use the phone. The client must name itself with a stable uniqueId.
 * The first id is remembered automatically when auto-accept is on and nobody
 * is trusted yet. Any other id waits for the user. Pause rejects everyone.
 */
class ClientGate(
    private val clients: TrustedClients,
    private val paused: () -> Boolean,
    private val ask: (clientId: String, ip: String, replacing: String?) -> Boolean,
) {
    // Only the cheap checks are locked. The blocking ask() runs outside the lock so a second
    // request from the same id joins the pending decision instead of queueing a new prompt behind it.
    fun rejection(clientId: String?, ip: String): String? {
        if (paused()) return PAUSED
        val id = clientId?.trim()?.takeIf { it.isNotEmpty() } ?: return MISSING_ID
        if (AgentConnections.wasReplaced(id)) return REPLACED
        // One agent at a time: a different id while one is active needs the owner to replace it.
        val other = AgentConnections.active()?.takeIf { it != id && !AgentConnections.isIdle() }
        if (other != null) {
            if (!ask(id, ip, other)) return BUSY
            synchronized(this) { clients.remember(id) }
            AgentConnections.mark(id)
            return null
        }
        val known = synchronized(this) {
            when {
                id in clients.trusted() -> true
                clients.trusted().isEmpty() && clients.autoAcceptNext() -> { clients.remember(id); true }
                else -> false
            }
        }
        if (known) { AgentConnections.mark(id); return null }
        if (ask(id, ip, null)) {
            synchronized(this) { clients.remember(id) }
            AgentConnections.mark(id)
            return null
        }
        return DENIED
    }

    companion object {
        const val HEADER = "X-Bekon-Client-Id"
        const val PAUSED = "device is being used by user so access declined."
        const val MISSING_ID =
            "Send a stable uniqueId at the start of every connection. " +
                "Put it in the X-Bekon-Client-Id header on every request, " +
                "and in initialize params as clientInfo.uniqueId."
        const val DENIED = "access declined"
        const val BUSY = "another agent is connected to this phone and the owner did not replace it. Only one agent at a time; ask the owner."
        const val REPLACED = "this agent was replaced by another agent on this phone; access declined until the owner restarts the session."
        const val INSTRUCTIONS =
            "Always identify yourself at the start with a stable uniqueId. " +
                "Send it on every request as the header X-Bekon-Client-Id, " +
                "and in initialize as clientInfo.uniqueId. " +
                "The phone remembers an accepted id. A different id needs the user to allow it. " +
                "Only ONE agent can be connected at a time: a second id must be approved by the owner as a replacement, and the replaced agent is refused until the session restarts. " +
                "After you connect, call shortcuts before hunting through the home screen. " +
                "The list may already open the app or page this task needs. " +
                "shortcut_open launches one by id. " +
                "shortcut_add saves an app (target is the package name) or an https url you will open again. " +
                "shortcut_remove deletes one by id."
    }
}

interface TrustedClients {
    fun autoAcceptNext(): Boolean
    fun trusted(): Set<String>
    fun remember(id: String)
}
