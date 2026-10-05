package pro.potoki.bekon.agent.shortcuts

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/** A saved jump: an app to launch, or an http(s) page. */
data class Shortcut(
    val id: String,
    val title: String,
    val kind: String,
    val target: String,
)

data class LaunchableApp(val label: String, val packageName: String)

/** Installed apps that have a launcher icon. */
fun launchableApps(context: Context): List<LaunchableApp> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(intent, 0)
        .map { LaunchableApp(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
        .distinctBy { it.packageName }
        .sortedBy { it.label.lowercase() }
}

/**
 * Shortcuts the user and the agent share. Stored on the phone so they survive
 * a service restart. Opening one brings that app or page to the front.
 */
class ShortcutStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun list(): List<Shortcut> = decode(prefs.getString(KEY, null))

    /** Returns the existing row when the same app or url is already saved. */
    @Synchronized
    fun add(title: String, kind: String, target: String): Shortcut {
        val cleanTitle = title.trim()
        val cleanKind = kind.trim()
        if (cleanTitle.isEmpty()) throw IllegalArgumentException("title is empty")
        if (cleanKind != "app" && cleanKind != "url") throw IllegalArgumentException("kind must be app or url")
        val cleanTarget = normalizeTarget(cleanKind, target)
        if (cleanKind == "app" && app.packageManager.getLaunchIntentForPackage(cleanTarget) == null) {
            throw IllegalArgumentException("no launchable app for $cleanTarget")
        }
        val current = list().toMutableList()
        current.find { it.kind == cleanKind && it.target == cleanTarget }?.let { return it }
        val created = Shortcut(UUID.randomUUID().toString(), cleanTitle, cleanKind, cleanTarget)
        current.add(created)
        save(current)
        return created
    }

    @Synchronized
    fun remove(id: String): Boolean {
        val current = list()
        val next = current.filterNot { it.id == id }
        if (next.size == current.size) return false
        save(next)
        return true
    }

    fun open(id: String) {
        val item = list().find { it.id == id } ?: throw IllegalArgumentException("no shortcut $id")
        val intent = when (item.kind) {
            "app" -> app.packageManager.getLaunchIntentForPackage(item.target)
                ?: throw IllegalArgumentException("no launchable app ${item.target}")
            "url" -> Intent(Intent.ACTION_VIEW, Uri.parse(item.target))
            else -> throw IllegalArgumentException("unknown kind ${item.kind}")
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val done = CompletableFuture<Unit>()
        Handler(Looper.getMainLooper()).post {
            try {
                app.startActivity(intent)
                done.complete(Unit)
            } catch (e: Exception) {
                done.completeExceptionally(e)
            }
        }
        try {
            done.get(4, TimeUnit.SECONDS)
        } catch (e: ExecutionException) {
            throw IllegalArgumentException(e.cause?.message ?: "could not open shortcut")
        }
    }

    private fun normalizeTarget(kind: String, target: String): String {
        val value = target.trim()
        if (value.isEmpty()) throw IllegalArgumentException("target is empty")
        if (kind != "url") return value
        val withScheme = if ("://" in value) value else "https://$value"
        val uri = Uri.parse(withScheme)
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") throw IllegalArgumentException("url must be http or https")
        if (uri.host.isNullOrBlank()) throw IllegalArgumentException("url has no host")
        return withScheme
    }

    private fun save(items: List<Shortcut>) {
        val arr = JSONArray()
        for (item in items) {
            arr.put(
                JSONObject()
                    .put("id", item.id)
                    .put("title", item.title)
                    .put("kind", item.kind)
                    .put("target", item.target),
            )
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private fun decode(raw: String?): List<Shortcut> {
        if (raw.isNullOrBlank()) return emptyList()
        val arr = JSONArray(raw)
        return List(arr.length()) { index ->
            val obj = arr.getJSONObject(index)
            Shortcut(obj.getString("id"), obj.getString("title"), obj.getString("kind"), obj.getString("target"))
        }
    }

    companion object {
        private const val PREFS = "bekon_shortcuts"
        private const val KEY = "items"
    }
}
