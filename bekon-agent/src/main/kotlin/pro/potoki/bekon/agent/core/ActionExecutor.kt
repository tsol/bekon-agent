package pro.potoki.bekon.agent.core

/** One clickable / labeled node from the accessibility tree or a future OCR source. */
data class UiNode(
    val ref: String,
    val label: String,
    val x: Int,
    val y: Int,
    val bounds: IntArray? = null, // [left, top, right, bottom]
    val clickable: Boolean = false,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val selected: Boolean = false,
    val enabled: Boolean = true,
    val pkg: String? = null,
    /** Status-bar clock/notification icons: shown, but never counted as a screen change. */
    val statusBar: Boolean = false,
)

/**
 * A snapshot of the screen: real screen size, the image size it was captured at, plus
 * ordered nodes. Coordinates of every node are in the *screen* space (screenW x screenH),
 * so a client that knows the screen size can tap precisely. `imageW`/`imageH` are the
 * pixel size of a default screenshot (scale 0.25), not the preview buffer. A screenshot
 * call can ask for another scale; its own width/height is the one to draw.
 */
data class ScreenSnapshot(
    val screenW: Int,
    val screenH: Int,
    val imageW: Int,
    val imageH: Int,
    val items: List<UiNode>,
)

/** Byte payload + size for a screenshot. Node taps stay in screen space, not these pixels. */
class JpegFrame(
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
    val mime: String = "image/jpeg",
    val screenW: Int = 0,
    val screenH: Int = 0,
)

/** What `screenshot` does when the caller omits scale, quality, or format. */
object ScreenshotDefaults {
    /** Four times smaller than the real screen on each side. */
    const val SCALE = 0.25f
    const val QUALITY = 70
    const val FORMAT = "jpeg"
}

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
    BACKSPACE("backspace"), ENTER("enter"), CLEAR("clear"), SELECT_ALL("selectAll"),
    COPY("copy"), CUT("cut"), PASTE("paste");
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
    fun screenshot(
        scale: Float = ScreenshotDefaults.SCALE,
        quality: Int = ScreenshotDefaults.QUALITY,
        format: String = ScreenshotDefaults.FORMAT,
    ): JpegFrame
    fun tap(x: Int, y: Int): Boolean
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Int): Boolean
    fun longPress(x: Int, y: Int): Boolean
    fun drag(x: Int, y: Int): Boolean
    fun release(): Boolean
    fun type(text: String): Boolean   // keystrokes (inputAsKeys)
    /** Accessibility text entry: SET_TEXT (placeholder-safe) -> paste -> keys. */
    fun typeText(text: String, replace: Boolean = false, asKeys: Boolean = false): Boolean = type(text)
    fun key(name: String, n: Int): Boolean
    /** Plain text on the system clipboard, or null when it is empty. */
    fun clipboard(): String?
    fun nav(action: String): Boolean
    /** Live UI-change signal from accessibility events; null = poll tree hashes only. */
    fun changeSignal(): ChangeSignal? = null
    /** Foreground package/activity/window title, best effort. */
    fun foreground(): Map<String, String> = emptyMap()
}

/** Counter of meaningful accessibility events (own overlay and status-bar ticks excluded). */
interface ChangeSignal {
    val seq: Long
    val lastEventAt: Long
    val lastReason: String
}
