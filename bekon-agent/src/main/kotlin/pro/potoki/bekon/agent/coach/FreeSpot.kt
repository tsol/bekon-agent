package pro.potoki.bekon.agent.coach

/**
 * Free-space placement for Bekon's floating hints (status pill, captions, scroll cues, mascot).
 * From the latest screen frame (downscaled luma) it builds a "busy" cost grid: local contrast per
 * cell (text, icons, photos are busy; flat backgrounds are free), OCR text boxes as occupied,
 * coach frames/marks as hard obstacles. [find] returns the lowest-cost rect of a given size that
 * fits the safe area (no status bar, nav/gesture bar, cutout), closest to an anchor.
 * Frames around a specific target are never moved by this; only the elements around them.
 * Pure Kotlin core (JVM unit-tested); [updateFromFrame] is the Android entry point.
 */
object FreeSpot {
    /** Cost grid in screen space: [cols]x[rows] cells of [cell] screen px, values ~0 (plain) .. 1+ (busy). */
    class Grid(val cols: Int, val rows: Int, val cell: Int, val sw: Int, val sh: Int, val cost: FloatArray, val atMs: Long = 0L)

    @Volatile var current: Grid? = null
    /** Safe area in screen px (l,t,r,b), set by the overlay host from window insets. */
    @Volatile var safeArea: IntArray? = null
    /** Called (any thread) after the grid changed, so floating elements can re-place themselves. */
    @Volatile var onUpdate: (() -> Unit)? = null
    /** Coarse luma signature (8×8 luma-px cells, own windows masked = −1) for placement hysteresis. */
    @Volatile var onSignature: ((IntArray, Long) -> Unit)? = null
    @Volatile var maskRects: (() -> List<IntArray>)? = null

    fun signature(luma: IntArray, w: Int, h: Int, scale: Float, mask: List<IntArray>, cell: Int = 8): IntArray {
        val cols = w / cell; val rows = h / cell
        val out = IntArray(cols * rows)
        for (r in 0 until rows) for (c in 0 until cols) {
            val sx0 = c * cell * scale; val sy0 = r * cell * scale; val sx1 = sx0 + cell * scale; val sy1 = sy0 + cell * scale
            if (mask.any { it[0] < sx1 && it[2] > sx0 && it[1] < sy1 && it[3] > sy0 }) { out[r * cols + c] = -1; continue }
            var s = 0
            for (y in r * cell until (r + 1) * cell) for (x in c * cell until (c + 1) * cell) s += luma[y * w + x]
            out[r * cols + c] = s / (cell * cell)
        }
        return out
    }

    const val TEXT_COST = 0.9f
    const val OBSTACLE_COST = 6f

    /**
     * @param luma downscaled frame, [w]x[h], 0..255; [scale] = screen px per luma px.
     * @param text OCR boxes in screen px (each l,t,r,b), counted as occupied.
     */
    fun build(luma: IntArray, w: Int, h: Int, scale: Float, sw: Int, sh: Int, text: List<IntArray>, cellPx: Int = 24, now: Long = 0L): Grid {
        val cols = (sw + cellPx - 1) / cellPx; val rows = (sh + cellPx - 1) / cellPx
        val cost = FloatArray(cols * rows)
        for (r in 0 until rows) for (c in 0 until cols) {
            val x0 = ((c * cellPx) / scale).toInt().coerceIn(0, w - 1)
            val x1 = (((c + 1) * cellPx) / scale).toInt().coerceIn(x0 + 1, w)
            val y0 = ((r * cellPx) / scale).toInt().coerceIn(0, h - 1)
            val y1 = (((r + 1) * cellPx) / scale).toInt().coerceIn(y0 + 1, h)
            var sum = 0.0; var sq = 0.0; var edge = 0.0; var n = 0
            for (y in y0 until y1) for (x in x0 until x1) {
                val v = luma[y * w + x]
                sum += v; sq += v.toDouble() * v; n++
                if (x + 1 < w) edge += kotlin.math.abs(v - luma[y * w + x + 1])
                if (y + 1 < h) edge += kotlin.math.abs(v - luma[(y + 1) * w + x])
            }
            if (n == 0) continue
            val mean = sum / n
            val sd = kotlin.math.sqrt((sq / n - mean * mean).coerceAtLeast(0.0))
            val ed = edge / n
            cost[r * cols + c] = ((sd / 38.0) * 0.6 + (ed / 18.0) * 0.4).toFloat().coerceIn(0f, 1.2f)
        }
        for (t in text) addRect(cost, cols, rows, cellPx, t, TEXT_COST)
        return Grid(cols, rows, cellPx, sw, sh, cost, now)
    }

    private fun addRect(cost: FloatArray, cols: Int, rows: Int, cell: Int, r: IntArray, v: Float) {
        val c0 = (r[0] / cell).coerceIn(0, cols - 1); val c1 = ((r[2] - 1) / cell).coerceIn(0, cols - 1)
        val r0 = (r[1] / cell).coerceIn(0, rows - 1); val r1 = ((r[3] - 1) / cell).coerceIn(0, rows - 1)
        if (r[2] <= r[0] || r[3] <= r[1]) return
        for (y in r0..r1) for (x in c0..c1) cost[y * cols + x] += v
    }

    private fun setRect(cost: FloatArray, cols: Int, rows: Int, cell: Int, r: IntArray, v: Float) {
        if (r[2] <= r[0] || r[3] <= r[1]) return
        val c0 = (r[0] / cell).coerceIn(0, cols - 1); val c1 = ((r[2] - 1) / cell).coerceIn(0, cols - 1)
        val r0 = (r[1] / cell).coerceIn(0, rows - 1); val r1 = ((r[3] - 1) / cell).coerceIn(0, rows - 1)
        for (y in r0..r1) for (x in c0..c1) cost[y * cols + x] = v
    }

    /**
     * The element being placed hides what is under it in the frame: estimate those cells from the
     * 2-cell ring around it (text sticking out beside the pill keeps the spot "busy").
     */
    private fun fillFromRing(cost: FloatArray, cols: Int, rows: Int, cell: Int, r: IntArray) {
        if (r[2] <= r[0] || r[3] <= r[1]) return
        val c0 = (r[0] / cell).coerceIn(0, cols - 1); val c1 = ((r[2] - 1) / cell).coerceIn(0, cols - 1)
        val r0 = (r[1] / cell).coerceIn(0, rows - 1); val r1 = ((r[3] - 1) / cell).coerceIn(0, rows - 1)
        var s = 0f; var n = 0
        for (y in (r0 - 2).coerceAtLeast(0)..(r1 + 2).coerceAtMost(rows - 1)) for (x in (c0 - 2).coerceAtLeast(0)..(c1 + 2).coerceAtMost(cols - 1)) {
            if (y in r0..r1 && x in c0..c1) continue
            s += cost[y * cols + x]; n++
        }
        setRect(cost, cols, rows, cell, r, if (n == 0) 0.5f else s / n)
    }

    /** Result: screen rect (l,t,r,b), mean busy cost inside it, and whether it overlaps an obstacle. */
    data class Spot(val rect: IntArray, val cost: Float, val blocked: Boolean)

    /**
     * Best rect of [w]x[h] screen px.
     * @param anchor zone of interest (l,t,r,b): prefer spots near it; [anchorIsObstacle] keeps it uncovered.
     * @param obstacles frames/marks/other hints that must not be covered.
     * @param self current rect of the element being placed (its own pixels are in the frame: neutralised).
     * @param distWeight trade-off between plainness and closeness (per screen diagonal).
     */
    fun find(
        g: Grid, w: Int, h: Int, anchor: IntArray, obstacles: List<IntArray> = emptyList(),
        safe: IntArray = intArrayOf(0, 0, g.sw, g.sh), anchorIsObstacle: Boolean = false,
        self: IntArray? = null, distWeight: Float = 0.9f, margin: Int = 16,
    ): Spot? {
        val cols = g.cols; val rows = g.rows; val cell = g.cell
        val cost = g.cost.copyOf()
        if (self != null) fillFromRing(cost, cols, rows, cell, self)
        for (o in obstacles) addRect(cost, cols, rows, cell, o, OBSTACLE_COST)
        if (anchorIsObstacle) addRect(cost, cols, rows, cell, anchor, OBSTACLE_COST)
        // Integral image over cells.
        val ii = DoubleArray((cols + 1) * (rows + 1))
        for (y in 0 until rows) {
            var rs = 0.0
            for (x in 0 until cols) {
                rs += cost[y * cols + x]
                ii[(y + 1) * (cols + 1) + x + 1] = ii[y * (cols + 1) + x + 1] + rs
            }
        }
        fun sum(c0: Int, r0: Int, c1: Int, r1: Int): Double = // [c0,c1) x [r0,r1)
            ii[r1 * (cols + 1) + c1] - ii[r0 * (cols + 1) + c1] - ii[r1 * (cols + 1) + c0] + ii[r0 * (cols + 1) + c0]
        val wc = ((w + cell - 1) / cell).coerceAtLeast(1); val hc = ((h + cell - 1) / cell).coerceAtLeast(1)
        // Clamp to the grid: after a rotation the safe rect can come from the new orientation
        // while the grid still describes the old one.
        val sl = ((safe[0] + margin + cell - 1) / cell).coerceAtLeast(0); val st = ((safe[1] + margin + cell - 1) / cell).coerceAtLeast(0)
        val sr = ((safe[2] - margin) / cell).coerceAtMost(cols); val sb = ((safe[3] - margin) / cell).coerceAtMost(rows)
        if (sr - sl < wc || sb - st < hc) return null
        val ax = (anchor[0] + anchor[2]) / 2f; val ay = (anchor[1] + anchor[3]) / 2f
        val diag = kotlin.math.hypot(g.sw.toFloat(), g.sh.toFloat())
        var best: Spot? = null; var bestScore = Double.MAX_VALUE
        for (r0 in st..(sb - hc)) for (c0 in sl..(sr - wc)) {
            val mean = sum(c0, r0, c0 + wc, r0 + hc) / (wc * hc)
            val l = c0 * cell; val t = r0 * cell
            // Distance from the anchor rect to the candidate rect (0 when touching/overlapping).
            val dx = maxOf(anchor[0] - (l + w), l - anchor[2], 0).toFloat()
            val dy = maxOf(anchor[1] - (t + h), t - anchor[3], 0).toFloat()
            val cdist = kotlin.math.hypot((l + w / 2f) - ax, (t + h / 2f) - ay) * 0.15f
            val score = mean + distWeight * (kotlin.math.hypot(dx, dy) + cdist) / diag
            if (score < bestScore) {
                bestScore = score
                best = Spot(intArrayOf(l, t, l + w, t + h), mean.toFloat(), mean >= OBSTACLE_COST / (wc * hc))
            }
        }
        return best
    }

    /** Mean busy cost of an existing rect (for hysteresis: only move when clearly better). */
    fun costAt(g: Grid, rect: IntArray, obstacles: List<IntArray> = emptyList(), self: IntArray? = null): Float {
        val cost = g.cost.copyOf()
        if (self != null) fillFromRing(cost, g.cols, g.rows, g.cell, self)
        for (o in obstacles) addRect(cost, g.cols, g.rows, g.cell, o, OBSTACLE_COST)
        val c0 = (rect[0] / g.cell).coerceIn(0, g.cols - 1); val c1 = ((rect[2] - 1) / g.cell).coerceIn(c0, g.cols - 1)
        val r0 = (rect[1] / g.cell).coerceIn(0, g.rows - 1); val r1 = ((rect[3] - 1) / g.cell).coerceIn(r0, g.rows - 1)
        var s = 0f; var n = 0
        for (y in r0..r1) for (x in c0..c1) { s += cost[y * g.cols + x]; n++ }
        return if (n == 0) 0f else s / n
    }

    /** Approximate caption chip size for SketchPainter.drawLabel at k=1 (46 px bold text). */
    fun labelSize(text: String, sw: Int): Pair<Int, Int> {
        val w = (text.length * 25 + 40).coerceAtMost((sw * 0.86f).toInt())
        val lines = ((text.length * 25 + 40) / (sw * 0.86f)).toInt() + 1
        return w to (lines * 53 + 32)
    }

    private fun Shape.rect(pad: Float = 0f) = intArrayOf(
        (minOf(x1, x2) - pad).toInt(), (minOf(y1, y2) - pad).toInt(), (maxOf(x1, x2) + pad).toInt(), (maxOf(y1, y2) + pad).toInt(),
    )

    /**
     * Places every caption (shape label) on the plainest free spot next to its shape, never over a
     * frame/mark or another caption. Frames themselves stay exactly on the target. No grid = unchanged.
     */
    fun placeLabels(shapes: List<Shape>, g: Grid? = current, safe: IntArray? = safeArea, extra: List<IntArray> = emptyList()): List<Shape> {
        if (g == null) return shapes
        val sa = safe ?: intArrayOf(0, (g.sh * 0.04f).toInt(), g.sw, (g.sh * 0.94f).toInt())
        val taken = ArrayList<IntArray>()
        shapes.forEach { if (it.kind != Shape.Kind.GRID && it.kind != Shape.Kind.TEXT) taken += it.rect(10f) }
        taken += extra
        return shapes.map { s ->
            val label = s.label
            if (label.isNullOrBlank() || s.kind == Shape.Kind.MARK || s.kind == Shape.Kind.GRID) return@map s
            val (lw, lh) = labelSize(label, g.sw)
            val anchor = if (s.kind == Shape.Kind.TEXT) intArrayOf(s.x1.toInt(), s.y1.toInt(), s.x1.toInt() + lw, s.y1.toInt() + lh) else s.rect(10f)
            val spot = find(g, lw, lh, anchor, taken, sa, anchorIsObstacle = s.kind != Shape.Kind.TEXT, distWeight = 2.4f, margin = 8)
                ?: return@map s
            taken += spot.rect
            s.copy(lx = spot.rect[0].toFloat(), ly = spot.rect[1].toFloat())
        }
    }

    /** Android: rebuild [current] from a captured frame + OCR text boxes (screen px). */
    fun updateFromFrame(bmp: android.graphics.Bitmap, sw: Int, sh: Int, text: List<IntArray>) {
        try {
            val dw = (sw / 4).coerceAtLeast(16); val dh = (sh / 4).coerceAtLeast(16)
            val small = android.graphics.Bitmap.createScaledBitmap(bmp, dw, dh, true)
            val px = IntArray(dw * dh); small.getPixels(px, 0, dw, 0, 0, dw, dh)
            if (small !== bmp) small.recycle()
            val luma = IntArray(px.size) { val p = px[it]; (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000 }
            val now = System.currentTimeMillis()
            onSignature?.let { cb -> cb(signature(luma, dw, dh, sw.toFloat() / dw, maskRects?.invoke() ?: emptyList()), now) }
            current = build(luma, dw, dh, sw.toFloat() / dw, sw, sh, text, now = now)
            onUpdate?.invoke()
        } catch (_: Exception) {}
    }
}
