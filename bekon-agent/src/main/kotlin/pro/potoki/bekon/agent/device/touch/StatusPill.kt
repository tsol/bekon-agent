package pro.potoki.bekon.agent.device.touch

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.launch
import pro.potoki.bekon.agent.R
import pro.potoki.bekon.agent.status.AgentStatus
import pro.potoki.bekon.agent.status.Capabilities
import pro.potoki.bekon.agent.status.AgentStatus.State as StatusState

/**
 * agent_status pill in its OWN small touchable window: only the pill takes touches, the rest of the
 * screen (and the coach marks) stays touch-through. Tap opens the pill menu, drag moves it,
 * fling/drag off the side hides it behind a small edge tab (tap the tab to restore; a new
 * agent_status also restores it). Auto-placement avoids coach marks/labels and clickable nodes.
 */
object StatusPill {
    private val main = Handler(Looper.getMainLooper())
    private var host: AccessibilityService? = null
    private var wm: WindowManager? = null
    private var view: PillView? = null
    private var params: WindowManager.LayoutParams? = null
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob())
    private val jobs = ArrayList<kotlinx.coroutines.Job>()

    private var dismissed = false
    private var manual: Pair<Int, Int>? = null
    private var lastStatus: AgentStatus.Status? = null
    private val tick = object : Runnable {
        override fun run() { refresh(); main.postDelayed(this, 2000) }
    }

    fun attach(svc: AccessibilityService) {
        host = svc
        jobs.forEach { it.cancel() }; jobs.clear()
        jobs.add(scope.launch { AgentStatus.current.collect { st ->
            if (st !== lastStatus && st != null) dismissed = false
            lastStatus = st; refresh()
        } })
        jobs.add(scope.launch { pro.potoki.bekon.agent.access.AgentConnections.connected.collect { refresh() } })
        jobs.add(scope.launch { pro.potoki.bekon.agent.session.SessionService.state.collect {
            if (it.overlaysMustClear) { manual = null; dismissed = false }
            refresh()
        } })
        pro.potoki.bekon.agent.coach.CoachOverlay.onMarksChanged = { relayout() }
        main.removeCallbacks(tick); main.postDelayed(tick, 2000)
    }

    fun detach() {
        jobs.forEach { it.cancel() }; jobs.clear()
        pro.potoki.bekon.agent.coach.CoachOverlay.onMarksChanged = null
        main.removeCallbacks(tick)
        main.post { remove() }
        host = null
    }

    private fun shouldShow(): Boolean {
        val s = pro.potoki.bekon.agent.session.SessionService.state.value
        if (!s.isRunning || s.overlaysMustClear) return false
        if (pro.potoki.bekon.agent.status.PillMenuActivity.open) return false
        // Bekon's own screens already show the state; the pill would cover our cards.
        if (ownForeground()) return false
        return AgentStatus.current.value != null || pro.potoki.bekon.agent.access.AgentConnections.connected.value.isNotEmpty()
    }

    fun refresh() = main.post {
        if (!shouldShow()) { remove(); return@post }
        val v = view ?: install() ?: return@post
        v.update(AgentStatus.current.value, Capabilities.current(), dismissed)
        relayoutNow()
    }

    fun relayout() = main.post { relayoutNow() }

    private fun ownForeground(): Boolean = try {
        val pkg = TouchService.active?.rootInActiveWindow?.packageName?.toString()
        pkg != null && pkg == host?.packageName
    } catch (_: Exception) { false }

    /** Keyboard window, if shown: the pill must stay above it and inside the screen. */
    private fun imeRect(): Rect? = try {
        host?.windows?.firstOrNull { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            ?.let { w -> Rect().also { w.getBoundsInScreen(it) } }?.takeIf { !it.isEmpty }
    } catch (_: Exception) { null }

    private fun remove() {
        val v = view ?: return
        try { wm?.removeView(v) } catch (_: Exception) {}
        view = null; params = null
    }

    private fun install(): PillView? {
        val svc = host ?: return null
        return try {
            val w = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val v = PillView(svc)
            val p = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            )
            p.gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= 28) p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            w.addView(v, p)
            wm = w; view = v; params = p
            v
        } catch (e: Exception) { null }
    }

    private fun screen(): Rect {
        val svc = host!!
        val dm = svc.resources.displayMetrics
        var top = (24 * dm.density).toInt(); var bottom = (48 * dm.density).toInt()
        if (Build.VERSION.SDK_INT >= 30) try {
            val m = (svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager).currentWindowMetrics
            val ins = m.windowInsets.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
            top = ins.top; bottom = ins.bottom
            return Rect(0, top, m.bounds.width(), m.bounds.height() - bottom)
        } catch (_: Exception) {}
        return Rect(0, top, dm.widthPixels, dm.heightPixels - bottom)
    }

    /** Areas the pill must never cover: coach marks + their labels, the coach banner target. */
    private fun avoid(d: Float): List<RectF> = buildList {
        addAll(pro.potoki.bekon.agent.coach.CoachOverlay.markBounds(d))
        GestureOverlay.coachBounds()?.let { add(RectF(it)) }
    }

    /** Clickable app nodes (not ours, not SystemUI) — covering fewer of them is better. */
    private fun clickables(): List<Rect> {
        val root = try { TouchService.active?.rootInActiveWindow } catch (_: Exception) { null } ?: return emptyList()
        val own = host?.packageName
        if (root.packageName?.toString() == own) return emptyList()
        val out = ArrayList<Rect>(); val stack = ArrayDeque<AccessibilityNodeInfo>(); stack.add(root)
        var n = 0
        while (stack.isNotEmpty() && n < 500) {
            val x = stack.removeLast(); n++
            if (x.isClickable && x.isVisibleToUser) { val r = Rect(); x.getBoundsInScreen(r); out += r }
            for (i in 0 until x.childCount) x.getChild(i)?.let { stack.add(it) }
        }
        return out
    }

    private fun relayoutNow() {
        val v = view ?: return; val p = params ?: return
        if (host == null) return
        val d = v.resources.displayMetrics.density
        val sc = screen()
        val ime = imeRect()
        if (ime != null && ime.top > sc.top + 100) sc.bottom = minOf(sc.bottom, ime.top)
        val (w, h) = v.measureNow()
        val (nx, ny) = if (dismissed) {
            (sc.right - w) to (sc.top + (sc.height() * 0.45f).toInt())
        } else {
            val bad = avoid(d) + listOfNotNull(ime?.let { RectF(it) })
            fun hits(x: Int, y: Int) = bad.any { RectF.intersects(it, RectF(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat())) }
            val m = manual
            if (m != null && !hits(m.first, m.second)) m else {
                val cx = (sc.width() - w) / 2
                val gap = (10 * d).toInt()
                val cands = listOf(
                    cx to sc.bottom - h - gap, cx to sc.top + gap,
                    cx to (sc.top + sc.height() * 0.72f).toInt(), cx to (sc.top + sc.height() * 0.22f).toInt(),
                )
                val cl = clickables()
                cands.minBy { (x, y) ->
                    val r = Rect(x, y, x + w, y + h)
                    (if (hits(x, y)) 10_000 else 0) + cl.count { Rect.intersects(it, r) } * 10 + cands.indexOf(x to y)
                }
            }
        }
        // Always clamp fully inside the usable area (above the keyboard). The old second pass re-applied the
        // unclamped position on the next tick, which pushed the pill past the right edge with the IME open.
        val cx2 = if (dismissed) nx else nx.coerceIn(sc.left, (sc.right - w).coerceAtLeast(sc.left))
        val cy2 = ny.coerceIn(sc.top, (sc.bottom - h).coerceAtLeast(sc.top))
        if (manual != null && !dismissed) manual = cx2 to cy2
        if (p.x != cx2 || p.y != cy2) {
            p.x = cx2; p.y = cy2
            try { wm?.updateViewLayout(v, p) } catch (_: Exception) {}
        }
    }

    private fun openMenu(ctx: Context) {
        try {
            ctx.startActivity(Intent(ctx, pro.potoki.bekon.agent.status.PillMenuActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        } catch (_: Exception) {}
    }

    @SuppressLint("ViewConstructor")
    private class PillView(ctx: Context) : View(ctx) {
        private var st: AgentStatus.Status? = null
        private var caps: Capabilities.Caps? = null
        private var tab = false
        private val d = resources.displayMetrics.density
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.argb(200, 255, 255, 255); strokeWidth = 1.5f * resources.displayMetrics.density }
        private val t1 = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = android.graphics.Typeface.DEFAULT_BOLD; textSize = 13f * resources.displayMetrics.density }
        private val t2 = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(235, 255, 255, 255); textSize = 10.5f * resources.displayMetrics.density }
        private var line1 = ""; private var line2 = ""
        private val pad = 14f * d

        fun update(s: AgentStatus.Status?, c: Capabilities.Caps, dismissed: Boolean) {
            st = s; caps = c; tab = dismissed
            val labelRes = when (s?.state) {
                StatusState.WORKING -> R.string.status_working; StatusState.WAITING -> R.string.status_waiting
                StatusState.DONE -> R.string.status_done; StatusState.ERROR -> R.string.status_error; null -> 0
            }
            line1 = when {
                s == null -> context.getString(R.string.cap_agent, c.agent ?: "")
                s.res != 0 -> context.getString(s.res)
                s.state == StatusState.DONE -> s.text
                else -> context.getString(labelRes) + ": " + s.text
            }
            line2 = Capabilities.line(context, c, s?.step, s?.total)
            bg.color = when (s?.state) {
                StatusState.WORKING -> Color.argb(235, 33, 82, 160); StatusState.WAITING -> Color.argb(240, 214, 120, 0)
                StatusState.DONE -> Color.argb(240, 30, 130, 60); StatusState.ERROR -> Color.argb(240, 190, 40, 40)
                null -> Color.argb(225, 45, 45, 45)
            }
            if (s != null && s.res != 0) bg.color = Color.argb(235, 90, 90, 96)
            requestLayout(); invalidate()
        }

        private var layout1: android.text.StaticLayout? = null

        /** Up to 92% of the screen width; line 1 wraps to 3 lines, line 2 stays one line. */
        fun measureNow(): Pair<Int, Int> {
            if (tab) return (22 * d).toInt() to (64 * d).toInt()
            val maxText = (resources.displayMetrics.widthPixels * 0.92f - 2 * pad).toInt().coerceAtLeast((80 * d).toInt())
            val want = maxOf(t1.measureText(line1), t2.measureText(line2)).toInt() + 1
            val tw = want.coerceAtMost(maxText)
            val tp = android.text.TextPaint(t1)
            layout1 = android.text.StaticLayout.Builder.obtain(line1, 0, line1.length, tp, tw)
                .setMaxLines(3).setEllipsize(android.text.TextUtils.TruncateAt.END).setIncludePad(false).build()
            val h1 = layout1!!.height
            val h = (8 * d + h1 + 4 * d + t2.textSize + 10 * d).toInt()
            return (tw + 2 * pad).toInt() to h
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val (w, h) = measureNow(); setMeasuredDimension(w, h)
        }

        override fun onDraw(canvas: Canvas) {
            val r = RectF(0f, 0f, width.toFloat(), height.toFloat())
            if (tab) {
                canvas.drawRoundRect(r, 10f * d, 10f * d, bg)
                canvas.drawText("‹", r.centerX() - t1.measureText("‹") / 2, r.centerY() + 5 * d, t1)
                return
            }
            val rad = minOf(height / 2f, 21f * d)
            canvas.drawRoundRect(r, rad, rad, bg)
            canvas.drawRoundRect(r, rad, rad, edge)
            val avail = width - 2 * pad
            val l1 = layout1 ?: run { measureNow(); layout1!! }
            canvas.save(); canvas.translate(pad, 8f * d); l1.draw(canvas); canvas.restore()
            val sub = android.text.TextUtils.ellipsize(line2, android.text.TextPaint(t2), avail, android.text.TextUtils.TruncateAt.END).toString()
            canvas.drawText(sub, pad, 8f * d + l1.height + 4f * d + t2.textSize * 0.85f, t2)
        }

        private var downX = 0f; private var downY = 0f; private var startX = 0; private var startY = 0
        private var dragging = false
        private var vt: VelocityTracker? = null
        private val slop = ViewConfiguration.get(ctx).scaledTouchSlop

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            val p = params ?: return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = p.x; startY = p.y; dragging = false
                    vt?.recycle(); vt = VelocityTracker.obtain(); vt?.addMovement(e)
                }
                MotionEvent.ACTION_MOVE -> {
                    vt?.addMovement(e)
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!dragging && (Math.abs(dx) > slop || Math.abs(dy) > slop) && !tab) dragging = true
                    if (dragging) {
                        p.x = (startX + dx).toInt(); p.y = (startY + dy).toInt()
                        try { wm?.updateViewLayout(this, p) } catch (_: Exception) {}
                    }
                }
                MotionEvent.ACTION_UP -> {
                    vt?.addMovement(e); vt?.computeCurrentVelocity(1000)
                    val vx = vt?.xVelocity ?: 0f
                    vt?.recycle(); vt = null
                    val sw = resources.displayMetrics.widthPixels
                    when {
                        tab -> { dismissed = false; update(st, caps ?: Capabilities.current(), false); relayoutNow() }
                        !dragging -> openMenu(context)
                        Math.abs(vx) > 2500 * d / 2.6f || p.x + width * 0.35f < 0 || p.x + width * 0.65f > sw -> {
                            dismissed = true; update(st, caps ?: Capabilities.current(), true); relayoutNow()
                        }
                        else -> {
                            // Keep a dragged pill fully on screen (fling-off is handled above).
                            val sh = resources.displayMetrics.heightPixels
                            p.x = p.x.coerceIn(0, (sw - width).coerceAtLeast(0))
                            p.y = p.y.coerceIn(0, (sh - height).coerceAtLeast(0))
                            try { wm?.updateViewLayout(this, p) } catch (_: Exception) {}
                            manual = p.x to p.y
                        }
                    }
                    dragging = false
                }
                MotionEvent.ACTION_CANCEL -> { dragging = false; vt?.recycle(); vt = null }
            }
            return true
        }
    }
}
