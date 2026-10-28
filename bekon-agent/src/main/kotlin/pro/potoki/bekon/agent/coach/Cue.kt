package pro.potoki.bekon.agent.coach

/**
 * What the on-screen character says/does for the current hint. Everything we draw except the frames
 * of specific targets goes through the single character: captions, scroll arrows, free text.
 */
data class Cue(
    val kind: Kind,
    val text: String?,
    /** For SCROLL: "up" | "down" | "left" | "right" — the way the arrow points. */
    val dir: String? = null,
    /** Screen px (l,t,r,b) of what the cue refers to: the framed target (TAP) or the arrow (SCROLL). */
    val anchor: FloatArray? = null,
    /** Colour of the framed target (TAP): the caption bubble is the same body. */
    val color: Int = Shape.DEFAULT_COLOR,
) {
    enum class Kind { TAP, SCROLL, TEXT }

    fun shifted(dx: Float, dy: Float) = copy(anchor = anchor?.let { floatArrayOf(it[0] + dx, it[1] + dy, it[2] + dx, it[3] + dy) })

    companion object {
        private fun rect(s: Shape) = floatArrayOf(minOf(s.x1, s.x2), minOf(s.y1, s.y2), maxOf(s.x1, s.x2), maxOf(s.y1, s.y2))

        /**
         * Split shapes into what the frames window draws (targets, without captions) and the cue the
         * character shows. Scroll cues and free text become the character; target captions too.
         */
        fun extract(shapes: List<Shape>): Pair<List<Shape>, Cue?> {
            var cue: Cue? = null
            val drawn = ArrayList<Shape>()
            for (s in shapes) when (s.kind) {
                Shape.Kind.SCROLL -> {
                    val dx = s.x2 - s.x1; val dy = s.y2 - s.y1
                    val dir = if (kotlin.math.abs(dx) > kotlin.math.abs(dy)) (if (dx < 0) "left" else "right") else (if (dy < 0) "up" else "down")
                    if (cue == null || cue.kind != Kind.SCROLL) cue = Cue(Kind.SCROLL, s.label, dir, rect(s))
                }
                Shape.Kind.TEXT -> { if (cue == null) cue = Cue(Kind.TEXT, s.label, anchor = rect(s)) }
                Shape.Kind.BOX, Shape.Kind.CIRCLE, Shape.Kind.UNDERLINE, Shape.Kind.ARROW -> {
                    val becomesBody = !s.label.isNullOrBlank() && (cue == null || cue.kind == Kind.TEXT)
                    if (becomesBody) cue = Cue(Kind.TAP, s.label, anchor = rect(s), color = s.color)
                    drawn += s.copy(label = null, lx = Float.NaN, ly = Float.NaN, body = becomesBody)
                }
                else -> drawn += s          // MARK (numbered target badges), GRID
            }
            return drawn to cue
        }
    }
}
