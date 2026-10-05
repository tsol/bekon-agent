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
        val ip = intent.getStringExtra(EXTRA_IP) ?: "unknown"
        val clientId = intent.getStringExtra(EXTRA_CLIENT) ?: ""
        AlertDialog.Builder(this)
            .setTitle("New connection")
            .setMessage("Someone at $ip wants to use this phone.\n\nId: $clientId")
            .setCancelable(false)
            .setPositiveButton("Allow") { _, _ -> answer(token, true) }
            .setNegativeButton("Deny") { _, _ -> answer(token, false) }
            .setOnDismissListener { finish() }
            .show()
    }

    private fun answer(token: String, ok: Boolean) {
        deliver(token, ok)
        val nm = getSystemService(NotificationManager::class.java)
        nm.cancel(NOTE_ID)
    }

    companion object {
        private const val EXTRA_TOKEN = "token"
        private const val EXTRA_IP = "ip"
        private const val EXTRA_CLIENT = "clientId"
        private const val CHANNEL = "bekon_access"
        private const val NOTE_ID = 42
        private val waiters = ConcurrentHashMap<String, (Boolean) -> Unit>()

        fun ask(context: Context, clientId: String, ip: String): Boolean {
            val token = java.util.UUID.randomUUID().toString()
            val latch = CountDownLatch(1)
            val allowed = AtomicBoolean(false)
            waiters[token] = { ok ->
                allowed.set(ok)
                latch.countDown()
            }
            val intent = Intent(context, AccessPromptActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(EXTRA_TOKEN, token)
                putExtra(EXTRA_IP, ip)
                putExtra(EXTRA_CLIENT, clientId)
            }
            notify(context, intent, ip)
            try {
                context.startActivity(intent)
            } catch (e: Exception) {
                // The notification is the fallback if the activity start is blocked.
            }
            val answered = latch.await(60, TimeUnit.SECONDS)
            waiters.remove(token)
            return answered && allowed.get()
        }

        fun deliver(token: String, ok: Boolean) {
            waiters.remove(token)?.invoke(ok)
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
                .setContentTitle("New connection")
                .setContentText("Someone at $ip wants to use this phone")
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
