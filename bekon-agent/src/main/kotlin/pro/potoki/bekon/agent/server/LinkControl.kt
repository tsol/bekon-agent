package pro.potoki.bekon.agent.server

import pro.potoki.bekon.agent.access.AgentConnections
import pro.potoki.bekon.agent.access.ClientGate
import pro.potoki.bekon.agent.coach.Shape
import pro.potoki.bekon.agent.core.ActionExecutor
import pro.potoki.bekon.agent.core.CapabilityException
import pro.potoki.bekon.agent.core.UiNode
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * FALLBACK "control by links" for plain chatbots that can only open URLs (no POST, no headers, maybe no images).
 * MCP stays the primary path — see AgentGuide.connectBlock.
 *
 * Flow: /link/start?client=<name> -> owner Allow dialog -> /link/wait returns /s/<token>/screen.
 * Token is the same per-session ClientGate token (dies on Stop / replacement, never persisted); only in this
 * mode it travels in the URL path. Every action link carries a one-shot nonce bound to a snapshot.
 * Coach (highlight / arrow / hint / clear) is allowed in read-only: drawing only, same as MCP coach tools.
 */
class LinkControl(
    private val executor: ActionExecutor,
    private val gate: ClientGate?,
    private val readOnly: () -> Boolean,
    private val root: () -> String,
    private val apps: Apps? = null,
    private val coach: Coach? = null,
    private val settleMs: Long = 900,
    private val waitMs: Long = 25_000,
) {
    interface Apps {
        fun list(): List<Pair<String, String>> // label to package
        fun open(pkg: String)
    }
    interface Coach {
        fun draw(shapes: List<Shape>, append: Boolean = false): Boolean
        fun clear()
    }

    class Page(val status: Int, val html: String? = null, val jpeg: ByteArray? = null)

    private sealed class Act(val what: String) {
        class Tap(val x: Int, val y: Int, label: String) : Act("tapped \"$label\"")
        class Long(val x: Int, val y: Int, label: String) : Act("long-pressed \"$label\"")
        class Type(val x: Int, val y: Int, label: String) : Act("typed into \"$label\"")
        class Swipe(val x1: Int, val y1: Int, val x2: Int, val y2: Int, dir: String) : Act("scrolled $dir")
        class Nav(val action: String) : Act("nav $action")
        class Open(val pkg: String, label: String) : Act("opened \"$label\"")
        class Highlight(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val label: String) :
            Act("highlighted \"$label\"")
        class Arrow(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val label: String) :
            Act("pointed at \"$label\"")
        class Hint(val x: Float, val y: Float, val text: String) : Act("showed hint")
        object Clear : Act("cleared highlights")
    }

    private data class Ticket(val clientId: String, @Volatile var token: String? = null, @Volatile var err: String? = null)
    private data class Nonce(val token: String, val act: Act, val at: Long = System.currentTimeMillis())

    private val tickets = ConcurrentHashMap<String, Ticket>()
    private val nonces = ConcurrentHashMap<String, Nonce>()
    private val rnd = SecureRandom()
    private val MAX_NONCES = 400
    private val NONCE_TTL_MS = 10 * 60_000L

    fun handle(method: String, path: String, query: Map<String, String>): Page? {
        if (method != "GET" && method != "HEAD") return null
        when {
            path == "/link/start" -> return start(query["client"] ?: query["name"] ?: "")
            path == "/link/wait" -> return wait(query["ticket"] ?: "")
            path.matches(Regex("""^/s/[^/]+/screen/?$""")) -> {
                val tok = path.split('/')[2]
                return screen(tok, note = query["note"])
            }
            path.matches(Regex("""^/s/[^/]+/apps/?$""")) -> return appsPage(path.split('/')[2])
            path.matches(Regex("""^/s/[^/]+/shot\.jpe?g$""")) -> return shot(path.split('/')[2])
            path.matches(Regex("""^/s/[^/]+/do/[^/]+/?$""")) -> {
                val parts = path.trimEnd('/').split('/')
                return doAction(parts[2], parts[4], query)
            }
        }
        return null
    }

    private fun start(raw: String): Page {
        val client = raw.trim().take(48).ifEmpty { return html(400, "Need ?client=<your-name> (a short id for yourself).") }
        if (!client.matches(Regex("""[A-Za-z0-9._@+\-]{1,48}""")))
            return html(400, "client must be 1–48 chars: letters, digits, . _ @ + -")
        val g = gate ?: return html(503, "Session not ready.")
        val ticket = rand()
        val t = Ticket(client)
        tickets[ticket] = t
        Thread {
            try {
                when (val d = g.check(client, "link", null)) {
                    is ClientGate.Decision.Allow -> {
                        val tok = d.issuedToken ?: g.tokenFor(client)
                        if (tok != null) { t.token = tok; AgentConnections.mark(client) }
                        else t.err = "approved but no token"
                    }
                    is ClientGate.Decision.Reject -> {
                        // Already approved this session (MCP or earlier link): reuse the existing token.
                        if (d.reason == ClientGate.TOKEN_REQUIRED || d.status == 401) {
                            val tok = g.tokenFor(client)
                            if (tok != null) { t.token = tok; AgentConnections.mark(client) }
                            else t.err = d.reason
                        } else t.err = d.reason
                    }
                }
            } catch (e: Exception) { t.err = e.message ?: "error" }
        }.apply { isDaemon = true; name = "bekon-link-ask"; start() }
        val wait = abs("/link/wait?ticket=$ticket")
        return html(200, """
            <h1>Bekon — link control (fallback)</h1>
            <p>Ask the phone owner to tap <b>Allow</b> for client <code>${esc(client)}</code>.</p>
            <p>Then open (or refresh):</p>
            <p><a href="$wait">$wait</a></p>
            <p>MCP is preferred when you can POST. This link mode is only a fallback.</p>
        """.trimIndent())
    }


    private fun wait(ticket: String): Page {
        val t = tickets[ticket] ?: return html(404, "Unknown or expired ticket. Start again at /link/start?client=…")
        val deadline = System.currentTimeMillis() + waitMs
        while (System.currentTimeMillis() < deadline) {
            t.token?.let { tok ->
                tickets.remove(ticket)
                val screen = abs("/s/$tok/screen")
                return html(200, """
                    <h1>Approved</h1>
                    <p>Open this private session link (do not share it — it is your token):</p>
                    <p><a href="$screen">$screen</a></p>
                    <p>Refresh that page after each step. Stop on the phone ends the token.</p>
                """.trimIndent())
            }
            t.err?.let { e ->
                tickets.remove(ticket)
                return html(403, "Not approved: ${esc(e)}. Start again at /link/start?client=…")
            }
            Thread.sleep(400)
        }
        // Still pending — give a refresh link (ticket kept).
        val wait = abs("/link/wait?ticket=$ticket")
        return html(200, """
            <h1>Waiting for Allow…</h1>
            <p>Owner has not tapped Allow yet. Refresh:</p>
            <p><a href="$wait">$wait</a></p>
        """.trimIndent())
    }

    private fun auth(token: String): String? {
        val id = gate?.clientForToken(token) ?: return null
        AgentConnections.mark(id)
        return id
    }

    private fun screen(token: String, note: String? = null, usedStale: Boolean = false): Page {
        if (auth(token) == null) return html(401, "Invalid or expired session. Start again at /link/start?client=…")
        return try {
            AgentConnections.busy {
                val snap = executor.snapshot()
                val fg = executor.foreground()
                val pkg = fg["package"] ?: "?"
                val title = fg["title"] ?: fg["activity"] ?: ""
                val ro = readOnly()
                pruneNonces()
                val base = abs("/s/$token")
                val sb = StringBuilder()
                sb.append("<!doctype html><html><head><meta charset=utf-8><title>Bekon screen</title></head><body>\n")
                sb.append("<h1>Bekon screen</h1>\n")
                if (note != null) sb.append("<p><b>${esc(note)}</b></p>\n")
                if (usedStale) sb.append("<p><i>That link was already used or stale — showing current screen, no action ran.</i></p>\n")
                sb.append("<p>App: <code>${esc(pkg)}</code>")
                if (title.isNotBlank()) sb.append(" — ${esc(title)}")
                sb.append("</p>\n")
                if (ro) sb.append("<p><b>Read-only:</b> viewing + coach highlights only (no taps/typing).</p>\n")
                sb.append("<p>")
                sb.append(a("$base/screen", "Refresh"))
                sb.append(" · ").append(a("$base/apps", "Installed apps"))
                sb.append(" · ").append(a("$base/shot.jpg", "Screenshot image"))
                val clearN = put(token, Act.Clear)
                sb.append(" · ").append(a("$base/do/$clearN", "Clear highlights"))
                sb.append("</p>\n")
                if (!ro) {
                    sb.append("<p>Nav: ")
                    for (n in listOf("back", "home", "recents")) {
                        val id = put(token, Act.Nav(n))
                        sb.append(a("$base/do/$id", n)).append(" ")
                    }
                    val up = put(token, Act.Swipe(snap.screenW / 2, snap.screenH * 3 / 4, snap.screenW / 2, snap.screenH / 4, "up"))
                    val down = put(token, Act.Swipe(snap.screenW / 2, snap.screenH / 4, snap.screenW / 2, snap.screenH * 3 / 4, "down"))
                    sb.append(" · scroll ").append(a("$base/do/$up", "up")).append(" / ").append(a("$base/do/$down", "down"))
                    sb.append("</p>\n")
                }
                sb.append("<h2>Elements</h2>\n<ul>\n")
                for (n in snap.items.filter { !it.statusBar }) {
                    sb.append("<li>${esc(n.label.ifBlank { "(unnamed)" })}")
                    if (n.clickable || n.checkable) sb.append(" <i>[actionable]</i>")
                    if (n.checkable) sb.append(if (n.checked) " ✓" else " ○")
                    val b = n.bounds
                    if (b != null && b.size == 4) {
                        val hl = put(token, Act.Highlight(b[0].toFloat(), b[1].toFloat(), b[2].toFloat(), b[3].toFloat(), n.label))
                        val ar = put(token, Act.Arrow(b[0].toFloat(), (b[1] - 120).toFloat().coerceAtLeast(0f),
                            ((b[0] + b[2]) / 2f), ((b[1] + b[3]) / 2f), n.label))
                        val hint = put(token, Act.Hint(((b[0] + b[2]) / 2f), b[1].toFloat(), ""))
                        sb.append(" — coach: ")
                        sb.append(a("$base/do/$hl", "highlight")).append(" ")
                        sb.append(a("$base/do/$ar", "arrow")).append(" ")
                        sb.append(a("$base/do/$hint?text=" + enc("Look here: ${n.label.take(40)}"), "hint"))
                    }
                    if (!ro && n.enabled && (n.clickable || n.checkable)) {
                        val tap = put(token, Act.Tap(n.x, n.y, n.label))
                        val lp = put(token, Act.Long(n.x, n.y, n.label))
                        sb.append(" — ").append(a("$base/do/$tap", "tap")).append(" ").append(a("$base/do/$lp", "long-press"))
                        val ty = put(token, Act.Type(n.x, n.y, n.label))
                        sb.append(" ").append(a("$base/do/$ty?text=hello", "type?text="))
                    }
                    sb.append("</li>\n")
                }
                sb.append("</ul>\n")
                sb.append("<p><img src=\"$base/shot.jpg\" alt=\"screenshot\" style=\"max-width:100%;height:auto\"></p>\n")
                sb.append("<p><small>One-shot links. Stale/reuse shows this page with no action. Stop on the phone ends the token.</small></p>\n")
                sb.append("</body></html>")
                Page(200, sb.toString())
            }
        } catch (e: CapabilityException) {
            html(403, esc(e.message ?: "blocked"))
        } catch (e: Exception) {
            html(500, esc(e.message ?: "error"))
        }
    }

    private fun appsPage(token: String): Page {
        if (auth(token) == null) return html(401, "Invalid or expired session.")
        val list = try { apps?.list().orEmpty() } catch (_: Exception) { emptyList() }
        val ro = readOnly()
        val base = abs("/s/$token")
        val sb = StringBuilder()
        sb.append("<!doctype html><html><head><meta charset=utf-8><title>Apps</title></head><body>\n")
        sb.append("<h1>Installed apps</h1>\n<p>").append(a("$base/screen", "← Back to screen")).append("</p>\n<ul>\n")
        for ((label, pkg) in list.take(400)) {
            sb.append("<li>${esc(label)} <code>${esc(pkg)}</code>")
            if (!ro) {
                val id = put(token, Act.Open(pkg, label))
                sb.append(" — ").append(a("$base/do/$id", "open"))
            }
            sb.append("</li>\n")
        }
        sb.append("</ul></body></html>")
        return Page(200, sb.toString())
    }

    private fun shot(token: String): Page {
        if (auth(token) == null) return Page(401, "unauthorized")
        return try {
            AgentConnections.busy {
                val f = executor.screenshot(0.4f, 70, "jpeg")
                Page(200, jpeg = f.bytes)
            }
        } catch (e: CapabilityException) {
            html(403, esc(e.message ?: "blocked"))
        } catch (e: Exception) {
            html(500, esc(e.message ?: "error"))
        }
    }

    private fun doAction(token: String, nonce: String, query: Map<String, String>): Page {
        if (auth(token) == null) return html(401, "Invalid or expired session.")
        val n = nonces.remove(nonce)
        if (n == null || n.token != token || System.currentTimeMillis() - n.at > NONCE_TTL_MS) {
            return screen(token, usedStale = true)
        }
        val act = n.act
        // Coach is allowed in read-only; writes are not.
        val isCoach = act is Act.Highlight || act is Act.Arrow || act is Act.Hint || act is Act.Clear
        if (!isCoach && readOnly()) return screen(token, note = "Read-only: action ignored.")
        return try {
            AgentConnections.busy {
                when (act) {
                    is Act.Tap -> executor.tap(act.x, act.y)
                    is Act.Long -> executor.longPress(act.x, act.y)
                    is Act.Type -> {
                        val text = query["text"]?.let { URLDecoder.decode(it, "UTF-8") } ?: ""
                        if (text.isEmpty()) return@busy screen(token, note = "type needs ?text=")
                        executor.tap(act.x, act.y)
                        Thread.sleep(200)
                        executor.typeText(text, replace = false, asKeys = false)
                    }
                    is Act.Swipe -> executor.swipe(act.x1, act.y1, act.x2, act.y2, 350)
                    is Act.Nav -> executor.nav(act.action)
                    is Act.Open -> apps?.open(act.pkg) ?: throw CapabilityException("apps not available")
                    is Act.Highlight -> {
                        val c = coach ?: throw CapabilityException("coach: Gestures off")
                        c.draw(listOf(Shape(Shape.Kind.BOX, act.x1, act.y1, act.x2, act.y2, act.label.take(40))), append = true)
                    }
                    is Act.Arrow -> {
                        val c = coach ?: throw CapabilityException("coach: Gestures off")
                        c.draw(listOf(Shape(Shape.Kind.ARROW, act.x1, act.y1, act.x2, act.y2, act.label.take(40))), append = true)
                    }
                    is Act.Hint -> {
                        val c = coach ?: throw CapabilityException("coach: Gestures off")
                        val text = (query["text"]?.let { URLDecoder.decode(it, "UTF-8") } ?: "").ifBlank { "Look here" }.take(80)
                        c.draw(listOf(Shape(Shape.Kind.TEXT, act.x, act.y, act.x, act.y, text)), append = true)
                    }
                    is Act.Clear -> coach?.clear()
                }
                if (!isCoach) Thread.sleep(settleMs)
                screen(token, note = act.what)
            }
        } catch (e: CapabilityException) {
            screen(token, note = e.message ?: "blocked")
        } catch (e: Exception) {
            screen(token, note = e.message ?: "error")
        }
    }

    private fun put(token: String, act: Act): String {
        if (nonces.size > MAX_NONCES) pruneNonces(force = true)
        val id = rand()
        nonces[id] = Nonce(token, act)
        return id
    }

    private fun pruneNonces(force: Boolean = false) {
        val now = System.currentTimeMillis()
        nonces.entries.removeIf { now - it.value.at > NONCE_TTL_MS }
        if (force && nonces.size > MAX_NONCES / 2) {
            nonces.entries.sortedBy { it.value.at }.take(nonces.size - MAX_NONCES / 2).forEach { nonces.remove(it.key) }
        }
    }

    private fun rand(): String {
        val b = ByteArray(18).also(rnd::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    }

    private fun abs(path: String): String {
        val r = root().trimEnd('/')
        return if (r.isEmpty()) path else r + path
    }

    private fun a(href: String, label: String) = "<a href=\"${esc(href)}\">${esc(label)}</a>"
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private fun html(code: Int, body: String) = Page(code, "<!doctype html><html><head><meta charset=utf-8></head><body>$body</body></html>")
}
