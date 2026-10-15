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

    fun root(url: String): String = url.trim().trimEnd('/').removeSuffix("/mcp")

    /** Very short, token-efficient prompt encoded in the QR and copied by "Copy for agent". */
    fun text(url: String): String =
        "Bekon: owner shares Android phone via MCP. Read ${root(url)}/ first."
}
