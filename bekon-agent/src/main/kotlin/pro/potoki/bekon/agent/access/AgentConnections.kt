package pro.potoki.bekon.agent.access

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * ONE agent at a time. [active] is the agent currently allowed to drive the phone; a different id
 * must replace it (owner prompt) and the replaced id is then refused until the session ends.
 * An agent silent for [IDLE_MS] counts as disconnected (home screen shows "Агент не подключён").
 */
object AgentConnections {
    const val IDLE_MS = 120_000L
    /** While an agent_status is shown, this much silence (no request in flight) means the agent is gone. */
    const val STATUS_SILENT_MS = 45_000L
    val inFlight = java.util.concurrent.atomic.AtomicInteger()

    /** Wrap a request: long calls (wait_for_change, coach scripts) are not "silence". */
    inline fun <T> busy(block: () -> T): T {
        inFlight.incrementAndGet()
        try { return block() } finally { touch(); inFlight.decrementAndGet() }
    }
    fun touch() { lastSeen = System.currentTimeMillis() }

    data class Conn(val active: String?, val idle: Boolean, val everConnected: Boolean)

    private val _state = MutableStateFlow(Conn(null, false, false))
    val state: StateFlow<Conn> = _state

    /** Compatibility view: the active agent as a 0/1 list. */
    private val _connected = MutableStateFlow<List<String>>(emptyList())
    val connected: StateFlow<List<String>> = _connected

    @Volatile @PublishedApi internal var lastSeen = 0L
    private val replaced = java.util.Collections.synchronizedSet(HashSet<String>())
    private val timer = java.util.Timer("bekon-agent-idle", true).apply {
        schedule(object : java.util.TimerTask() { override fun run() { checkIdle() } }, 5_000L, 5_000L)
    }

    fun active(): String? = _state.value.active
    fun isIdle(): Boolean = _state.value.active != null && System.currentTimeMillis() - lastSeen > IDLE_MS
    fun wasReplaced(id: String) = id in replaced

    @Synchronized
    fun mark(clientId: String) {
        lastSeen = System.currentTimeMillis()
        val s = _state.value
        if (s.active != clientId || s.idle || !s.everConnected) {
            if (s.active != null && s.active != clientId) replaced += s.active
            replaced -= clientId
            _state.value = Conn(clientId, false, true)
            _connected.value = listOf(clientId)
        }
    }

    private fun checkIdle() {
        val s = _state.value
        val st = pro.potoki.bekon.agent.status.AgentStatus.current.value
        if (s.active != null && !s.idle && st != null && st.res == 0 && inFlight.get() == 0 &&
            System.currentTimeMillis() - lastSeen > STATUS_SILENT_MS) { lost(tunnel = false); return }
        if (s.active != null && !s.idle && isIdle()) _state.value = s.copy(idle = true)
    }

    /**
     * The agent is gone (silent while its status was shown, or the tunnel dropped): clear its stale status,
     * show a short notice, home card goes back to "Агент не подключён". A new request reconnects it.
     */
    @Synchronized
    fun lost(tunnel: Boolean) {
        val s = _state.value
        if (s.active == null || s.idle) return
        android.util.Log.w("AgentConnections", "agent lost (" + (if (tunnel) "tunnel down" else "silent ${STATUS_SILENT_MS / 1000}s") + ")")
        _state.value = s.copy(idle = true)
        _connected.value = emptyList()
        pro.potoki.bekon.agent.status.AgentStatus.notice(if (tunnel) pro.potoki.bekon.agent.R.string.agent_link_lost else pro.potoki.bekon.agent.R.string.agent_gone)
    }

    @Synchronized
    fun clear() {
        replaced.clear(); lastSeen = 0
        _state.value = Conn(null, false, false)
        _connected.value = emptyList()
    }
}
