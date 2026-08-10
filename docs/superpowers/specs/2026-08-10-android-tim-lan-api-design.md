# Android TIM 局域网 API（独立模块）设计

**日期：** 2026-08-10  
**状态：** Spec approved；实现计划见 `docs/superpowers/plans/2026-08-10-android-tim-lan-api.md`  
**范围：** 仅本机安卓 **TIM**（QQ 精简客户端）；不做微信 / 官方 QQ OpenAPI / Windows  
**与现有栈关系：** 与 `wechat_*`、`nexus_phone` **并列独立**；可同机共存，不改微信工程

---

## 1. 背景与目标

微信实验号异常后，改用 TIM（`com.tencent.tim`）作为局域网 IM 通道。TIM 与 QQ 生态互通，风控相对个人微信往往更宽松（**不保证**）。机上已装 TIM **4.1.0 / versionCode 4050**，并已准备**小号好友 + 测试群**用于联调。

### 1.1 MVP 目标（本期）

| 阶段 | 目标 |
|------|------|
| **P0 探活** | LSPosed 注入 TIM → UDS 连 Bridge → `GET /v1/health` 显示 `hook=connected`，上报登录态（uin / nick） |
| **P1 文本** | 对好友 QQ 号、群号 **发送与接收文本**；`/v1/events` 可轮询 |

### 1.2 非目标（本期不做）

- 图片 / 文件 / 语音 / 真 @  
- Redis Stream / Webhook / API Token（P2 可从 wechat_bridge 搬，不挡 P0/P1）  
- 注入完整版 QQ（`com.tencent.mobileqq`）——仅锁定 TIM  
- 合并进 `wechat_bridge` 多后端  
- 朋友圈、支付、加好友自动化  

### 1.3 与微信差异（已知）

- TIM **无**「文件传输助手」类固定会话；发送目标用真实好友 QQ / 群号  
- `chat_id` 格式以实现阶段反编译为准（文档锁定后写入 API.md）  
- 包名、混淆、Hook 点与微信完全不同，**不能复用 `wechat_hook` 字节码 Hook**

### 1.4 风险声明

非官方 Hook，可能违反腾讯用户协议，存在限流 / 冻结风险。约定：

- 仅用**实验小号**；主号勿登此机  
- **锁 TIM 版本**（4.1.0 / 4050），关应用商店自动更新  
- 首版 HTTP **无鉴权**（仅可信局域网）；P2 再补 Token  

---

## 2. 架构

### 2.1 组件

```text
[局域网客户端]
       │  HTTP :8788
       ▼
┌──────────────────────────┐
│  tim_bridge              │  独立 Android App + 前台服务
│  - REST / 事件游标       │
└────────────┬─────────────┘
             │  abstract UDS「nexus_tim」
             ▼
┌──────────────────────────┐
│  tim_hook                │  LSPosed，仅注入 com.tencent.tim
│  - HELLO / 文本收发      │
└────────────┬─────────────┘
             ▼
        TIM App（锁版本 + 小号）
```

| 组件 | 职责 |
|------|------|
| `tim_protocol` | UDS 帧编解码与字段名（可从 `wechat_protocol` 复制后改包名） |
| `tim_bridge` | HTTP `:8788`、发送队列、EventStore、UDS server |
| `tim_hook` | TIM 进程内收发；UDS client；版本校验与 HELLO |

### 2.2 仓库布局

```text
Nexus/
├── tim_protocol/
├── tim_bridge/
├── tim_hook/
├── wechat_*                 # 不变
└── docs/superpowers/specs/2026-08-10-android-tim-lan-api-design.md
```

### 2.3 为何新开而非扩展微信 Bridge

- 包名、协议语义、版本锁定、封禁域均不同  
- 微信栈可保留作对照；TIM 失败不影响已推送的 wechat 代码  
- 端口 / UDS / applicationId 分离，同机可双开调试  

---

## 3. 常量与包名

| 项 | 值 |
|----|-----|
| TIM 包名 | `com.tencent.tim` |
| 锁定 versionName | `4.1.0` |
| 锁定 versionCode | `4050` |
| Bridge applicationId | `com.nexus.tim.bridge`（debug 后缀 `.debug`） |
| Hook applicationId | `com.nexus.tim.hook` |
| HTTP | `0.0.0.0:8788` |
| UDS | abstract `nexus_tim`（文档写作 `@nexus_tim`） |
| 日志 TAG | `NexusTimHook` / `TimBridge` |

---

## 4. 协议（UDS）

首版帧类型与微信对齐，便于照抄 Bridge 逻辑（命名空间改为 `com.nexus.tim.protocol`）：

| type | 方向 | 用途 |
|------|------|------|
| `HELLO` | Hook → Bridge | 版本、logged_in、uin、nick |
| `SEND_TEXT` | Bridge → Hook | `chat_id` + `text` |
| `SEND_RESULT` | Hook → Bridge | ok / error / msg_id |
| `MSG_IN` | Hook → Bridge | 入站文本（含自发回显若可得） |
| `PING` / `PONG` | 双向 | 保活 |

本期不发 `SEND_IMAGE` / `MEDIA_READY`。

`MSG_IN` / 发送目标字段（初稿，反编译后可微调）：

| 字段 | 含义 |
|------|------|
| `chat_id` | 好友 QQ 号或群标识（格式实现时锁定） |
| `from_id` | 发送者 QQ |
| `text` | 文本 |
| `is_group` | 是否群 |
| `is_self` | 是否本机发出 |
| `ts` | Unix 秒 |

---

## 5. HTTP API（首版）

Base：`http://<phone-ip>:8788`

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/v1/health` | bridge / hook / tim_version / logged_in / uin |
| GET | `/v1/me` | 本机 uin / nick |
| GET | `/v1/events?after=` | 内存游标事件（约 200 条） |
| POST | `/v1/messages/text` | `{"chat_id":"<qq或群>","text":"..."}` |

不做：contacts 全量、media、Redis、鉴权（P2）。

版本不符时：health 标 `tim_version_mismatch`；**拒绝发送**。

---

## 6. 实现策略

### 6.1 骨架先行（已选）

1. 搭三件套工程 + 空 Hook（`IXposedHookLoadPackage` 仅 log + 连 UDS + 假/真 HELLO）  
2. Bridge 起服务，验收 P0  
3. 拉取 TIM APK，jadx/androguard 定位：登录态、消息入库/回调、发送入口  
4. 实现 P1 文本收发，对好友与群各验收一条  

### 6.2 锁版本

- `SUPPORTED_TIM.md` 记录 4.1.0 / 4050  
- Hook 启动比对 `PackageInfo`；不符则 HELLO 带 mismatch，不 Hook 发送  

### 6.3 测试账号

- 已具备小号好友 + 测试群；**无 filehelper**  
- 联调文档写明用真实 `chat_id`，不写死微信式常量  

---

## 7. 验收标准

**P0**

```bash
adb forward tcp:8788 tcp:8788
curl http://127.0.0.1:8788/v1/health
# hook=connected, logged_in=true, tim_version=4.1.0
```

logcat 过滤 `NexusTimHook` 见模块加载。

**P1**

```bash
curl -X POST http://127.0.0.1:8788/v1/messages/text \
  -H "Content-Type: application/json" \
  -d '{"chat_id":"<好友QQ>","text":"tim bridge hi"}'
# 对端 TIM/QQ 收到；/v1/events 可见 MSG_IN（自发或对端回复）
```

群聊同理换群 `chat_id`。

---

## 8. 文档交付（随实现）

| 文档 | 内容 |
|------|------|
| `docs/tim-lan-api-guide.md` | 安装 LSPosed 作用域勾 TIM、启 Bridge、联调 |
| `tim_bridge/API.md` | HTTP 字段 |
| `tim_hook/SUPPORTED_TIM.md` | 锁版本表 |
| `tim_hook/HOOK_NOTES.md` | 反编译笔记 |

---

## 9. 里程碑（建议）

1. Spec 审阅通过 → 写实现计划  
2. P0 骨架可装可连  
3. TIM 反编译笔记 + 文本发送 PoC  
4. P1 收发验收 + 简短使用指南  
5.（可选）P2 Redis / Webhook / Token  

---

## 10. Spec 自检

- [x] 无「TBD」占位阻断 P0/P1（`chat_id` 格式标明实现时锁定）  
- [x] 与 wechat 栈边界清晰（端口 / UDS / 包名）  
- [x] 非目标已列（图/文件/Redis）  
- [x] 风险与锁版本写明  
- [x] 验收命令可执行  
