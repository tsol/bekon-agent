package pro.potoki.bekon.agent.coach

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The character is ONE body: an outer contour and a hole contour, each [N] points uniformly spaced
 * by arc length. Every state is produced by a target-contour generator (shape params → dense
 * polyline → [resample] to N points → [align] start point/orientation to the current contour), and
 * the points travel there with a per-vertex spring ([BodySpring]). Pure Kotlin (FloatArray x,y…),
 * screen coordinates, y down; generators emit points in screen-clockwise order.
 */
object BodyShapes {
    const val N = 192

    // ---------------- generators (dense polylines) ----------------

    fun roundRect(l: Float, t: Float, r: Float, b: Float, rad: Float, seg: Int = 12): FloatArray {
        val rr = min(rad, min(r - l, b - t) / 2f).coerceAtLeast(0f)
        val out = ArrayList<Float>(4 * (seg + 1) * 2)
        val cs = arrayOf(floatArrayOf(r - rr, b - rr, 0f), floatArrayOf(l + rr, b - rr, 90f), floatArrayOf(l + rr, t + rr, 180f), floatArrayOf(r - rr, t + rr, 270f))
        for (c in cs) for (k in 0..seg) {
            val a = Math.toRadians((c[2] + 90.0 * k / seg))
            out += c[0] + (rr * cos(a)).toFloat(); out += c[1] + (rr * sin(a)).toFloat()
        }
        return out.toFloatArray()
    }

    fun pill(l: Float, t: Float, r: Float, b: Float) = roundRect(l, t, r, b, (b - t) / 2f)

    fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float, n: Int = 72): FloatArray {
        val o = FloatArray(2 * n)
        for (i in 0 until n) { val a = 2 * Math.PI * i / n; o[2 * i] = cx + (rx * cos(a)).toFloat(); o[2 * i + 1] = cy + (ry * sin(a)).toFloat() }
        return o
    }

    /** Teardrop: a circle of radius [r] whose rim is pulled into a soft point toward [angle] (rad). */
    fun drop(cx: Float, cy: Float, r: Float, angle: Double, n: Int = 120): FloatArray {
        val o = FloatArray(2 * n)
        for (i in 0 until n) {
            val a = 2 * Math.PI * i / n
            val k = max(0.0, cos(a - angle))
            val rr = r * (1 + 0.85 * Math.pow(k, 6.0))
            o[2 * i] = cx + (rr * cos(a)).toFloat(); o[2 * i + 1] = cy + (rr * sin(a)).toFloat()
        }
        return o
    }

    /**
     * Arrow: a shaft (rounded rect l,t,r,b — the text lives here) plus a head of length [head] on
     * the [dir] side ("up","down","left","right","back"). Left/right heads are wider than the shaft
     * (a real arrow); up/down heads sit centred on the long edge; "back" = left with a swallowtail.
     */
    const val BACK_NOTCH = 0.35f
    /** Horizontal text inset at a pill's round end so the text corner stays [clear] from the arc. */
    fun pillPadH(h: Float, padV: Float, padH: Float, clear: Float): Float {
        val rr = h / 2; val dy = rr - padV; val q = (rr - clear) * (rr - clear) - dy * dy
        return if (q <= 0f) maxOf(padH, rr) else maxOf(padH, rr - kotlin.math.sqrt(q))
    }
    fun arrow(l: Float, t: Float, r: Float, b: Float, dir: String, head: Float, seg: Int = 10): FloatArray {
        val h = b - t; val w = r - l; val cx = (l + r) / 2; val cy = (t + b) / 2
        val rr = min(h, w) / 2f * 0.9f
        val o = ArrayList<Float>()
        fun p(x: Float, y: Float) { o += x; o += y }
        fun arc(ccx: Float, ccy: Float, a0: Double) { for (k in 0..seg) { val a = Math.toRadians(a0 + 90.0 * k / seg); p(ccx + (rr * cos(a)).toFloat(), ccy + (rr * sin(a)).toFloat()) } }
        when (dir) {
            "right" -> {
                val bw = h / 2 + head * 0.55f
                p(r + head, cy); p(r, cy + bw); p(r, b)
                arc(l + rr, b - rr, 90.0); arc(l + rr, t + rr, 180.0)
                p(r, t); p(r, cy - bw)
            }
            "left" -> {
                val bw = h / 2 + head * 0.55f
                p(l - head, cy); p(l, cy - bw); p(l, t)
                arc(r - rr, t + rr, 270.0); arc(r - rr, b - rr, 0.0)
                p(l, b); p(l, cy + bw)
            }
            "back" -> {
                val bw = h / 2 + head * 0.55f
                p(l - head, cy); p(l, cy - bw); p(l, t); p(r, t); p(r - h * BACK_NOTCH, cy); p(r, b); p(l, b); p(l, cy + bw)
            }
            "up" -> {
                val bw = min(w / 2 - rr, h * 0.8f).coerceAtLeast(4f)
                arc(r - rr, b - rr, 0.0); arc(l + rr, b - rr, 90.0); arc(l + rr, t + rr, 180.0)
                p(cx - bw, t); p(cx, t - head); p(cx + bw, t)
                arc(r - rr, t + rr, 270.0)
            }
            else -> { // down
                val bw = min(w / 2 - rr, h * 0.8f).coerceAtLeast(4f)
                arc(r - rr, b - rr, 0.0); p(cx + bw, b); p(cx, b + head); p(cx - bw, b)
                arc(l + rr, b - rr, 90.0); arc(l + rr, t + rr, 180.0); arc(r - rr, t + rr, 270.0)
            }
        }
        return o.toFloatArray()
    }

    /**
     * Ring around a target joined to a caption pocket by a tapered tail — ONE simple contour:
     * ring long arc → tail side → pocket long arc → other tail side. [ring] = outer ring rect,
     * [pocket] = caption rect; [w0] half-width at the ring, [wm] at the pinch, [w1] at the pocket.
     * Returns the contour and the tail axis (x0,y0,x1,y1) for the fade gradient, or the bare ring
     * when the pocket overlaps it.
     */
    fun ringPocket(ring: FloatArray, rr: Float, pocket: FloatArray, kr: Float, w0: Float, wm: Float, w1: Float): Pair<FloatArray, FloatArray?> {
        val (rl, rt, rrt, rb) = ring.let { listOf(it[0], it[1], it[2], it[3]) }
        val (kl, kt, krt, kb) = pocket.let { listOf(it[0], it[1], it[2], it[3]) }
        val ringLoop = densify(roundRect(rl, rt, rrt, rb, rr), 2f)
        if (kl < rrt && krt > rl && kt < rb && kb > rt) return ringLoop to null
        val pocketLoop = densify(roundRect(kl, kt, krt, kb, kr), 2f)
        val rcx = (rl + rrt) / 2; val rcy = (rt + rb) / 2; val kcx = (kl + krt) / 2; val kcy = (kt + kb) / 2
        val vertical = abs(kcy - rcy) * (rrt - rl) >= abs(kcx - rcx) * (rb - rt) || (kt >= rb || kb <= rt) && !(kl >= rrt || krt <= rl)
        val a1: FloatArray; val a2: FloatArray; val am: FloatArray; val p1: FloatArray; val p2: FloatArray; val pm: FloatArray
        if (vertical) {
            val down = kcy > rcy
            val ey = if (down) rb else rt; val ky = if (down) kt else kb
            val x0 = clampMid(kcx, rl + rr + w0, rrt - rr - w0)
            val x1 = clampMid(x0, kl + kr + w1, krt - kr - w1)
            a1 = floatArrayOf(x0 - w0, ey); a2 = floatArrayOf(x0 + w0, ey); am = floatArrayOf(x0, ey)
            p1 = floatArrayOf(x1 - w1, ky); p2 = floatArrayOf(x1 + w1, ky); pm = floatArrayOf(x1, ky)
        } else {
            val right = kcx > rcx
            val ex = if (right) rrt else rl; val kx = if (right) kl else krt
            val y0 = clampMid(kcy, rt + rr + w0, rb - rr - w0)
            val y1 = clampMid(y0, kt + kr + w1, kb - kr - w1)
            a1 = floatArrayOf(ex, y0 - w0); a2 = floatArrayOf(ex, y0 + w0); am = floatArrayOf(ex, y0)
            p1 = floatArrayOf(kx, y1 - w1); p2 = floatArrayOf(kx, y1 + w1); pm = floatArrayOf(kx, y1)
        }
        val i1 = nearest(ringLoop, a1); val i2 = nearest(ringLoop, a2); val im = nearest(ringLoop, am)
        val j1 = nearest(pocketLoop, p1); val j2 = nearest(pocketLoop, p2); val jm = nearest(pocketLoop, pm)
        val out = ArrayList<Float>()
        // Ring long arc, ending on side "e" (1 or 2).
        val n = ringLoop.size / 2
        val (rs, re, endSide) = if (within(n, i1, i2, im)) Triple(i2, i1, 1) else Triple(i1, i2, 2)
        walk(ringLoop, rs, re, +1, out)
        val pStart = if (endSide == 1) j1 else j2; val pEnd = if (endSide == 1) j2 else j1
        val ringEnd = if (endSide == 1) a1 else a2; val ringStart = if (endSide == 1) a2 else a1
        val pocketStart = if (endSide == 1) p1 else p2; val pocketEnd = if (endSide == 1) p2 else p1
        // Tail side 1: ring → pocket, pinched toward the axis in the middle.
        tail(ringEnd, pocketStart, am, pm, wm, w0, w1, out)
        val m = pocketLoop.size / 2
        val dirP = if (within(m, pStart, pEnd, jm)) -1 else +1
        walk(pocketLoop, pStart, pEnd, dirP, out)
        tail(pocketEnd, ringStart, pm, am, wm, w1, w0, out)
        return out.toFloatArray() to floatArrayOf(am[0], am[1], pm[0], pm[1])
    }

    private fun clampMid(v: Float, lo: Float, hi: Float) = if (hi < lo) (lo + hi) / 2 else v.coerceIn(lo, hi)
    private fun nearest(p: FloatArray, q: FloatArray): Int {
        var bi = 0; var bd = Float.MAX_VALUE
        for (i in 0 until p.size / 2) { val dx = p[2 * i] - q[0]; val dy = p[2 * i + 1] - q[1]; val d = dx * dx + dy * dy; if (d < bd) { bd = d; bi = i } }
        return bi
    }
    /** Is [m] on the increasing walk from [a] to [b] (mod n)? */
    private fun within(n: Int, a: Int, b: Int, m: Int): Boolean { val ab = (b - a + n) % n; val am = (m - a + n) % n; return am <= ab }
    private fun walk(p: FloatArray, from: Int, to: Int, step: Int, out: ArrayList<Float>) {
        val n = p.size / 2; var i = from
        while (true) { out += p[2 * i]; out += p[2 * i + 1]; if (i == to) break; i = (i + step + n) % n }
    }
    /** Cubic tail edge from a→b; the controls sit [wm] from the axis (a0→b0) so the middle pinches. */
    private fun tail(a: FloatArray, b: FloatArray, a0: FloatArray, b0: FloatArray, wm: Float, wa: Float, wb: Float, out: ArrayList<Float>) {
        val sa = if (wa > 0) wm / wa else 0f; val sb = if (wb > 0) wm / wb else 0f
        val c1x = a0[0] + (a[0] - a0[0]) * sa + (b0[0] - a0[0]) * 0.35f; val c1y = a0[1] + (a[1] - a0[1]) * sa + (b0[1] - a0[1]) * 0.35f
        val c2x = b0[0] + (b[0] - b0[0]) * sb + (a0[0] - b0[0]) * 0.35f; val c2y = b0[1] + (b[1] - b0[1]) * sb + (a0[1] - b0[1]) * 0.35f
        for (k in 1 until 16) {
            val t = k / 16f; val u = 1 - t
            out += u * u * u * a[0] + 3 * u * u * t * c1x + 3 * u * t * t * c2x + t * t * t * b[0]
            out += u * u * u * a[1] + 3 * u * u * t * c1y + 3 * u * t * t * c2y + t * t * t * b[1]
        }
    }

    // ---------------- resampling & correspondence ----------------

    fun densify(p: FloatArray, step: Float): FloatArray {
        val o = ArrayList<Float>(); val n = p.size / 2
        for (i in 0 until n) {
            val x0 = p[2 * i]; val y0 = p[2 * i + 1]; val x1 = p[2 * ((i + 1) % n)]; val y1 = p[2 * ((i + 1) % n) + 1]
            val k = max(1, (hypot(x1 - x0, y1 - y0) / step).toInt())
            for (j in 0 until k) { o += x0 + (x1 - x0) * j / k; o += y0 + (y1 - y0) * j / k }
        }
        return o.toFloatArray()
    }

    /** Uniform arc-length resampling of a closed polyline to [n] points. */
    fun resample(p: FloatArray, n: Int = N): FloatArray {
        val m = p.size / 2
        val cum = FloatArray(m + 1)
        for (i in 0 until m) { val j = (i + 1) % m; cum[i + 1] = cum[i] + hypot(p[2 * j] - p[2 * i], p[2 * j + 1] - p[2 * i + 1]) }
        val total = cum[m]; val o = FloatArray(2 * n)
        if (total <= 0f) { for (i in 0 until n) { o[2 * i] = p[0]; o[2 * i + 1] = p[1] }; return o }
        var seg = 0
        for (k in 0 until n) {
            val s = total * k / n
            while (seg < m - 1 && cum[seg + 1] < s) seg++
            val j = (seg + 1) % m; val len = cum[seg + 1] - cum[seg]
            val f = if (len > 0) (s - cum[seg]) / len else 0f
            o[2 * k] = p[2 * seg] + (p[2 * j] - p[2 * seg]) * f; o[2 * k + 1] = p[2 * seg + 1] + (p[2 * j + 1] - p[2 * seg + 1]) * f
        }
        return o
    }

    fun signedArea(p: FloatArray): Float {
        var a = 0f; val n = p.size / 2
        for (i in 0 until n) { val j = (i + 1) % n; a += p[2 * i] * p[2 * j + 1] - p[2 * j] * p[2 * i + 1] }
        return a / 2
    }

    /** Same orientation as [cur] and the cyclic start that best matches it (least squared travel). */
    fun align(cur: FloatArray, tgt: FloatArray): FloatArray {
        val n = tgt.size / 2
        var t = tgt
        if ((signedArea(cur) >= 0) != (signedArea(t) >= 0)) { val r = FloatArray(t.size); for (i in 0 until n) { r[2 * i] = t[2 * (n - 1 - i)]; r[2 * i + 1] = t[2 * (n - 1 - i) + 1] }; t = r }
        var best = 0; var bd = Double.MAX_VALUE
        var s = 0
        while (s < n) {
            var d = 0.0; var i = 0
            while (i < n) { val j = (i + s) % n; val dx = (t[2 * j] - cur[2 * i]).toDouble(); val dy = (t[2 * j + 1] - cur[2 * i + 1]).toDouble(); d += dx * dx + dy * dy; i += 4 }
            if (d < bd) { bd = d; best = s }
            s += 2
        }
        val o = FloatArray(t.size)
        for (i in 0 until n) { val j = (i + best) % n; o[2 * i] = t[2 * j]; o[2 * i + 1] = t[2 * j + 1] }
        return o
    }

    fun bounds(p: FloatArray): FloatArray {
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        for (i in 0 until p.size / 2) { l = min(l, p[2 * i]); r = max(r, p[2 * i]); t = min(t, p[2 * i + 1]); b = max(b, p[2 * i + 1]) }
        return floatArrayOf(l, t, r, b)
    }

    /** Any two non-adjacent edges properly crossing? (tests: generated contours must be simple). */
    fun selfIntersects(p: FloatArray): Boolean {
        val n = p.size / 2
        fun orient(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float): Int {
            val v = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)
            return if (v > 1e-2f) 1 else if (v < -1e-2f) -1 else 0
        }
        for (i in 0 until n) for (j in i + 2 until n) {
            if (i == 0 && j == n - 1) continue
            val a = i; val b = (i + 1) % n; val c = j; val d = (j + 1) % n
            val ax = p[2 * a]; val ay = p[2 * a + 1]; val bx = p[2 * b]; val by = p[2 * b + 1]
            val cx = p[2 * c]; val cy = p[2 * c + 1]; val dx = p[2 * d]; val dy = p[2 * d + 1]
            val o1 = orient(ax, ay, bx, by, cx, cy); val o2 = orient(ax, ay, bx, by, dx, dy)
            val o3 = orient(cx, cy, dx, dy, ax, ay); val o4 = orient(cx, cy, dx, dy, bx, by)
            if (o1 * o2 < 0 && o3 * o4 < 0) return true
        }
        return false
    }
    /** Smallest distance from the rect [l,t,r,b] (text bounds) to the contour; < 0 when the rect pokes out. */
    fun clearance(c: FloatArray, l: Float, t: Float, r: Float, b: Float): Float {
        val n = c.size / 2
        var best = Float.MAX_VALUE
        for (i in 0 until n) {
            val x0 = c[2 * i]; val y0 = c[2 * i + 1]; val j = (i + 1) % n; val x1 = c[2 * j]; val y1 = c[2 * j + 1]
            // distance between segment and rect ≈ sample the segment
            for (k in 0..4) {
                val x = x0 + (x1 - x0) * k / 4f; val y = y0 + (y1 - y0) * k / 4f
                val dx = maxOf(l - x, 0f, x - r); val dy = maxOf(t - y, 0f, y - b)
                val inside = x in l..r && y in t..b
                val dd = if (inside) -minOf(x - l, r - x, y - t, b - y) else kotlin.math.hypot(dx, dy)
                if (dd < best) best = dd
            }
        }
        return best
    }
}

/**
 * Per-vertex spring toward target contours (outer + hole), with a stagger that releases vertices
 * progressively from a lead vertex, so the body flows into a new shape instead of snapping.
 */
class BodySpring(val n: Int = BodyShapes.N) {
    val outer = FloatArray(2 * n); val hole = FloatArray(2 * n)
    private val vo = FloatArray(2 * n); private val vh = FloatArray(2 * n)
    private var to = FloatArray(2 * n); private var th = FloatArray(2 * n)
    private val relO = LongArray(n); private val relH = LongArray(n)
    var ready = false; private set
    var speed = 0f; private set          // mean |v| px/s (drives wobble)

    fun setTargets(o: FloatArray, h: FloatArray, now: Long, stagger: Boolean, staggerMs: Long = 90) {
        if (!ready) { o.copyInto(outer); h.copyInto(hole); o.copyInto(to); h.copyInto(th); ready = true; return }
        to = BodyShapes.align(outer, o); th = BodyShapes.align(hole, h)
        if (stagger) {
            // Lead = the current vertex nearest the new shape's centre: that side moves first.
            val b = BodyShapes.bounds(to); val cx = (b[0] + b[2]) / 2; val cy = (b[1] + b[3]) / 2
            var lead = 0; var bd = Float.MAX_VALUE
            for (i in 0 until n) { val dx = outer[2 * i] - cx; val dy = outer[2 * i + 1] - cy; val d = dx * dx + dy * dy; if (d < bd) { bd = d; lead = i } }
            for (i in 0 until n) {
                val dist = minOf((i - lead + n) % n, (lead - i + n) % n).toFloat() / (n / 2f)
                relO[i] = now + (staggerMs * dist).toLong(); relH[i] = now + (staggerMs * 0.6f * dist).toLong()
            }
        }
    }

    fun translate(dx: Float, dy: Float) {
        for (i in 0 until n) { outer[2 * i] += dx; outer[2 * i + 1] += dy; hole[2 * i] += dx; hole[2 * i + 1] += dy
            to[2 * i] += dx; to[2 * i + 1] += dy; th[2 * i] += dx; th[2 * i + 1] += dy }
    }

    /** Advance by [dtMs]; returns true while still moving. k/zeta: slightly under-damped (a little jiggle). */
    fun step(dtMs: Float, now: Long, k: Float = 260f, zeta: Float = 0.62f): Boolean {
        if (!ready) return false
        val dt = (dtMs.coerceIn(0f, 34f) / 1000f) / 2f
        val c = 2f * zeta * sqrt(k)
        var e = 0f; var sp = 0f
        repeat(2) {
            for (i in 0 until n) {
                for (pass in 0..1) {
                    val p = if (pass == 0) outer else hole; val v = if (pass == 0) vo else vh; val t = if (pass == 0) to else th
                    val rel = if (pass == 0) relO[i] else relH[i]
                    if (now < rel) continue
                    for (a in 0..1) {
                        val q = 2 * i + a
                        val acc = k * (t[q] - p[q]) - c * v[q]
                        v[q] += acc * dt; p[q] += v[q] * dt
                        e += abs(t[q] - p[q]) + abs(v[q]) * 0.02f; sp += abs(v[q])
                    }
                }
            }
        }
        speed = sp / (8f * n)
        val moving = e / (8f * n) > 0.05f || relO.any { it > now }
        if (!moving) { to.copyInto(outer); th.copyInto(hole); vo.fill(0f); vh.fill(0f); speed = 0f }
        return moving
    }

}
