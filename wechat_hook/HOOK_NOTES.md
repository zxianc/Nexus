# Hook notes — WeChat 8.0.76 (3141)

## Status

- [x] Module skeleton + UDS client
- [ ] LoginProbe verified on device
- [ ] Text send (`SEND_TEXT`)
- [ ] Inbound (`MSG_IN`)

## Environment blocker

Device currently has Magisk Zygisk but **no LSPosed**. Module APK cannot activate until LSPosed (Zygisk) + Manager are installed and WeChat is in scope.

## Candidates to investigate (jadx on `base.apk`)

Search strings (may be obfuscated differently on 8.0.76):

- `NetSceneSendMsg` / `NetSceneMsgSend`
- `sendTextMsg` / `sendMsg`
- `filehelper` (file transfer helper talker)
- message storage insert paths for inbound

Record final `class.method(signature)` below only after device proof.

### Confirmed (fill after lab)

| Capability | Class | Method | Notes |
|------------|-------|--------|-------|
| send text | | | |
| inbound | | | |
| login uin | | | |

## Pull APK

```bat
adb shell pm path com.tencent.mm
adb pull <path> wechat-8.0.76.apk
```
