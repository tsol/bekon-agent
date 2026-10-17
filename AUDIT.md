# Auditing Bekon Agent (for AI agents and humans)

The app links to the tag that matches its installed version, e.g. `v1.0.18`. Use the links for that tag; replace
`v1.0.18` with the version shown in the app (Settings → Version).

- Source tree: https://github.com/tsol/bekon-agent/tree/v1.0.18
- Zip archive: https://github.com/tsol/bekon-agent/archive/refs/tags/v1.0.18.zip
- Previous audit and its fixes: [SECURITY-AUDIT.md](SECURITY-AUDIT.md)

## Key files (raw, pinned to v1.0.18)
- https://raw.githubusercontent.com/tsol/bekon-agent/v1.0.18/SECURITY-AUDIT.md
- https://raw.githubusercontent.com/tsol/bekon-agent/v1.0.18/bekon-agent/src/main/AndroidManifest.xml
- https://raw.githubusercontent.com/tsol/bekon-agent/v1.0.18/bekon-agent/src/main/kotlin/pro/potoki/bekon/agent/mcp/McpHandler.kt
- https://raw.githubusercontent.com/tsol/bekon-agent/v1.0.18/bekon-agent/src/main/kotlin/pro/potoki/bekon/agent/access/ClientGate.kt
- https://raw.githubusercontent.com/tsol/bekon-agent/v1.0.18/bekon-agent/src/main/kotlin/pro/potoki/bekon/agent/server/LocalServer.kt
- https://raw.githubusercontent.com/tsol/bekon-agent/v1.0.18/bekon-agent/src/main/kotlin/pro/potoki/bekon/agent/session/SessionService.kt
- https://raw.githubusercontent.com/tsol/bekon-agent/v1.0.18/bekon-agent/src/main/kotlin/pro/potoki/bekon/agent/tunnel/CloudflareTunnel.kt
- https://raw.githubusercontent.com/tsol/bekon-agent/v1.0.18/bekon-agent/src/main/kotlin/pro/potoki/bekon/agent/core/DeviceExecutor.kt
- https://raw.githubusercontent.com/tsol/bekon-agent/v1.0.18/bekon-agent/src/main/kotlin/pro/potoki/bekon/agent/device/capture/ScreenCapture.kt
- https://raw.githubusercontent.com/tsol/bekon-agent/v1.0.18/bekon-agent/src/main/kotlin/pro/potoki/bekon/agent/status/Capabilities.kt
- https://raw.githubusercontent.com/tsol/bekon-agent/v1.0.18/bekon-agent/src/main/kotlin/pro/potoki/bekon/agent/handoff/AgentGuide.kt
- https://raw.githubusercontent.com/tsol/bekon-agent/v1.0.18/bekon-agent/src/main/kotlin/pro/potoki/bekon/agent/ui/AuditPrompt.kt
- https://raw.githubusercontent.com/tsol/bekon-agent/v1.0.18/bekon-agent/src/main/res/xml/accessibility_service_config.xml
- https://raw.githubusercontent.com/tsol/bekon-agent/v1.0.18/bekon-agent/build.gradle.kts

## Rules for the auditor
- Answer only from the actual code, with file:line references. No generic checklists.
- If you cannot open links, say so and ask the user to download the zip and upload it.
- If a file could not be read, say which one.

## Questions to answer
1. What data leaves the device, where to and when (every network endpoint and tunnel)?
2. How are permissions used (accessibility, screen capture, notifications)? Is anything excessive?
3. How is agent access checked (owner approval, per-session bearer token in `ClientGate.kt`)? Can it be bypassed?
4. Do Stop, Pause and Read-only really cut access (`SessionService.kt`, `DeviceExecutor.kt`, `McpHandler.kt`)?
