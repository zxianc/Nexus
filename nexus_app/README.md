# Nexus Assistant (`nexus_app`)

Kotlin sources for the App-side of the Zygisk ↔ App architecture.

## Build

SDK path is in `local.properties` (gitignored). On this machine: `E:\Android\Sdk`.

```bat
gradlew.bat :app:test
gradlew.bat :app:assembleDebug
```

`app/src/androidMain/` is merged into the main Android source set (LocalSocket / SmokeActivity).

## Layout

| Path | Role |
|------|------|
| `app/src/main/java/.../protocol` | APCM + framed UDS codec (shared with HAL constants) |
| `app/src/main/java/.../config` | JSON config true source |
| `app/src/androidMain/java/.../uds` | `PcmSocketClient` |
| `app/src/androidMain/java/.../ui` | `SmokeActivity` for G1 UDS smoke |

See `docs/superpowers/plans/2026-07-20-nexus-app-architecture.md`.
