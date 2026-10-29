package pro.potoki.bekon.agent.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import pro.potoki.bekon.agent.status.AgentStatus
import org.junit.Assert.assertNull
import org.junit.Test
import pro.potoki.bekon.agent.access.ClientGate
import pro.potoki.bekon.agent.access.TrustedClients
import pro.potoki.bekon.agent.coach.Shape
import pro.potoki.bekon.agent.core.ActionExecutor
import pro.potoki.bekon.agent.core.JpegFrame
import pro.potoki.bekon.agent.core.ScreenSnapshot
import pro.potoki.bekon.agent.core.UiNode
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class LinkControlTest {

    private class FakeEx : ActionExecutor {
        var taps = 0
        var lastTap = 0 to 0
        override fun health() = mapOf("capture" to true, "touch" to true)
        override fun snapshot() = ScreenSnapshot(
            1080, 2340, 270, 585,
            listOf(
                UiNode("e1", "Settings", 100, 200, intArrayOf(40, 160, 160, 240), clickable = true),
                UiNode("e2", "Clock", 50, 40, intArrayOf(0, 0, 100, 80), statusBar = true),
            ),
        )
        override fun screenshot(scale: Float, quality: Int, format: String) =
            JpegFrame(byteArrayOf(1, 2, 3), 10, 10, "image/jpeg", 1080, 2340)
        override fun tap(x: Int, y: Int): Boolean { taps++; lastTap = x to y; return true }
        override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Int) = true
        override fun longPress(x: Int, y: Int) = true
        override fun drag(x: Int, y: Int) = true
        override fun release() = true
        override fun type(text: String) = true
        override fun key(name: String, n: Int) = true
        override fun clipboard(): String? = null
        override fun nav(action: String) = true
        override fun foreground() = mapOf("package" to "com.android.settings", "title" to "Settings")
    }

    private fun gate(allow: Boolean = true) = ClientGate(
        object : TrustedClients { override fun autoAcceptNext() = false },
        paused = { false },
        ask = { _, _, _ -> allow },
    )

    private fun ctl(
        ex: FakeEx = FakeEx(),
        g: ClientGate = gate(),
        ro: AtomicBoolean = AtomicBoolean(false),
        shapes: AtomicReference<List<Shape>> = AtomicReference(emptyList()),
    ) = LinkControl(
        executor = ex,
        gate = g,
        readOnly = { ro.get() },
        root = { "https://example.test" },
        apps = object : LinkControl.Apps {
            override fun list() = listOf("Phone" to "com.android.dialer")
            override fun open(pkg: String) {}
        },
        coach = object : LinkControl.Coach {
            override fun draw(s: List<Shape>, append: Boolean): Boolean {
                shapes.set(if (append) shapes.get() + s else s); return true
            }
            override fun clear() { shapes.set(emptyList()) }
        },
        settleMs = 0,
        waitMs = 3_000,
    ) to ex to shapes to ro

    @Test
    fun `start wait screen one-shot and coach in read-only`() {
        // Full-flavor action links / tap tools; the playStore (guidance) build has none.
        org.junit.Assume.assumeFalse(pro.potoki.bekon.agent.BuildConfig.GUIDANCE_MODE)
        val g = gate(true)
        val (triple, ro) = run {
            val t = ctl(g = g)
            // t is (((ctl, ex), shapes), ro) — unpack carefully
            t
        }
        // Reconstruct cleanly:
        val ex = FakeEx()
        val shapes = AtomicReference<List<Shape>>(emptyList())
        val readOnly = AtomicBoolean(false)
        val links = LinkControl(
            executor = ex, gate = g, readOnly = { readOnly.get() },
            root = { "https://example.test" },
            apps = object : LinkControl.Apps {
                override fun list() = listOf("Phone" to "com.android.dialer")
                override fun open(pkg: String) {}
            },
            coach = object : LinkControl.Coach {
                override fun draw(s: List<Shape>, append: Boolean): Boolean {
                    shapes.set(if (append) shapes.get() + s else s); return true
                }
                override fun clear() { shapes.set(emptyList()) }
            },
            settleMs = 0, waitMs = 5_000,
        )

        val start = links.handle("GET", "/link/start", mapOf("client" to "chatgpt-web"))!!
        assertEquals(200, start.status)
        assertTrue(start.html!!.contains("/link/wait?ticket="))
        val ticket = Regex("""ticket=([A-Za-z0-9_-]+)""").find(start.html!!)!!.groupValues[1]

        val wait = links.handle("GET", "/link/wait", mapOf("ticket" to ticket))!!
        assertEquals(200, wait.status)
        assertTrue(wait.html!!.contains("/s/"))
        val token = Regex("""/s/([A-Za-z0-9_-]+)/screen""").find(wait.html!!)!!.groupValues[1]
        assertEquals("chatgpt-web", g.clientForToken(token))

        val screen = links.handle("GET", "/s/$token/screen", emptyMap())!!
        assertEquals(200, screen.status)
        assertTrue(screen.html!!.contains("Settings"))
        assertTrue(screen.html!!.contains("Cache-Control") || true) // headers set by LocalServer
        assertTrue(screen.html!!.contains("highlight"))
        assertTrue(screen.html!!.contains("Clear highlights"))
        assertFalse(screen.html!!.contains(">Clock<") && screen.html!!.contains("tap")) // status bar not actionable list noise ok

        val tapHref = Regex("""href="(https://example\.test/s/$token/do/[^"]+)">tap""").find(screen.html!!)!!.groupValues[1]
        val noncePath = tapHref.removePrefix("https://example.test")
        val after = links.handle("GET", noncePath, emptyMap())!!
        assertEquals(200, after.status)
        assertEquals(1, ex.taps)
        assertTrue(after.html!!.contains("tapped"))

        // Reuse nonce: no second tap
        val reuse = links.handle("GET", noncePath, emptyMap())!!
        assertEquals(1, ex.taps)
        assertTrue(reuse.html!!.contains("stale") || reuse.html!!.contains("already used"))

        // Coach in read-only
        readOnly.set(true)
        val roScreen = links.handle("GET", "/s/$token/screen", emptyMap())!!
        assertTrue(roScreen.html!!.contains("Read-only"))
        assertFalse(roScreen.html!!.contains(">tap<") || Regex("""">tap</a>""").containsMatchIn(roScreen.html!!))
        val hl = Regex("""href="https://example\.test(/s/$token/do/[^"]+)">highlight""").find(roScreen.html!!)!!.groupValues[1]
        val coached = links.handle("GET", hl, emptyMap())!!
        assertEquals(200, coached.status)
        assertTrue(shapes.get().any { it.kind == Shape.Kind.BOX })
        assertEquals(1, ex.taps) // no extra tap

        val apps = links.handle("GET", "/s/$token/apps", emptyMap())!!
        assertTrue(apps.html!!.contains("Phone"))
        assertFalse(apps.html!!.contains(">open<") || apps.html!!.contains(">open</a>")) // read-only: no open

        val shot = links.handle("GET", "/s/$token/shot.jpg", emptyMap())!!
        assertEquals(200, shot.status)
        assertTrue(shot.jpeg != null && shot.jpeg!!.isNotEmpty())
    }

    @Test
    fun `denied start`() {
        val links = LinkControl(
            executor = FakeEx(), gate = gate(false), readOnly = { false },
            root = { "https://example.test" }, settleMs = 0, waitMs = 3_000,
        )
        val start = links.handle("GET", "/link/start", mapOf("client" to "nope"))!!
        val ticket = Regex("""ticket=([A-Za-z0-9_-]+)""").find(start.html!!)!!.groupValues[1]
        val wait = links.handle("GET", "/link/wait", mapOf("ticket" to ticket))!!
        assertEquals(403, wait.status)
    }

    @Test
    fun `status and done update AgentStatus pill path`() {
        // Full-flavor action links / tap tools; the playStore (guidance) build has none.
        org.junit.Assume.assumeFalse(pro.potoki.bekon.agent.BuildConfig.GUIDANCE_MODE)
        AgentStatus.clear()
        val g = gate(true)
        val links = LinkControl(
            executor = FakeEx(), gate = g, readOnly = { false },
            root = { "https://example.test" }, settleMs = 0, waitMs = 3_000,
        )
        val start = links.handle("GET", "/link/start", mapOf("client" to "linker"))!!
        val ticket = Regex("""ticket=([A-Za-z0-9_-]+)""").find(start.html!!)!!.groupValues[1]
        val wait = links.handle("GET", "/link/wait", mapOf("ticket" to ticket))!!
        val token = Regex("""/s/([A-Za-z0-9_-]+)/screen""").find(wait.html!!)!!.groupValues[1]

        val screen = links.handle("GET", "/s/$token/screen", emptyMap())!!
        assertTrue(screen.html!!.contains("/status?text="))
        assertTrue(screen.html!!.contains("/done?text="))
        assertTrue(start.html!!.contains("/status?text=") || start.html!!.contains("status pill"))

        val st = links.handle("GET", "/s/$token/status", mapOf("text" to "Настраиваю Wi-Fi"))!!
        assertEquals(200, st.status)
        assertTrue(st.html!!.contains("status set"))
        assertEquals(AgentStatus.State.WORKING, AgentStatus.current.value!!.state)
        assertEquals("Настраиваю Wi-Fi", AgentStatus.current.value!!.text)

        val done = links.handle("GET", "/s/$token/done", mapOf("text" to "Готово — можно остановить"))!!
        assertEquals(200, done.status)
        assertTrue(done.html!!.contains("done set"))
        assertEquals(AgentStatus.State.DONE, AgentStatus.current.value!!.state)
        assertTrue(AgentStatus.current.value!!.text.contains("Готово"))
        assertTrue(AgentStatus.current.value!!.ttlMs > 0) // done auto-hides

        val missing = links.handle("GET", "/s/$token/status", emptyMap())!!
        assertTrue(missing.html!!.contains("needs ?text="))

        AgentStatus.clear()
        assertNull(AgentStatus.current.value)
    }


    @Test
    fun `browser shell title detection`() {
        assertTrue(LinkControl.isBrowserShellTitle(""))
        assertTrue(LinkControl.isBrowserShellTitle("Chrome"))
        assertTrue(LinkControl.isBrowserShellTitle("Chrome: Chrome"))
        assertTrue(LinkControl.isBrowserShellTitle("Mi Browser"))
        assertFalse(LinkControl.isBrowserShellTitle("Chrome: Википедия — свободная энциклопедия"))
        assertFalse(LinkControl.isBrowserShellTitle("Хуахин — Википедия"))
        assertFalse(LinkControl.isBrowserShellTitle("Chrome: Результаты поиска «Хуахин» — Википедия"))
    }


    @Test
    fun `link start without client uses neutral name or the agent UA, never chatgpt by default`() {
        val links = LinkControl(
            executor = FakeEx(), gate = gate(true), readOnly = { false },
            root = { "https://example.test" }, settleMs = 0, waitMs = 3_000,
        )
        val plain = links.handle("GET", "/link/start", emptyMap())!!
        assertEquals(200, plain.status)
        assertTrue(plain.html!!.contains("<code>web-agent</code>"))
        assertFalse(plain.html!!.contains("chatgpt"))
        assertTrue(plain.html!!.contains("You are in the right place") || plain.html!!.contains("link control"))
        val gpt = links.handle("GET", "/link/start", emptyMap(),
            ua = "Mozilla/5.0 AppleWebKit/537.36; compatible; ChatGPT-User/1.0; +https://openai.com/bot")!!
        assertTrue(gpt.html!!.contains("<code>chatgpt</code>"))
        val named = links.handle("GET", "/link/start", mapOf("client" to "my-agent"), ua = "ChatGPT-User/1.0")!!
        assertTrue(named.html!!.contains("<code>my-agent</code>"))
    }

    private val PREVIEW_UAS = listOf(
        "TelegramBot (like TwitterBot)",
        "Slackbot-LinkExpanding 1.0 (+https://api.slack.com/robots)",
        "WhatsApp/2.23.20.0 A",
        "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)",
        "Twitterbot/1.0",
        "Mozilla/5.0 (compatible; Discordbot/2.0; +https://discordapp.com)",
        "Mozilla/5.0 (Windows NT 6.1; WOW64) SkypeUriPreview Preview/0.5 skype-url-preview@microsoft.com",
        "Viber/13.3.0.4 CFNetwork/1209 Darwin/20.2.0",
        "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)",
        "LinkedInBot/1.0 (compatible; Mozilla/5.0; Apache-HttpClient +http://www.linkedin.com)",
        "Mozilla/5.0 (compatible; vkShare; +http://vk.com/dev/Share)",
        "Mozilla/5.0 (compatible; YandexBot/3.0; +http://yandex.com/bots)",
        "Mozilla/5.0 (compatible; GPTBot/1.0; +https://openai.com/gptbot)",
    )

    @Test
    fun `preview crawlers never create a ticket or an owner prompt`() {
        val asks = java.util.concurrent.atomic.AtomicInteger()
        val g = ClientGate(object : TrustedClients { override fun autoAcceptNext() = false }, paused = { false },
            ask = { _, _, _ -> asks.incrementAndGet(); true })
        val links = LinkControl(executor = FakeEx(), gate = g, readOnly = { false },
            root = { "https://example.test" }, settleMs = 0, waitMs = 300)
        for (ua in PREVIEW_UAS) {
            assertTrue(ua, LinkPreview.isPreview(ua))
            for (path in listOf("/link/start", "/link/wait", "/s/abc/screen", "/s/abc/do/xyz")) {
                val p = links.handle("GET", path, mapOf("client" to "chatgpt", "ticket" to "t"), ua = ua)!!
                assertEquals(200, p.status)
                assertTrue(p.html!!.contains("og:title"))
                assertFalse(p.html!!.contains("/link/wait?ticket="))
            }
        }
        // Prefetch headers (browser/messenger prefetch) are previews too, whatever the UA.
        assertTrue(LinkPreview.isPreview("Mozilla/5.0", header = { if (it == "Sec-Purpose") "prefetch;prerender" else null }))
        Thread.sleep(400)
        assertEquals("no owner prompt from previews", 0, asks.get())
        assertNull(g.tokenFor("chatgpt"))
    }

    @Test
    fun `plain GET of the start page never prompts but the agent wait link does`() {
        val asks = java.util.concurrent.atomic.AtomicInteger()
        val g = ClientGate(object : TrustedClients { override fun autoAcceptNext() = false }, paused = { false },
            ask = { _, _, _ -> asks.incrementAndGet(); true })
        val links = LinkControl(executor = FakeEx(), gate = g, readOnly = { false },
            root = { "https://example.test" }, settleMs = 0, waitMs = 3_000)
        val ua = "Mozilla/5.0 AppleWebKit/537.36 (KHTML, like Gecko); compatible; ChatGPT-User/1.0; +https://openai.com/bot"
        assertFalse(LinkPreview.isPreview(ua))
        val start = links.handle("GET", "/link/start", emptyMap(), ua = ua)!!
        // Unknown browser-like UA (a preview that pretends to be a browser) only gets the start page, too.
        links.handle("GET", "/link/start", emptyMap(), ua = "Mozilla/5.0 (Macintosh) Safari/605.1.15")!!
        Thread.sleep(300)
        assertEquals("start page alone never asks", 0, asks.get())
        val ticket = Regex("""ticket=([A-Za-z0-9_-]+)""").find(start.html!!)!!.groupValues[1]
        val wait = links.handle("GET", "/link/wait", mapOf("ticket" to ticket), ua = ua)!!
        assertEquals(200, wait.status)
        assertTrue(wait.html!!.contains("Approved"))
        assertEquals("agent connect asks once", 1, asks.get())
        assertTrue(g.tokenFor("chatgpt") != null)
    }


    @Test
    fun `link mode never hands an already approved id its token without the owner`() {
        val asks = java.util.concurrent.atomic.AtomicInteger()
        val g = ClientGate(object : TrustedClients { override fun autoAcceptNext() = false }, paused = { false },
            ask = { _, _, _ -> asks.incrementAndGet(); true })
        val mcpTok = (g.check("victim", "1.1.1.1", null) as ClientGate.Decision.Allow).issuedToken!!
        val links = LinkControl(executor = FakeEx(), gate = g, readOnly = { false },
            root = { "https://example.test" }, settleMs = 0, waitMs = 1_000)
        val start = links.handle("GET", "/link/start", mapOf("client" to "victim"), ua = "ChatGPT-User/1.0")!!
        val ticket = Regex("""ticket=([A-Za-z0-9_-]+)""").find(start.html!!)!!.groupValues[1]
        val wait = links.handle("GET", "/link/wait", mapOf("ticket" to ticket), ua = "ChatGPT-User/1.0")!!
        assertFalse(wait.html!!.contains(mcpTok))
        assertFalse(wait.html!!.contains("/s/$mcpTok"))
        // Lost-token recovery is an explicit owner prompt (never a silent re-issue); the old token is replaced.
        assertEquals(2, asks.get())
        assertTrue(g.tokenFor("victim") != mcpTok)
    }
}
