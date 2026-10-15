package pro.potoki.bekon.agent.coach

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/** Draws [Shape]s onto a Canvas in marker style. [k] maps screen coords to canvas pixels. */
object SketchPainter {
    fun draw(canvas: Canvas, shapes: List<Shape>, k: Float, density: Float = 2.6f) {
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
                drawLabel(canvas, ax * k, ay * k, label, s.color, k, canvas.width.toFloat(), canvas.height.toFloat())
            }
        }
    }

    private fun drawLabel(c: Canvas, x: Float, y: Float, text: String, color: Int, k: Float, cw: Float, ch: Float) {
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.WHITE
            textSize = (46f * k).coerceAtLeast(16f)
            isFakeBoldText = true
        }
        val pad = tp.textSize * 0.35f
        val w = tp.measureText(text) + pad * 2
        val h = tp.textSize + pad * 2
        var left = x.coerceIn(4f, (cw - w - 4f).coerceAtLeast(4f))
        var top = y.coerceIn(4f, (ch - h - 4f).coerceAtLeast(4f))
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        c.drawRoundRect(RectF(left, top, left + w, top + h), h / 2.4f, h / 2.4f, bg)
        c.drawText(text, left + pad, top + pad + tp.textSize * 0.85f, tp)
    }
}
