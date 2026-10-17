package pro.potoki.bekon.agent.coach

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager

/**
 * Live coach marks on the phone screen via a TYPE_ACCESSIBILITY_OVERLAY window owned by the
 * accessibility service (no SYSTEM_ALERT_WINDOW needed). Not touchable: the human taps through.
 */
object CoachOverlay {
    private const val TAG = "CoachOverlay"
    private val main = Handler(Looper.getMainLooper())
    private var view: MarksView? = null
    private var wm: WindowManager? = null
    private val clearTask = Runnable { clear() }
    @Volatile private var shownAt = 0L
    @Volatile private var windowAtShow: String? = null
    @Volatile private var lastWindow: String? = null

    /**
     * Marks are drawn in screen coordinates for one specific screen. When the human (or the
     * agent) moves to another screen they point at the wrong thing, so drop them. A short
     * grace period ignores the transition that is still settling when the marks are drawn.
     */
    fun onWindowChanged(key: String) {
        lastWindow = key
        if (view == null || key == windowAtShow) return
        if (System.currentTimeMillis() - shownAt < 1200) { windowAtShow = key; return }
        Log.i(TAG, "screen changed ($windowAtShow -> $key): clearing stale marks")
        clear()
    }

    /** Blocks briefly on the main thread. Returns false when the a11y service is not running. */
    fun show(svc: AccessibilityService?, shapes: List<Shape>, append: Boolean, ttlMs: Long): Boolean {
        if (svc == null) return false
        var ok = false
        val latch = java.util.concurrent.CountDownLatch(1)
        main.post {
            try {
                val v = view ?: install(svc)
                v.shapes = if (append) v.shapes + shapes else shapes
                v.invalidate()
                onMarksChanged?.invoke()
                main.removeCallbacks(clearTask)
                shownAt = System.currentTimeMillis(); windowAtShow = lastWindow
                if (ttlMs > 0) main.postDelayed(clearTask, ttlMs)
                ok = true
            } catch (e: Exception) {
                Log.e(TAG, "show failed", e)
            } finally { latch.countDown() }
        }
        latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
        return ok
    }

    /** Called (main thread) whenever marks appear, change or go away: the status pill re-lays out. */
    @Volatile var onMarksChanged: (() -> Unit)? = null

    /** Screen-space areas covered by marks and their label bubbles (estimated), for the status pill to avoid. */
    fun markBounds(density: Float): List<android.graphics.RectF> {
        val shapes = view?.shapes ?: return emptyList()
        val out = ArrayList<android.graphics.RectF>()
        for (s in shapes) {
            val pad = 12f * density
            out += android.graphics.RectF(minOf(s.x1, s.x2) - pad, minOf(s.y1, s.y2) - pad, maxOf(s.x1, s.x2) + pad, maxOf(s.y1, s.y2) + pad)
            val label = s.label
            if (!label.isNullOrBlank()) {
                val (ax, ay) = Sketch.labelAnchor(s)
                val hw = (label.length * 9f * density / 2f).coerceAtMost(180f * density)
                out += android.graphics.RectF(ax - hw - pad, ay - 48f * density, ax + hw + pad, ay + 48f * density)
            }
        }
        return out
    }

    fun clear() {
        main.post {
            main.removeCallbacks(clearTask)
            val v = view ?: return@post
            try { wm?.removeView(v) } catch (_: Exception) {}
            view = null
            onMarksChanged?.invoke()
        }
    }

    private fun install(svc: AccessibilityService): MarksView {
        val w = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val v = MarksView(svc)
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        if (Build.VERSION.SDK_INT >= 28) {
            p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        w.addView(v, p)
        wm = w; view = v
        return v
    }

    private class MarksView(ctx: Context) : View(ctx) {
        var shapes: List<Shape> = emptyList()
        override fun onDraw(canvas: Canvas) {
            // Shapes are in real-screen coords; compensate if the window does not start at 0,0.
            val loc = IntArray(2); getLocationOnScreen(loc)
            canvas.save()
            canvas.translate(-loc[0].toFloat(), -loc[1].toFloat())
            val dm = resources.displayMetrics
            val sw = dm.widthPixels.toFloat().coerceAtLeast(width + loc[0].toFloat())
            val sh = dm.heightPixels.toFloat().coerceAtLeast(height + loc[1].toFloat())
            if (shapes.isNotEmpty()) BaconFrame.draw(canvas, sw, sh, dm.density)
            val ins = rootWindowInsets
            val strip = BaconFrame.VISIBLE_DP * dm.density
            val top = maxOf(strip, (ins?.stableInsetTop ?: 0).toFloat(), (ins?.displayCutout?.safeInsetTop ?: 0).toFloat()) + 6f
            val bottom = maxOf(strip, (ins?.stableInsetBottom ?: 0).toFloat()) + 6f
            SketchPainter.draw(canvas, shapes, 1f, dm.density, safeTop = top, safeBottom = bottom, safeSide = strip + 4f, areaW = sw, areaH = sh)
            canvas.restore()
        }
    }
}
