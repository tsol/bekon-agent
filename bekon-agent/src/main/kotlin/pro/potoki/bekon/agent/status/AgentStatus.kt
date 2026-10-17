package pro.potoki.bekon.agent.status

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Short human-facing status line the agent sets with the `agent_status` MCP tool
 * ("Working…", "Need you: tap Allow", "Done — you can stop sharing").
 * Drawn as a pill inside the bacon frame by GestureOverlay; mirrored into the session
 * notification when the overlay is unavailable or when state=done. Cleared on session stop.
 */
object AgentStatus {
    enum class State(val wire: String) {
        WORKING("working"), DONE("done"), WAITING("waiting"), ERROR("error");
        companion object { fun of(s: String?): State? = entries.firstOrNull { it.wire == s?.trim()?.lowercase() } }
    }

    data class Status(val text: String, val state: State, val setAtMs: Long, val ttlMs: Long, val top: Boolean = false)

    const val MAX_CHARS = 80
    const val MAX_TTL_MS = 3_600_000L

    private val _current = MutableStateFlow<Status?>(null)
    val current: StateFlow<Status?> = _current.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var ttlJob: Job? = null

    /** Validated request; pure so it can be unit-tested. */
    data class Request(val text: String, val state: State, val ttlMs: Long, val clear: Boolean, val truncated: Boolean, val top: Boolean = false)

    fun parse(text: String?, state: String?, ttlMs: Long?, clear: Boolean?, position: String? = null): Request {
        if (clear == true) return Request("", State.WORKING, 0, true, false)
        val st = if (state.isNullOrBlank()) State.WORKING
            else State.of(state) ?: throw IllegalArgumentException("state must be one of working|done|waiting|error")
        val t = (text ?: "").replace(Regex("\\s+"), " ").trim()
        if (t.isEmpty()) throw IllegalArgumentException("text is required (short, ≤ ~60 chars) unless clear=true")
        val cut = t.length > MAX_CHARS
        val out = if (cut) t.take(MAX_CHARS - 1).trimEnd() + "…" else t
        val ttl = (ttlMs ?: 0L).coerceIn(0L, MAX_TTL_MS)
        val top = when (position?.trim()?.lowercase()) {
            null, "", "bottom" -> false
            "top" -> true
            else -> throw IllegalArgumentException("position must be top|bottom")
        }
        return Request(out, st, ttl, false, cut, top)
    }

    @Synchronized
    fun apply(r: Request): Status? {
        ttlJob?.cancel(); ttlJob = null
        if (r.clear) { _current.value = null; return null }
        val s = Status(r.text, r.state, System.currentTimeMillis(), r.ttlMs, r.top)
        _current.value = s
        if (r.ttlMs > 0) ttlJob = scope.launch {
            delay(r.ttlMs)
            synchronized(this@AgentStatus) { if (_current.value === s) _current.value = null }
        }
        return s
    }

    fun clear() = apply(Request("", State.WORKING, 0, true, false))

    /** Device UI locale as a BCP-47 tag (e.g. "ru-RU") and its language ("ru"). */
    fun localeTag(): String = Locale.getDefault().toLanguageTag()
    fun language(): String = Locale.getDefault().language
    fun languageName(): String = Locale.getDefault().getDisplayLanguage(Locale.ENGLISH)
}
