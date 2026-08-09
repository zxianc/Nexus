# Android 微信局域网 API（独立模块）设计

**日期：** 2026-08-09  
**状态：** Spec approved；实现计划见 `docs/superpowers/plans/2026-08-09-android-wechat-lan-api.md`  
**范围：** 仅本机安卓微信；不做 Windows / WeChatFerry / 网页协议  
**与 Nexus 电话栈关系：** 完全独立，不依赖 `nexus_phone` / `nexus_audio_hook`

---

## 1. 背景与目标

在已 Root 的一加 8T（LineageOS + Magisk + LSPosed）上，使用**锁定版本的安卓微信小号**，通过进程 Hook 接管通信能力，并由独立 Bridge 对局域网暴露 HTTP API，便于脚本或其它服务收发消息。

### 1.1 目标

- 私聊 / 群聊：**发送与接收**文本、图片、文件  
- 群聊支持真正的 **@ 成员**（按微信内部 id，而非仅文本 `@昵称`）  
- 局域网 HTTP API；首版**不做鉴权**（后续必补）  
- 组件与现有 AI 电话功能解耦，可同机共存

### 1.2 非目标

- 朋友圈、支付 / 红包、自动通过好友、改资料  
- Windows 微信、网页微信、企微官方开放平台替代个人号  
- 塞进 `nexus_phone` 或复用音频 UDS  
- 追每一个微信热更新版本

### 1.3 风险声明

个人微信无官方「任意发消息」开放 API。本方案基于非官方 Hook，**可能违反微信用户协议**，存在限流 / 封号风险。约定：

- 仅使用**实验小号**，主号不登录该机  
- 稳定靠**锁微信版本**，不靠频繁跟版  
- 首版无鉴权：任何能访问该局域网的客户端均可发信；家庭环境外勿用，下一阶段补 Token

---

## 2. 架构

### 2.1 组件

```text
[局域网客户端]
       │  HTTP :8787（可配置；首版无鉴权）
       ▼
┌──────────────────────────┐
│  wechat_bridge           │  独立 Android App + 前台服务
│  - REST / 事件游标       │
│  - 媒体暂存与对外下载    │
└────────────┬─────────────┘
             │  本机 abstract UDS（如 @nexus_wechat）
             ▼
┌──────────────────────────┐
│  wechat_hook             │  LSPosed 模块，仅注入微信进程
│  - 发送文本 / 图 / 文件  │
│  - 接收与 @ 解析         │
│  - 媒体导出到约定目录    │
└────────────┬─────────────┘
             ▼
        微信 App（固定版本 + 小号）
```

| 组件 | 职责 |
|------|------|
| `wechat_bridge` | 局域网 HTTP、发送队列、事件日志、媒体 HTTP 托管；UDS server |
| `wechat_hook` | 微信进程内收发与媒体导出；UDS client；上报登录态与版本 |
| 微信 App | 人工登录与日常客户端；禁止自动更新 |

### 2.2 仓库布局（建议）

```text
Nexus/
├── wechat_bridge/     # 独立 App
├── wechat_hook/       # LSPosed 模块
├── nexus_phone/       # 不变
├── zygisk_module/     # 不变
└── docs/superpowers/specs/2026-08-09-android-wechat-lan-api-design.md
```

### 2.3 为何拆成 Bridge + Hook

- HTTP 与微信进程隔离：微信被杀时 API 可返回明确 `503`，Bridge 崩溃不拖垮微信  
- 与现有「旁路核心 / 业务表面分离」一致  
- 发送串行队列、媒体落盘更适合放在 Bridge

**不采用：** 在微信进程内直接起 HTTP（方案已否决，不利于稳定）。  
**不采用：** Windows WeChatFerry（需求明确为手机端）。

---

## 3. 局域网 HTTP API

默认监听 `0.0.0.0:8787`。

### 3.1 会话与状态

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/v1/health` | Bridge / Hook / 登录 / 微信版本是否匹配 |
| `GET` | `/v1/me` | 当前小号基本信息 |
| `GET` | `/v1/chats` | 最近会话（`chat_id`、显示名、是否群） |
| `GET` | `/v1/chats/{chat_id}/members` | 群成员列表（供 @）；私聊可 `404` |

`chat_id` 使用微信内部稳定 id（如 `wxid_*`、`***@chatroom`）。展示名不得作为唯一键。

`GET /v1/health` 示例：

```json
{
  "bridge": "ok",
  "hook": "connected",
  "wechat_version": "8.0.x",
  "supported_wechat_version": "8.0.x",
  "logged_in": true
}
```

版本不符时增加 `wechat_version_mismatch: true`，发送类接口拒绝。

### 3.2 发送

| 方法 | 路径 | Body |
|------|------|------|
| `POST` | `/v1/messages/text` | JSON：`chat_id`, `text`, `ats?`（`wxid` 数组） |
| `POST` | `/v1/messages/image` | `multipart`：`chat_id` + 图片文件 |
| `POST` | `/v1/messages/file` | `multipart`：`chat_id` + 文件 |

响应：`{ "ok": true, "msg_id": "..." }` 或 `{ "ok": false, "error": "..." }`。

| HTTP | 含义 |
|------|------|
| `200` | 发送成功 |
| `400` | 参数错误 / 超大小限制 |
| `503` | Hook 未连接或未登录 |
| `504` | Hook 发送超时 |

群 `@`：必须通过 `ats` 传成员 id，由 Hook 生成微信认可的 @ 结构。

### 3.3 接收

首版以**游标轮询**为主：

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/v1/events?after={cursor}` | 增量事件（主路径） |
| `GET` | `/v1/media/{id}` | 下载已导出的图片 / 文件 |

事件条目至少包含：`type`, `msg_id`, `chat_id`, `from_id`, `is_group`, `text?`, `ats?`, `media?`, `ts`。

`media` 形如：`{ "kind": "image"|"file", "url": "/v1/media/xxx", "name": "a.jpg" }`。

可选后续：`WebSocket /v1/ws`、`GET /v1/messages?chat_id=`（不挡 MVP）。

### 3.4 限制（MVP）

- 单文件大小上限：**25MB**（可配置）  
- 发送在 Bridge 内**串行**，消息间隔默认 **0.5–1s**  
- 不做朋友圈等非通信能力

---

## 4. Hook ↔ Bridge 内部协议

### 4.1 传输

- Abstract UDS，例如 `@nexus_wechat`  
- Bridge = server，Hook = client（微信进程就绪后连接）  
- 帧：`type | flags | u32 len | payload`；MVP payload 用 JSON  
- 不对局域网暴露该 UDS

### 4.2 消息类型

| type | 方向 | 含义 |
|------|------|------|
| `HELLO` | Hook→Bridge | 微信版本、登录态、能力位 |
| `SEND_TEXT` / `SEND_IMAGE` / `SEND_FILE` | Bridge→Hook | 发送 |
| `SEND_RESULT` | Hook→Bridge | 成功 / 失败 + `msg_id` |
| `MSG_IN` | Hook→Bridge | 新消息（文本 / @ / 媒体元数据） |
| `MEDIA_READY` | Hook→Bridge | 媒体已导出到约定路径 |
| `PING` | 双向 | 探活 |

### 4.3 发送流程

```text
HTTP POST → Bridge 校验并（如图/文件）落盘
         → UDS SEND_*
         → Hook 调用微信内部发送
         → SEND_RESULT
         → HTTP 响应
```

超时（建议 15s）未收到 `SEND_RESULT` → HTTP `504`；默认不自动重试。

### 4.4 接收流程

```text
微信收信 → Hook 回调
        → 文本/@ → MSG_IN
        → 图片/文件 → 导出 → MEDIA_READY + MSG_IN（带 media_id）
        → Bridge 追加事件（cursor 单调递增）
        → 客户端 GET /v1/events?after=cursor
```

### 4.5 媒体路径

| 阶段 | 位置 |
|------|------|
| 待发送暂存 | Bridge 私有目录 `cache/out/` |
| 接收导出 | Bridge 可读目录 `cache/in/{media_id}` |
| 对外 | 仅经 `GET /v1/media/{id}` |

微信进程与 Bridge 之间的路径权限需在实现阶段用可读共享目录或 `FileProvider`/拷贝策略落实；设计要求：**调用方永不直接读微信私有目录**。

---

## 5. 稳定与运维约定

| 项 | 约定 |
|----|------|
| 微信版本 | 实现阶段选定单一 APK 版本号写入 `supported_wechat_version` 常量；安装包可归档；关闭自动更新 |
| 跟版 | 微信升级 = 新里程碑；不保证热兼容 |
| 账号 | 仅小号 |
| 鉴权 | MVP 无；**下一阶段必做** Token（可选 IP 白名单） |
| 共存 | 与 `nexus_phone` / `nexus_audio_hook` 无代码依赖 |

---

## 6. 验收标准（MVP）

- [ ] 锁定版本小号登录后，`GET /v1/health` 为 `hook=connected` 且 `logged_in=true`  
- [ ] 局域网其它设备可发送文本到好友 / 群，微信侧可见  
- [ ] 群发可带 `ats`，对端收到真正 @  
- [ ] 可发送图片、文件  
- [ ] 可接收文本 / 图片 / 文件，经 `/v1/events` 与 `/v1/media/{id}` 取回  
- [ ] 停止 Bridge 不影响微信；杀微信后发送返回 `503`，恢复后自动重连  
- [ ] AI 电话旁路回归：现有通话链路不受影响  

---

## 7. 实现分期

| 阶段 | 内容 |
|------|------|
| M1 | Bridge 骨架 + UDS + `health`；Hook `HELLO`/探活 |
| M2 | 文本发送 + 事件接收（文本） |
| M3 | 群 @ + 成员列表 |
| M4 | 图片 / 文件收发与 `/v1/media` |
| M5 | 鉴权（Token）+ 文档与锁版本说明 |

开源参照（能力模型，非直接移植）：

- [WeChatFerry](https://github.com/lich0821/WeChatFerry)：Hook→API 的职责切分与事件思维（PC 端，本项目不采用其运行环境）  
- 本仓库 `zygisk_module` + App：本机 UDS 旁路与进程隔离模式  

---

## 8. 决策记录

| 决策 | 选择 |
|------|------|
| 平台 | 仅安卓手机微信 |
| 接管方式 | LSPosed Hook（稳定优先 = 锁版本） |
| 部署 | 独立 `wechat_bridge` + `wechat_hook`，不进 `nexus_phone` |
| 鉴权 | MVP 无，后续补 |
| 接收 | 游标轮询为主 |
| 内部 IPC | Abstract UDS |
