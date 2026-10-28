# Bekon Agent — implementation plan (phase 1)

Read `GOAL.md` for product scope. This file is how to build it.
Do not modify `apps/android-gateway` except to copy from it.

## Architecture

One Android application module: `apps/bekon-agent`.
`minSdk 24`, `compileSdk 34`, `targetSdk 34`, package `pro.potoki.bekon.agent`.
Do not split into Gradle modules yet. Do not extract `packages/bekon-control`.
Copy the few gateway classes you need. A second app can share them later.

The Activity is only UI. The foreground service owns the session:
capture, accessibility, keyboard status, the local server, and cloudflared.
If the activity dies, the session keeps running. Stop is explicit.

```
ui  --intents-->  session (StateFlow<HomeState>)
                      |
                      v
                 ActionExecutor          <-- the only place that touches the device
                   /            \
           HttpRouter          McpRouter
                   \            /
                    127.0.0.1:port
                          |
                    cloudflared (arm64, in the apk)
                          |
                    public https URL  -->  agent's normal HTTP MCP / API
```

Rules:

- UI renders state and sends intents. No MediaProjection, no accessibility, no process start in a composable.
- `ActionExecutor` is the only implementation of snapshot, tap, swipe, type, screenshot. HTTP and MCP both call it. They must not grow a second copy of the logic.
- Device I/O sits behind small interfaces (`ScreenGrab`, `Toucher`, `Typer`, `TreeSource`) so JVM tests use fakes.
- The server binds `127.0.0.1` only. The public URL exists only while cloudflared is up.
- Phone stays dumb: pixels, tree, tap, type. Do not port `nav.py` (`open`, `find`) or `see` (vision) or tesseract. The outside agent decides what to tap.
- Kotlin coroutines and a single `StateFlow`. Do not copy `SetupActivity`'s `Handler` polling.
- Compose + Material 3. Gateway UI is programmatic views; do not copy `BekonUi` or the five tabs.

## Layout

`Column`, 16dp padding, one card.

- Row: Экран + status pill. Tap opens the capture consent.
- Row: Жесты + pill. Tap opens Accessibility settings.
- Row: Клавиатура + pill. Tap opens IME settings (enable Bekon Keys, then select it). Same two-step as `BekonImeService`.
- Row: Туннель + pill (`выкл` / `жив`). Choice control default `Cloudflare`.
  If the row is narrower than the label + pill + dropdown, the dropdown is the next full-width line. Do not squeeze it.
- Filled button Запустить / Остановить.
- Outlined button QR для агента, enabled only when a public URL exists.
- Text button Настройки: stub screen, no WLYA fields.

QR screen: code, url text, copy. Secret is not part of phase 1.

## What to copy from android-gateway

Copy behavior, new files under `device/`:

- `capture/ScreenCapture.kt` — MediaProjection
- `touch/TouchService.kt`, `TouchController.kt` — gestures
- `ime/BekonImeService.kt` — keyboard
- `A11yDump.kt` — accessibility tree snapshot
- `AgentForegroundService.kt` — only the idea: a foreground notification while the session is up

Leave voice, SMS, WLYA, root, GSM, adapters behind.

## HTTP API

Flat routes. There is no `/tunnels/:id` on the phone. That shape belongs to the Node API in front of WLYA.

- `GET /health` → `{ ok, capture, touch, keyboard, tunnel }`
- `GET /snapshot` → accessibility nodes `{ text, bbox, clickable }[]`
- `GET /screenshot` → jpeg bytes
- `POST /tap` `{ x, y }`
- `POST /swipe` `{ x1, y1, x2, y2, ms }`
- `POST /type` `{ text }`

Errors are JSON `{ error }` with 4xx/5xx. A missing permission is 409 and the status pill, not a crash.

Bind `127.0.0.1` and an ephemeral port, remember it for the life of the session.
Ktor CIO is fine. Do not add NanoHTTPD if Ktor is already a small dependency.

## MCP

Same process, Streamable HTTP on `POST /mcp` (MCP spec, not the old SSE-only transport).
Tools, each one call into `ActionExecutor`:

- `snapshot`
- `screenshot`
- `tap`
- `swipe`
- `type`

No `look`/`open`/`find`/`see` in phase 1. Those are client-side policies on top of snapshot+tap.
An external client must be able to add the public https URL as a remote MCP server and also call the REST paths. Same executor, so they cannot disagree.

## Cloudflare

Runs on the phone. Not on the host, not in Hermes.

1. Local server answers `/health`.
2. Then start the bundled `cloudflared` arm64-v8a binary: quick tunnel to `http://127.0.0.1:<port>`.
3. Parse the `trycloudflare.com` URL from its stdout.
4. Put that URL in state. QR encodes it.
5. On Stop, destroy the process and clear the URL.

Quick tunnels mint a new URL every start. Do not cache it across sessions.
Ship the binary in `src/main/jniLibs` or assets and `exec` it. A desktop cloudflared on the host is the wrong test.
If no Android-compatible binary is in the repo, fetch a known arm64 Android build, record its version in the module README, and verify it runs on the Redmi. If it will not run, write `docs/BLOCKED.md` and stop. Do not invent another tunnel.
Until the binary works, prove HTTP and MCP through `adb forward` to `127.0.0.1`. That is a step, not the done state.

## Order of work

Each step ends green before the next. JVM tests where the step has no device. One device check at the end of a step that needs the phone.

1. Wire the module into `settings.gradle.kts`. Empty Compose activity. `assembleDebug` in Hermes.
   SDK: source `env.sh`, `sdk.dir` from `local.properties.hermes` for the run only, then restore the host file.
   `--project-cache-dir /opt/data/.gradle/bekon-project-cache`. Do not stop the host daemon `:wlya-tunnel:run`.
2. `HomeState` + Compose screen on fake data. Compose UI test or a screenshot test for the narrow wrap.
3. Foreground service. Запустить shows the notification and moves state to Serving. Остановить tears it down.
4. Copy capture, touch, IME, tree dump. Pills read real checks. Row taps open the right system screen. Install on the Redmi and confirm each row.
5. `ActionExecutor` + JVM tests on fakes for snapshot, tap, swipe, type, screenshot.
6. HTTP routes on `127.0.0.1`. From the host: `adb forward` and curl `/health`, `/snapshot`, `/tap`.
7. MCP `/mcp` listing those five tools, one test that a tool call hits the executor.
8. Bundle cloudflared, start it after health, show QR. Agent fetches `https://<url>/health`.
9. Done: from that URL, snapshot, tap, and type on the Redmi.

## Build facts

- Hermes JDK 21, SDK `/opt/data/.toolchain/android-sdk` (android-34, build-tools 34.0.0).
- Prefer `/opt/data/workspace/tools/bekon-android.sh`.
- Phone serial `ab52ba8d0504` is USB on the host. Container has no USB.
  Host adb: `/home/harry/Android/Sdk/platform-tools/adb -s ab52ba8d0504`.
- No emulator.


## Reuse now

Copy these into `device/`. Do not edit the originals. Do not import the gateway module.

- `capture/ScreenCapture.kt`
- `touch/TouchService.kt`, `touch/TouchController.kt`
- `ime/BekonImeService.kt`
- `A11yDump.kt`
- Foreground-service pattern from `AgentForegroundService.kt`

Read `apps/phone-control-api/src/snapshot.ts` for the snapshot field shape (text, bbox). Skip its OCR branch.
Do not reuse `phone-control-mcp`, `WlyaTunnelManager`, voice, SMS, root, or the `/tunnels/:id` HTTP API.

## Later phases — seams only, do not build

Phase 1 ships Cloudflare. Shape the code so the next phases plug in without a rewrite.

- Tunnel is an interface: `start()`, `stop()`, `publicUrl`. The only implementation now is `CloudflareTunnel`. WLYA (poll mailbox, channelId, secret, email transport) is a second implementation later. Do not call `cloudflared` from the session or the UI.
- `ActionExecutor` stays the core. A future thin gateway next to the agent (WLYA mode) will call the same actions. Do not put tool policy in the tunnel.
- Snapshot comes from a `TreeSource`. Accessibility is the only source now. OCR / ML Kit would be a second source later, merged the way `snapshot.ts` merges `a11y` and `ocr`. Do not bury tree parsing inside tap.
- Home tunnel choice is an enum. Phase 1 has one value, Cloudflare. Adding WLYA is a new value plus a settings screen for channel and secret, not a new activity.
- QR encodes whatever `publicUrl` the active tunnel returned. A later flag may append a secret. The QR screen must not know which tunnel it was.
- Voice, email, and `open`/`find` navigation stay out. Navigation is a client of snapshot+tap, not phone code.

## Out of scope

WLYA, potoki, email, the laptop-side MCP gateway, voice, OCR, ML Kit, root, rewriting android-gateway, Python `phone-control-mcp`.
