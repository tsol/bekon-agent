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
        /** http/https only; caller validates. */
        fun openUrl(url: String) { throw CapabilityException("open url not available") }
    }
    interface Coach {
        fun draw(shapes: List<Shape>, append: Boolean = false): Boolean
        fun clear()
    }

    class Page(val status: Int, val html: String? = null, val jpeg: ByteArray? = null)

    private sealed class Act(val what: String) {
        class Tap(val x: Int, val y: Int, label: String, val fp: UiNode? = null) : Act("tapped: ${label.take(80)}")
        class Long(val x: Int, val y: Int, label: String, val fp: UiNode? = null) : Act("long-pressed: ${label.take(80)}")
        class Type(val x: Int, val y: Int, label: String, val fp: UiNode? = null) : Act("typed into: ${label.take(80)}")
        class Swipe(val x1: Int, val y1: Int, val x2: Int, val y2: Int, val forward: Boolean, dir: String) : Act("scrolled $dir")
        class Nav(val action: String) : Act("nav $action")
        class Open(val pkg: String, label: String) : Act("opened: ${label.take(80)}")
        class Highlight(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val label: String, val fp: UiNode? = null) :
            Act("highlighted: ${label.take(80)}")
        class Arrow(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val label: String, val fp: UiNode? = null) :
            Act("pointed at: ${label.take(80)}")
        class Hint(val x: Float, val y: Float, val text: String, val fp: UiNode? = null) : Act("showed hint")
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
                return screen(tok, note = query["note"], from = query["from"]?.toIntOrNull() ?: 0)
            }
            path.matches(Regex("""^/s/[^/]+/apps/?$""")) -> return appsPage(path.split('/')[2])
            path.matches(Regex("""^/s/[^/]+/open/?$""")) -> return openUrl(path.split('/')[2], query["url"] ?: "")
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

    private fun screen(token: String, note: String? = null, usedStale: Boolean = false, from: Int = 0): Page {
        if (auth(token) == null) return html(401, "Invalid or expired session. Start again at /link/start?client=…")
        return try {
            AgentConnections.busy {
                val snap = executor.snapshot()
                val snapId = rand().take(10)
                val imeOpen = snap.items.any { it.window == "ime" }
                val items = prepareForLink(snap.items, imeOpen)
                val fg = executor.foreground()
                val pkg = fg["package"] ?: "?"
                val title = fg["title"] ?: fg["activity"] ?: ""
                val ro = readOnly()
                pruneNonces()
                val base = abs("/s/$token")
                val shotUrl = "$base/shot.jpg?v=$snapId"
                val sb = StringBuilder()
                sb.append("<!doctype html><html><head><meta charset=utf-8><title>Bekon screen</title></head><body>\n")
                sb.append("<h1>Bekon screen</h1>\n")
                appendOwnerBanner(sb, token)
                if (note != null) sb.append("<p><b>${esc(note)}</b></p>\n")
                if (usedStale) sb.append("<p><i>That link was already used or stale — showing current screen, no action ran.</i></p>\n")
                sb.append("<p>App: <code>${esc(pkg)}</code>")
                if (title.isNotBlank()) sb.append(" — ${esc(title)}")
                sb.append(" · ${items.size} elements</p>\n")
                if (ro) sb.append("<p><b>Read-only:</b> viewing + coach highlights only (no taps/typing).</p>\n")
                // Screenshot first (absolute URL + cache-bust). Harmless JS refreshes img for humans; page works without JS.
                sb.append("<p><img id=\"shot\" src=\"${esc(shotUrl)}\" alt=\"screenshot\" style=\"max-width:100%;height:auto;border:1px solid #ccc\"></p>\n")
                sb.append("<p>")
                sb.append(a("$base/screen", "Refresh"))
                sb.append(" · ").append(a("$base/apps", "Installed apps"))
                sb.append(" · ").append(a(shotUrl, "Screenshot image"))
                val clearN = put(token, Act.Clear)
                sb.append(" · ").append(a("$base/do/$clearN", "Clear highlights"))
                sb.append("</p>\n")
                if (!ro) {
                    sb.append("<p>Nav: ")
                    for (n in listOf("back", "home", "recents")) {
                        val id = put(token, Act.Nav(n))
                        sb.append(a("$base/do/$id", n)).append(" ")
                    }
                    // "down" = reveal content further down (finger moves up).
                    val down = put(token, Act.Swipe(snap.screenW / 2, snap.screenH * 7 / 10, snap.screenW / 2, snap.screenH * 3 / 10, true, "down"))
                    val up = put(token, Act.Swipe(snap.screenW / 2, snap.screenH * 3 / 10, snap.screenW / 2, snap.screenH * 7 / 10, false, "up"))
                    sb.append(" · scroll ").append(a("$base/do/$down", "down")).append(" / ").append(a("$base/do/$up", "up"))
                    sb.append("</p>\n")
                    sb.append("<p>Open a website: <code>${esc(base)}/open?url=https://…</code> (http/https only). ")
                    sb.append("Type into a field: its <i>type?text=</i> link; append the extra parameter <code>enter=1</code> (example: ...?text=hello and enter=1, joined with an ampersand) to submit.</p>\n")
                }
                sb.append("<h2>Elements</h2>\n<ul>\n")
                val allVisible = items.filter { it.window != "ime" }
                val start = from.coerceIn(0, maxOf(0, allVisible.size - 1))
                val visible = allVisible.drop(start).take(PAGE_SIZE)
                if (start > 0) sb.append("<li>").append(a("$base/screen", "↑ first elements")).append(" (showing ${start + 1}–${start + visible.size} of ${allVisible.size})</li>\n")
                if (imeOpen) {
                    sb.append("<li><b>[keyboard]</b> Keyboard is open — type with type?text= on a field")
                    if (!ro) {
                        val hide = put(token, Act.Nav("back"))
                        sb.append(" — ").append(a("$base/do/$hide", "hide keyboard"))
                    }
                    sb.append("</li>\n")
                }
                val fpOrd = HashMap<String, Int>(); val labOrd = HashMap<String, Int>()
                val ords = allVisible.associateWith { m -> fpOrd.merge(fpKey(m), 1, Int::plus)!! - 1 }
                val labelNo = allVisible.associateWith { m -> labOrd.merge(m.window + "\u0000" + m.label, 1, Int::plus)!! }
                val labelCount = labOrd
                for (n0 in visible) {
                    // Fingerprint carries the ordinal among identical (window,label,class,editable) nodes.
                    val n = n0.copy(ref = "#" + (ords[n0] ?: 0))
                    sb.append("<li>")
                    val win = n.window?.takeIf { it.isNotBlank() && it != "application" }
                    if (win != null) sb.append("<b>[${esc(win)}]</b> ")
                    sb.append(esc(displayLabel(n)))
                    if ((labelCount[n.window + "\u0000" + n.label] ?: 1) > 1) {
                        sb.append(esc(if (n.editable) " (field)" else " #${labelNo[n0] ?: 1}"))
                    } else if (n.editable) sb.append(" (field)")
                    if (n.clickable || n.checkable) sb.append(" <i>[actionable]</i>")
                    if (n.checkable) sb.append(if (n.checked) " ✓" else " ○")
                    val b = n.bounds
                    if (b != null && b.size == 4) {
                        val hl = put(token, Act.Highlight(b[0].toFloat(), b[1].toFloat(), b[2].toFloat(), b[3].toFloat(), n.label, n))
                        val ar = put(token, Act.Arrow(b[0].toFloat(), (b[1] - 120).toFloat().coerceAtLeast(0f),
                            ((b[0] + b[2]) / 2f), ((b[1] + b[3]) / 2f), n.label, n))
                        val hint = put(token, Act.Hint(((b[0] + b[2]) / 2f), b[1].toFloat(), "", n))
                        sb.append(" — coach: ")
                        sb.append(a("$base/do/$hl", "highlight")).append(" ")
                        sb.append(a("$base/do/$ar", "arrow")).append(" ")
                        sb.append(a("$base/do/$hint?text=" + enc("Look here: ${n.label.take(40)}"), "hint"))
                    }
                    if (!ro && n.enabled && (n.clickable || n.checkable)) {
                        val tap = put(token, Act.Tap(n.x, n.y, n.label, n))
                        val lp = put(token, Act.Long(n.x, n.y, n.label, n))
                        sb.append(" — ").append(a("$base/do/$tap", "tap")).append(" ").append(a("$base/do/$lp", "long-press"))
                        val ty = put(token, Act.Type(n.x, n.y, n.label, n))
                        sb.append(" ").append(a("$base/do/$ty?text=hello", "type?text="))
                    }
                    sb.append("</li>\n")
                }
                sb.append("</ul>\n")
                if (start + visible.size < allVisible.size) {
                    sb.append("<p>").append(a("$base/screen?from=${start + visible.size}", "More elements"))
                        .append(" (${allVisible.size - start - visible.size} more; ${start + visible.size} of ${allVisible.size} shown)</p>\n")
                }
                sb.append("<p><small>One-shot links. Stale/reuse shows this page with no action. Stop on the phone ends the token.</small></p>\n")
                sb.append("<script>(function(){var i=document.getElementById('shot');if(!i)return;setTimeout(function(){i.src='${esc(shotUrl)}&r=1';},800);})();</script>\n")
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
        sb.append("<h1>Installed apps</h1>\n")
        appendOwnerBanner(sb, token)
        sb.append("<p>").append(a("$base/screen", "← Back to screen")).append("</p>\n<ul>\n")
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
        if (gate?.isPaused() == true) return html(403, esc(ClientGate.PAUSED))
        return try {
            AgentConnections.busy {
                // Re-find the element in a fresh tree: lists re-layout between snapshot and click.
                val fp = when (act) { is Act.Tap -> act.fp; is Act.Long -> act.fp; is Act.Type -> act.fp
                    is Act.Highlight -> act.fp; is Act.Arrow -> act.fp; is Act.Hint -> act.fp; else -> null }
                val cur: UiNode? = if (fp != null) (refind(fp) ?: return@busy screen(token, note = "element moved/gone, pick again: ${fp.label.take(60)}")) else null
                val cb = cur?.bounds
                val before = if (act is Act.Tap || act is Act.Type || act is Act.Long) pageSig() else null
                var extraNote = ""
                when (act) {
                    is Act.Tap -> extraNote = tapSmart(cur, act.x, act.y)
                    is Act.Long -> if (cb == null || !nodeClick(cb, true)) executor.longPress(cur?.x ?: act.x, cur?.y ?: act.y)
                    is Act.Type -> {
                        // Ktor already URL-decodes query values; do not decode twice.
                        val text = query["text"] ?: ""
                        if (text.isEmpty()) return@busy screen(token, note = "type needs ?text=")
                        // Gesture tap on the field: real touch also brings the keyboard up (needed for its enter key).
                        executor.tap(cur?.x ?: act.x, cur?.y ?: act.y)
                        Thread.sleep(350) // focus lands; typeText waits up to 1.2 s more for an input-focused field
                        executor.typeText(text, replace = true, asKeys = false)
                        if (query["enter"] == "1" || query["submit"] == "1") { Thread.sleep(200); extraNote = "; " + submit(cur ?: fp, text) }
                    }
                    is Act.Swipe -> scrollVerified(act)
                    is Act.Nav -> executor.nav(act.action)
                    is Act.Open -> { (apps ?: throw CapabilityException("apps not available")).open(act.pkg); waitForPackage(act.pkg) }
                    is Act.Highlight -> {
                        val c = coach ?: throw CapabilityException("coach: Gestures off")
                        val bx = cb?.map { it.toFloat() } ?: listOf(act.x1, act.y1, act.x2, act.y2)
                        c.draw(listOf(Shape(Shape.Kind.BOX, bx[0], bx[1], bx[2], bx[3], act.label.take(40))), append = true)
                    }
                    is Act.Arrow -> {
                        val c = coach ?: throw CapabilityException("coach: Gestures off")
                        val tx = cur?.x?.toFloat() ?: act.x2; val ty = cur?.y?.toFloat() ?: act.y2
                        val sy = cb?.let { (it[1] - 120).toFloat().coerceAtLeast(0f) } ?: act.y1
                        c.draw(listOf(Shape(Shape.Kind.ARROW, cb?.get(0)?.toFloat() ?: act.x1, sy, tx, ty, act.label.take(40))), append = true)
                    }
                    is Act.Hint -> {
                        val c = coach ?: throw CapabilityException("coach: Gestures off")
                        val text = (query["text"] ?: "").ifBlank { "Look here" }.take(80)
                        val hx = cur?.x?.toFloat() ?: act.x; val hy = cb?.get(1)?.toFloat() ?: act.y
                        c.draw(listOf(Shape(Shape.Kind.TEXT, hx, hy, hx, hy, text)), append = true)
                    }
                    is Act.Clear -> coach?.clear()
                }
                if (act is Act.Tap || act is Act.Type || act is Act.Long) settleNav(before)
                else if (!isCoach && act !is Act.Open && act !is Act.Swipe) settleQuiet()
                screenWithin(token, act.what + extraNote)
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


    /**
     * Drain owner→agent pill-menu events (same queue MCP agents read). Shown once as a banner on the next
     * link page. [changed_mind] also drops every outstanding action nonce for this session token.
     */
    private fun pullOwnerBanner(token: String): String? {
        val ev = pro.potoki.bekon.agent.status.UserEvents.drain()
        if (ev.isEmpty()) return null
        if (ev.any { it.type == "changed_mind" }) {
            nonces.entries.removeIf { it.value.token == token }
        }
        val lines = ev.map { e ->
            when (e.type) {
                "changed_mind" -> "Owner: I changed my mind — cancel the current step. Old action links are now stale."
                "new_goal" -> "Owner new goal: " + (e.text ?: "(empty)")
                "back_to_app" -> "Owner: back in the Bekon app."
                "cancel_all" -> "Owner: cancel all — " + (e.text ?: "session paused/stopped")
                else -> "Owner: " + e.type + (e.text?.let { ": $it" } ?: "")
            }
        }
        return lines.joinToString(" ")
    }

    private fun appendOwnerBanner(sb: StringBuilder, token: String) {
        val msg = pullOwnerBanner(token) ?: return
        sb.append("<div style=\"background:#fff3cd;border:2px solid #d4a017;padding:12px;margin:8px 0;border-radius:8px\">")
        sb.append("<b>Message from the phone owner</b><br>").append(esc(msg))
        sb.append("</div>\n")
    }


    private val openTimes = ArrayDeque<Long>()

    /** Agent-built URL (cannot be one-shot): http/https only, read-only/pause respected, max 1 per 2 s and 20 per 10 min. */
    private fun openUrl(token: String, raw: String): Page {
        if (auth(token) == null) return html(401, "Invalid or expired session.")
        if (gate?.isPaused() == true) return html(403, esc(ClientGate.PAUSED))
        if (readOnly()) return screen(token, note = "Read-only: open url ignored.")
        val url = raw.trim()
        val uri = try { java.net.URI(url) } catch (_: Exception) { null }
        if (uri == null || (uri.scheme?.lowercase() != "http" && uri.scheme?.lowercase() != "https") || uri.host.isNullOrBlank())
            return screen(token, note = "open needs ?url=https://… (http/https only)")
        val now = System.currentTimeMillis()
        synchronized(openTimes) {
            while (openTimes.isNotEmpty() && now - openTimes.first() > 600_000) openTimes.removeFirst()
            if ((openTimes.isNotEmpty() && now - openTimes.last() < 2_000) || openTimes.size >= 20)
                return screen(token, note = "open url rate-limited; try again shortly")
            openTimes.addLast(now)
        }
        return try {
            AgentConnections.busy {
                (apps ?: throw CapabilityException("open url not available")).openUrl(url)
                settleQuiet(debounceMs = 700, maxMs = 3_000)
                screenWithin(token, "opened: " + url.take(100))
            }
        } catch (e: CapabilityException) { screen(token, note = e.message ?: "blocked") }
        catch (e: Exception) { screen(token, note = e.message ?: "error") }
    }

    /** After launching [pkg]: wait until it is in front, then for a quiet UI (popups/dialogs), max ~3.5 s total. */
    private fun waitForPackage(pkg: String) {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < 2_500) {
            if (executor.foreground()["package"] == pkg) break
            Thread.sleep(80)
        }
        val left = (3_000 - (System.currentTimeMillis() - start)).coerceAtLeast(600)
        settleQuiet(debounceMs = 600, maxMs = left)
    }



    /**
     * Tap the re-found element, verified by a page change. Web nodes: gesture tap first (ACTION_CLICK on Chrome
     * virtual nodes often returns true and does nothing), then ACTION_CLICK. Native: ACTION_CLICK first, then gesture.
     */
    private fun tapSmart(cur: UiNode?, x: Int, y: Int): String {
        val b = cur?.bounds
        if (cur == null || b == null) { executor.tap(x, y); return "" }
        val before = pageSig()
        if (isWeb(cur)) {
            executor.tap(cur.x, cur.y)
            if (waitChanged(before)) return ""
            if (nodeClick(b, false) && waitChanged(before)) return " (via click action)"
            return " (no visible change)"
        }
        if (nodeClick(b, false) && waitChanged(before)) return ""
        executor.tap(cur.x, cur.y)
        return if (waitChanged(before)) " (via gesture)" else " (no visible change)"
    }

    private fun nodeClick(b: IntArray, long: Boolean): Boolean = try { executor.clickNode(b, long) } catch (_: CapabilityException) { false }

    /** Find [fp] in a fresh snapshot: same window, label, view id and class; nearest to its old position. */
    private fun refind(fp: UiNode): UiNode? {
        val snap = try { executor.snapshot() } catch (_: Exception) { return null }
        return findFingerprint(prepareForLink(snap.items, snap.items.any { it.window == "ime" }), fp)
    }

    /**
     * Submit after typing, verifying each method by a page change: IME action → IME key event → injected key →
     * keyboard's enter key → a visible Search/Найти button. Returns which one worked for the note.
     */
    private fun submit(field: UiNode?, typed: String): String {
        var before = pageSig()
        for (m in listOf("ime_action", "keys", "inject", "ime_key")) {
            val acted = try { executor.enterVia(m) } catch (_: Exception) { false }
            if (!acted) continue
            if (waitChanged(before)) return "submitted via $m"
            before = pageSig()
        }
        // Keyboard may have closed after SET_TEXT: touch the field to bring it back, then press its enter key.
        if (field != null) {
            val f = refind(field) ?: field
            executor.tap(f.x, f.y)
            Thread.sleep(if (settleMs <= 0) 0 else 700)
            before = pageSig()
            if ((try { executor.enterVia("ime_key") } catch (_: Exception) { false }) && waitChanged(before)) return "submitted via keyboard enter key"
            before = pageSig()
        }
        val snap = try { executor.snapshot() } catch (_: Exception) { null }
        val prepared = snap?.let { prepareForLink(it.items, false) }
        val phrase = prepared?.let { findSearchPhrase(it, typed) }
        if (phrase != null) {
            executor.tap(phrase.x, phrase.y)
            if (waitChanged(before)) return "submitted via: ${phrase.label.take(50)}"
            before = pageSig()
        }
        val btn = prepared?.let { findSubmitButton(it, field) }
        if (btn != null) {
            val b = btn.bounds
            if (b == null || !nodeClick(b, false)) executor.tap(btn.x, btn.y)
            if (waitChanged(before)) return "submitted via button: ${btn.label.take(40)}"
        }
        return "enter sent but page did not change (try tapping a suggestion or the search button)"
    }

    private fun waitChanged(before: Int): Boolean {
        if (settleMs <= 0) return pageSig() != before
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < 1_500) {
            Thread.sleep(250)
            if (pageSig() != before) return true
        }
        return false
    }

    /** Cheap page signature: foreground title + text/bounds of the snapshot. */
    private fun pageSig(): Int = try { sigOf(executor.foreground()["title"], executor.snapshot()) } catch (_: Exception) { 0 }

    private fun sigOf(title: String?, snap: pro.potoki.bekon.agent.core.ScreenSnapshot): Int =
        31 * (title ?: "").hashCode() + snap.items.joinToString("|") { it.label + it.bounds?.joinToString(",") }.hashCode()

    /** Scroll action first; if the page did not change (WebView often reports success and stays put), swipe 75%→25%. */
    private fun scrollVerified(act: Act.Swipe): Boolean {
        val before = pageSig()
        val acted = try { executor.scroll(act.forward) } catch (_: CapabilityException) { false }
        if (acted) {
            Thread.sleep(450)
            if (pageSig() != before) { settleQuiet(debounceMs = 300, maxMs = 1_200); return true }
        }
        val ok = executor.swipe(act.x1, act.y1, act.x2, act.y2, 350)
        settleQuiet(debounceMs = 350, maxMs = 1_500)
        return ok
    }

    /**
     * After a tap that may navigate (web links): wait for a real change (title/content) and a quiet period,
     * and for a visible progress bar to go away; max ~4 s.
     */
    private fun settleNav(before: Int?) {
        if (settleMs <= 0) return // unit tests
        val start = System.currentTimeMillis()
        settleQuiet(debounceMs = 600, maxMs = 1_500)
        // One snapshot per round, at most ~every 500 ms: snapshots of long web pages are not free.
        while (System.currentTimeMillis() - start < NAV_SETTLE_MS) {
            val (sig, loading) = try {
                val fgPkg = executor.foreground()["package"]
                val snap = executor.snapshot()
                sigOf(executor.foreground()["title"], snap) to snap.items.any { (it.cls ?: "").contains("ProgressBar") && it.pkg == fgPkg }
            } catch (_: Exception) { return }
            if ((before == null || sig != before) && !loading) break
            settleQuiet(debounceMs = 400, maxMs = 500)
        }
    }

    /** Build the screen page but never let a slow snapshot stall the HTTP response (tunnels time out). */
    private fun screenWithin(token: String, note: String): Page {
        val f = java.util.concurrent.CompletableFuture.supplyAsync { screen(token, note = note) }
        return try { f.get(RESPONSE_BUDGET_MS, java.util.concurrent.TimeUnit.MILLISECONDS) }
        catch (_: java.util.concurrent.TimeoutException) {
            html(200, "<p><b>${esc(note)}</b></p><p>Done. The screen is still loading — " + a(abs("/s/$token/screen"), "Refresh") + ".</p>")
        }
    }

    /** Wait until accessibility goes quiet (~debounceMs) or maxMs elapses, then the next snapshot/shot is fresh. */
    private fun settleQuiet(debounceMs: Long = 500, maxMs: Long = 2_500) {
        val start = System.currentTimeMillis()
        var lastSeq = executor.changeSignal()?.seq ?: -1L
        var quietSince = start
        while (System.currentTimeMillis() - start < maxMs) {
            Thread.sleep(40)
            val seq = executor.changeSignal()?.seq ?: lastSeq
            if (seq != lastSeq) { lastSeq = seq; quietSince = System.currentTimeMillis() }
            if (System.currentTimeMillis() - quietSince >= debounceMs) break
        }
    }

    private fun abs(path: String): String {
        val r = root().trimEnd('/')
        return if (r.isEmpty()) path else r + path
    }

    private fun a(href: String, label: String) = "<a href=\"${esc(href)}\">${esc(label)}</a>"
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private fun html(code: Int, body: String) = Page(code, "<!doctype html><html><head><meta charset=utf-8></head><body>$body</body></html>")
    companion object {
        const val PAGE_SIZE = 200
        private const val NAV_SETTLE_MS = 4_000L
        private const val RESPONSE_BUDGET_MS = 5_000L
        private val ID_NAME = Regex("""^(button|field|image|toggle|dropdown|control)( \(id: [^)]*\))?$""")
        private val NAV_LABELS = setOf(
            "назад", "back", "домой", "home", "последние", "recents", "recent apps", "overview",
        )
        private val A11Y_LABELS = listOf("специальные возможности", "accessibility")

        /** Dedupe + drop system chrome / bare "clickable" + truncate long bubbles. */
        fun prepareForLink(items: List<pro.potoki.bekon.agent.core.UiNode>, imeOpen: Boolean): List<pro.potoki.bekon.agent.core.UiNode> =
            dedupe(items).mapNotNull { n ->
                if (isSystemNoise(n, imeOpen)) return@mapNotNull null
                if (n.window == "ime") return@mapNotNull null
                val lab = polishLabel(n) ?: return@mapNotNull null
                if (lab == n.label) n else n.copy(label = lab)
            }.let(::dropPieces)

        /** Drop plain text that is just a piece of a nearby actionable label (link "Name, description" then its parts). */
        fun fpKey(n: UiNode) = listOf(n.window, n.label, n.viewId, n.cls, n.editable).joinToString("\u0000")

        /** Same window/label/id/class/editable; the ordinal among those decides between duplicates, else nearest. */
        fun findFingerprint(items: List<UiNode>, fp: UiNode): UiNode? {
            val k = fpKey(fp)
            val c = items.filter { fpKey(it) == k }
            val ord = fp.ref.removePrefix("#").toIntOrNull()
            if (ord != null && fp.ref.startsWith("#") && ord < c.size) return c[ord]
            return c.minByOrNull { (it.x - fp.x) * (it.x - fp.x) + (it.y - fp.y) * (it.y - fp.y) }
        }

        private val BROWSERS = setOf("com.android.chrome", "com.chrome.beta", "com.sec.android.app.sbrowser", "org.mozilla.firefox",
            "com.yandex.browser", "com.opera.browser", "com.microsoft.emmx", "com.brave.browser", "com.duckduckgo.mobile.android")

        /** Web content (Chrome/WebView virtual nodes): ACTION_CLICK often "succeeds" without navigating. */
        fun isWeb(n: UiNode): Boolean =
            (n.viewId != null && !n.viewId.contains(":id/")) || (n.cls == "android.view.View") ||
                (n.pkg in BROWSERS && n.viewId == null)

        private val SEARCH_PHRASE = Regex("""поиск|search|containing|содерж|искать""", RegexOption.IGNORE_CASE)

        /** Wikipedia-style "Поиск страниц, содержащих <typed>" row. */
        fun findSearchPhrase(items: List<UiNode>, typed: String): UiNode? {
            val t = typed.trim().lowercase()
            if (t.isEmpty()) return null
            return items.firstOrNull { it.clickable && !it.editable && it.window != "ime" && it.window != "system" &&
                it.label.lowercase().contains(t) && it.label.trim().lowercase() != t && SEARCH_PHRASE.containsMatchIn(it.label) }
        }

        private val SUBMIT = Regex("""^(найти|искать|поиск|search|go|перейти|submit|отправить|send|ok)$""", RegexOption.IGNORE_CASE)

        /** A visible actionable Search/Найти-like button, nearest to the field. */
        fun findSubmitButton(items: List<UiNode>, field: UiNode?): UiNode? =
            items.filter { (it.clickable) && it.window != "ime" && it.window != "system" && SUBMIT.matches(it.label.trim()) }
                .minByOrNull { if (field == null) 0 else kotlin.math.abs(it.y - field.y) * 4 + kotlin.math.abs(it.x - field.x) }

        fun dropPieces(items: List<pro.potoki.bekon.agent.core.UiNode>): List<pro.potoki.bekon.agent.core.UiNode> {
            val out = ArrayList<pro.potoki.bekon.agent.core.UiNode>()
            for (n in items) {
                val k = n.label.trim().lowercase()
                if (!n.clickable && !n.checkable && k.length >= 2) {
                    val nb = n.bounds
                    val dup = out.takeLast(8).any { p ->
                        (p.clickable || p.checkable) && p.window == n.window && p.label.lowercase().contains(k) &&
                            p.bounds != null && nb != null && nb.size == 4 && p.bounds.size == 4 &&
                            nb[0] < p.bounds[2] && p.bounds[0] < nb[2] && nb[1] < p.bounds[3] && p.bounds[1] < nb[3]
                    }
                    if (dup) continue
                }
                out += n
            }
            return out
        }

        fun dedupe(items: List<pro.potoki.bekon.agent.core.UiNode>): List<pro.potoki.bekon.agent.core.UiNode> {
            fun key(n: pro.potoki.bekon.agent.core.UiNode) = n.label.trim().lowercase()
            fun area(b: IntArray?) = if (b == null || b.size != 4) 0 else (b[2] - b[0]).coerceAtLeast(0) * (b[3] - b[1]).coerceAtLeast(0)
            fun contains(a: IntArray, b: IntArray) = a[0] <= b[0] && a[1] <= b[1] && a[2] >= b[2] && a[3] >= b[3]
            fun overlaps(a: IntArray, b: IntArray) = a[0] < b[2] && b[0] < a[2] && a[1] < b[3] && b[1] < a[3]
            fun informative(n: pro.potoki.bekon.agent.core.UiNode): Boolean {
                if (n.statusBar) return false
                val lab = n.label.trim()
                if (lab.isEmpty() || lab == "(unnamed)") return n.clickable || n.checkable
                if (lab.length <= 1 && !n.clickable && !n.checkable) return false
                return true
            }
            fun better(a: pro.potoki.bekon.agent.core.UiNode, b: pro.potoki.bekon.agent.core.UiNode): pro.potoki.bekon.agent.core.UiNode {
                if (a.clickable != b.clickable) return if (a.clickable) a else b
                if (a.checkable != b.checkable) return if (a.checkable) a else b
                // Same clickability: the smaller node is the precise target (a list container named after its first row
                // has the same text but its center lands on another row — 1.0.25 tapped "Хуан Карлос I" for "Хуахин").
                return if (area(a.bounds) <= area(b.bounds)) a else b
            }
            val src = items.filter(::informative)
            val kept = ArrayList<pro.potoki.bekon.agent.core.UiNode>()
            for (n in src) {
                val nb = n.bounds
                var merged = false
                for (i in kept.indices) {
                    val k = kept[i]
                    val kb = k.bounds
                    val sameText = key(n).isNotEmpty() && key(n) == key(k)
                    val nest = nb != null && kb != null && nb.size == 4 && kb.size == 4 &&
                        (contains(kb, nb) || contains(nb, kb) || (sameText && overlaps(kb, nb)))
                    // Merge only true duplicates: same text overlapping, or an unlabeled container around a labeled child.
                    // (1.0.20 merged ANY nested pair, so a big WebView/list swallowed all page text, links and fields.)
                    // Unlabeled wrapper absorbs a labeled child only when it is about the child's size (icon button +
                    // label); big containers (WebView, lists, toolbars) never swallow their content.
                    val emptyWrap = (key(n).isEmpty() || key(k).isEmpty()) && nb != null && kb != null &&
                        (contains(kb, nb) || contains(nb, kb)) &&
                        maxOf(area(kb), area(nb)) <= 4 * maxOf(1, minOf(area(kb), area(nb)))
                    if (nest && (sameText || emptyWrap)) {
                        kept[i] = better(k, n).let { win ->
                            val w = listOfNotNull(k.window, n.window).firstOrNull { it != "application" } ?: win.window
                            val lab = if (win.label.isBlank()) (if (k.label.isNotBlank()) k.label else n.label) else win.label
                            val small = if (area(k.bounds) <= area(n.bounds)) k else n
                            val big = if (small === k) n else k
                            // Clickable container + same-text inner node: keep the action, but tap the inner node's spot.
                            val pin = sameText && win === big && area(big.bounds) >= 4 * maxOf(1, area(small.bounds))
                            if (pin) win.copy(window = w, label = lab, bounds = small.bounds, x = small.x, y = small.y)
                            else win.copy(window = w, label = lab)
                        }
                        merged = true
                        break
                    }
                }
                if (!merged) kept.add(n)
            }
            val plain = ArrayList<pro.potoki.bekon.agent.core.UiNode>()
            for (n in kept) {
                val actionable = n.clickable || n.checkable
                val dup = !actionable && key(n).isNotEmpty() && plain.any {
                    !it.clickable && !it.checkable && key(it) == key(n) &&
                        (it.window ?: "application") == (n.window ?: "application")
                }
                if (!dup) plain.add(n)
            }
            val rank = { w: String? -> when (w) { "popup" -> 0; "system" -> 1; "other" -> 2; "ime" -> 3; else -> 4 } }
            return plain.sortedWith(compareBy({ rank(it.window) }, { it.bounds?.getOrNull(1) ?: it.y }, { it.bounds?.getOrNull(0) ?: it.x }))
        }

        /** System nav / a11y / IME-switcher — covered by Nav + keyboard lines. */
        fun isSystemNoise(n: pro.potoki.bekon.agent.core.UiNode, imeOpen: Boolean): Boolean {
            if (n.window != "system") return false
            val lab = n.label.trim().lowercase()
            if (lab in NAV_LABELS) return true
            if (A11Y_LABELS.any { it in lab }) return true
            if ("голосовой" in lab || "voice" in lab || ("ввод" in lab && "google" in lab) ||
                "gboard" in lab || "keyboard" in lab || "клавиатур" in lab) return true
            return true // drop remaining system chrome
        }

        /** Null => omit from the link list. */
        fun polishLabel(n: pro.potoki.bekon.agent.core.UiNode): String? {
            var lab = n.label.trim()
            val actionable = n.clickable || n.checkable
            if (lab.isEmpty() || lab == "(unnamed)" || lab.equals("clickable", ignoreCase = true) || ID_NAME.matches(lab)) {
                // Names from view-id/class only: never for plain (non-actionable) nodes; web ids carry no meaning.
                if (!actionable) return null
                val webId = n.viewId != null && !n.viewId.contains(":id/")
                lab = pro.potoki.bekon.agent.core.NodeNameResolver.fromIdClass(if (webId) null else n.viewId, n.cls)
                    ?: return null
            }
            if (isJunkLabel(lab, n)) return null
            if (lab.length > 120) lab = lab.take(117).trimEnd() + "…"
            return lab
        }

        private val IMAGE_NAME = Regex("""\.(jpe?g|png|gif|webp|svg)(\?|$)""", RegexOption.IGNORE_CASE)

        /**
         * Web junk: single characters (map/letter-index tiles like "H", "Я") unless a native control with its own
         * view id, and image file names / raw URLs used as link text. Link page only; MCP output is untouched.
         */
        fun isJunkLabel(label: String, n: pro.potoki.bekon.agent.core.UiNode): Boolean {
            val t = label.trim()
            if (n.editable) return false
            if (t.codePointCount(0, t.length) == 1 && !(n.viewId?.contains(":id/") ?: false)) return true
            if (IMAGE_NAME.containsMatchIn(t)) return true
            if (t.startsWith("http://", true) || t.startsWith("https://", true) || t.contains("utm_", true)) return true
            return false
        }

        fun displayLabel(n: pro.potoki.bekon.agent.core.UiNode): String = n.label.ifBlank { "(unnamed)" }
    }
}
