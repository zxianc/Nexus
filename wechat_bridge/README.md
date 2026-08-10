# WeChat Bridge

局域网微信网关 App：HTTP `:8787` + UDS `@nexus_wechat`，对接 LSPosed 模块 `wechat_hook`。

## 文档

| 文档 | 内容 |
|------|------|
| [**使用指南（推荐）**](../docs/wechat-lan-api-guide.md) | 从 Magisk / LSPosed 安装到原理、验收、排障 |
| [`API.md`](API.md) | HTTP / Redis Stream / 鉴权 / 收发接口 |

## 快速编译

```bat
cd wechat_bridge
gradlew.bat :app:assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Debug 包名：`com.nexus.wechat.bridge.debug`。打开 App → **Start service** → 冷启动已注入的微信 → 状态变为 `Hook: connected`。
