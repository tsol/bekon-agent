package pro.potoki.bekon.agent.coach

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Coach marks the agent draws to guide a human: boxes, circles, arrows, underlines and
 * text, all in real SCREEN coordinates (same space as snapshot nodes / tap). Pure Kotlin,
 * JVM-testable; Android rendering lives in [SketchPainter].
 */
data class Shape(
    val kind: Kind,
    val x1: Float, val y1: Float,
    val x2: Float, val y2: Float,
    val label: String? = null,
    val color: Int = DEFAULT_COLOR,
    /** Caption chip top-left chosen by [FreeSpot] (screen px); NaN = default placement. */
    val lx: Float = Float.NaN,
    val ly: Float = Float.NaN,
    /** Guidance: this target is wrapped by the character's own body (not painted as a frame). */
    val body: Boolean = false,
) {
    enum class Kind { BOX, CIRCLE, ARROW, UNDERLINE, TEXT, MARK, GRID, SCROLL }
    companion object {
        const val DEFAULT_COLOR = 0xFFFF2D55.toInt() // marker red-pink
    }
}

object Sketch {
    val KINDS = listOf("box", "circle", "arrow", "underline", "text", "mark", "grid", "scroll")

    private val NAMED = mapOf(
        "red" to 0xFFFF2D55.toInt(), "green" to 0xFF22C55E.toInt(), "blue" to 0xFF3B82F6.toInt(),
        "yellow" to 0xFFFACC15.toInt(), "orange" to 0xFFF97316.toInt(), "white" to 0xFFFFFFFF.toInt(),
        "black" to 0xFF000000.toInt(),
    )

    fun parseColor(s: String?): Int {
        if (s.isNullOrBlank()) return Shape.DEFAULT_COLOR
        NAMED[s.lowercase()]?.let { return it }
        val hex = s.removePrefix("#")
        return try {
            when (hex.length) {
                6 -> (0xFF000000 or hex.toLong(16)).toInt()
                8 -> hex.toLong(16).toInt()
                else -> Shape.DEFAULT_COLOR
            }
        } catch (e: NumberFormatException) { Shape.DEFAULT_COLOR }
    }

    /**
     * Parse the `shapes` array. Each item: {type, ...}
     *  - box:       x,y,w,h  or bounds:[l,t,r,b]
     *  - circle:    x,y (centre) + r, or bounds:[l,t,r,b] (ellipse around it)
     *  - arrow:     x1,y1 -> x2,y2 (head at x2,y2)
     *  - underline: x1,y1,x2,y2, or bounds (drawn under the bottom edge)
     *  - text:      x,y + label
     * Every shape takes optional label and color (name or #rrggbb).
     */
    fun parse(arr: JsonArray?): List<Shape> {
        if (arr == null) throw IllegalArgumentException("shapes is required (array)")
        return arr.mapIndexed { i, el ->
            val o = el as? JsonObject ?: throw IllegalArgumentException("shapes[$i] is not an object")
            parseOne(o, i)
        }
    }

    private fun parseOne(o: JsonObject, i: Int): Shape {
        val type = (o.s("type") ?: o.s("kind") ?: "").lowercase()
        val label = o.s("label") ?: o.s("text")
        val color = parseColor(o.s("color"))
        val b = (o["bounds"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content?.toFloatOrNull() }
            ?.takeIf { it.size == 4 }
        fun need(k: String): Float = o.f(k) ?: throw IllegalArgumentException("shapes[$i] ($type) needs $k")
        return when (type) {
            "scroll" -> {
                // Bouncing scroll/swipe cue: x,y = centre (default: screen middle is resolved by caller), dir.
                val dir = (o.s("dir") ?: o.s("direction") ?: "down").lowercase()
                scrollCue(dir, need("x"), need("y"), label, color)
            }
            "box", "rect" -> if (b != null) Shape(Shape.Kind.BOX, b[0], b[1], b[2], b[3], label, color) else {
                val x = need("x"); val y = need("y")
                Shape(Shape.Kind.BOX, x, y, x + need("w"), y + need("h"), label, color)
            }
            "circle", "ellipse" -> if (b != null) Shape(Shape.Kind.CIRCLE, b[0], b[1], b[2], b[3], label, color) else {
                val x = need("x"); val y = need("y"); val r = o.f("r") ?: 80f
                Shape(Shape.Kind.CIRCLE, x - r, y - r, x + r, y + r, label, color)
            }
            "arrow" -> Shape(Shape.Kind.ARROW, need("x1"), need("y1"), need("x2"), need("y2"), label, color)
            "underline" -> if (b != null) Shape(Shape.Kind.UNDERLINE, b[0], b[3] + 8, b[2], b[3] + 8, label, color)
                else Shape(Shape.Kind.UNDERLINE, need("x1"), need("y1"), need("x2"), o.f("y2") ?: need("y1"), label, color)
            "text", "label" -> {
                val x = need("x"); val y = need("y")
                Shape(Shape.Kind.TEXT, x, y, x, y, label ?: throw IllegalArgumentException("shapes[$i] text needs label"), color)
            }
            "mark", "som" -> {
                // Numbered Set-of-Marks badge at centre (x,y) or bounds centre; label = number/text
                if (b != null) Shape(Shape.Kind.MARK, b[0], b[1], b[2], b[3], label ?: "?", color)
                else {
                    val x = need("x"); val y = need("y"); val r = o.f("r") ?: 28f
                    Shape(Shape.Kind.MARK, x - r, y - r, x + r, y + r, label ?: "?", color)
                }
            }
            "grid" -> {
                // Optional coordinate grid over the screen (or bounds). label unused; color = line color.
                if (b != null) Shape(Shape.Kind.GRID, b[0], b[1], b[2], b[3], label, color)
                else Shape(Shape.Kind.GRID, 0f, 0f, need("w"), need("h"), label, color)
            }
            else -> throw IllegalArgumentException("shapes[$i]: unknown type '$type' (use ${KINDS.joinToString()})")
        }
    }

    private fun JsonObject.s(k: String): String? = (this[k] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != "null" }
    private fun JsonObject.f(k: String): Float? = (this[k] as? JsonPrimitive)?.content?.toFloatOrNull()

    // ---------- hand-drawn geometry (polylines, screen space) ----------

    /** Wobbly stroke between two points: slight bow + jitter, like a marker. */
    fun wobblyLine(ax: Float, ay: Float, bx: Float, by: Float, rnd: Random, steps: Int = 16): List<Pair<Float, Float>> {
        val len = hypot(bx - ax, by - ay).coerceAtLeast(1f)
        val nx = -(by - ay) / len; val ny = (bx - ax) / len
        val bow = (rnd.nextFloat() - 0.5f) * min(len * 0.06f, 18f)
        val jit = min(2.5f, len * 0.01f + 0.8f)
        return (0..steps).map { k ->
            val t = k / steps.toFloat()
            val off = bow * sin(PI * t).toFloat() + (rnd.nextFloat() - 0.5f) * jit
            (ax + (bx - ax) * t + nx * off) to (ay + (by - ay) * t + ny * off)
        }
    }

    /** Loose loop around an ellipse, ~1.1 turns with drifting radius (overshoots its start). */
    fun wobblyEllipse(l: Float, t: Float, r: Float, b: Float, rnd: Random, steps: Int = 48): List<Pair<Float, Float>> {
        val cx = (l + r) / 2; val cy = (t + b) / 2
        val rx = max((r - l) / 2, 12f) * 1.12f; val ry = max((b - t) / 2, 12f) * 1.18f
        val start = rnd.nextFloat() * 2 * PI.toFloat()
        val turns = 1.1f + rnd.nextFloat() * 0.08f
        val total = (steps * turns).toInt()
        return (0..total).map { k ->
            val a = start + 2 * PI.toFloat() * k / steps
            val drift = 1f + 0.05f * sin(a * 1.7f + start) + (rnd.nextFloat() - 0.5f) * 0.015f
            val grow = 1f + 0.06f * k / total
            (cx + rx * drift * grow * cos(a)) to (cy + ry * drift * sin(a))
        }
    }

    fun wobblyBox(l: Float, t: Float, r: Float, b: Float, rnd: Random): List<List<Pair<Float, Float>>> {
        val p = 10f
        val o = { (rnd.nextFloat() - 0.5f) * 8f }
        return listOf(
            wobblyLine(l - p + o(), t - p + o(), r + p + o(), t - p + o(), rnd),
            wobblyLine(r + p + o(), t - p + o(), r + p + o(), b + p + o(), rnd),
            wobblyLine(r + p + o(), b + p + o(), l - p + o(), b + p + o(), rnd),
            wobblyLine(l - p + o(), b + p + o(), l - p + o(), t - p - 6 + o(), rnd),
        )
    }

    /** Shaft + two head strokes. */
    fun arrow(ax: Float, ay: Float, bx: Float, by: Float, rnd: Random): List<List<Pair<Float, Float>>> {
        val ang = atan2(by - ay, bx - ax)
        val head = min(60f, max(28f, hypot(bx - ax, by - ay) * 0.22f))
        val spread = 0.5f
        val h1 = (bx - head * cos(ang - spread)) to (by - head * sin(ang - spread))
        val h2 = (bx - head * cos(ang + spread)) to (by - head * sin(ang + spread))
        return listOf(
            wobblyLine(ax, ay, bx, by, rnd),
            wobblyLine(h1.first, h1.second, bx, by, rnd, 6),
            wobblyLine(h2.first, h2.second, bx, by, rnd, 6),
        )
    }

    /**
     * Scroll cue as a SCROLL shape: (x1,y1) = arrow tail, (x2,y2) = arrow head, length ~170 px.
     * dir = where the content to reach is (never the finger movement): down/up/left/right = see what is
     * below/above/to the left/to the right; the arrow points that way (right = next home-screen page).
     */
    fun scrollCue(dir: String, cx: Float, cy: Float, label: String?, color: Int = Shape.DEFAULT_COLOR): Shape {
        val l = 110f
        val (dx, dy) = when (dir) {
            "up" -> 0f to -1f
            "left" -> -1f to 0f
            "right" -> 1f to 0f
            "down" -> 0f to 1f
            else -> throw IllegalArgumentException("scroll dir must be up|down|left|right")
        }
        return Shape(Shape.Kind.SCROLL, cx - dx * l, cy - dy * l, cx + dx * l, cy + dy * l, label, color)
    }

    /** Where to place a shape's label (top-left of the text baseline), screen space. */
    fun labelAnchor(s: Shape): Pair<Float, Float> = if (!s.lx.isNaN() && !s.ly.isNaN()) s.lx to s.ly else when (s.kind) {
        Shape.Kind.ARROW -> s.x1 to s.y1
        Shape.Kind.TEXT -> s.x1 to s.y1
        Shape.Kind.MARK -> ((s.x1 + s.x2) / 2) to ((s.y1 + s.y2) / 2)
        Shape.Kind.GRID -> s.x1 to s.y1
        Shape.Kind.UNDERLINE -> min(s.x1, s.x2) to (max(s.y1, s.y2) + 24)
        else -> min(s.x1, s.x2) to (max(s.y1, s.y2) + 28)
    }

    fun strokes(s: Shape, seed: Int): List<List<Pair<Float, Float>>> {
        val rnd = Random(seed)
        return when (s.kind) {
            Shape.Kind.BOX -> wobblyBox(min(s.x1, s.x2), min(s.y1, s.y2), max(s.x1, s.x2), max(s.y1, s.y2), rnd)
            Shape.Kind.CIRCLE -> listOf(wobblyEllipse(s.x1, s.y1, s.x2, s.y2, rnd))
            Shape.Kind.ARROW -> arrow(s.x1, s.y1, s.x2, s.y2, rnd)
            Shape.Kind.UNDERLINE -> listOf(
                wobblyLine(s.x1, s.y1, s.x2, s.y2, rnd),
                wobblyLine(s.x1 + 6, s.y1 + 9, s.x2 - 4, s.y2 + 7, rnd),
            )
            Shape.Kind.TEXT -> emptyList()
            Shape.Kind.MARK -> emptyList() // filled badge drawn in SketchPainter
            Shape.Kind.GRID -> emptyList()
            Shape.Kind.SCROLL -> emptyList() // animated cue drawn in SketchPainter
        }
    }
}
