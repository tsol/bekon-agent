package pro.potoki.bekon.agent.coach

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/** Draws [Shape]s onto a Canvas in marker style. [k] maps screen coords to canvas pixels. */
object SketchPainter {
    fun draw(
        canvas: Canvas, shapes: List<Shape>, k: Float, density: Float = 2.6f,
        safeTop: Float = 4f, safeBottom: Float = 4f, safeSide: Float = 4f,
        areaW: Float = canvas.width.toFloat(), areaH: Float = canvas.height.toFloat(),
    ) {
        val strokeW = (9f * k).coerceAtLeast(3f)
        val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
            strokeWidth = strokeW.coerceAtLeast(3f)
        }
        val halo = Paint(pen).apply { color = 0x99FFFFFF.toInt(); strokeWidth = pen.strokeWidth + 5f }
        shapes.forEachIndexed { i, s ->
            pen.color = s.color
            for (stroke in Sketch.strokes(s, seed = 7919 * (i + 1) + s.kind.ordinal)) {
                if (stroke.size < 2) continue
                val path = Path()
                path.moveTo(stroke[0].first * k, stroke[0].second * k)
                for (j in 1 until stroke.size) path.lineTo(stroke[j].first * k, stroke[j].second * k)
                canvas.drawPath(path, halo)
                canvas.drawPath(path, pen)
            }
            val label = s.label
            if (!label.isNullOrBlank()) {
                val (ax, ay) = Sketch.labelAnchor(s)
                drawLabel(canvas, ax * k, ay * k, label, s.color, k, areaW, areaH, safeTop, safeBottom, safeSide)
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
        val r = (tp.textSize + pad * 2) / 2.4f
        c.drawRoundRect(RectF(left, top, left + w, top + h), r, r, bg)
        lines.forEachIndexed { i, ln -> c.drawText(ln, left + pad, top + pad + tp.textSize * 0.85f + i * lineH, tp) }
    }

    private fun wrap(text: String, p: Paint, maxW: Float): List<String> {
        val out = mutableListOf<String>()
        var cur = ""
        for (word in text.split(' ')) {
            val cand = if (cur.isEmpty()) word else "$cur $word"
            if (p.measureText(cand) <= maxW || cur.isEmpty()) {
                if (p.measureText(cand) > maxW) { // single very long word: hard-break
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
