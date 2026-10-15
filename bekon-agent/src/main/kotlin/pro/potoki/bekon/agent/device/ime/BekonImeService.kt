package pro.potoki.bekon.agent.device.ime

import android.inputmethodservice.InputMethodService
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.inputmethod.InputConnection
import android.widget.TextView

/**
 * Silent keyboard so we can type into Termux (and any focused field) on Android 11+.
 * Enable it in Language & input, then select it as the current IME.
 */
class BekonImeService : InputMethodService() {
    companion object {
        private const val TAG = "BekonIme"

        @Volatile var instance: BekonImeService? = null
            private set

        private fun isOurs(id: String?): Boolean =
            id != null && id.contains("pro.potoki.bekon.agent") && id.contains("BekonImeService")

        /** Settings.Secure.ENABLED_INPUT_METHODS throws SecurityException for targetSdk >= 34; use IMM. */
        fun isEnabled(resolver: android.content.ContentResolver): Boolean {
            val ctx = pro.potoki.bekon.agent.BekonApp.appContext
            if (ctx != null) {
                try {
                    val imm = ctx.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                    if (imm != null) return imm.enabledInputMethodList.any { isOurs(it.id) }
                } catch (e: Exception) { Log.w(TAG, "imm enabled list failed", e) }
            }
            return try {
                Settings.Secure.getString(resolver, Settings.Secure.ENABLED_INPUT_METHODS)
                    ?.split(':')?.any { isOurs(it) } ?: false
            } catch (e: SecurityException) { false }
        }

        fun isSelected(resolver: android.content.ContentResolver): Boolean {
            return try {
                isOurs(Settings.Secure.getString(resolver, Settings.Secure.DEFAULT_INPUT_METHOD))
            } catch (e: SecurityException) {
                instance != null
            }
        }

        fun statusDetail(resolver: android.content.ContentResolver): String = when {
            isSelected(resolver) -> "Current keyboard"
            isEnabled(resolver) -> "Enabled — tap to select"
            else -> "Not enabled — tap to open settings"
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "onCreate")
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        return TextView(this).apply {
            text = "Bekon Keys"
            textSize = 14f
            setPadding(24, 24, 24, 24)
        }
    }

    fun liveConnection(): InputConnection? = currentInputConnection
}
