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
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.access_title))
            .setMessage(getString(R.string.access_message, ip, clientId))
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
        private const val EXTRA_TOKEN = "token"
        private const val EXTRA_IP = "ip"
        private const val EXTRA_CLIENT = "clientId"
        private const val CHANNEL = "bekon_access"
        private const val NOTE_ID = 42
        /** How long one HTTP request waits. The prompt itself lives a bit longer so a retry joins it. */
        private const val WAIT_SECONDS = 60L
        private const val PROMPT_GRACE_MS = 30_000L
        private val pending = ConcurrentHashMap<String, Pending>()
        private val handler = android.os.Handler(android.os.Looper.getMainLooper())
        @Volatile private var current: java.lang.ref.WeakReference<Activity>? = null

        fun ask(context: Context, clientId: String, ip: String): Boolean {
            var created = false
            val p = synchronized(pending) {
                val existing = pending[clientId]
                val q = existing ?: Pending(java.util.UUID.randomUUID().toString(), clientId)
                if (existing == null) { pending[clientId] = q; created = true }
                q.waiters++
                q
            }
            if (created) show(context, p, ip)
            val answered = try {
                p.latch.await(WAIT_SECONDS, TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
                false
            }
            synchronized(pending) { p.waiters-- }
            if (!answered) scheduleExpiry(context, p)
            return answered && p.allowed.get()
        }

        private fun show(context: Context, p: Pending, ip: String) {
            val intent = Intent(context, AccessPromptActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(EXTRA_TOKEN, p.token)
                putExtra(EXTRA_IP, ip)
                putExtra(EXTRA_CLIENT, p.clientId)
            }
            notify(context, intent, ip)
            try {
                context.startActivity(intent)
            } catch (e: Exception) {
                // The notification is the fallback if the activity start is blocked.
            }
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
            current?.get()?.let { appCtx -> closeUi(appCtx.applicationContext) }
        }

        private fun closeUi(context: Context) {
            context.getSystemService(NotificationManager::class.java).cancel(NOTE_ID)
            if (pending.isEmpty()) handler.post { current?.get()?.finish() }
        }

        private fun notify(context: Context, intent: Intent, ip: String) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Bekon access", NotificationManager.IMPORTANCE_HIGH),
                )
            }
            val pi = PendingIntent.getActivity(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val note = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(context.getString(R.string.access_title))
                .setContentText(context.getString(R.string.access_note_text, ip))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setContentIntent(pi)
                .setFullScreenIntent(pi, true)
                .setAutoCancel(true)
                .build()
            nm.notify(NOTE_ID, note)
        }
    }
}
