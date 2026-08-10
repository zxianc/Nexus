# TIM 4.1.0 / 4050 Hook notes

## IPC

- Bridge ↔ Hook: TCP `127.0.0.1:18788` (abstract UDS SELinux-denied)

## Login

- `sp_login_auto` keys = current uin digits (e.g. `3086243780`)
- Account prefs: `/data/data/com.tencent.tim/shared_prefs/<uin>.xml`

## Send text (candidate)

Class: `com.tencent.mobileqq.activity.ChatActivityFacade`

| Method | Signature (abbrev) | Notes |
|--------|--------------------|-------|
| `H0` | `(QQAppInterface, Context, SessionInfo, String)V` | likely plain text |
| `I0` | `(…, SessionInfo, String, ArrayList)V` | text + @ list |
| `G` / `c0` / `U` | `(QQAppInterface, SessionInfo, String)…` | alternates |

`SessionInfo` extends / related to `com.tencent.mobileqq.activity.aio.q` with public fields including:

- `d` : `int` — try as **uinType** (`0` friend, `1` troop)
- `e` : `String` — try as **peer uin**

App runtime: `mqq.app.MobileQQ` / `com.tencent.mobileqq.app.QQAppInterface`.

## chat_id (MVP)

- Friend: QQ number digits, e.g. `123456`
- Group: `troop:123456789` (troop uin)
