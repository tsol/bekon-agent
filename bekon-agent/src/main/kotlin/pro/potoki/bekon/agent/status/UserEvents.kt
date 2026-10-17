package pro.potoki.bekon.agent.status

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Things the human tells the agent from the phone (status pill menu): back_to_app, changed_mind,
 * new_goal (with text), cancel_all. Delivered by the user_events tool and attached to
 * wait_for_change / wait_for_script results; those waits also return early when one arrives.
 */
object UserEvents {
    data class Event(val type: String, val text: String?, val atMs: Long)

    private val queue = ArrayDeque<Event>()
    private val lock = Object()
    @Volatile var seq = 0L; private set
    /** Stops a running coach_script (registered by the MCP handler). */
    @Volatile var stopCoaching: (() -> Unit)? = null

    fun add(type: String, text: String? = null) {
        synchronized(lock) {
            queue.addLast(Event(type, text?.trim()?.take(500)?.ifEmpty { null }, System.currentTimeMillis()))
            while (queue.size > 20) queue.removeFirst()
            seq++
            lock.notifyAll()
        }
    }

    fun pending(): Boolean = synchronized(lock) { queue.isNotEmpty() }

    fun drain(): List<Event> = synchronized(lock) { queue.toList().also { queue.clear() } }

    fun json(events: List<Event>): JsonArray = buildJsonArray {
        events.forEach { e -> add(buildJsonObject { put("type", e.type); e.text?.let { put("text", it) }; put("atMs", e.atMs) }) }
    }

    const val DOC = "userEvents come from the human tapping the status pill menu: back_to_app (they went back to Bekon), " +
        "changed_mind (they cancelled the current step: hints were cleared, do not redraw the same hint; ask or pick another way), " +
        "new_goal (text = their new instruction: follow it instead), cancel_all (they stopped coaching and paused the session: stop, " +
        "agent_status done/clear, do not call action tools until they resume)."
}
