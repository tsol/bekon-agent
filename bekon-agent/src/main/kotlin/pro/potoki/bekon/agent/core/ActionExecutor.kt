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
 * A snapshot of the screen: real screen size, the image size it was captured at, plus
 * ordered nodes. Coordinates of every node are in the *screen* space (screenW x screenH),
 * so a client that knows the screen size can tap precisely; if the screenshot is a
 * different size, `imageW`/`imageH` are provided so the client can scale.
 */
data class ScreenSnapshot(
    val screenW: Int,
    val screenH: Int,
    val imageW: Int,
    val imageH: Int,
    val items: List<UiNode>,
)

/** Byte payload + size for a screenshot. */
class JpegFrame(val bytes: ByteArray, val width: Int, val height: Int)

/** Thrown when a device capability is missing; maps to HTTP 409 / MCP isError. */
class CapabilityException(message: String) : Exception(message)

/** Navigation actions exposed as a tool (global accessibility actions, not app search). */
enum class NavAction(val wire: String) {
    HOME("home"), BACK("back"), RECENTS("recents");
    companion object {
        fun fromWire(s: String): NavAction? = entries.firstOrNull { it.wire == s }
    }
}

/** Key action exposed as a tool. */
enum class KeyAction(val wire: String) {
    BACKSPACE("backspace"), ENTER("enter"), CLEAR("clear"), SELECT_ALL("selectAll");
    companion object {
        fun fromWire(s: String): KeyAction? = entries.firstOrNull { it.wire == s }
    }
}

/**
 * The only place in the app that touches the device. HTTP routes and MCP tools both
 * call this. No second copy of snapshot/tap/type logic anywhere.
 * `type` sends keystrokes (not a whole-string paste); the editor tool sends key names.
 */
interface ActionExecutor {
    fun health(): Map<String, Boolean>
    fun snapshot(): ScreenSnapshot
    fun screenshot(): JpegFrame
    fun tap(x: Int, y: Int): Boolean
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Int): Boolean
    fun longPress(x: Int, y: Int): Boolean
    fun drag(x: Int, y: Int): Boolean
    fun release(): Boolean
    fun type(text: String): Boolean   // keystrokes (inputAsKeys)
    fun key(name: String, n: Int): Boolean
    fun nav(action: String): Boolean
}
