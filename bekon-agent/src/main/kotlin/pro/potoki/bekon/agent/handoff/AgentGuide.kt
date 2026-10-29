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
    val SERVER_INSTRUCTIONS: String get() = ClientGate.INSTRUCTIONS + " " +
        (if (pro.potoki.bekon.agent.BuildConfig.GUIDANCE_MODE) McpHandler.GUIDANCE_INSTRUCTIONS else McpHandler.COACH_INSTRUCTIONS + " " + McpHandler.STATUS_INSTRUCTIONS)

    fun markdown(baseUrl: String?): String {
        val base = baseUrl?.trim()?.trimEnd('/')?.removeSuffix("/mcp")?.takeIf { it.isNotEmpty() }
        val mcp = base?.let { "$it/mcp" } ?: "/mcp"
        val root = base ?: ""
        val hdr = ClientGate.HEADER
        if (GUIDANCE) return connectBlock(mcp, root) + guidanceMarkdown(mcp, hdr)
        return connectBlock(mcp, root) + """

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

- **Approval + token, step by step** (works for stateless scripts too):
  1. `initialize` without a token. It answers within ~10 s and ALWAYS carries `bekon_session_token`
     (also the `X-Bekon-Token` response header). **Save it at once** with your id (file/config).
     - `result.approval = "approved"`: you are in.
     - `error.code = -32002` / `error.data.status = "approval_pending"`: the owner has not tapped Allow yet;
       the token is in `error.data.bekon_session_token` and is not valid yet.
  2. While pending: repeat `initialize` every 3-5 s with the SAME id and `Authorization: Bearer <token>`
     (each poll answers within ~2 s) until `result.approval = "approved"`. Tell the human to tap Allow.
  3. Send `Authorization: Bearer <token>` (or `X-Bekon-Token`) with `$hdr` on EVERY later request.
  - **Token contract (handle BOTH shapes):** approved -> `result.bekon_session_token` (this happens on the FIRST call
    when the owner taps Allow during the ~10 s wait, and on every poll after Allow); pending -> `error.data.bekon_session_token`
    (`error.code = -32002`). Both also in the `X-Bekon-Token` response header.
  - Lost the token? `initialize` again WITHOUT a token and the same id: the owner gets a new Allow prompt
    ("asks to connect again"); Allow gives a NEW token and the old one stops working. The id alone never gets a token silently.
  - "access declined" (Deny, or no answer in ~2 min): ask the human, then start again at step 1.
  - The token ends when the owner taps **Disconnect agent** (pill menu or the Bekon card; screen sharing and the link
    stay up) or **Stop** (everything stops, the link dies), or approves another agent; then start again at step 1.
    Nothing is remembered between sessions.
- **Tunnel errors:** HTTP 530 / Cloudflare error 1033 means this link is dead (Bekon was stopped or its tunnel restarted
  and got a NEW URL). Stop calling it and ask the owner for the new link (the Bekon card shows it, with a QR).
- **Calling tools:** tools are called via JSON-RPC method `tools/call` with params `{name, arguments}`. A tool name used
  as the JSON-RPC method (e.g. method `session_info`) is an error.
- **Stateless agents** (each tool call is a new process): generate your id ONCE and save it with the token;
  never mint a new id per call (each new id = a new owner prompt). To check a saved session, call tool
  `session_info` with the saved id + token: it never prompts and says alive / pending / invalid.
  `initialize` with a valid token is a silent reconnect.

```
curl -s $mcp -H 'Content-Type: application/json' -H '$hdr: my-agent-1' \
  -H 'Authorization: Bearer <bekon_session_token>' \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'
```
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
- Tapping the status pill opens: Back to Bekon / I changed my mind / Change the goal / Cancel everything /
  Disconnect agent (ends only your session: token void) / Stop Bekon (end screen sharing; the link dies).
- These arrive as userEvents in wait_for_change / wait_for_script (they return early) and via `user_events`:
  back_to_app, changed_mind (hints were cleared: don't redraw the same hint), new_goal {text} (follow it),
  cancel_all (stop; session paused, agent disconnected, or Bekon stopped). Events belong to one agent session: a new
  token never receives events from an earlier session.
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


    private const val GUIDANCE = pro.potoki.bekon.agent.BuildConfig.GUIDANCE_MODE

    /** Guide for the guidance (playStore) build: see + hint only, the human does every tap. */
    private fun guidanceMarkdown(mcp: String, hdr: String): String = """

# Bekon: screen guidance for an Android phone, over MCP

You are reading this because the phone's owner showed you a QR code or link from the Bekon Agent app.
In this build Bekon lets an AI assistant SEE the screen and SHOW HINTS (highlights, arrows, numbered marks,
short captions). The human does every tap, swipe and keystroke themselves. There are no input tools.

## Consent
- The owner installed Bekon, accepted the disclosure, pressed Start and shared this link with you on purpose.
- A persistent frame and notification are shown while a session is live. The owner can Stop at any time.
- Only help with what the human asked. Do not read their private data beyond the task.

## Connect (MCP, streamable HTTP)
- Endpoint: $mcp (JSON-RPC 2.0 over HTTP POST). Send header `$hdr: <your-stable-id>` on every request and
  the same id as `params.clientInfo.uniqueId` in `initialize`.
- The first `initialize` ALWAYS returns `bekon_session_token` (also the `X-Bekon-Token` header).
  **Token contract (handle BOTH shapes):** approved -> `result.bekon_session_token` with `result.approval = "approved"`
  (happens on the first call when the owner taps Allow within the ~10 s wait); pending -> `error.code = -32002`
  "approval_pending" with `error.data.bekon_session_token`.
  Save it at once; while pending repeat `initialize` with the same id + `Authorization: Bearer <token>` every
  3-5 s until `result.approval = "approved"`. Then send that header on every request.
- Stateless agents: save your id + token to a file between runs (never a new id per call); `session_info`
  (never prompts) says alive / pending / invalid.
- Lost the token? `initialize` again WITHOUT a token, same id: the owner gets a new Allow prompt; Allow gives a new
  token (the old one stops working). Nothing is ever re-issued silently.
- Tools are called via JSON-RPC method `tools/call` with params `{name, arguments}` (never the tool name as the method).
- The owner can tap **Disconnect agent** (ends your session only; the link stays) or **Stop** (the link dies).
- HTTP 530 / Cloudflare error 1033 = this link is dead (Bekon stopped or its tunnel restarted with a NEW URL):
  stop calling it and ask the owner for the new link (shown on the Bekon card, with a QR).

## Device language
- UI locale `${pro.potoki.bekon.agent.status.AgentStatus.localeTag()}` (${pro.potoki.bekon.agent.status.AgentStatus.languageName()}). Write hints in this language.

## Tools
${Handoff.GUIDANCE_TOOLS}, connection_info, session_info, device_setup_guide, current_screen_fingerprint, nav_mark.

## Hint loop
1. `ocr_screen` or `find_text` {text, contains:true} to locate the target on the CURRENT screen (`query` is accepted as an alias of `text`).
2. Show it: `find_text` draw=true, `som_marks` (numbered marks), `coach_draw` (screen pixels), or `guidance_step` (caption + Next).
3. `wait_for_change`, then look again and show the next hint; `coach_clear` when done.
- Below the fold: `find_text` acrossHistory=true returns `scrollHint`; show `coach_scroll` {direction:"down"} (bouncing arrow + caption), `wait_for_change`, then `find_text` again.
- Other home-screen page: `coach_scroll` {direction:"right"} for the next page, "left" for the previous one. direction is always where the content to reach is (the arrow points there), never the finger movement.
- `find_text` draw=true puts a short caption next to the frame ("Your turn: tap …"); pass `caption` to word it yourself (UI language, ≤ 5 words).
- If a result has `overlayHidden:true`, this screen blocks overlays: describe the target in words; a hint notification was posted.
- Coordinates are screen pixels of the full screenshot (e.g. 1080x2340, status bar and navigation bar included). Hints are
  drawn in an app overlay that Android keeps BELOW the system bars: a target inside the navigation bar (Home/Back) is marked
  with an arrow pointing at it from just above the bar, plus the part of the circle that is visible.

## Server instructions (identical to initialize.instructions)
${SERVER_INSTRUCTIONS}
"""

    /** Short hint for GET /mcp without a JSON-RPC body. */
    /** Universal "how to connect" block shown first on / , /llms.txt and GET /mcp. No app-specific links. */
    /**
     * Absolute URLs for handoff pages. [baseUrl] is the tunnel root (with or without trailing /mcp).
     * When unknown, returns site-relative paths (still usable on the same host).
     */
    fun urls(baseUrl: String?): Triple<String, String, String> {
        val root = baseUrl?.trim()?.trimEnd('/')?.removeSuffix("/mcp")?.takeIf { it.isNotEmpty() }
        val mcp = root?.let { "$it/mcp" } ?: "/mcp"
        val link = root?.let { "$it/link/start" } ?: "/link/start"
        val r = root ?: ""
        return Triple(r, mcp, link)
    }

    /**
     * First lines an agent must see on / /llms.txt. Chatbots that can only open URLs are told
     * NOT to stop — absolute link-start URL is line 1.
     */
    fun connectBlock(mcp: String, root: String = ""): String {
        val link = (root.trimEnd('/').ifEmpty { null }?.let { "$it/link/start" })
            ?: "/link/start"
        return """
# HOW TO CONNECT (read first)
- **If you are a chatbot that can only open web pages (ChatGPT etc.), do NOT stop — open this link now:** $link
  (Then open the wait link it shows: that asks the owner to tap Allow on the phone; optional ?client=<your-name>. Then use the one-shot ${if (GUIDANCE) "hint" else "action"} links. No MCP/POST needed.)
- **Preferred when you can POST — MCP:** JSON-RPC 2.0 at $mcp (details below), or ask the user to add $mcp as a custom MCP connector (Streamable HTTP, no auth).
- The owner approves you on the phone; nothing else is needed (no account, no API key).
""".trimStart()
    }

    /**
     * Plain GET /mcp for browsers and web-only agents (no MCP/SSE Accept).
     * First line must be unmistakable; absolute clickable URL when [baseUrl] is known.
     */
    fun mcpGetHtml(baseUrl: String?): String {
        val (_, mcp, link) = urls(baseUrl)
        val esc = { s: String -> s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;") }
        return """
<!doctype html><html><head><meta charset=utf-8><meta name="robots" content="all">
<title>Bekon — use ${if (GUIDANCE) "link guidance" else "link control"}</title></head><body>
<p><b>You can't use MCP from here? ${if (GUIDANCE) "Show hints on this phone via links" else "Control this phone via links"}: open <a href="${esc(link)}">${esc(link)}</a></b></p>
<p>That is the fallback for chatbots that can only open web pages (ChatGPT etc.). Do not stop — open the link above.
The phone owner taps Allow; then follow the on-screen one-shot action links.</p>
<p>If you <i>can</i> POST JSON-RPC, use MCP at <a href="${esc(mcp)}">${esc(mcp)}</a> instead (Streamable HTTP).</p>
<p><a href="${esc(if (baseUrl.isNullOrBlank()) "/" else urls(baseUrl).first + "/")}">Full instructions</a></p>
</body></html>
""".trim()
    }

    /** Plain-text twin of [mcpGetHtml] (same first-line instruction). */
    fun mcpGetText(baseUrl: String?): String {
        val (_, mcp, link) = urls(baseUrl)
        return "You can't use MCP from here? ${if (GUIDANCE) "Show hints on this phone via links" else "Control this phone via links"}: open $link\n" +
            "Do NOT stop — open that URL (chatbots / ChatGPT etc.). Owner taps Allow, then use the action links.\n" +
            "If you can POST JSON-RPC, use MCP at $mcp instead. Full guide: GET /\n"
    }

    const val ROBOTS_TXT = """User-agent: *
Content-Signal: search=yes,ai-train=no,ai-input=yes,use=full
Allow: /

# Bekon: the phone owner shares this link with an AI agent on purpose.
# User-triggered agents (chatbots opening a URL the human pasted) MUST be allowed.
# Cloudflare quick tunnels may prepend a managed block that forbids some agent UAs;
# those agents should prefer THIS Allow group / Content-Signal and still fetch.
User-agent: ChatGPT-User
User-agent: OAI-SearchBot
User-agent: Claude-User
User-agent: Claude-SearchBot
User-agent: Perplexity-User
User-agent: PerplexityBot
User-agent: Google-Agent
User-agent: Gemini-Deep-Research
User-agent: MistralAI-User
User-agent: DuckAssistBot
User-agent: Meta-ExternalFetcher
User-agent: Grok
Content-Signal: search=yes,ai-train=no,ai-input=yes,use=full
Allow: /
Allow: /link/
Allow: /mcp
Allow: /llms.txt
Allow: /s/
"""

    /** Kept for real MCP clients (Accept: application/json or text/event-stream) on GET /mcp. */
    fun mcpGetHint(baseUrl: String?): String {
        val (_, mcp, link) = urls(baseUrl)
        return "Bekon MCP endpoint: POST JSON-RPC 2.0 here ($mcp) with header ${ClientGate.HEADER}: <stable-id>; " +
            "save bekon_session_token from your first initialize (result.bekon_session_token when approved, error.data.bekon_session_token while approval_pending) and send Authorization: Bearer <it> on every request. " +
            "Web-only chatbot? Do NOT stop — open $link instead (${if (GUIDANCE) "link guidance" else "link control"}, no POST)."
    }
}
