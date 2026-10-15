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
                main.removeCallbacks(clearTask)
                if (ttlMs > 0) main.postDelayed(clearTask, ttlMs)
                ok = true
            } catch (e: Exception) {
                Log.e(TAG, "show failed", e)
            } finally { latch.countDown() }
        }
        latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
        return ok
    }

    fun clear() {
        main.post {
            main.removeCallbacks(clearTask)
            val v = view ?: return@post
            try { wm?.removeView(v) } catch (_: Exception) {}
            view = null
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
            SketchPainter.draw(canvas, shapes, 1f, resources.displayMetrics.density)
            canvas.restore()
        }
    }
}
