package pro.potoki.bekon.agent.ocr

import android.content.res.Resources
import android.graphics.Rect

/**
 * Bekon's own on-screen windows (status pill, coach label chips) are part of the MediaProjection
 * frame. Drop their text from OCR so it never pollutes find_text / som_marks / fingerprints.
 */
object OwnOverlay {
    /** Current status-pill window rect in screen pixels, or null when hidden. Set by StatusPill. */
    @Volatile var pillRect: Rect? = null
    /** The pill's current lines: matched by text anywhere (the pill animates/moves between frames). */
    @Volatile var pillLines: List<String> = emptyList()

    /** Lowercase letters/digits only: OCR turns “curly” quotes into "straight" ones, drops dashes, etc. */
    internal fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    fun isOwn(b: ScreenOcr.Block, pillInFrame: Boolean = true): Boolean {
        val cx = (b.bounds[0] + b.bounds[2]) / 2
        val cy = (b.bounds[1] + b.bounds[3]) / 2
        if (pillInFrame) pillRect?.let { if (it.contains(cx, cy) || overlapsMostly(b.bounds, it)) return true }
        val labels = try {
            pro.potoki.bekon.agent.coach.CoachOverlay.labelBoxes(Resources.getSystem().displayMetrics.density)
        } catch (_: Exception) { emptyList() }
        val t = norm(b.text)
        if (t.isEmpty()) return false
        // The highlighted control itself (inside the drawn frame) is app text, not our caption.
        val inTarget = try { pro.potoki.bekon.agent.coach.CoachOverlay.targetRects().any { it.contains(cx.toFloat(), cy.toFloat()) } } catch (_: Exception) { false }
        if (inTarget) return false
        for (line in pillLines) {
            val p = norm(line)
            if (p.length >= 6 && (p == t || (p.contains(t) && t.length * 10 >= p.length * 6) || (t.contains(p) && p.length * 10 >= t.length * 6) || tokenMatch(p, t))) return true
        }
        for ((label, r) in labels) {
            val l = norm(label)
            if (l.isNotEmpty() && (l == t || t.contains(l) || (l.contains(t) && t.length * 10 >= l.length * 6) || tokenMatch(l, t)) && r.contains(cx.toFloat(), cy.toFloat())) return true
        }
        return false
    }

    /**
     * OCR can merge the pill's text with an app label next to it into one block
     * ("Insta Your turn: Find Music"), whose centre then falls outside the pill. Treat a block
     * that is a quarter or more covered by the pill as ours.
     */
    internal fun overlapsMostly(b: IntArray, r: Rect): Boolean {
        val w = minOf(b[2], r.right) - maxOf(b[0], r.left)
        val h = minOf(b[3], r.bottom) - maxOf(b[1], r.top)
        if (w <= 0 || h <= 0) return false
        val area = (b[2] - b[0]).toLong() * (b[3] - b[1])
        return area > 0 && w.toLong() * h * 4 >= area
    }

    /** OCR of our caption with a misread word or two ("Your tum: tap Gallery"). */
    internal fun tokenMatch(label: String, text: String): Boolean {
        val lt = label.split(' ').filter { it.isNotEmpty() }; val tt = text.split(' ').filter { it.isNotEmpty() }
        if (lt.size < 3 || tt.isEmpty() || tt.size > lt.size + 1) return false
        val hit = tt.count { it in lt }
        return hit * 10 >= lt.size * 6
    }

    fun filter(blocks: List<ScreenOcr.Block>, pillInFrame: Boolean = true): List<ScreenOcr.Block> =
        blocks.filterNot { isOwn(it, pillInFrame) }
}
