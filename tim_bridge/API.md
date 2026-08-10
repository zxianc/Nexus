# TIM Bridge LAN API

Base: `http://<phone-ip>:8788`  
Hook IPC: `127.0.0.1:18788` (internal)

## Endpoints

| Method | Path | Notes |
|--------|------|-------|
| GET | `/v1/health` | `hook`, `tim_version`, `logged_in`, `user_id` |
| GET | `/v1/me` | uin / nick |
| GET | `/v1/events?after=` | memory ring |
| POST | `/v1/messages/text` | JSON `chat_id` + `text` |

## chat_id

| 形式 | 含义 |
|------|------|
| `123456789` | 好友 QQ |
| `troop:123456789` 或 `g:123456789` | 群（troop uin） |

无文件传输助手。

```bash
adb forward tcp:8788 tcp:8788
curl -X POST http://127.0.0.1:8788/v1/messages/text \
  -H "Content-Type: application/json" \
  -d "{\"chat_id\":\"<好友QQ>\",\"text\":\"hi from tim bridge\"}"
```
