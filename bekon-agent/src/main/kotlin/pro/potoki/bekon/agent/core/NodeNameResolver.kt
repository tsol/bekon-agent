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
        return "clickable"
    }
}
