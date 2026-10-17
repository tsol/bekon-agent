package pro.potoki.bekon.agent

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File

/**
 * Append-only diagnostics in filesDir/crash.log (kept < 64 KB): uncaught exceptions, swallowed server errors,
 * and the system's record of why the previous process died (ANR / low memory / crash / signal), API 30+.
 * The owner sees a "Bekon stopped unexpectedly" dialog with Share on next launch. No tokens are ever written.
 */
object CrashLog {
    private const val TAG = "CrashLog"
    private const val MAX = 64 * 1024
    private const val PREF_SEEN = "crashlog_seen_len"
    private const val PREF_EXIT_TS = "crashlog_exit_ts"

    private fun file(ctx: Context) = File(ctx.filesDir, "crash.log")

    @Synchronized
    fun note(msg: String, t: Throwable? = null) {
        val ctx = BekonApp.appContext ?: return
        try {
            val f = file(ctx)
            if (f.length() > MAX) f.writeText(f.readText().takeLast(MAX / 2))
            val sb = StringBuilder()
            sb.append(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", java.util.Locale.US).format(java.util.Date()))
            sb.append(" v").append(BuildConfig.VERSION_NAME).append(" ").append(msg.take(500)).append('\n')
            t?.stackTrace?.take(40)?.forEach { sb.append("  at ").append(it).append('\n') }
            t?.cause?.let { sb.append("  caused by ").append(it).append('\n') }
            f.appendText(sb.toString())
        } catch (e: Exception) { Log.w(TAG, "note: ${e.message}") }
    }

    /** Record why the previous process ended, once per exit. */
    fun recordExitReasons(ctx: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val prefs = ctx.getSharedPreferences(BekonApp.PREFS, Context.MODE_PRIVATE)
            val seen = prefs.getLong(PREF_EXIT_TS, 0L)
            val infos = am.getHistoricalProcessExitReasons(ctx.packageName, 0, 5).filter { it.timestamp > seen }
            for (i in infos.sortedBy { it.timestamp }) {
                val r = when (i.reason) {
                    android.app.ApplicationExitInfo.REASON_ANR -> "ANR"
                    android.app.ApplicationExitInfo.REASON_CRASH -> "CRASH"
                    android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "NATIVE_CRASH"
                    android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
                    android.app.ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
                    android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE"
                    android.app.ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
                    android.app.ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
                    android.app.ApplicationExitInfo.REASON_OTHER -> "OTHER"
                    else -> "reason=${i.reason}"
                }
                val bad = i.reason in setOf(android.app.ApplicationExitInfo.REASON_ANR, android.app.ApplicationExitInfo.REASON_CRASH,
                    android.app.ApplicationExitInfo.REASON_CRASH_NATIVE, android.app.ApplicationExitInfo.REASON_LOW_MEMORY,
                    android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE, android.app.ApplicationExitInfo.REASON_SIGNALED)
                if (!bad) continue
                val trace = if (i.reason == android.app.ApplicationExitInfo.REASON_ANR) try {
                    i.traceInputStream?.bufferedReader()?.use { rd -> rd.readText() }
                        ?.let { t -> Regex("""(?s)"main".*?\n\n""").find(t)?.value ?: t.take(3000) }?.take(4000)
                } catch (_: Exception) { null } else null
                note("previous process exit: $r (${i.description ?: ""}) at ${java.util.Date(i.timestamp)} pss=${i.pss}kB" +
                    (trace?.let { "\n$it" } ?: ""))
            }
            infos.maxOfOrNull { it.timestamp }?.let { prefs.edit().putLong(PREF_EXIT_TS, it).apply() }
        } catch (e: Exception) { Log.w(TAG, "exit reasons: ${e.message}") }
    }

    fun text(ctx: Context): String = try { file(ctx).takeIf { it.exists() }?.readText() ?: "" } catch (_: Exception) { "" }

    /** New content since the owner last dismissed the dialog. */
    fun unseen(ctx: Context): String {
        val all = text(ctx)
        val seen = ctx.getSharedPreferences(BekonApp.PREFS, Context.MODE_PRIVATE).getInt(PREF_SEEN, 0)
        return if (all.length > seen) all.substring(seen.coerceAtMost(all.length)) else ""
    }

    fun markSeen(ctx: Context) {
        ctx.getSharedPreferences(BekonApp.PREFS, Context.MODE_PRIVATE).edit().putInt(PREF_SEEN, text(ctx).length).apply()
    }
}
