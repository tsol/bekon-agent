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
        "touch" to TouchService.isBound(),
        "keyboard" to BekonImeService.isEnabled(context.contentResolver),
        "tunnel" to false, // filled by caller on the wire
    )

    override fun snapshot(): ScreenSnapshot {
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
            if (r - l <= 0 || bot - t <= 0) continue
            val text = o.optString("text").trim().ifEmpty { null }
            val desc = o.optString("desc").trim().ifEmpty { null }
            val clickable = o.optBoolean("clickable", false)
            nodes.add(RawNode(intArrayOf(l, t, r, bot), text, desc, clickable))
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
        val sc = screenCapture ?: throw CapabilityException("capture: media permission not granted")
        val opts = CapturePrefs.resolve(context, hiRes = true, scale = scale, quality = quality, format = format)
        val frame = sc.capture(opts) ?: throw CapabilityException("capture: no frame")
        val (sw, sh) = screenSize
        return JpegFrame(frame.bytes, frame.width, frame.height, frame.mime, sw, sh)
    }

    override fun annotatedScreenshot(
        shapes: List<pro.potoki.bekon.agent.coach.Shape>, scale: Float, quality: Int, format: String,
    ): JpegFrame {
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

    override fun drawOnScreen(shapes: List<pro.potoki.bekon.agent.coach.Shape>, append: Boolean, ttlMs: Long): Boolean =
        pro.potoki.bekon.agent.coach.CoachOverlay.show(TouchService.instance, shapes, append, ttlMs)

    override fun clearScreen() = pro.potoki.bekon.agent.coach.CoachOverlay.clear()

    override fun tap(x: Int, y: Int): Boolean =
        controller()?.tap(x.toFloat(), y.toFloat())
            ?: throw CapabilityException("touch: accessibility service not enabled")

    override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Int): Boolean =
        controller()?.swipe(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), ms.toLong())
            ?: throw CapabilityException("touch: accessibility service not enabled")

    override fun longPress(x: Int, y: Int): Boolean =
        controller()?.longPress(x.toFloat(), y.toFloat())
            ?: throw CapabilityException("touch: accessibility service not enabled")

    override fun drag(x: Int, y: Int): Boolean {
        val c = controller() ?: throw CapabilityException("touch: accessibility service not enabled")
        return try {
            c.drag(x.toFloat(), y.toFloat())
        } catch (e: Exception) {
            throw CapabilityException(e.message ?: "drag unsupported")
        }
    }

    override fun release(): Boolean =
        controller()?.release() ?: throw CapabilityException("touch: accessibility service not enabled")

    override fun type(text: String): Boolean =
        controller()?.inputAsKeys(text)
            ?: throw CapabilityException("touch: accessibility service not enabled")

    override fun typeText(text: String, replace: Boolean, asKeys: Boolean): Boolean {
        val c = controller() ?: throw CapabilityException("touch: accessibility service not enabled")
        return try {
            c.input(text, asKeys = asKeys, replace = replace)
        } catch (e: Exception) {
            throw CapabilityException(e.message ?: "type failed")
        }
    }

    override fun key(name: String, n: Int): Boolean =
        controller()?.key(name, n) ?: throw CapabilityException("touch: accessibility service not enabled")

    override fun clipboard(): String? {
        controller() ?: throw CapabilityException("touch: accessibility service not enabled")
        return controller()?.clipboardText()
    }

    override fun nav(action: String): Boolean {
        val c = controller() ?: throw CapabilityException("touch: accessibility service not enabled")
        return when (NavAction.fromWire(action)) {
            NavAction.HOME -> c.home()
            NavAction.BACK -> c.back()
            NavAction.RECENTS -> c.recentApps()
            null -> throw CapabilityException("unknown nav action")
        }
    }

    private fun controller(): TouchController? = TouchService.instance?.controller

    private fun resolveName(rn: RawNode, all: List<RawNode>): String =
        NodeNameResolver.resolve(
            rn.bounds, rn.text, rn.desc, rn.clickable,
            all.map { it.bounds to (it.text ?: it.desc) },
        )

    private fun realScreenSize(): Pair<Int, Int> {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        return dm.widthPixels to dm.heightPixels
    }

    private data class RawNode(val bounds: IntArray, val text: String?, val desc: String?, val clickable: Boolean)
}
