# TIM Bridge LAN API

Base: `http://<phone-ip>:8788`  
Hook IPC: `127.0.0.1:18788` (internal)

## Endpoints

| Method | Path | Notes |
|--------|------|-------|
| GET | `/v1/health` | `hook`, `tim_version`, `logged_in`, `user_id` |
| GET | `/v1/me` | uin / nick |
| GET | `/v1/events?after=` | memory ring（约 200 条） |
| POST | `/v1/messages/text` | JSON `chat_id` + `text` |

## chat_id

| 形式 | 含义 |
|------|------|
| `123456789` | 好友 QQ |
| `troop:123456789` 或 `g:123456789` | 群（troop uin） |

无文件传输助手。

## `/v1/events` payload（`type=message`）

| 字段 | 说明 |
|------|------|
| `chat_id` | 同上 |
| `msg_id` | TIM msgId |
| `text` | 纯文本（MVP） |
| `from_id` | 发送者 QQ（尽量解析） |
| `from_display` | 昵称/群名片 |
| `is_self` | 是否本账号 |
| `is_group` | 是否群 |
| `chat_title` | 会话名（若有） |
| `ts` | 消息秒级时间戳 |

```bash
adb forward tcp:8788 tcp:8788
curl "http://127.0.0.1:8788/v1/events?after=0"

curl -X POST http://127.0.0.1:8788/v1/messages/text \
  -H "Content-Type: application/json" \
  -d "{\"chat_id\":\"<好友QQ>\",\"text\":\"hi from tim bridge\"}"
```
