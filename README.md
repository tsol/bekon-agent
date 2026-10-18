# Bekon Agent

<img src="docs/bekon-icon.png" width="96" alt="Bekon icon" align="right">

Bekon Agent is an Android app that lets an AI agent you trust see and operate your phone, with
your consent, through a private link. The screen is shared only while a session is running; a
visible frame, a notification and a one-tap **Stop** are shown for the whole session.

- Module: [`bekon-agent/`](bekon-agent/) (Kotlin, Jetpack Compose)
- Architecture: [`bekon-agent/ARCHITECTURE.md`](bekon-agent/ARCHITECTURE.md)
- Privacy policy: [`bekon-agent/src/main/assets/privacy.html`](bekon-agent/src/main/assets/privacy.html)

## Verify this build / ask your agent to audit

Every release is tagged with its version (`v1.0.1` … `v1.0.31`, …). The app's
"ask your agent to check our code" button opens the tag matching the installed version, for example
<https://github.com/tsol/bekon-agent/tree/v1.0.9>.

For agents: [AUDIT.md](AUDIT.md) and [llms.txt](llms.txt) list tag-pinned raw links to the key files and a zip.

Hand that link to your AI agent and ask it to review:

1. What is captured (screen, accessibility events) and when — `session/`, `device/capture/`.
2. What leaves the device and where to — `server/`, `tunnel/`.
3. Which permissions are requested and why — `bekon-agent/src/main/AndroidManifest.xml`.
4. That the Stop button, pause and read-only modes really cut access.

## Connecting an agent (auth)

**Preferred:** MCP JSON-RPC at `/mcp` (POST). **Fallback** for chatbots that can only open links: `/link/start?client=<name>` → owner Allow → private `/s/<token>/screen` with one-shot action links (and coach highlights even in read-only).


The tunnel URL stays clean. Authentication is a per-session token issued by the phone after the owner approves:

1. The agent sends `initialize` (or any request) with header `X-Bekon-Client-Id: <stable-id>` and no token.
   The phone shows an approval prompt; the request waits.
2. On **Allow**, the response carries `bekon_session_token` (32 random bytes, base64url) in the JSON-RPC result
   and in the `X-Bekon-Token` response header.
3. Every later request sends `Authorization: Bearer <token>` (or `X-Bekon-Token: <token>`) plus the client id.
   Without a valid token an approved id gets **HTTP 401**; the owner is not re-prompted silently.
4. The token lives only in memory and ends on Stop, when the session ends, when the owner taps
   "Disconnect agent", or when another agent is approved as a replacement. Approvals are never remembered across sessions.

No CORS headers are sent; the endpoint is for agents, not browser pages.

## Build

Requires JDK 17+ and the Android SDK (`sdk.dir` in `local.properties`).

Release tags point at the app source as it shipped (the module history was extracted from a larger
monorepo, so tags up to v1.0.8 contain only `bekon-agent/`). From `v1.0.9` on, tags are on `main` and build directly
(`git checkout v1.0.9`); for older tags, build from `main`:

```bash
./gradlew :bekon-agent:assembleDebug            # debug APK
./gradlew :bekon-agent:bundlePlayStoreRelease   # release bundle (unsigned unless BEKON_UPLOAD_* are set)
```

Play Store builds are re-signed by Google Play, so a locally built bundle won't be byte-identical;
compare the source at the tag.

## License

[AGPL-3.0](LICENSE)
