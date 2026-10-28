package pro.potoki.bekon.agent.setup

import android.content.Context
import android.content.Intent
import pro.potoki.bekon.agent.R
import pro.potoki.bekon.agent.device.touch.ChangeMonitor
import pro.potoki.bekon.agent.device.touch.TouchService
import pro.potoki.bekon.agent.status.AgentAlert

/**
 * Starts an activity for the agent. Android blocks background activity starts silently, so:
 * foreground -> start directly; background + accessibility on -> start from the accessibility
 * service and verify the foreground package changed; otherwise -> heads-up "Tap to open" notification.
 */
object Launcher {
    const val BLOCKED = "blocked_background"
    const val OFFERED = "offered_to_user"

    data class Result(val opened: Boolean, val code: String, val text: String)

    fun launch(ctx: Context, intent: Intent, screenName: String, what: String, hint: String = ""): Result {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        AgentAlert.lastSetupIntent = intent
        if (pro.potoki.bekon.agent.BuildConfig.GUIDANCE_MODE) {
            // Guidance build: never open anything for the user — offer a "Tap to open" notification; the human decides.
            val t = ctx.getString(R.string.setup_tap_to_open, screenName)
            AgentAlert.post(ctx, t, ctx.getString(R.string.setup_tap_body) + if (hint.isNotEmpty()) "\n$hint" else "", intent)
            return Result(false, OFFERED,
                "$OFFERED: a notification '$t' was shown (target $what). Guidance mode never opens screens itself: " +
                    "ask the human to tap the notification if they want to, then wait_for_change.")
        }
        if (AgentAlert.inForeground()) {
            ctx.startActivity(intent)
            return Result(true, "opened", "opened $what")
        }
        val svc = TouchService.active
        if (svc != null) {
            val target = try { intent.resolveActivity(ctx.packageManager)?.packageName } catch (_: Exception) { null }
            try {
                svc.startActivity(Intent(intent))
                if (target == null) return Result(true, "opened", "opened $what via the accessibility service (not verified)")
                val t0 = System.currentTimeMillis()
                while (System.currentTimeMillis() - t0 < 2500) {
                    Thread.sleep(250)
                    if (ChangeMonitor.foreground()["package"] == target) {
                        return Result(true, "opened", "opened $what via the accessibility service (verified: $target in front)")
                    }
                }
            } catch (_: Exception) {}
        }
        val title = ctx.getString(R.string.setup_tap_to_open, screenName)
        AgentAlert.post(ctx, title, ctx.getString(R.string.setup_tap_body) + if (hint.isNotEmpty()) "\n$hint" else "", intent)
        return Result(false, BLOCKED,
            "$BLOCKED: user must tap notification '$title' (target $what). Bekon is in the background, so Android did not open the screen. " +
                "Ask the human to tap the notification, then wait_for_change.")
    }
}
