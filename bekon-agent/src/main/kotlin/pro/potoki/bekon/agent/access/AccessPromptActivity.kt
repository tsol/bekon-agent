package pro.potoki.bekon.agent.access

import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import pro.potoki.bekon.agent.R
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Blocks the caller until the user allows or denies a new client.
 * Shown as an alert on top of the phone.
 */
class AccessPromptActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val token = intent.getStringExtra(EXTRA_TOKEN) ?: run { finish(); return }
        // Stale notification / duplicate launch: the decision is already made or expired.
        if (pending.values.none { it.token == token }) { finish(); return }
        current = java.lang.ref.WeakReference(this)
        val ip = intent.getStringExtra(EXTRA_IP) ?: "unknown"
        val clientId = intent.getStringExtra(EXTRA_CLIENT) ?: ""
        val replacing = intent.getStringExtra(EXTRA_REPLACING)
        AlertDialog.Builder(this)
            .setTitle(titleOf(this, clientId, replacing))
            .setMessage(messageOf(this, clientId, ip, replacing))
            .setCancelable(false)
            .setPositiveButton(R.string.access_allow) { _, _ -> answer(token, true) }
            .setNegativeButton(R.string.access_deny) { _, _ -> answer(token, false) }
            .setOnDismissListener { finish() }
            .show()
    }

    override fun onDestroy() {
        if (current?.get() === this) current = null
        super.onDestroy()
    }

    private fun answer(token: String, ok: Boolean) {
        deliver(token, ok)
    }

    /** One decision per client id; every concurrent request for that id waits on it. */
    private class Pending(val token: String, val clientId: String) {
        val latch = CountDownLatch(1)
        val allowed = AtomicBoolean(false)
        @Volatile var waiters = 0
    }

    companion object {
        const val EXTRA_REPLACING = "replacing"
        const val EXTRA_TOKEN = "token"
        private const val EXTRA_IP = "ip"
        private const val EXTRA_CLIENT = "clientId"
        /** v2: re-created with sound + vibration + public lock-screen visibility (channel settings are immutable). */
        private const val CHANNEL = "bekon_access_v2"
        private const val OLD_CHANNEL = "bekon_access"
        private const val NOTE_ID = 42
        /** How long the prompt waits for the owner (ClientGate runs it on its own thread; requests poll it). */
        private const val WAIT_SECONDS = 120L
        private const val PROMPT_GRACE_MS = 30_000L
        private val pending = ConcurrentHashMap<String, Pending>()
        private val handler = android.os.Handler(android.os.Looper.getMainLooper())
        @Volatile private var current: java.lang.ref.WeakReference<Activity>? = null

        fun ask(context: Context, clientId: String, ip: String, replacing: String? = null): Boolean {
            var created = false
            val p = synchronized(pending) {
                val existing = pending[clientId]
                val q = existing ?: Pending(java.util.UUID.randomUUID().toString(), clientId)
                if (existing == null) { pending[clientId] = q; created = true }
                q.waiters++
                q
            }
            if (created) show(context, p, ip, replacing)
            val answered = try {
                p.latch.await(WAIT_SECONDS, TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
                false
            }
            synchronized(pending) { p.waiters-- }
            if (!answered) scheduleExpiry(context, p)
            return answered && p.allowed.get()
        }

        private fun show(context: Context, p: Pending, ip: String, replacing: String?) {
            val intent = Intent(context, AccessPromptActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(EXTRA_TOKEN, p.token)
                putExtra(EXTRA_IP, ip)
                putExtra(EXTRA_CLIENT, p.clientId)
                replacing?.let { putExtra(EXTRA_REPLACING, it) }
            }
            notify(context, intent, ip, p, replacing)
            val fg = try { pro.potoki.bekon.agent.status.AgentAlert.inForeground() } catch (_: Throwable) { false }
            val ui = AccessSurface.plan(fg, AccessSurface.canOverlay(context))
            android.util.Log.i("BekonAccess", "access prompt: foreground=$fg ui=$ui")
            if (ui == AccessSurface.Ui.OVERLAY && AccessOverlay.show(
                    context, p.token, titleOf(context, p.clientId, replacing), messageOf(context, p.clientId, ip, replacing),
                    context.getString(R.string.access_allow), context.getString(R.string.access_deny),
                )) return
            try {
                context.startActivity(intent)
            } catch (e: Exception) {
                // The notification is the fallback if the activity start is blocked.
            }
        }

        /** A reconnect (same id lost its token) reads differently from a replacement of another agent. */
        fun titleOf(ctx: Context, clientId: String, replacing: String?): String = when (replacing) {
            null -> ctx.getString(R.string.access_title)
            clientId -> ctx.getString(R.string.access_reconnect_title, clientId)
            else -> ctx.getString(R.string.access_replace_title)
        }

        fun messageOf(ctx: Context, clientId: String, ip: String, replacing: String?): String = when (replacing) {
            null -> ctx.getString(R.string.access_message, clientId, ip)
            clientId -> ctx.getString(R.string.access_reconnect_message, clientId, ip)
            else -> ctx.getString(R.string.access_replace_message, replacing, clientId)
        }

        /** Nobody is waiting any more: drop the prompt after a grace period unless a retry joined it. */
        private fun scheduleExpiry(context: Context, p: Pending) {
            handler.postDelayed({
                val drop = synchronized(pending) {
                    (p.waiters <= 0 && p.latch.count > 0 && pending[p.clientId] === p).also {
                        if (it) pending.remove(p.clientId)
                    }
                }
                if (drop) closeUi(context)
            }, PROMPT_GRACE_MS)
        }

        fun deliver(token: String, ok: Boolean) {
            val p = synchronized(pending) {
                pending.values.firstOrNull { it.token == token }?.also { pending.remove(it.clientId) }
            } ?: return
            p.allowed.set(ok)
            p.latch.countDown()
            (current?.get()?.applicationContext ?: pro.potoki.bekon.agent.BekonApp.appContext)?.let { closeUi(it) }
        }

        fun deliverFromNotification(context: Context, token: String, ok: Boolean) {
            deliver(token, ok)
            context.getSystemService(NotificationManager::class.java).cancel(NOTE_ID)
            AccessOverlay.hide(context)
        }

        private fun closeUi(context: Context) {
            context.getSystemService(NotificationManager::class.java).cancel(NOTE_ID)
            AccessOverlay.hide(context)
            if (pending.isEmpty()) handler.post { current?.get()?.finish() }
        }

        private fun notify(context: Context, intent: Intent, ip: String, p: Pending, replacing: String?) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try { nm.deleteNotificationChannel(OLD_CHANNEL) } catch (_: Throwable) {}
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, context.getString(R.string.access_channel), NotificationManager.IMPORTANCE_HIGH).apply {
                        enableVibration(true)
                        vibrationPattern = longArrayOf(0, 300, 200, 300)
                        lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                        setSound(
                            android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION),
                            android.media.AudioAttributes.Builder()
                                .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_REQUEST)
                                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
                        )
                    },
                )
            }
            val pi = PendingIntent.getActivity(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            fun action(a: String, code: Int) = PendingIntent.getBroadcast(
                context, code,
                Intent(context, AccessActionReceiver::class.java).setAction(a).putExtra(EXTRA_TOKEN, p.token),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val text = if (replacing != null) messageOf(context, p.clientId, ip, replacing)
                else context.getString(R.string.access_note_text, p.clientId, ip)
            val note = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(if (replacing != null) titleOf(context, p.clientId, replacing) else context.getString(R.string.access_note_title, p.clientId))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setContentIntent(pi)
                .setFullScreenIntent(pi, true)
                .addAction(0, context.getString(R.string.access_allow), action(AccessActionReceiver.ACTION_ALLOW, 1))
                .addAction(0, context.getString(R.string.access_deny), action(AccessActionReceiver.ACTION_DENY, 2))
                .setAutoCancel(true)
                .setOngoing(true)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setTimeoutAfter((WAIT_SECONDS * 1000) + PROMPT_GRACE_MS)
                .build()
            nm.notify(NOTE_ID, note)
        }
    }
}
