# Nexus WeChat Hook (LSPosed)

独立 LSPosed 模块：注入锁定版本微信，经 abstract UDS `@nexus_wechat` 与 `wechat_bridge` 通信。

## 风险

- 非官方 Hook，可能违反微信用户协议，存在封号风险。
- **仅用实验小号**；主号不要登录此机。
- 微信升级后模块可能失效——靠锁版本，不追热更。

## 前置

1. Magisk + **Zygisk 开启**
2. 安装 **LSPosed (Zygisk)** 并装好 Manager
3. 微信锁定为 [`SUPPORTED_WECHAT.md`](SUPPORTED_WECHAT.md) 中的版本（当前 **8.0.76 / 3141**）
4. 本机已运行 `wechat_bridge` 前台服务

## 编译 / 安装

```bat
cd wechat_hook
gradlew.bat :app:assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

然后在 LSPosed Manager：

1. 启用模块 **Nexus WeChat Hook**
2. 作用域勾选 **微信** `com.tencent.mm`
3. 强制停止微信后冷启动
4. logcat 过滤 `NexusWeChatHook` / `LSPosed-Bridge`，应见 `nexus_wechat_hook loaded`

## 与 Bridge 联调

1. 启动 `wechat_bridge`（HTTP `:8787`）
2. 冷启动微信（模块连上 UDS 后发 `HELLO`）
3. `curl http://手机IP:8787/v1/health` → `hook=connected`
