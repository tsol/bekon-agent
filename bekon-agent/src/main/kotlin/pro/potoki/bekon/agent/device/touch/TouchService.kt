package pro.potoki.bekon.agent.device.touch

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class TouchService : AccessibilityService() {
    companion object {
        private const val TAG = "TouchService"
        @Volatile var instance: TouchService? = null; private set
        /** The service only when the user consented; use this for every read or action. */
        val active: TouchService? get() = instance?.takeIf { it.consented }
        /** Bound AND the user accepted the in-app prominent disclosure. */
        fun isBound(): Boolean = instance?.consented == true
    }

    /** False until the prominent disclosure was accepted: no reads, no gestures. */
    @Volatile var consented: Boolean = false; private set

    /** Re-check consent; if declined while enabled, turn ourselves off. */
    fun onConsentChanged() {
        consented = pro.potoki.bekon.agent.consent.Consent.accepted(this, pro.potoki.bekon.agent.consent.Consent.Kind.A11Y)
        if (!consented && Build.VERSION.SDK_INT >= 24) disableSelf()
    }

    val controller: TouchController by lazy { TouchController(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "onCreate")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "onServiceConnected — gestures ready (sdk=${Build.VERSION.SDK_INT})")
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                val ime = javaClass.getMethod("getInputMethod").invoke(this)
                Log.i(TAG, "InputMethod=${ime != null}")
            } catch (e: Exception) {
                Log.w(TAG, "getInputMethod: ${e.message}")
            }
        }
        consented = pro.potoki.bekon.agent.consent.Consent.accepted(this, pro.potoki.bekon.agent.consent.Consent.Kind.A11Y)
        if (!consented) {
            // Enabled from system settings without seeing our disclosure: ask first, act on nothing.
            Log.w(TAG, "no prominent-disclosure consent yet; staying inert")
            pro.potoki.bekon.agent.consent.Consent.showDisclosure(this, pro.potoki.bekon.agent.consent.Consent.Kind.A11Y, thenOpenSettings = false)
        }
        GestureOverlay.attach(this)
    }

    override fun onDestroy() {
        GestureOverlay.detach()
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (!consented) return
        if (e?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString() ?: return
        // Ignore our own overlays/dialogs and transient system UI (shade, toasts).
        if (pkg == packageName || pkg == "com.android.systemui") return
        val cls = e.className?.toString() ?: ""
        if (!cls.contains("Activity") && !cls.contains("Launcher")) return
        pro.potoki.bekon.agent.coach.CoachOverlay.onWindowChanged("$pkg/$cls")
    }
    override fun onInterrupt() {}
}
