package pro.potoki.bekon.agent.device.touch

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class TouchService : AccessibilityService() {
    companion object {
        private const val TAG = "TouchService"
        @Volatile var instance: TouchService? = null; private set
        fun isBound(): Boolean = instance != null
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
        GestureOverlay.attach(this)
    }

    override fun onDestroy() {
        GestureOverlay.detach()
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
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
