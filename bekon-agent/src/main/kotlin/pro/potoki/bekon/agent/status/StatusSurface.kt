package pro.potoki.bekon.agent.status

/**
 * Where an agent_status / guidance_step pill is ACTUALLY shown, from live state (never guessed):
 * - full flavor: the accessibility overlay (GestureOverlay) when Gestures (Accessibility) is on;
 * - playStore / guidance: the SAW overlay pill (StatusPill, TYPE_APPLICATION_OVERLAY) when "Display over other apps"
 *   is allowed. Accessibility is never used in guidance, so "Accessibility overlay OFF" must not be reported there.
 * Only when neither overlay can draw is the status notification-only.
 */
object StatusSurface {
    enum class Kind(val wire: String) { SAW_PILL("overlay"), A11Y_PILL("overlay"), NOTIFICATION("notification-only") }

    fun decide(guidance: Boolean, a11yOverlay: Boolean, sawAllowed: Boolean): Kind = when {
        guidance -> if (sawAllowed) Kind.SAW_PILL else Kind.NOTIFICATION
        a11yOverlay -> Kind.A11Y_PILL
        else -> Kind.NOTIFICATION
    }

    /** Live decision on the phone. */
    fun current(): Kind {
        val guidance = pro.potoki.bekon.agent.BuildConfig.GUIDANCE_MODE
        val a11y = if (guidance) false else try {
            pro.potoki.bekon.agent.device.touch.GestureOverlay.canShowStatus()
        } catch (_: Throwable) { false }
        val saw = if (!guidance) false else try {
            pro.potoki.bekon.agent.BekonApp.appContext?.let { pro.potoki.bekon.agent.overlay.OverlayHost.canDraw(it) } ?: false
        } catch (_: Throwable) { false }
        return decide(guidance, a11y, saw)
    }

    fun onScreen(k: Kind) = k != Kind.NOTIFICATION

    /** Human-readable "where it went" for tool replies (matches what the owner sees). */
    fun describe(k: Kind, guidance: Boolean): String = when (k) {
        Kind.SAW_PILL -> "shown on the phone screen in Bekon's on-screen pill (display-over-apps overlay is ON)"
        Kind.A11Y_PILL -> "shown on the phone screen in the overlay pill (Accessibility overlay is ON)"
        Kind.NOTIFICATION -> if (guidance)
            "shown as a notification only, because 'Display over other apps' is OFF for Bekon (ask the human to allow it " +
                "for on-screen hints; open_setup_screen can open that page)"
        else
            "shown as a notification only, because the Accessibility (Gestures) overlay is OFF: a heads-up 'Agent needs you' " +
                "notification plus the session notification (the human sees it only if they look at notifications)"
    }
}
