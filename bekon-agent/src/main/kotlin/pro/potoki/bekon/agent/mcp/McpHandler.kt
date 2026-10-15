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
        const val READ_ONLY = "device is in read-only mode so this action was declined."
        val WRITE_TOOLS = setOf(
            "tap", "swipe", "longPress", "drag", "release", "type", "key", "nav", "shortcut_open",
            "coach_draw", "coach_clear", "open_setup_screen",
        )
    }


    private val json = Json { ignoreUnknownKeys = true }

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
                put("instructions", ClientGate.INSTRUCTIONS)
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
                    val data = callTool(name, args)
                    val content: JsonObject = when {
                        name == "screenshot" || name == "annotate_screenshot" -> data
                        // MCP content items must carry a type; ship the tree as JSON text.
                        name == "snapshot" -> buildJsonObject { put("type", "text"); put("text", data.toString()) }
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
    private fun callTool(name: String, args: JsonObject): JsonObject = when (name) {
        "snapshot" -> {
            val s = executor.snapshot()
            buildJsonObject {
                put("screenW", s.screenW); put("screenH", s.screenH)
                put("imageW", s.imageW); put("imageH", s.imageH)
                put("items", buildJsonArray {
                    for (n in s.items) {
                        add(buildJsonObject {
                            put("ref", n.ref); put("name", n.label)
                            put("x", n.x); put("y", n.y)
                            put("clickable", n.clickable)
                            put("bounds", buildJsonArray { n.bounds?.forEach { add(JsonPrimitive(it)) } ?: Unit })
                        })
                    }
                })
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
            val c = coach ?: throw CapabilityException("coach: not available")
            val shapes = pro.potoki.bekon.agent.coach.Sketch.parse(args["shapes"] as? kotlinx.serialization.json.JsonArray)
            val append = (args["append"] as? JsonPrimitive)?.content == "true"
            val ttl = (args.intOr("ttlMs") ?: 0).toLong()
            if (!c.drawOnScreen(shapes, append, ttl)) {
                throw CapabilityException("overlay: accessibility service (Gestures) is off. Use annotate_screenshot and show the image to the user instead.")
            }
            buildJsonObject { put("ok", true); put("text", "drew ${shapes.size} mark(s) on the phone screen") }
        }
        "coach_clear" -> {
            val c = coach ?: throw CapabilityException("coach: not available")
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
        "type" -> buildJsonObject { put("ok", executor.type(args.str("text"))) }
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
        "device_setup_guide" -> (setup ?: throw CapabilityException("setup guide: not available")).guide()
        "open_setup_screen" -> {
            val g = setup ?: throw CapabilityException("setup guide: not available")
            buildJsonObject { put("ok", true); put("text", g.open(args.str("step"))) }
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
            shortcutStore().open(id)
            buildJsonObject { put("ok", true); put("text", "opened $id") }
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
        tool("snapshot", "Accessibility-tree snapshot of the screen (nodes in screen space)", schema())
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
        tool("coach_clear", "Remove all coach marks from the phone screen.", schema())
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
        tool("type", "Type text as key presses into the focused field", schema { str("text") })
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
            "Save a shortcut. kind is app (target is the Android package name) or url (target is an http or https link). The same target is not saved twice.",
            schema {
                str("title")
                str("kind", enum = listOf("app", "url"))
                str("target")
            },
        )
        tool("shortcut_open", "Open a saved shortcut by id. An app launches on the phone. A url opens in the browser.", schema { str("id") })
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
