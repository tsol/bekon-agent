# Bekon Agent

<img src="docs/bekon-icon.png" width="96" alt="Bekon icon" align="right">

Bekon Agent is an Android app that lets an AI agent you trust see and operate your phone, with
your consent, through a private link. The screen is shared only while a session is running; a
visible frame, a notification and a one-tap **Stop** are shown for the whole session.

- Module: [`bekon-agent/`](bekon-agent/) (Kotlin, Jetpack Compose)
- Architecture: [`bekon-agent/ARCHITECTURE.md`](bekon-agent/ARCHITECTURE.md)
- Privacy policy: [`bekon-agent/src/main/assets/privacy.html`](bekon-agent/src/main/assets/privacy.html)

## Verify this build / ask your agent to audit

Every release is tagged with its version (`v1.0.1` … `v1.0.9`, …). The app's
"ask your agent to check our code" button opens the tag matching the installed version, for example
<https://github.com/tsol/bekon-agent/tree/v1.0.9>.

Hand that link to your AI agent and ask it to review:

1. What is captured (screen, accessibility events) and when — `session/`, `device/capture/`.
2. What leaves the device and where to — `server/`, `tunnel/`.
3. Which permissions are requested and why — `bekon-agent/src/main/AndroidManifest.xml`.
4. That the Stop button, pause and read-only modes really cut access.

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
