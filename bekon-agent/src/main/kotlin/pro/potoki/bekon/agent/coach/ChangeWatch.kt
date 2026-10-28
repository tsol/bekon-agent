package pro.potoki.bekon.agent.coach

/**
 * Fast hint removal (guidance). While marks are shown, every captured frame is probed at a few dozen
 * low-res points around the target frames. When that area changes (tap ripple, new screen, scroll),
 * [onChange] fires within ~1–2 frames, so the hint fades out at once instead of waiting for the
 * settle + OCR + fingerprint path (~1–3 s). Placing NEW hints keeps the longer settle debounce.
 * Pure logic (no Android types) so it is unit-tested.
 */
object ChangeWatch {
    const val SETTLE_MS = 300L        // reference frame taken this long after the marks were drawn
    const val PIXEL_DELTA = 24        // luma change that counts a probe point as changed
    const val CHANGED_FRACTION = 0.15f
    const val CONFIRM_FRAMES = 2      // consecutive changed frames (filters a single noisy frame)

    @Volatile private var pts: IntArray? = null   // x0,y0,x1,y1,… in screen px
    @Volatile private var ref: IntArray? = null
    @Volatile private var armAt = 0L
    @Volatile private var hits = 0
    @Volatile private var fired = false
    /** Right after a touch landed (anywhere), react to the first changed frame and smaller changes. */
    const val TOUCH_BOOST_MS = 1500L
    const val TOUCH_FRACTION = 0.03f
    const val TOUCH_PIXEL_DELTA = 10
    @Volatile private var touchAt = 0L
    fun touched(now: Long) { touchAt = now }

    @Volatile var screenW = 0; private set
    @Volatile var lastFireAt = 0L; private set
    /** Called once per arm when the area changed (capture thread). */
    @Volatile var onChange: ((Long) -> Unit)? = null

    val active: Boolean get() = pts != null && !fired

    /** @param rects target areas in screen px (l,t,r,b), already inflated by the caller. */
    fun arm(rects: List<IntArray>, screenWidth: Int, now: Long, cols: Int = 14, rows: Int = 8) {
        if (rects.isEmpty()) { disarm(); return }
        val out = ArrayList<Int>()
        for (r in rects) {
            val w = (r[2] - r[0]).coerceAtLeast(1); val h = (r[3] - r[1]).coerceAtLeast(1)
            for (j in 0 until rows) for (i in 0 until cols) {
                out += r[0] + ((i + 0.5f) * w / cols).toInt(); out += r[1] + ((j + 0.5f) * h / rows).toInt()
            }
        }
        screenW = screenWidth; ref = null; hits = 0; fired = false; armAt = now + SETTLE_MS; pendingAt = 0L
        pts = out.toIntArray()
    }

    /** Content scrolled under the probes: take a fresh reference (no settle wait), keep watching. */
    fun rebase(now: Long) { ref = null; hits = 0; armAt = now; pendingAt = 0L }

    /** Re-arm on moved rects without the initial settle (after a scroll moved the hints). */
    fun move(rects: List<IntArray>, screenWidth: Int, now: Long) {
        val keepFired = fired
        arm(rects, screenWidth, now); armAt = now; fired = keepFired
    }

    fun disarm() { pts = null; ref = null; hits = 0; fired = false }

    /**
     * Probe one frame. [luma] returns 0..255 for a SCREEN-px point, or -1 when it is outside the frame
     * or masked (e.g. under the pill). Returns true when this frame triggered [onChange].
     */
    /** A touch-boosted hit waits one more frame: a scroll that follows (rebase) cancels it. */
    private var pendingAt = 0L

    /** [motion] = this frame moved but is not a pure shift (the static path passes false). */
    fun offer(now: Long, motion: Boolean = false, luma: (Int, Int) -> Int): Boolean {
        val p = pts ?: return false
        if (fired || now < armAt) return false
        val n = p.size / 2
        val cur = IntArray(n) { luma(p[it * 2], p[it * 2 + 1]) }
        val r = ref
        if (r == null || r.size != n) { ref = cur; return false }
        val boosted = touchAt != 0L && now - touchAt in 0..TOUCH_BOOST_MS
        val delta = if (boosted) TOUCH_PIXEL_DELTA else PIXEL_DELTA
        var counted = 0; var changed = 0
        for (i in 0 until n) {
            if (cur[i] < 0 || r[i] < 0) continue
            counted++
            if (kotlin.math.abs(cur[i] - r[i]) > delta) changed++
        }
        if (counted == 0) return false
        if (changed >= counted * (if (boosted) TOUCH_FRACTION else CHANGED_FRACTION)) hits++ else { hits = 0; pendingAt = 0L }
        if (hits < (if (boosted) 1 else CONFIRM_FRAMES)) return false
        if (boosted) {
            // The finger may be starting a scroll (the press highlight changes the row first):
            // confirm on a later non-shift motion frame, or a still frame 350 ms on.
            if (pendingAt == 0L) { pendingAt = now; return false }
            if (!motion && now - pendingAt < 350) return false
        }
        pendingAt = 0L
        fired = true; lastFireAt = now
        onChange?.invoke(now)
        return true
    }
}
