package pro.potoki.bekon.agent.coach

/**
 * Hysteresis for floating elements (status pill): decides WHEN a free-spot search may run and WHETHER
 * its result may move the element. Owner bug: the search ran on every frame/tick and the pill jittered
 * back and forth (its own pixels changed the grid each time it moved).
 *  - recompute only when the screen content really changed: coarse luma signature with our own
 *    overlays masked out, compared with the signature at the last placement;
 *  - only after the screen settled ([settleMs] without motion) and no touch for [touchQuietMs];
 *  - stay put unless the current spot became occupied or a clearly better spot exists ([gain]);
 *  - at most one move per [minMoveIntervalMs] (an occupied spot may move sooner).
 * Pure logic; the caller animates one move at a time.
 */
class PlacementGovernor(
    val settleMs: Long = 700,
    val minMoveIntervalMs: Long = 2_000,
    val touchQuietMs: Long = 1_000,
    val diffThresh: Float = 8f,
    val gain: Float = 0.25f,
) {
    private var prevSig: IntArray? = null
    private var placedSig: IntArray? = null
    private var lastMotionAt = Long.MIN_VALUE / 2
    private var lastTouchAt = Long.MIN_VALUE / 2
    private var lastMoveAt = Long.MIN_VALUE / 2
    private var forced = true
    var moves = 0; private set

    /** Masked cells are < 0 and ignored. */
    fun onSignature(sig: IntArray, now: Long) {
        val p = prevSig
        if (p != null && diff(sig, p) > diffThresh / 2) lastMotionAt = now
        prevSig = sig
    }

    fun force() { forced = true }
    fun touched(now: Long) { lastTouchAt = now }

    private fun dirty(): Boolean {
        val s = prevSig ?: return forced
        val a = placedSig ?: return true
        return forced || diff(s, a) > diffThresh
    }

    fun shouldRecompute(now: Long): Boolean =
        dirty() && now - lastMotionAt >= settleMs && now - lastTouchAt >= touchQuietMs

    /**
     * After a search: [here] = cost of the current spot, [best] = best spot's cost.
     * Returns true when the element should move (caller then animates once and calls [moved]).
     */
    fun decide(now: Long, here: Float, best: Float, occupied: Boolean, firstPlacement: Boolean = false): Boolean {
        val move = when {
            firstPlacement -> true
            occupied -> now - lastMoveAt >= 300
            now - lastMoveAt < minMoveIntervalMs -> return false   // keep dirty: retry after the cap
            else -> best + gain < here
        }
        placedSig = prevSig; forced = false
        return move
    }

    fun moved(now: Long) { lastMoveAt = now; moves++ }

    companion object {
        fun diff(a: IntArray, b: IntArray): Float {
            if (a.size != b.size) return Float.MAX_VALUE
            var s = 0L; var n = 0
            for (i in a.indices) { if (a[i] < 0 || b[i] < 0) continue; s += kotlin.math.abs(a[i] - b[i]); n++ }
            return if (n == 0) 0f else s.toFloat() / n
        }
    }
}
