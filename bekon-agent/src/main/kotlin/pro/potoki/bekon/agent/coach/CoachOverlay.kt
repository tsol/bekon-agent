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
import pro.potoki.bekon.agent.overlay.OverlayHost

/**
 * Live coach marks on the phone screen.
 * - full: TYPE_ACCESSIBILITY_OVERLAY owned by the accessibility service
 * - guidance (playStore): TYPE_APPLICATION_OVERLAY (SYSTEM_ALERT_WINDOW)
 * Not touchable: the human taps through.
 */
object CoachOverlay {
    private const val TAG = "CoachOverlay"
    private val main = Handler(Looper.getMainLooper())
    private var view: MarksView? = null
    private var wm: WindowManager? = null
    private var appContext: Context? = null
    private val clearTask = Runnable { clear() }
    @Volatile private var shownAt = 0L
    @Volatile private var windowAtShow: String? = null
    @Volatile private var lastWindow: String? = null
    @Volatile private var lastSummary: String? = null

    fun bindAppContext(ctx: Context) {
        appContext = ctx.applicationContext
    }

    fun onWindowChanged(key: String) {
        lastWindow = key
        if (view == null || key == windowAtShow) return
        if (System.currentTimeMillis() - shownAt < 1200) { windowAtShow = key; return }
        Log.i(TAG, "screen changed ($windowAtShow -> $key): clearing stale marks")
        clear()
    }

    /** Called when NavJournal detects a fingerprint change (guidance mode). */
    fun onScreenFingerprintChanged(fp: String) = onWindowChanged("fp:$fp")

    /**
     * @param svc accessibility service in full flavor; ignored in guidance (uses [appContext]).
     */
    fun show(svc: AccessibilityService?, shapes: List<Shape>, append: Boolean, ttlMs: Long): Boolean {
        val ctx: Context? = when {
            OverlayHost.guidance -> appContext
            else -> svc
        }
        if (ctx == null) return false
        if (OverlayHost.guidance && !OverlayHost.canDraw(ctx)) return false
        var ok = false
        val latch = java.util.concurrent.CountDownLatch(1)
        main.post {
            try {
                val v = view ?: install(ctx)
                v.shapes = if (append) v.shapes + shapes else shapes
                v.invalidate()
                lastSummary = shapes.joinToString("; ") { "${it.kind}:${it.label ?: ""}" }.take(120)
                try { pro.potoki.bekon.agent.nav.NavJournal.noteHint(lastSummary) } catch (_: Exception) {}
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

    @Volatile var onMarksChanged: (() -> Unit)? = null

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
            lastSummary = null
            onMarksChanged?.invoke()
        }
    }

    private fun install(ctx: Context): MarksView {
        val w = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val v = MarksView(ctx)
        val p = OverlayHost.passThroughParams()
        w.addView(v, p)
        wm = w; view = v
        return v
    }

    private class MarksView(ctx: Context) : View(ctx) {
        var shapes: List<Shape> = emptyList()
        override fun onDraw(canvas: Canvas) {
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
            val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
            SketchPainter.draw(
                canvas, shapes, 1f, dm.density,
                safeTop = top, safeBottom = bottom, safeSide = strip + 4f,
                areaW = sw, areaH = sh, rtl = rtl,
            )
            canvas.restore()
        }
    }
}
