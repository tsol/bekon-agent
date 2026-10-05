package pro.potoki.bekon.agent.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
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
    fun `health and snapshot and tap routes respond`() = runBlocking {
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

        // CORS: preflight OPTIONS must get allow-origin so the browser console works
        val cors = client.get("http://127.0.0.1:$port/health") {
            header("Origin", "http://localhost:5181")
        }
        val allowOrigin = cors.headers["Access-Control-Allow-Origin"]
        assertTrue("CORS allow-origin present", allowOrigin == "*")

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
