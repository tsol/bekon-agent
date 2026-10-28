package pro.potoki.bekon.agent.core

import android.content.Context
import android.content.Intent
import android.util.DisplayMetrics
import android.view.WindowManager
import pro.potoki.bekon.agent.device.capture.CaptureOpts
import pro.potoki.bekon.agent.device.capture.CapturePrefs
import pro.potoki.bekon.agent.device.capture.ScreenCapture
import pro.potoki.bekon.agent.device.ime.BekonImeService
import pro.potoki.bekon.agent.device.touch.A11yDump
import pro.potoki.bekon.agent.device.touch.TouchController
import pro.potoki.bekon.agent.device.touch.TouchService
import org.json.JSONArray

/**
 * Real device executor. Wires the copied gateway device classes behind the
 * ActionExecutor interface so a single implementation serves HTTP and MCP.
 */
class DeviceExecutor(private val context: Context) : ActionExecutor, pro.potoki.bekon.agent.coach.CoachSurface {

    private var screenCapture: ScreenCapture? = null
    @Volatile private var captureStarted = false

    private val screenSize: Pair<Int, Int> by lazy { realScreenSize() }

    /** Provide the MediaProjection permission result (called when user consents). */
    fun onCapturePermission(data: Intent, resultCode: Int) {
        val sc = ScreenCapture(context)
        sc.setPermissionData(data, resultCode)
        sc.start()
        screenCapture = sc
        captureStarted = true
    }

    override fun health(): Map<String, Boolean> = mapOf(
        "capture" to (screenCapture?.isActive == true),
        "touch" to (if (pro.potoki.bekon.agent.BuildConfig.GUIDANCE_MODE) false else TouchService.isBound()),
        "overlay" to pro.potoki.bekon.agent.overlay.OverlayHost.canDraw(context),
        "guidance" to pro.potoki.bekon.agent.BuildConfig.GUIDANCE_MODE,
        "keyboard" to (if (pro.potoki.bekon.agent.BuildConfig.GUIDANCE_MODE) false else BekonImeService.isEnabled(context.contentResolver)),
        "tunnel" to false, // filled by caller on the wire
    )

    override fun snapshot(): ScreenSnapshot {
        guardRead()
        if (pro.potoki.bekon.agent.BuildConfig.GUIDANCE_MODE) return snapshotFromOcr()
        val controller = controller()
        if (controller == null) throw CapabilityException("touch: accessibility service not enabled")
        val raw = A11yDump.dumpJson() ?: throw CapabilityException("snapshot: no accessibility tree")
        val arr = JSONArray(raw)
        val nodes = ArrayList<RawNode>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val b = o.optJSONArray("bounds")
            if (b == null || b.length() != 4) continue
            val l = b.getInt(0); val t = b.getInt(1); val r = b.getInt(2); val bot = b.getInt(3)
            // Compare, don't subtract: offscreen nodes report [MAX,MAX,MIN,MIN] and MIN-MAX overflows to a positive width.
            if (r <= l || bot <= t) continue
            val scrMax = maxOf(screenSize.first, screenSize.second) // rotation-safe
            if (r <= 0 || bot <= 0 || l >= scrMax || t >= scrMax) continue
            if (o.has("visible") && !o.optBoolean("visible", true)) continue
            val text = o.optString("text").trim().ifEmpty { null }
            val desc = o.optString("desc").trim().ifEmpty { null }
            val clickable = o.optBoolean("clickable", false)
            nodes.add(RawNode(intArrayOf(l, t, r, bot), text, desc, clickable,
                o.optBoolean("checkable"), o.optBoolean("checked"), o.optBoolean("selected"),
                o.optBoolean("enabled", true), o.optString("pkg").ifEmpty { null }, o.optBoolean("statusBar"),
                o.optString("win").ifEmpty { null },
                o.optString("id").ifEmpty { null }, o.optString("class").ifEmpty { null }, o.optBoolean("editable")))
        }
        val items = ArrayList<UiNode>()
        for (i in nodes.indices) {
            val rn = nodes[i]
            items.add(
                UiNode(
                    ref = "e${i + 1}",
                    label = resolveName(rn, nodes),
                    x = (rn.bounds[0] + rn.bounds[2]) / 2,
                    y = (rn.bounds[1] + rn.bounds[3]) / 2,
                    bounds = rn.bounds,
                    clickable = rn.clickable,
                    checkable = rn.checkable, checked = rn.checked, selected = rn.selected,
                    enabled = rn.enabled, pkg = rn.pkg, statusBar = rn.statusBar, window = rn.window, viewId = rn.viewId, cls = rn.cls, editable = rn.editable,
                )
            )
        }
        val (sw, sh) = screenSize
        val (iw, ih) = CapturePrefs.pixelSize(sw, sh, ScreenshotDefaults.SCALE)
        return ScreenSnapshot(
            screenW = sw,
            screenH = sh,
            imageW = iw,
            imageH = ih,
            items = items,
        )
    }

    override fun screenshot(scale: Float, quality: Int, format: String): JpegFrame {
        guardRead()
        val sc = screenCapture ?: throw CapabilityException("capture: media permission not granted")
        val opts = CapturePrefs.resolve(context, hiRes = true, scale = scale, quality = quality, format = format)
        val frame = sc.capture(opts) ?: throw CapabilityException("capture: no frame")
        val (sw, sh) = screenSize
        return JpegFrame(frame.bytes, frame.width, frame.height, frame.mime, sw, sh)
    }

    override fun annotatedScreenshot(
        shapes: List<pro.potoki.bekon.agent.coach.Shape>, scale: Float, quality: Int, format: String,
    ): JpegFrame {
        guardRead()
        val sc = screenCapture ?: throw CapabilityException("capture: media permission not granted")
        val full = sc.latestBitmap() ?: throw CapabilityException("capture: no frame")
        val (sw, sh) = screenSize
        val s = scale.coerceIn(0.1f, 1f)
        val tw = (sw * s).toInt().coerceAtLeast(1); val th = (sh * s).toInt().coerceAtLeast(1)
        val out = android.graphics.Bitmap.createScaledBitmap(full, tw, th, true)
            .copy(android.graphics.Bitmap.Config.ARGB_8888, true)
        full.recycle()
        // Bitmap is screen-sized * s, so screen coords map by tw/sw.
        pro.potoki.bekon.agent.coach.SketchPainter.draw(android.graphics.Canvas(out), shapes, tw.toFloat() / sw)
        val png = format.equals("png", true)
        val bos = java.io.ByteArrayOutputStream()
        out.compress(if (png) android.graphics.Bitmap.CompressFormat.PNG else android.graphics.Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), bos)
        out.recycle()
        return JpegFrame(bos.toByteArray(), tw, th, if (png) "image/png" else "image/jpeg", sw, sh)
    }

    override fun drawOnScreen(shapes: List<pro.potoki.bekon.agent.coach.Shape>, append: Boolean, ttlMs: Long): Boolean {
        guardRead()
        if (pro.potoki.bekon.agent.BuildConfig.GUIDANCE_MODE) {
            pro.potoki.bekon.agent.coach.CoachOverlay.bindAppContext(context)
            val before = latestCaptureBitmap()
            val prev = if (append) pro.potoki.bekon.agent.coach.CoachOverlay.markBounds(context.resources.displayMetrics.density)
                .map { intArrayOf(it.left.toInt(), it.top.toInt(), it.right.toInt(), it.bottom.toInt()) } else emptyList()
            val placed = try { pro.potoki.bekon.agent.coach.FreeSpot.placeLabels(shapes, extra = prev + listOfNotNull(pro.potoki.bekon.agent.ocr.OwnOverlay.pillRect?.let { intArrayOf(it.left, it.top, it.right, it.bottom) })) } catch (_: Exception) { shapes }
            val ok = pro.potoki.bekon.agent.coach.CoachOverlay.show(null, placed, append, ttlMs)
            if (ok) {
                try {
                    Thread.sleep(250)
                    var after = latestCaptureBitmap()
                    var hidden = overlayMissing(shapes, before, after)
                    if (hidden == true && pro.potoki.bekon.agent.coach.CoachOverlay.hasMarks()) {
                        // The projection frame can lag a draw; confirm once before falling back.
                        Thread.sleep(450); after?.recycle(); after = latestCaptureBitmap()
                        hidden = overlayMissing(shapes, before, after)
                    }
                    if (hidden == null) hidden = try { pro.potoki.bekon.agent.device.touch.StatusPill.hiddenBySystem() } catch (_: Exception) { null }
                    if (hidden != null) pro.potoki.bekon.agent.coach.CoachOverlay.screenHidesOverlay = hidden
                    after?.recycle()
                } catch (_: Exception) {}
            }
            before?.recycle()
            return ok
        }
        return pro.potoki.bekon.agent.coach.CoachOverlay.show(TouchService.active, shapes, append, ttlMs)
    }

    override fun clearScreen() = pro.potoki.bekon.agent.coach.CoachOverlay.clear()

    override fun tap(x: Int, y: Int): Boolean =
        writer()?.tap(x.toFloat(), y.toFloat())
            ?: throw CapabilityException("touch: accessibility service not enabled")

    override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Int): Boolean =
        writer()?.swipe(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), ms.toLong())
            ?: throw CapabilityException("touch: accessibility service not enabled")

    override fun longPress(x: Int, y: Int): Boolean =
        writer()?.longPress(x.toFloat(), y.toFloat())
            ?: throw CapabilityException("touch: accessibility service not enabled")

    override fun drag(x: Int, y: Int): Boolean {
        val c = writer() ?: throw CapabilityException("touch: accessibility service not enabled")
        return try {
            c.drag(x.toFloat(), y.toFloat())
        } catch (e: Exception) {
            throw CapabilityException(e.message ?: "drag unsupported")
        }
    }

    override fun release(): Boolean =
        writer()?.release() ?: throw CapabilityException("touch: accessibility service not enabled")

    override fun type(text: String): Boolean =
        writer()?.inputAsKeys(text)
            ?: throw CapabilityException("touch: accessibility service not enabled")

    override fun typeText(text: String, replace: Boolean, asKeys: Boolean): Boolean {
        val c = writer() ?: throw CapabilityException("touch: accessibility service not enabled")
        return try {
            c.input(text, asKeys = asKeys, replace = replace)
        } catch (e: Exception) {
            throw CapabilityException(e.message ?: "type failed")
        }
    }

    override fun scroll(forward: Boolean): Boolean =
        (writer() ?: throw CapabilityException("touch: accessibility service not enabled")).scroll(forward)

    override fun clickNode(bounds: IntArray, long: Boolean): Boolean =
        (writer() ?: throw CapabilityException("touch: accessibility service not enabled")).clickNode(bounds, long)

    override fun enterVia(method: String): Boolean =
        (writer() ?: throw CapabilityException("touch: accessibility service not enabled")).enterVia(method)

    override fun key(name: String, n: Int): Boolean =
        writer()?.key(name, n) ?: throw CapabilityException("touch: accessibility service not enabled")

    override fun clipboard(): String? {
        reader() ?: throw CapabilityException("touch: accessibility service not enabled")
        return reader()?.clipboardText()
    }

    override fun nav(action: String): Boolean {
        val c = writer() ?: throw CapabilityException("touch: accessibility service not enabled")
        return when (NavAction.fromWire(action)) {
            NavAction.HOME -> c.home()
            NavAction.BACK -> c.back()
            NavAction.RECENTS -> c.recentApps()
            null -> throw CapabilityException("unknown nav action")
        }
    }

    private fun writer(): TouchController? {
        guardWrite()
        if (pro.potoki.bekon.agent.BuildConfig.GUIDANCE_MODE) guidanceDenied()
        return controller()
    }
    private fun reader(): TouchController? { guardRead(); return controller() }

    /**
     * Last line of defence, below MCP/HTTP gating: covers coach scripts and long-running calls that were
     * started before the owner pressed Pause / Read-only / Stop. Reads need a live, unpaused session;
     * writes additionally need read-only off.
     */
    private fun guardRead() {
        val st = pro.potoki.bekon.agent.session.SessionService.state.value
        if (st.phase != pro.potoki.bekon.agent.SessionPhase.SERVING && st.phase != pro.potoki.bekon.agent.SessionPhase.STARTING)
            throw CapabilityException("session_stopped: the owner stopped the Bekon session")
        if (st.paused) throw CapabilityException(pro.potoki.bekon.agent.access.ClientGate.PAUSED)
    }
    private fun guardWrite() {
        guardRead()
        if (pro.potoki.bekon.agent.session.SessionService.state.value.readOnly)
            throw CapabilityException(pro.potoki.bekon.agent.mcp.McpHandler.READ_ONLY)
    }

    /** Stop: end MediaProjection and the virtual display, not just the server. */
    fun releaseCapture() {
        try { screenCapture?.stop() } catch (_: Exception) {}
        screenCapture = null
        captureStarted = false
    }


    /** Guidance mode: OCR the latest capture into UiNode-like items (ref e1..). */
    private fun snapshotFromOcr(): ScreenSnapshot {
        val sc = screenCapture ?: throw CapabilityException("capture: media permission not granted")
        val hidPill = pro.potoki.bekon.agent.overlay.OverlayHost.guidance &&
            try { pro.potoki.bekon.agent.device.touch.StatusPill.hideForCapture() } catch (_: Exception) { false }
        if (hidPill) Thread.sleep(220)
        val full = try { sc.latestBitmap() } finally {
            if (hidPill) try { pro.potoki.bekon.agent.device.touch.StatusPill.restoreAfterCapture() } catch (_: Exception) {}
        } ?: throw CapabilityException("capture: no frame")
        val (sw, sh) = screenSizeNow()
        try {
            val ocr = pro.potoki.bekon.agent.ocr.ScreenOcr.recognize(full, timeoutMs = 8_000)
            val blocks = pro.potoki.bekon.agent.ocr.ScreenOcr.toScreen(ocr.blocks, ocr.imageW, ocr.imageH, sw, sh, pillInFrame = !hidPill)
            pro.potoki.bekon.agent.nav.NavJournal.observe(blocks, sw, sh)
            pro.potoki.bekon.agent.coach.FreeSpot.updateFromFrame(full, sw, sh, blocks.map { it.bounds })
            pro.potoki.bekon.agent.ocr.ScreenHistory.offerFrame(full, sw, sh, pro.potoki.bekon.agent.nav.NavJournal.currentFingerprint(), force = true, blocks = blocks)
            val items = ArrayList<UiNode>()
            for ((i, b) in blocks.withIndex()) {
                val bb = b.bounds
                items += UiNode(
                    ref = "e${i + 1}",
                    label = b.text,
                    x = (bb[0] + bb[2]) / 2,
                    y = (bb[1] + bb[3]) / 2,
                    bounds = bb,
                    clickable = false,
                    checkable = false, checked = false, selected = false,
                    enabled = true, pkg = null, statusBar = false, window = "ocr",
                    viewId = null, cls = "ocr", editable = false,
                )
            }
            val (iw, ih) = CapturePrefs.pixelSize(sw, sh, ScreenshotDefaults.SCALE)
            return ScreenSnapshot(screenW = sw, screenH = sh, imageW = iw, imageH = ih, items = items)
        } finally {
            // latestBitmap may be owned by capture — do not recycle here
        }
    }

    /**
     * Some screens (system Settings pages with HIDE_NON_SYSTEM_OVERLAY_WINDOWS) make Android hide our
     * overlay without telling the app. Detect it from the captured frame: near each shape, count pixels
     * of the shape colour before/after drawing. Null = cannot tell (no capture or nothing countable).
     */
    private fun overlayMissing(
        shapes: List<pro.potoki.bekon.agent.coach.Shape>,
        before: android.graphics.Bitmap?,
        after: android.graphics.Bitmap?,
    ): Boolean? {
        if (after == null || shapes.isEmpty()) return null
        var b = 0; var a = 0; var counted = false
        for (s in shapes) {
            // Only what the frames window really draws (captions/scroll arrows live in the character).
            if (s.kind == pro.potoki.bekon.agent.coach.Shape.Kind.GRID || s.kind == pro.potoki.bekon.agent.coach.Shape.Kind.SCROLL ||
                s.kind == pro.potoki.bekon.agent.coach.Shape.Kind.TEXT) continue
            val l = (minOf(s.x1, s.x2) - 24).toInt().coerceIn(0, after.width - 1)
            val r = (maxOf(s.x1, s.x2) + 24).toInt().coerceIn(0, after.width - 1)
            val t = (minOf(s.y1, s.y2) - 24).toInt().coerceIn(0, after.height - 1)
            val btm = (maxOf(s.y1, s.y2) + 24).toInt().coerceIn(0, after.height - 1)
            if (r <= l || btm <= t) continue
            if (!s.label.isNullOrBlank() && s.kind != pro.potoki.bekon.agent.coach.Shape.Kind.MARK) {
                // Wrapped by the character body (contrast-picked colour): count changed pixels instead.
                if (before == null || before.width != after.width || before.height != after.height) continue
                counted = true; a += diffCount(before, after, l, t, r, btm); continue
            }
            counted = true
            a += colorCount(after, s.color, l, t, r, btm)
            if (before != null && before.width == after.width && before.height == after.height) b += colorCount(before, s.color, l, t, r, btm)
        }
        if (!counted) return null
        return a - b < 40 && a < 150
    }

    private fun diffCount(x: android.graphics.Bitmap, y: android.graphics.Bitmap, l: Int, t: Int, r: Int, b: Int): Int {
        var n = 0; var yy = t
        while (yy <= b) { var xx = l
            while (xx <= r) { val p = x.getPixel(xx, yy); val q = y.getPixel(xx, yy)
                if (kotlin.math.abs(((p shr 16) and 0xFF) - ((q shr 16) and 0xFF)) + kotlin.math.abs(((p shr 8) and 0xFF) - ((q shr 8) and 0xFF)) + kotlin.math.abs((p and 0xFF) - (q and 0xFF)) > 90) n++
                xx += 2 }
            yy += 2 }
        return n
    }

    private fun colorCount(bm: android.graphics.Bitmap, c: Int, l: Int, t: Int, r: Int, b: Int): Int {
        val cr = (c shr 16) and 0xFF; val cg = (c shr 8) and 0xFF; val cb = c and 0xFF
        var n = 0
        var y = t
        while (y <= b) {
            var x = l
            while (x <= r) {
                val p = bm.getPixel(x, y)
                if (kotlin.math.abs(((p shr 16) and 0xFF) - cr) < 40 && kotlin.math.abs(((p shr 8) and 0xFF) - cg) < 40 && kotlin.math.abs((p and 0xFF) - cb) < 40) n++
                x += 2
            }
            y += 2
        }
        return n
    }

    fun latestCaptureBitmap(): android.graphics.Bitmap? = try { screenCapture?.latestBitmap() } catch (_: Exception) { null }

    fun screenSizeNow(): Pair<Int, Int> = try { realScreenSize() } catch (_: Exception) { screenSize }

    private fun guidanceDenied(): Nothing =
        throw CapabilityException("guidance_mode: Bekon only draws hints; the user performs every tap. Use coach_draw / find_text / screenshot.")

    private fun controller(): TouchController? = TouchService.active?.controller

    private fun resolveName(rn: RawNode, all: List<RawNode>): String =
        NodeNameResolver.resolve(
            rn.bounds, rn.text, rn.desc, rn.clickable,
            all.map { it.bounds to (it.text ?: it.desc) },
            checkable = rn.checkable,
            viewId = rn.viewId,
            cls = rn.cls,
        )

    private fun realScreenSize(): Pair<Int, Int> {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        return dm.widthPixels to dm.heightPixels
    }

    override fun changeSignal(): ChangeSignal = pro.potoki.bekon.agent.device.touch.ChangeMonitor

    override fun foreground(): Map<String, String> = pro.potoki.bekon.agent.device.touch.ChangeMonitor.foreground()

    private data class RawNode(
        val bounds: IntArray, val text: String?, val desc: String?, val clickable: Boolean,
        val checkable: Boolean = false, val checked: Boolean = false, val selected: Boolean = false,
        val enabled: Boolean = true, val pkg: String? = null, val statusBar: Boolean = false,
        val window: String? = null, val viewId: String? = null, val cls: String? = null,
        val editable: Boolean = false,
    )
}
