# Nexus App MVP 验收清单（G3）

**日期：** 2026-07-21  
**分支：** `nexus_app_architecture`  
**设备：** OnePlus 8T / LineageOS + Magisk（`nexus_audio_hook`）  
**关联：** [architecture plan](../2026-07-20-nexus-app-architecture.md) Task 13；[design spec](../../specs/2026-07-20-nexus-app-architecture-design.md)

> 全部通过后再进入 Task 14（停用 Go 守护）。  
> **Deferred：** 项 2 中「环境音软静音」见 plan《Deferred TODO：AI 接听静麦保 TX》，**不阻塞** Task 14。

## 验收表

| # | 项 | 结果 | 备注 |
|---|----|:----:|------|
| 1 | 默认电话授予后，AI 策略卡自动接听并进入 AI 会话 | ☐ | Nexus 接管 ON；卡策略 = AI |
| 1b | 关闭 Nexus 接管 → 系统电话可正常弹窗接听；再开启 → AI 卡可再自动接 | ☐ | 勿 force-stop 系统 Dialer；交回时禁用 Nexus ICS |
| 2a | 对方能听到 TTS（开场白 / 回复） | ☐ | |
| 2b | AI 模式环境音被软静音 | ☐ **Deferred** | 当前可失败；勿用系统静音 / 勿清 `TX_AIF1_CAP Mixer DEC*` |
| 3 | 通话中切「人工」后对方能听到本机 mic | ☐ | `CTRL_MUTE=0` |
| 4 | 挂断后存档目录有 `meta.json` + `transcript.txt` | ☐ | `…/Android/data/com.nexus.assistant/files/nexus_calls/calls/` |
| 5 | Webhook 收到通话摘要（含对方+本机；`notify.enabled`） | ☐ | 可选 |
| 6 | 短信转发（含发件人+收件人本机；`READ_SMS` + SMS_RECEIVED） | ☐ | 可选 |
| 7 | 开启接管时取消默认电话确认 → 回滚系统电话，不半残 | ☐ | |
| 8 | 非通话时无异常录音 / 无常驻抢麦 | ☐ | |
| 9 | 说一句后 AI 不连说（TTS 回声门控） | ☐ | log 可见 `TTS echo guard` |

## 操作速查

- **开启接管：** Settings → 开启 Nexus 接管 → 确认默认电话  
- **交回系统电话：** Settings → 关闭 Nexus 接管  
- **存档拉取：**  
  `adb shell run-as com.nexus.assistant ls files/nexus_calls/calls`  
  或外部：`/sdcard/Android/data/com.nexus.assistant/files/nexus_calls/calls/`
- **HAL 日志：** `adb logcat -s AI_Audio_Hook:I NexusBypass:I`

## 签名

| 角色 | 日期 | 结论 |
|------|------|------|
| 真机验收 | | ☐ 通过可进 Task 14　☐ 有阻塞项（列出） |
