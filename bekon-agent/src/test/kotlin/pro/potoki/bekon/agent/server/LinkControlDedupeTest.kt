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

    @Test
    fun `identical non-actionable text deduped without overlap`() {
        val a = n("e1", "Bekon Agent", 40, 80, 300, 140, clickable = false, window = "application")
        val b = n("e2", "Bekon Agent", 40, 400, 300, 460, clickable = false, window = "application")
        val out = LinkControl.dedupe(listOf(a, b))
        assertEquals(1, out.size)
    }

    @Test
    fun `ime nodes collapsed to one keyboard line`() {
        UserEvents.drain()
        val items = listOf(
            n("k1", "q", 0, 1800, 100, 1900, window = "ime"),
            n("k2", "w", 100, 1800, 200, 1900, window = "ime"),
            n("f", "Search", 40, 200, 400, 280, clickable = true, window = "application"),
        )
        val ex = object : ActionExecutor {
            override fun health() = mapOf("capture" to true, "touch" to true)
            override fun snapshot() = ScreenSnapshot(1080, 2340, 270, 585, items)
            override fun screenshot(scale: Float, quality: Int, format: String) = JpegFrame(byteArrayOf(1), 1, 1)
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
        val start = links.handle("GET", "/link/start", mapOf("client" to "k"))!!
        val ticket = Regex("""ticket=([A-Za-z0-9_-]+)""").find(start.html!!)!!.groupValues[1]
        val token = Regex("""/s/([A-Za-z0-9_-]+)/screen""")
            .find(links.handle("GET", "/link/wait", mapOf("ticket" to ticket))!!.html!!)!!.groupValues[1]
        val html = links.handle("GET", "/s/$token/screen", emptyMap())!!.html!!
        assertTrue(html.contains("Keyboard is open"))
        assertTrue(html.contains("hide keyboard"))
        assertFalse(html.contains("[ime]"))
        assertTrue(html.contains("Search"))
        assertTrue(html.indexOf("<h2>Elements</h2>") < html.indexOf("<script>"))
    }

    @Test
    fun `prepareForLink drops system nav and clickable and truncates`() {
        val items = listOf(
            n("s1", "Назад", 800, 2200, 1000, 2340, clickable = true, window = "system"),
            n("s2", "Домой", 500, 2200, 700, 2340, clickable = true, window = "system"),
            n("s3", "Последние", 200, 2200, 400, 2340, clickable = true, window = "system"),
            n("s4", "Специальные возможности", 1000, 2200, 1080, 2340, clickable = true, window = "system"),
            n("s5", "Выключено, Голосовой ввод Google", 0, 2200, 120, 2340, clickable = true, window = "system"),
            n("c1", "clickable", 10, 10, 80, 80, clickable = true, window = "application"),
            n("m1", "A".repeat(200), 0, 100, 400, 400, clickable = true, window = "application"),
            n("ok", "Send", 0, 500, 100, 560, clickable = true, window = "application"),
        )
        val out = LinkControl.prepareForLink(items, imeOpen = true)
        assertTrue(out.none { it.window == "system" })
        assertTrue(out.none { it.label.equals("clickable", true) })
        assertTrue(out.any { it.label == "Send" })
        val long = out.first { it.label.startsWith("A") }
        assertTrue(long.label.length <= 120 && long.label.endsWith("…"))
    }

    @Test
    fun `action note has no html entities for quotes`() {
        UserEvents.drain()
        val items = listOf(n("e1", "More", 10, 10, 100, 80, clickable = true, window = "application"))
        val ex = object : ActionExecutor {
            override fun health() = mapOf("capture" to true, "touch" to true)
            override fun snapshot() = ScreenSnapshot(1080, 2340, 270, 585, items)
            override fun screenshot(scale: Float, quality: Int, format: String) = JpegFrame(byteArrayOf(1), 1, 1)
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
        val start = links.handle("GET", "/link/start", mapOf("client" to "n"))!!
        val ticket = Regex("""ticket=([A-Za-z0-9_-]+)""").find(start.html!!)!!.groupValues[1]
        val token = Regex("""/s/([A-Za-z0-9_-]+)/screen""")
            .find(links.handle("GET", "/link/wait", mapOf("ticket" to ticket))!!.html!!)!!.groupValues[1]
        val screen = links.handle("GET", "/s/$token/screen", emptyMap())!!.html!!
        val tap = Regex("""href="https://ex\.test(/s/$token/do/[^"]+)">tap""").find(screen)!!.groupValues[1]
        val after = links.handle("GET", tap, emptyMap())!!.html!!
        assertTrue(after.contains("tapped: More"))
        assertFalse(after.contains("&quot;"))
        assertFalse(after.contains("&amp;quot;"))
    }

    @Test
    fun `web content inside big WebView container is kept`() {
        val web = n("w", "", 0, 300, 1080, 2100, clickable = true, window = "application")
        val url = n("u", "ru.wikipedia.org", 100, 150, 900, 250, clickable = true, window = "application")
        val search = n("s", "Искать в Википедии", 60, 500, 1000, 600, clickable = true, window = "application")
        val link = n("l", "Заглавная страница", 60, 700, 600, 760, clickable = true, window = "application")
        val text = n("t", "Добро пожаловать в Википедию", 60, 800, 1000, 860, window = "application")
        val out = LinkControl.prepareForLink(listOf(web, url, search, link, text), imeOpen = false)
        val labels = out.map { it.label }
        assertTrue(labels.contains("ru.wikipedia.org"))
        assertTrue(labels.contains("Искать в Википедии"))
        assertTrue(labels.contains("Заглавная страница"))
        assertTrue(labels.contains("Добро пожаловать в Википедию"))
    }

    @Test
    fun `small unlabeled button takes child label`() {
        val btn = n("b", "", 0, 0, 120, 120, clickable = true)
        val lab = n("l", "Send", 10, 10, 110, 110)
        val out = LinkControl.dedupe(listOf(btn, lab))
        assertEquals(1, out.size); assertEquals("Send", out[0].label); assertTrue(out[0].clickable)
    }

    private fun linkSession(ex: ActionExecutor, apps: LinkControl.Apps? = null, ro: () -> Boolean = { false }): Pair<LinkControl, String> {
        val g = ClientGate(object : TrustedClients { override fun autoAcceptNext() = false }, { false }, ask = { _, _, _ -> true })
        val links = LinkControl(ex, g, ro, { "https://ex.test" }, apps = apps, settleMs = 0, waitMs = 3_000)
        val start = links.handle("GET", "/link/start", mapOf("client" to "o" + System.nanoTime() % 100000))!!
        val ticket = Regex("""ticket=([A-Za-z0-9_-]+)""").find(start.html!!)!!.groupValues[1]
        val token = Regex("""/s/([A-Za-z0-9_-]+)/screen""")
            .find(links.handle("GET", "/link/wait", mapOf("ticket" to ticket))!!.html!!)!!.groupValues[1]
        return links to token
    }

    private class Ex2 : ActionExecutor {
        val calls = ArrayList<String>()
        var scrollOk = false
        override fun health() = mapOf("capture" to true, "touch" to true)
        override fun snapshot() = ScreenSnapshot(1080, 2340, 270, 585, listOf(UiNode("f", "Поиск или URL", 540, 150, intArrayOf(100, 100, 980, 200), true)))
        override fun screenshot(scale: Float, quality: Int, format: String) = JpegFrame(byteArrayOf(1), 1, 1)
        override fun tap(x: Int, y: Int) = true.also { calls += "tap" }
        override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Int) = true.also { calls += "swipe:$y1>$y2" }
        override fun longPress(x: Int, y: Int) = true
        override fun drag(x: Int, y: Int) = true
        override fun release() = true
        override fun type(text: String) = true
        override fun typeText(text: String, replace: Boolean, asKeys: Boolean) = true.also { calls += "set:$text" }
        override fun key(name: String, n: Int) = true.also { calls += "key:$name" }
        override fun clipboard(): String? = null
        override fun nav(action: String) = true
        override fun foreground() = mapOf("package" to "com.sec.android.app.sbrowser")
        override fun scroll(forward: Boolean) = scrollOk.also { calls += "scroll:$forward" }
    }

    @Test
    fun `type with enter, scroll fallback, open url validation`() {
        UserEvents.drain()
        val ex = Ex2()
        val opened = ArrayList<String>()
        val apps = object : LinkControl.Apps {
            override fun list() = emptyList<Pair<String, String>>()
            override fun open(pkg: String) {}
            override fun openUrl(url: String) { opened += url }
        }
        val (links, token) = linkSession(ex, apps)
        val page = links.handle("GET", "/s/$token/screen", emptyMap())!!.html!!
        assertTrue(page.contains("/open?url="))
        val type = Regex("""href="https://ex\.test(/s/$token/do/[^"?]+)\?text=hello">type""").find(page)!!.groupValues[1]
        links.handle("GET", type, mapOf("text" to "wikipedia a+b", "enter" to "1"))
        assertTrue(ex.calls.contains("set:wikipedia a+b"))   // no double URL-decoding
        assertTrue(ex.calls.contains("key:enter"))
        val page2 = links.handle("GET", "/s/$token/screen", emptyMap())!!.html!!
        val down = Regex("""href="https://ex\.test(/s/$token/do/[^"]+)">down""").find(page2)!!.groupValues[1]
        links.handle("GET", down, emptyMap())
        assertTrue(ex.calls.contains("scroll:true"))
        assertTrue(ex.calls.any { it.startsWith("swipe:") && it.substringAfter(":").split(">").let { (a, b) -> a.toInt() > b.toInt() } })
        val bad = links.handle("GET", "/s/$token/open", mapOf("url" to "intent://x"))!!.html!!
        assertTrue(bad.contains("http/https only")); assertTrue(opened.isEmpty())
        links.handle("GET", "/s/$token/open", mapOf("url" to "https://ru.wikipedia.org/"))
        assertEquals(listOf("https://ru.wikipedia.org/"), opened)
        val fast = links.handle("GET", "/s/$token/open", mapOf("url" to "https://example.org/"))!!.html!!
        assertTrue(fast.contains("rate-limited")); assertEquals(1, opened.size)
    }

    @Test
    fun `open url refused in read-only`() {
        UserEvents.drain()
        val opened = ArrayList<String>()
        val apps = object : LinkControl.Apps {
            override fun list() = emptyList<Pair<String, String>>()
            override fun open(pkg: String) {}
            override fun openUrl(url: String) { opened += url }
        }
        val (links, token) = linkSession(Ex2(), apps, ro = { true })
        val r = links.handle("GET", "/s/$token/open", mapOf("url" to "https://example.org/"))!!.html!!
        assertTrue(r.contains("Read-only")); assertTrue(opened.isEmpty())
    }

    @Test
    fun `id-only names dropped for plain nodes, web ids stripped, pieces of link dropped`() {
        val bar = n("a", "control (id: action_bar_root)", 0, 0, 1080, 2340, window = "application")
        val hair = n("h", "image (id: toolbar_hairline)", 0, 200, 1080, 202, window = "application")
        val webBtn = n("w", "", 900, 300, 1000, 400, clickable = true, window = "application").copy(viewId = "searchButton", cls = "android.widget.Button")
        val link = n("l", "Харингтон, Кит английский актёр", 0, 500, 1080, 640, clickable = true, window = "application")
        val p1 = n("p1", "Харингтон, Кит", 40, 510, 600, 560, window = "application")
        val p2 = n("p2", "английский актёр", 40, 570, 600, 620, window = "application")
        val other = n("o", "Харингтон, Кит", 40, 1500, 600, 1560, window = "application")
        val out = LinkControl.prepareForLink(listOf(bar, hair, webBtn, link, p1, p2, other), imeOpen = false)
        val labels = out.map { it.label }
        assertTrue(labels.none { it.contains("action_bar_root") || it.contains("toolbar_hairline") })
        assertTrue(labels.contains("button"))
        assertEquals(1, labels.count { it == "Харингтон, Кит английский актёр" })
        assertTrue(labels.none { it == "Харингтон, Кит" }) // piece (and its same-window text twin) dropped
        assertTrue(labels.none { it == "английский актёр" })
    }

    @Test
    fun `hint line has no html entity and long pages paginate`() {
        UserEvents.drain()
        val many = (0 until 450).map { UiNode("n$it", "Item $it", 100, 10 + it * 5, intArrayOf(0, it * 5, 500, it * 5 + 4), true) }
        val ex = object : ActionExecutor by Ex2() {
            override fun snapshot() = ScreenSnapshot(1080, 2340, 270, 585, many)
        }
        val (links, token) = linkSession(ex)
        val p1 = links.handle("GET", "/s/$token/screen", emptyMap())!!.html!!
        assertTrue(!p1.contains("&amp;enter"))
        assertTrue(p1.contains("Item 199") && !p1.contains("Item 200<"))
        assertTrue(p1.contains("/screen?from=200"))
        val p3 = links.handle("GET", "/s/$token/screen", mapOf("from" to "400"))!!.html!!
        assertTrue(p3.contains("Item 449") && !p3.contains("More elements"))
    }

    private class Moving : ActionExecutor by Ex2() {
        val calls = ArrayList<String>()
        var items = listOf(
            UiNode("a", "Хуахин", 300, 400, intArrayOf(0, 350, 1080, 450), true),
            UiNode("b", "Хуан Карлос I", 300, 500, intArrayOf(0, 450, 1080, 550), true),
        )
        override fun snapshot() = ScreenSnapshot(1080, 2340, 270, 585, items)
        override fun tap(x: Int, y: Int) = true.also { calls += "tap:$x,$y" }
        override fun clickNode(bounds: IntArray, long: Boolean) = true.also { calls += "click:${bounds[1]}" }
    }

    @Test
    fun `tap re-finds moved element and refuses when gone`() {
        UserEvents.drain()
        val ex = Moving()
        val (links, token) = linkSession(ex)
        val page = links.handle("GET", "/s/$token/screen", emptyMap())!!.html!!
        val taps = Regex("""href="https://ex\.test(/s/$token/do/[^"]+)">tap""").findAll(page).map { it.groupValues[1] }.toList()
        // list re-lays out: Хуахин moves down
        ex.items = listOf(
            UiNode("b", "Хуан Карлос I", 300, 400, intArrayOf(0, 350, 1080, 450), true),
            UiNode("a", "Хуахин", 300, 500, intArrayOf(0, 450, 1080, 550), true),
        )
        links.handle("GET", taps[0], emptyMap())
        assertEquals(listOf("click:450", "tap:300,500"), ex.calls) // Хуахин at its NEW place; no change -> gesture fallback
        ex.items = listOf(UiNode("c", "Другое", 300, 400, intArrayOf(0, 350, 1080, 450), true))
        val r = links.handle("GET", taps[1], emptyMap())!!.html!!
        assertTrue(r.contains("element moved/gone"))
        assertEquals(2, ex.calls.size)
    }

    @Test
    fun `submit button finder prefers search label near field`() {
        val field = UiNode("f", "Искать в Википедии", 400, 150, intArrayOf(0, 100, 900, 200), true)
        val far = UiNode("x", "Найти", 500, 2000, intArrayOf(400, 1950, 600, 2050), true)
        val near = UiNode("y", "Search", 980, 150, intArrayOf(920, 100, 1060, 200), true)
        val text = UiNode("z", "Найти", 500, 1000, intArrayOf(400, 950, 600, 1050), false)
        assertEquals("Search", LinkControl.findSubmitButton(listOf(field, far, near, text), field)!!.label)
        assertEquals(null, LinkControl.findSubmitButton(listOf(field, text), field))
    }

    @Test
    fun `duplicate labels disambiguated and second one is tapped by gesture`() {
        UserEvents.drain()
        val ex = Moving()
        ex.items = listOf(
            UiNode("f", "Хуахин", 400, 150, intArrayOf(0, 100, 900, 200), true, pkg = "com.android.chrome", cls = "android.widget.EditText", editable = true),
            UiNode("s", "Хуахин", 300, 400, intArrayOf(0, 350, 1080, 450), true, pkg = "com.android.chrome", cls = "android.view.View"),
            UiNode("p", "Поиск страниц, содержащих Хуахин", 300, 600, intArrayOf(0, 550, 1080, 650), true, pkg = "com.android.chrome", cls = "android.view.View"),
        )
        val (links, token) = linkSession(ex)
        val page = links.handle("GET", "/s/$token/screen", emptyMap())!!.html!!
        assertTrue(page.contains("Хуахин (field)")); assertTrue(page.contains("Хуахин #2"))
        val taps = Regex("""href="https://ex\.test(/s/$token/do/[^"]+)">tap""").findAll(page).map { it.groupValues[1] }.toList()
        links.handle("GET", taps[1], emptyMap())
        assertEquals("tap:300,400", ex.calls.first()) // web: gesture at the suggestion's current center, not the field
    }

    @Test
    fun `search phrase row is a submit candidate`() {
        val items = listOf(
            UiNode("f", "Хуахин", 400, 150, intArrayOf(0, 100, 900, 200), true, editable = true),
            UiNode("s", "Хуахин", 300, 400, intArrayOf(0, 350, 1080, 450), true),
            UiNode("p", "Поиск страниц, содержащих Хуахин", 300, 600, intArrayOf(0, 550, 1080, 650), true),
        )
        assertEquals("p", LinkControl.findSearchPhrase(items, "Хуахин")!!.ref)
        assertEquals(null, LinkControl.findSearchPhrase(items, "Бангкок"))
        assertTrue(LinkControl.isWeb(items[2].copy(cls = "android.view.View")))
        assertTrue(!LinkControl.isWeb(UiNode("n", "OK", 1, 1, null, true, viewId = "com.x:id/ok", cls = "android.widget.Button")))
    }

    @Test
    fun `fingerprint ordinal separates identical labels`() {
        val a = UiNode("#0", "Item", 10, 10, intArrayOf(0, 0, 20, 20), true)
        val list = listOf(a.copy(ref = "x"), a.copy(ref = "y", y = 50), a.copy(ref = "z", y = 90))
        assertEquals("z", LinkControl.findFingerprint(list, a.copy(ref = "#2"))!!.ref)
        assertEquals("x", LinkControl.findFingerprint(list, a.copy(ref = "#7"))!!.ref) // out of range -> nearest
    }

    @Test
    fun `list container named after first row does not steal the tap point`() {
        // Chrome: clickable suggestion list (label resolved from its first row) + the clickable first row.
        val list = UiNode("L", "Хуахин", 540, 1300, intArrayOf(0, 400, 1080, 2200), true, cls = "android.view.View")
        val row = UiNode("r", "Хуахин", 540, 460, intArrayOf(0, 400, 1080, 520), true, cls = "android.view.View")
        val other = UiNode("o", "Хуан Карлос I король Испании", 540, 1300, intArrayOf(0, 1240, 1080, 1360), true, cls = "android.view.View")
        val out = LinkControl.prepareForLink(listOf(list, row, other), imeOpen = false)
        val h = out.single { it.label == "Хуахин" }
        assertEquals(460, h.y)
        // and a non-clickable inner text under a clickable container pins the tap to the text
        val txt = row.copy(clickable = false)
        val h2 = LinkControl.prepareForLink(listOf(list, txt, other), imeOpen = false).single { it.label == "Хуахин" }
        assertEquals(460, h2.y); assertTrue(h2.clickable)
    }

    @Test
    fun `junk web labels dropped, real items and coordinates kept`() {
        fun w(r: String, l: String, y: Int, id: String? = null) = UiNode(r, l, 100, y, intArrayOf(0, y - 20, 200, y + 20), true, viewId = id, cls = "android.view.View")
        val items = listOf(
            w("a", "H", 100), w("b", "Я", 200),
            w("c", "960px-Hua_Hin_hotels.jpg?utm_source=ru.wikipedia", 300),
            w("d", "https://example.org/x", 400), w("e", "logo.SVG", 500),
            w("f", "Бангкока", 600), w("g", "+", 700, id = "com.calc:id/plus"),
        )
        val out = LinkControl.prepareForLink(items, imeOpen = false)
        assertEquals(listOf("Бангкока", "+"), out.map { it.label })
        assertEquals(600, out[0].y)
    }

    @Test
    fun `stacked icon and caption merge, id-only controls dropped`() {
        val icon = UiNode("i", "Pause", 200, 1990, intArrayOf(140, 1940, 260, 2040), true)
        val cap = UiNode("c", "Pause", 200, 2150, intArrayOf(150, 2060, 250, 2120), false)
        val div = UiNode("d", "", 540, 900, intArrayOf(0, 898, 1080, 902), true, viewId = "com.android.chrome:id/divider_line_id", cls = "android.view.View")
        val btn = UiNode("b", "", 540, 500, intArrayOf(500, 460, 580, 540), true, viewId = "com.x:id/send", cls = "android.widget.ImageButton")
        val out = LinkControl.prepareForLink(listOf(icon, cap, div, btn), imeOpen = false)
        assertEquals(setOf("Pause", "button (id: send)"), out.map { it.label }.toSet()); assertEquals(2, out.size)
    }

    @Test
    fun `suggestion right under a same-text field is not merged into it`() {
        val field = UiNode("f", "Хуахин", 400, 150, intArrayOf(0, 100, 900, 200), true, editable = true)
        val row = UiNode("r", "Хуахин", 540, 260, intArrayOf(0, 220, 1080, 300), true)
        val rowText = UiNode("t", "Хуахин", 300, 260, intArrayOf(150, 230, 500, 290), false)
        val out = LinkControl.prepareForLink(listOf(field, row, rowText), imeOpen = false)
        assertEquals(2, out.size)
        assertTrue(out.all { it.clickable })
        assertEquals(1, out.count { it.editable })
    }
}
