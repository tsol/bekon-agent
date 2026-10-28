# Security audit — Bekon Agent v1.0.9 → fixes in v1.0.10

Scope: `bekon-agent/` at tag [v1.0.9](https://github.com/tsol/bekon-agent/tree/v1.0.9) (Play flavor), date 2026-10-17.
Fixes are in [v1.0.10](https://github.com/tsol/bekon-agent/tree/v1.0.10).

## 1. Data egress

**OK.** Screenshots, the accessibility tree (screen text), clipboard and typed text are only returned as replies to
requests on the local MCP/HTTP server. Nothing is pushed anywhere on the app's own initiative.

- The server listens on **127.0.0.1:8711 only** ([LocalServer.kt:52](https://github.com/tsol/bekon-agent/blob/v1.0.9/bekon-agent/src/main/kotlin/pro/potoki/bekon/agent/server/LocalServer.kt#L52)). It is reachable from outside only through the Cloudflare quick tunnel.
- The tunnel is the bundled `cloudflared`, started only when a session starts
  ([CloudflareTunnel.kt:34](https://github.com/tsol/bekon-agent/blob/v1.0.9/bekon-agent/src/main/kotlin/pro/potoki/bekon/agent/tunnel/CloudflareTunnel.kt#L34), [SessionService.kt:217](https://github.com/tsol/bekon-agent/blob/v1.0.9/bekon-agent/src/main/kotlin/pro/potoki/bekon/agent/session/SessionService.kt#L217)).
  It contacts `api.trycloudflare.com` (to get a random `*.trycloudflare.com` hostname), the Cloudflare edge, and DNS (the system's DNS servers, or 1.1.1.1/8.8.8.8 if none are found, [CloudflareTunnel.kt:89](https://github.com/tsol/bekon-agent/blob/v1.0.9/bekon-agent/src/main/kotlin/pro/potoki/bekon/agent/tunnel/CloudflareTunnel.kt#L89)).
  It runs with `--no-autoupdate`. Cloudflare terminates TLS, so Cloudflare can technically see the traffic.
- **No analytics, crash reporting, ads, Firebase or update checks.** The only dependencies are Compose, Ktor server, kotlinx and ZXing ([build.gradle.kts](https://github.com/tsol/bekon-agent/blob/v1.0.9/bekon-agent/build.gradle.kts#L104-L120)).
- Other URLs are opened in the browser only when the user taps them: the privacy policy (shown from a local asset, JavaScript off, [PrivacyPolicyActivity.kt:42](https://github.com/tsol/bekon-agent/blob/v1.0.9/bekon-agent/src/main/kotlin/pro/potoki/bekon/agent/consent/PrivacyPolicyActivity.kt#L42)) and the GitHub source link.
- **On-device storage:** no screenshots or screen text are written to disk. What is stored:
  - app-private SharedPreferences: approved agent ids, consent flags, shortcuts, capture settings;
  - `crash.log` with stack traces only ([BekonApp.kt:23](https://github.com/tsol/bekon-agent/blob/v1.0.9/bekon-agent/src/main/kotlin/pro/potoki/bekon/agent/BekonApp.kt#L23));
  - cloudflared's resolver and CA files in the app cache.
- Password fields are masked in the screen-text tree ([A11yDump.kt:127](https://github.com/tsol/bekon-agent/blob/v1.0.9/bekon-agent/src/main/kotlin/pro/potoki/bekon/agent/device/touch/A11yDump.kt#L127)).

## 2. Access boundaries

| Control | v1.0.9 behaviour | Verdict |
|---|---|---|
| **Pause** | ClientGate rejects every request, MCP and HTTP ([ClientGate.kt:16](https://github.com/tsol/bekon-agent/blob/v1.0.9/bekon-agent/src/main/kotlin/pro/potoki/bekon/agent/access/ClientGate.kt#L16), [McpHandler.kt:217](https://github.com/tsol/bekon-agent/blob/v1.0.9/bekon-agent/src/main/kotlin/pro/potoki/bekon/agent/mcp/McpHandler.kt#L217), [LocalServer.kt:76](https://github.com/tsol/bekon-agent/blob/v1.0.9/bekon-agent/src/main/kotlin/pro/potoki/bekon/agent/server/LocalServer.kt#L76)) | Concern: a **running coach_script kept tapping/swiping**, and a `wait_for_change` that was already waiting returned screen data captured after Pause. **Fixed in 1.0.10.** |
| **Read-only** | Blocks `WRITE_TOOLS` and HTTP /tap /swipe /type ([McpHandler.kt:67](https://github.com/tsol/bekon-agent/blob/v1.0.9/bekon-agent/src/main/kotlin/pro/potoki/bekon/agent/mcp/McpHandler.kt#L67), [:240](https://github.com/tsol/bekon-agent/blob/v1.0.9/bekon-agent/src/main/kotlin/pro/potoki/bekon/agent/mcp/McpHandler.kt#L240), [LocalServer.kt:87](https://github.com/tsol/bekon-agent/blob/v1.0.9/bekon-agent/src/main/kotlin/pro/potoki/bekon/agent/server/LocalServer.kt#L87)). Screen and clipboard reads stay open, by design. | Concern: **`open_url`, `shortcut_add`, `shortcut_remove` were not in WRITE_TOOLS** (open_url can launch apps through https app links), and running coach scripts kept acting. **Fixed.** |
| **Stop** (`stopNow` → `stopSession`) | Clears agents, stops the supervisor, kills the cloudflared process (destroy + destroyForcibly, [CloudflareTunnel.kt:118](https://github.com/tsol/bekon-agent/blob/v1.0.9/bekon-agent/src/main/kotlin/pro/potoki/bekon/agent/tunnel/CloudflareTunnel.kt#L118)), stops the server, then stopSelf ([SessionService.kt:255](https://github.com/tsol/bekon-agent/blob/v1.0.9/bekon-agent/src/main/kotlin/pro/potoki/bekon/agent/session/SessionService.kt#L255)) | The tunnel and server **are** killed. Concern: **MediaProjection / VirtualDisplay was never released** (`ScreenCapture.stop()` had no caller, [ScreenCapture.kt:230](https://github.com/tsol/bekon-agent/blob/v1.0.9/bekon-agent/src/main/kotlin/pro/potoki/bekon/agent/device/capture/ScreenCapture.kt#L230)), and coach scripts were not stopped. **Fixed.** |

Every MCP tool (snapshot, screenshot, annotate_screenshot, coach_*, open_url, open_setup_screen, wait_for_change,
user_events, clipboard, key, type, nav, shortcuts) goes through `ClientGate.rejection` on **every** request
([McpHandler.kt:217](https://github.com/tsol/bekon-agent/blob/v1.0.9/bekon-agent/src/main/kotlin/pro/potoki/bekon/agent/mcp/McpHandler.kt#L217)). Non-MCP HTTP paths are gated in [LocalServer.kt:76](https://github.com/tsol/bekon-agent/blob/v1.0.9/bekon-agent/src/main/kotlin/pro/potoki/bekon/agent/server/LocalServer.kt#L76); only /health and the agent guide text are open.

**One agent at a time.** A second client id needs the owner to approve it as a replacement on the phone; the replaced id is then refused for the rest of the session ([ClientGate.kt:18-26](https://github.com/tsol/bekon-agent/blob/v1.0.9/bekon-agent/src/main/kotlin/pro/potoki/bekon/agent/access/ClientGate.kt#L18-L26)).
New ids always need an explicit Allow: auto-accept is off and is forced off in the Play build ([PrefsTrustedClients.kt:9](https://github.com/tsol/bekon-agent/blob/v1.0.9/bekon-agent/src/main/kotlin/pro/potoki/bekon/agent/access/PrefsTrustedClients.kt#L9)).

**Authentication — ✅ RESOLVED in [v1.0.11](https://github.com/tsol/bekon-agent/tree/v1.0.11):** the owner's approval now issues a
per-session bearer token (SecureRandom 32 bytes, base64url, constant-time comparison) bound to the client id. Every later request must
send it (otherwise 401). It is revoked on Stop, at session end, on "Disconnect agent" and when a replacement agent is approved.
Approvals are no longer persisted, and CORS is removed. See [ClientGate.kt](https://github.com/tsol/bekon-agent/blob/v1.0.11/bekon-agent/src/main/kotlin/pro/potoki/bekon/agent/access/ClientGate.kt).
The original v1.0.9 finding was: the endpoint has no cryptographic authentication.
- Access depends on (a) the random trycloudflare hostname staying secret, and (b) a client id the agent picks itself and the owner approves once.
- Anyone who learns **both** the URL and an approved id can connect, and approved ids are remembered across sessions.
- Mitigations:
  - the URL changes every session;
  - the owner sees who is connected (pill and notification);
  - **1.0.10 adds "Forget approved agents"** in Settings.
- Recommendation for a future version: a per-session secret token in the handoff URL or header.
- The client IP shown in the approval prompt comes from `CF-Connecting-IP` / `X-Forwarded-For` and is informational only.
- CORS is `*` for the browser console ([LocalServer.kt:60](https://github.com/tsol/bekon-agent/blob/v1.0.9/bekon-agent/src/main/kotlin/pro/potoki/bekon/agent/server/LocalServer.kt#L60)); it adds nothing beyond URL knowledge.

## 3. Permissions ([AndroidManifest.xml](https://github.com/tsol/bekon-agent/blob/v1.0.9/bekon-agent/src/main/AndroidManifest.xml#L5-L12))

| Permission | Why | Verdict |
|---|---|---|
| INTERNET, ACCESS_NETWORK_STATE | tunnel; system DNS lookup ([CloudflareTunnel.kt:99](https://github.com/tsol/bekon-agent/blob/v1.0.9/bekon-agent/src/main/kotlin/pro/potoki/bekon/agent/tunnel/CloudflareTunnel.kt#L99)) | needed |
| FOREGROUND_SERVICE, _MEDIA_PROJECTION, _SPECIAL_USE | session service, screen capture | needed |
| POST_NOTIFICATIONS | ongoing session notification with Stop | needed |
| WAKE_LOCK | keep the session alive while serving | needed |
| REQUEST_IGNORE_BATTERY_OPTIMIZATIONS | asked once so OEMs don't kill the session ([SessionService.kt:440](https://github.com/tsol/bekon-agent/blob/v1.0.9/bekon-agent/src/main/kotlin/pro/potoki/bekon/agent/session/SessionService.kt#L440)) | justified, but Play scrutinises it |
| Accessibility service: `typeAllMask`, `canRetrieveWindowContent`, `canPerformGestures` ([config](https://github.com/tsol/bekon-agent/blob/v1.0.9/bekon-agent/src/main/res/xml/accessibility_service_config.xml)) | screen tree, gestures, change detection | needed for the core feature; broad by nature |

No SYSTEM_ALERT_WINDOW, contacts, SMS, location, storage or camera/mic permissions. The keyboard (IME) service exists only in the `full` flavor, not in the Play build.

> **Update v1.0.35 (Play build):** the table above reflects v1.0.9. The Google Play (`playStore`) build now requests only SYSTEM_ALERT_WINDOW, FOREGROUND_SERVICE (+ _MEDIA_PROJECTION, _SPECIAL_USE), POST_NOTIFICATIONS, INTERNET, ACCESS_NETWORK_STATE and WAKE_LOCK. It has no AccessibilityService, no IME and no REQUEST_IGNORE_BATTERY_OPTIMIZATIONS (that permission is in the sideloaded `full` flavor only). `BIND_JOB_SERVICE` and `DUMP` appear in its manifest only as guard attributes on library components (ML Kit datatransport JobService, androidx.profileinstaller receiver); they are not requested by the app.

> **Update v1.0.36 (connection approval):** link-preview bots (Telegram, Slack, WhatsApp, Facebook, Twitter/X, Discord, Skype/Teams, Viber, search/AI crawlers) and prefetch requests on `/link/*` and `/s/*` get a static OG page and never create an approval request ([LinkPreview.kt](https://github.com/tsol/bekon-agent/blob/v1.0.36/bekon-agent/src/main/kotlin/pro/potoki/bekon/agent/server/LinkPreview.kt)); `GET /link/start` no longer prompts the owner, only the agent's `/link/wait` fetch does. The handoff link no longer hardcodes `client=chatgpt`. At most one owner prompt is open at a time (other new ids get `approval_pending`). `initialize` with the same id AND a valid session token is a silent reconnect; an id alone never gets a token or a silent re-approval, and a stopped session's token is dead. New read-only `session_info` tool: answered before the gate, never prompts or issues a token, returns only alive/status/mode/readOnly ([ClientGate.kt](https://github.com/tsol/bekon-agent/blob/v1.0.36/bekon-agent/src/main/kotlin/pro/potoki/bekon/agent/access/ClientGate.kt)).

## Fixes in v1.0.10
1. A guard inside `DeviceExecutor` itself: reads need a live, unpaused session; writes also need read-only off. This covers coach scripts and anything else that bypasses the MCP layer.
2. Results of long calls that finish after Pause or Stop are dropped.
3. Pause, Read-only and Stop also stop a running coach_script.
4. Stop releases MediaProjection and the VirtualDisplay (`releaseCapture()`), both on Stop and when the service is destroyed.
5. `open_url`, `shortcut_add` and `shortcut_remove` are now write tools, blocked in read-only mode.
6. Settings → "Forget approved agents".

## Fixes in v1.0.11
7. Per-session bearer token issued on approval, as described above (resolves the authentication concern).
8. Approved client ids are no longer persisted; ids stored by older versions are deleted on upgrade.
9. CORS removed: no `Access-Control-*` headers, and OPTIONS returns 405.
10. CI fixed to use the flavored tasks (`assemblePlayStoreDebug`, `testPlayStoreDebugUnitTest`); `ClientGateTest` covers the token rules.
