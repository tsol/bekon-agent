package pro.potoki.bekon.agent.core

/**
 * Names an accessibility node for snapshot: prefer its own text, else contentDescription;
 * a clickable node without text borrows the text of a descendant/neighbor whose bounds
 * fall inside it. Pure and device-independent so it is unit-testable on JVM.
 */
object NodeNameResolver {
    fun resolve(
        bounds: IntArray,
        text: String?,
        desc: String?,
        clickable: Boolean,
        all: List<Pair<IntArray, String?>>,
        checkable: Boolean = false,
    ): String {
        text?.let { if (it.isNotBlank()) return it }
        desc?.let { if (it.isNotBlank()) return it }
        if (!clickable) return "clickable"
        val (l, t, r, b) = bounds
        for ((ob, ot) in all) {
            val name = ot ?: continue
            if (name.isBlank()) continue
            if (ob[0] >= l && ob[1] >= t && ob[2] <= r && ob[3] <= b) return name
        }
        // A bare switch/checkbox (MIUI Autostart rows): name it after the text on its row,
        // so the diff says "Bekon Agent: off -> on" instead of "clickable: off -> on".
        if (checkable) rowLabel(bounds, all)?.let { return it }
        return "clickable"
    }

    private fun rowLabel(bounds: IntArray, all: List<Pair<IntArray, String?>>): String? {
        val cy = (bounds[1] + bounds[3]) / 2
        return all.asSequence()
            .filter { (ob, ot) -> !ot.isNullOrBlank() && cy in ob[1]..ob[3] && ob[2] <= bounds[0] && (ob[3] - ob[1]) < 3 * (bounds[3] - bounds[1]) }
            .minByOrNull { (ob, _) -> bounds[0] - ob[2] }?.second
    }
}
