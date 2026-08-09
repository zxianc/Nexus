# WeChat Bridge LAN API

Base: `http://<phone-ip>:8787`

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
- `GET /v1/chats/{chat_id}/members` — 群成员（HELLO 里带了才有）
