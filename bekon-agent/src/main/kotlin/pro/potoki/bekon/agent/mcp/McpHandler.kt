package pro.potoki.bekon.agent.mcp

import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.request.httpMethod
import io.ktor.server.response.respondText
import kotlinx.coroutines.launch
import io.ktor.http.withCharset
import kotlin.text.Charsets
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import pro.potoki.bekon.agent.access.ClientGate
import pro.potoki.bekon.agent.core.ActionExecutor
import pro.potoki.bekon.agent.shortcuts.ShortcutStore
import pro.potoki.bekon.agent.core.CapabilityException
import pro.potoki.bekon.agent.core.ScreenshotDefaults
import java.util.Base64

/**
 * Minimal MCP Streamable HTTP server (current MCP spec). JSON-RPC 2.0 envelope over
 * HTTP POST on `/mcp`. Tools call into ActionExecutor — the same single executor that
 * serves the REST routes, so they cannot disagree.
 *
 * PLAN2: tools carry real inputSchema, tools/call returns a proper MCP envelope
 * (content + structuredContent + isError), and the exposed tools are exactly what the
 * touch controller already does (no new phone "intelligence").
 */
class McpHandler(
    private val executor: ActionExecutor,
    private val gate: ClientGate? = null,
    private val readOnly: () -> Boolean = { false },
    /** Rejection reason when the session was paused/stopped while a call ran (null = still live). */
    private val liveCheck: () -> String? = { null },
    private val shortcuts: ShortcutStore? = null,
    private val coach: pro.potoki.bekon.agent.coach.CoachSurface? = executor as? pro.potoki.bekon.agent.coach.CoachSurface,
    private val setup: pro.potoki.bekon.agent.setup.DeviceSetupGuide? = null,
) {
    /** Guidance: post [summary] as a heads-up hint notification (system UI is never hidden). */
    private fun postHint(summary: String) {
        val ctx = pro.potoki.bekon.agent.BekonApp.appContext ?: return
        try {
            pro.potoki.bekon.agent.status.AgentAlert.hint(ctx, ctx.getString(pro.potoki.bekon.agent.R.string.hint_notif_title), summary)
            hintPosted = true
        } catch (_: Exception) {}
    }

    @Volatile private var hintPosted = false

    private fun clearHiddenHint() {
        if (!hintPosted) return
        hintPosted = false
        val ctx = pro.potoki.bekon.agent.BekonApp.appContext ?: return
        try { pro.potoki.bekon.agent.status.AgentAlert.clearHint(ctx) } catch (_: Exception) {}
    }

    /** After drawing in guidance mode: if Android hides the overlay here, fall back to a notification. */
    private fun hiddenFallback(summary: String?): Boolean {
        if (!GUIDANCE) return false
        val hidden = pro.potoki.bekon.agent.coach.CoachOverlay.screenHidesOverlay
        if (hidden && summary != null) postHint(summary)
        return hidden
    }

    /** Scroll cue at the screen centre line (or x/y), with a default caption in the UI language. */
    /** Guidance SoM: a label wrapped onto two OCR lines ("SIM cards & mobile" / "networks") is one target. */
    private fun mergeWrappedLines(items: List<pro.potoki.bekon.agent.core.UiNode>): List<pro.potoki.bekon.agent.core.UiNode> {
        val out = ArrayList<pro.potoki.bekon.agent.core.UiNode>()
        for (n in items) {
            val p = out.lastOrNull(); val a = p?.bounds; val b = n.bounds
            if (p != null && a != null && b != null) {
                val h = (a[3] - a[1]).coerceAtLeast(1)
                if (kotlin.math.abs(a[0] - b[0]) <= h / 2 && b[1] - a[3] in -h / 4..h / 2 && (b[3] - b[1]) <= h * 3 / 2) {
                    val m = intArrayOf(minOf(a[0], b[0]), a[1], maxOf(a[2], b[2]), b[3])
                    out[out.size - 1] = p.copy(label = p.label + " " + n.label, bounds = m, x = (m[0] + m[2]) / 2, y = (m[1] + m[3]) / 2)
                    continue
                }
            }
            out += n
        }
        return out
    }

    private fun scrollCueShape(dir: String, text: String?, x: Int?, y: Int?): pro.potoki.bekon.agent.coach.Shape {
        val (sw, sh) = (executor as? pro.potoki.bekon.agent.core.DeviceExecutor)?.screenSizeNow() ?: (1080 to 2340)
        val ctx = pro.potoki.bekon.agent.BekonApp.appContext
        val res = when (dir) {
            "up" -> pro.potoki.bekon.agent.R.string.cue_scroll_up
            "left" -> pro.potoki.bekon.agent.R.string.cue_scroll_left
            "right" -> pro.potoki.bekon.agent.R.string.cue_scroll_right
            "down" -> pro.potoki.bekon.agent.R.string.cue_scroll_down
            else -> throw IllegalArgumentException("direction must be up|down|left|right")
        }
        val label = (text?.takeIf { it.isNotBlank() } ?: ctx?.getString(res) ?: dir).take(40)
        var cx = (x ?: (if (dir == "left" || dir == "right") sw / 2 else (sw * 0.42f).toInt())).toFloat()
        var cy = (y ?: (if (dir == "up") (sh * 0.35f).toInt() else if (dir == "down") (sh * 0.62f).toInt() else (sh * 0.5f).toInt())).toFloat()
        val g = pro.potoki.bekon.agent.coach.FreeSpot.current
        if (x == null && y == null && g != null && g.sw == sw) {
            // Plainest spot near the default position, clear of current marks and the pill.
            val vert = dir == "up" || dir == "down"
            val (bw, bh) = if (vert) 200 to 360 else 360 to 200
            val d = pro.potoki.bekon.agent.BekonApp.appContext?.resources?.displayMetrics?.density ?: 2.75f
            val obs = pro.potoki.bekon.agent.coach.CoachOverlay.markBounds(d).map { intArrayOf(it.left.toInt(), it.top.toInt(), it.right.toInt(), it.bottom.toInt()) } +
                listOfNotNull(pro.potoki.bekon.agent.ocr.OwnOverlay.pillRect?.let { intArrayOf(it.left, it.top, it.right, it.bottom) })
            val zone = if (vert) intArrayOf((sw * 0.2f).toInt(), (cy - sh * 0.12f).toInt(), (sw * 0.8f).toInt(), (cy + sh * 0.12f).toInt())
                else intArrayOf((sw * 0.3f).toInt(), (sh * 0.35f).toInt(), (sw * 0.7f).toInt(), (sh * 0.65f).toInt())
            pro.potoki.bekon.agent.coach.FreeSpot.find(g, bw, bh, zone, obs, pro.potoki.bekon.agent.coach.FreeSpot.safeArea ?: intArrayOf(0, (sh * 0.04f).toInt(), sw, (sh * 0.94f).toInt()), distWeight = 1.2f)
                ?.let { cx = (it.rect[0] + it.rect[2]) / 2f; cy = (it.rect[1] + it.rect[3]) / 2f }
        }
        return pro.potoki.bekon.agent.coach.Sketch.scrollCue(dir, cx, cy, label)
    }

    /** Rough words for where a box is ("top left", "middle", ...), for hints without an overlay. */
    private fun where(b: IntArray, sw: Int, sh: Int): String {
        if (sw <= 0 || sh <= 0) return ""
        val cx = (b[0] + b[2]) / 2f / sw; val cy = (b[1] + b[3]) / 2f / sh
        val v = if (cy < 0.33f) "top" else if (cy < 0.66f) "middle" else "bottom"
        val h = if (cx < 0.33f) " left" else if (cx > 0.66f) " right" else ""
        return v + h
    }

    companion object {
        /** Returned when Android hides Bekon's overlay on the current screen. */
        const val HIDDEN_TEXT = "overlayHidden: this screen blocks overlays (Android hides Bekon's marks and pill here, e.g. some system Settings pages). " +
            "A hint notification with your label was posted instead; also describe the target in words (label + position). Marks return on other screens."

        /** Default stays well below Cloudflare's ~100s proxy read timeout (HTTP 524). */
        const val WAIT_DEFAULT_MS = 30_000
        const val WAIT_MAX_MS = 90_000
        const val POLL_MS = 1_000L
        val COACH_INSTRUCTIONS: String =
            if (pro.potoki.bekon.agent.BuildConfig.GUIDANCE_MODE) "" else (
            "Coaching a human (e.g. device setup): you decide what to coach; Bekon only waits. " +
                "Loop: snapshot (keep its baseline token) -> coach_draw a hint on the exact control -> " +
                "Build every hint from the CURRENT snapshot (its refs/bounds), never from assumed or remembered layouts: menus differ per phone and Android version. " +
                "wait_for_change with that baseline -> read the diff (added/removed/changed, toggles 'off -> on') -> next hint or coach_clear when done. " +
                "Never ask the human to type or say 'done'. Never stop to wait for chat replies during setup; " +
                "if wait_for_change times out (changed=false), re-check the screen and call it again. " +
                "Prefer coach_script for multi-step or scrolling targets: upload rules (done when the toggle is on; circle the row when its text is visible; " +
                "arrow_down 'Прокрутите ниже' when it is not) and call wait_for_script; the phone moves the highlight itself. " +
                "Before Accessibility (Gestures) is on you cannot tap, scroll, snapshot or draw on the phone: only screenshot/annotate_screenshot work, " +
                "so coach the human in chat with annotated screenshots to scroll and tap themselves (never use tap/swipe for the human). Marks are cleared automatically when wait_for_change sees a change."
            )
        val STATUS_INSTRUCTIONS: String =
            if (pro.potoki.bekon.agent.BuildConfig.GUIDANCE_MODE) "" else (
            "Keep the human informed on the phone with agent_status (a small pill inside the bacon frame; touches pass through): " +
                "call agent_status state=working with a short text when you start a task, state=waiting when the human must act (e.g. 'Нажмите «Разрешить»'; the pill already shows the state label, so do NOT prefix the text with 'Нужны вы:'/'Working:' etc.), optional step/total to show 'шаг 2 из 4', " +
                "state=error if you are stuck, and state=done with a one-line summary when finished (e.g. 'Готово — можно остановить доступ'). " +
                "Only one agent may be connected at a time. The human can answer from the pill menu: watch userEvents in wait_for_change/wait_for_script results (or call user_events). " +
                "The pill also shows live capabilities (sees screen / can tap / paused), also returned as capabilities in agent_status and connection_info: trust them, not assumptions. " +
                "Write the text in the HUMAN's language: use deviceInfo.language from initialize/connection_info/agent_status (device locale, e.g. ru = Russian) unless the human writes to you in another language. Keep it under ~60 characters."
            )
        /** Guidance (playStore) build: the assistant only sees the screen and draws hints; the human does every tap. */
        const val GUIDANCE_INSTRUCTIONS =
            "Guidance mode: you can see the screen and show the human hints; the human does every tap, swipe and keystroke themselves. " +
                "There are no input tools in this build. Loop: ocr_screen or find_text (text, contains, acrossHistory) to locate the target -> " +
                "show a hint (find_text draw=true, som_marks numbered marks, coach_draw circle/arrow/box in screen pixels, or guidance_step with a short caption) -> " +
                "wait_for_change -> read the new screen and show the next hint, or coach_clear when done. " +
                "If the target is below the fold, find_text acrossHistory=true returns scrollHint: show an arrow asking the human to scroll, then look again. " +
                "Build every hint from the CURRENT screen, never from remembered layouts. Never ask the human to type 'done'."
        const val READ_ONLY = "device is in read-only mode so this action was declined."
        const val AUTH_INFO = "Session token: send 'Authorization: Bearer <bekon_session_token>' (or X-Bekon-Token) plus X-Bekon-Client-Id on every request; " +
            "the token is handed out in every initialize response (result.bekon_session_token when approved, error.data.bekon_session_token " +
            "when approval_pending, plus the X-Bekon-Token header), becomes valid when the owner taps Allow, " +
            "and ends when the owner taps Disconnect agent or Stop, or when another agent is approved. Lost it? initialize again without a token: the owner gets a new Allow prompt."
        private val ISSUED_TOKEN = io.ktor.util.AttributeKey<String>("bekon-issued-token")
        const val SESSION_INFO = "session_info"
        const val TOOLS_CALL_HINT = "Tools are called via JSON-RPC method tools/call with params {name, arguments}, e.g. " +
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"session_info\",\"arguments\":{}}}. " +
            "JSON-RPC methods: initialize, tools/list, tools/call, ping."
        private val RPC_METHODS = setOf("initialize", "notifications/initialized", "ping", "tools/list", "tools/call")

        /** Error for an unknown JSON-RPC method; a tool name used as a method gets the tools/call shape. */
        fun unknownMethodMessage(method: String, tools: Set<String>): String =
            if (method in tools) "unknown method $method: '$method' is a tool, not a JSON-RPC method. $TOOLS_CALL_HINT"
            else "unknown method $method. $TOOLS_CALL_HINT"

        /** Error for an unknown tool name in tools/call; a JSON-RPC method used as a tool name gets a hint. */
        fun unknownToolMessage(name: String, tools: Set<String>): String =
            if (name in RPC_METHODS) "unknown tool $name: '$name' is a JSON-RPC method, not a tool; send it as {\"method\":\"$name\"}. $TOOLS_CALL_HINT"
            else "unknown tool $name. Call tools/list for the available tool names. $TOOLS_CALL_HINT"

        /** find_text target text: `text`, or the common aliases `query` / `q`. */
        fun textArg(args: JsonObject): String =
            listOf("text", "query", "q").firstNotNullOfOrNull { k -> (args[k] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } } ?: ""

        /** Log line for POST /mcp: method and tool name only. */
        fun callLogLine(method: String, params: JsonElement?): String {
            val tool = if (method == "tools/call") ((params as? JsonObject)?.get("name") as? JsonPrimitive)?.content?.take(64) else null
            return "POST /mcp method=${method.take(64)}" + (tool?.let { " tool=$it" } ?: "")
        }
        const val APPROVAL_HEADER = "X-Bekon-Approval"
        /** Tools that inject input / navigate — forbidden in guidance (playStore) mode. */
        val AUTONOMOUS_TOOLS = setOf(
            "tap", "swipe", "longPress", "drag", "release", "type", "key", "nav", "clipboard",
            "coach_script", "coach_script_stop", "wait_for_script", "scan_list",
            "shortcut_open", "shortcut_add", "shortcut_remove",
        )
        val WRITE_TOOLS = setOf(
            "tap", "swipe", "longPress", "drag", "release", "type", "key", "nav", "shortcut_open",
            "coach_draw", "coach_clear", "open_setup_screen", "coach_script", "coach_script_stop", "scan_list",
            "open_url", "shortcut_add", "shortcut_remove",
            "find_text", "ocr_screen", "som_marks", "nav_mark", "guidance_step", "coach_scroll",
        )
        const val GUIDANCE = pro.potoki.bekon.agent.BuildConfig.GUIDANCE_MODE
        /** Tools hidden from tools/list in guidance mode. */
        val GUIDANCE_HIDDEN = AUTONOMOUS_TOOLS + setOf("coach_script", "wait_for_script", "scan_list", "shortcuts")
    }


    private val json = Json { ignoreUnknownKeys = true }

    /** Tool names currently offered (for hints on wrongly-shaped calls). */
    private val toolNames: Set<String> by lazy {
        (toolsList as kotlinx.serialization.json.JsonArray).mapNotNull { ((it as? JsonObject)?.get("name") as? JsonPrimitive)?.content }
            .filter { !GUIDANCE || it !in GUIDANCE_HIDDEN }.toSet() + SESSION_INFO
    }
    fun unknownMethodMessage(method: String): String = unknownMethodMessage(method, toolNames)
    fun unknownToolMessage(name: String): String = unknownToolMessage(name, toolNames)
    private val scripts by lazy { CoachScriptRunner(executor, coach) }
    init { pro.potoki.bekon.agent.status.UserEvents.stopCoaching = { scripts.run?.takeIf { it.status == "running" }?.let { scripts.stop() } } }

    private fun seenJson(seen: CoachScriptRunner.Seen, withPages: Boolean) = buildJsonObject {
        synchronized(seen) {
            put("pagesScanned", seen.pageCount)
            put("items", buildJsonArray { seen.items.values.forEach { it2 -> add(buildJsonObject {
                put("name", it2.label); put("page", it2.page)
                if (it2.checkable) put("checked", it2.checked)
                if (it2.selected) put("selected", true)
            }) } })
            if (withPages) put("pages", buildJsonArray { seen.pages.forEach { b -> add(buildJsonObject { put("mime", "image/jpeg"); put("data", Base64.getEncoder().encodeToString(b)) }) } })
        }
    }

    private fun seenText(seen: CoachScriptRunner.Seen): String = synchronized(seen) {
        "\nSeen while scrolling (${seen.pageCount} page(s), ${seen.items.size} items): " +
            seen.items.values.joinToString("; ") { it.label.take(60) + (if (it.checkable) (if (it.checked) " [on]" else " [off]") else "") }
    }

    private fun scriptJson(r: CoachScriptRunner.Run) = buildJsonObject {
        put("status", r.status)
        put("elapsedMs", r.elapsed())
        r.current?.let { put("currentRule", it) }
        r.doneRule?.let { put("doneRule", it) }
        r.error?.let { put("error", it) }
        val ev = synchronized(r.events) { r.events.toList() }
        if (!r.seen.empty()) put("seen", seenJson(r.seen, r.status == "error"))
        put("fired", buildJsonArray { ev.takeLast(30).forEach { e -> add(buildJsonObject { put("rule", e.ruleId); put("atMs", e.atMs); e.pkg?.let { put("package", it) } }) } })
        put("text", buildString {
            append("script ${r.status} after ${r.elapsed()}ms")
            r.doneRule?.let { append("; done by rule '$it'") }
            r.current?.let { if (r.status == "running") append("; showing rule '$it'") }
            r.error?.let { append("; error: $it") }
            if (ev.isNotEmpty()) append("; fired: " + ev.takeLast(12).joinToString(" -> ") { "${it.ruleId}@${it.atMs}ms" })
            if (!r.seen.empty() && r.status == "error") append(seenText(r.seen) + "\nPick the right item yourself (it may be named differently) and continue.")
            if (r.status == "error" && r.error?.contains("not found") != true) append(". Accessibility (Gestures) is needed for node rules and the overlay; without it use screenshot + annotate_screenshot and ask the human in chat.")
        })
    }

    /** Recent snapshots by baseline token, so wait_for_change can diff against what the agent saw. */
    private val baselines = object : LinkedHashMap<String, List<pro.potoki.bekon.agent.core.UiNode>>(16, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, List<pro.potoki.bekon.agent.core.UiNode>>?) = size > 16
    }
    /** Items of the agent's latest snapshot: refs (e12) are resolved against these. */
    @Volatile private var lastItems: List<pro.potoki.bekon.agent.core.UiNode> = emptyList()
    private var anchorJob: kotlinx.coroutines.Job? = null
    private val anchorScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default + kotlinx.coroutines.SupervisorJob())

    private data class Anchor(val index: Int, val label: String, val bounds: IntArray)

    /**
     * coach_draw shapes may carry ref (from the latest snapshot) or anchorText/anchorContains instead of
     * coordinates. Resolve them to node bounds (clickable node preferred) and remember the anchors.
     */
    private fun resolveAnchors(arr: kotlinx.serialization.json.JsonArray?): Pair<kotlinx.serialization.json.JsonArray?, List<Anchor>> {
        if (arr == null) return null to emptyList()
        val anchors = ArrayList<Anchor>()
        var cur: List<pro.potoki.bekon.agent.core.UiNode>? = null
        val out = kotlinx.serialization.json.JsonArray(arr.mapIndexed { i, el ->
            val o = el as? JsonObject ?: return@mapIndexed el
            val ref = (o["ref"] as? JsonPrimitive)?.content
            val at = (o["anchorText"] as? JsonPrimitive)?.content
            val ac = (o["anchorContains"] as? JsonPrimitive)?.content
            if (ref == null && at == null && ac == null) return@mapIndexed el
            val node = if (ref != null) lastItems.firstOrNull { it.ref == ref }
                ?: throw IllegalArgumentException("shapes[$i].ref $ref not in your latest snapshot; snapshot again")
            else {
                val items = cur ?: executor.snapshot().items.also { cur = it }
                val sel = pro.potoki.bekon.agent.coach.CoachScript.Sel(at, ac)
                pro.potoki.bekon.agent.coach.CoachScript.preferClickable(items.filter { it.bounds != null && sel.matches(it) }, items)
                    ?: throw IllegalArgumentException("shapes[$i]: no node matches anchor '${at ?: ac}' on the current screen")
            }
            val b = node.bounds ?: throw IllegalArgumentException("shapes[$i]: anchor node has no bounds")
            anchors += Anchor(i, node.label, b)
            val hasCoords = listOf("x", "x1", "bounds").any { o.containsKey(it) }
            if (hasCoords) o else JsonObject(o + ("bounds" to buildJsonArray { b.forEach { add(JsonPrimitive(it)) } }))
        })
        return out to anchors
    }

    private fun shift(s: pro.potoki.bekon.agent.coach.Shape, dx: Float, dy: Float) =
        s.copy(x1 = s.x1 + dx, y1 = s.y1 + dy, x2 = s.x2 + dx, y2 = s.y2 + dy)

    /** Keep anchored marks on their node while the layout shifts (scroll, re-sort, keyboard). */
    private fun followAnchors(c: pro.potoki.bekon.agent.coach.CoachSurface, shapes: List<pro.potoki.bekon.agent.coach.Shape>, anchors: List<Anchor>) {
        anchorJob?.cancel()
        if (anchors.isEmpty()) return
        anchorJob = anchorScope.launch {
            val pos = anchors.associate { it.index to it.bounds.copyOf() }.toMutableMap()
            var misses = 0
            val t0 = System.currentTimeMillis()
            while (System.currentTimeMillis() - t0 < 600_000 && misses < 6) {
                kotlinx.coroutines.delay(700)
                val items = try { executor.snapshot().items } catch (_: Exception) { misses++; continue }
                var moved = false; var lost = false
                for (a in anchors) {
                    val p = pos[a.index]!!
                    val n = items.filter { it.label == a.label && it.bounds != null }
                        .minByOrNull { Math.abs(it.bounds!![1] - p[1]) + Math.abs(it.bounds[0] - p[0]) }
                    if (n == null) { lost = true; continue }
                    if (Math.abs(n.bounds!![1] - p[1]) > 8 || Math.abs(n.bounds[0] - p[0]) > 8) { pos[a.index] = n.bounds.copyOf(); moved = true }
                }
                if (lost) { misses++; continue } else misses = 0
                if (moved) {
                    val out = shapes.mapIndexed { i, s ->
                        val a = anchors.firstOrNull { it.index == i } ?: return@mapIndexed s
                        val p = pos[i]!!
                        shift(s, (p[0] - a.bounds[0]).toFloat(), (p[1] - a.bounds[1]).toFloat())
                    }
                    if (!c.drawOnScreen(out, false, 0)) break
                }
            }
        }
    }

    private fun remember(items: List<pro.potoki.bekon.agent.core.UiNode>): String {
        lastItems = items
        val t = pro.potoki.bekon.agent.core.ScreenDiff.token(items)
        synchronized(baselines) { baselines[t] = items }
        return t
    }

    @Serializable
    data class JsonRpcRequest(val id: Int = 0, val method: String, val params: JsonElement? = null)

    @Serializable
    data class JsonRpcResponse(val jsonrpc: String = "2.0", val id: Int = 0, val result: JsonElement? = null, val error: JsonElement? = null)

    /**
     * GET /mcp with no body: real MCP/SSE clients keep a short plain hint; browsers and web-only
     * agents (ChatGPT etc.) get an HTML page whose first line is the absolute link-start URL.
     */
    private suspend fun respondMcpGet(call: ApplicationCall) {
        val accept = (call.request.headers["Accept"] ?: "").lowercase()
        val ua = (call.request.headers["User-Agent"] ?: "")
        val mcpClient = "text/event-stream" in accept ||
            ("application/json" in accept && "text/html" !in accept)
        val base = pro.potoki.bekon.agent.handoff.Handoff.currentUrl
        try { android.util.Log.i("BekonHttp", "GET /mcp ua=${ua.take(120)} accept=${accept.take(80)} mcpClient=$mcpClient") } catch (_: Throwable) {}
        if (mcpClient) {
            call.respondText(
                pro.potoki.bekon.agent.handoff.AgentGuide.mcpGetHint(base),
                ContentType.Text.Plain.withCharset(Charsets.UTF_8),
            )
            return
        }
        // Prefer HTML when the client looks like a browser / generic fetcher; plain text otherwise.
        val wantHtml = "text/html" in accept || accept.isBlank() || accept == "*/*" ||
            ua.contains("Mozilla", ignoreCase = true) ||
            ua.contains("ChatGPT", ignoreCase = true) ||
            ua.contains("OAI-", ignoreCase = true) ||
            ua.contains("Claude", ignoreCase = true) ||
            ua.contains("GPT", ignoreCase = true)
        call.response.headers.append("X-Robots-Tag", "all")
        if (wantHtml) {
            call.respondText(
                pro.potoki.bekon.agent.handoff.AgentGuide.mcpGetHtml(base),
                ContentType.Text.Html.withCharset(Charsets.UTF_8),
            )
        } else {
            call.respondText(
                pro.potoki.bekon.agent.handoff.AgentGuide.mcpGetText(base),
                ContentType.Text.Plain.withCharset(Charsets.UTF_8),
            )
        }
    }

    suspend fun handle(call: ApplicationCall) {
        val body = try { call.receiveText() } catch (e: Exception) { "" }
        if (body.isBlank() && call.request.httpMethod == io.ktor.http.HttpMethod.Get) {
            respondMcpGet(call)
            return
        }
        if (body.isBlank()) { respondJson(call, JsonRpcResponse(id = 0, error = errElement("empty request"))); return }
        val req = try {
            json.decodeFromString(JsonRpcRequest.serializer(), body)
        } catch (e: Exception) {
            respondJson(call, JsonRpcResponse(id = 0, error = errElement("invalid json"))); return
        }
        val id = req.id
        val token = ClientGate.tokenFrom(call.request.headers["Authorization"], call.request.headers[ClientGate.TOKEN_HEADER])
        // session_info is answered BEFORE the gate: read-only, token-validated, never prompts the owner or issues a token.
        if (req.method == "tools/call" && ((req.params as? JsonObject)?.get("name") as? JsonPrimitive)?.content == SESSION_INFO) {
            val cid = clientIdOf(call, req)
            logCall(req.method, req.params)
            val st = gate?.status(cid, token) ?: ClientGate.Status.ALIVE
            // A live agent checking its session is activity too (keeps the card/pill "connected").
            if (st == ClientGate.Status.ALIVE && cid != null) pro.potoki.bekon.agent.access.AgentConnections.seen(cid)
            val info = sessionInfoJson(st)
            respondJson(call, JsonRpcResponse(id = id, result = buildJsonObject {
                put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", info.toString()) }) })
                put("structuredContent", info)
            }))
            return
        }
        var reconnected = false
        logCall(req.method, req.params)
        when (val d = gate?.check(clientIdOf(call, req), clientIp(call), token)) {
            is ClientGate.Decision.Reject -> {
                if (d.status == 401) {
                    call.response.headers.append("WWW-Authenticate", "Bearer realm=\"bekon\"")
                    call.respondText(json.encodeToString(JsonRpcResponse.serializer(), JsonRpcResponse(id = id, error = errElement(d.reason))),
                        ContentType.Application.Json, io.ktor.http.HttpStatusCode.Unauthorized)
                } else respondJson(call, JsonRpcResponse(id = id, error = errElement(d.reason)))
                return
            }
            is ClientGate.Decision.Pending -> {
                // Owner has not answered yet: hand out the (not yet valid) token NOW so a stateless caller can't lose it.
                call.response.headers.append(ClientGate.TOKEN_HEADER, d.token)
                call.response.headers.append(APPROVAL_HEADER, "pending")
                call.response.headers.append("Retry-After", "3")
                call.respondText(json.encodeToString(JsonRpcResponse.serializer(), JsonRpcResponse(id = id, error = buildJsonObject {
                    put("code", ClientGate.PENDING_CODE); put("message", ClientGate.PENDING_MESSAGE)
                    put("data", buildJsonObject {
                        put("status", "approval_pending"); put("approval", "pending")
                        put(ClientGate.TOKEN_FIELD, d.token); put("retry_after_s", 3)
                        put("next", "save the token; repeat initialize with the same uniqueId and Authorization: Bearer <token> until approval=approved")
                    })
                })), ContentType.Application.Json)
                return
            }
            is ClientGate.Decision.Allow -> {
                // A valid token on initialize = reconnect of the SAME approved session (no prompt, nothing new issued).
                reconnected = d.issuedToken == null && token != null
                call.response.headers.append(APPROVAL_HEADER, "approved")
                // initialize ALWAYS carries the session token (result.bekon_session_token + X-Bekon-Token header):
                // just issued (Allow tapped within the wait) or echoed back to the poller that already holds it.
                val tok = d.issuedToken ?: token?.takeIf { req.method == "initialize" }?.trim()
                tok?.let {
                    call.response.headers.append(ClientGate.TOKEN_HEADER, it)
                    call.attributes.put(ISSUED_TOKEN, it)
                }
            }
            null -> {}
        }
        when (req.method) {
            "initialize" -> respondJson(call, JsonRpcResponse(id = id, result = buildJsonObject {
                put("protocolVersion", "2025-03-26")
                put("capabilities", buildJsonObject { put("tools", JsonObject(emptyMap())) })
                put("serverInfo", buildJsonObject {
                    put("name", "bekon-agent"); put("version", pro.potoki.bekon.agent.BuildConfig.VERSION_NAME)
                    put("sourceUrl", pro.potoki.bekon.agent.ui.SourceCode.url)
                })
                put("auth", AUTH_INFO)
                put("instructions", pro.potoki.bekon.agent.handoff.AgentGuide.SERVER_INSTRUCTIONS)
                put("deviceInfo", deviceInfoJson())
                if (gate != null) put("approval", "approved")
                if (reconnected) { put("reconnected", true); put("session", sessionInfoJson(ClientGate.Status.ALIVE)) }
            }))
            "notifications/initialized" -> respondJson(call, JsonRpcResponse(id = id, result = JsonObject(emptyMap())))
            "ping" -> respondJson(call, JsonRpcResponse(id = id, result = JsonObject(emptyMap())))
            "tools/list" -> respondJson(call, JsonRpcResponse(id = id, result = buildJsonObject {
                val tools = if (!GUIDANCE) toolsList else {
                    val arr = toolsList as kotlinx.serialization.json.JsonArray
                    buildJsonArray {
                        for (el in arr) {
                            val o = el as? JsonObject ?: continue
                            val n = (o["name"] as? JsonPrimitive)?.content ?: continue
                            if (n in GUIDANCE_HIDDEN) continue
                            add(el)
                        }
                    }
                }
                put("tools", tools)
            }))
            "tools/call" -> {
                val params = req.params?.jsonObject ?: JsonObject(emptyMap())
                val name = (params["name"] as? JsonPrimitive)?.content ?: ""
                val args = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())
                if (GUIDANCE && name in AUTONOMOUS_TOOLS) {
                    respondJson(call, JsonRpcResponse(id = id, error = errElement(
                        "guidance_mode: Bekon only draws hints; the user performs every tap. Use coach_draw, find_text, screenshot, wait_for_change."
                    )))
                    return
                }
                if (readOnly() && name in WRITE_TOOLS) {
                    respondJson(call, JsonRpcResponse(id = id, error = errElement(READ_ONLY)))
                    return
                }
                try {
                    val pageImgs = ArrayList<JsonObject>()
                    fun strip(o: JsonObject): JsonObject = JsonObject(o.mapValues { (k, v) ->
                        if (k == "pages" && v is kotlinx.serialization.json.JsonArray) { v.forEach { (it as? JsonObject)?.let(pageImgs::add) }; JsonPrimitive("${v.size} image(s) in content") }
                        else if (v is JsonObject) strip(v) else v })
                    val raw0 = callTool(name, args)
                    // Long calls (wait_for_change, coach scripts) may finish after Pause/Stop: drop their result.
                    liveCheck()?.let { throw CapabilityException(it) }
                    val raw = if (name == "screenshot" || name == "annotate_screenshot") raw0 else strip(raw0)
                    val shot = (raw["screenshot"] as? JsonObject)
                    val data = if (shot != null) JsonObject(raw.filterKeys { it != "screenshot" }) else raw
                    val content: JsonObject = when {
                        name == "screenshot" || name == "annotate_screenshot" -> data
                        // MCP content items must carry a type; ship the tree as JSON text.
                        name == "snapshot" -> buildJsonObject { put("type", "text"); put("text", data.toString()) }
                        name == "wait_for_change" -> buildJsonObject {
                            put("type", "text")
                            put("text", ((data["summary"] as? JsonPrimitive)?.content ?: "") + "\n" + data.toString())
                        }
                        else -> {
                            val clip = (data["text"] as? JsonPrimitive)?.content
                            buildJsonObject { put("type", "text"); put("text", clip ?: "ok") }
                        }
                    }
                    val toolResult = buildJsonObject {
                        put("content", buildJsonArray {
                            if (name == "screenshot" || name == "annotate_screenshot") {
                                val obj = data as JsonObject
                                val b64 = (obj["data"] as? JsonPrimitive)?.content ?: ""
                                val mime = (obj["mime"] as? JsonPrimitive)?.content ?: "image/jpeg"
                                add(buildJsonObject {
                                    put("type", "image")
                                    put("mimeType", mime)
                                    put("data", b64)
                                })
                            } else {
                                add(content)
                                pageImgs.forEach { pg -> add(buildJsonObject {
                                    put("type", "image"); put("mimeType", (pg["mime"] as? JsonPrimitive)?.content ?: "image/jpeg")
                                    put("data", (pg["data"] as? JsonPrimitive)?.content ?: "")
                                }) }
                                if (shot != null) add(buildJsonObject {
                                    put("type", "image")
                                    put("mimeType", (shot["mime"] as? JsonPrimitive)?.content ?: "image/jpeg")
                                    put("data", (shot["data"] as? JsonPrimitive)?.content ?: "")
                                })
                            }
                        })
                        // Don't ship the image twice (content + structuredContent) over the tunnel.
                        put("structuredContent", if (name == "screenshot" || name == "annotate_screenshot")
                            JsonObject(data.filterKeys { it != "data" }) else data)
                        put("isError", false)
                    }
                    respondJson(call, JsonRpcResponse(id = id, result = toolResult))
                } catch (e: CapabilityException) {
                    val toolResult = buildJsonObject {
                        put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", e.message ?: "capability missing") }) })
                        put("structuredContent", buildJsonObject { put("error", e.message ?: "capability missing") })
                        put("isError", true)
                    }
                    respondJson(call, JsonRpcResponse(id = id, result = toolResult))
                } catch (e: Exception) {
                    val toolResult = buildJsonObject {
                        put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", e.message ?: "error") }) })
                        put("structuredContent", buildJsonObject { put("error", e.message ?: "error") })
                        put("isError", true)
                    }
                    respondJson(call, JsonRpcResponse(id = id, result = toolResult))
                }
            }
            else -> respondJson(call, JsonRpcResponse(id = id, error = errElement(unknownMethodMessage(req.method))))
        }
    }

    /** debug logcat: method + tool name only (never params, arguments, ids or tokens). */
    private fun logCall(method: String, params: JsonElement?) {
        try { android.util.Log.d("BekonHttp", callLogLine(method, params)) } catch (_: Throwable) { /* JVM unit tests */ }
    }

    /** Each tool returns a JsonObject payload (for structuredContent) OR a screenshot object. */
    private fun itemJson(n: pro.potoki.bekon.agent.core.UiNode) = buildJsonObject {
        put("ref", n.ref); put("name", n.label)
        put("x", n.x); put("y", n.y)
        put("clickable", n.clickable)
        put("checkable", n.checkable); put("checked", n.checked)
        put("selected", n.selected); put("enabled", n.enabled)
        if (n.statusBar) put("statusBar", true)
        put("bounds", buildJsonArray { n.bounds?.forEach { add(JsonPrimitive(it)) } ?: Unit })
    }

    private fun snapshotJson(s: pro.potoki.bekon.agent.core.ScreenSnapshot, token: String) = buildJsonObject {
        put("screenW", s.screenW); put("screenH", s.screenH)
        put("imageW", s.imageW); put("imageH", s.imageH)
        put("baseline", token)
        put("items", buildJsonArray { for (n in s.items) add(itemJson(n)) })
    }

    private fun foregroundJson() = buildJsonObject { executor.foreground().forEach { (k, v) -> put(k, v) } }

    /**
     * Block until the screen meaningfully changes vs [baseline] (or the screen at call time),
     * then wait for the UI to be quiet for debounceMs (capped by settleMaxMs).
     */
    /** Grayscale grid of the screen (status bar and nav bar cropped) from a projection frame. */
    private fun pixelGrid(): IntArray? = try {
        val f = executor.screenshot(0.1f, 60, "jpeg")
        val bm = android.graphics.BitmapFactory.decodeByteArray(f.bytes, 0, f.bytes.size) ?: null
        bm?.let {
            // Downscaled grayscale grid. Crop the status bar (clock/icons tick) and the bottom strip
            // (nav bar + the agent_status pill), and the bacon frame edges.
            val gw = 36; val gh = 64
            val top = (it.height * 0.06).toInt(); val bot = (it.height * 0.86).toInt()
            val l = (it.width * 0.03).toInt(); val r = (it.width * 0.97).toInt()
            val out = IntArray(gw * gh)
            for (j in 0 until gh) for (i in 0 until gw) {
                val x = l + (i + 0.5f) * (r - l) / gw; val y = top + (j + 0.5f) * (bot - top) / gh
                val c = it.getPixel(x.toInt().coerceIn(0, it.width - 1), y.toInt().coerceIn(0, it.height - 1))
                out[j * gw + i] = ((c shr 16 and 255) * 3 + (c shr 8 and 255) * 6 + (c and 255)) / 10
            }
            it.recycle(); out
        }
    } catch (_: Exception) { null }

    /** wait_for_change before Accessibility is on: compare screen-capture frames (no tree, no diff). */
    private suspend fun waitForChangePixels(args: JsonObject): JsonObject {
        val timeout = (args.intOr("timeoutMs") ?: WAIT_DEFAULT_MS).coerceIn(0, WAIT_MAX_MS).toLong()
        val debounce = (args.intOr("debounceMs") ?: 600).coerceIn(0, 5000).toLong()
        val t0 = System.currentTimeMillis()
        fun el() = System.currentTimeMillis() - t0
        // Changed when > 1.5% of cells moved by more than a JPEG-noise margin.
        fun differs(a: IntArray, b: IntArray) = a.indices.count { Math.abs(a[it] - b[it]) > 28 } > a.size * 15 / 1000
        val base = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { pixelGrid() }
            ?: throw CapabilityException("wait_for_change: accessibility is off and screen capture is not running; press Start in Bekon")
        var changed = false
        var last = base
        val ueSeq = pro.potoki.bekon.agent.status.UserEvents.seq
        while (el() < timeout && pro.potoki.bekon.agent.status.UserEvents.seq == ueSeq) {
            kotlinx.coroutines.delay(250)
            val g = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { pixelGrid() } ?: continue
            if (differs(base, g)) {
                // settle: until two frames in a row agree (or debounce budget runs out)
                last = g; val s0 = el()
                while (el() - s0 < 3000) {
                    kotlinx.coroutines.delay(debounce.coerceAtLeast(200))
                    val g2 = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { pixelGrid() } ?: break
                    if (!differs(last, g2)) break
                    last = g2
                }
                changed = differs(base, last); if (changed) break
            }
        }
        return buildJsonObject {
            put("changed", changed)
            put("mode", "pixels (accessibility off: no tree, no diff, no baseline)")
            put("elapsedMs", el())
            put("summary", (if (changed) "CHANGED (screen pixels)" else "NO CHANGE (timeout)") + " after ${el()}ms. Accessibility is off: take a screenshot/annotate_screenshot to see what the human did.")
        }
    }

    /** The a11y tree is briefly unavailable during window transitions: retry for ~1.5 s before failing. */
    private suspend fun snapshotRetry(): pro.potoki.bekon.agent.core.ScreenSnapshot {
        var last: Exception? = null
        repeat(6) {
            try { return executor.snapshot() } catch (e: CapabilityException) { last = e; kotlinx.coroutines.delay(250) }
        }
        throw last!!
    }

    private suspend fun waitForChange(args: JsonObject): JsonObject {
        if (executor.health()["touch"] == false) return waitForChangePixels(args)
        val timeout = (args.intOr("timeoutMs") ?: WAIT_DEFAULT_MS).coerceIn(0, WAIT_MAX_MS).toLong()
        val debounce = (args.intOr("debounceMs") ?: 600).coerceIn(0, 5000).toLong()
        val settleMax = (args.intOr("settleMaxMs") ?: 3000).coerceIn(debounce.toInt(), 10000).toLong()
        val withShot = args.boolOr("includeScreenshot") ?: false
        val sig = executor.changeSignal()
        val t0 = System.nanoTime()
        fun elapsed() = (System.nanoTime() - t0) / 1_000_000
        val given = args.strOr("baseline")
        var baseItems: List<pro.potoki.bekon.agent.core.UiNode>? = given?.let { synchronized(baselines) { baselines[it] } }
        val baseToken: String = given ?: remember(snapshotRetry().items.also { baseItems = it })
        var seq = sig?.seq ?: 0L
        var lastPoll = 0L
        var reason = ""
        var result: pro.potoki.bekon.agent.core.ScreenSnapshot? = null
        var cur: pro.potoki.bekon.agent.core.ScreenSnapshot? = null
        var first = true
        val ueSeq = pro.potoki.bekon.agent.status.UserEvents.seq
        while (true) {
            if (pro.potoki.bekon.agent.status.UserEvents.seq != ueSeq) break
            val now = elapsed()
            val evt = sig != null && sig.seq != seq
            val poll = first || now - lastPoll >= POLL_MS
            if (evt || poll) {
                first = false
                if (evt) { seq = sig!!.seq; reason = sig.lastReason }
                // Debounce: quiet for [debounce] ms after the latest event, but no longer than settleMax.
                val settleStart = elapsed()
                if (evt && sig != null) {
                    var lastSeen = sig.seq; var quietSince = elapsed()
                    while (elapsed() - quietSince < debounce && elapsed() - settleStart < settleMax && elapsed() < timeout) {
                        kotlinx.coroutines.delay(50)
                        if (sig.seq != lastSeen) { lastSeen = sig.seq; quietSince = elapsed(); reason = sig.lastReason }
                    }
                    seq = sig.seq
                }
                lastPoll = elapsed()
                val snap = try { executor.snapshot() } catch (e: CapabilityException) { if (elapsed() >= timeout) throw e else null }
                if (snap != null) {
                    cur = snap
                    if (pro.potoki.bekon.agent.core.ScreenDiff.token(snap.items) != baseToken) {
                        if (!evt) {
                            reason = if (reason.isEmpty()) "tree hash changed (poll)" else reason
                            // Change found by polling: still let it settle once.
                            kotlinx.coroutines.delay(debounce)
                            result = try { executor.snapshot() } catch (_: CapabilityException) { snap }
                        } else result = snap
                        break
                    }
                }
            }
            if (elapsed() >= timeout) break
            kotlinx.coroutines.delay(50)
        }
        val fin = result ?: cur ?: snapshotRetry()
        val token = remember(fin.items)
        val changed = result != null && token != baseToken
        // Marks are positioned for the old screen: after a list re-sort/scroll/new window they point at
        // the wrong control (e.g. another app's Autostart switch). Clear them unless asked to keep.
        val cleared = changed && args.boolOr("keepMarks") != true && try { coach?.clearScreen(); coach != null } catch (_: Exception) { false }
        val diff = baseItems?.let { pro.potoki.bekon.agent.core.ScreenDiff.diff(it, fin.items) }
        val fg = foregroundJson()
        return buildJsonObject {
            put("changed", changed)
            put("reason", if (changed) reason.ifEmpty { "tree changed" } else "timeout: no meaningful change in ${timeout}ms; call wait_for_change again with the same baseline")
            put("elapsedMs", elapsed())
            put("window", fg)
            put("baseline", token)
            put("previousBaseline", baseToken)
            if (cleared) put("coachMarks", "cleared (screen changed); draw the next hint")
            if (diff != null) put("diff", buildJsonObject {
                put("added", buildJsonArray { diff.added.take(40).forEach { add(buildJsonObject { put("ref", it.ref); put("name", it.label); put("state", pro.potoki.bekon.agent.core.ScreenDiff.state(it).trim()) }) } })
                put("removed", buildJsonArray { diff.removed.take(40).forEach { add(buildJsonObject { put("name", it.label); put("state", pro.potoki.bekon.agent.core.ScreenDiff.state(it).trim()) }) } })
                put("changed", buildJsonArray { diff.changed.take(40).forEach { add(buildJsonObject { put("ref", it.ref); put("name", it.name); put("what", it.what) }) } })
            }) else put("diff", JsonPrimitive("unavailable: baseline token not known to this server (take a snapshot first)"))
            put("summary", buildString {
                append(if (changed) "CHANGED" else "NO CHANGE (timeout)")
                fg["package"]?.let { append(" — ${(it as JsonPrimitive).content}") }
                fg["title"]?.let { append(" '${(it as JsonPrimitive).content}'") }
                append(" after ${elapsed()}ms")
                if (diff != null) append("\n" + diff.summary())
            })
            put("screenW", fin.screenW); put("screenH", fin.screenH)
            put("items", buildJsonArray { for (n in fin.items) add(itemJson(n)) })
            if (withShot) {
                try {
                    val f = executor.screenshot(ScreenshotDefaults.SCALE, ScreenshotDefaults.QUALITY, "jpeg")
                    put("screenshot", buildJsonObject { put("mime", f.mime); put("data", Base64.getEncoder().encodeToString(f.bytes)) })
                } catch (e: Exception) { put("screenshotError", e.message ?: "capture failed") }
            }
        }
    }

    private suspend fun callTool(name: String, args: JsonObject): JsonObject = when (name) {
        "snapshot" -> {
            val s = executor.snapshot()
            val t = remember(s.items)
            val fg = foregroundJson()
            JsonObject(snapshotJson(s, t) + ("window" to fg) + ("package" to (fg["package"] ?: JsonPrimitive(""))))
        }
        "open_url" -> {
            val url = args.str("url").trim()
            val u = android.net.Uri.parse(url)
            if (u.scheme != "https") throw IllegalArgumentException("open_url accepts only https:// urls")
            val ctx = pro.potoki.bekon.agent.BekonApp.appContext ?: throw CapabilityException("open_url: app context unavailable")
            val r = pro.potoki.bekon.agent.setup.Launcher.launch(ctx, android.content.Intent(android.content.Intent.ACTION_VIEW, u), url, url)
            buildJsonObject { put("ok", r.opened); put("result", r.code); put("text", r.text) }
        }
        "wait_for_change" -> withUserEvents(waitForChange(args))
        "user_events" -> {
            val ev = pro.potoki.bekon.agent.status.UserEvents.drain()
            buildJsonObject {
                put("events", pro.potoki.bekon.agent.status.UserEvents.json(ev))
                put("text", if (ev.isEmpty()) "no new user events" else ev.joinToString("; ") { it.type + (it.text?.let { t -> ": $t" } ?: "") } + ". " + pro.potoki.bekon.agent.status.UserEvents.DOC)
            }
        }
        "screenshot" -> {
            val scale = args.floatOr("scale") ?: ScreenshotDefaults.SCALE
            val quality = args.intOr("quality") ?: ScreenshotDefaults.QUALITY
            val format = args.strOr("format") ?: ScreenshotDefaults.FORMAT
            val f = executor.screenshot(scale, quality, format)
            buildJsonObject {
                put("mime", f.mime)
                put("width", f.width); put("height", f.height)
                put("screenW", f.screenW); put("screenH", f.screenH)
                put("data", Base64.getEncoder().encodeToString(f.bytes))
            }
        }
        "annotate_screenshot" -> {
            val c = coach ?: throw CapabilityException("coach: not available")
            val shapes = pro.potoki.bekon.agent.coach.Sketch.parse(args["shapes"] as? kotlinx.serialization.json.JsonArray)
            val f = c.annotatedScreenshot(
                shapes,
                args.floatOr("scale") ?: 0.5f,
                args.intOr("quality") ?: 80,
                args.strOr("format") ?: "png",
            )
            buildJsonObject {
                put("mime", f.mime)
                put("width", f.width); put("height", f.height)
                put("screenW", f.screenW); put("screenH", f.screenH)
                put("shapes", shapes.size)
                put("data", Base64.getEncoder().encodeToString(f.bytes))
            }
        }
        "coach_draw" -> {
            if (GUIDANCE) clearHiddenHint()
            val c = coach ?: throw CapabilityException("coach: not available")
            val (resolved, anchors) = resolveAnchors(args["shapes"] as? kotlinx.serialization.json.JsonArray)
            val shapes = pro.potoki.bekon.agent.coach.Sketch.parse(resolved)
            val append = (args["append"] as? JsonPrimitive)?.content == "true"
            val ttl = (args.intOr("ttlMs") ?: 0).toLong()
            if (!c.drawOnScreen(shapes, append, ttl)) {
                throw CapabilityException(
                    if (GUIDANCE) "overlay: display-over-other-apps (SYSTEM_ALERT_WINDOW) is off. Open Bekon and grant overlay, or use annotate_screenshot."
                    else "overlay: accessibility service (Gestures) is off. Use annotate_screenshot and show the image to the user instead."
                )
            }
            if (!append) followAnchors(c, shapes, anchors) else anchorJob?.cancel()
            val labels = shapes.mapNotNull { it.label?.takeIf { l -> l.isNotBlank() } }.joinToString(" · ")
            val hidden = hiddenFallback(labels.ifBlank { null })
            buildJsonObject {
                put("ok", true)
                if (hidden) { put("overlayHidden", true); put("text", HIDDEN_TEXT) }
                else put("text", "drew ${shapes.size} mark(s) on the phone screen. Now call wait_for_change (pass your last baseline) and read its diff; do not ask the human to say 'done'.")
            }
        }
        "coach_script" -> {
            val rules = pro.potoki.bekon.agent.coach.CoachScript.parse(args["rules"] as? kotlinx.serialization.json.JsonArray)
            if (coach == null) throw CapabilityException("coach: not available")
            // Fail fast (before starting a thread) when the a11y tree is unavailable.
            executor.snapshot()
            val timeout = (args.intOr("timeoutMs") ?: 120_000).coerceIn(1_000, 600_000).toLong()
            val interval = (args.intOr("intervalMs") ?: 250).coerceIn(100, 2_000).toLong()
            val r = scripts.start(rules, timeout, interval)
            Thread.sleep(minOf(interval + 150, 600))
            val o = scriptJson(r)
            JsonObject(o + ("text" to JsonPrimitive((o["text"] as JsonPrimitive).content + ". The phone now follows these rules itself; call wait_for_script to block until a done rule fires.")))
        }
        "wait_for_script" -> {
            val r = scripts.run ?: throw IllegalArgumentException("no coach_script started")
            val t = (args.intOr("timeoutMs") ?: WAIT_DEFAULT_MS).coerceIn(0, WAIT_MAX_MS).toLong()
            val onChange = args.boolOr("returnOnRuleChange") == true
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { scripts.await(r, t, onChange) }
            withUserEvents(scriptJson(r))
        }
        "scan_list" -> {
            val dir = (args["direction"] as? JsonPrimitive)?.content ?: "down"
            val max = (args.intOr("maxPages") ?: 8).coerceIn(1, 15)
            val restore = args.boolOr("restore") ?: true
            val seen = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { scripts.scanList(dir, max, restore) }
            JsonObject(seenJson(seen, true) + ("text" to JsonPrimitive("scanned list" + seenText(seen))))
        }
        "coach_script_stop" -> {
            scripts.stop()
            buildJsonObject { put("ok", true); put("text", "script stopped, marks cleared") }
        }
        "coach_clear" -> {
            if (GUIDANCE) clearHiddenHint()
            val c = coach ?: throw CapabilityException("coach: not available")
            scripts.run?.takeIf { it.status == "running" }?.let { scripts.stop() }
            anchorJob?.cancel()
            c.clearScreen()
            buildJsonObject { put("ok", true); put("text", "cleared") }
        }
        
        "ocr_screen" -> {
            val snap = executor.snapshot()
            buildJsonObject {
                put("ok", true)
                put("count", snap.items.size)
                put("screenW", snap.screenW); put("screenH", snap.screenH)
                put("items", buildJsonArray {
                    snap.items.forEach { n ->
                        add(buildJsonObject {
                            put("ref", n.ref); put("text", n.label)
                            put("x", n.x); put("y", n.y)
                            n.bounds?.let { b -> put("bounds", buildJsonArray { b.forEach { add(JsonPrimitive(it)) } }) }
                        })
                    }
                })
                put("fingerprint", pro.potoki.bekon.agent.nav.NavJournal.currentFingerprint() ?: "")
                put("text", "ocr ${snap.items.size} text block(s). Use find_text or coach_draw with ref/bounds; user taps.")
            }
        }
        "find_text" -> {
            val q = textArg(args).trim()
            if (q.isEmpty()) throw IllegalArgumentException("text is required (non-empty); aliases: query, q")
            val contains = args.boolOr("contains") ?: true
            val across = args.boolOr("acrossHistory") ?: false
            val c = coach
            val (sw0, sh0) = try { (executor as? pro.potoki.bekon.agent.core.DeviceExecutor)?.screenSizeNow() ?: (0 to 0) } catch (_: Exception) { 0 to 0 }
            // History hits are in page space; convert with the current scroll offset so marks land on screen.
            // Fresh pill-free OCR of the current screen first, so history includes what is visible right now.
            val fresh = if (across && GUIDANCE) try { executor.snapshot() } catch (_: Exception) { null } else null
            val off = if (across && GUIDANCE) pro.potoki.bekon.agent.ocr.ScreenHistory.currentOffset() else 0
            // What is on screen right now wins: its OCR bounds are exact, while history positions
            // are page-space estimates that drift when a fling outruns the scroll tracker.
            val freshHits = fresh?.items?.filter {
                pro.potoki.bekon.agent.ocr.TextMatch.matches(it.label, q, contains)
            }?.map { Triple(it.label, it.bounds ?: intArrayOf(it.x, it.y, it.x, it.y), -1) }.orEmpty()
            val histHits = if (freshHits.isEmpty() && across && GUIDANCE) {
                pro.potoki.bekon.agent.ocr.ScreenHistory.findText(q, contains).map {
                    val pb = it.pageBounds
                    Triple(it.text, intArrayOf(pb[0], pb[1] - off, pb[2], pb[3] - off), it.frameIndex)
                }.sortedBy { t -> if (sh0 > 0 && t.second[1] >= 0 && t.second[3] <= sh0) 0 else 1 }
            } else emptyList()
            val hits = if (freshHits.isNotEmpty()) freshHits else if (histHits.isNotEmpty()) histHits else {
                val snap = fresh ?: executor.snapshot()
                snap.items.filter {
                    pro.potoki.bekon.agent.ocr.TextMatch.matches(it.label, q, contains)
                }.map { Triple(it.label, it.bounds ?: intArrayOf(it.x, it.y, it.x, it.y), -1) }
            }
            val draw = args.boolOr("draw") ?: true
            val kind = (args.strOr("mark") ?: "highlight").lowercase()
            val onScreen = hits.isNotEmpty() && (sh0 <= 0 || (hits.first().second[1] >= 0 && hits.first().second[3] <= sh0))
            var hidden = false
            if (GUIDANCE) clearHiddenHint()
            if (draw && onScreen && c != null) {
                val (ocrText, b, _) = hits.first()
                // OCR may return look-alike glyphs (Cyrillic read by the Latin model): show the human
                // the label as the agent asked for it, not the raw OCR string.
                val text = if (ocrText.contains(q, true)) ocrText else q
                val capDefault: String? = if (GUIDANCE) pro.potoki.bekon.agent.BekonApp.appContext?.getString(pro.potoki.bekon.agent.R.string.cue_tap, text.take(28)) else null
                val cap: String = (args.strOr("caption")?.takeIf { it.isNotBlank() } ?: capDefault ?: text).take(48)
                val shapes = when (kind) {
                    "arrow" -> listOf(
                        pro.potoki.bekon.agent.coach.Shape(
                            pro.potoki.bekon.agent.coach.Shape.Kind.ARROW,
                            b[0].toFloat(), (b[1] - 120).toFloat().coerceAtLeast(0f),
                            ((b[0] + b[2]) / 2f), b[1].toFloat(),
                            cap,
                        ),
                    )
                    "mark", "som" -> listOf(
                        pro.potoki.bekon.agent.coach.Shape(
                            pro.potoki.bekon.agent.coach.Shape.Kind.MARK,
                            b[0].toFloat(), b[1].toFloat(), b[2].toFloat(), b[3].toFloat(),
                            "1",
                        ),
                    )
                    else -> listOf(
                        pro.potoki.bekon.agent.coach.Shape(
                            pro.potoki.bekon.agent.coach.Shape.Kind.BOX,
                            b[0].toFloat(), b[1].toFloat(), b[2].toFloat(), b[3].toFloat(),
                            cap,
                        ),
                    )
                }
                val ttl = (args.intOr("ttlMs") ?: 12_000).toLong()
                if (!c.drawOnScreen(shapes, false, ttl) && GUIDANCE) {
                    throw CapabilityException("overlay: display-over-other-apps is off")
                }
                hidden = hiddenFallback("“$text” — " + where(b, sw0, sh0))
            }
            val (sw, sh) = if (sh0 > 0) sw0 to sh0 else try {
                val s = executor.snapshot(); s.screenW to s.screenH
            } catch (_: Exception) { 0 to 0 }
            val hint = if (across && GUIDANCE && freshHits.isEmpty()) {
                pro.potoki.bekon.agent.ocr.ScreenHistory.scrollHintFor(q, 0, sh)
            } else null
            buildJsonObject {
                put("ok", true); put("count", hits.size)
                put("hits", buildJsonArray {
                    hits.take(20).forEach { (text, b, fi) ->
                        add(buildJsonObject {
                            put("text", text)
                            put("bounds", buildJsonArray { b.forEach { add(JsonPrimitive(it)) } })
                            if (fi >= 0) put("frameIndex", fi)
                        })
                    }
                })
                hint?.let { put("scrollHint", it) }
                if (hidden) put("overlayHidden", true)
                put("text", when {
                    hidden -> HIDDEN_TEXT
                    hits.isEmpty() && across && GUIDANCE -> "no match for '$q' on screen or in this session's scroll history. If the list continues below, ask the human to scroll (coach_scroll direction=down), wait_for_change, then find_text again"
                    hits.isEmpty() -> "no match for '$q'"
                    !onScreen && hint == null -> "found but off screen — ask the user to scroll, then find_text again"
                    hint == "below (swipe up / scroll down to reveal)" -> "found further down — show coach_scroll direction=down, wait_for_change, then find_text again"
                    hint == "above (swipe down / scroll up to reveal)" -> "found above — show coach_scroll direction=up, wait_for_change, then find_text again"
                    !draw || !onScreen -> "found ${hits.size} match(es)"
                    else -> "highlighted ${hits.size} match(es); user taps"
                })
            }
        }
        "som_marks" -> {
            val snap = executor.snapshot()
            val max = (args.intOr("max") ?: 12).coerceIn(1, 30)
            val grid = args.boolOr("grid") ?: false
            // Skip the status bar (clock, icons) and OCR junk (")", "|"): marks go on real targets, in reading order.
            val sbH = (snap.screenH * 0.035f).toInt()
            val items = if (!GUIDANCE) snap.items.take(max) else snap.items.filter { n ->
                val b = n.bounds ?: intArrayOf(n.x, n.y, n.x, n.y)
                b[1] >= sbH && n.label.count { it.isLetter() } >= 2 &&
                    !Regex("^(on|off|вкл|выкл)\\W*$", RegexOption.IGNORE_CASE).matches(n.label.trim())
            }.distinctBy { it.label.lowercase() to (it.y / 40) }
                .sortedWith(compareBy({ it.y / 48 }, { it.x }))
                .let { mergeWrappedLines(it) }.take(max)
            val shapes = ArrayList<pro.potoki.bekon.agent.coach.Shape>()
            if (grid) {
                shapes += pro.potoki.bekon.agent.coach.Shape(
                    pro.potoki.bekon.agent.coach.Shape.Kind.GRID,
                    0f, 0f, snap.screenW.toFloat(), snap.screenH.toFloat(),
                    (args.intOr("cols") ?: 6).toString(),
                    0xFF3B82F6.toInt(),
                )
            }
            items.forEachIndexed { i, n ->
                val b = n.bounds ?: intArrayOf(n.x - 20, n.y - 20, n.x + 20, n.y + 20)
                shapes += pro.potoki.bekon.agent.coach.Shape(
                    pro.potoki.bekon.agent.coach.Shape.Kind.MARK,
                    b[0].toFloat(), b[1].toFloat(), b[2].toFloat(), b[3].toFloat(),
                    (i + 1).toString(),
                )
            }
            val c = coach ?: throw CapabilityException("coach: not available")
            val ttl = (args.intOr("ttlMs") ?: 20_000).toLong()
            if (!c.drawOnScreen(shapes, false, ttl)) {
                throw CapabilityException(if (GUIDANCE) "overlay: SAW off" else "overlay: Gestures off")
            }
            buildJsonObject {
                put("ok", true); put("count", items.size)
                put("marks", buildJsonArray {
                    items.forEachIndexed { i, n ->
                        add(buildJsonObject { put("n", i + 1); put("ref", n.ref); put("text", n.label); put("x", n.x); put("y", n.y) })
                    }
                })
                put("text", "drew ${items.size} numbered marks; tell the user which number to tap")
            }
        }
        "screen_history" -> {
            val s = pro.potoki.bekon.agent.ocr.ScreenHistory.snapshot()
            buildJsonObject {
                put("frames", s.frames)
                put("offsetMin", s.offsetMin); put("offsetMax", s.offsetMax)
                put("fingerprint", s.fingerprint ?: "")
                put("blockCount", s.blocks.size)
                put("fullText", s.fullText.take(12_000))
                put("blocks", buildJsonArray {
                    s.blocks.take(200).forEach { b ->
                        add(buildJsonObject {
                            put("text", b.text)
                            put("bounds", buildJsonArray { b.pageBounds.forEach { add(JsonPrimitive(it)) } })
                            put("frameIndex", b.frameIndex)
                        })
                    }
                })
                put("text", "accumulated ${s.blocks.size} OCR blocks across ${s.frames} frames (in-memory, session only)")
            }
        }
        "full_page_text" -> {
            val s = pro.potoki.bekon.agent.ocr.ScreenHistory.snapshot()
            buildJsonObject {
                put("text", s.fullText.take(20_000))
                put("frames", s.frames)
                put("blockCount", s.blocks.size)
            }
        }
        "nav_mark" -> {
            val label = args.str("label")
            pro.potoki.bekon.agent.nav.NavJournal.mark(label, args.strOr("fingerprint"))
            buildJsonObject { put("ok", true); put("text", "marked screen as '$label'"); put("fingerprint", pro.potoki.bekon.agent.nav.NavJournal.currentFingerprint() ?: "") }
        }
        "nav_history" -> {
            val st = pro.potoki.bekon.agent.nav.NavJournal.state(args.intOr("limit") ?: 30)
            buildJsonObject {
                put("fingerprint", st.fingerprint ?: "")
                put("label", st.label ?: "")
                put("screen_changed", st.changed)
                put("steps", buildJsonArray {
                    st.steps.forEach { s ->
                        add(buildJsonObject {
                            put("atMs", s.atMs); put("fingerprint", s.fingerprint)
                            put("screen_changed", s.screenChanged)
                            s.label?.let { put("label", it) }
                            s.hintSummary?.let { put("hint", it) }
                            s.a11yWindow?.let { put("window", it) }
                        })
                    }
                })
            }
        }
        "current_screen_fingerprint" -> buildJsonObject {
            val st = pro.potoki.bekon.agent.nav.NavJournal.state(1)
            put("fingerprint", st.fingerprint ?: "")
            put("label", st.label ?: "")
            put("screen_changed", st.changed)
        }
        "coach_scroll" -> {
            val c = coach ?: throw CapabilityException("coach: not available")
            if (GUIDANCE) clearHiddenHint()
            val dir = (args.strOr("direction") ?: "down").lowercase()
            val shape = scrollCueShape(dir, args.strOr("text"), args.intOr("x"), args.intOr("y"))
            val append = args.boolOr("append") ?: false
            val ttl = (args.intOr("ttlMs") ?: 15_000).toLong()
            if (!c.drawOnScreen(listOf(shape), append, ttl)) throw CapabilityException("overlay: display-over-other-apps is off")
            val hidden = hiddenFallback(shape.label)
            buildJsonObject {
                put("ok", true); put("direction", dir)
                if (hidden) { put("overlayHidden", true); put("text", HIDDEN_TEXT) }
                else put("text", "showing a bouncing $dir cue \"${shape.label}\". The human scrolls/swipes; call wait_for_change, then find_text again (acrossHistory=true).")
            }
        }
        "guidance_step" -> {
            val text = args.strOr("text") ?: ""
            val step = args.intOr("step")
            val total = args.intOr("total")
            val st = when ((args.strOr("state") ?: "waiting").lowercase()) {
                "working" -> pro.potoki.bekon.agent.status.AgentStatus.State.WORKING
                "done" -> pro.potoki.bekon.agent.status.AgentStatus.State.DONE
                "error" -> pro.potoki.bekon.agent.status.AgentStatus.State.ERROR
                else -> pro.potoki.bekon.agent.status.AgentStatus.State.WAITING
            }
            if (text.isNotBlank()) {
                val req = pro.potoki.bekon.agent.status.AgentStatus.parse(text, st.wire, args.intOr("ttlMs")?.toLong(), false, "top", step, total)
                pro.potoki.bekon.agent.status.AgentStatus.apply(req)
            }
            // The pill is an overlay too: on screens that hide overlays, repeat the step as a notification.
            var pillHidden = false
            if (GUIDANCE && text.isNotBlank()) {
                pillHidden = pro.potoki.bekon.agent.coach.CoachOverlay.screenHidesOverlay
                if (pillHidden) postHint((if (step != null && total != null) "$step/$total · " else "") + text)
            }
            args.strOr("scroll")?.takeIf { it.isNotBlank() }?.let { d ->
                coach?.drawOnScreen(listOf(scrollCueShape(d.lowercase(), null, null, null)), true, (args.intOr("ttlMs") ?: 15_000).toLong())
            }
            buildJsonObject {
                put("ok", true)
                if (pillHidden) put("overlayHidden", true)
                put("text", "guidance step set — wait for userEvents next_step (pill Next) or wait_for_change")
                put("hint", "Do not tap for the user. Draw a mark, then wait.")
            }
        }
        "tap" -> buildJsonObject { put("ok", executor.tap(args.int("x"), args.int("y"))) }
        "swipe" -> {
            val ms = args.intOr("ms") ?: 300
            buildJsonObject { put("ok", executor.swipe(args.int("x1"), args.int("y1"), args.int("x2"), args.int("y2"), ms)) }
        }
        "longPress" -> buildJsonObject { put("ok", executor.longPress(args.int("x"), args.int("y"))) }
        "drag" -> buildJsonObject { put("ok", executor.drag(args.int("x"), args.int("y"))) }
        "release" -> buildJsonObject { put("ok", executor.release()) }
        "type" -> buildJsonObject {
            put("ok", executor.typeText(args.str("text"), replace = args.boolOr("replace") ?: false, asKeys = args.boolOr("asKeys") ?: false))
        }
        "key" -> {
            val keyName = args.str("name")
            val n = args.intOr("n") ?: 1
            val before = if (keyName == "copy" || keyName == "cut") executor.clipboard() else null
            val ok = executor.key(keyName, n)
            buildJsonObject {
                put("ok", ok)
                if (ok && (keyName == "copy" || keyName == "cut")) {
                    var text = executor.clipboard()
                    val deadline = System.currentTimeMillis() + 500
                    while (text == before && System.currentTimeMillis() < deadline) {
                        Thread.sleep(40)
                        text = executor.clipboard()
                    }
                    text?.let { put("text", it) }
                }
            }
        }
        "clipboard" -> {
            val text = executor.clipboard()
                ?: throw IllegalArgumentException("clipboard empty or unavailable")
            buildJsonObject { put("text", text) }
        }
        "nav" -> {
            val action = args.str("action")
            buildJsonObject { put("ok", executor.nav(action)) }
        }
        "shortcuts" -> shortcutStore().listPayload()
        "agent_status" -> {
            val req = pro.potoki.bekon.agent.status.AgentStatus.parse(
                args.strOr("text"), args.strOr("state"), args.intOr("ttlMs")?.toLong(), args.boolOr("clear"), args.strOr("position"), args.intOr("step"), args.intOr("total"))
            val st = pro.potoki.bekon.agent.status.AgentStatus.apply(req)
            val surface = pro.potoki.bekon.agent.status.StatusSurface.current()
            val overlay = pro.potoki.bekon.agent.status.StatusSurface.onScreen(surface)
            val ctx = pro.potoki.bekon.agent.BekonApp.appContext
            if (ctx != null) {
                if (st == null) pro.potoki.bekon.agent.status.AgentAlert.clear(ctx)
                else if (!overlay) pro.potoki.bekon.agent.status.AgentAlert.status(
                    ctx, st, pro.potoki.bekon.agent.access.AgentConnections.connected.value.lastOrNull())
            }
            buildJsonObject {
                put("ok", true)
                put("shown", when {
                    st == null -> "cleared"
                    overlay && st.state == pro.potoki.bekon.agent.status.AgentStatus.State.DONE -> "overlay+notification"
                    overlay -> "overlay"
                    else -> "notification-only"
                })
                put("deviceInfo", deviceInfoJson())
                put("capabilities", pro.potoki.bekon.agent.status.Capabilities.json())
                put("text", when {
                    st == null -> "status cleared"
                    overlay -> "status '${st.text}' (${st.state.wire}) " + pro.potoki.bekon.agent.status.StatusSurface.describe(surface, GUIDANCE) +
                        (if (st.state == pro.potoki.bekon.agent.status.AgentStatus.State.DONE) " and the session notification ('Agent finished')" else "") +
                        (if (st.ttlMs > 0) ", auto-clears in ${st.ttlMs} ms" else ", stays until replaced/cleared or the session stops")
                    else -> "status '${st.text}' (${st.state.wire}) " + pro.potoki.bekon.agent.status.StatusSurface.describe(surface, GUIDANCE)
                } + (if (req.truncated) ". Text was truncated to ${pro.potoki.bekon.agent.status.AgentStatus.MAX_CHARS} chars; keep it under ~60." else "") +
                    ". Device language: ${pro.potoki.bekon.agent.status.AgentStatus.language()} — write status text in the human's language.")
            }
        }
        "connection_info" -> {
            val u = pro.potoki.bekon.agent.handoff.Handoff.currentUrl
            buildJsonObject {
                put("deviceInfo", deviceInfoJson())
                put("capabilities", pro.potoki.bekon.agent.status.Capabilities.json())
                put("endpoint", u?.let { pro.potoki.bekon.agent.handoff.Handoff.endpoint(it) } ?: "")
                put("sourceUrl", pro.potoki.bekon.agent.ui.SourceCode.url)
                put("auth", AUTH_INFO)
                put("text", u?.let { pro.potoki.bekon.agent.handoff.Handoff.text(it) } ?: (if (GUIDANCE) "Bekon Agent MCP (screen guidance: hints only, the human taps). No public tunnel URL yet." else "Bekon Agent MCP (Android remote control). No public tunnel URL yet."))
            }
        }
        "device_setup_guide" -> (setup ?: throw CapabilityException("setup guide: not available")).guide()
        "open_setup_screen" -> {
            val g = setup ?: throw CapabilityException("setup guide: not available")
            buildJsonObject { val opened = g.open(args.str("step"))
            val blocked = opened.startsWith(pro.potoki.bekon.agent.setup.Launcher.BLOCKED)
            val offered = opened.startsWith(pro.potoki.bekon.agent.setup.Launcher.OFFERED)
            put("ok", !blocked && !offered)
            put("result", if (offered) "offered_to_user: the human may tap the notification" else if (blocked) "blocked_background: user must tap notification" else "opened")
            val next = if (GUIDANCE) ". Next: when the human has opened it, ocr_screen / find_text -> show a hint -> wait_for_change."
            else if (executor.health()["touch"] == false)
                ". Accessibility is OFF: you cannot snapshot, tap, scroll or draw on the phone. Next: screenshot, annotate_screenshot the control and show it to the human, then wait_for_change (pixel mode) and screenshot again. The human scrolls and taps."
            else ". Next: coach_script (rules follow scrolling) or snapshot -> coach_draw -> wait_for_change and read the diff. Never wait for a chat reply during setup."
            put("text", opened + next) }
        }
        "shortcut_add" -> {
            val saved = shortcutStore().add(args.str("title"), args.str("kind"), args.str("target"))
            buildJsonObject {
                put("id", saved.id)
                put("title", saved.title)
                put("kind", saved.kind)
                put("target", saved.target)
                put("text", "saved ${saved.id}")
            }
        }
        "shortcut_open" -> {
            val id = args.str("id")
            val item = shortcutStore().open(id)
            if (item.kind != "script") buildJsonObject { put("ok", true); put("text", "opened $id") } else {
                val o = org.json.JSONObject(item.target)
                val rules = pro.potoki.bekon.agent.coach.CoachScript.parse(json.parseToJsonElement(o.getJSONArray("rules").toString()) as kotlinx.serialization.json.JsonArray)
                if (o.optString("app").isNotBlank()) kotlinx.coroutines.delay(1200)
                val r = scripts.start(rules, o.optLong("timeoutMs", 60_000).coerceIn(1_000, 600_000), 250)
                val wait = (args.intOr("waitMs") ?: 20_000).coerceIn(0, WAIT_MAX_MS).toLong()
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { scripts.await(r, wait, false) }
                JsonObject(scriptJson(r) + ("shortcut" to JsonPrimitive(item.title)))
            }
        }
        "shortcut_remove" -> {
            val id = args.str("id")
            val removed = shortcutStore().remove(id)
            if (!removed) throw IllegalArgumentException("no shortcut $id")
            buildJsonObject { put("ok", true); put("text", "removed $id") }
        }
        else -> throw IllegalArgumentException(unknownToolMessage(name))
    }

    private val toolsList: JsonElement = buildJsonArray {
        tool("snapshot", "Accessibility-tree snapshot of the screen (nodes in screen space). Items carry checkable/checked/selected/enabled (switches, radio rows, checkmarks). Returns a baseline token for wait_for_change.", schema())
        tool(
            "wait_for_change",
            "Block until the phone screen meaningfully changes (new window, tap result, toggle flipped, text changed), wait until the UI is quiet for debounceMs, then return. Ignores Bekon's own overlay and status-bar clock/icons. Returns changed, reason, elapsedMs, window {package, activity, title}, diff vs baseline (added/removed/changed incl. 'on -> off'), full new items and a new baseline token. Heads-up notifications, the notification shade and the status bar (SystemUI) never count as a change; a briefly missing accessibility tree is retried. On a change, coach marks are cleared (they would point at the old layout) unless keepMarks=true: pass keepMarks=true when your marks are anchored (ref/anchorText) or the change may be unrelated (e.g. a toast). Returns early with userEvents when the human uses the status-pill menu. If the row you hinted disappears from the diff (list re-sorted/scrolled), snapshot again and verify its state before moving on. On timeout changed=false with the current snapshot: just call again. Coaching loop: coach_draw hint -> wait_for_change -> read diff -> next hint. Never ask the human to say 'done'.",
            schema {
                int("timeoutMs", required = false)
                int("debounceMs", required = false)
                int("settleMaxMs", required = false)
                str("baseline", required = false)
                add("includeScreenshot", buildJsonObject { put("type", "boolean") }, false)
                add("keepMarks", buildJsonObject { put("type", "boolean") }, false)
            },
        )
        tool(
            "screenshot",
            "Capture the screen. scale is a fraction of the real screen (default 0.25, four times smaller). format is jpeg or png. quality is 1-100 and applies to jpeg only. Node coordinates stay in screen space; use width/height to place this image.",
            schema {
                num("scale", required = false)
                int("quality", required = false)
                str("format", enum = listOf("jpeg", "png"), required = false)
            },
        )
        val shapesSchema = buildJsonObject {
            put("type", "array")
            put("description", "Marks in real SCREEN coordinates (same as snapshot/tap). Items: {type:box,x,y,w,h | bounds:[l,t,r,b]}, {type:circle,x,y,r | bounds}, {type:arrow,x1,y1,x2,y2} (head at x2,y2), {type:underline,x1,y1,x2[,y2] | bounds}, {type:text,x,y,label}. Instead of coordinates you can pass ref (from your latest snapshot) or anchorText/anchorContains: the mark is placed on that node and follows it while the layout shifts. Optional label (short, e.g. 'tap here') and color (red/green/blue/yellow/orange or #rrggbb).")
            put("items", buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("type", buildJsonObject { put("type", "string"); put("enum", buildJsonArray { pro.potoki.bekon.agent.coach.Sketch.KINDS.forEach { add(JsonPrimitive(it)) } }) })
                    for (k in listOf("x", "y", "w", "h", "r", "x1", "y1", "x2", "y2")) put(k, buildJsonObject { put("type", "number") })
                    put("bounds", buildJsonObject { put("type", "array"); put("items", buildJsonObject { put("type", "number") }) })
                    put("ref", buildJsonObject { put("type", "string"); put("description", "Anchor to a node of your latest snapshot (e.g. e12): bounds come from it and the mark follows it when the layout shifts") })
                    put("anchorText", buildJsonObject { put("type", "string"); put("description", "Anchor to the node with this exact text/contentDescription on the current screen (clickable node preferred)") })
                    put("anchorContains", buildJsonObject { put("type", "string") })
                    put("label", buildJsonObject { put("type", "string") })
                    put("color", buildJsonObject { put("type", "string") })
                })
                put("required", buildJsonArray { add(JsonPrimitive("type")) })
            })
        }
        tool(
            "annotate_screenshot",
            "Coach the human in chat: screenshot with hand-drawn boxes/circles/arrows/labels drawn on it. Returns an image (default png, scale 0.5) you can show or save. Works whenever screen capture is on.",
            schema {
                add("shapes", shapesSchema, true)
                num("scale", required = false)
                int("quality", required = false)
                str("format", enum = listOf("png", "jpeg"), required = false)
            },
        )
        tool(
            "coach_draw",
            if (GUIDANCE) "Show the human a hint: draw circles/boxes/arrows/underlines/labels on the phone screen (display-over-other-apps overlay; touches pass through, the human taps). Replaces previous marks unless append=true. ttlMs>0 auto-clears. If the overlay permission is off, use annotate_screenshot." else "Coach the human live: draw hand-drawn circles/boxes/arrows/underlines/labels directly on the phone screen (accessibility overlay; touches pass through). Replaces previous marks unless append=true. ttlMs>0 auto-clears. Needs Gestures (accessibility) on; otherwise use annotate_screenshot.",
            schema {
                add("shapes", shapesSchema, true)
                add("append", buildJsonObject { put("type", "boolean") }, false)
                int("ttlMs", required = false)
            },
        )
        tool(
            "agent_status",
            "Show a short status line to the human on the phone (compact pill at the bottom above the nav bar by default, position=top to move it, touches pass through; works across apps). " +
                "Use it so the human knows what you are doing: state=working at the start of a task, waiting when they must act (e.g. tap Allow), done with a short summary when finished " +
                "(also updates the notification to 'Agent finished'), error when stuck. Write text in the human's language (deviceInfo.language, e.g. ru). " +
                "Persists until replaced, clear=true, ttlMs elapses or the session stops. If Accessibility is off it falls back to the session notification text.",
            schema {
                add("text", buildJsonObject { put("type", "string"); put("maxLength", pro.potoki.bekon.agent.status.AgentStatus.MAX_CHARS); put("description", "Short message, ≤ ~60 chars, in the human's language") }, false)
                str("state", enum = listOf("working", "done", "waiting", "error"), required = false)
                int("ttlMs", required = false)
                str("position", enum = listOf("bottom", "top"), required = false)
                int("step", required = false)
                int("total", required = false)
                add("clear", buildJsonObject { put("type", "boolean"); put("description", "Remove the status") }, false)
            },
        )
        tool("user_events", "Read (and consume) what the human told you from the phone's status-pill menu: back_to_app, changed_mind, new_goal {text}, cancel_all. Also attached as userEvents to wait_for_change / wait_for_script results (those return early when one arrives).", schema())
        tool("connection_info", "Bekon handoff: what this server is, its MCP endpoint and core tools.", schema())
        tool(SESSION_INFO, "Read-only session check for stateless agents: send your saved X-Bekon-Client-Id and Authorization: Bearer <bekon_session_token>. Returns alive, status (alive|paused|no_token|invalid|replaced|missing_id), mode, readOnly, expiry. Never prompts the owner and never issues a token: call it before initialize so you don't trigger a new approval.", schema())
        tool("coach_clear", "Remove all coach marks from the phone screen (also stops a running coach_script).", schema())
        tool(
            "coach_script",
            "Upload a mini highlight script the PHONE runs itself (~4x/s) so hints follow scrolling/re-sorting instantly with no agent round-trip. " +
                "rules: ordered list; each tick the FIRST matching rule fires. A rule may also carry do:{type: tap|scroll_down|scroll_up|back|home, target:{text|contains}, settleMs} (nav action, runs on the phone; same format as script shortcuts). once:true = the rule fires at most once (use it for taps whose text stays visible as the next screen's title). Rule: {id, when:{textVisible|contains, textNotVisible|notContains, package, " +
                "checked:{text|contains, value}, selected:{text|contains, value}} (all ANDed; empty when = always), draw:{type: circle|box|underline (around the node; " +
                "target:{text|contains} or the when node; checkable control preferred for checked rules) | arrow_down|arrow_up (scroll hint, centre) | back_hint (circles the nav-bar Back key, or an edge-swipe arrow on gesture nav; label default 'Нажмите Назад') | text, label, color}, done:true ends it}. " +
                "Put the done rule first, then the 'target visible' rule, then a fallback scroll-hint rule. Matching is by visible text/contentDescription (no resource ids). " +
                "Needs Accessibility (Gestures) on: without it there is no node tree and no overlay; use screenshot + annotate_screenshot instead. Starting a script replaces the previous one.",
            schema {
                add("rules", buildJsonObject { put("type", "array"); put("items", buildJsonObject { put("type", "object") }) }, true)
                int("timeoutMs", required = false)
                int("intervalMs", required = false)
            },
        )
        tool(
            "wait_for_script",
            "Block until the running coach_script ends (done rule fired, timeout, stopped, error) or timeoutMs passes (default 30s, max 90s; just call again while status=running). " +
                "returnOnRuleChange=true also returns when a different rule starts showing. Returns status, doneRule, currentRule and the fired rule history with times.",
            schema {
                int("timeoutMs", required = false)
                add("returnOnRuleChange", buildJsonObject { put("type", "boolean") }, false)
            },
        )
        tool("coach_script_stop", "Stop the running coach_script and clear its marks.", schema())
        tool(
            "scan_list",
            "Scroll the current list to its end (stops when nothing moves, max maxPages) and return the union of all item names with checked states plus compact JPEG page screenshots, then scroll back (restore=true). Use it instead of blind scrolling when looking for a differently named item. Needs Accessibility.",
            schema {
                str("direction", enum = listOf("down", "up"), required = false)
                int("maxPages", required = false)
                add("restore", buildJsonObject { put("type", "boolean") }, false)
            },
        )
        tool(
            "device_setup_guide",
            "Phone manufacturer/model/SDK/ROM and the ordered OEM-specific setup steps Bekon needs (restricted settings, accessibility, notifications, autostart, battery, background popups, screen capture 'Entire screen'), each with EN/RU text, settings path and live status ok/todo/unknown. Use before coaching a user through setup.",
            schema(),
        )
        tool("open_setup_screen", if (GUIDANCE) "Offer the human a settings screen for a step id from device_setup_guide: shows a 'Tap to open' notification; Bekon never opens it itself. Extra ids: app_details, play_listing. Then wait_for_change." else "Open the best settings screen for a step id from device_setup_guide (tries OEM intents with fallbacks). Extra ids: app_details (Android App info for Bekon), play_listing (Bekon in Google Play). If the result is blocked_background, the human must tap the notification.", schema { str("step") })
        tool("open_url", if (GUIDANCE) "Offer the human an https URL: shows a 'Tap to open' notification; Bekon never opens it itself. Then wait_for_change." else "Open an https URL on the phone in the default browser/app without saving a shortcut. Returns blocked_background if Android blocked it (the human then taps a notification).", schema { str("url") })
        tool("tap", "Tap at screen coordinates", schema { int("x"); int("y") })
        tool("swipe", "Swipe between two points", schema { int("x1"); int("y1"); int("x2"); int("y2"); int("ms", required = false) })
        tool("longPress", "Long-press at a screen coordinate", schema { int("x"); int("y") })
        tool("drag", "Press and hold a finger down (release to let go)", schema { int("x"); int("y") })
        tool("release", "Lift a held finger", schema())
        tool(
            "type",
            "Enter text into the focused field. Uses accessibility SET_TEXT (placeholder is never kept), then paste, then key presses. replace=true clears the field first. asKeys=true forces key presses (terminals like Termux; needs the optional Bekon Keys keyboard or Android 13+).",
            schema {
                str("text")
                add("replace", buildJsonObject { put("type", "boolean") }, false)
                add("asKeys", buildJsonObject { put("type", "boolean") }, false)
            },
        )
        tool("key", "Editing key. copy and cut also return the clipboard text.", schema {
            str("name", enum = listOf("backspace", "enter", "clear", "selectAll", "copy", "cut", "paste"))
            int("n", required = false)
        })
        tool("clipboard", "Read plain text from the system clipboard", schema())
        tool("nav", "Global navigation: home/back/recents", schema { str("action", enum = listOf("home", "back", "recents")) })
        tool(
            "shortcuts",
            "List saved shortcuts. Call this after connecting, before hunting through the home screen. A row may already open the app or page the task needs.",
            schema(),
        )
        tool(
            "shortcut_add",
            "Save a shortcut. kind is app (target is the Android package name), url (an http or https link) or script: target is JSON " +
                "{\"app\"?: package to launch first, \"rules\": [same rule format as coach_script, plus do:{type: tap|scroll_down|scroll_up|back|home, target:{text|contains}, settleMs}], \"timeoutMs\"?}. " +
                "A script replays on the phone with one shortcut_open (e.g. open Settings > scroll to 'Bekon Agent' > tap). Actions need Accessibility. The same target is not saved twice.",
            schema {
                str("title")
                str("kind", enum = listOf("app", "url", "script"))
                str("target")
            },
        )
        tool("shortcut_open", "Open a saved shortcut by id. An app launches on the phone. A url opens in the browser. A script runs its rules on the phone and waits up to waitMs (default 20s) for done; then use wait_for_script if still running.", schema { str("id"); int("waitMs", required = false) })

        tool(
            "ocr_screen",
            "Guidance/OCR: run on-device text recognition on the current screenshot and return text blocks with screen-space bounds and refs (e1..). Does not tap. Prefer this over accessibility snapshot in guidance mode.",
            schema(),
        )
        tool(
            "find_text",
            "Find on-screen text (OCR or current snapshot) and optionally draw a highlight/arrow/mark on the first hit. acrossHistory=true searches the in-memory scroll-accumulated OCR store and may return scrollHint (further down/above). The text to find goes in `text` (`query` is accepted as an alias). User taps; Bekon never taps.",
            schema {
                str("text")
                str("query", required = false)
                add("contains", buildJsonObject { put("type", "boolean") }, false)
                add("acrossHistory", buildJsonObject { put("type", "boolean") }, false)
                add("draw", buildJsonObject { put("type", "boolean") }, false)
                str("mark", enum = listOf("highlight", "arrow", "mark"), required = false)
                str("caption", required = false)
                int("ttlMs", required = false)
            },
        )
        tool(
            "som_marks",
            "Draw numbered Set-of-Marks badges on OCR/snapshot elements (and optional coordinate grid). Tell the human which number to tap. Guidance-friendly.",
            schema {
                int("max", required = false)
                add("grid", buildJsonObject { put("type", "boolean") }, false)
                int("cols", required = false)
                int("ttlMs", required = false)
            },
        )
        tool(
            "screen_history",
            "In-memory scroll-aware OCR accumulation for this session (guidance). Returns deduplicated blocks with page-space bounds, frame count, fullText. Cleared on session stop / page change. Not stored on disk.",
            schema(),
        )
        tool(
            "full_page_text",
            "Plain text of the accumulated scroll OCR history (session memory only).",
            schema(),
        )
        tool(
            "nav_mark",
            "Label the current screen in the in-memory navigation journal (breadcrumb). Optional fingerprint override.",
            schema { str("label"); str("fingerprint", required = false) },
        )
        tool(
            "nav_history",
            "In-memory navigation journal: fingerprints, agent labels, last hints. Full flavor may include a11y window keys.",
            schema { int("limit", required = false) },
        )
        tool(
            "current_screen_fingerprint",
            "Current OCR-based screen fingerprint (and optional agent label).",
            schema(),
        )
        tool(
            "guidance_step",
            "Set the status pill for a guided step (waiting/working/done) with optional step/total; scroll=up|down|left|right also shows a bouncing scroll cue pointing toward the content to reach (right = next home-screen page). Then wait for userEvents next_step or wait_for_change. Never tap for the user.",
            schema {
                str("text", required = false)
                str("state", enum = listOf("waiting", "working", "done", "error"), required = false)
                int("step", required = false); int("total", required = false); int("ttlMs", required = false)
                str("scroll", enum = listOf("up", "down", "left", "right"), required = false)
            },
        )
        tool(
            "coach_scroll",
            "Show a bouncing hand-drawn scroll arrow with a short caption. direction = where the content the human must reach is (NOT the finger movement): down = see what is below, up = what is above, left = what is to the left, right = what is to the right (right = next home-screen page / next tab, left = previous). The arrow points that way. Optional text (≤ 5 words, UI language), x/y centre, ttlMs (default 15000), append. Then wait_for_change. The human scrolls; Bekon never does.",
            schema {
                str("direction", enum = listOf("up", "down", "left", "right"))
                str("text", required = false)
                int("x", required = false); int("y", required = false); int("ttlMs", required = false)
                add("append", buildJsonObject { put("type", "boolean") }, false)
            },
        )
        tool("shortcut_remove", "Delete a saved shortcut by id.", schema { str("id") })
    }

    /** Attach (and consume) pending pill-menu events so the agent sees them in its wait loop. */
    private fun withUserEvents(o: JsonObject): JsonObject {
        val ev = pro.potoki.bekon.agent.status.UserEvents.drain()
        if (ev.isEmpty()) return o
        val summary = (o["summary"] as? JsonPrimitive)?.content
        return JsonObject(o + ("userEvents" to pro.potoki.bekon.agent.status.UserEvents.json(ev)) +
            ("userEventsNote" to JsonPrimitive(pro.potoki.bekon.agent.status.UserEvents.DOC)) +
            (if (summary != null) mapOf("summary" to JsonPrimitive("USER EVENT: " + ev.joinToString { it.type + (it.text?.let { t -> " '$t'" } ?: "") } + "\n" + summary)) else emptyMap()))
    }

    private fun deviceInfoJson() = buildJsonObject {
        put("locale", pro.potoki.bekon.agent.status.AgentStatus.localeTag())
        put("language", pro.potoki.bekon.agent.status.AgentStatus.language())
        put("languageName", pro.potoki.bekon.agent.status.AgentStatus.languageName())
        put("manufacturer", android.os.Build.MANUFACTURER ?: "")
        put("model", android.os.Build.MODEL ?: "")
        put("appVersionName", pro.potoki.bekon.agent.BuildConfig.VERSION_NAME)
        put("appVersionCode", pro.potoki.bekon.agent.BuildConfig.VERSION_CODE)
    }

    private fun shortcutStore(): ShortcutStore =
        shortcuts ?: throw IllegalArgumentException("shortcuts are not available")

    private fun ShortcutStore.listPayload() = buildJsonObject {
        val rows = list()
        put("items", buildJsonArray {
            for (row in rows) {
                add(buildJsonObject {
                    put("id", row.id)
                    put("title", row.title)
                    put("kind", row.kind)
                    put("target", row.target)
                })
            }
        })
        put("text", if (rows.isEmpty()) "no shortcuts" else rows.joinToString("\n") {
            "${it.id} ${it.kind} ${it.title} ${it.target}"
        })
    }

    private fun JsonArrayBuilder.tool(name: String, desc: String, schema: JsonObject) {
        add(buildJsonObject {
            put("name", name); put("description", desc)
            put("inputSchema", schema)
        })
    }

    // --- schema builder: properties map + required list, never empty ---

    private data class Prop(val json: JsonObject, val required: Boolean)

    private fun schema(block: PropBuilder.() -> Unit = {}): JsonObject {
        val pb = PropBuilder()
        pb.block()
        return buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                pb.props.forEach { (k, p) -> put(k, p.json) }
            })
            val req = pb.props.filterValues { it.required }.keys
            if (req.isNotEmpty()) put("required", buildJsonArray { req.forEach { add(JsonPrimitive(it)) } })
        }
    }

    private class PropBuilder {
        val props = LinkedHashMap<String, Prop>()
        fun int(k: String, required: Boolean = true) = add(k, buildJsonObject { put("type", "integer") }, required)
        fun num(k: String, required: Boolean = true) = add(k, buildJsonObject { put("type", "number") }, required)
        fun str(k: String, enum: List<String>? = null, required: Boolean = true) =
            add(k, buildJsonObject {
                put("type", "string")
                if (enum != null) put("enum", buildJsonArray { enum.forEach { add(JsonPrimitive(it)) } })
            }, required)
        fun add(k: String, o: JsonObject, required: Boolean) { props[k] = Prop(o, required) }
    }

    private fun JsonObject.int(key: String): Int = when (val v = this[key]) {
        is JsonPrimitive -> {
            val c = v.content
            if (c.startsWith('"')) c.substring(1, c.length - 1).toInt() else c.toInt()
        }
        else -> throw IllegalArgumentException("$key is not an integer")
    }
    private fun JsonObject.intOr(key: String): Int? = try { int(key) } catch (e: Exception) { null }
    private fun JsonObject.floatOr(key: String): Float? {
        val v = this[key] as? JsonPrimitive ?: return null
        return try { v.content.toFloat() } catch (e: Exception) { null }
    }
    private fun JsonObject.boolOr(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.content?.trim('"')?.toBooleanStrictOrNull()
    private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.content ?: ""
    private fun JsonObject.strOr(key: String): String? {
        val s = (this[key] as? JsonPrimitive)?.content ?: return null
        return s.ifBlank { null }
    }

    /** session_info payload: liveness only; never the token, other clients' ids, screen or device data. */
    private fun sessionInfoJson(st: ClientGate.Status): JsonObject = buildJsonObject {
        put("alive", st == ClientGate.Status.ALIVE || st == ClientGate.Status.PAUSED)
        put("status", st.name.lowercase())
        put("paused", st == ClientGate.Status.PAUSED)
        if (st == ClientGate.Status.ALIVE || st == ClientGate.Status.PAUSED) {
            put("mode", if (GUIDANCE) "guidance" else "control")
            put("readOnly", readOnly())
        }
        put("expiry", "no fixed time: the token ends when the owner stops the session, disconnects you, or approves another agent")
        put("next", when (st) {
            ClientGate.Status.ALIVE -> "keep using your saved id and token; do NOT call initialize without the token"
            ClientGate.Status.PAUSED -> "the owner paused the session; wait and check again, do not re-initialize"
            ClientGate.Status.NO_TOKEN, ClientGate.Status.INVALID ->
                "no live session for this id/token: call initialize WITHOUT a token (same saved uniqueId) to ask the owner"
            ClientGate.Status.PENDING -> "the owner has not tapped Allow yet: repeat initialize with the same uniqueId and this token every 3-5 s"
            ClientGate.Status.REPLACED -> "another agent replaced you; wait until the owner restarts the session"
            ClientGate.Status.MISSING_ID -> ClientGate.MISSING_ID
        })
    }

    private fun clientIdOf(call: ApplicationCall, req: JsonRpcRequest): String? {
        val header = call.request.headers[ClientGate.HEADER]?.trim()?.takeIf { it.isNotEmpty() }
        if (header != null) return header
        val params = req.params as? JsonObject ?: return null
        val info = params["clientInfo"] as? JsonObject
        val fromInfo = (info?.get("uniqueId") as? JsonPrimitive)?.content?.trim()
        if (!fromInfo.isNullOrEmpty()) return fromInfo
        return (params["uniqueId"] as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun clientIp(call: ApplicationCall): String {
        val headers = call.request.headers
        val cf = headers["CF-Connecting-IP"]?.trim()?.takeIf { it.isNotEmpty() }
        if (cf != null) return cf
        val forwarded = headers["X-Forwarded-For"]?.split(",")?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        if (forwarded != null) return forwarded
        return call.request.local.remoteHost
    }

    private fun errElement(msg: String): JsonElement = buildJsonObject {
        put("code", -32000); put("message", msg)
    }

    private suspend fun respondJson(call: ApplicationCall, resp0: JsonRpcResponse) {
        // The request that was just approved also carries the new session token in its result.
        val issued = call.attributes.getOrNull(ISSUED_TOKEN)
        val r = resp0.result
        val resp = if (issued != null && r is JsonObject) resp0.copy(result = JsonObject(r + mapOf(
            ClientGate.TOKEN_FIELD to JsonPrimitive(issued),
            "bekon_auth" to JsonPrimitive(ClientGate.TOKEN_ISSUED),
        ))) else resp0
        call.respondText(json.encodeToString(JsonRpcResponse.serializer(), resp), ContentType.Application.Json)
    }
}
