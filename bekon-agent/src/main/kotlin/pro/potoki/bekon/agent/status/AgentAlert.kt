package pro.potoki.bekon.agent.status

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import pro.potoki.bekon.agent.MainActivity
import pro.potoki.bekon.agent.R

/**
 * Heads-up "Agent needs you" notifications: used when the a11y overlay pill is unavailable,
 * and when Android blocks a background activity start (open_setup_screen).
 */
object AgentAlert {
    private const val CHANNEL = "bekon_agent_needs_you"
    private const val NOTE_ID = 77

    /** Last settings screen the agent tried to open; tapping a status alert opens it. */
    @Volatile var lastSetupIntent: Intent? = null

    /** True only when one of our activities is visible; background starts are blocked otherwise (Android 10+). */
    fun inForeground(): Boolean {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    private fun channel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, ctx.getString(R.string.alert_channel), NotificationManager.IMPORTANCE_HIGH),
            )
        }
    }

    fun post(ctx: Context, title: String, body: String, target: Intent?) {
        channel(ctx)
        val open = (target?.let { Intent(it) } ?: Intent(ctx, MainActivity::class.java)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pi = PendingIntent.getActivity(
            ctx, NOTE_ID, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val note = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try { ctx.getSystemService(NotificationManager::class.java).notify(NOTE_ID, note) } catch (_: Exception) {}
    }

    /** agent_status mirrored as a heads-up: the agent's exact text is the title, the agent name and state below. */
    fun status(ctx: Context, st: AgentStatus.Status, agent: String?) {
        val lab = ctx.getString(when (st.state) {
            AgentStatus.State.WORKING -> R.string.status_working; AgentStatus.State.WAITING -> R.string.status_waiting
            AgentStatus.State.DONE -> R.string.status_done; AgentStatus.State.ERROR -> R.string.status_error
        })
        val who = agent ?: ctx.getString(R.string.alert_agent_unknown)
        post(ctx, st.text, ctx.getString(R.string.alert_status_body, who, lab, st.text), lastSetupIntent)
    }

    private const val HINT_ID = 78

    /** Sticky "next step" hint while the human is inside system Settings (no overlay before Accessibility is on). */
    fun hint(ctx: Context, title: String, text: String) {
        channel(ctx)
        val pi = PendingIntent.getActivity(
            ctx, HINT_ID, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val note = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
        try { ctx.getSystemService(NotificationManager::class.java).notify(HINT_ID, note) } catch (_: Exception) {}
    }

    fun clearHint(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).cancel(HINT_ID)
    }

    fun clear(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).cancel(NOTE_ID)
    }
}
