package pro.potoki.bekon.agent.handoff

/** Compact machine+human readable handoff shared with an agent alongside the tunnel URL. */
object Handoff {
    /** Last known public tunnel URL (set by SessionService); used by the connection_info tool. */
    @Volatile var currentUrl: String? = null

    const val CORE_TOOLS =
        "snapshot, wait_for_change, screenshot, tap, swipe, type, coach_draw, coach_clear, annotate_screenshot, device_setup_guide, open_setup_screen, open_url, clipboard, shortcut_add, shortcut_remove, user_events"

    fun endpoint(url: String): String {
        val base = url.trim().trimEnd('/')
        return if (base.endsWith("/mcp")) base else "$base/mcp"
    }

    fun root(url: String): String = url.trim().trimEnd('/').removeSuffix("/mcp")

    /** Very short prompt encoded in the QR and copied by "Copy for agent". Leads with link mode for chatbots. */
    fun text(url: String): String {
        val r = root(url)
        val link = "$r/link/start?client=chatgpt"
        return "Bekon phone. If you can only open web pages (ChatGPT etc.), do NOT stop — open $link " +
            "(owner taps Allow, then use the action links). MCP (if you can POST): $r/mcp — guide: $r/"
    }
}
