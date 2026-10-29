package pro.potoki.bekon.agent.coach

/**
 * Maps agent shapes (screencap / screen px, status + navigation bars included, e.g. 1080x2340) onto the hint
 * overlay. An app overlay window cannot cover the system bars (Android keeps them on top: on a Redmi 9 the
 * TYPE_APPLICATION_OVERLAY frame ends at y=2210 above the 130 px nav bar), so:
 * - a target inside the safe area is drawn exactly where the agent put it (circles stay circles);
 * - a target whose CENTRE lies in a system bar (Home / Back / status icons) is drawn unsquashed at its true
 *   screen position (the visible arc is exactly where it belongs) plus an arrow from inside the window pointing at it;
 * - only a target that is partly past the edge with its centre still inside (content scrolled under the bar) is
 *   squashed against the edge (cartoon "smeared on the wall").
 */
object TargetMapping {
    /** Length of the pointer arrow drawn towards a target in a system bar (px). */
    const val POINTER_LEN = 150f

    fun map(list: List<Shape>, top: Float, bottom: Float): List<Shape> {
        val out = ArrayList<Shape>(list.size + 1)
        for (s in list) {
            if (s.kind == Shape.Kind.GRID || s.kind == Shape.Kind.SCROLL || s.kind == Shape.Kind.ARROW) { out += s; continue }
            val t = minOf(s.y1, s.y2); val b = maxOf(s.y1, s.y2)
            if (t >= top && b <= bottom) { out += s; continue }
            val cy = (t + b) / 2f
            when {
                cy > bottom || cy < top -> {
                    out += s
                    pointer(s, top, bottom)?.let { out += it }
                }
                else -> out += squash(s, top, bottom)
            }
        }
        return out
    }

    /** True when a target rect [l,t,r,b] has its centre in a system bar (drawn exact, not clamped). */
    fun inSystemBar(t: Float, b: Float, top: Float, bottom: Float): Boolean {
        val cy = (t + b) / 2f
        return cy > bottom || cy < top
    }

    private fun pointer(s: Shape, top: Float, bottom: Float): Shape? {
        if (s.kind == Shape.Kind.MARK || s.kind == Shape.Kind.TEXT) return null
        val cx = (s.x1 + s.x2) / 2f
        val t = minOf(s.y1, s.y2); val b = maxOf(s.y1, s.y2)
        return if ((t + b) / 2f > bottom) {
            val headY = bottom - 4f
            Shape(Shape.Kind.ARROW, cx, headY - POINTER_LEN, cx, headY, color = s.color)
        } else {
            val headY = top + 4f
            Shape(Shape.Kind.ARROW, cx, headY + POINTER_LEN, cx, headY, color = s.color)
        }
    }

    /** Target partly past the top/bottom edge: clamp to the edge and widen ("smeared on the wall"). */
    fun squash(s: Shape, top: Float, bottom: Float): Shape {
        val t = minOf(s.y1, s.y2); val b = maxOf(s.y1, s.y2); val h = (b - t).coerceAtLeast(1f)
        if (t >= top && b <= bottom) return s
        val nt = t.coerceIn(top, bottom); val nb = b.coerceIn(top, bottom)
        val f = ((nb - nt) / h).coerceIn(0.15f, 1f)
        val ease = 1f - f * f                       // cartoon ease: barely at first, then flattens
        val nh = maxOf(nb - nt, h * 0.22f)
        val ny1 = if (t < top) top else bottom - nh
        val w = kotlin.math.abs(s.x2 - s.x1); val grow = w * 0.18f * ease
        return s.copy(x1 = minOf(s.x1, s.x2) - grow, x2 = maxOf(s.x1, s.x2) + grow, y1 = ny1, y2 = ny1 + nh)
    }
}
