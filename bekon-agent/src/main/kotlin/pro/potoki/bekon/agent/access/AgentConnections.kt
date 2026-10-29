package pro.potoki.bekon.agent.access

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * ONE agent at a time. [active] is the agent currently allowed to drive the phone; a different id
 * must replace it (owner prompt) and the replaced id is then refused until the session ends.
 *
 * Single source of truth for the home card AND the status pill: both read [state] / [connected]
 * (which is derived from [state], never updated separately).
 * Liveness = the agent's session token is valid (cleared on Disconnect agent / Stop / replacement via [clear]) and
 * its last call (any call, including session_info) was within [IDLE_MS] (15 min). A slow stateless agent that thinks
 * for a few minutes between calls stays "connected".
 */
object AgentConnections {
    /** Session liveness window: no call for this long = the agent is treated as gone. Extended by every call. */
    const val IDLE_MS = 15 * 60_000L
    val inFlight = java.util.concurrent.atomic.AtomicInteger()

    /** Wrap a request: long calls (wait_for_change, coach scripts) are not "silence". */
    inline fun <T> busy(block: () -> T): T {
        inFlight.incrementAndGet()
        try { return block() } finally { touch(); inFlight.decrementAndGet() }
    }
    fun touch() { lastSeen = clock() }

    data class Conn(val active: String?, val idle: Boolean, val everConnected: Boolean) {
        /** The agent counts as connected (card "Connected", pill shows its name). */
        val live: Boolean get() = active != null && !idle
    }

    private val _state = MutableStateFlow(Conn(null, false, false))
    val state: StateFlow<Conn> = _state

    /** The live agent as a 0/1 list; always derived from [state] (card and pill never disagree). */
    private val _connected = MutableStateFlow<List<String>>(emptyList())
    val connected: StateFlow<List<String>> = _connected

    /** Test hook. */
    @Volatile var clock: () -> Long = { System.currentTimeMillis() }

    @Volatile @PublishedApi internal var lastSeen = 0L
    private val replaced = java.util.Collections.synchronizedSet(HashSet<String>())
    private val timer by lazy {
        java.util.Timer("bekon-agent-idle", true).apply {
            schedule(object : java.util.TimerTask() { override fun run() { checkIdle() } }, 5_000L, 5_000L)
        }
    }

    private fun set(c: Conn) {
        _state.value = c
        _connected.value = if (c.live) listOf(c.active!!) else emptyList()
    }

    fun active(): String? = _state.value.active
    fun isIdle(): Boolean = _state.value.active != null && clock() - lastSeen > IDLE_MS
    fun wasReplaced(id: String) = id in replaced

    @Synchronized
    fun mark(clientId: String) {
        timer
        lastSeen = clock()
        val s = _state.value
        if (s.active != clientId || s.idle || !s.everConnected) {
            if (s.active != null && s.active != clientId) replaced += s.active
            replaced -= clientId
            set(Conn(clientId, false, true))
        }
    }

    /** A call from the live agent that did not pass the gate (e.g. session_info with a valid token). */
    @Synchronized
    fun seen(clientId: String) {
        if (_state.value.active == clientId) mark(clientId)
    }

    /** Timer tick (also called by tests). */
    fun checkIdle() {
        val s = _state.value
        if (s.active != null && !s.idle && inFlight.get() == 0 && isIdle()) lost(tunnel = false)
    }

    /**
     * The agent is gone (silent for [IDLE_MS], or the tunnel dropped): clear its stale status,
     * show a short notice, home card goes back to "Agent not connected". A new request reconnects it.
     */
    @Synchronized
    fun lost(tunnel: Boolean) {
        val s = _state.value
        if (s.active == null || s.idle) return
        try { android.util.Log.w("AgentConnections", "agent lost (" + (if (tunnel) "tunnel down" else "silent ${IDLE_MS / 60_000} min") + ")") } catch (_: Throwable) {}
        set(s.copy(idle = true))
        try {
            pro.potoki.bekon.agent.status.AgentStatus.notice(if (tunnel) pro.potoki.bekon.agent.R.string.agent_link_lost else pro.potoki.bekon.agent.R.string.agent_gone)
        } catch (_: Throwable) {}
    }

    /** Disconnect agent / Stop / token revoked: the agent is gone right now (pill and card update at once). */
    @Synchronized
    fun clear() {
        replaced.clear(); lastSeen = 0
        set(Conn(null, false, _state.value.everConnected))
    }

    /** Full reset (tests, fresh service). */
    @Synchronized
    fun resetAll() {
        replaced.clear(); lastSeen = 0
        set(Conn(null, false, false))
    }
}
