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

    /** Guidance: the last draw on this screen was hidden by Android (reset when the screen changes). */
    @Volatile var screenHidesOverlay = false

    fun onWindowChanged(key: String) {
        if (!ScreenKey.sameScreen(key, lastWindow)) screenHidesOverlay = false
        lastWindow = key
        if (view == null || !hasMarks() || ScreenKey.sameScreen(key, windowAtShow)) return
        if (System.currentTimeMillis() - shownAt < 1200) { windowAtShow = key; return }
        // OCR saw new lines because the content scrolled under hints we are tracking: same screen.
        if (System.currentTimeMillis() - lastTranslateAt < 2500) { windowAtShow = key; return }
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
                // Captions, scroll arrows and free text go through the single on-screen character.
                val (drawn, cue) = Cue.extract(shapes)
                v.shapes = if (append) v.shapes + drawn else drawn
                v.marksAlpha = 1f
                v.invalidate()
                pro.potoki.bekon.agent.device.touch.StatusPill.setCue(cue, keepOld = append)
                armWatch(v)
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

    /** Label chips currently drawn (text + generous screen rect), so OCR can ignore Bekon's own labels. */
    fun labelBoxes(density: Float): List<Pair<String, android.graphics.RectF>> {
        val shapes = view?.shapes ?: return emptyList()
        val out = ArrayList<Pair<String, android.graphics.RectF>>()
        for (s in shapes.toList()) {
            val label = s.label
            if (label.isNullOrBlank()) continue
            if (!s.lx.isNaN() && !s.ly.isNaN()) {
                val (lw, lh) = FreeSpot.labelSize(label, resourcesWidth())
                out += label to android.graphics.RectF(s.lx - 16f, s.ly - 16f, s.lx + lw + 16f, s.ly + lh + 16f)
                continue
            }
            val (ax, ay) = Sketch.labelAnchor(s)
            val hw = (label.length * 12f * density / 2f + 24f * density).coerceAtMost(220f * density)
            out += label to android.graphics.RectF(ax - hw, ay - 40f * density, ax + hw, ay + 40f * density)
        }
        return out
    }

    /** Framed targets (box/circle/underline) — the app's own text inside them is never "ours". */
    fun targetRects(): List<android.graphics.RectF> = (view?.shapes ?: emptyList()).filter {
        it.kind == Shape.Kind.BOX || it.kind == Shape.Kind.CIRCLE || it.kind == Shape.Kind.UNDERLINE
    }.map { android.graphics.RectF(minOf(it.x1, it.x2), minOf(it.y1, it.y2), maxOf(it.x1, it.x2), maxOf(it.y1, it.y2)) }

    private fun resourcesWidth(): Int = android.content.res.Resources.getSystem().displayMetrics.widthPixels

    fun markBounds(density: Float): List<android.graphics.RectF> {
        val shapes = view?.shapes ?: return emptyList()
        val out = ArrayList<android.graphics.RectF>()
        for (s in shapes) {
            val pad = (if (s.kind == Shape.Kind.SCROLL) 60f else 12f) * density
            out += android.graphics.RectF(minOf(s.x1, s.x2) - pad, minOf(s.y1, s.y2) - pad, maxOf(s.x1, s.x2) + pad, maxOf(s.y1, s.y2) + pad)
            val label = s.label
            if (!label.isNullOrBlank() && !s.lx.isNaN() && !s.ly.isNaN()) {
                val (lw, lh) = FreeSpot.labelSize(label, resourcesWidth())
                out += android.graphics.RectF(s.lx - pad, s.ly - pad, s.lx + lw + pad, s.ly + lh + pad)
            } else if (!label.isNullOrBlank()) {
                val (ax, ay) = Sketch.labelAnchor(s)
                val hw = (label.length * 9f * density / 2f).coerceAtMost(180f * density)
                out += android.graphics.RectF(ax - hw - pad, ay - 48f * density, ax + hw + pad, ay + 48f * density)
            }
        }
        return out
    }

    /**
     * True when Android hides our overlay window although it is attached: some system/secure screens
     * (e.g. MIUI Display settings) set HIDE_NON_SYSTEM_OVERLAY_WINDOWS. Null when no marks are shown.
     */
    fun hiddenBySystem(): Boolean? = windowHidden(view)

    internal fun windowHidden(v: View?): Boolean? {
        if (v == null) return null
        var r: Boolean? = null
        val latch = java.util.concurrent.CountDownLatch(1)
        main.post { try { r = v.isAttachedToWindow && v.windowVisibility != View.VISIBLE } finally { latch.countDown() } }
        latch.await(400, java.util.concurrent.TimeUnit.MILLISECONDS)
        return r
    }

    fun hasMarks(): Boolean = view?.shapes?.isNotEmpty() == true

    // ---- fast removal + scroll tracking (guidance) ----
    private var fading: android.animation.ValueAnimator? = null
    @Volatile var lastTranslateAt = 0L; private set
    @Volatile var lastFadeLatencyMs = -1L; private set

    private fun watchRects(v: MarksView): List<IntArray> {
        val pad = 24
        val r = v.shapes.filter { it.kind != Shape.Kind.GRID }.map {
            intArrayOf((minOf(it.x1, it.x2) - pad).toInt(), (minOf(it.y1, it.y2) - pad).toInt(), (maxOf(it.x1, it.x2) + pad).toInt(), (maxOf(it.y1, it.y2) + pad).toInt())
        }
        if (r.isNotEmpty()) return r
        // Scroll/text cue only: watch the middle of the screen (the character is masked out).
        val dm = android.content.res.Resources.getSystem().displayMetrics
        return if (pro.potoki.bekon.agent.device.touch.StatusPill.cueActive()) listOf(intArrayOf((dm.widthPixels * 0.08f).toInt(), (dm.heightPixels * 0.25f).toInt(), (dm.widthPixels * 0.92f).toInt(), (dm.heightPixels * 0.75f).toInt())) else emptyList()
    }

    private fun armWatch(v: MarksView) {
        if (!OverlayHost.guidance) return
        val rects = watchRects(v)
        if (rects.isEmpty()) { ChangeWatch.disarm(); FrameTracker.enabled = false; return }
        ChangeWatch.onChange = { at -> fadeOutMarks(at) }
        ChangeWatch.arm(rects, resourcesWidth(), System.currentTimeMillis())
        FrameTracker.reset(); FrameTracker.enabled = true
    }

    /** The hinted area changed (not a scroll): fade the hints out in ~180 ms. [at] = frame time. */
    fun fadeOutMarks(at: Long = System.currentTimeMillis()) = main.post {
        val v = view ?: return@post
        pro.potoki.bekon.agent.device.touch.StatusPill.setCue(null)
        if (v.shapes.isEmpty()) { clear(); return@post }
        fading?.cancel()
        fading = android.animation.ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 150
            // Fast-out: most of the ink is gone in the first ~80 ms.
            addUpdateListener { val a = it.animatedValue as Float; v.marksAlpha = a * a * a; v.invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) {
                    lastFadeLatencyMs = System.currentTimeMillis() - at
                    Log.i(TAG, "hints faded: ${lastFadeLatencyMs} ms after the change frame")
                    clear()
                }
            })
            start()
        }
    }

    /** Pure scroll under the hints: move every anchored hint with the content (screen px). */
    fun translateAnchored(dx: Float, dy: Float) = main.post {
        val v = view ?: return@post
        lastTranslateAt = System.currentTimeMillis()
        val dm = resources()
        val sw = dm.widthPixels.toFloat(); val sh = dm.heightPixels.toFloat()
        val moved = v.shapes.map { it.copy(x1 = it.x1 + dx, x2 = it.x2 + dx, y1 = it.y1 + dy, y2 = it.y2 + dy) }
        // A target that left the screen entirely is gone; partly off ones are squashed at the edge.
        val kept = moved.filter { s -> s.kind == Shape.Kind.GRID || (maxOf(s.y1, s.y2) > 0 && minOf(s.y1, s.y2) < sh && maxOf(s.x1, s.x2) > 0 && minOf(s.x1, s.x2) < sw) }
        val lostAll = moved.isNotEmpty() && kept.none { it.kind != Shape.Kind.GRID }
        v.shapes = kept
        v.invalidate()
        pro.potoki.bekon.agent.device.touch.StatusPill.onAnchorShift(dx, dy, targetGone = lostAll)
        if (lostAll && !pro.potoki.bekon.agent.device.touch.StatusPill.cueActive()) { clear(); return@post }
        ChangeWatch.move(watchRects(v), resourcesWidth(), System.currentTimeMillis())
        onMarksChanged?.invoke()
    }

    /** Repaint the frames window (TAP bubble moved/grew: the neck follows). Main thread. */
    fun redrawNow() { view?.invalidate() }


    fun onScrollSettled() = main.post { pro.potoki.bekon.agent.device.touch.StatusPill.onScrollSettled() }

    private fun resources() = (appContext?.resources ?: android.content.res.Resources.getSystem()).displayMetrics

    fun clear() {
        main.post {
            main.removeCallbacks(clearTask)
            ChangeWatch.disarm(); FrameTracker.enabled = false
            pro.potoki.bekon.agent.device.touch.StatusPill.setCue(null)
            val v = view ?: return@post
            fading?.cancel(); fading = null; v.marksAlpha = 1f
            if (frameWanted) {
                // Guidance session with a connected agent: keep the window for the persistent frame.
                if (v.shapes.isNotEmpty()) { v.shapes = emptyList(); v.invalidate() }
            } else {
                try { wm?.removeView(v) } catch (_: Exception) {}
                view = null
            }
            lastSummary = null
            onMarksChanged?.invoke()
        }
    }

    /**
     * Guidance (playStore): the Bekon frame stays on every screen while a session runs with a
     * connected agent (full flavor draws it in the a11y GestureOverlay instead). Touch-through
     * SAW window; marks are drawn into the same window.
     */
    @Volatile var frameWanted = false
        private set

    fun setSessionFrame(ctx: Context, on: Boolean) = main.post {
        if (!OverlayHost.guidance) return@post
        if (on == frameWanted && (!on || view != null)) return@post
        frameWanted = on
        if (on) {
            appContext = ctx.applicationContext
            if (!OverlayHost.canDraw(ctx)) { frameWanted = false; return@post }
            val v = view ?: try { install(ctx.applicationContext) } catch (e: Exception) { Log.e(TAG, "frame install failed", e); null }
            v?.invalidate()
        } else {
            val v = view ?: return@post
            if (v.shapes.isEmpty()) {
                try { wm?.removeView(v) } catch (_: Exception) {}
                view = null
            } else v.invalidate()
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
        var marksAlpha = 1f

        /** Targets partly past the top/bottom edge: clamp to the edge and widen ("smeared on the wall"). */
        private fun squashed(list: List<Shape>, top: Float, bottom: Float): List<Shape> = list.map { s ->
            if (s.kind == Shape.Kind.GRID) return@map s
            val t = minOf(s.y1, s.y2); val b = maxOf(s.y1, s.y2); val h = (b - t).coerceAtLeast(1f)
            if (t >= top && b <= bottom) return@map s
            val nt = t.coerceIn(top, bottom); val nb = b.coerceIn(top, bottom)
            val f = ((nb - nt) / h).coerceIn(0.15f, 1f)
            val ease = 1f - f * f                       // cartoon ease: barely at first, then flattens
            val nh = maxOf(nb - nt, h * 0.22f)
            val ny1 = if (t < top) top else bottom - nh
            val w = kotlin.math.abs(s.x2 - s.x1); val grow = w * 0.18f * ease
            s.copy(x1 = minOf(s.x1, s.x2) - grow, x2 = maxOf(s.x1, s.x2) + grow, y1 = ny1, y2 = ny1 + nh)
        }

        override fun onDraw(canvas: Canvas) {
            val loc = IntArray(2); getLocationOnScreen(loc)
            canvas.save()
            canvas.translate(-loc[0].toFloat(), -loc[1].toFloat())
            val dm = resources.displayMetrics
            val sw = dm.widthPixels.toFloat().coerceAtLeast(width + loc[0].toFloat())
            val sh = dm.heightPixels.toFloat().coerceAtLeast(height + loc[1].toFloat())
            if (shapes.isNotEmpty() || frameWanted) BaconFrame.draw(canvas, sw, sh, dm.density)
            val ins = rootWindowInsets
            val strip = BaconFrame.VISIBLE_DP * dm.density
            val top = maxOf(strip, (ins?.stableInsetTop ?: 0).toFloat(), (ins?.displayCutout?.safeInsetTop ?: 0).toFloat()) + 6f
            val bottom = maxOf(strip, (ins?.stableInsetBottom ?: 0).toFloat()) + 6f
            val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
            val restoreTo = if (marksAlpha < 1f) canvas.saveLayerAlpha(0f, 0f, sw, sh, (marksAlpha * 255).toInt()) else -1
            SketchPainter.draw(
                canvas, squashed(shapes.filter { !it.body }, top, sh - bottom), 1f, dm.density,
                safeTop = top, safeBottom = bottom, safeSide = strip + 4f,
                areaW = sw, areaH = sh, rtl = rtl,
                phase = (System.currentTimeMillis() % 900L) / 900f,
            )
            if (restoreTo >= 0) canvas.restoreToCount(restoreTo)
            // The single character body (pill ↔ ring around the target) — outside the fade layer.
            if (pro.potoki.bekon.agent.device.touch.StatusPill.paintBody(canvas, top, sh - bottom)) postInvalidateOnAnimation()
            canvas.restore()
        }
    }
}
