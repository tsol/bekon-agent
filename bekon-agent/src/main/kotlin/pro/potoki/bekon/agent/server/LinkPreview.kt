package pro.potoki.bekon.agent.server

/**
 * Link-preview / crawler detection for the GET-only link pages.
 *
 * Messengers (Telegram, WhatsApp, Slack, …) fetch a pasted URL to build a preview card. Such a fetch is NOT an
 * agent asking to connect: it gets a harmless page with Open Graph tags and never touches tickets, tokens,
 * nonces or the owner's Allow/Deny prompt. Real agent sessions start only when the agent opens /link/wait
 * (see LinkControl) or sends MCP JSON-RPC.
 */
object LinkPreview {
    /** Lower-case User-Agent fragments of preview bots and crawlers. Never a bare "bot": ChatGPT-User says "+openai.com/bot". */
    private val PREVIEW_UA = listOf(
        "telegrambot", "slackbot", "slack-imgproxy", "whatsapp", "facebookexternalhit", "facebot", "meta-externalagent",
        "twitterbot", "discordbot", "skypeuripreview", "skype", "viber", "googlebot", "google-inspectiontool",
        "googleother", "adsbot-google", "mediapartners-google", "bingbot", "bingpreview", "msnbot", "linkedinbot",
        "pinterest", "redditbot", "embedly", "iframely", "vkshare", "yandexbot", "yandex.com/bots", "applebot",
        "mastodon", "kakaotalk-scrap", "snapchat", "line-poker", "zoombot", "skypebot", "duckduckbot",
        "baiduspider", "petalbot", "ahrefsbot", "semrushbot", "mj12bot", "bytespider", "amazonbot", "ccbot",
        "gptbot", "oai-searchbot", "claudebot", "perplexitybot", "google-extended", "outbrain", "quora link preview",
        "nuzzel", "bitlybot", "tumblr", "flipboard", "qwantify", "seznambot", "rogerbot", "dotbot",
        "preview", "linkexpander", "unfurl", "opengraph", "metainspector", "w3c_validator",
    )

    /** User agents of chatbots that open links for a real user session (never treated as previews). */
    private val AGENT_UA = listOf(
        "chatgpt-user" to "chatgpt", "claude-user" to "claude", "perplexity-user" to "perplexity",
        "mistralai-user" to "mistral", "gemini" to "gemini", "copilot" to "copilot", "deepseek" to "deepseek",
        "grok" to "grok", "duckassistbot" to "duckassist", "youbot" to "you",
    )

    fun isPreview(ua: String?, header: (String) -> String? = { null }): Boolean {
        val purpose = listOf("Purpose", "Sec-Purpose", "X-Purpose", "X-Moz")
            .mapNotNull { header(it)?.lowercase() }
        if (purpose.any { "prefetch" in it || "preview" in it || "prerender" in it }) return true
        val u = ua?.lowercase()?.trim().orEmpty()
        if (u.isEmpty()) return false
        if (AGENT_UA.any { it.first in u }) return false
        return PREVIEW_UA.any { it in u }
    }

    /** Client name from a known agent User-Agent, else null (caller picks a neutral name). */
    fun agentName(ua: String?): String? {
        val u = ua?.lowercase().orEmpty()
        return AGENT_UA.firstOrNull { it.first in u }?.second
    }

    const val NEUTRAL_CLIENT = "web-agent"

    fun page(): String = """
        <!doctype html><html><head><meta charset=utf-8>
        <title>Bekon — phone session link</title>
        <meta property="og:title" content="Bekon — phone session link">
        <meta property="og:description" content="Open this link in your AI assistant. The phone owner approves it on the phone.">
        <meta property="og:type" content="website">
        <meta name="robots" content="noindex">
        </head><body>
        <h1>Bekon — phone session link</h1>
        <p>Open this link in your AI assistant. The phone owner approves the connection on the phone.</p>
        </body></html>
    """.trimIndent()
}
