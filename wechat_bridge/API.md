# WeChat Bridge LAN API

安装、原理与验收步骤见 **[使用指南](../docs/wechat-lan-api-guide.md)**。

Base: `http://<phone-ip>:8787`

## 接口鉴权（App 内配置）

App 可开启 **API Token**。开启后除 `GET /v1/health` 外均需鉴权，否则 `401` + `unauthorized`。

任选一种传法：

```bash
curl -H "Authorization: Bearer <token>" http://127.0.0.1:8787/v1/me
curl -H "X-Api-Token: <token>" http://127.0.0.1:8787/v1/chats
curl "http://127.0.0.1:8787/v1/events?after=0&token=<token>"
```

未开启鉴权时行为与之前相同（局域网内任意可访问）。

## 出站推送（App 内配置）

Bridge App 可配置：

| 项 | 说明 |
|----|------|
| Redis host / port / password / stream key | 启用后，消息事件实时 `XADD` 到 **Redis Stream**（默认 key `nexus:wechat:events`） |
| Webhook URL | **仅告警**（Hook 断开、版本不匹配、Redis 失败、发送失败等），不推业务消息。通用 JSON；若 URL 为企微群机器人 `qyapi.weixin.qq.com`，自动包装为 `msgtype=text` |

- `/v1/events` **仍保留**，但是内存环形缓冲（约 200 条），**不做本地持久化**；生产消费请以 Redis Stream 为准
- 局域网示例：`docker run -d --name nexus-redis -p 6379:6379 redis:7-alpine`

### Redis 存储说明（Stream）

Bridge **只写不读**：把事件追加进 Stream，**不包含**消费者逻辑。业务服务需自行 `XREAD` / `XREADGROUP`。

与普通 Redis KV（`SET`/`GET`）的区别：

| | KV | Stream（本项目使用） |
|--|----|----------------------|
| 模型 | 一个 key 对应当前值，写入常覆盖 | 一个 key 下挂可追加的消息日志 |
| 适合 | 配置、缓存、最新状态 | 消息流、异步消费、多消费者 |
| Bridge 行为 | 不用 | 每条事件 `XADD` 一条，旧条目仍保留 |

**条目字段**（`XADD` 的 field-value）：

| field | 含义 |
|-------|------|
| `type` | 事件类型：`message`（收/发消息）、`media_ready`（媒体就绪） |
| `ts` | Bridge 写入时的毫秒时间戳（字符串） |
| `data` | 事件 JSON 字符串（与 `/v1/events` 的 payload 同结构，见下文「区分是谁发来的」） |

**数据流**：

```text
微信 → Hook → Bridge → Redis XADD → Stream key（默认 nexus:wechat:events）
                              ↑
                     你的服务 XREAD / 消费组读取
```

**消费示例**：

```bash
# 从尾部阻塞读（演示用；生产建议消费组）
redis-cli -h <redis-host> XREAD COUNT 10 BLOCK 5000 STREAMS nexus:wechat:events $

# 消费组（可断点续读、多 worker）
redis-cli XGROUP CREATE nexus:wechat:events wechat_workers 0 MKSTREAM
redis-cli XREADGROUP GROUP wechat_workers worker1 COUNT 10 BLOCK 5000 \
  STREAMS nexus:wechat:events >
# 处理成功后 ACK
redis-cli XACK nexus:wechat:events wechat_workers <entry-id>
```

**无人消费时**：

- Bridge 仍正常 `XADD`，收发微信不受影响
- 条目会一直积压在 Stream 中（当前未设置 `MAXLEN`），长期可能占满 Redis 内存
- 需要业务侧消费，或自行用 `XTRIM` / 配置 `MAXLEN` 做裁剪；暂时不用时可在 App 关闭 Redis

**失败行为**：`XADD` 失败时 App 状态栏会显示 Redis 错误；若配置了 Webhook，会额外告警 `redis_publish_failed`。Webhook **不会**代替 Redis 投递业务消息。

## 发送目标（可配置）

`POST /v1/messages/text` 的 `chat_id` 就是发送目标：

| chat_id | 含义 |
|---------|------|
| `filehelper` | 文件传输助手 |
| `wxid_xxx` | 私聊对方微信 ID |
| `123456@chatroom` | 群聊 |

先查会话再发：

```bash
curl http://127.0.0.1:8787/v1/chats
curl -X POST http://127.0.0.1:8787/v1/messages/text \
  -H "Content-Type: application/json" \
  -d "{\"chat_id\":\"filehelper\",\"text\":\"hello\"}"
```

发送前校验（避免对不存在/非好友 wxid 调起发送，从而在会话列表留下「还不是你的朋友」）：

| error | 含义 |
|-------|------|
| `unknown_chat` | 通讯录/群不存在 |
| `not_friend` | 有记录但非好友（或已删除） |

`filehelper` 始终允许；私聊需 `rcontact` 好友位；群需已在群/会话中。HTTP 返回 `400`。

群真 @（`ats` = 成员 wxid；`notify@all` = @所有人）：

```json
{"chat_id":"123@chatroom","text":"hi","ats":["wxid_a"]}
{"chat_id":"123@chatroom","text":"注意","ats":["notify@all"]}
```

Hook 写入微信 `atuserlist`（`y11.s1` Map / `AtSomeOneHelper`），并在正文前补 `@显示名` + U+2005（显示名来自通讯录备注/昵称）。对端应收到真正 @ 提醒；自发事件里也会带上 `ats`。若 `text` 已含 `@显示名\u2005` 则不再重复前缀。

## 区分是谁发来的

`GET /v1/events` 每条 `message` payload：

| 字段 | 含义 |
|------|------|
| `chat_id` | 会话（私聊=对方 wxid，群=chatroom） |
| `chat_title` | 会话显示名 |
| `from_id` | 发送者 wxid（自己发出时为本机 wxid） |
| `from_display` | 发送者备注/昵称 |
| `is_self` | 是否自己发出 |
| `is_group` | 是否群聊 |
| `text` | 文本 |
| `ats` | 被 @ 的 wxid 列表；含 `notify@all` 表示 @所有人 |
| `at_me` | 是否 @ 了本机账号（含 @所有人） |
| `at_all` | 是否 @所有人 |
| `ts` | Unix 秒 |

群 @ 解析自微信 `msgSource` / `lvbuffer` 里的 `<atuserlist>`，不是靠正文昵称猜测。

轮询示例：

```bash
curl "http://127.0.0.1:8787/v1/events?after=0"
# 下次用返回的 cursor 作为 after
```

## 图片 / 文件

上传发送（multipart）：

```bash
curl -X POST http://127.0.0.1:8787/v1/messages/image ^
  -F "chat_id=filehelper" ^
  -F "file=@C:\tmp\tiny.png"
REM 默认 original=true（微信原图 / compressType=1）。压缩发送：
curl -X POST http://127.0.0.1:8787/v1/messages/image ^
  -F "chat_id=filehelper" ^
  -F "original=false" ^
  -F "file=@C:\tmp\tiny.png"
curl -X POST http://127.0.0.1:8787/v1/messages/file ^
  -F "chat_id=filehelper" ^
  -F "file=@C:\tmp\a.pdf" ^
  -F "name=a.pdf"
```

| 字段 | 默认 | 说明 |
|------|------|------|
| `original` | `true` | 发图是否走微信「原图」；`false`/`0`/`compress` 为压缩图 |

说明：原图走微信官方 `compressType=1`，尽量少压；微信 CDN 仍可能做容器/元数据处理，不保证字节级完全无损。

限制：单文件 ≤ 25MB，超限返回 `file_too_large`。

下载：`GET /v1/media/{media_id}`  
事件 payload 里若有 `media_id`，会附带：

```json
"media": {"kind":"image","url":"/v1/media/xxx","name":"a.png"}
```

> 真机发图：Bridge 暂存到 `/data/local/tmp/nexus_wechat/out`（并附带 `data_b64` 兜底），Hook 调 `kl5.s5.rj(talker, path)`。  
> 真机收图：Hook 解析 `imgPath=THUMBNAIL_DIRPATH://th_*`（`th_*` 可能是 UUID 指针）→ `image2/.ref/d/{uuid}`，导出到 `/data/local/tmp/nexus_wechat/in`，经 `MEDIA_READY` 后 `GET /v1/media/{id}`。  
> 真机发文件（非图片）暂未实现。

## 其它

- `GET /v1/health` — bridge/hook/登录态
- `GET /v1/me` — 本机 `user_id` / `nick`
- `GET /v1/chats` — 最近会话（可作发送目标列表）
- `GET /v1/contacts` — **全部好友**（`rcontact` 好友位；`user_id` + `display`；上限约 2000）
- `GET /v1/groups` — **全部群**（`chatroom` 表；`chat_id` + `title`；上限约 500）
- `GET /v1/chats/{chat_id}/members` — 群成员（`chatroom.memberlist`；最近会话或 `/v1/groups` 里出现过的群均可）

```bash
curl http://127.0.0.1:8787/v1/contacts
curl http://127.0.0.1:8787/v1/groups
```
