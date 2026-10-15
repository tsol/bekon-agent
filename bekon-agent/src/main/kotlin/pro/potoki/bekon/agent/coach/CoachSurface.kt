package pro.potoki.bekon.agent.coach

import pro.potoki.bekon.agent.core.JpegFrame

/** Coach tools behind MCP: annotated screenshot (chat fallback) and live on-screen marks. */
interface CoachSurface {
    fun annotatedScreenshot(shapes: List<Shape>, scale: Float, quality: Int, format: String): JpegFrame
    /** false when the accessibility overlay is not available. */
    fun drawOnScreen(shapes: List<Shape>, append: Boolean, ttlMs: Long): Boolean
    fun clearScreen()
}
