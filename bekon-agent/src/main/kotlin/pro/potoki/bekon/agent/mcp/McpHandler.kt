package pro.potoki.bekon.agent.mcp

import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import pro.potoki.bekon.agent.core.ActionExecutor
import pro.potoki.bekon.agent.core.CapabilityException
import java.util.Base64

/**
 * Minimal MCP Streamable HTTP server (current MCP spec). JSON-RPC 2.0 envelope over
 * HTTP POST on `/mcp`. Tools call into ActionExecutor — the same single executor that
 * serves the REST routes, so they cannot disagree.
 */
class McpHandler(private val executor: ActionExecutor) {

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class JsonRpcRequest(val id: Int = 0, val method: String, val params: JsonElement? = null)

    @Serializable
    data class JsonRpcResponse(val jsonrpc: String = "2.0", val id: Int = 0, val result: JsonElement? = null, val error: JsonElement? = null)

    suspend fun handle(call: ApplicationCall) {
        val body = try {
            call.receiveText()
        } catch (e: Exception) {
            ""
        }
        if (body.isBlank()) {
            respondJson(call, JsonRpcResponse(id = 0, error = errElement("empty request")))
            return
        }
        val req = try {
            json.decodeFromString(JsonRpcRequest.serializer(), body)
        } catch (e: Exception) {
            respondJson(call, JsonRpcResponse(id = 0, error = errElement("invalid json")))
            return
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
            "tools/list" -> respondJson(call, JsonRpcResponse(id = id, result = buildJsonObject {
                put("tools", toolsList)
            }))
            "tools/call" -> {
                val params = req.params?.jsonObject ?: JsonObject(emptyMap())
                val name = (params["name"] as? JsonPrimitive)?.content ?: ""
                val args = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())
                try {
                    val result = callTool(name, args)
                    respondJson(call, JsonRpcResponse(id = id, result = result))
                } catch (e: CapabilityException) {
                    respondJson(call, JsonRpcResponse(id = id, error = errElement(e.message ?: "capability missing")))
                } catch (e: Exception) {
                    respondJson(call, JsonRpcResponse(id = id, error = errElement(e.message ?: "error")))
                }
            }
            else -> respondJson(call, JsonRpcResponse(id = id, error = errElement("unknown method ${req.method}")))
        }
    }

    private fun callTool(name: String, args: JsonObject): JsonElement = when (name) {
        "snapshot" -> {
            val s = executor.snapshot()
            val itemsJson = buildJsonArray {
                for (n in s.items) {
                    val itemObj = buildJsonObject {
                        put("ref", n.ref); put("name", n.label)
                        put("x", n.x); put("y", n.y)
                        put("clickable", n.clickable)
                        if (n.bounds != null) {
                            val boundsArr = buildJsonArray {
                                for (b in n.bounds) add(JsonPrimitive(b))
                            }
                            put("bounds", boundsArr)
                        } else {
                            put("bounds", buildJsonArray { })
                        }
                    }
                    add(itemObj)
                }
            }
            buildJsonObject {
                put("captureW", s.captureW); put("captureH", s.captureH)
                put("screenW", s.screenW); put("screenH", s.screenH)
                put("items", itemsJson)
            }
        }
        "screenshot" -> {
            val f = executor.screenshot()
            buildJsonObject {
                put("type", "image/jpeg")
                put("data", Base64.getEncoder().encodeToString(f.bytes))
            }
        }
        "tap" -> {
            val x = args.int("x"); val y = args.int("y")
            buildJsonObject { put("ok", executor.tap(x, y)) }
        }
        "swipe" -> {
            val x1 = args.int("x1"); val y1 = args.int("y1")
            val x2 = args.int("x2"); val y2 = args.int("y2")
            val ms = args.intOr("ms") ?: 300
            buildJsonObject { put("ok", executor.swipe(x1, y1, x2, y2, ms)) }
        }
        "type" -> {
            val text = (args["text"] as? JsonPrimitive)?.content ?: ""
            buildJsonObject { put("ok", executor.type(text)) }
        }
        else -> throw IllegalArgumentException("unknown tool $name")
    }

    private val toolsList = buildJsonArray {
        tool("snapshot", "Accessibility-tree snapshot of the screen")
        tool("screenshot", "Capture a JPEG screenshot of the screen (base64)")
        tool("tap", "Tap at {x, y} screen coordinates")
        tool("swipe", "Swipe between points {x1,y1,x2,y2,ms?}")
        tool("type", "Type {text} into the focused field")
    }

    private fun JsonArrayBuilder.tool(name: String, desc: String) {
        add(buildJsonObject {
            put("name", name); put("description", desc)
            put("inputSchema", buildJsonObject {
                put("type", "object")
                put("properties", JsonObject(emptyMap()))
            })
        })
    }

    private fun JsonObject.int(key: String): Int = when (val v = this[key]) {
        is JsonPrimitive -> {
            val c = v.content
            if (c.startsWith('"')) c.substring(1, c.length - 1).toInt() else c.toInt()
        }
        else -> throw IllegalArgumentException("$key is not an integer")
    }
    private fun JsonObject.intOr(key: String): Int? = try { int(key) } catch (e: Exception) { null }

    private fun errElement(msg: String): JsonElement = buildJsonObject {
        put("code", -32000); put("message", msg)
    }

    private suspend fun respondJson(call: ApplicationCall, resp: JsonRpcResponse) {
        call.respondText(json.encodeToString(JsonRpcResponse.serializer(), resp), ContentType.Application.Json)
    }
}
