# Hook notes — WeChat 8.0.76 (3141)

## Status

- [x] Module skeleton + UDS client
- [x] LSPosed Zygisk installed (JingMatrix v1.11.0 / 7209)
- [x] Module scope `com.tencent.mm` enabled
- [x] LoginProbe (WCDB `userinfo` via captured DB handle)
- [x] Text send via `qh3.t1` → `kl5.s5.yj` (needs Tinker ClassLoader)
- [x] Inbound via WCDB `message` insert → `MSG_IN`
- [x] Device E2E send proof (`POST /v1/messages/text` → filehelper ok)
- [x] Device E2E recv/events + `logged_in=true` (Tinker ClassLoader rehook)
- [x] Image send via `kl5.s5.rj(ctx, talker, path, …)` (do **not** call `qj` with path)
- [x] Image export: `THUMBNAIL_DIRPATH://th_*` → UUID pointer → `image2/.ref/d/{uuid}` JPEG
- [x] Group @ recv: `lvbuffer`/`msgSource` `<atuserlist>` → events `ats` / `at_me` / `at_all`
- [x] Group @ send: `y11.s1` + `r1.h={atuserlist:CDATA}`；真机验证单人 @ 与 `notify@all`

## Critical: Tinker ClassLoader

Hooks/calls MUST use WeChat's `DelegateLastClassLoader` (tinker_classN), not raw
`lpparam.classLoader`. Otherwise ServiceManager/EventCenter look uninitialized
and WCDB insert hooks never fire.

## Reverse findings on 8.0.76

- Observatory 8.0.75 classes `w11.r0` / `w11.s1` / `w11.r1` **absent**.
- Stable path: `com.tencent.mm.autogen.events.SendMsgEvent` present in dex.
- Inbound: hook `com.tencent.wcdb.database.SQLiteDatabase.insert*` when table=`message`.
- Login: after any WCDB insert, read `userinfo` id=2 (wxid), id=4/5 (nick).

### Confirmed (8.0.76 dex)

| Capability | Class | Method | Notes |
|------------|-------|--------|-------|
| send text (preferred) | `qh3.t1` → `kl5.s5` | `a()` / `yj(String,String,int,int)` | was `tg3.t1`/`dk5.s5.fj` on 8.0.75 |
| send text + group @ | `y11.s1` → `r1.h` / `kl5.s5.zj` | `h={atuserlist:CDATA}` + `i1.d` map | Non-PPC ignores `zj` p24; need Map/`AtSomeOneHelper`; content `@nick\u2005` (U+2005) |
| send text (NetScene) | `y11.r0` + `com.tencent.mm.modelbase.z2` | ctor `(S,S,I,I,J)` + `b/c` | was `w11.r0` |
| send text (event) | `SendMsgEvent` | publish `e()` | needs EventCenter init (`is.g`) |
| send image | `qh3.t1` → `kl5.s5` | `rj(Context,talker,path,int,S,S,e01.h7)` | `qj(text,talker)` is caption-only |
| msg type | `e01.e2` | `C(talker)` | fallback `1` |
| inbound | WCDB `SQLiteDatabase` | `insert*` + query capture | table `message`, type=1 text / type=3 image |
| image bytes | `MicroMsg/<hash>/image2/.ref/d/` | UUID from `th_*` file content | `th_*` may be 36-byte pointer |
| login uin | same DB handle | `userinfo` id=2/4/5 | |

## Pull APK

```bat
adb shell pm path com.tencent.mm
adb pull <path> wechat-8.0.76.apk
```
