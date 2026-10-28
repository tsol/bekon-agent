package pro.potoki.bekon.agent.coach

/**
 * Scroll displacement between two consecutive downscaled luma frames (block matching on a band).
 * Pure: unit-tested. Thumbnails are tiny (≈90×195), so a ±[maxShift] search runs in a few ms.
 */
object ShiftEstimator {
    data class Result(val dx: Int, val dy: Int, val cost: Float, val cost0: Float, val dxF: Float = dx.toFloat(), val dyF: Float = dy.toFloat()) {
        /** Content moved as a whole (scroll / page swipe), not a new screen. */
        val isShift: Boolean get() = (dx != 0 || dy != 0) && cost < 7f && cost < cost0 * 0.6f
        val isStatic: Boolean get() = cost0 < 2.5f
    }

    /**
     * @param a previous, [b] current; both [w]×[h] luma 0..255.
     * Vertical shifts are matched on rows [top]..[bottom] (skip fixed bars), horizontal on all rows.
     * dy > 0 = content moved DOWN on screen (finger dragged down); dx > 0 = moved right.
     */
    fun estimate(a: IntArray, b: IntArray, w: Int, h: Int, top: Int = (h * 0.12f).toInt(), bottom: Int = (h * 0.9f).toInt(), maxShift: Int = 40, mask: BooleanArray? = null): Result {
        val c0 = sad(a, b, w, 0, 0, top, bottom, mask)
        var best = c0; var bx = 0; var by = 0
        val my = minOf(maxShift, (bottom - top) / 2)
        for (dy in -my..my) {
            if (dy == 0) continue
            val c = sad(a, b, w, 0, dy, top, bottom, mask)
            if (c < best) { best = c; by = dy; bx = 0 }
        }
        val mx = minOf(maxShift, w / 2)
        for (dx in -mx..mx) {
            if (dx == 0) continue
            val c = sad(a, b, w, dx, 0, top, bottom, mask)
            if (c < best) { best = c; bx = dx; by = 0 }
        }
        // Sub-pixel refinement: parabola through the cost at best−1, best, best+1.
        fun refine(cm: Float, cp: Float): Float {
            if (cm == Float.MAX_VALUE || cp == Float.MAX_VALUE) return 0f
            val den = cm - 2 * best + cp
            return if (den <= 1e-4f) 0f else ((cm - cp) / (2 * den)).coerceIn(-0.5f, 0.5f)
        }
        var fx = bx.toFloat(); var fy = by.toFloat()
        if (by != 0) fy += refine(sad(a, b, w, 0, by - 1, top, bottom, mask), sad(a, b, w, 0, by + 1, top, bottom, mask))
        if (bx != 0) fx += refine(sad(a, b, w, bx - 1, 0, top, bottom, mask), sad(a, b, w, bx + 1, 0, top, bottom, mask))
        return Result(bx, by, best, c0, fx, fy)
    }

    /** Mean |a(x,y) − b(x+dx, y+dy)| over the overlap (≥ 40 % of the band), stride 1.
     *  [mask] = thumbnail pixels covered by our own floating body (static while content moves): skipped. */
    private fun sad(a: IntArray, b: IntArray, w: Int, dx: Int, dy: Int, top: Int, bottom: Int, mask: BooleanArray? = null): Float {
        var s = 0L; var n = 0
        val y0 = maxOf(top, top - dy); val y1 = minOf(bottom, bottom - dy)
        val x0 = maxOf(0, -dx); val x1 = minOf(w, w - dx)
        if (y1 - y0 < (bottom - top) * 0.4f || x1 - x0 < w * 0.4f) return Float.MAX_VALUE
        for (y in y0 until y1) {
            val ra = y * w; val rb = (y + dy) * w + dx
            if (mask == null) for (x in x0 until x1) { s += kotlin.math.abs(a[ra + x] - b[rb + x]); n++ }
            else for (x in x0 until x1) { if (mask[ra + x] || mask[rb + x]) continue; s += kotlin.math.abs(a[ra + x] - b[rb + x]); n++ }
        }
        return if (n == 0) Float.MAX_VALUE else s.toFloat() / n
    }
}
