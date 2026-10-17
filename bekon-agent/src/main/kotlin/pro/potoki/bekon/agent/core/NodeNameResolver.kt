package pro.potoki.bekon.agent.core

/**
 * Names an accessibility node for snapshot: prefer its own text, else contentDescription;
 * a clickable node without text borrows the text of a descendant/neighbor whose bounds
 * fall inside it; otherwise view-id / class (e.g. "button (id: send)"). Pure and JVM-testable.
 */
object NodeNameResolver {
    fun resolve(
        bounds: IntArray,
        text: String?,
        desc: String?,
        clickable: Boolean,
        all: List<Pair<IntArray, String?>>,
        checkable: Boolean = false,
        viewId: String? = null,
        cls: String? = null,
    ): String {
        text?.let { if (it.isNotBlank()) return it.trim() }
        desc?.let { if (it.isNotBlank()) return it.trim() }
        if (clickable || checkable) {
            val (l, t, r, b) = bounds
            for ((ob, ot) in all) {
                val name = ot ?: continue
                if (name.isBlank()) continue
                if (ob[0] >= l && ob[1] >= t && ob[2] <= r && ob[3] <= b) return name.trim()
            }
            if (checkable) rowLabel(bounds, all)?.let { return it }
        }
        fromIdClass(viewId, cls)?.let { return it }
        return if (clickable || checkable) "clickable" else "clickable"
    }

    /** "button (id: send)" / "field (id: …)" / "control (id: …)" / "button". */
    fun fromIdClass(viewId: String?, cls: String?): String? {
        val shortId = viewId?.substringAfterLast('/')?.substringAfterLast(':')?.takeIf { it.isNotBlank() && it != "null" }
        val c = cls?.substringAfterLast('.')?.lowercase() ?: ""
        val kind = when {
            "button" in c || c == "button" -> "button"
            "image" in c || "imageview" in c || "imagebutton" in c -> "image"
            "edit" in c || "textfield" in c || c.contains("edittext") -> "field"
            "switch" in c || "checkbox" in c || "radiobutton" in c || "toggle" in c -> "toggle"
            "spinner" in c || "dropdown" in c -> "dropdown"
            shortId != null -> "control"
            else -> null
        } ?: return null
        return if (shortId != null) "$kind (id: $shortId)" else kind
    }

    private fun rowLabel(bounds: IntArray, all: List<Pair<IntArray, String?>>): String? {
        val cy = (bounds[1] + bounds[3]) / 2
        return all.asSequence()
            .filter { (ob, ot) -> !ot.isNullOrBlank() && cy in ob[1]..ob[3] && ob[2] <= bounds[0] && (ob[3] - ob[1]) < 3 * (bounds[3] - bounds[1]) }
            .minByOrNull { (ob, _) -> bounds[0] - ob[2] }?.second
    }
}
