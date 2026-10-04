package pro.potoki.bekon.agent.core

import android.content.Context
import android.content.Intent
import android.provider.Settings
import pro.potoki.bekon.agent.device.capture.CaptureFrame
import pro.potoki.bekon.agent.device.capture.CaptureOpts
import pro.potoki.bekon.agent.device.capture.ScreenCapture
import pro.potoki.bekon.agent.device.touch.A11yDump
import pro.potoki.bekon.agent.device.touch.TouchController
import pro.potoki.bekon.agent.device.touch.TouchService
import pro.potoki.bekon.agent.device.ime.BekonImeService
import org.json.JSONArray

/**
 * Real device executor. Wires the copied gateway device classes behind the
 * ActionExecutor interface so a single implementation serves HTTP and MCP.
 */
class DeviceExecutor(private val context: Context) : ActionExecutor {

    private var screenCapture: ScreenCapture? = null
    @Volatile private var captureStarted = false

    /** Provide the MediaProjection permission result (called when user consents). */
    fun onCapturePermission(data: Intent, resultCode: Int) {
        val sc = ScreenCapture(context)
        sc.setPermissionData(data, resultCode)
        sc.start()
        screenCapture = sc
        captureStarted = true
    }

    override fun health(): Map<String, Boolean> = mapOf(
        "capture" to (captureStarted && screenCapture != null),
        "touch" to TouchService.isBound(),
        "keyboard" to BekonImeService.isEnabled(context.contentResolver),
        "tunnel" to false, // filled by caller on the wire
    )

    override fun snapshot(): ScreenSnapshot {
        val controller = controller()
        val touch = controller != null
        if (!touch) throw CapabilityException("touch: accessibility service not enabled")
        val raw = A11yDump.dumpJson() ?: throw CapabilityException("snapshot: no accessibility tree")
        val arr = JSONArray(raw)
        val items = ArrayList<UiNode>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val b = o.optJSONArray("bounds")
            val hasBounds = b != null && b.length() == 4
            if (!hasBounds) continue
            val l = b.getInt(0); val t = b.getInt(1); val r = b.getInt(2); val bot = b.getInt(3)
            val w = r - l; val h = bot - t
            if (w <= 0 || h <= 0) continue
            val name = o.optString("text").ifBlank { o.optString("desc") }.ifBlank { "clickable" }
            val clickable = o.optBoolean("clickable", false)
            items.add(
                UiNode(
                    ref = "e${items.size + 1}",
                    label = name,
                    x = (l + r) / 2,
                    y = (t + bot) / 2,
                    bounds = intArrayOf(l, t, r, bot),
                    clickable = clickable,
                )
            )
        }
        val sc = screenCapture
        return ScreenSnapshot(
            captureW = sc?.width ?: 0,
            captureH = sc?.height ?: 0,
            screenW = sc?.width ?: 0,
            screenH = sc?.height ?: 0,
            items = items,
        )
    }

    override fun screenshot(): JpegFrame {
        val sc = screenCapture ?: throw CapabilityException("capture: media permission not granted")
        val frame = sc.capture(CaptureOpts(hiRes = true, scale = 0.8f, quality = 70))
            ?: throw CapabilityException("capture: no frame")
        return JpegFrame(frame.bytes, frame.width, frame.height)
    }

    override fun tap(x: Int, y: Int): Boolean {
        val c = controller() ?: throw CapabilityException("touch: accessibility service not enabled")
        return c.tap(x.toFloat(), y.toFloat())
    }

    override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Int): Boolean {
        val c = controller() ?: throw CapabilityException("touch: accessibility service not enabled")
        return c.swipe(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), ms.toLong())
    }

    override fun type(text: String): Boolean {
        val c = controller() ?: throw CapabilityException("touch: accessibility service not enabled")
        return c.inputAsKeys(text)
    }

    private fun controller(): TouchController? = TouchService.instance?.controller
}
