package pro.potoki.bekon.agent.access

import android.content.Context

class PrefsTrustedClients(context: Context) : TrustedClients {
    private val prefs = context.applicationContext.getSharedPreferences("bekon_clients", Context.MODE_PRIVATE)

    init {
        // 1.0.11: approvals are per session (token); drop ids remembered by older versions.
        if (prefs.contains(LEGACY_IDS)) prefs.edit().remove(LEGACY_IDS).apply()
    }

    // Off by default: every new agent needs an explicit Allow on the phone. The Play build ignores the toggle.
    override fun autoAcceptNext(): Boolean =
        !pro.potoki.bekon.agent.BuildConfig.PLAY_STORE && prefs.getBoolean(AUTO, false)

    fun autoAcceptNextRaw(): Boolean = prefs.getBoolean(AUTO, false)

    fun setAutoAcceptNext(on: Boolean) {
        prefs.edit().putBoolean(AUTO, on).apply()
    }

    companion object {
        private const val AUTO = "auto_accept_next"
        private const val LEGACY_IDS = "trusted_ids"
    }
}
