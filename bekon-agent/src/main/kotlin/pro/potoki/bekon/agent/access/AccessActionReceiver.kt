package pro.potoki.bekon.agent.access

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Allow / Deny buttons on the access notification. */
class AccessActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val token = intent.getStringExtra(AccessPromptActivity.EXTRA_TOKEN) ?: return
        AccessPromptActivity.deliverFromNotification(context, token, intent.action == ACTION_ALLOW)
    }

    companion object {
        const val ACTION_ALLOW = "pro.potoki.bekon.agent.ACCESS_ALLOW"
        const val ACTION_DENY = "pro.potoki.bekon.agent.ACCESS_DENY"
    }
}
