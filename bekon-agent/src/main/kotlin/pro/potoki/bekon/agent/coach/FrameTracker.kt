package pro.potoki.bekon.agent.coach

import android.graphics.Bitmap
import android.util.Log

/**
 * Per-captured-frame hook (guidance, capture thread). While anchored hints are on screen:
 *  - pure scroll / page swipe (ShiftEstimator) → move the anchored hints with the content;
 *  - other change in the hinted area (ChangeWatch) → fade the hints out at once (~1–2 frames).
 * Static frames cost one tiny downscale. Nothing runs when no hint is shown.
 */
object FrameTracker {
    private const val TAG = "FrameTracker"
    private const val TW = 135
    private var prev: IntArray? = null
    private var prevH = 0
    private var lastShiftAt = 0L
    private var holdSince = 0L
    /** Keyframe for drift-free tracking: displacement is measured key→current, not frame→frame. */
    private var key: IntArray? = null
    private var mask: BooleanArray? = null
    private var emittedX = 0f; private var emittedY = 0f
    private const val AMBIGUOUS_COST = 12f
    private const val SOFT_COST = 12f
    private const val HOLD_MS = 400L
    /** Screen px per thumbnail px (x, y) of the last frame. */
    @Volatile var sx = 12f; private set
    @Volatile var sy = 12f; private set

    @Volatile var enabled = false

    fun reset() { prev = null; key = null; emittedX = 0f; emittedY = 0f }

    fun onFrame(bmp: Bitmap, screenW: Int, screenH: Int) {
        if (!enabled) { prev = null; return }
        val now = System.currentTimeMillis()
        val th = (TW * bmp.height / bmp.width.coerceAtLeast(1)).coerceAtLeast(16)
        val small = try { Bitmap.createScaledBitmap(bmp, TW, th, true) } catch (_: Exception) { return }
        val px = IntArray(TW * th); small.getPixels(px, 0, TW, 0, 0, TW, th)
        if (small !== bmp) small.recycle()
        val cur = IntArray(px.size) { val p = px[it]; (((p shr 16) and 0xFF) * 3 + ((p shr 8) and 0xFF) * 6 + (p and 0xFF)) / 10 }
        sx = screenW.toFloat() / TW; sy = screenH.toFloat() / th
        val p = prev
        prev = cur
        if (p == null || prevH != th) { prevH = th; return }
        // Our own body/pill does not scroll with the content: keep it out of the match.
        val mask = BooleanArray(cur.size) { i -> val x = ((i % TW) + 0.5f) * sx; val y = ((i / TW) + 0.5f) * sy
            pro.potoki.bekon.agent.device.touch.StatusPill.ownsPoint(x.toInt(), y.toInt()) }
        this.mask = mask
        if (key?.size != cur.size) { key = p; emittedX = 0f; emittedY = 0f }
        val r = ShiftEstimator.estimate(p, cur, TW, th, mask = mask)
        if (r.isStatic || r.isShift) holdSince = 0L
        if (r.isStatic) {
            if (lastShiftAt != 0L) settleKey(cur, th)
            else { key = cur; emittedX = 0f; emittedY = 0f }
            if (lastShiftAt != 0L && now - lastShiftAt > 250) { lastShiftAt = 0L; CoachOverlay.onScrollSettled() }
            ChangeWatch.offer(now) { x, y -> lumaAt(cur, th, x, y) }
            return
        }
        // Mid-scroll, motion blur / rows entering raise the match cost above the strict shift threshold;
        // dropping those frames under-tracks the hints, so accept a clearly better-than-zero match then.
        if (r.isShift || (lastShiftAt != 0L && now - lastShiftAt < 400 && softShift(r))) {
            lastShiftAt = now
            trackFromKey(cur, th, r)
            // The probe reference moved with the content: retake it on the next frame.
            ChangeWatch.rebase(now)
            return
        }
        if (r.cost0 < AMBIGUOUS_COST && (holdSince == 0L || now - holdSince < HOLD_MS)) {
            // Small motion (a slow scroll moves < 1 thumbnail px per frame): keep the older frame as the
            // reference so the shift accumulates until the estimator can see it; no change verdict yet.
            if (holdSince == 0L) holdSince = now
            prev = p
            return
        }
        holdSince = 0L
        if (ChangeWatch.offer(now, motion = true) { x, y -> lumaAt(cur, th, x, y) }) Log.i(TAG, "area changed (cost0=${r.cost0}) → fade hints")
    }

    private fun softShift(r: ShiftEstimator.Result) =
        (r.dx != 0 || r.dy != 0) && r.cost < SOFT_COST && r.cost < r.cost0 * 0.8f

    /** Move the hints by the key→current displacement (sub-pixel), minus what was already applied. */
    private fun trackFromKey(cur: IntArray, th: Int, frameShift: ShiftEstimator.Result) {
        val k = key
        val rk = if (k != null) ShiftEstimator.estimate(k, cur, TW, th, mask = mask) else null
        val totX: Float; val totY: Float
        if (rk != null && (rk.isShift || softShift(rk)) && kotlin.math.abs(rk.dy - (emittedY / sy)) < 12 && kotlin.math.abs(rk.dx - (emittedX / sx)) < 12) {
            totX = rk.dxF * sx; totY = rk.dyF * sy
        } else { totX = emittedX + frameShift.dxF * sx; totY = emittedY + frameShift.dyF * sy }
        val dx = totX - emittedX; val dy = totY - emittedY
        emittedX = totX; emittedY = totY
        if (dx != 0f || dy != 0f) CoachOverlay.translateAnchored(dx, dy)
        // Far from the key (search window ±40): start a new key; error carried ≤ one refinement.
        if (kotlin.math.abs(totY / sy) > 24 || kotlin.math.abs(totX / sx) > 24) { key = cur; emittedX = 0f; emittedY = 0f }
    }

    /** Scroll stopped: apply the final key→current correction, then re-key. */
    private fun settleKey(cur: IntArray, th: Int) {
        val k = key
        if (k != null) {
            val rk = ShiftEstimator.estimate(k, cur, TW, th, mask = mask)
            if (rk.isShift) {
                val dx = rk.dxF * sx - emittedX; val dy = rk.dyF * sy - emittedY
                if (kotlin.math.abs(dx) + kotlin.math.abs(dy) >= 1f) CoachOverlay.translateAnchored(dx, dy)
            }
        }
        key = cur; emittedX = 0f; emittedY = 0f
    }

    /** Luma of a SCREEN-px point from the thumbnail; −1 when outside or under our own floating window. */
    private fun lumaAt(t: IntArray, th: Int, x: Int, y: Int): Int {
        pro.potoki.bekon.agent.ocr.OwnOverlay.pillRect?.let { if (it.contains(x, y)) return -1 }
        if (pro.potoki.bekon.agent.device.touch.StatusPill.ownsPoint(x, y)) return -1
        val tx = (x / sx).toInt(); val ty = (y / sy).toInt()
        if (tx !in 0 until TW || ty !in 0 until th) return -1
        return t[ty * TW + tx]
    }
}
