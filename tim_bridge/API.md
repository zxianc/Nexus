# TIM Bridge LAN API

安装与验收见 **[使用指南](../docs/tim-lan-api-guide.md)**。

Base: `http://<phone-ip>:8788`  
Hook IPC: `127.0.0.1:18788` (internal)  
Debug AppId: `com.nexus.tim.bridge.debug`

## Endpoints

| Method | Path | Notes |
|--------|------|-------|
| GET | `/v1/health` | `hook`, `tim_version`, `logged_in`, `user_id`, `recv_hook`（无需 Token） |
| GET | `/v1/me` | uin / nick |
| GET | `/v1/contacts` | 全部好友（`user_id` QQ 号 + `display` 备注/昵称） |
| GET | `/v1/groups` | 全部群（`chat_id=troop:<群号>` + `title`） |
| GET | `/v1/chats/{chat_id}/members` | 群成员（按需拉取并缓存；`user_id` + `display`） |
| GET | `/v1/events?after=` | memory ring（约 200 条） |
| POST | `/v1/messages/text` | JSON `chat_id` + `text`，可选 `ats` |
| POST | `/v1/messages/image` | multipart：`chat_id` + `file`/`image`，可选 `name` / `original` |
| GET | `/v1/media/{media_id}` | 发出图片的二进制回读（收图未做） |

## chat_id

| 形式 | 含义 |
|------|------|
| `123456789` | 好友 QQ |
| `troop:123456789` 或 `g:123456789` | 群（troop uin） |

无文件传输助手。

## 文本与群 @

```bash
# 普通文本
curl -X POST http://127.0.0.1:8788/v1/messages/text \
  -H "Content-Type: application/json" \
  -d "{\"chat_id\":\"95019432\",\"text\":\"hi\"}"

# 群 @ 单人（QQ 号）/ @所有人
curl -X POST http://127.0.0.1:8788/v1/messages/text \
  -H "Content-Type: application/json" \
  -d "{\"chat_id\":\"troop:723765339\",\"text\":\"ping\",\"ats\":[\"95019432\"]}"

curl -X POST http://127.0.0.1:8788/v1/messages/text \
  -H "Content-Type: application/json" \
  -d "{\"chat_id\":\"troop:723765339\",\"text\":\"注意\",\"ats\":[\"notify@all\"]}"
```

Hook 使用 `IMsgUtilApi.createAtTextElement` + `createTextElement` 再 `sendMsg`。

## 发图

```bash
curl -X POST http://127.0.0.1:8788/v1/messages/image \
  -F chat_id=95019432 \
  -F file=@/path/to/test.jpg
```

响应：`{ok, msg_id, media_id, original, media:{kind,url,name}}`。  
`GET` 响应里的 `media.url`（如 `/v1/media/xxx`）可回读**本机发出**的图。收图 / `MEDIA_READY` 未实现。

## `/v1/events` payload（`type=message`）

| 字段 | 说明 |
|------|------|
| `chat_id` | 同上 |
| `msg_id` | TIM msgId |
| `text` | 纯文本 |
| `from_id` | 发送者 QQ（尽量解析） |
| `from_display` | 昵称/群名片 |
| `is_self` | 是否本账号 |
| `is_group` | 是否群 |
| `chat_title` | 会话名（若有） |
| `ts` | 消息秒级时间戳 |
| `ats` | 被 @ 的 QQ 列表；含 `notify@all` 表示 @所有人 |
| `at_me` | 是否 @ 了本机（含 @所有人） |
| `at_all` | 是否 @所有人 |

## 接口鉴权（App 内配置）

可开启 **API Token**。开启后除 `GET /v1/health` 外均需鉴权，否则 `401` + `unauthorized`。

```bash
curl -H "Authorization: Bearer <token>" http://127.0.0.1:8788/v1/me
curl -H "X-Api-Token: <token>" http://127.0.0.1:8788/v1/events
curl "http://127.0.0.1:8788/v1/events?after=0&token=<token>"
```

## 出站推送（App 内配置）

| 项 | 说明 |
|----|------|
| Redis host / port / password / stream key | 启用后，消息事件实时 `XADD`（默认 key `nexus:tim:events`） |
| Webhook URL | **仅告警**（Hook 断开、版本不匹配、Redis 失败、发送失败等），不推业务消息。企微机器人 URL 自动包装为 `msgtype=text` |

- `/v1/events` 仍为内存环形缓冲；生产消费请以 Redis Stream 为准
- Redis 字段：`type` / `ts` / `data`（与 events payload 同结构）

```bash
adb forward tcp:8788 tcp:8788
curl http://127.0.0.1:8788/v1/contacts
curl http://127.0.0.1:8788/v1/groups
curl http://127.0.0.1:8788/v1/chats/troop:723765339/members
curl "http://127.0.0.1:8788/v1/events?after=0"
```
