package pro.potoki.bekon.agent.device.touch

import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import pro.potoki.bekon.agent.core.ChangeSignal

/**
 * Counts accessibility events that may mean the screen changed, for wait_for_change.
 * Ignores Bekon's own package (coach overlay, our dialogs only when they are overlays is
 * not knowable here, so the tree hash decides) and SystemUI content ticks (clock, icons).
 */
object ChangeMonitor : ChangeSignal {
    @Volatile override var seq: Long = 0; private set
    @Volatile override var lastEventAt: Long = 0; private set
    @Volatile override var lastReason: String = ""; private set
    @Volatile private var fgPkg: String = ""
    @Volatile private var fgCls: String = ""

    private val RELEVANT = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
        AccessibilityEvent.TYPE_VIEW_CLICKED or
        AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
        AccessibilityEvent.TYPE_VIEW_SELECTED or
        AccessibilityEvent.TYPE_VIEW_SCROLLED or
        AccessibilityEvent.TYPE_WINDOWS_CHANGED or
        AccessibilityEvent.TYPE_VIEW_FOCUSED

    fun onEvent(e: AccessibilityEvent, ownPkg: String) {
        val t = e.eventType
        if (t and RELEVANT == 0) return
        val pkg = e.packageName?.toString() ?: ""
        val cls = e.className?.toString() ?: ""
        if (t == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && pkg.isNotEmpty() && pkg != "com.android.systemui" &&
            (cls.contains("Activity") || cls.contains("Launcher"))) { fgPkg = pkg; fgCls = cls }
        // SystemUI content/windows churn = clock, signal and notification icons.
        if (pkg == "com.android.systemui" && t != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            t != AccessibilityEvent.TYPE_VIEW_CLICKED) return
        // Our overlay redraws (coach marks) come as content/windows changes from our package.
        if (pkg == ownPkg && (t == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED || t == AccessibilityEvent.TYPE_WINDOWS_CHANGED)) return
        // Background content churn from other packages (MIUI widget feed com.mi.android.globalminusscreen
        // ticks constantly) kept the debounce from ever going quiet, so every wake waited settleMax.
        if (t == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && fgPkg.isNotEmpty() && pkg.isNotEmpty() &&
            pkg != fgPkg && pkg != "com.android.systemui") return
        lastReason = "${AccessibilityEvent.eventTypeToString(t)} $pkg".trim()
        lastEventAt = SystemClock.uptimeMillis()
        seq++
    }

    fun foreground(): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        val svc = TouchService.active
        val root = try { svc?.rootInActiveWindow } catch (_: Exception) { null }
        // Root can be null (or our own overlay) mid-transition: fall back to the active app window, then the last event.
        val own = svc?.packageName
        val pkg = root?.packageName?.toString()?.takeIf { it != own }
            ?: try {
                svc?.windows?.firstOrNull { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive }
                    ?.root?.packageName?.toString()
            } catch (_: Exception) { null }
            ?: fgPkg
        if (pkg.isNotEmpty()) m["package"] = pkg
        if (fgCls.isNotEmpty() && fgPkg == pkg) m["activity"] = fgCls
        try {
            val w = svc?.windows?.firstOrNull { it.isActive }
            w?.title?.toString()?.takeIf { it.isNotBlank() }?.let { m["title"] = it }
        } catch (_: Exception) {}
        return m
    }
}
