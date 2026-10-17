package pro.potoki.bekon.agent.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pro.potoki.bekon.agent.access.ClientGate
import pro.potoki.bekon.agent.access.TrustedClients
import pro.potoki.bekon.agent.core.ActionExecutor
import pro.potoki.bekon.agent.core.JpegFrame
import pro.potoki.bekon.agent.core.ScreenSnapshot
import pro.potoki.bekon.agent.core.UiNode
import pro.potoki.bekon.agent.status.UserEvents
import java.util.concurrent.atomic.AtomicReference

class LinkControlDedupeTest {

    private fun n(
        ref: String, label: String, l: Int, t: Int, r: Int, b: Int,
        clickable: Boolean = false, window: String? = null, statusBar: Boolean = false,
    ) = UiNode(ref, label, (l + r) / 2, (t + b) / 2, intArrayOf(l, t, r, b), clickable, window = window, statusBar = statusBar)

    @Test
    fun `dedupe merges child label into clickable parent`() {
        val parent = n("e1", "Settings", 0, 100, 400, 200, clickable = true)
        val child = n("e2", "Settings", 20, 120, 200, 180, clickable = false)
        val empty = n("e3", "", 0, 300, 10, 310)
        val out = LinkControl.dedupe(listOf(parent, child, empty))
        assertEquals(1, out.size)
        assertTrue(out[0].clickable)
        assertEquals("Settings", out[0].label)
    }

    @Test
    fun `popup windows sorted first and marked`() {
        val app = n("a", "Row", 0, 500, 100, 560, clickable = true, window = "application")
        val popup = n("p", "Delete", 200, 400, 500, 480, clickable = true, window = "popup")
        val out = LinkControl.dedupe(listOf(app, popup))
        assertEquals("popup", out[0].window)
        assertEquals("Delete", out[0].label)
    }

    @Test
    fun `owner changed_mind banner and invalidates nonces`() {
        // Clear any leftover events from other tests
        UserEvents.drain()
        val items = AtomicReference(
            listOf(n("e1", "OK", 10, 10, 100, 80, clickable = true, window = "application")),
        )
        val ex = object : ActionExecutor {
            override fun health() = mapOf("capture" to true, "touch" to true)
            override fun snapshot() = ScreenSnapshot(1080, 2340, 270, 585, items.get())
            override fun screenshot(scale: Float, quality: Int, format: String) =
                JpegFrame(byteArrayOf(9), 1, 1, "image/jpeg", 1080, 2340)
            override fun tap(x: Int, y: Int) = true
            override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Int) = true
            override fun longPress(x: Int, y: Int) = true
            override fun drag(x: Int, y: Int) = true
            override fun release() = true
            override fun type(text: String) = true
            override fun key(name: String, n: Int) = true
            override fun clipboard(): String? = null
            override fun nav(action: String) = true
            override fun foreground() = mapOf("package" to "app")
        }
        val g = ClientGate(object : TrustedClients { override fun autoAcceptNext() = false }, { false }, ask = { _, _, _ -> true })
        val links = LinkControl(ex, g, { false }, { "https://ex.test" }, settleMs = 0, waitMs = 3_000)
        val start = links.handle("GET", "/link/start", mapOf("client" to "bot"))!!
        val ticket = Regex("""ticket=([A-Za-z0-9_-]+)""").find(start.html!!)!!.groupValues[1]
        val wait = links.handle("GET", "/link/wait", mapOf("ticket" to ticket))!!
        val token = Regex("""/s/([A-Za-z0-9_-]+)/screen""").find(wait.html!!)!!.groupValues[1]
        val screen = links.handle("GET", "/s/$token/screen", emptyMap())!!
        val tap = Regex("""href="https://ex\.test(/s/$token/do/[^"]+)">tap""").find(screen.html!!)!!.groupValues[1]

        UserEvents.add("new_goal", "Open Gmail instead")
        UserEvents.add("changed_mind")
        val next = links.handle("GET", "/s/$token/screen", emptyMap())!!
        assertTrue(next.html!!.contains("Message from the phone owner"))
        assertTrue(next.html!!.contains("Open Gmail instead"))
        assertTrue(next.html!!.contains("changed my mind") || next.html!!.contains("I changed my mind"))
        // Banner shown once: drained
        val again = links.handle("GET", "/s/$token/screen", emptyMap())!!
        assertFalse(again.html!!.contains("Open Gmail instead"))

        // Old nonce stale after changed_mind
        val reuse = links.handle("GET", tap, emptyMap())!!
        assertTrue(reuse.html!!.contains("stale") || reuse.html!!.contains("already used"))
    }

    @Test
    fun `screen html shows popup marker and shot first`() {
        UserEvents.drain()
        val items = listOf(
            n("p", "Share", 100, 200, 400, 280, clickable = true, window = "popup"),
            n("a", "Body", 0, 400, 200, 480, clickable = true, window = "application"),
        )
        val ex = object : ActionExecutor {
            override fun health() = mapOf("capture" to true, "touch" to true)
            override fun snapshot() = ScreenSnapshot(1080, 2340, 270, 585, items)
            override fun screenshot(scale: Float, quality: Int, format: String) =
                JpegFrame(byteArrayOf(1), 1, 1)
            override fun tap(x: Int, y: Int) = true
            override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Int) = true
            override fun longPress(x: Int, y: Int) = true
            override fun drag(x: Int, y: Int) = true
            override fun release() = true
            override fun type(text: String) = true
            override fun key(name: String, n: Int) = true
            override fun clipboard(): String? = null
            override fun nav(action: String) = true
            override fun foreground() = mapOf("package" to "app")
        }
        val g = ClientGate(object : TrustedClients { override fun autoAcceptNext() = false }, { false }, ask = { _, _, _ -> true })
        val links = LinkControl(ex, g, { false }, { "https://ex.test" }, settleMs = 0, waitMs = 3_000)
        val start = links.handle("GET", "/link/start", mapOf("client" to "w"))!!
        val ticket = Regex("""ticket=([A-Za-z0-9_-]+)""").find(start.html!!)!!.groupValues[1]
        val token = Regex("""/s/([A-Za-z0-9_-]+)/screen""")
            .find(links.handle("GET", "/link/wait", mapOf("ticket" to ticket))!!.html!!)!!.groupValues[1]
        val html = links.handle("GET", "/s/$token/screen", emptyMap())!!.html!!
        val imgAt = html.indexOf("<img id=\"shot\"")
        val elAt = html.indexOf("<h2>Elements</h2>")
        assertTrue(imgAt in 0 until elAt)
        assertTrue(html.contains("shot.jpg?v="))
        assertTrue(html.indexOf("[popup]") < html.indexOf("Body"))
    }
}
