# Bekon Agent

One-tap: give an agent temporary control of this phone (Instagram scroll, taps, screenshots), then auto-stop.

Not the full Bekon Suite laptop stack. This app embeds (or exposes) a **Java/Kotlin MCP server** and reuses WLYA + Gateway control capabilities from the monorepo.

## Layout

| Path | Role |
|------|------|
| `apps/bekon-agent` | Android UI: connect, QR/URL, timer, live log, stop |
| `apps/bekon-agent-mcp` | Kotlin/JVM MCP server (own process or in-app) — **not** Python `phone-control-mcp` |
| Existing | `android-gateway` gestures/a11y, `wlya-*` transport, `CONTROL-PROTOCOL.md` |

## Non-goals (v1)

- Full desktop-ui / Node `phone-control-api`
- Voice / Line / Magisk voice module
- Permanent always-on phone sharing

## Night plan

See [`NIGHT-PLAN.md`](NIGHT-PLAN.md).
