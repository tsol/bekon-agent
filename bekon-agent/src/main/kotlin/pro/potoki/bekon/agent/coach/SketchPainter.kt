package pro.potoki.bekon.agent.coach

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface

/** Draws [Shape]s onto a Canvas in marker style. [k] maps screen coords to canvas pixels. */
object SketchPainter {
    fun draw(
        canvas: Canvas, shapes: List<Shape>, k: Float, density: Float = 2.6f,
        safeTop: Float = 4f, safeBottom: Float = 4f, safeSide: Float = 4f,
        areaW: Float = canvas.width.toFloat(), areaH: Float = canvas.height.toFloat(),
        rtl: Boolean = false,
    ) {
        val strokeW = (9f * k).coerceAtLeast(3f)
        val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
            strokeWidth = strokeW.coerceAtLeast(3f)
        }
        // Light + dark halo so marks stay readable on both wallpaper extremes.
        val haloLight = Paint(pen).apply { color = 0xAAFFFFFF.toInt(); strokeWidth = pen.strokeWidth + 6f }
        val haloDark = Paint(pen).apply { color = 0x88000000.toInt(); strokeWidth = pen.strokeWidth + 3f }
        shapes.forEachIndexed { i, s ->
            when (s.kind) {
                Shape.Kind.GRID -> drawGrid(canvas, s, k, density)
                Shape.Kind.MARK -> drawMark(canvas, s, k, density)
                else -> {
                    pen.color = s.color
                    for (stroke in Sketch.strokes(s, seed = 7919 * (i + 1) + s.kind.ordinal)) {
                        if (stroke.size < 2) continue
                        val path = Path()
                        path.moveTo(stroke[0].first * k, stroke[0].second * k)
                        for (j in 1 until stroke.size) path.lineTo(stroke[j].first * k, stroke[j].second * k)
                        canvas.drawPath(path, haloLight)
                        canvas.drawPath(path, haloDark)
                        canvas.drawPath(path, pen)
                    }
                    val label = s.label
                    if (!label.isNullOrBlank() && s.kind != Shape.Kind.MARK) {
                        val (ax, ay) = Sketch.labelAnchor(s)
                        val lx = if (rtl) areaW - ax * k else ax * k
                        drawLabel(canvas, lx, ay * k, label, s.color, k, areaW, areaH, safeTop, safeBottom, safeSide)
                    }
                }
            }
        }
    }

    private fun drawMark(c: Canvas, s: Shape, k: Float, density: Float) {
        val cx = ((s.x1 + s.x2) / 2f) * k
        val cy = ((s.y1 + s.y2) / 2f) * k
        val r = (kotlin.math.max(kotlin.math.abs(s.x2 - s.x1), kotlin.math.abs(s.y2 - s.y1)) / 2f * k)
            .coerceAtLeast(18f * density * k / density) // ~18dp min
            .coerceAtLeast(22f * k)
        // Soft pulse ring
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = (s.color and 0x00FFFFFF) or 0x66000000.toInt()
            strokeWidth = 4f * k
        }
        c.drawCircle(cx, cy, r * 1.35f, ring)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = s.color }
        val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = Color.WHITE; strokeWidth = 3f * k
        }
        c.drawCircle(cx, cy, r, fill)
        c.drawCircle(cx, cy, r, edge)
        val text = (s.label ?: "?").take(3)
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
            textSize = (r * 1.1f).coerceIn(18f * k, 40f * k)
        }
        val fm = tp.fontMetrics
        c.drawText(text, cx, cy - (fm.ascent + fm.descent) / 2f, tp)
    }

    private fun drawGrid(c: Canvas, s: Shape, k: Float, density: Float) {
        val l = minOf(s.x1, s.x2) * k
        val t = minOf(s.y1, s.y2) * k
        val r = maxOf(s.x1, s.x2) * k
        val b = maxOf(s.y1, s.y2) * k
        val cols = (s.label?.toIntOrNull()?.coerceIn(4, 12)) ?: 6
        val rows = cols
        val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = (s.color and 0x00FFFFFF) or 0x55000000.toInt()
            strokeWidth = 1.5f * k
        }
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = (s.color and 0x00FFFFFF) or 0xAA000000.toInt()
            textSize = 11f * density * (k / 1f).coerceAtLeast(0.5f)
        }
        val dw = (r - l) / cols
        val dh = (b - t) / rows
        for (i in 0..cols) {
            val x = l + i * dw
            c.drawLine(x, t, x, b, pen)
        }
        for (j in 0..rows) {
            val y = t + j * dh
            c.drawLine(l, y, r, y, pen)
        }
        // Cell ids like A1
        for (j in 0 until rows) {
            for (i in 0 until cols) {
                val id = "${('A' + i)}${j + 1}"
                c.drawText(id, l + i * dw + 4f * k, t + j * dh + labelPaint.textSize + 2f * k, labelPaint)
            }
        }
    }

    private fun drawLabel(
        c: Canvas, x: Float, y: Float, text: String, color: Int, k: Float, cw: Float, ch: Float,
        safeTop: Float, safeBottom: Float, safeSide: Float,
    ) {
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.WHITE
            textSize = (46f * k).coerceAtLeast(16f)
            isFakeBoldText = true
        }
        val pad = tp.textSize * 0.35f
        val maxTextW = (cw - 2 * safeSide - pad * 2).coerceAtLeast(tp.textSize * 4)
        val lines = wrap(text, tp, maxTextW)
        val lineH = tp.textSize * 1.15f
        val w = (lines.maxOf { tp.measureText(it) }) + pad * 2
        val h = lineH * lines.size + pad * 2 - (lineH - tp.textSize)
        val left = x.coerceIn(safeSide, (cw - w - safeSide).coerceAtLeast(safeSide))
        val top = y.coerceIn(safeTop, (ch - h - safeBottom).coerceAtLeast(safeTop))
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; this.color = 0xCC000000.toInt(); strokeWidth = 2f * k
        }
        val rr = (tp.textSize + pad * 2) / 2.4f
        val rect = RectF(left, top, left + w, top + h)
        c.drawRoundRect(rect, rr, rr, bg)
        c.drawRoundRect(rect, rr, rr, outline)
        lines.forEachIndexed { i, ln -> c.drawText(ln, left + pad, top + pad + tp.textSize * 0.85f + i * lineH, tp) }
    }

    private fun wrap(text: String, p: Paint, maxW: Float): List<String> {
        val out = mutableListOf<String>()
        var cur = ""
        for (word in text.split(' ')) {
            val cand = if (cur.isEmpty()) word else "$cur $word"
            if (p.measureText(cand) <= maxW || cur.isEmpty()) {
                if (p.measureText(cand) > maxW) {
                    var rest = cand
                    while (p.measureText(rest) > maxW && rest.length > 1) {
                        val n = p.breakText(rest, true, maxW, null).coerceAtLeast(1)
                        out += rest.substring(0, n); rest = rest.substring(n)
                    }
                    cur = rest
                } else cur = cand
            } else { out += cur; cur = word }
        }
        if (cur.isNotEmpty()) out += cur
        return out.take(4)
    }
}
