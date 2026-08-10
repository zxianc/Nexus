# Nexus TIM Hook (LSPosed)

Injects pinned **TIM** (`com.tencent.tim`) and connects to `tim_bridge` via loopback TCP  
`127.0.0.1:18788` (TIM SELinux blocks abstract UDS).

## Pin

See [`SUPPORTED_TIM.md`](SUPPORTED_TIM.md) — currently **4.1.0 / 4050**.

## Install

```bat
cd tim_hook
gradlew.bat :app:assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

LSPosed Manager:

1. Enable **Nexus TIM Hook**
2. Scope: **TIM** `com.tencent.tim`
3. Force-stop TIM, reopen
4. logcat: `NexusTimHook` → `nexus_tim_hook loaded`

Start `tim_bridge` first (`HTTP :8788`), then cold-start TIM → `curl http://127.0.0.1:8788/v1/health` should show `hook=connected`.
