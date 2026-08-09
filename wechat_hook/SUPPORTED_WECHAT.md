# Supported WeChat pin

| Field | Value |
|-------|-------|
| package | `com.tencent.mm` |
| versionName | **8.0.76** |
| versionCode | **3141** |
| device sample | OnePlus 8T (KB2000) |
| noted | 2026-08-09 |

APK is **not** stored in git. Pull from device if needed:

```bat
adb shell pm path com.tencent.mm
adb pull /data/app/.../base.apk wechat-8.0.76.apk
```

Disable WeChat auto-update after pin (Play / 应用商店 / 微信内更新入口).
