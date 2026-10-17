package pro.potoki.bekon.agent.handoff

import pro.potoki.bekon.agent.access.ClientGate
import pro.potoki.bekon.agent.mcp.McpHandler

/**
 * Plain-text/markdown guide served at GET / (also /llms.txt, /README) for an AI agent that only
 * got the QR prompt or the bare tunnel URL. The rules section reuses the exact strings the MCP
 * initialize response sends as `instructions`, so both stay in sync.
 */
object AgentGuide {
    /** Same text initialize returns as `instructions`. */
    val SERVER_INSTRUCTIONS: String get() = ClientGate.INSTRUCTIONS + " " + McpHandler.COACH_INSTRUCTIONS + " " + McpHandler.STATUS_INSTRUCTIONS

    fun markdown(baseUrl: String?): String {
        val base = baseUrl?.trim()?.trimEnd('/')?.removeSuffix("/mcp")?.takeIf { it.isNotEmpty() }
        val mcp = base?.let { "$it/mcp" } ?: "/mcp"
        val hdr = ClientGate.HEADER
        return """
# Bekon: an Android phone shared with you over MCP

You are reading this because the phone's owner showed you a QR code or link from the Bekon Agent app.
Bekon lets an AI agent see and (once allowed) operate this Android phone, or coach the human through it.

## Consent
- The human owner installed Bekon, accepted the disclosure, pressed Start and shared this link with you on purpose.
- The phone shows a persistent "bacon frame" border and notification while a session is live.
- The owner can Pause, switch to Read only, or Stop & disconnect at any time. Respect that: if a call
  answers "device is being used by user" or "read-only", stop acting and tell the human.
- Only do what the human asked. Do not browse their private data beyond the task.

## Connect (MCP, streamable HTTP)
- Endpoint: $mcp  (JSON-RPC 2.0 over HTTP POST, one request per POST, JSON response).
- Pick ONE stable id for yourself (e.g. "claude-anna-laptop") and send it on EVERY request as header:
  `$hdr: <your-id>`  plus `Content-Type: application/json`.
- First call `initialize` with `params.clientInfo.uniqueId` = the same id, e.g.:

```
curl -s $mcp -H 'Content-Type: application/json' -H '$hdr: my-agent-1' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","clientInfo":{"name":"my-agent","version":"1","uniqueId":"my-agent-1"}}}'
```

- A new id must be approved by the owner on the phone (a prompt appears). If you get "access declined"
  or the call waits, ask the human to tap Allow on the phone, then retry. Accepted ids are remembered.
- Then `tools/list`, then `tools/call` with `{"name": "...", "arguments": {...}}`.
- Start with `connection_info`, `device_setup_guide` (what is still off) and `shortcuts`.

## Device language
- This phone's UI locale is `${pro.potoki.bekon.agent.status.AgentStatus.localeTag()}` (language `${pro.potoki.bekon.agent.status.AgentStatus.language()}`, ${pro.potoki.bekon.agent.status.AgentStatus.languageName()}).
  Also returned as `deviceInfo` by initialize, connection_info and agent_status. Talk to the human and write agent_status text in this language
  unless they write to you in another one.

## Core tools
agent_status, snapshot (UI tree with refs + a baseline token), screenshot, annotate_screenshot, tap, swipe, type, key, nav,
wait_for_change, coach_draw, coach_clear, coach_script, wait_for_script, coach_script_stop, scan_list,
shortcuts, shortcut_open, shortcut_add, shortcut_remove, open_url (https, no shortcut saved), clipboard,
device_setup_guide, open_setup_screen, connection_info, user_events.

## Icon-only controls
- Many buttons have no text (search, ⋮, +, back arrows). Their name in snapshot is the contentDescription;
  match them by that name in coach_script / coach_draw anchorText, or use the snapshot ref. If a node has no
  name at all, use its bounds from the CURRENT snapshot (never guess positions).

## One agent at a time
- Only one agent can be connected. A second client id must be approved by the owner as a replacement; the
  replaced agent gets "replaced by another agent" until the session restarts.

## The human can talk back (status pill menu)
- Tapping the status pill opens: Back to Bekon / I changed my mind / Change the goal / Cancel everything.
- These arrive as userEvents in wait_for_change / wait_for_script (they return early) and via `user_events`:
  back_to_app, changed_mind (hints were cleared: don't redraw the same hint), new_goal {text} (follow it),
  cancel_all (stop; session paused or stopped).
- The pill can be dragged or swiped away by the human; it never covers your coach marks.

## Coaching loop (helping a human do it themselves)
1. `snapshot` -> keep its baseline token.
2. `coach_draw` a circle/arrow on the exact control, or better `coach_script` for multi-step/scrolling targets.
3. `wait_for_change` with the baseline (long-poll, <=90 s; on timeout just look again and call it again).
4. Read the diff (added/removed/changed, toggles "off -> on"), then next hint, or `coach_clear` when done.
- coach_script: give rules (show a hint when text is visible, arrow to scroll when not) and a done rule
  (e.g. done when the toggle is on), then `wait_for_script`. The phone moves the highlight itself.
- Match on-screen text by "contains", not exact equality (labels vary by MIUI/Android version and language).
- Use `scan_list` to read a long scrolling list before choosing.

## Status for the human (agent_status)
- At the start of a task: `agent_status` {state:"working", text:"Настраиваю Wi-Fi…"}.
- When the human must act: {state:"waiting", text:"Нажмите «Разрешить»", step:2, total:4}. The pill already shows the state
  label ("Нужны вы"), so never prefix the text with it. step/total are optional ("шаг 2 из 4").
- The pill's second line shows live capabilities (видит экран / может нажимать / пауза); `capabilities` in agent_status and
  connection_info has the same booleans (screenVisible, gesturesConnected, canTap, readOnly, paused). Trust them.
- Build every coach hint from the CURRENT snapshot (refs/bounds). Never assume a layout from memory or another phone.
- When finished: {state:"done", text:"Готово — можно остановить доступ"} (also updates the notification to "Agent finished").
- {state:"error"} when stuck; {clear:true} to remove. Keep text under ~60 chars, in the human's language.

## Rules
- Before Accessibility ("Gestures") is on you cannot tap, snapshot or draw on the phone: only screenshot /
  annotate_screenshot work. Coach the human in chat with annotated screenshots; never tap/swipe for the
  human while they are setting it up.
- Xiaomi/MIUI: NEVER turn Autostart off for Bekon (it kills the session). Use `device_setup_guide` /
  `open_setup_screen` for the OEM settings.
- Never ask the human to type "done"; detect progress with wait_for_change / wait_for_script.
- The owner may speak Russian (UI labels like "Прокрутите ниже", "Разрешить"). Reply in the human's language.
- Do not install apps, pay, send messages or change accounts unless the human explicitly asks.

## Privacy / stopping
- Screens may contain private data; use only what the task needs and do not store or forward it.
- When the task is done or the human says stop: `agent_status` state=done with a short summary, `coach_clear`, stop calling tools, and tell them they can
  press Stop in Bekon (or the notification's "Stop & disconnect"). The link dies when the session stops.

## Server instructions (identical to initialize.instructions)
${SERVER_INSTRUCTIONS}
""".trimStart()
    }

    /** Short hint for GET /mcp without a JSON-RPC body. */
    const val MCP_GET_HINT =
        "Bekon MCP endpoint: POST JSON-RPC 2.0 here with header ${ClientGate.HEADER}: <stable-id>. " +
            "Read GET / (this server's root) first for full instructions."
}
