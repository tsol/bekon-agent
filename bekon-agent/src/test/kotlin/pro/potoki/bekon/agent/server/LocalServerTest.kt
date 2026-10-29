package pro.potoki.bekon.agent.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import pro.potoki.bekon.agent.core.ActionExecutor
import pro.potoki.bekon.agent.core.CapabilityException
import pro.potoki.bekon.agent.core.JpegFrame
import pro.potoki.bekon.agent.core.ScreenSnapshot
import pro.potoki.bekon.agent.core.UiNode
import pro.potoki.bekon.agent.mcp.McpHandler

/** In-memory executor that records calls — no Android device involved. */
class FakeExecutor : ActionExecutor {
    var tapCalls = 0
    override fun health(): Map<String, Boolean> = mapOf(
        "capture" to true, "touch" to true, "keyboard" to true, "tunnel" to true,
    )
    override fun snapshot(): ScreenSnapshot = ScreenSnapshot(
        screenW = 1080, screenH = 2340, imageW = 540, imageH = 1170,
        items = listOf(UiNode("e1", "YouTube", 540, 900, intArrayOf(390, 810, 690, 990), true)),
    )
    override fun screenshot(scale: Float, quality: Int, format: String): JpegFrame =
        JpegFrame(byteArrayOf(1, 2, 3, 4), 10, 10)
    override fun tap(x: Int, y: Int): Boolean { tapCalls++; return true }
    override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Int): Boolean = true
    override fun longPress(x: Int, y: Int): Boolean = true
    override fun drag(x: Int, y: Int): Boolean = true
    override fun release(): Boolean = true
    override fun type(text: String): Boolean = true
    override fun key(name: String, n: Int): Boolean = true
    var clipboardText: String? = "copied"
    override fun clipboard(): String? = clipboardText
    override fun nav(action: String): Boolean = true
}

class LocalServerTest {

    @Test
    fun `robots, guide, GET mcp and HEAD answer 200 for readers`() = runBlocking {
        val port = freePort()
        val executor = FakeExecutor()
        val srv = LocalServer(port, executor, McpHandler(executor))
        srv.start()
        val client = HttpClient(CIO)
        val robots = client.get("http://127.0.0.1:$port/robots.txt")
        assertTrue("robots 200", robots.status.value == 200)
        val rb = robots.bodyAsText()
        assertTrue("robots allows user agents", rb.contains("User-agent: ChatGPT-User") && rb.contains("Allow: /") && rb.contains("ai-input=yes") && !rb.contains("Disallow: /"))
        val root = client.get("http://127.0.0.1:$port/") {
            header("User-Agent", "ChatGPT-User")
            header("Accept", "text/html,*/*")
        }.bodyAsText()
        assertTrue("connect block first", root.startsWith("# HOW TO CONNECT"))
        assertTrue("chatbot line first", root.contains("do NOT stop") && root.contains("/link/start"))
        assertTrue("no blocked wording up top", !root.lowercase().contains("access denied") && !root.lowercase().contains("forbidden"))
        // Plain GET /mcp as browser → HTML with absolute/relative link-start URL first
        val gm = client.get("http://127.0.0.1:$port/mcp") {
            header("User-Agent", "Mozilla/5.0 ChatGPT-User")
            header("Accept", "text/html,application/xhtml+xml")
        }
        val gmt = gm.bodyAsText()
        assertTrue("GET /mcp html 200", gm.status.value == 200)
        assertTrue("GET /mcp points to link-start", gmt.contains("/link/start") && gmt.contains("You can't use MCP from here"))
        assertTrue("GET /mcp is html", gmt.contains("<a href=") || gmt.contains("<!doctype html>"))
        // Real MCP client Accept keeps plain hint (not a dead end)
        val mcpPlain = client.get("http://127.0.0.1:$port/mcp") {
            header("Accept", "application/json, text/event-stream")
        }.bodyAsText()
        assertTrue("MCP client GET stays plain", mcpPlain.contains("POST JSON-RPC") && mcpPlain.contains("/link/start"))
        val linkStart = client.get("http://127.0.0.1:$port/link/start") {
            header("User-Agent", "ChatGPT-User")
        }
        // Without a ClientGate the handler returns 503 Session not ready — still reachable for ChatGPT-User (not 403/404).
        assertTrue(
            "GET /link/start reachable for ChatGPT-User (was ${linkStart.status.value})",
            linkStart.status.value in setOf(200, 503),
        )
        for (path in listOf("/", "/mcp", "/robots.txt", "/llms.txt", "/link/start")) {
            val h = client.request("http://127.0.0.1:$port$path") { method = io.ktor.http.HttpMethod.Head }
            assertTrue("HEAD $path 200 (was ${h.status.value})", h.status.value == 200)
        }
        client.close(); srv.stop()
    }

    @Test
    fun `Telegram preview of the link never prompts the owner, a real MCP initialize does`() = runBlocking {
        val port = freePort()
        val executor = FakeExecutor()
        val asks = java.util.concurrent.atomic.AtomicInteger()
        val gate = pro.potoki.bekon.agent.access.ClientGate(
            object : pro.potoki.bekon.agent.access.TrustedClients { override fun autoAcceptNext() = false },
            paused = { false }, ask = { _, _, _ -> asks.incrementAndGet(); true },
        )
        val srv = LocalServer(port, executor, McpHandler(executor, gate), gate = gate)
        srv.start()
        val client = HttpClient(CIO)
        for (ua in listOf("TelegramBot (like TwitterBot)", "WhatsApp/2.23.20.0 A", "Slackbot-LinkExpanding 1.0 (+https://api.slack.com/robots)")) {
            val r = client.get("http://127.0.0.1:$port/link/start") { header("User-Agent", ua) }
            val b = r.bodyAsText()
            assertTrue("preview 200 for $ua", r.status.value == 200)
            assertTrue("OG page for $ua", b.contains("og:title") && !b.contains("ticket="))
        }
        // Plain GETs of the landing pages never ask either.
        for (path in listOf("/", "/llms.txt", "/mcp", "/link/start")) client.get("http://127.0.0.1:$port$path")
        Thread.sleep(300)
        assertTrue("no owner prompt from previews/landing GETs (asks=${asks.get()})", asks.get() == 0)
        val init = client.post("http://127.0.0.1:$port/mcp") {
            contentType(ContentType.Application.Json)
            header(pro.potoki.bekon.agent.access.ClientGate.HEADER, "real-agent")
            setBody("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}""")
        }
        assertTrue("initialize ok", init.bodyAsText().contains("bekon-agent"))
        assertTrue("real agent connect asks once (asks=${asks.get()})", asks.get() == 1)
        client.close(); srv.stop()
    }

    @Test
    fun `session_info never prompts and initialize with a valid token is a silent reconnect`() = runBlocking {
        pro.potoki.bekon.agent.access.AgentConnections.clear()
        val port = freePort()
        val executor = FakeExecutor()
        val asks = java.util.concurrent.atomic.AtomicInteger()
        val gate = pro.potoki.bekon.agent.access.ClientGate(
            object : pro.potoki.bekon.agent.access.TrustedClients { override fun autoAcceptNext() = false },
            paused = { false }, ask = { _, _, _ -> asks.incrementAndGet(); true },
        )
        val srv = LocalServer(port, executor, McpHandler(executor, gate), gate = gate)
        srv.start()
        val client = HttpClient(CIO)
        val url = "http://127.0.0.1:$port/mcp"
        val hdr = pro.potoki.bekon.agent.access.ClientGate.HEADER
        suspend fun rpc(body: String, tok: String? = null) = client.post(url) {
            contentType(ContentType.Application.Json); header(hdr, "stateless-agent")
            tok?.let { header("Authorization", "Bearer $it") }; setBody(body)
        }.bodyAsText()
        val info = """{"jsonrpc":"2.0","id":9,"method":"tools/call","params":{"name":"session_info","arguments":{}}}"""
        val none = rpc(info)
        assertTrue(none, none.contains("\"alive\":false"))
        assertTrue(asks.get() == 0)
        val init = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"clientInfo":{"uniqueId":"stateless-agent"}}}"""
        val first = rpc(init)
        val tok = Regex("\"bekon_session_token\":\"([A-Za-z0-9_-]+)\"").find(first)!!.groupValues[1]
        assertTrue(asks.get() == 1)
        val alive = rpc(info, tok)
        assertTrue(alive, alive.contains("\"alive\":true") && !alive.contains(tok))
        val again = rpc(init, tok)
        // Token contract: every approved initialize result carries bekon_session_token (here: echoed back to its holder).
        assertTrue(again, again.contains("\"reconnected\"") && again.contains("\"bekon_session_token\":\"$tok\""))
        assertTrue("one prompt so far (asks=${asks.get()})", asks.get() == 1)
        // Lost-token recovery: the id alone never gets the OLD token back; the owner is asked again and Allow issues a new one.
        val idOnly = rpc(init)
        val tok2 = Regex("\"bekon_session_token\":\"([A-Za-z0-9_-]+)\"").find(idOnly)!!.groupValues[1]
        assertTrue("id-only init never gets the old token: $idOnly", !idOnly.contains(tok) && tok2 != tok)
        assertTrue("re-prompted (asks=${asks.get()})", asks.get() == 2)
        val stale = rpc(info, tok)
        assertTrue(stale, stale.contains("\"alive\":false"))
        client.close(); srv.stop()
    }

    @Test
    fun `stateless initialize while the owner decides returns approval_pending with the token, then approved`() = runBlocking {
        pro.potoki.bekon.agent.access.AgentConnections.clear()
        val port = freePort()
        val executor = FakeExecutor()
        val answerRef = java.util.concurrent.atomic.AtomicReference<Boolean?>(null)
        val gate = pro.potoki.bekon.agent.access.ClientGate(
            object : pro.potoki.bekon.agent.access.TrustedClients { override fun autoAcceptNext() = false },
            paused = { false }, holdMs = 200, ask = { _, _, _ -> while (answerRef.get() == null) Thread.sleep(5); answerRef.get()!! },
        )
        val srv = LocalServer(port, executor, McpHandler(executor, gate), gate = gate)
        srv.start()
        val client = HttpClient(CIO)
        val url = "http://127.0.0.1:$port/mcp"
        val init = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"clientInfo":{"uniqueId":"madisson-geo-2026"}}}"""
        suspend fun call(tok: String?) = client.post(url) {
            contentType(ContentType.Application.Json); header(pro.potoki.bekon.agent.access.ClientGate.HEADER, "madisson-geo-2026")
            tok?.let { header("Authorization", "Bearer $it") }; setBody(init)
        }
        val r1 = call(null)
        val b1 = r1.bodyAsText()
        assertTrue("HTTP 200 for pending: ${r1.status}", r1.status.value == 200)
        assertTrue(b1, b1.contains("\"code\":-32002") && b1.contains("approval_pending"))
        val tok = Regex("\"bekon_session_token\":\"([A-Za-z0-9_-]{43})\"").find(b1)!!.groupValues[1]
        assertTrue("token also in header", r1.headers["X-Bekon-Token"] == tok && r1.headers["X-Bekon-Approval"] == "pending")
        // Tools are refused with a pending token.
        val tl = client.post(url) { contentType(ContentType.Application.Json); header(pro.potoki.bekon.agent.access.ClientGate.HEADER, "madisson-geo-2026")
            header("Authorization", "Bearer $tok"); setBody("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""") }.bodyAsText()
        assertTrue(tl, tl.contains("approval_pending") && !tl.contains("\"tools\""))
        answerRef.set(true)
        Thread.sleep(50)
        val r2c = call(tok)
        val r2 = r2c.bodyAsText()
        assertTrue(r2, r2.contains("\"approval\":\"approved\""))
        assertTrue("approved result carries the token: $r2", r2.contains("\"bekon_session_token\":\"$tok\"") && r2c.headers["X-Bekon-Token"] == tok)
        // Id alone after approval: a NEW owner prompt (here: Deny) -- never the token.
        answerRef.set(false)
        val r3 = call(null)
        val b3 = r3.bodyAsText()
        assertTrue("id alone after approval: no token: $b3", !b3.contains(tok) && b3.contains("access declined"))
        client.close(); srv.stop()
    }

    @Test
    fun `health and snapshot and tap routes respond`() = runBlocking {
        // Full-flavor action links / tap tools; the playStore (guidance) build has none.
        org.junit.Assume.assumeFalse(pro.potoki.bekon.agent.BuildConfig.GUIDANCE_MODE)
        val port = freePort()
        val executor = FakeExecutor()
        val srv = LocalServer(port, executor, McpHandler(executor))
        srv.start()

        val client = HttpClient(CIO)
        val health = client.get("http://127.0.0.1:$port/health").bodyAsText()
        assertTrue("health ok", health.contains("\"ok\":true"))

        val snap = client.get("http://127.0.0.1:$port/snapshot").bodyAsText()
        assertTrue("snapshot has item", snap.contains("YouTube"))
        assertTrue("snapshot carries real screen size", snap.contains("1080") && snap.contains("2340"))
        assertTrue("snapshot carries image size", snap.contains("\"imageW\":540"))

        val tap = client.post("http://127.0.0.1:$port/tap") {
            contentType(ContentType.Application.Json)
            setBody("""{"x":12,"y":34}""")
        }.bodyAsText()
        assertTrue("tap ok", tap.contains("\"ok\":true"))
        assertTrue("executor tap hit", executor.tapCalls == 1)

        val mcp = client.post("http://127.0.0.1:$port/mcp") {
            contentType(ContentType.Application.Json)
            setBody("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")
        }.bodyAsText()
        assertTrue("mcp lists tools", mcp.contains("snapshot") && mcp.contains("screenshot"))

        val mcpCall = client.post("http://127.0.0.1:$port/mcp") {
            contentType(ContentType.Application.Json)
            setBody("""{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"snapshot","arguments":{}}}""")
        }.bodyAsText()
        assertTrue("mcp call returns content", mcpCall.contains("YouTube"))
        assertTrue("mcp call is envelope with content", mcpCall.contains("\"content\"") && mcpCall.contains("\"structuredContent\""))
        // tools/list should carry a non-empty inputSchema for tap
        val list = client.post("http://127.0.0.1:$port/mcp") {
            contentType(ContentType.Application.Json)
            setBody("""{"jsonrpc":"2.0","id":3,"method":"tools/list"}""")
        }.bodyAsText()
        assertTrue("tap schema has x integer", list.contains("\"x\":{\"type\":\"integer\"}"))
        assertTrue("swipe schema has ms optional", list.contains("\"ms\""))

        // No CORS: browser origins are not served
        val cors = client.get("http://127.0.0.1:$port/health") {
            header("Origin", "http://localhost:5181")
        }
        val allowOrigin = cors.headers["Access-Control-Allow-Origin"]
        assertTrue("no CORS allow-origin", allowOrigin == null)

        srv.stop()
        client.close()
    }

    private fun freePort(): Int {
        val s = java.net.ServerSocket(0)
        val p = s.localPort
        s.close()
        return p
    }
}
