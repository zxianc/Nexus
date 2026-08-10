# TIM Bridge

LAN gateway for pinned TIM. HTTP `:8788`, UDS `@nexus_tim`.

See plan: [`docs/superpowers/plans/2026-08-10-android-tim-lan-api.md`](../docs/superpowers/plans/2026-08-10-android-tim-lan-api.md).

```bat
cd tim_bridge
gradlew.bat :app:assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n com.nexus.tim.bridge.debug/com.nexus.tim.bridge.ui.MainActivity --ez auto_start true
adb forward tcp:8788 tcp:8788
curl http://127.0.0.1:8788/v1/health
```
