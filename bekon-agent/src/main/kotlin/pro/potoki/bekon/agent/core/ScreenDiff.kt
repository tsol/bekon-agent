package pro.potoki.bekon.agent.core

/**
 * Pure tree hashing + diff for wait_for_change. Status-bar items are excluded so the clock
 * and notification icons never count as a change. Coordinates are bucketed (8px) so a
 * one-pixel relayout does not count either.
 */
object ScreenDiff {
    private fun sig(n: UiNode): String {
        val b = n.bounds?.joinToString(",") { (it / 8).toString() } ?: "${n.x / 8},${n.y / 8}"
        return "${n.label}|$b|${if (n.checkable) (if (n.checked) "1" else "0") else "-"}|${if (n.selected) "s" else ""}|${if (n.enabled) "" else "d"}"
    }

    fun meaningful(items: List<UiNode>) = items.filter { !it.statusBar }

    /** Stable baseline token (16 hex chars, FNV-1a 64) of the meaningful items. */
    fun token(items: List<UiNode>): String {
        var h = -0x340d631b7bdddcdbL
        for (n in meaningful(items)) {
            for (c in sig(n)) { h = h xor c.code.toLong(); h *= 0x100000001b3L }
            h = h xor 0x1fL; h *= 0x100000001b3L
        }
        return java.lang.Long.toHexString(h).padStart(16, '0')
    }

    data class Change(val ref: String, val name: String, val what: String)
    data class Diff(val added: List<UiNode>, val removed: List<UiNode>, val changed: List<Change>) {
        val empty get() = added.isEmpty() && removed.isEmpty() && changed.isEmpty()
        fun summary(): String = buildString {
            if (empty) { append("no visible change"); return@buildString }
            changed.take(8).forEach { append("~ ${it.name}: ${it.what}\n") }
            added.take(12).forEach { append("+ ${it.label}${state(it)}\n") }
            removed.take(12).forEach { append("- ${it.label}\n") }
            val more = (changed.size - 8).coerceAtLeast(0) + (added.size - 12).coerceAtLeast(0) + (removed.size - 12).coerceAtLeast(0)
            if (more > 0) append("(+$more more)\n")
        }.trimEnd()
    }

    fun state(n: UiNode): String = buildString {
        if (n.checkable) append(if (n.checked) " [on]" else " [off]")
        if (n.selected) append(" [selected]")
        if (!n.enabled) append(" [disabled]")
    }

    private fun cmp(a: UiNode, b: UiNode): String? {
        val parts = ArrayList<String>()
        if (a.label != b.label) parts += "name '${a.label}' -> '${b.label}'"
        if (a.checkable || b.checkable) if (a.checked != b.checked) parts += if (b.checked) "off -> on" else "on -> off"
        if (a.selected != b.selected) parts += if (b.selected) "selected" else "unselected"
        if (a.enabled != b.enabled) parts += if (b.enabled) "enabled" else "disabled"
        return if (parts.isEmpty()) null else parts.joinToString(", ")
    }

    fun diff(old: List<UiNode>, new: List<UiNode>): Diff {
        val o = meaningful(old).toMutableList()
        val n = meaningful(new)
        val unmatched = ArrayList<UiNode>()
        val changed = ArrayList<Change>()
        // 1) same label (+ nearest position), 2) same bounds with a new label.
        val pending = ArrayList<UiNode>()
        for (b in n) {
            val cands = o.filter { it.label == b.label && it.label.isNotEmpty() }
            val a = cands.minByOrNull { Math.abs(it.x - b.x) + Math.abs(it.y - b.y) }
            if (a != null) { o.remove(a); cmp(a, b)?.let { changed += Change(b.ref, b.label, it) } } else pending += b
        }
        for (b in pending) {
            val a = o.firstOrNull { it.bounds != null && b.bounds != null && it.bounds.contentEquals(b.bounds) }
            if (a != null) { o.remove(a); cmp(a, b)?.let { changed += Change(b.ref, b.label, it) } } else unmatched += b
        }
        return Diff(unmatched, o, changed)
    }
}
