package pro.potoki.bekon.agent.status

import android.content.Context
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import pro.potoki.bekon.agent.R

/** What the agent can do right now, from live state (never from what the agent claims). */
object Capabilities {
    data class Caps(
        val running: Boolean, val seeing: Boolean, val gestures: Boolean,
        val paused: Boolean, val readOnly: Boolean, val agent: String?,
    ) {
        /** Can actually tap/draw: service connected, not paused, not read-only. */
        val canAct: Boolean get() = gestures && !paused && !readOnly
    }

    fun current(): Caps {
        val s = pro.potoki.bekon.agent.session.SessionService.state.value
        return Caps(
            running = s.isRunning,
            seeing = s.isRunning && s.captureGranted && !s.paused,
            gestures = pro.potoki.bekon.agent.device.touch.TouchService.isBound(),
            paused = s.paused, readOnly = s.readOnly,
            agent = pro.potoki.bekon.agent.access.AgentConnections.connected.value.lastOrNull(),
        )
    }

    /** "видит экран · не может нажимать · шаг 2 из 4" (or пауза).
     *  When capture is off, [cap_blind] admits accessibility still reads on-screen text. */
    fun line(ctx: Context, c: Caps = current(), step: Int? = null, total: Int? = null): String {
        if (c.paused) return ctx.getString(R.string.cap_paused)
        val eye = ctx.getString(if (c.seeing) R.string.cap_sees else R.string.cap_blind)
        val hand = ctx.getString(when {
            c.gestures && c.readOnly -> R.string.cap_read_only
            c.canAct -> R.string.cap_taps
            else -> R.string.cap_no_taps
        })
        val st = if (step != null && total != null) " · " + ctx.getString(R.string.cap_step, step, total) else ""
        return "$eye · $hand$st"
    }

    fun json(c: Caps = current()): JsonObject = buildJsonObject {
        put("screenVisible", c.seeing)
        put("gesturesConnected", c.gestures)
        put("canTap", c.canAct)
        put("readOnly", c.readOnly)
        put("paused", c.paused)
        put("auth", "bearer-session-token")
        c.agent?.let { put("agent", it) }
    }
}
