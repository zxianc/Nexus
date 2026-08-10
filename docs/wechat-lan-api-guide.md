# 微信局域网 API 使用指南

从 Root / Magisk / LSPosed 安装，到 Bridge 收发与 Redis 消费，说明各组件**作用、原理与操作步骤**。

API 字段与 curl 示例见 [`wechat_bridge/API.md`](../wechat_bridge/API.md)。  
Hook 安装短文见 [`wechat_hook/README.md`](../wechat_hook/README.md)。  
锁定微信版本见 [`wechat_hook/SUPPORTED_WECHAT.md`](../wechat_hook/SUPPORTED_WECHAT.md)。

---

## 1. 风险与适用场景

- 非官方 Hook，可能违反微信用户协议，**有封号风险**。
- **仅用实验小号**；主号不要登录此机。
- 微信升级后 Hook 可能失效——靠**锁版本**，不追热更。
- 本栈与 Nexus 电话 AI（`nexus_phone`）**无代码依赖**，可同机共存，但请分开验收。

---

## 2. 整体架构

仓库里与微信相关的三件套：

| 组件 | 路径 | 进程 / 形态 | 作用 |
|------|------|-------------|------|
| **wechat_protocol** | `wechat_protocol/` | JVM 库 | UDS 帧编解码与字段常量（Bridge / Hook 共用） |
| **wechat_bridge** | `wechat_bridge/` | 独立 App（前台服务） | 局域网 HTTP API、UDS 服务端、可选 Redis / Webhook |
| **wechat_hook** | `wechat_hook/` | LSPosed 模块（注入微信） | 在微信进程内收发消息，经 UDS 连 Bridge |

```text
局域网业务 / curl / 你的后端
        │  HTTP  :8787  （可选 API Token）
        ▼
┌─────────────────── wechat_bridge ───────────────────┐
│  前台服务 · EventStore(内存) · MediaStore             │
│  可选：Redis Stream XADD · Webhook 告警              │
│  UDS server：abstract socket 「nexus_wechat」         │
└─────────────────────────┬───────────────────────────┘
                          │  本机 UDS（不经 TCP）
                          ▼
┌─────────────────── wechat_hook ─────────────────────┐
│  LSPosed 注入 com.tencent.mm                         │
│  收：监听消息 → MSG_IN / MEDIA_READY                  │
│  发：SEND_TEXT / SEND_MEDIA → 调微信内部发送          │
└─────────────────────────┬───────────────────────────┘
                          ▼
                     微信 App（锁版本）
```

**为什么拆成 Bridge + Hook？**

- Hook 只能活在**微信进程**里，不适合长期监听 `0.0.0.0:8787`、连 Redis、做 UI 配置。
- Bridge 是普通 App：起 HTTP、管配置、推 Redis；通过本机 UDS 跟 Hook 说话。
- Protocol 保证两边帧格式一致，避免各自拼包。

---

## 3. 各层原理

### 3.1 Magisk + Zygisk

| 概念 | 作用 |
|------|------|
| **Magisk** | 系统级 Root 框架；管理模块、授权 `su` |
| **Zygisk** | Magisk 的进程注入能力，在 `zygote` 派生 App 时加载模块 |
| **本栈对 Magisk 的依赖** | LSPosed（Zygisk 版）依赖 Zygisk；Bridge 发图时可选 `su` 建 `/data/local/tmp/nexus_wechat` 供微信读文件 |

没有 Magisk / Zygisk，就装不了现代 LSPosed，Hook 无法进微信进程。

### 3.2 LSPosed

| 概念 | 作用 |
|------|------|
| **LSPosed** | 基于 Zygisk 的 Xposed 兼容框架 |
| **模块 APK** | `wechat_hook` 安装后出现在 LSPosed Manager |
| **作用域（Scope）** | 必须勾选 **微信** `com.tencent.mm`，模块才注入该进程 |
| **冷启动** | 勾选 / 更新模块后需**强制停止微信再打开**，注入才生效 |

Hook 加载成功时 logcat（过滤 `NexusWeChatHook`）可见类似 `nexus_wechat_hook loaded`。

### 3.3 wechat_hook（注入层）

运行在微信进程内，主要做：

1. **版本校验**：仅支持锁定版本（当前 **8.0.76 / versionCode 3141**），不符则拒绝发送并告警。
2. **连 Bridge**：作为 UDS **客户端**连接 abstract `nexus_wechat`；连上后发 `HELLO`（登录态、wxid、会话 / 好友 / 群摘要等）。
3. **收消息**：Hook 微信消息路径 → 组装 JSON → `MSG_IN`；图片等导出到共享目录后发 `MEDIA_READY`。
4. **发消息**：收到 Bridge 的 `SEND_TEXT` / `SEND_MEDIA` → 调微信内部发送 API → `SEND_RESULT`。
5. **群 @**：解析 / 写入微信 `atuserlist`（真 @，不是只拼正文昵称）。

### 3.4 wechat_bridge（网关层）

| 能力 | 说明 |
|------|------|
| **HTTP :8787** | 局域网 REST（health / me / chats / contacts / groups / events / messages / media） |
| **UDS 服务端** | 等 Hook 连接；状态 `Hook: connected/disconnected` |
| **EventStore** | 内存环形约 200 条，供 `/v1/events` 调试；**非持久化** |
| **Redis Stream** | 业务消息主路径：`XADD`（Bridge **只写不读**） |
| **Webhook** | **仅告警**（断连、版本不符、Redis 失败、发送失败等） |
| **API Token** | 可选；开启后除 `/v1/health` 外需鉴权 |
| **媒体暂存** | 发：stage 到 `/data/local/tmp/nexus_wechat/out`（失败则公共目录）；收：Hook 导出后 Bridge 登记供 `GET /v1/media/{id}` |

通知栏文案会随 Hook / 登录态刷新，例如：`Hook: connected · logged in: yes · wxid_xxx`。

### 3.5 wechat_protocol（协议层）

二进制帧：`type` + payload（UTF-8 JSON）。常用类型：

| type | 方向（相对 Bridge） | 含义 |
|------|---------------------|------|
| `HELLO` | Hook → Bridge | 上线握手、账号与通讯录摘要 |
| `SEND_TEXT` / `SEND_MEDIA` | Bridge → Hook | 发送请求 |
| `SEND_RESULT` | Hook → Bridge | 发送结果 |
| `MSG_IN` | Hook → Bridge | 收到（或自发回显）消息 |
| `MEDIA_READY` | Hook → Bridge | 媒体文件已导出到可读路径 |
| `PING` / `PONG` | 双向 | 保活 |

### 3.6 Redis Stream（可选出站）

- Bridge 在收到 `message` / `media_ready` 时 `XADD` 到配置的 key（默认 `nexus:wechat:events`）。
- 字段：`type` / `ts` / `data`（JSON）。
- **不是**普通 KV：Stream 是可追加的事件日志；无人消费会积压（当前未设 `MAXLEN`）。
- 消费者在你的服务器上自己写（`XREAD` / 消费组），详见 API.md。

### 3.7 Root 是否必须？

| 场景 | 是否需要 Root |
|------|----------------|
| Magisk + LSPosed + Hook 文本收发 | 需要 Root 环境装框架；Bridge **不必**每次点授权 |
| Bridge 启动弹 `su` | 为建 `/data/local/tmp/nexus_wechat`（发图更稳）；可拒，会 fallback 公共目录 |
| 读微信私有目录媒体 | 往往需要 root / 或依赖 Hook 侧已导出的路径 |

---

## 4. 环境准备（从零）

以下以已解锁 Bootloader 的 Android 机为例（样机：OnePlus 8T）。具体刷机步骤因 ROM 而异，此处从 **Magisk 已可开机** 之后写起。

### 4.1 Magisk：打开 Zygisk

1. 打开 Magisk App → 设置。
2. 开启 **Zygisk**。
3. 按提示重启。
4. Magisk 首页确认 Zygisk 为 Yes。

### 4.2 安装 LSPosed（Zygisk 版）

1. 从可信来源获取 **LSPosed Zygisk** 模块（团队曾用过 JingMatrix 构建，如 v1.11.x；以你设备能稳定运行为准）。
2. Magisk → 模块 → 从本地安装 → 重启。
3. 安装并打开 **LSPosed Manager**，确认框架状态正常（已激活）。

> 若 Manager 提示未激活：确认 Zygisk 已开、模块已启用并重启、未被其它隐藏模块误伤。

### 4.3 安装并锁定微信

1. 安装 **微信 8.0.76（versionCode 3141）**（APK 不进 git；可从已 pin 设备 `adb pull`）。
2. **关闭自动更新**（应用商店 / Play / 微信内更新）。
3. 用**实验小号**登录。

验证版本：

```bat
adb shell dumpsys package com.tencent.mm | findstr version
```

应看到 `versionName=8.0.76` 与 `versionCode=3141`。

### 4.4 编译安装 Hook

```bat
cd wechat_hook
gradlew.bat :app:assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

LSPosed Manager：

1. 模块列表启用 **Nexus WeChat Hook**。
2. 作用域勾选 **微信** `com.tencent.mm`。
3. 强制停止微信 → 再打开。
4. logcat：

```bat
adb logcat -s NexusWeChatHook:V LSPosed-Bridge:V
```

应出现模块加载日志。此时若 Bridge 未启动，Hook 会连不上 UDS（属正常）。

### 4.5 编译安装 Bridge

```bat
cd wechat_bridge
gradlew.bat :app:assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

包名（debug）：`com.nexus.wechat.bridge.debug`。

1. 打开 **WeChat Bridge** App。
2. 点 **Start service**（通知栏出现前台服务）。
3. 若弹出 Root 授权：发图建议允许；仅测文字可拒绝。
4. **强制停止微信并重新打开**（让 Hook 重新连 UDS）。
5. 等待通知 / 界面显示 `Hook: connected`，`Logged in: yes`。

USB 调试时：

```bat
adb forward tcp:8787 tcp:8787
curl http://127.0.0.1:8787/v1/health
```

期望大致为：

```json
{
  "bridge": "ok",
  "hook": "connected",
  "wechat_version": "8.0.76",
  "logged_in": true,
  "user_id": "wxid_..."
}
```

### 4.6（可选）配置 Redis / Webhook / API Token

在 Bridge App 向下滚动：

| 区块 | 用途 |
|------|------|
| **API auth** | 局域网 API 令牌；开启后除 health 外需 `Authorization: Bearer …` |
| **Redis push** | host / port / password / stream key；host 填**手机能访问**的地址（不要填电脑的 `127.0.0.1`，除非 Redis 在手机上） |
| **Alert webhook** | 仅故障告警 |

点 **Save all settings**。改 Redis 后发一条消息验证（见第 6 节）。

局域网 Redis 示例：

```bash
docker run -d --name nexus-redis -p 6379:6379 redis:7-alpine
```

防火墙需放行手机 → Redis 主机的 `6379`。

---

## 5. 推荐启动顺序

```text
1. 手机已 Root，Zygisk + LSPosed 正常
2. 微信已锁版本并登录小号
3. Start Bridge 服务（HTTP + UDS 监听）
4. 冷启动微信（Hook 连接 → HELLO）
5. 看 Hook: connected
6. 再调 HTTP / 等 Redis
```

**常见误区**：只开 Bridge 不会变成 `connected`——必须微信进程里的 Hook 连上来。界面约 1.5s 刷新；通知栏在 connect / disconnect / HELLO 时更新，无需手动刷 App。

---

## 6. 验收清单

### 6.1 文本

```bat
adb forward tcp:8787 tcp:8787
curl -X POST http://127.0.0.1:8787/v1/messages/text ^
  -H "Content-Type: application/json" ^
  -d "{\"chat_id\":\"filehelper\",\"text\":\"hello bridge\"}"
```

微信「文件传输助手」应出现消息；`/v1/events` 或 Redis 中可见对应事件。

### 6.2 列表

```bat
curl http://127.0.0.1:8787/v1/me
curl http://127.0.0.1:8787/v1/chats
curl http://127.0.0.1:8787/v1/contacts
curl http://127.0.0.1:8787/v1/groups
```

### 6.3 Redis（若已开启）

在 Redis 主机：

```bash
redis-cli XLEN nexus:wechat:events
redis-cli XREVRANGE nexus:wechat:events + - COUNT 3
```

发一条微信消息后 `XLEN` 应增加；`data` 内含 `chat_id` / `text` 等。

### 6.4 图片（可选）

见 [`API.md`](../wechat_bridge/API.md) multipart 示例。默认按微信「原图」发送；非图片文件发送尚未实现。

---

## 7. 排障

| 现象 | 可能原因 | 处理 |
|------|----------|------|
| `Hook: disconnected` | Bridge 未起 / 微信未冷启 / 模块未勾选微信 | 按第 5 节顺序重来；查 logcat `NexusWeChatHook` |
| LSPosed 无模块 | Hook APK 未装或 Manager 未刷新 | 重装 Hook；打开 Manager 查看 |
| `wechat_version_mismatch` | 微信被升级 | 重装 8.0.76 / 3141，关更新 |
| HTTP 连不上 | 未 Start service / 未 forward / 防火墙 | `adb forward`；同 Wi‑Fi 用手机 IP:8787 |
| `401 unauthorized` | 开了 API Token | 带 Bearer / 关闭鉴权 |
| Redis error / 无数据 | 手机到 Redis 不通；或曾遇 Jedis JMX（已修） | host 用局域网 IP；查 App Redis 状态与 logcat `WeChatBridgeRedis` |
| 发图失败 | 共享目录权限 | 给 Bridge 一次 Magisk 授权，或确认 `/sdcard/nexus_wechat` |
| 通知栏不更新 | 旧版 Bridge | 更新到会刷新通知的版本 |

---

## 8. 包名与常量速查

| 项 | 值 |
|----|-----|
| Bridge debug 包名 | `com.nexus.wechat.bridge.debug` |
| Hook 包名 | `com.nexus.wechat.hook` |
| 微信包名 | `com.tencent.mm` |
| HTTP | `0.0.0.0:8787` |
| UDS | abstract `nexus_wechat`（文档常写作 `@nexus_wechat`） |
| 支持微信 | `8.0.76` / `3141` |
| Redis 默认 stream | `nexus:wechat:events` |
| 媒体临时目录 | `/data/local/tmp/nexus_wechat/{in,out}` |

---

## 9. 相关文档

| 文档 | 内容 |
|------|------|
| [`wechat_bridge/API.md`](../wechat_bridge/API.md) | HTTP / Redis / 鉴权 / 群 @ 等接口细节 |
| [`wechat_hook/README.md`](../wechat_hook/README.md) | Hook 安装短步骤 |
| [`wechat_hook/SUPPORTED_WECHAT.md`](../wechat_hook/SUPPORTED_WECHAT.md) | 锁定版本表 |
| [`wechat_hook/HOOK_NOTES.md`](../wechat_hook/HOOK_NOTES.md) | 反编译与 Hook 点笔记 |
| [`docs/superpowers/specs/2026-08-09-android-wechat-lan-api-design.md`](superpowers/specs/2026-08-09-android-wechat-lan-api-design.md) | 设计 Spec |
| [`docs/superpowers/plans/2026-08-09-android-wechat-lan-api.md`](superpowers/plans/2026-08-09-android-wechat-lan-api.md) | 实现计划 |
