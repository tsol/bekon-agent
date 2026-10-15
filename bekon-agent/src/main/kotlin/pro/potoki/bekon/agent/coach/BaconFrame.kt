package pro.potoki.bekon.agent.coach

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.PI
import kotlin.math.sin

/**
 * Branded "frying bacon" frame drawn along all four screen edges while coach / gesture
 * marks are visible. Thin strips only (~30dp); centre content is never covered.
 */
object BaconFrame {
    private val meat = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.rgb(214, 86, 92) }
    private val crisp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
        color = Color.rgb(122, 58, 26)
    }
    private val char = Paint(crisp).apply { color = Color.rgb(78, 34, 14) }
    private val fat = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = Color.argb(235, 255, 236, 220)
    }
    private val sizzle = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.argb(170, 255, 214, 120) }

    /** [phase] in 0..1 animates the wave (sizzle). */
    fun draw(canvas: Canvas, w: Float, h: Float, density: Float, phase: Float = 0f) {
        val t = 30f * density
        crisp.strokeWidth = 3.2f * density
        char.strokeWidth = 1.4f * density
        fat.strokeWidth = 2.6f * density
        // top, bottom (horizontal); left, right (vertical, inset by t to avoid corner overlap)
        strip(canvas, 0f, 0f, w, true, t, density, phase, 1)
        strip(canvas, 0f, h - t, w, true, t, density, phase, 2)
        strip(canvas, 0f, t, h - 2 * t, false, t, density, phase, 3)
        strip(canvas, w - t, t, h - 2 * t, false, t, density, phase, 4)
    }

    private fun strip(c: Canvas, x0: Float, y0: Float, len: Float, horiz: Boolean, t: Float, d: Float, phase: Float, seed: Int) {
        if (len <= 0f) return
        val amp = t * 0.14f
        val wl = 46f * d
        val steps = (len / (4f * d)).toInt().coerceAtLeast(8)
        fun wave(s: Float, off: Float) =
            amp * sin(2 * PI * (s / wl + phase + off)).toFloat() + amp * 0.4f * sin(2 * PI * (s / (wl * 0.37f) + seed)).toFloat()
        fun pt(s: Float, across: Float): Pair<Float, Float> =
            if (horiz) (x0 + s) to (y0 + across) else (x0 + across) to (y0 + s)
        val top = Path(); val bot = Path()
        val outline = Path()
        for (i in 0..steps) {
            val s = len * i / steps
            val (ax, ay) = pt(s, t * 0.18f + wave(s, 0f))
            if (i == 0) { outline.moveTo(ax, ay); top.moveTo(ax, ay) } else { outline.lineTo(ax, ay); top.lineTo(ax, ay) }
        }
        for (i in steps downTo 0) {
            val s = len * i / steps
            val (bx, by) = pt(s, t * 0.82f + wave(s, 0.25f))
            outline.lineTo(bx, by)
            if (i == steps) bot.moveTo(bx, by) else bot.lineTo(bx, by)
        }
        outline.close()
        c.drawPath(outline, meat)
        // fat streaks along the middle
        for ((k, frac) in listOf(0.40f, 0.62f).withIndex()) {
            val f = Path()
            for (i in 0..steps) {
                val s = len * i / steps
                val (fx, fy) = pt(s, t * frac + wave(s, 0.1f + k * 0.2f) * 0.8f)
                if (i == 0) f.moveTo(fx, fy) else f.lineTo(fx, fy)
            }
            c.drawPath(f, fat)
        }
        c.drawPath(top, crisp); c.drawPath(bot, crisp)
        c.drawPath(top, char); c.drawPath(bot, char)
        // sizzle bubbles
        var s = (seed * 37 % 50) * d
        while (s < len) {
            val (px, py) = pt(s, t * 0.5f + wave(s, 0.5f))
            c.drawCircle(px, py, 1.6f * d, sizzle)
            s += (70 + (seed * 13 + s.toInt()) % 40) * d
        }
    }
}
