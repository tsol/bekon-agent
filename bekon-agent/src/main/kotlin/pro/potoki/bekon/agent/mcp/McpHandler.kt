package pro.potoki.bekon.agent.mcp

import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
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
    private val shortcuts: ShortcutStore? = null,
    private val coach: pro.potoki.bekon.agent.coach.CoachSurface? = executor as? pro.potoki.bekon.agent.coach.CoachSurface,
    private val setup: pro.potoki.bekon.agent.setup.DeviceSetupGuide? = null,
) {
    companion object {
        /** Default stays well below Cloudflare's ~100s proxy read timeout (HTTP 524). */
        const val WAIT_DEFAULT_MS = 30_000
        const val WAIT_MAX_MS = 90_000
        const val POLL_MS = 1_000L
        const val COACH_INSTRUCTIONS =
            "Coaching a human (e.g. device setup): you decide what to coach; Bekon only waits. " +
                "Loop: snapshot (keep its baseline token) -> coach_draw a hint on the exact control -> " +
                "wait_for_change with that baseline -> read the diff (added/removed/changed, toggles 'off -> on') -> next hint or coach_clear when done. " +
                "Never ask the human to type or say 'done'. Never stop to wait for chat replies during setup; " +
                "if wait_for_change times out (changed=false), re-check the screen and call it again. " +
                "Prefer coach_script for multi-step or scrolling targets: upload rules (done when the toggle is on; circle the row when its text is visible; " +
                "arrow_down 'Прокрутите ниже' when it is not) and call wait_for_script; the phone moves the highlight itself. " +
                "Before Accessibility (Gestures) is on you cannot tap, scroll, snapshot or draw on the phone: only screenshot/annotate_screenshot work, " +
                "so coach the human in chat with annotated screenshots to scroll and tap themselves (never use tap/swipe for the human). Marks are cleared automatically when wait_for_change sees a change."
        const val READ_ONLY = "device is in read-only mode so this action was declined."
        val WRITE_TOOLS = setOf(
            "tap", "swipe", "longPress", "drag", "release", "type", "key", "nav", "shortcut_open",
            "coach_draw", "coach_clear", "open_setup_screen", "coach_script", "coach_script_stop", "scan_list",
        )
    }


    private val json = Json { ignoreUnknownKeys = true }
    private val scripts by lazy { CoachScriptRunner(executor, coach) }

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
    private fun remember(items: List<pro.potoki.bekon.agent.core.UiNode>): String {
        val t = pro.potoki.bekon.agent.core.ScreenDiff.token(items)
        synchronized(baselines) { baselines[t] = items }
        return t
    }

    @Serializable
    data class JsonRpcRequest(val id: Int = 0, val method: String, val params: JsonElement? = null)

    @Serializable
    data class JsonRpcResponse(val jsonrpc: String = "2.0", val id: Int = 0, val result: JsonElement? = null, val error: JsonElement? = null)

    suspend fun handle(call: ApplicationCall) {
        val body = try { call.receiveText() } catch (e: Exception) { "" }
        if (body.isBlank()) { respondJson(call, JsonRpcResponse(id = 0, error = errElement("empty request"))); return }
        val req = try {
            json.decodeFromString(JsonRpcRequest.serializer(), body)
        } catch (e: Exception) {
            respondJson(call, JsonRpcResponse(id = 0, error = errElement("invalid json"))); return
        }
        val id = req.id
        val blocked = gate?.rejection(clientIdOf(call, req), clientIp(call))
        if (blocked != null) {
            respondJson(call, JsonRpcResponse(id = id, error = errElement(blocked)))
            return
        }
        when (req.method) {
            "initialize" -> respondJson(call, JsonRpcResponse(id = id, result = buildJsonObject {
                put("protocolVersion", "2025-03-26")
                put("capabilities", buildJsonObject { put("tools", JsonObject(emptyMap())) })
                put("serverInfo", buildJsonObject { put("name", "bekon-agent"); put("version", "0.1") })
                put("instructions", ClientGate.INSTRUCTIONS + " " + COACH_INSTRUCTIONS)
            }))
            "notifications/initialized" -> respondJson(call, JsonRpcResponse(id = id, result = JsonObject(emptyMap())))
            "ping" -> respondJson(call, JsonRpcResponse(id = id, result = JsonObject(emptyMap())))
            "tools/list" -> respondJson(call, JsonRpcResponse(id = id, result = buildJsonObject { put("tools", toolsList) }))
            "tools/call" -> {
                val params = req.params?.jsonObject ?: JsonObject(emptyMap())
                val name = (params["name"] as? JsonPrimitive)?.content ?: ""
                val args = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())
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
            else -> respondJson(call, JsonRpcResponse(id = id, error = errElement("unknown method ${req.method}")))
        }
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
    private suspend fun waitForChange(args: JsonObject): JsonObject {
        val timeout = (args.intOr("timeoutMs") ?: WAIT_DEFAULT_MS).coerceIn(0, WAIT_MAX_MS).toLong()
        val debounce = (args.intOr("debounceMs") ?: 600).coerceIn(0, 5000).toLong()
        val settleMax = (args.intOr("settleMaxMs") ?: 3000).coerceIn(debounce.toInt(), 10000).toLong()
        val withShot = args.boolOr("includeScreenshot") ?: false
        val sig = executor.changeSignal()
        val t0 = System.nanoTime()
        fun elapsed() = (System.nanoTime() - t0) / 1_000_000
        val given = args.strOr("baseline")
        var baseItems: List<pro.potoki.bekon.agent.core.UiNode>? = given?.let { synchronized(baselines) { baselines[it] } }
        val baseToken: String = given ?: remember(executor.snapshot().items.also { baseItems = it })
        var seq = sig?.seq ?: 0L
        var lastPoll = 0L
        var reason = ""
        var result: pro.potoki.bekon.agent.core.ScreenSnapshot? = null
        var cur: pro.potoki.bekon.agent.core.ScreenSnapshot? = null
        var first = true
        while (true) {
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
        val fin = result ?: cur ?: executor.snapshot()
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
            JsonObject(snapshotJson(s, t) + ("window" to foregroundJson()))
        }
        "wait_for_change" -> waitForChange(args)
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
            val c = coach ?: throw CapabilityException("coach: not available")
            val shapes = pro.potoki.bekon.agent.coach.Sketch.parse(args["shapes"] as? kotlinx.serialization.json.JsonArray)
            val append = (args["append"] as? JsonPrimitive)?.content == "true"
            val ttl = (args.intOr("ttlMs") ?: 0).toLong()
            if (!c.drawOnScreen(shapes, append, ttl)) {
                throw CapabilityException("overlay: accessibility service (Gestures) is off. Use annotate_screenshot and show the image to the user instead.")
            }
            buildJsonObject { put("ok", true); put("text", "drew ${shapes.size} mark(s) on the phone screen. Now call wait_for_change (pass your last baseline) and read its diff; do not ask the human to say 'done'.") }
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
            scriptJson(r)
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
            val c = coach ?: throw CapabilityException("coach: not available")
            scripts.run?.takeIf { it.status == "running" }?.let { scripts.stop() }
            c.clearScreen()
            buildJsonObject { put("ok", true); put("text", "cleared") }
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
        "connection_info" -> {
            val u = pro.potoki.bekon.agent.handoff.Handoff.currentUrl
            buildJsonObject {
                put("endpoint", u?.let { pro.potoki.bekon.agent.handoff.Handoff.endpoint(it) } ?: "")
                put("text", u?.let { pro.potoki.bekon.agent.handoff.Handoff.text(it) } ?: "Bekon Agent MCP (Android remote control). No public tunnel URL yet.")
            }
        }
        "device_setup_guide" -> (setup ?: throw CapabilityException("setup guide: not available")).guide()
        "open_setup_screen" -> {
            val g = setup ?: throw CapabilityException("setup guide: not available")
            buildJsonObject { put("ok", true); put("text", g.open(args.str("step")) + ". Next: snapshot, coach_draw a hint on the control to change, then wait_for_change and read the diff. Never wait for a chat reply during setup.") }
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
        else -> throw IllegalArgumentException("unknown tool $name")
    }

    private val toolsList: JsonElement = buildJsonArray {
        tool("snapshot", "Accessibility-tree snapshot of the screen (nodes in screen space). Items carry checkable/checked/selected/enabled (switches, radio rows, checkmarks). Returns a baseline token for wait_for_change.", schema())
        tool(
            "wait_for_change",
            "Block until the phone screen meaningfully changes (new window, tap result, toggle flipped, text changed), wait until the UI is quiet for debounceMs, then return. Ignores Bekon's own overlay and status-bar clock/icons. Returns changed, reason, elapsedMs, window {package, activity, title}, diff vs baseline (added/removed/changed incl. 'on -> off'), full new items and a new baseline token. On a change, coach marks are cleared (they would point at the old layout) unless keepMarks=true. If the row you hinted disappears from the diff (list re-sorted/scrolled), snapshot again and verify its state before moving on. On timeout changed=false with the current snapshot: just call again. Coaching loop: coach_draw hint -> wait_for_change -> read diff -> next hint. Never ask the human to say 'done'.",
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
            put("description", "Marks in real SCREEN coordinates (same as snapshot/tap). Items: {type:box,x,y,w,h | bounds:[l,t,r,b]}, {type:circle,x,y,r | bounds}, {type:arrow,x1,y1,x2,y2} (head at x2,y2), {type:underline,x1,y1,x2[,y2] | bounds}, {type:text,x,y,label}. Optional label (short, e.g. 'tap here') and color (red/green/blue/yellow/orange or #rrggbb).")
            put("items", buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("type", buildJsonObject { put("type", "string"); put("enum", buildJsonArray { pro.potoki.bekon.agent.coach.Sketch.KINDS.forEach { add(JsonPrimitive(it)) } }) })
                    for (k in listOf("x", "y", "w", "h", "r", "x1", "y1", "x2", "y2")) put(k, buildJsonObject { put("type", "number") })
                    put("bounds", buildJsonObject { put("type", "array"); put("items", buildJsonObject { put("type", "number") }) })
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
            "Coach the human live: draw hand-drawn circles/boxes/arrows/underlines/labels directly on the phone screen (accessibility overlay; touches pass through). Replaces previous marks unless append=true. ttlMs>0 auto-clears. Needs Gestures (accessibility) on; otherwise use annotate_screenshot.",
            schema {
                add("shapes", shapesSchema, true)
                add("append", buildJsonObject { put("type", "boolean") }, false)
                int("ttlMs", required = false)
            },
        )
        tool("connection_info", "Bekon handoff: what this server is, its MCP endpoint and core tools.", schema())
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
        tool("open_setup_screen", "Open the best settings screen for a step id from device_setup_guide (tries OEM intents with fallbacks).", schema { str("step") })
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
        tool("shortcut_remove", "Delete a saved shortcut by id.", schema { str("id") })
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

    private suspend fun respondJson(call: ApplicationCall, resp: JsonRpcResponse) {
        call.respondText(json.encodeToString(JsonRpcResponse.serializer(), resp), ContentType.Application.Json)
    }
}
