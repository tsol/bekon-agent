package pro.potoki.bekon.agent.device.touch

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Path
import android.graphics.PointF
import android.app.Instrumentation
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputConnection
import pro.potoki.bekon.agent.device.ime.BekonImeService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class TouchController(private val svc: AccessibilityService) {
    companion object {
        private const val TAG = "Touch"
        /** AccessibilityNodeInfo.ACTION_IME_ENTER (API 30); not on older compile stubs. */
        private const val ACTION_IME_ENTER = 0x00000100
        private const val KEY_GAP_MS = 12L
        private const val INJECT_ASYNC = 0
        /** Launchers often need longer than ViewConfiguration.getLongPressTimeout(). */
        private const val LONG_PRESS_MS = 1500L
        private const val DRAG_MOVE_MS = 500L
        private const val RESULT_SLACK_MS = 5000L
    }

    private var heldStroke: GestureDescription.StrokeDescription? = null
    private var heldAt: PointF? = null

    fun tap(x: Float, y: Float, dur: Long = 50): Boolean {
        GestureOverlay.showTap(x, y, "tap ${x.toInt()},${y.toInt()}")
        Log.i(TAG, "tap $x,$y dur=$dur")
        return dispatch(nudge(x, y, dur), waitMsFor(dur))
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, dur: Long = 300): Boolean {
        if (heldStroke != null) {
            val moveMs = if (dur == 300L) DRAG_MOVE_MS else dur
            Log.i(TAG, "swipe continues drag -> $x2,$y2 dur=$moveMs")
            return continueHeld(x2, y2, moveMs, keepDown = true)
        }
        GestureOverlay.showSwipe(x1, y1, x2, y2, "swipe ${x1.toInt()},${y1.toInt()} → ${x2.toInt()},${y2.toInt()}")
        Log.i(TAG, "swipe $x1,$y1 -> $x2,$y2 dur=$dur")
        if (x1 == x2 && y1 == y2) {
            return dispatch(nudge(x1, y1, dur), waitMsFor(dur))
        }
        val p = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        return dispatch(stroke(p, dur), waitMsFor(dur))
    }

    fun longPress(x: Float, y: Float): Boolean {
        endHeld()
        GestureOverlay.showLongPress(x, y, "long ${x.toInt()},${y.toInt()}")
        Log.i(TAG, "longPress $x,$y")
        return dispatch(nudge(x, y, LONG_PRESS_MS), waitMsFor(LONG_PRESS_MS))
    }

    /**
     * Finger down + long-press timeout, keep the pointer down until [swipe] / [release].
     * Needs Android 8+ ([GestureDescription.StrokeDescription] continueStroke).
     */
    fun drag(x: Float, y: Float): Boolean {
        if (Build.VERSION.SDK_INT < 26) {
            throw Exception("drag needs Android 8+")
        }
        endHeld()
        GestureOverlay.showLongPress(x, y, "drag ${x.toInt()},${y.toInt()}")
        val hold = LONG_PRESS_MS
        Log.i(TAG, "drag $x,$y hold=${hold}ms")
        val path = Path().apply { moveTo(x, y); lineTo(x + 1f, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, hold, true)
        val ok = dispatch(GestureDescription.Builder().addStroke(stroke).build(), waitMsFor(hold))
        if (ok) {
            heldStroke = stroke
            heldAt = PointF(x + 1f, y)
        }
        return ok
    }

    fun release(): Boolean = endHeld()

    fun back() = svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
    fun home() = svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
    fun recentApps() = svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)

    /**
     * Put [text] into the focused field without needing a custom keyboard.
     *
     * Order: ACTION_SET_TEXT with the full desired string (placeholder stripped, see [TextEntry]),
     * verified by reading the node back; then select-all/selection + clipboard paste; then
     * keystrokes through an InputConnection (Bekon Keys IME or the Android 13+ accessibility IME)
     * as the last resort. [asKeys] forces keystrokes (Termux and other terminals).
     */
    fun input(text: String, asKeys: Boolean = false, replace: Boolean = false): Boolean {
        if (asKeys) return inputAsKeys(text)
        val target = focusedEditable()
        if (target != null) {
            if (setTextSmart(target, text, replace)) return true
            if (pasteSmart(target, text, replace)) return true
        }
        Log.i(TAG, "SET_TEXT/paste unavailable; falling back to keys")
        if (replace) key("selectAll")
        return inputAsKeys(text)
    }

    private fun showingHint(node: AccessibilityNodeInfo): Boolean =
        Build.VERSION.SDK_INT >= 26 && node.isShowingHintText

    private fun hintOf(node: AccessibilityNodeInfo): CharSequence? =
        if (Build.VERSION.SDK_INT >= 26) node.hintText else null

    private fun setTextSmart(node: AccessibilityNodeInfo, text: String, replace: Boolean): Boolean {
        val hasSetText = node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }
        if (!hasSetText) return false
        val hint = hintOf(node)
        val cur = TextEntry.effectiveCurrent(node.text, hint, showingHint(node))
        val selS = node.textSelectionStart
        val selE = node.textSelectionEnd
        val wanted = TextEntry.desired(cur, text, replace, selS, selE)
        if (!setText(node, wanted)) return false
        node.refresh()
        val after = node.text
        if (TextEntry.gluedToHint(after, hint, wanted)) {
            Log.w(TAG, "SET_TEXT glued placeholder; clearing and retrying")
            setText(node, "")
            node.refresh()
            if (!setText(node, wanted)) return false
        }
        val caret = TextEntry.cursorAfter(cur, text, replace, selS, selE)
        val args = Bundle()
        args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, caret)
        args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, caret)
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
        return true
    }

    private fun pasteSmart(node: AccessibilityNodeInfo, text: String, replace: Boolean): Boolean {
        val cur = TextEntry.effectiveCurrent(node.text, hintOf(node), showingHint(node))
        if (replace || cur.isEmpty()) {
            // Select whatever is there (incl. a placeholder rendered as real text) so paste replaces it.
            val len = node.text?.length ?: 0
            if (len > 0) {
                val args = Bundle()
                args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, len)
                if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)) imeContextAction(android.R.id.selectAll)
            }
        }
        return paste(node, text)
    }

    fun inputAsKeys(text: String): Boolean {
        if (text.isEmpty()) return true
        val ic = currentInputConnection()
        if (ic != null) {
            Log.i(TAG, "inputAsKeys via InputConnection len=${text.length}")
            return typeViaInputConnection(ic, text)
        }
        Log.w(TAG, "no InputConnection (Android ${Build.VERSION.SDK_INT}; need 33+ and a11y IME flag)")
        if (typeViaInject(text)) return true
        throw Exception(
            "no_keys: select Bekon Keys as the keyboard (Status → Keyboard). " +
                "injectInputEvent is blocked on this Android",
        )
    }

    private fun typeViaInputConnection(ic: InputConnection, text: String): Boolean {
        for (ch in text) {
            val ok = onMain {
                when (ch) {
                    '\n', '\r' -> sendIcKey(ic, KeyEvent.KEYCODE_ENTER)
                    '\t' -> sendIcKey(ic, KeyEvent.KEYCODE_TAB)
                    // Letters, spaces and punctuation go as committed text. A key event
                    // for space or '.' is applied by the editor later than the next
                    // letter, so the characters trade places.
                    else -> {
                        val committed = ic.commitText(ch.toString(), 1)
                        ic.finishComposingText()
                        committed
                    }
                }
            }
            if (!ok) {
                Log.w(TAG, "IC failed at U+${ch.code.toString(16)}")
                return false
            }
            Thread.sleep(KEY_GAP_MS)
        }
        return true
    }

    private fun typeViaInject(text: String): Boolean {
        val kcm = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
        for (ch in text) {
            val ok = when (ch) {
                '\n', '\r' -> injectKeyCode(KeyEvent.KEYCODE_ENTER)
                '\t' -> injectKeyCode(KeyEvent.KEYCODE_TAB)
                else -> {
                    val events = kcm.getEvents(charArrayOf(ch))
                    if (events == null || events.isEmpty()) false
                    else events.all { injectKeyEvent(it) }
                }
            }
            if (!ok) return false
            Thread.sleep(KEY_GAP_MS)
        }
        return true
    }

    fun key(name: String, n: Int = 1): Boolean {
        val ok = when (name) {
            "backspace" -> {
                if (injectKeyCode(KeyEvent.KEYCODE_DEL, times = n.coerceAtLeast(1))) true
                else {
                    val target = focusedEditable() ?: return false
                    val s = target.text?.toString() ?: ""
                    setText(target, dropLastCodePoints(s, n.coerceAtLeast(1)))
                }
            }
            "enter" -> {
                val node = focusedEditable(300)
                if (node != null && Build.VERSION.SDK_INT >= 30 && node.performAction(ACTION_IME_ENTER)) true
                else if (injectKeyCode(KeyEvent.KEYCODE_ENTER)) true
                else if (node != null) setText(node, (node.text?.toString() ?: "") + "\n")
                else false
            }
            "clear" -> {
                val target = focusedEditable() ?: return false
                setText(target, "")
            }
            "selectAll" -> imeContextAction(android.R.id.selectAll) || selectionOnFocused()
            "copy" -> imeContextAction(android.R.id.copy) || actionOnFocused(AccessibilityNodeInfo.ACTION_COPY)
            "cut" -> imeContextAction(android.R.id.cut) || actionOnFocused(AccessibilityNodeInfo.ACTION_CUT)
            "paste" -> imeContextAction(android.R.id.paste) || actionOnFocused(AccessibilityNodeInfo.ACTION_PASTE)
            else -> {
                Log.w(TAG, "unknown key $name")
                false
            }
        }
        Log.i(TAG, "key $name -> $ok")
        return ok
    }

    /** Keyboard action on the focused field. Docs ignores accessibility selection. */
    private fun imeContextAction(id: Int): Boolean {
        val ic = currentInputConnection() ?: return false
        return try {
            val ok = onMain { ic.performContextMenuAction(id) }
            Log.i(TAG, "ime context $id -> $ok")
            ok
        } catch (e: Exception) {
            Log.w(TAG, "ime context $id: ${e.message}")
            false
        }
    }

    /** The focused editor only. Do not grab the first editable in the window. */
    private fun focusedInputOnly(): AccessibilityNodeInfo? {
        val root = svc.rootInActiveWindow ?: return null
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null
        return if (canSetText(focused)) focused else null
    }

    private fun actionOnFocused(action: Int): Boolean {
        val target = focusedInputOnly() ?: return false
        return target.performAction(action)
    }

    private fun selectionOnFocused(): Boolean {
        val target = focusedInputOnly() ?: return false
        val len = target.text?.length ?: 0
        val args = Bundle()
        args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
        args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, len)
        return target.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
    }

    /**
     * Input-focused editable in ANY window (browser URL bars and popups live outside rootInActiveWindow),
     * waiting briefly for focus to land after a tap; then first editable in the active window.
     */
    private fun focusedEditable(waitMs: Long = 1_200): AccessibilityNodeInfo? {
        val deadline = android.os.SystemClock.uptimeMillis() + waitMs
        while (true) {
            val f = try { svc.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } catch (_: Exception) { null }
            if (f != null && canSetText(f)) return f
            if (android.os.SystemClock.uptimeMillis() >= deadline) break
            Thread.sleep(100)
        }
        val root = svc.rootInActiveWindow
        if (root == null) { Log.w(TAG, "no active window"); return null }
        return findEditable(root)
    }

    /** Largest scrollable node across app windows (incl. WebView); ACTION_SCROLL_FORWARD/BACKWARD. */
    fun scroll(forward: Boolean): Boolean {
        var best: AccessibilityNodeInfo? = null; var bestArea = 0
        val wins = try { svc.windows } catch (_: Exception) { null }
        val roots = wins?.filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION }
            ?.mapNotNull { try { it.root } catch (_: Exception) { null } }
            ?.ifEmpty { null } ?: listOfNotNull(svc.rootInActiveWindow)
        val stack = ArrayDeque<AccessibilityNodeInfo>(); roots.forEach { stack.add(it) }
        var n = 0
        val r = android.graphics.Rect()
        while (stack.isNotEmpty() && n < 3000) {
            val x = stack.removeLast(); n++
            if (x.isScrollable && x.isVisibleToUser) {
                x.getBoundsInScreen(r); val a = r.width() * r.height()
                if (a > bestArea) { bestArea = a; best = x }
            }
            for (i in 0 until x.childCount) x.getChild(i)?.let { stack.add(it) }
        }
        val b = best ?: return false
        val ok = b.performAction(if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
        Log.i(TAG, "scroll forward=$forward node=${b.className} ok=$ok")
        return ok
    }

    private fun setText(node: AccessibilityNodeInfo, value: String): Boolean {
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (ok) Log.i(TAG, "setText ${value.length} chars")
        return ok
    }

    private fun dropLastCodePoints(s: String, n: Int): String {
        var end = s.length
        repeat(n) {
            if (end <= 0) return ""
            end = s.offsetByCodePoints(end, -1)
        }
        return s.substring(0, end)
    }

    private fun injectKeyCode(code: Int, meta: Int = 0, times: Int = 1): Boolean {
        val ic = currentInputConnection()
        if (ic != null) {
            val ok = onMain {
                (1..times.coerceAtLeast(1)).all { sendIcKey(ic, code, meta) }
            }
            if (ok) return true
        }
        repeat(times.coerceAtLeast(1)) {
            val now = SystemClock.uptimeMillis()
            val down = KeyEvent(
                now, now, KeyEvent.ACTION_DOWN, code, 0, meta,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD,
            )
            val up = KeyEvent(
                now, now, KeyEvent.ACTION_UP, code, 0, meta,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD,
            )
            if (!injectKeyEvent(down) || !injectKeyEvent(up)) return false
            if (times > 1) Thread.sleep(KEY_GAP_MS)
        }
        return true
    }

    private fun sendIcKey(ic: InputConnection, code: Int, meta: Int = 0): Boolean {
        val now = SystemClock.uptimeMillis()
        val down = KeyEvent(
            now, now, KeyEvent.ACTION_DOWN, code, 0, meta,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD,
        )
        val up = KeyEvent(
            now, now, KeyEvent.ACTION_UP, code, 0, meta,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD,
        )
        return ic.sendKeyEvent(down) && ic.sendKeyEvent(up)
    }

    /** Prefer Bekon Keys IME (Android 11+), then accessibility IME (13+). */
    private fun currentInputConnection(): InputConnection? {
        val fromIme = try {
            onMain { BekonImeService.instance?.liveConnection() }
        } catch (e: Exception) {
            Log.w(TAG, "BekonIme IC: ${e.message}")
            null
        }
        if (fromIme != null) return fromIme
        if (Build.VERSION.SDK_INT < 33) return null
        return try {
            onMain {
                val ime = AccessibilityService::class.java.getMethod("getInputMethod").invoke(svc)
                    ?: return@onMain null
                ime.javaClass.getMethod("getCurrentInputConnection").invoke(ime) as? InputConnection
            }
        } catch (e: Exception) {
            Log.w(TAG, "getInputMethod: ${e.message}")
            null
        }
    }

    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val done = CountDownLatch(1)
        val box = arrayOfNulls<Any>(1)
        var err: Exception? = null
        Handler(Looper.getMainLooper()).post {
            try {
                box[0] = block()
            } catch (e: Exception) {
                err = e
            }
            done.countDown()
        }
        if (!done.await(8, TimeUnit.SECONDS)) error("main-thread timeout")
        err?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return box[0] as T
    }

    private fun injectKeyEvent(event: KeyEvent): Boolean {
        try {
            val im = svc.getSystemService(Context.INPUT_SERVICE) ?: error("no input service")
            val m = im.javaClass.methods.firstOrNull { method ->
                method.name == "injectInputEvent" && method.parameterTypes.size >= 2
            } ?: Class.forName("android.hardware.input.InputManager")
                .getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
            m.isAccessible = true
            val result = m.invoke(im, event, INJECT_ASYNC)
            if (result is Boolean && result) return true
            if (result == null) return true
        } catch (e: Exception) {
            Log.d(TAG, "injectInputEvent: ${e.message}")
        }
        return try {
            Instrumentation().sendKeySync(event)
            true
        } catch (e: Exception) {
            Log.w(TAG, "sendKeySync: ${e.message}")
            false
        }
    }

    private fun canSetText(node: AccessibilityNodeInfo): Boolean {
        if (node.isEditable) return true
        return node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }
    }

    private fun findEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (canSetText(node)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findEditable(child)
            if (found != null) return found
        }
        return null
    }

    private fun paste(node: AccessibilityNodeInfo, text: String): Boolean {
        val cm = svc.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
        cm.setPrimaryClip(ClipData.newPlainText("wlya", text))
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        Log.i(TAG, "input paste=$ok")
        return ok
    }

    fun clipboardText(): String? {
        val cm = svc.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount < 1) return null
        val t = clip.getItemAt(0).coerceToText(svc).toString()
        return t.ifEmpty { null }
    }

    private fun waitMsFor(dur: Long) = dur.coerceAtLeast(1) + RESULT_SLACK_MS

    /** Stationary a11y paths (moveTo only) hang or never complete on some OEMs. */
    private fun nudge(x: Float, y: Float, dur: Long): GestureDescription {
        val p = Path().apply { moveTo(x, y); lineTo(x + 1f, y) }
        return stroke(p, dur)
    }

    private fun stroke(path: Path, dur: Long): GestureDescription =
        GestureDescription.Builder().addStroke(
            GestureDescription.StrokeDescription(path, 0, dur.coerceAtLeast(1))
        ).build()

    private fun continueHeld(x2: Float, y2: Float, dur: Long, keepDown: Boolean): Boolean {
        if (Build.VERSION.SDK_INT < 26) return false
        val held = heldStroke
        val at = heldAt
        if (held == null || at == null) return false
        GestureOverlay.showSwipe(at.x, at.y, x2, y2, "drag ${at.x.toInt()},${at.y.toInt()} → ${x2.toInt()},${y2.toInt()}")
        val path = Path().apply { moveTo(at.x, at.y); lineTo(x2, y2) }
        val next = held.continueStroke(path, 0, dur.coerceAtLeast(1), keepDown)
        val ok = dispatch(GestureDescription.Builder().addStroke(next).build(), waitMsFor(dur))
        if (ok && keepDown) {
            heldStroke = next
            heldAt = PointF(x2, y2)
            return true
        }
        if (keepDown) {
            try {
                val liftPath = Path().apply { moveTo(at.x, at.y); lineTo(at.x + 1f, at.y) }
                val lift = held.continueStroke(liftPath, 0, 50, false)
                dispatch(GestureDescription.Builder().addStroke(lift).build(), waitMsFor(50))
            } catch (e: Exception) {
                Log.w(TAG, "lift after failed continue: ${e.message}")
            }
        }
        heldStroke = null
        heldAt = null
        return ok
    }

    /** Lift a pointer left down by [drag]. Safe no-op if none. */
    fun endHeld(): Boolean {
        val at = heldAt
        if (heldStroke == null || at == null) {
            heldStroke = null
            heldAt = null
            return true
        }
        Log.i(TAG, "release ${at.x},${at.y}")
        return continueHeld(at.x + 1f, at.y, 50, keepDown = false)
    }

    private fun dispatch(g: GestureDescription, waitMs: Long = 2000): Boolean {
        val done = CountDownLatch(1)
        val ok = AtomicBoolean(false)
        val posted = svc.dispatchGesture(
            g,
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    ok.set(true)
                    done.countDown()
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Log.w(TAG, "gesture cancelled")
                    ok.set(false)
                    done.countDown()
                }
            },
            Handler(Looper.getMainLooper()),
        )
        if (!posted) {
            Log.e(TAG, "dispatchGesture returned false")
            return false
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return true
        }
        val wait = waitMs.coerceAtLeast(1)
        if (!done.await(wait, TimeUnit.MILLISECONDS)) {
            Log.w(TAG, "gesture result timeout (${wait}ms)")
            return false
        }
        return ok.get()
    }
}
