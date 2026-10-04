package pro.potoki.bekon.agent.core

/** One clickable / labeled node from the accessibility tree or a future OCR source. */
data class UiNode(
    val ref: String,
    val label: String,
    val x: Int,
    val y: Int,
    val bounds: IntArray? = null, // [left, top, right, bottom]
    val clickable: Boolean = false,
)

/**
 * A snapshot of the screen: pixel size plus ordered nodes that an external agent
 * can tap. Mirrors the flat shape of `phone-control-api/src/snapshot.ts`.
 */
data class ScreenSnapshot(
    val captureW: Int,
    val captureH: Int,
    val screenW: Int,
    val screenH: Int,
    val items: List<UiNode>,
)

/** Byte payload + size for a screenshot. */
class JpegFrame(val bytes: ByteArray, val width: Int, val height: Int)

/** Thrown when a device capability is missing; maps to HTTP 409. */
class CapabilityException(message: String) : Exception(message)

/**
 * The only place in the app that touches the device. HTTP routes and MCP tools both
 * call this. No second copy of snapshot/tap/type logic anywhere.
 */
interface ActionExecutor {
    fun health(): Map<String, Boolean>
    fun snapshot(): ScreenSnapshot
    fun screenshot(): JpegFrame
    fun tap(x: Int, y: Int): Boolean
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Int): Boolean
    fun type(text: String): Boolean
}
