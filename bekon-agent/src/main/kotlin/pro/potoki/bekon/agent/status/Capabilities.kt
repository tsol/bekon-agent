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
        val guidance: Boolean = false, val canHint: Boolean = false,
    ) {
        /** Can actually tap: a11y connected, not paused, not read-only. Always false in guidance. */
        val canAct: Boolean get() = !guidance && gestures && !paused && !readOnly
    }

    fun current(): Caps {
        val s = pro.potoki.bekon.agent.session.SessionService.state.value
        val guidance = pro.potoki.bekon.agent.BuildConfig.GUIDANCE_MODE
        val overlay = pro.potoki.bekon.agent.BekonApp.appContext?.let {
            pro.potoki.bekon.agent.overlay.OverlayHost.canDraw(it)
        } ?: false
        return Caps(
            running = s.isRunning,
            seeing = s.isRunning && s.captureGranted && !s.paused,
            gestures = if (guidance) overlay else pro.potoki.bekon.agent.device.touch.TouchService.isBound(),
            paused = s.paused, readOnly = s.readOnly,
            agent = pro.potoki.bekon.agent.access.AgentConnections.connected.value.lastOrNull(),
            guidance = guidance,
            canHint = guidance && s.isRunning && s.captureGranted && overlay && !s.paused,
        )
    }

    /** "видит экран · не может нажимать · шаг 2 из 4" (or пауза).
     *  When capture is off, [cap_blind] admits accessibility still reads on-screen text. */
    fun line(ctx: Context, c: Caps = current(), step: Int? = null, total: Int? = null): String {
        if (c.paused) return ctx.getString(R.string.cap_paused)
        val eye = ctx.getString(if (c.seeing) R.string.cap_sees else R.string.cap_blind)
        val hand = ctx.getString(when {
            c.guidance && c.canHint -> R.string.cap_guidance
            c.guidance -> R.string.cap_guidance_no_overlay
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
        put("guidance", c.guidance)
        put("canHint", c.canHint)
        put("readOnly", c.readOnly)
        put("paused", c.paused)
        put("auth", "bearer-session-token")
        c.agent?.let { put("agent", it) }
    }
}
