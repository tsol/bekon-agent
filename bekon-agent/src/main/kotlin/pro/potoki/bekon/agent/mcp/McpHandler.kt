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
import pro.potoki.bekon.agent.core.ActionExecutor
import pro.potoki.bekon.agent.core.CapabilityException
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
class McpHandler(private val executor: ActionExecutor) {

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
        when (req.method) {
            "initialize" -> respondJson(call, JsonRpcResponse(id = id, result = buildJsonObject {
                put("protocolVersion", "2025-03-26")
                put("capabilities", buildJsonObject { put("tools", JsonObject(emptyMap())) })
                put("serverInfo", buildJsonObject { put("name", "bekon-agent"); put("version", "0.1") })
            }))
            "notifications/initialized" -> respondJson(call, JsonRpcResponse(id = id, result = JsonObject(emptyMap())))
            "ping" -> respondJson(call, JsonRpcResponse(id = id, result = JsonObject(emptyMap())))
            "tools/list" -> respondJson(call, JsonRpcResponse(id = id, result = buildJsonObject { put("tools", toolsList) }))
            "tools/call" -> {
                val params = req.params?.jsonObject ?: JsonObject(emptyMap())
                val name = (params["name"] as? JsonPrimitive)?.content ?: ""
                val args = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())
                try {
                    val data = callTool(name, args)
                    val content: JsonObject = when {
                        name == "screenshot" -> data
                        name == "snapshot" -> data
                        else -> buildJsonObject { put("type", "text"); put("text", "ok") }
                    }
                    val toolResult = buildJsonObject {
                        put("content", buildJsonArray {
                            if (name == "screenshot") {
                                add(buildJsonObject { put("type", "image"); put("data", (data as JsonObject)["data"]?.toString() ?: "") })
                            } else {
                                add(content)
                            }
                        })
                        put("structuredContent", data)
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
            val f = executor.screenshot()
            buildJsonObject {
                put("mime", "image/jpeg")
                put("width", f.width); put("height", f.height)
                put("data", Base64.getEncoder().encodeToString(f.bytes))
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
        "type" -> buildJsonObject { put("ok", executor.type(args.str("text"))) }
        "key" -> {
            val n = args.intOr("n") ?: 1
            buildJsonObject { put("ok", executor.key(args.str("name"), n)) }
        }
        "nav" -> {
            val action = args.str("action")
            buildJsonObject { put("ok", executor.nav(action)) }
        }
        else -> throw IllegalArgumentException("unknown tool $name")
    }

    private val toolsList: JsonElement = buildJsonArray {
        tool("snapshot", "Accessibility-tree snapshot of the screen (nodes in screen space)", schema())
        tool("screenshot", "Capture a JPEG screenshot of the screen (base64)", schema())
        tool("tap", "Tap at screen coordinates", schema { int("x"); int("y") })
        tool("swipe", "Swipe between two points", schema { int("x1"); int("y1"); int("x2"); int("y2"); int("ms", required = false) })
        tool("longPress", "Long-press at a screen coordinate", schema { int("x"); int("y") })
        tool("drag", "Press and hold a finger down (release to let go)", schema { int("x"); int("y") })
        tool("release", "Lift a held finger", schema())
        tool("type", "Type text as key presses into the focused field", schema { str("text") })
        tool("key", "Send an editing key: backspace/enter/clear/selectAll", schema {
            str("name", enum = listOf("backspace", "enter", "clear", "selectAll"))
            int("n", required = false)
        })
        tool("nav", "Global navigation: home/back/recents", schema { str("action", enum = listOf("home", "back", "recents")) })
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
    private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.content ?: ""

    private fun errElement(msg: String): JsonElement = buildJsonObject {
        put("code", -32000); put("message", msg)
    }

    private suspend fun respondJson(call: ApplicationCall, resp: JsonRpcResponse) {
        call.respondText(json.encodeToString(JsonRpcResponse.serializer(), resp), ContentType.Application.Json)
    }
}
