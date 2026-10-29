package pro.potoki.bekon.agent.device.touch

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import kotlinx.coroutines.launch
import pro.potoki.bekon.agent.R
import pro.potoki.bekon.agent.coach.Cue
import pro.potoki.bekon.agent.coach.FreeSpot
import pro.potoki.bekon.agent.coach.PlacementGovernor
import pro.potoki.bekon.agent.status.AgentStatus
import pro.potoki.bekon.agent.status.Capabilities
import pro.potoki.bekon.agent.status.AgentStatus.State as StatusState

/**
 * The single on-screen character (replaces the old status chip/pill, 1.0.34).
 *
 * One small touchable window, shown the whole time an agent is connected to a running session —
 * there is no hidden/dismissed state; only its shape, size, colour, position and caption change:
 *  - idle: compact blob with one eye + agent name and status, parked once in a free spot;
 *  - working: blue, orbiting dots; waiting / your turn: amber; done: green; error: red;
 *  - TAP cue: sits right next to the framed target, points at it, caption "Your turn: tap …";
 *  - SCROLL cue: morphs into a bouncing arrow (up/down/left/right) with the caption.
 * Everything we draw except the frames around specific targets goes through it. Tap → menu
 * (Next step / Stop …), drag → move. Placement: [FreeSpot] + [PlacementGovernor] hysteresis.
 * Removed only when the session ends or the agent disconnects.
 */
object StatusPill {
    private val main = Handler(Looper.getMainLooper())
    private var host: Context? = null
    private var wm: WindowManager? = null
    private var view: CharView? = null
    private var params: WindowManager.LayoutParams? = null
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob())
    private val jobs = ArrayList<kotlinx.coroutines.Job>()

    private var manual: Pair<Int, Int>? = null
    private var cue: Cue? = null
    private var cueDirty = false
    private var lastKey = ""
    private val gov = PlacementGovernor()
    private val tick = object : Runnable {
        override fun run() { refresh(); main.postDelayed(this, 2000) }
    }

    fun attach(ctx: Context) {
        host = ctx.applicationContext
        jobs.forEach { it.cancel() }; jobs.clear()
        jobs.add(scope.launch { AgentStatus.current.collect { refresh() } })
        jobs.add(scope.launch { pro.potoki.bekon.agent.access.AgentConnections.connected.collect { refresh() } })
        jobs.add(scope.launch { pro.potoki.bekon.agent.session.SessionService.state.collect {
            if (it.overlaysMustClear) manual = null
            refresh()
        } })
        pro.potoki.bekon.agent.coach.CoachOverlay.onMarksChanged = { gov.force(); relayout() }
        FreeSpot.onUpdate = { relayout() }
        FreeSpot.onSignature = { sig, at -> main.post { gov.onSignature(sig, at) } }
        FreeSpot.maskRects = { ownMask() }
        main.removeCallbacks(tick); main.postDelayed(tick, 2000)
    }

    /** The character is never hidden for OCR: its window rect is masked out instead (OwnOverlay). */
    fun hideForCapture(): Boolean = false
    fun restoreAfterCapture() {}

    /** Attached but hidden by Android (screen blocks non-system overlays); null when not shown. */
    fun hiddenBySystem(): Boolean? = pro.potoki.bekon.agent.coach.CoachOverlay.windowHidden(view)

    fun detach() {
        jobs.forEach { it.cancel() }; jobs.clear()
        pro.potoki.bekon.agent.coach.CoachOverlay.onMarksChanged = null
        FreeSpot.onUpdate = null; FreeSpot.onSignature = null; FreeSpot.maskRects = null
        main.removeCallbacks(tick)
        host?.let { h -> pro.potoki.bekon.agent.coach.CoachOverlay.setSessionFrame(h, false) }
        main.post { remove() }
        host = null
    }

    private fun onMain(r: () -> Unit) { if (Looper.myLooper() == Looper.getMainLooper()) r() else main.post(r) }

    // ---- cues from CoachOverlay ----

    /** New hint for the character ([c] null = hint over). [keepOld]: appended draw without a cue. */
    fun setCue(c: Cue?, keepOld: Boolean = false) = onMain {
        if (c == null && keepOld) return@onMain
        if (c == cue) return@onMain
        cue = c
        if (c != null) manual = null
        cueDirty = true; gov.force()
        view?.let { it.update(AgentStatus.current.value, Capabilities.current(), cue); relayoutNow() }
    }

    fun cueActive(): Boolean = cue != null

    /** Guidance: paint the body into the frames window canvas (screen coords). True = keep animating. */
    fun paintBody(canvas: Canvas, safeTop: Float, safeBottom: Float): Boolean {
        val v = view ?: return false; val p = params ?: return false
        if (!pro.potoki.bekon.agent.overlay.OverlayHost.guidance || v.width == 0) return false
        // Our own "Tell the agent" menu is open underneath: do not cover it.
        if (pro.potoki.bekon.agent.status.PillMenuActivity.open) return false
        return v.paintBody(canvas, p.x, p.y, safeTop, safeBottom)
    }

    /** Screen point covered by our own body (masked out of change probes). */
    fun ownsPoint(x: Int, y: Int): Boolean =
        !pro.potoki.bekon.agent.status.PillMenuActivity.open && view?.owns(x, y) == true

    /** Content scrolled: an anchored character moves with it (lockstep, no animation). */
    fun onAnchorShift(dx: Float, dy: Float, targetGone: Boolean) = onMain {
        val c = cue ?: return@onMain
        if (c.anchor == null || c.kind == Cue.Kind.SCROLL) return@onMain
        if (targetGone) { setCue(null); return@onMain }
        cue = c.shifted(dx, dy)
        val v = view ?: return@onMain; val p = params ?: return@onMain
        v.setTarget(cue?.anchor)
        v.shiftBody(dx, dy)
        anim?.cancel()
        val sc = screen()
        val nx = p.x + dx.toInt(); val ny = p.y + dy.toInt()
        // Pushed against the wall: squash (cartoon), the crawl to a new spot happens when the scroll settles.
        val over = when {
            ny < sc.top -> (sc.top - ny).toFloat() / v.height
            ny + v.height > sc.bottom -> (ny + v.height - sc.bottom).toFloat() / v.height
            else -> 0f
        }
        v.setSquash(over.coerceIn(0f, 0.6f), top = ny < sc.top)
        p.x = nx.coerceIn(sc.left, (sc.right - v.width).coerceAtLeast(sc.left))
        p.y = ny.coerceIn(sc.top, (sc.bottom - v.height).coerceAtLeast(sc.top))
        try { wm?.updateViewLayout(v, p) } catch (_: Exception) {}
        v.anchorPoint(cue?.anchor, p)
        pro.potoki.bekon.agent.ocr.OwnOverlay.pillRect = Rect(p.x, p.y, p.x + v.width, p.y + v.height)
    }

    fun onScrollSettled() = onMain {
        val v = view ?: return@onMain
        v.setSquash(0f, false)
        if (cue?.anchor != null) { cueDirty = true; gov.force(); relayoutNow() }
    }

    // ---- lifecycle ----

    private fun shouldShow(): Boolean {
        val s = pro.potoki.bekon.agent.session.SessionService.state.value
        if (!s.isRunning || s.overlaysMustClear) return false
        return pro.potoki.bekon.agent.access.AgentConnections.connected.value.isNotEmpty() || AgentStatus.current.value != null
    }

    fun refresh() = main.post {
        if (pro.potoki.bekon.agent.overlay.OverlayHost.guidance) host?.let { h ->
            val s = pro.potoki.bekon.agent.session.SessionService.state.value
            val on = s.isRunning && !s.overlaysMustClear && pro.potoki.bekon.agent.access.AgentConnections.connected.value.isNotEmpty()
            pro.potoki.bekon.agent.coach.CoachOverlay.setSessionFrame(h, on)
        }
        if (!shouldShow()) { remove(); return@post }
        val v = view ?: install() ?: return@post
        v.update(AgentStatus.current.value, Capabilities.current(), cue)
        relayoutNow()
        // Our own "Tell the agent" menu sits under the overlay: step aside (fade, pass touches) while it is open.
        val menu = pro.potoki.bekon.agent.status.PillMenuActivity.open
        params?.let { p ->
            val f = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            val nf = if (menu) p.flags or f else p.flags and f.inv()
            if (nf != p.flags) { p.flags = nf; try { wm?.updateViewLayout(v, p) } catch (_: Exception) {} }
        }
        pro.potoki.bekon.agent.coach.CoachOverlay.redrawNow()
    }

    fun relayout() = main.post { relayoutNow() }

    private fun remove() {
        pro.potoki.bekon.agent.ocr.OwnOverlay.pillRect = null
        pro.potoki.bekon.agent.ocr.OwnOverlay.pillLines = emptyList()
        anim?.cancel(); anim = null
        val v = view ?: return
        try { wm?.removeView(v) } catch (_: Exception) {}
        view = null; params = null; lastKey = ""; cue = null
    }

    private fun install(): CharView? {
        val svc = host ?: return null
        return try {
            val w = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val v = CharView(svc)
            val p = pro.potoki.bekon.agent.overlay.OverlayHost.touchableWrapParams()
            p.gravity = Gravity.TOP or Gravity.START
            // Learn about touches elsewhere (no coordinates): never reposition while the user touches.
            p.flags = p.flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
            w.addView(v, p)
            wm = w; view = v; params = p
            gov.force()
            v
        } catch (e: Exception) { null }
    }

    private fun screen(): Rect {
        val svc = host!!
        val dm = svc.resources.displayMetrics
        val strip = (pro.potoki.bekon.agent.coach.BaconFrame.VISIBLE_DP * dm.density).toInt()
        if (Build.VERSION.SDK_INT >= 30) try {
            val m = (svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager).currentWindowMetrics
            val ins = m.windowInsets.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
            return Rect(ins.left + strip, ins.top, m.bounds.width() - ins.right - strip, m.bounds.height() - ins.bottom)
        } catch (_: Exception) {}
        return Rect(strip, (24 * dm.density).toInt(), dm.widthPixels - strip, dm.heightPixels - (48 * dm.density).toInt())
    }

    /** Areas the character must never cover: target frames and numbered marks. */
    private fun avoid(d: Float): List<RectF> = buildList {
        addAll(pro.potoki.bekon.agent.coach.CoachOverlay.markBounds(d))
        GestureOverlay.coachBounds()?.let { add(RectF(it)) }
    }

    private val recent = ArrayDeque<Rect>()
    /** Our own window(s) in screen px, masked out of change signatures (current + recent spots). */
    private fun ownMask(): List<IntArray> = synchronized(recent) { recent.map { intArrayOf(it.left, it.top, it.right, it.bottom) } }
    private fun remember(r: Rect) = synchronized(recent) { recent.addLast(r); while (recent.size > 3) recent.removeFirst() }

    private fun relayoutNow() {
        val v = view ?: return; val p = params ?: return
        if (host == null) return
        val d = v.resources.displayMetrics.density
        val sc = screen()
        FreeSpot.safeArea = intArrayOf(sc.left, sc.top, sc.right, sc.bottom)
        val c = cue
        val anchorF = c?.anchor
        val (w, h) = v.measureNow()
        val key = "$w,$h,${sc.flattenToString()}"
        if (key != lastKey) { lastKey = key; gov.force() }
        val now = System.currentTimeMillis()
        val placed = p.x != 0 || p.y != 0
        if (anim?.isRunning == true) return          // one smooth move at a time
        var target: Pair<Int, Int>? = null
        val m = manual
        if (m != null && c == null) target = m
        else if (!placed || cueDirty || gov.shouldRecompute(now)) {
            val obs = avoid(d).map { intArrayOf(it.left.toInt(), it.top.toInt(), it.right.toInt(), it.bottom.toInt()) }
            val self = if (placed && !cueDirty) intArrayOf(p.x, p.y, p.x + w, p.y + h) else null
            val anchor = anchorF?.let { intArrayOf(it[0].toInt(), it[1].toInt(), it[2].toInt(), it[3].toInt()) }
                ?: self ?: intArrayOf(sc.right - w, sc.bottom - h - (40 * d).toInt(), sc.right, sc.bottom - (40 * d).toInt())
            // A grid from the other orientation (rotation just happened) describes another screen: skip it.
            val g = FreeSpot.current?.takeIf { (it.sw > it.sh) == ((sc.right - sc.left) > (sc.bottom - sc.top)) }
            val spot = g?.let { FreeSpot.find(it, w, h, anchor, obs, intArrayOf(sc.left, sc.top, sc.right, sc.bottom), self = self,
                distWeight = if (anchorF != null) 1.6f else 0.6f, margin = ((if (c?.kind == Cue.Kind.TAP) 14 else 8) * d).toInt()) }
            val best = spot?.rect?.let { it[0] to it[1] } ?: if (anchorF != null) {
                // No frame analysed yet: beside the target, below it if there is room, else above.
                val below = anchorF[3].toInt() + (10 * d).toInt()
                val y = if (below + h < sc.bottom) below else anchorF[1].toInt() - h - (10 * d).toInt()
                anchorF[0].toInt() to y
            } else (sc.right - w - (8 * d).toInt()) to (sc.bottom - h - (56 * d).toInt())
            val here = if (self != null && g != null) FreeSpot.costAt(g, self, obs, self) else Float.MAX_VALUE
            val occupied = self != null && (here >= FreeSpot.OBSTACLE_COST * 0.2f || obs.any { Rect.intersects(Rect(it[0], it[1], it[2], it[3]), Rect(self[0], self[1], self[2], self[3])) })
            val move = gov.decide(now, if (here == Float.MAX_VALUE) 99f else here, spot?.cost ?: 0f, occupied, firstPlacement = !placed || cueDirty)
            cueDirty = false
            if (move) target = best
        }
        val (tx0, ty0) = target ?: (p.x to p.y)
        val tx = tx0.coerceIn(sc.left, (sc.right - w).coerceAtLeast(sc.left))
        val ty = ty0.coerceIn(sc.top, (sc.bottom - h).coerceAtLeast(sc.top))
        if (tx != p.x || ty != p.y) {
            if (placed && v.isAttachedToWindow) { animateTo(v, p, tx, ty); gov.moved(now) }
            else { p.x = tx; p.y = ty; try { wm?.updateViewLayout(v, p) } catch (_: Exception) {} }
        }
        pro.potoki.bekon.agent.coach.CoachOverlay.redrawNow()
        v.anchorPoint(anchorF, p, tx, ty)
        val r = Rect(tx, ty, tx + w, ty + h)
        remember(r)
        pro.potoki.bekon.agent.ocr.OwnOverlay.pillRect = r
    }

    private var anim: android.animation.ValueAnimator? = null

    /** Crawl to the new spot: one eased move with a little squash-and-stretch. */
    private fun animateTo(v: CharView, p: WindowManager.LayoutParams, tx: Int, ty: Int) {
        anim?.cancel()
        val sx = p.x; val sy = p.y
        anim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 420
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                p.x = (sx + (tx - sx) * f).toInt(); p.y = (sy + (ty - sy) * f).toInt()
                if (cue != null) pro.potoki.bekon.agent.coach.ChangeWatch.rebase(System.currentTimeMillis() + 450)
                try { if (v.isAttachedToWindow) wm?.updateViewLayout(v, p) } catch (_: Exception) {}
                v.invalidate()
                pro.potoki.bekon.agent.coach.CoachOverlay.redrawNow()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) {
                    v.invalidate()
                    // Our own motion is not a screen change: retake the probe reference.
                    pro.potoki.bekon.agent.coach.ChangeWatch.rebase(System.currentTimeMillis() + 120)
                }
            })
            start()
        }
    }

    private fun openMenu(ctx: Context) {
        try {
            ctx.startActivity(Intent(ctx, pro.potoki.bekon.agent.status.PillMenuActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        } catch (_: Exception) {}
    }

    @SuppressLint("ViewConstructor")
    private class CharView(ctx: Context) : View(ctx) {
        enum class Mode { IDLE, WORKING, WAITING, TAP, SCROLL, DONE, ERROR }
        private val d = resources.displayMetrics.density
        var mode = Mode.IDLE; private set
        private var dir = "down"
        private var line1 = ""; private var line2 = ""
        private var squash = 0f; private var squashTop = false
        private var bodyLeft = true
        private var target: FloatArray? = null

        // ONE body: one path (outer + hole, even-odd), one fill, one stroke. Nothing else is drawn.
        private val spring = pro.potoki.bekon.agent.coach.BodySpring()
        private val bodyPath = Path().apply { fillType = Path.FillType.EVEN_ODD }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val keyline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
        private val edgeStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
        private val t1 = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = android.graphics.Typeface.DEFAULT_BOLD; textSize = 13f * d }
        private val t2 = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10.5f * d }
        private var lay1: android.text.StaticLayout? = null
        private var shapeKey = ""
        private var lastStep = 0L
        private var tailAxis: FloatArray? = null
        private var textBox = RectF()
        private var ownBox: RectF? = null
        private var winX = 0; private var winY = 0
        private val guide = pro.potoki.bekon.agent.overlay.OverlayHost.guidance

        // ---- palette: the fill is the state colour (never background-adapted); text comes from the fill ----
        private var fillFrom = MODE_COLORS.getValue(Mode.IDLE)
        private var fillTo = fillFrom
        private var palT = 1f
        private var palAnim: android.animation.ValueAnimator? = null
        private val argb = android.animation.ArgbEvaluator()
        private fun curFill(): Int = if (palT >= 1f) fillTo else argb.evaluate(palT, fillFrom, fillTo) as Int
        private fun retarget(stateColor: Int) {
            fillFrom = curFill(); fillTo = stateColor; palT = 0f
            palAnim?.cancel()
            palAnim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 240; interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                addUpdateListener { palT = it.animatedValue as Float; redraw() }
                start()
            }
        }

        init { clipToOutline = false }

        private fun redraw() { if (guide) pro.potoki.bekon.agent.coach.CoachOverlay.redrawNow() else invalidate() }

        fun update(s: AgentStatus.Status?, c: Capabilities.Caps, cue: Cue?) {
            val name = c.agent ?: ""
            val newMode = when {
                cue?.kind == Cue.Kind.SCROLL -> Mode.SCROLL
                cue?.kind == Cue.Kind.TAP -> Mode.TAP
                cue != null -> Mode.WAITING
                s == null -> Mode.IDLE
                s.state == StatusState.WORKING -> Mode.WORKING
                s.state == StatusState.WAITING -> Mode.WAITING
                s.state == StatusState.DONE -> Mode.DONE
                s.state == StatusState.ERROR -> Mode.ERROR
                else -> Mode.IDLE
            }
            dir = cue?.dir ?: dir
            val statusText = when {
                s == null -> null
                s.res != 0 -> context.getString(s.res)
                s.state == StatusState.DONE -> s.text
                else -> {
                    val lr = when (s.state) {
                        StatusState.WORKING -> R.string.status_working; StatusState.WAITING -> R.string.status_waiting
                        StatusState.DONE -> R.string.status_done; StatusState.ERROR -> R.string.status_error
                    }
                    val head = context.getString(lr)
                    // WORKING: the agent's own text already says what is happening; the generic
                    // head would repeat it ("Preparing a hint… Preparing the next step").
                    if (s.state == StatusState.WORKING && s.text.isNotBlank()) s.text
                    else head + (if (head.endsWith("…") || head.endsWith(":") || head.endsWith("...")) " " else ": ") + s.text
                }
            }
            val capLine = Capabilities.line(context, c, s?.step, s?.total)
            val step = if (s?.step != null && s.total != null) context.getString(R.string.cap_step, s.step, s.total) else null
            if (cue?.text != null) {
                line1 = cue.text
                line2 = listOfNotNull(name.takeIf { it.isNotBlank() }, step).joinToString(" · ")
            } else if (newMode == Mode.IDLE) {
                line1 = name.ifBlank { capLine }
                line2 = if (name.isBlank()) "" else context.getString(when {
                    c.paused -> R.string.cap_paused; c.seeing -> R.string.char_sees; else -> R.string.cap_blind
                })
            } else {
                line1 = statusText ?: name
                line2 = listOfNotNull(name.takeIf { it.isNotBlank() && it != line1 }, step).joinToString(" · ")
            }
            pro.potoki.bekon.agent.ocr.OwnOverlay.pillLines = listOf(line1, line2).filter { it.isNotBlank() }
            if (newMode == Mode.TAP) { line2 = step ?: ""; setTarget(cue?.anchor) }
            if (newMode != mode) {
                mode = newMode
                retarget(MODE_COLORS.getValue(newMode))
                // The body flows into the new shape; its own motion is not a screen change.
                pro.potoki.bekon.agent.coach.ChangeWatch.rebase(System.currentTimeMillis() + 750)
            }
            requestLayout(); redraw()
        }

        fun setSquash(v: Float, top: Boolean) { squash = v; squashTop = top; redraw() }
        fun setTarget(a: FloatArray?) { if (a != null) target = a.copyOf() }
        /** Content scrolled under an anchored body: the whole body moves in lockstep. */
        fun shiftBody(dx: Float, dy: Float) { spring.translate(dx, dy); redraw() }

        fun anchorPoint(a: FloatArray?, p: WindowManager.LayoutParams, x: Int = p.x, y: Int = p.y) {
            val ax = if (a == null) Float.NaN else (a[0] + a[2]) / 2f - x
            val nb = if (!ax.isNaN()) ax < width / 2f else (x + width / 2 < resources.displayMetrics.widthPixels / 2)
            if (nb != bodyLeft) bodyLeft = nb
            redraw()
        }

        /** Window pad: stroke + AA + spring overshoot + wobble, so nothing is ever clipped. */
        private val gpad get() = 10f * d
        private val head get() = 16f * d

        fun measureNow(): Pair<Int, Int> {
            val maxText = (minOf(220f * d, resources.displayMetrics.widthPixels * 0.62f)).toInt()
            val want = maxOf(t1.measureText(line1), if (line2.isEmpty()) 0f else t2.measureText(line2)).toInt() + 1
            val tw = want.coerceIn((40 * d).toInt(), maxText)
            lay1 = android.text.StaticLayout.Builder.obtain(line1, 0, line1.length, t1, tw)
                .setMaxLines(if (mode == Mode.IDLE) 1 else 3).setEllipsize(android.text.TextUtils.TruncateAt.END).setIncludePad(false).build()
            // The body grows to fit its text: pill = eye zone + text; TAP = caption pocket; SCROLL = shaft + head.
            val textH = lay1!!.height + (if (line2.isEmpty()) 0f else 3 * d + t2.textSize)
            // Inner padding: ~9 dp above/below the text, ~16 dp from the text to the contour; the eye
            // lives in its own zone (one body height) clear of the text.
            val ih = maxOf(textH + 2 * PAD_V * d, 44 * d)
            // The eye zone keeps its previous size (body height before the extra text padding).
            var iw = if (mode == Mode.TAP) tw + 2 * PAD_H * d else (ih - EXTRA_V * d) + 4 * d + tw + endPad(ih)
            var hh = ih
            if (mode == Mode.SCROLL) { if (dir == "up" || dir == "down") hh += head else iw += head
                if (dir == "back") iw += pro.potoki.bekon.agent.coach.BodyShapes.BACK_NOTCH * ih }   // the swallowtail notch never eats the text
            return (iw + 2 * gpad).toInt() to (hh + 2 * gpad).toInt()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) { val (w, h) = measureNow(); setMeasuredDimension(w, h) }

        fun owns(x: Int, y: Int): Boolean {
            if (x >= winX && y >= winY && x < winX + width && y < winY + height) return true
            val b = ownBox ?: return false
            if (!b.contains(x.toFloat(), y.toFloat())) return false
            val t = target
            return !(mode == Mode.TAP && t != null && x > t[0] + 2 && x < t[2] - 2 && y > t[1] + 2 && y < t[3] - 2)
        }

        /** Target contours (outer, hole) for the current state, in screen px. */
        private fun targets(px: Int, py: Int, safeTop: Float, safeBottom: Float, now: Long): Triple<FloatArray, FloatArray, String> {
            val S = pro.potoki.bekon.agent.coach.BodyShapes
            val r = RectF(px + gpad, py + gpad, px + width - gpad, py + height - gpad)
            if (squash > 0f) { val k = r.height() * squash * 0.5f; if (squashTop) r.top += k else r.bottom -= k }
            val bt = now % 5200L
            val blink = if (bt >= 140) 1f else 0.15f + 0.85f * kotlin.math.abs(bt - 70f) / 70f
            tailAxis = null
            when (mode) {
                Mode.TAP -> {
                    val tg = target
                    if (tg != null && guide) {
                        var tl = tg[0]; var tt = tg[1]; val tr = tg[2]; var tb = tg[3]
                        val h0 = tb - tt
                        // A target in a system bar (Home/Back, status icons) stays at its true place (the ring is
                        // clipped by the bar); only content scrolled partly under an edge is squashed.
                        if (!pro.potoki.bekon.agent.coach.TargetMapping.inSystemBar(tt, tb, safeTop, safeBottom)) {
                            if (tt < safeTop) { tt = safeTop; tb = maxOf(tb, tt + h0 * 0.22f) }
                            if (tb > safeBottom) { tb = safeBottom; tt = minOf(tt, tb - h0 * 0.22f) }
                        }
                        val gap = 3f * d; val thick = 8f * d
                        val ring = floatArrayOf(tl - gap - thick, tt - gap - thick, tr + gap + thick, tb + gap + thick)
                        val (outer, axis) = S.ringPocket(ring, 12f * d, floatArrayOf(r.left, r.top, r.right, r.bottom), 14f * d, 5f * d, 2.2f * d, 7f * d)
                        tailAxis = axis
                        textBox = RectF(r)
                        val hole = S.roundRect(tl - gap, tt - gap, tr + gap, tb + gap, 6f * d)
                        return Triple(S.resample(outer), S.resample(hole), "tap")
                    }
                    textBox = RectF(r)
                    return Triple(S.resample(S.roundRect(r.left, r.top, r.right, r.bottom, 14f * d)),
                        S.resample(S.ellipse(r.left + 4 * d, r.centerY(), 0.5f, 0.5f)), "tapbare")
                }
                Mode.SCROLL -> {
                    val sh = RectF(r)
                    when (dir) { "up" -> sh.top += head; "down" -> sh.bottom -= head; "left", "back" -> sh.left += head; else -> sh.right -= head }
                    // Bounce along the direction (the whole body, as one).
                    val bnc = kotlin.math.sin((now % 900L) / 900.0 * 2 * Math.PI).toFloat() * 3.5f * d
                    when (dir) { "up" -> sh.offset(0f, -bnc); "down" -> sh.offset(0f, bnc); "left", "back" -> sh.offset(-bnc, 0f); else -> sh.offset(bnc, 0f) }
                    val e = eye(sh, 1f)
                    textBox = textArea(sh).also { if (dir == "back") it.right -= pro.potoki.bekon.agent.coach.BodyShapes.BACK_NOTCH * sh.height() }
                    return Triple(S.resample(S.arrow(sh.left, sh.top, sh.right, sh.bottom, dir, head)), S.resample(S.ellipse(e.centerX(), e.centerY(), e.width() / 2, e.height() / 2)), "scroll$dir")
                }
                Mode.WAITING -> {
                    // A drop: the pill swells and points down to where the user acts.
                    val e = eye(r, blink)
                    textBox = textArea(r)
                    val pill = S.pill(r.left, r.top, r.right, r.bottom)
                    return Triple(S.resample(pill), S.resample(S.ellipse(e.centerX(), e.centerY(), e.width() / 2, e.height() / 2)), "wait")
                }
                else -> {
                    var e = eye(r, blink)
                    if (mode == Mode.WORKING) { val k = 1f + 0.18f * kotlin.math.sin((now % 1200L) / 1200.0 * 2 * Math.PI).toFloat(); e = RectF(e.centerX() - e.width() / 2 * k, e.centerY() - e.height() / 2 / k, e.centerX() + e.width() / 2 * k, e.centerY() + e.height() / 2 / k) }
                    textBox = textArea(r)
                    return Triple(S.resample(S.pill(r.left, r.top, r.right, r.bottom)), S.resample(S.ellipse(e.centerX(), e.centerY(), e.width() / 2, e.height() / 2)), "pill")
                }
            }
        }

        private fun eye(r: RectF, blink: Float): RectF {
            val h0 = eyeZone(r); val ex = if (bodyLeft) r.left + r.height() / 2 else r.right - r.height() / 2; val ey = r.centerY()
            val re = h0 * 0.24f
            return RectF(ex - re, ey - re * blink, ex + re, ey + re * blink)
        }
        private fun eyeZone(r: RectF) = r.height() - EXTRA_V * d
        /** Text inset at the round end: ≥ PAD_H and ≥ 8 dp clear of the arc (tall bodies need more). */
        private fun endPad(h: Float) = pro.potoki.bekon.agent.coach.BodyShapes.pillPadH(h, PAD_V * d, PAD_H * d, 9f * d)
        private fun textArea(r: RectF) = if (bodyLeft) RectF(r.left + eyeZone(r) + 4 * d, r.top + PAD_V * d, r.right - endPad(r.height()), r.bottom - PAD_V * d)
            else RectF(r.left + endPad(r.height()), r.top + PAD_V * d, r.right - eyeZone(r) - 4 * d, r.bottom - PAD_V * d)

        /** Paint the single body (screen coords). Returns true while it needs more frames. */
        fun paintBody(canvas: Canvas, px: Int, py: Int, safeTop: Float, safeBottom: Float): Boolean {
            winX = px; winY = py
            val now = System.currentTimeMillis()
            val (to, th, key) = targets(px, py, safeTop, safeBottom, now)
            val stagger = key != shapeKey && spring.ready
            spring.setTargets(to, th, now, stagger)
            shapeKey = key
            val dt = if (lastStep == 0L) 16f else (now - lastStep).toFloat()
            lastStep = now
            val moving = spring.step(dt, now)
            // The body's own flow is not "the screen changed": keep the probe reference until it settles.
            if (moving && mode == Mode.TAP) pro.potoki.bekon.agent.coach.ChangeWatch.rebase(now + 450)   // capture lags the screen ~1–3 frames
            // Path: outer + hole, with a slight wobble while the body is moving.
            val amp = (spring.speed / 500f).coerceIn(0f, 1f) * 2.2f * d
            val ph = (now % 1000L) / 1000.0 * 2 * Math.PI
            bodyPath.rewind(); bodyPath.fillType = Path.FillType.EVEN_ODD   // rewind() resets the fill type
            for (c in 0..1) {
                val p = if (c == 0) spring.outer else spring.hole
                val n = p.size / 2
                for (i in 0 until n) {
                    val w = if (amp > 0f) (amp * kotlin.math.sin(ph + i * 6.0 * Math.PI / n)).toFloat() else 0f
                    val j = (i + 1) % n; val k = (i + n - 1) % n
                    val nx = -(p[2 * j + 1] - p[2 * k + 1]); val ny = p[2 * j] - p[2 * k]
                    val len = kotlin.math.hypot(nx, ny).coerceAtLeast(0.001f)
                    val x = p[2 * i] + nx / len * w; val y = p[2 * i + 1] + ny / len * w
                    if (i == 0) bodyPath.moveTo(x, y) else bodyPath.lineTo(x, y)
                }
                bodyPath.close()
            }
            val ob = pro.potoki.bekon.agent.coach.BodyShapes.bounds(spring.outer)
            ownBox = RectF(ob[0] - 6, ob[1] - 6, ob[2] + 6, ob[3] + 6)
            // Colours: fill = state colour. Border is fixed: a black keyline + white stroke, both
            // stroking the SAME single contour (contrasts on any background).
            val fc = curFill() or (0xFF shl 24)
            val FA = pro.potoki.bekon.agent.coach.StateFills.TEXT_ALPHA   // opaque where text sits
            edgeStroke.clearShadowLayer()
            val ax = tailAxis
            fun grad(c: Int, a0: Int, a1: Int) = android.graphics.LinearGradient(ax!![0], ax[1], ax[2], ax[3],
                intArrayOf((c and 0x00FFFFFF) or (a0 shl 24), (c and 0x00FFFFFF) or (a1 shl 24), (c and 0x00FFFFFF) or (a0 shl 24)),
                floatArrayOf(0f, 0.5f, 1f), android.graphics.Shader.TileMode.CLAMP)
            if (ax != null) {
                // The tail fades toward transparency mid-way; ring and pocket stay solid.
                fill.shader = grad(fc, FA, 40)
                keyline.shader = grad(Color.BLACK, 235, 60); edgeStroke.shader = grad(Color.WHITE, 255, 70)
            } else {
                fill.shader = null; fill.color = (fc and 0x00FFFFFF) or (FA shl 24)
                keyline.shader = null; keyline.color = Color.argb(235, 0, 0, 0)
                edgeStroke.shader = null; edgeStroke.color = Color.WHITE
            }
            keyline.strokeWidth = 4.4f * d
            edgeStroke.strokeWidth = 2.6f * d
            canvas.drawPath(bodyPath, fill)
            canvas.drawPath(bodyPath, keyline)      // black keyline (wider) under…
            canvas.drawPath(bodyPath, edgeStroke)   // …the white stroke: one contour, dual tone
            // Text inside the body: computed from the FILL only (luminance; ≥ 4.5:1). The single text-colour path.
            val l1 = lay1 ?: run { measureNow(); lay1!! }
            val tc = pro.potoki.bekon.agent.coach.ContrastPicker.textOn(fc, FA)   // effective fill under the text
            t1.color = tc; t2.color = tc   // second line same colour (no alpha: keeps ≥ 4.5:1)
            val settledness = if (mode == Mode.TAP && moving) (1f - spring.speed / 300f).coerceIn(0f, 1f) else 1f
            if (settledness > 0.05f) {
                val box = textBox
                val thh = l1.height + (if (line2.isEmpty()) 0f else 3 * d + t2.textSize)
                val tx = if (mode == Mode.TAP) box.left + PAD_H * d else box.left
                val ty = box.centerY() - thh / 2
                val sv = canvas.saveLayerAlpha(box.left - 2, box.top - 2, box.right + 2, box.bottom + 2, (settledness * 255).toInt())
                canvas.translate(tx, ty); l1.draw(canvas)
                if (line2.isNotEmpty()) canvas.drawText(line2, 0f, l1.height + 3 * d + t2.textSize * 0.85f, t2)
                canvas.restoreToCount(sv)
            }
            val bt = now % 5200L
            return moving || mode == Mode.SCROLL || mode == Mode.WORKING || bt < 200 || palT < 1f
        }

        override fun onDraw(canvas: Canvas) {
            if (guide) { pro.potoki.bekon.agent.coach.CoachOverlay.redrawNow(); return }
            // Full flavor: the same single body, painted inside this window.
            val p = params ?: return
            canvas.save(); canvas.translate(-p.x.toFloat(), -p.y.toFloat())
            val more = paintBody(canvas, p.x, p.y, -1e6f, 1e6f)
            canvas.restore()
            if (more) postInvalidateOnAnimation()
        }

        private var downX = 0f; private var downY = 0f; private var startX = 0; private var startY = 0
        private var dragging = false
        private val slop = ViewConfiguration.get(ctx).scaledTouchSlop

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                val now = System.currentTimeMillis()
                gov.touched(now); pro.potoki.bekon.agent.coach.ChangeWatch.touched(now)
                android.util.Log.d("BekonChar", "touch outside @$now")
                return false
            }
            val p = params ?: return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = p.x; startY = p.y; dragging = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!dragging && (Math.abs(dx) > slop || Math.abs(dy) > slop)) dragging = true
                    if (dragging) {
                        anim?.cancel()
                        p.x = (startX + dx).toInt(); p.y = (startY + dy).toInt()
                        try { wm?.updateViewLayout(this, p) } catch (_: Exception) {}
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) openMenu(context) else {
                        val sw = resources.displayMetrics.widthPixels; val sh = resources.displayMetrics.heightPixels
                        p.x = p.x.coerceIn(0, (sw - width).coerceAtLeast(0)); p.y = p.y.coerceIn(0, (sh - height).coerceAtLeast(0))
                        try { wm?.updateViewLayout(this, p) } catch (_: Exception) {}
                        manual = p.x to p.y
                        pro.potoki.bekon.agent.ocr.OwnOverlay.pillRect = Rect(p.x, p.y, p.x + width, p.y + height)
                    }
                    dragging = false
                }
                MotionEvent.ACTION_CANCEL -> dragging = false
            }
            return true
        }

        companion object {
            const val PAD_H = 18f
            const val PAD_V = 9f
            /** Extra vertical text padding vs the previous 7 dp (the eye does not grow with it). */
            const val EXTRA_V = 4f
            private val F = pro.potoki.bekon.agent.coach.StateFills
            val MODE_COLORS = mapOf(
                Mode.IDLE to F.IDLE, Mode.WORKING to F.WORKING,
                Mode.WAITING to F.ATTENTION, Mode.TAP to F.ATTENTION, Mode.SCROLL to F.ATTENTION,
                Mode.DONE to F.DONE, Mode.ERROR to F.ERROR,
            )
        }
    }
}
