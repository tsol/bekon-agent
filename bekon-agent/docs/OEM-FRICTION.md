# OEM Friction & Version Matrix (Part A5)

Compatibility targets: `minSdk 24` … `targetSdk 34` (as in the module).

| Theme | API / OEM | Expectation | Notes |
|-------|-----------|-------------|-------|
| Foreground service types | 29+ / 34+ | correct `foregroundServiceType` | `mediaProjection` registered in manifest; on Android 14+ also need `FOREGROUND_SERVICE_MEDIA_PROJECTION` |
| Restricted settings (a11y for sideload) | 13+ / Samsung | deep-link + instruction | `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` → ⋮ → "Allow restricted settings", then re-enable accessibility |
| Battery / auto-start | Samsung, Xiaomi, Huawei | intent if present, else instruction | best-effort `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`; OEM may still show "App was closed" — instruction text in SetupCard |
| Overlay draw | `SYSTEM_ALERT_WINDOW` / a11y overlay | `GestureOverlay` via a11y service window — verify on 14+ | TYPE_ACCESSIBILITY_OVERLAY does not need SYSTEM_ALERT_WINDOW grant |
| MediaProjection partial share | 14+ | UX copy: always pick "Entire screen" | system chooser cannot be removed without Device Owner / privileged |
