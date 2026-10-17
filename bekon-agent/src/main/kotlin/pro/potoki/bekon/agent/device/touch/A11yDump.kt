package pro.potoki.bekon.agent.device.touch

import android.content.Context
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

object A11yDump {
    private const val TAG = "A11yDump"
    const val PREFS_DUMP = "a11y_dump"
    private const val PREFS_NAME = "wlya_prefs"
    private const val MAX_NODES = 2500
    /** Long web pages: stop walking after this so a request never hangs the tunnel. */
    private const val WALK_BUDGET_MS = 2_000L
    @Volatile private var deadline = Long.MAX_VALUE
    private const val MAX_TEXT = 200

    fun isDumpEnabled(ctx: Context): Boolean = true

    fun setDumpEnabled(ctx: Context, on: Boolean) {
        ctx.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREFS_DUMP, on)
            .apply()
    }

    /** Gzip+Base64 of a compact JSON array, or null if dump is off/unavailable/empty. */
    fun gzipBase64OrNull(ctx: Context): String? {
        if (!isDumpEnabled(ctx)) return null
        val json = dumpJson() ?: return null
        if (json == "[]") return null
        return try {
            gzipB64(json)
        } catch (e: Exception) {
            Log.w(TAG, "gzip: ${e.message}")
            null
        }
    }

    fun dumpJson(): String? {
        val svc = TouchService.active ?: return null
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return dumpOnService(svc)
        }
        val box = arrayOfNulls<String>(1)
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            try {
                box[0] = dumpOnService(TouchService.active)
            } catch (e: Exception) {
                Log.w(TAG, "dump: ${e.message}")
            } finally {
                latch.countDown()
            }
        }
        return try {
            latch.await(2, TimeUnit.SECONDS)
            box[0]
        } catch (_: InterruptedException) {
            null
        }
    }

    private fun dumpOnService(svc: TouchService?): String? {
        if (svc == null) return null
        val out = JSONArray()
        deadline = android.os.SystemClock.uptimeMillis() + WALK_BUDGET_MS
        try {
            val windows = svc.windows
            val sbH = statusBarHeight(svc)
            if (!windows.isNullOrEmpty()) {
                for (w in windows) {
                    if (out.length() >= MAX_NODES) break
                    // Bekon's own coach/gesture overlays are never screen content.
                    if (w.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue
                    val sysWin = w.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM
                    val root = try {
                        w.root
                    } catch (_: Exception) {
                        null
                    }
                    val winType = windowType(w)
                    if (root != null) {
                        walk(root, out, if (sysWin) sbH else -1, winType)
                        recycleQuiet(root)
                    }
                }
            } else {
                val root = svc.rootInActiveWindow ?: return null
                walk(root, out, -1, "application")
                recycleQuiet(root)
            }
        } catch (e: Exception) {
            Log.w(TAG, "walk: ${e.message}")
            return null
        }
        return if (out.length() == 0) null else out.toString()
    }

    private fun statusBarHeight(svc: TouchService): Int = try {
        val id = svc.resources.getIdentifier("status_bar_height", "dimen", "android")
        if (id > 0) svc.resources.getDimensionPixelSize(id) else 0
    } catch (_: Exception) { 0 }

    /** Checked/selected state of a row's descendants (MIUI radio rows keep it on a child). */
    private fun childState(node: AccessibilityNodeInfo, depth: Int, acc: BooleanArray) {
        if (depth > 3) return
        for (i in 0 until node.childCount) {
            val c = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
            if (c.isCheckable) { acc[0] = true; if (c.isChecked) acc[1] = true }
            if (c.isSelected) acc[2] = true
            val cls = c.className?.toString() ?: ""
            if (cls.contains("RadioButton") || cls.contains("CheckBox") || cls.contains("Switch") || cls.contains("CheckedTextView")) acc[0] = true
            childState(c, depth + 1, acc)
            recycleQuiet(c)
        }
    }

    /** [sbH] >= 0: node is in a system window; mark nodes inside the status bar strip. */
    private fun windowType(w: android.view.accessibility.AccessibilityWindowInfo): String {
        val t = w.type
        return when (t) {
            android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "ime"
            android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM -> "system"
            android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION ->
                // Non-active application windows are typically menus, dialogs, popups, edge panels.
                if (w.isActive) "application" else "popup"
            else -> "other"
        }
    }

    private fun walk(node: AccessibilityNodeInfo, out: JSONArray, sbH: Int, winType: String) {
        if (out.length() >= MAX_NODES) return
        if (android.os.SystemClock.uptimeMillis() > deadline) return
        // Never ship password contents off the device.
        val text = if (node.isPassword) (if (node.text.isNullOrEmpty()) null else "[password hidden]") else clip(node.text?.toString())
        val desc = clip(node.contentDescription?.toString())
        val id = node.viewIdResourceName
        val clickable = node.isClickable
        val editable = node.isEditable
        var checkable = node.isCheckable
        var checked = node.isCheckable && node.isChecked
        var selected = node.isSelected
        val enabled = node.isEnabled
        if (clickable && !checkable) {
            val acc = BooleanArray(3)
            childState(node, 0, acc)
            if (acc[0]) { checkable = true; checked = acc[1] }
            if (acc[2]) selected = true
        }
        val focused = node.isFocused
        val useful = !text.isNullOrEmpty() || !desc.isNullOrEmpty() || !id.isNullOrEmpty() ||
            clickable || editable || checkable || selected || focused
        if (useful) {
            val r = Rect()
            node.getBoundsInScreen(r)
            val obj = JSONObject()
            obj.put("bounds", JSONArray().put(r.left).put(r.top).put(r.right).put(r.bottom))
            if (!text.isNullOrEmpty()) obj.put("text", text)
            if (!desc.isNullOrEmpty()) obj.put("desc", desc)
            val cls = node.className?.toString()?.substringAfterLast('.')
            if (!cls.isNullOrEmpty()) obj.put("class", cls)
            if (!id.isNullOrEmpty()) obj.put("id", id)
            if (clickable) obj.put("clickable", true)
            if (editable) obj.put("editable", true)
            if (checkable) obj.put("checkable", true)
            if (checked) obj.put("checked", true)
            if (selected) obj.put("selected", true)
            if (!enabled) obj.put("enabled", false)
            if (focused) obj.put("focused", true)
            node.packageName?.toString()?.let { obj.put("pkg", it) }
            if (sbH >= 0 && r.bottom <= sbH + 4) obj.put("statusBar", true)
            obj.put("win", winType)
            out.put(obj)
        }
        val n = node.childCount
        for (i in 0 until n) {
            if (out.length() >= MAX_NODES) return
            val child = try {
                node.getChild(i)
            } catch (_: Exception) {
                null
            } ?: continue
            walk(child, out, sbH, winType)
            recycleQuiet(child)
        }
    }

    private fun clip(s: String?): String? {
        if (s.isNullOrBlank()) return null
        val t = s.trim()
        return if (t.length <= MAX_TEXT) t else t.take(MAX_TEXT)
    }

    private fun recycleQuiet(node: AccessibilityNodeInfo) {
        try {
            node.recycle()
        } catch (_: Exception) {
            /* ignore */
        }
    }

    private fun gzipB64(json: String): String {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { gz ->
            gz.write(json.toByteArray(Charsets.UTF_8))
        }
        return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
    }
}
