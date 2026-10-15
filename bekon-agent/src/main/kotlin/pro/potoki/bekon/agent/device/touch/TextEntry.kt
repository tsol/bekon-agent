package pro.potoki.bekon.agent.device.touch

/**
 * Pure text-entry math for accessibility typing (no Android types, unit-testable).
 *
 * Root cause of the old "placeholder + text" bug: [AccessibilityNodeInfo.getText] returns the
 * hint/placeholder for an empty field on many editors (all of them before API 26, Compose and
 * a lot of WebView/React Native inputs even later). The old code did `SET_TEXT(node.text + new)`,
 * so an empty field got "Search…hello". The clipboard fallback pasted at the cursor without
 * selecting the placeholder in widgets that render it as real, selectable text.
 */
object TextEntry {
    /** The text actually typed by the user, with any placeholder stripped. */
    fun effectiveCurrent(text: CharSequence?, hint: CharSequence?, showingHint: Boolean): String {
        if (text.isNullOrEmpty() || showingHint) return ""
        val t = text.toString()
        if (!hint.isNullOrEmpty() && t == hint.toString()) return ""
        return t
    }

    /**
     * Full string to hand to ACTION_SET_TEXT. With [replace] the field becomes exactly [insert];
     * otherwise [insert] goes at the selection (replacing a selected range) or at the end.
     */
    fun desired(current: String, insert: String, replace: Boolean, selStart: Int = -1, selEnd: Int = -1): String {
        if (replace || current.isEmpty()) return insert
        val a = minOf(selStart, selEnd)
        val b = maxOf(selStart, selEnd)
        if (a < 0 || b > current.length) return current + insert
        return current.substring(0, a) + insert + current.substring(b)
    }

    /** Cursor position after [desired] (end of the inserted text). */
    fun cursorAfter(current: String, insert: String, replace: Boolean, selStart: Int = -1, selEnd: Int = -1): Int {
        if (replace || current.isEmpty()) return insert.length
        val a = minOf(selStart, selEnd)
        val b = maxOf(selStart, selEnd)
        if (a < 0 || b > current.length) return current.length + insert.length
        return a + insert.length
    }

    /** True when the editor kept the placeholder and glued our text to it. */
    fun gluedToHint(after: CharSequence?, hint: CharSequence?, wanted: String): Boolean {
        if (after.isNullOrEmpty() || hint.isNullOrEmpty() || wanted.isEmpty()) return false
        val a = after.toString()
        return a != wanted && a.contains(hint.toString()) && a.contains(wanted)
    }
}
