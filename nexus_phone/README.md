# Nexus Phone

Fossify Phone vendored and rebranded for Nexus AI call assistant.

## Upstream

See [UPSTREAM.md](UPSTREAM.md).

## Build

```powershell
cd nexus_phone
.\gradlew.bat :app:assembleCoreDebug
```

APK: `app/build/outputs/apk/core/debug/phone-*-core-debug.apk`

- **applicationId:** `org.fossify.nexus.phone` (debug: `.debug`) — required by Fossify Commons anti-mod checks  
- **namespace / Kotlin:** `com.nexus.phone`

Needs Android SDK (`local.properties` → `sdk.dir=...`).

## Install

```powershell
adb install -r app\build\outputs\apk\core\debug\phone-22-core-debug.apk
```

Set as **default Phone app**, then open **Settings → Nexus / AI**.

## HAL

PCM bypass still uses Magisk module `nexus_audio_hook` (`zygisk_module/`). See repo `doc/00_framework_overview.md`.
