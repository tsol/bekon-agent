package pro.potoki.bekon.agent.handoff

/** Compact machine+human readable handoff shared with an agent alongside the tunnel URL. */
object Handoff {
    /** Last known public tunnel URL (set by SessionService); used by the connection_info tool. */
    @Volatile var currentUrl: String? = null

    const val CORE_TOOLS =
        "snapshot, wait_for_change, screenshot, tap, swipe, type, coach_draw, coach_clear, annotate_screenshot, device_setup_guide, open_setup_screen"

    fun endpoint(url: String): String {
        val base = url.trim().trimEnd('/')
        return if (base.endsWith("/mcp")) base else "$base/mcp"
    }

    fun text(url: String): String =
        "Bekon Agent MCP (Android remote control). Endpoint: ${endpoint(url)}\n" +
            "User already shared the screen. Connect as MCP client (JSON-RPC over HTTP POST), list tools, then help the user.\n" +
            "Core tools: $CORE_TOOLS.\n" +
            "Confirm on-device if prompted. Stop when the user asks."
}
