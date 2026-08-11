# TIM 局域网 API 使用指南

从 Magisk / LSPosed 安装，到 Bridge 收发与 Redis 消费。  
接口细节见 [`tim_bridge/API.md`](../tim_bridge/API.md)；Hook 短文见 [`tim_hook/README.md`](../tim_hook/README.md)；锁定版本见 [`tim_hook/SUPPORTED_TIM.md`](../tim_hook/SUPPORTED_TIM.md)。

---

## 1. 风险

- 非官方 Hook，可能违反 TIM/QQ 协议，**有封号风险**；仅用实验号。
- 锁定 **TIM 4.1.0 / versionCode 4050**，不要热更。
- 与 `wechat_*`、`nexus_phone` 并列，勿混改。

---

## 2. 架构

| 组件 | 路径 | 作用 |
|------|------|------|
| **tim_protocol** | `tim_protocol/` | 帧编解码与字段常量 |
| **tim_bridge** | `tim_bridge/` | HTTP `:8788`、配置、可选 Redis / Webhook / Token |
| **tim_hook** | `tim_hook/` | LSPosed 注入 `com.tencent.tim` |

```text
局域网 / curl / 你的后端
        │  HTTP  :8788  （可选 API Token）
        ▼
┌─────────────────── tim_bridge ───────────────────┐
│  前台服务 · EventStore(内存约 200)                 │
│  可选：Redis Stream XADD · Webhook 告警           │
│  Hook IPC server：127.0.0.1:18788（本机 TCP）     │
└─────────────────────────┬───────────────────────┘
                          │
                          ▼
┌─────────────────── tim_hook ─────────────────────┐
│  LSPosed → com.tencent.tim                        │
│  发：IMsgService.sendMsg（QQNT）                   │
│  收：MsgService onRecvMsg → MSG_IN                 │
└──────────────────────────────────────────────────┘
```

**为何用本机 TCP 而不是 abstract UDS？**  
TIM 进程内 abstract socket 会被 SELinux 拒绝；改为 `127.0.0.1:18788`。

**为何不用旧 Facade？**  
`ChatActivityFacade.H0 → J0` 在 4.1.0 是空实现；`addSendMsg` 只入本地（转圈）。真发出网是 `IMsgService.sendMsg`。

---

## 3. 安装

### 3.1 设备

- Magisk + Zygisk + LSPosed  
- 已安装并登录 **TIM 4.1.0 (4050)**

### 3.2 Bridge

```bat
cd tim_bridge
gradlew.bat :app:assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n com.nexus.tim.bridge.debug/com.nexus.tim.bridge.ui.MainActivity --ez auto_start true
```

Debug 包名：`com.nexus.tim.bridge.debug`。打开 App → **Start service**（或 `auto_start`）。

### 3.3 Hook

```bat
cd tim_hook
gradlew.bat :app:assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

LSPosed Manager：

1. 启用 **Nexus TIM Hook**
2. 作用域勾选 **TIM** `com.tencent.tim`
3. 强制停止 TIM 再打开  
4. logcat 过滤 `NexusTimHook`：应见 `HELLO … recv=true`

### 3.4 探活

```bat
adb forward tcp:8788 tcp:8788
curl http://127.0.0.1:8788/v1/health
```

期望：`hook=connected`，`logged_in=true`，`recv_hook=true`，`user_id` 为本机 TIM 号。

---

## 4. 通讯录 / 群列表

HELLO 会周期性同步；Bridge UI 显示 `Contacts N · Groups M`。

```bash
curl http://127.0.0.1:8788/v1/contacts
# {"ok":true,"contacts":[{"user_id":"95019432","display":"..."}, ...]}

curl http://127.0.0.1:8788/v1/groups
# {"ok":true,"groups":[{"chat_id":"troop:723765339","title":"...","is_group":true}, ...]}
```

---

## 5. 群成员

```bash
curl http://127.0.0.1:8788/v1/chats/troop:723765339/members
# {"ok":true,"chat_id":"troop:723765339","members":[{"user_id":"...","display":"..."}, ...]}
```

按需向 Hook 拉取（`getAllMemberList`），Bridge 侧缓存。

---

## 6. 收发验收

### 发文本 / 群 @

```bash
# 好友
curl -X POST http://127.0.0.1:8788/v1/messages/text \
  -H "Content-Type: application/json" \
  -d "{\"chat_id\":\"好友QQ\",\"text\":\"hi from tim bridge\"}"

# 群 @ 单人 / @所有人
curl -X POST http://127.0.0.1:8788/v1/messages/text \
  -H "Content-Type: application/json" \
  -d "{\"chat_id\":\"troop:群号\",\"text\":\"ping\",\"ats\":[\"95019432\"]}"

curl -X POST http://127.0.0.1:8788/v1/messages/text \
  -H "Content-Type: application/json" \
  -d "{\"chat_id\":\"troop:群号\",\"text\":\"注意\",\"ats\":[\"notify@all\"]}"
```

### 发图

```bash
curl -X POST http://127.0.0.1:8788/v1/messages/image \
  -F chat_id=好友QQ \
  -F file=@/path/to/test.jpg
```

### 收文本 / @

对方发消息后：

```bash
curl "http://127.0.0.1:8788/v1/events?after=0"
```

`payload` 含 `chat_id` / `text` / `from_id` / `is_group`，群消息另有 `ats` / `at_me` / `at_all`（见 API.md）。收图未做。

---

## 7. Redis / Webhook / Token（P2）

在 Bridge App **Settings** 中配置并 **Save settings**：

| 项 | 说明 |
|----|------|
| API Token | 开启后除 `/v1/health` 外需 `Authorization: Bearer` / `X-Api-Token` / `?token=` |
| Redis | 默认 Stream key `nexus:tim:events`；字段 `type` / `ts` / `data` |
| Webhook | 仅告警（断线、版本不符、发送失败、Redis 失败）；企微 URL 自动 `msgtype=text` |

```bash
# 局域网 Redis 示例
docker run -d --name nexus-redis -p 6379:6379 redis:7-alpine
redis-cli XREAD COUNT 10 BLOCK 5000 STREAMS nexus:tim:events $
```

---

## 8. 与微信栈差距（对齐进度）

| 能力 | 微信 | TIM |
|------|------|-----|
| 文本收发 + events | ✓ | ✓ |
| Redis / Webhook / Token | ✓ | ✓ |
| Compose Bridge UI | ✓ | ✓（已对齐） |
| contacts / groups API | ✓ | ✓ |
| chats（最近会话） | ✓ | ✗ |
| 群成员 | ✓ | ✓ |
| 群 @ 收发 | ✓ | ✓ |
| 发图 | ✓ | ✓ |
| 收图 / MEDIA_READY | ✓ | ✗ |
| HELLO 同步通讯录 | ✓ | ✓（contacts + groups） |
| filehelper | ✓ | 不适用 |

---

## 9. 常见问题

| 现象 | 处理 |
|------|------|
| `hook=disconnected` | 先开 Bridge，再冷启 TIM；检查 LSPosed 作用域 |
| `recv_hook=false` | 重装 unminified hook，冷启 TIM |
| 消息进会话但转圈 | 旧路径；确认已装含 `sendMsg` 的 hook |
| `/v1/events` 空但 log 有 MSG_IN | Bridge 当时未运行；现已有断线缓存，仍建议保持服务常开 |
| HTTP 401 | 开了 Token，请求带上鉴权 |

更多字段与 curl：[`tim_bridge/API.md`](../tim_bridge/API.md)。  
Hook 逆向笔记：[`tim_hook/HOOK_NOTES.md`](../tim_hook/HOOK_NOTES.md)。
