# Nexus Assistant (`nexus_app`)

Kotlin sources for the App-side of the Zygisk ↔ App architecture.

## Current build mode (dev host)

This tree builds as **Kotlin JVM** (`org.jetbrains.kotlin.jvm`) so protocol/config unit tests run without an Android SDK:

```bat
gradlew.bat :app:test
```

`app/src/androidMain/` holds Android-only types (`LocalSocket`, Activities). Switch `:app` to `com.android.application` (AGP) on a machine with Android SDK, and add `androidMain` to the Android source sets / Manifest.

## Layout

| Path | Role |
|------|------|
| `app/src/main/java/.../protocol` | APCM + framed UDS codec (shared with HAL constants) |
| `app/src/main/java/.../config` | JSON config true source |
| `app/src/androidMain/java/.../uds` | `PcmSocketClient` |
| `app/src/androidMain/java/.../ui` | `SmokeActivity` for G1 UDS smoke |

See `docs/superpowers/plans/2026-07-20-nexus-app-architecture.md`.
