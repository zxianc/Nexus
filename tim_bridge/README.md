# TIM Bridge

LAN gateway for pinned TIM. HTTP `:8788`, Hook IPC `127.0.0.1:18788`.

- 使用指南：[`docs/tim-lan-api-guide.md`](../docs/tim-lan-api-guide.md)
- API：[`API.md`](API.md)
- 设计/计划：[`docs/superpowers/`](../docs/superpowers/)

```bat
cd tim_bridge
gradlew.bat :app:assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n com.nexus.tim.bridge.debug/com.nexus.tim.bridge.ui.MainActivity --ez auto_start true
adb forward tcp:8788 tcp:8788
curl http://127.0.0.1:8788/v1/health
```
