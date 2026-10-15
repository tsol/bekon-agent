package pro.potoki.bekon.agent.access

import android.content.Context

class PrefsTrustedClients(context: Context) : TrustedClients {
    private val prefs = context.applicationContext.getSharedPreferences("bekon_clients", Context.MODE_PRIVATE)

    // Off by default: every new agent needs an explicit Allow on the phone. The Play build ignores the toggle.
    override fun autoAcceptNext(): Boolean =
        !pro.potoki.bekon.agent.BuildConfig.PLAY_STORE && prefs.getBoolean(AUTO, false)

    override fun trusted(): Set<String> = prefs.getStringSet(IDS, emptySet())?.toSet() ?: emptySet()

    override fun remember(id: String) {
        prefs.edit().putStringSet(IDS, trusted() + id).apply()
    }

    fun setAutoAcceptNext(on: Boolean) {
        prefs.edit().putBoolean(AUTO, on).apply()
    }

    companion object {
        private const val AUTO = "auto_accept_next"
        private const val IDS = "trusted_ids"
    }
}
