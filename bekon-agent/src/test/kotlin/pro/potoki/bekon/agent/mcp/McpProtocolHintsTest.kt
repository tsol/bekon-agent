package pro.potoki.bekon.agent.mcp

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpProtocolHintsTest {
    private val tools = setOf("session_info", "find_text", "ocr_screen")

    @Test fun toolNameUsedAsMethodGetsTheToolsCallShape() {
        val m = McpHandler.unknownMethodMessage("session_info", tools)
        assertTrue(m.contains("Tools are called via JSON-RPC method tools/call with params {name, arguments}"))
        assertTrue(m.contains("is a tool"))
        assertTrue(McpHandler.unknownMethodMessage("bogus", tools).contains("tools/call"))
    }

    @Test fun rpcMethodUsedAsToolNameGetsAHint() {
        val m = McpHandler.unknownToolMessage("tools/list", tools)
        assertTrue(m.contains("JSON-RPC method")); assertTrue(m.contains("tools/call"))
        assertTrue(McpHandler.unknownToolMessage("nope", tools).startsWith("unknown tool nope"))
    }

    @Test fun findTextAcceptsQueryAlias() {
        assertEquals("Wi-Fi", McpHandler.textArg(buildJsonObject { put("text", "Wi-Fi") }))
        assertEquals("Wi-Fi", McpHandler.textArg(buildJsonObject { put("query", "Wi-Fi") }))
        assertEquals("Wi-Fi", McpHandler.textArg(buildJsonObject { put("text", " "); put("query", "Wi-Fi") }))
        assertEquals("", McpHandler.textArg(buildJsonObject { }))
    }

    @Test fun debugLogLineHasMethodAndToolOnlyNeverParamsOrTokens() {
        val params = buildJsonObject {
            put("name", "find_text")
            put("arguments", buildJsonObject { put("text", "secret-pin-1234") })
            put("bekon_session_token", "TOKEN-abc")
        }
        val line = McpHandler.callLogLine("tools/call", params)
        assertEquals("POST /mcp method=tools/call tool=find_text", line)
        assertFalse(line.contains("secret")); assertFalse(line.contains("TOKEN"))
        val init = McpHandler.callLogLine("initialize", buildJsonObject { put("clientInfo", JsonPrimitive("x")) })
        assertEquals("POST /mcp method=initialize", init)
    }
}
