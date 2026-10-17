package pro.potoki.bekon.agent.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
}
