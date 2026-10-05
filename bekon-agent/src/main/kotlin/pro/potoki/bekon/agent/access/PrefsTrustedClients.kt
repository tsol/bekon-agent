package pro.potoki.bekon.agent.access

import android.content.Context

class PrefsTrustedClients(context: Context) : TrustedClients {
    private val prefs = context.applicationContext.getSharedPreferences("bekon_clients", Context.MODE_PRIVATE)

    override fun autoAcceptNext(): Boolean = prefs.getBoolean(AUTO, true)

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
