package pro.potoki.bekon.agent.access

/**
 * Who may use the phone. The client must name itself with a stable uniqueId.
 * The first id is remembered automatically when auto-accept is on and nobody
 * is trusted yet. Any other id waits for the user. Pause rejects everyone.
 */
class ClientGate(
    private val clients: TrustedClients,
    private val paused: () -> Boolean,
    private val ask: (clientId: String, ip: String) -> Boolean,
) {
    @Synchronized
    fun rejection(clientId: String?, ip: String): String? {
        if (paused()) return PAUSED
        val id = clientId?.trim()?.takeIf { it.isNotEmpty() } ?: return MISSING_ID
        if (id in clients.trusted()) return null
        val first = clients.trusted().isEmpty()
        if (first && clients.autoAcceptNext()) {
            clients.remember(id)
            return null
        }
        if (ask(id, ip)) {
            clients.remember(id)
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
        const val INSTRUCTIONS =
            "Always identify yourself at the start with a stable uniqueId. " +
                "Send it on every request as the header X-Bekon-Client-Id, " +
                "and in initialize as clientInfo.uniqueId. " +
                "The phone remembers an accepted id. A different id needs the user to allow it. " +
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
