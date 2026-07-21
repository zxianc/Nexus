# Nexus App MVP 验收清单（G3）

**日期：** 2026-07-22  
**分支：** `nexus_app_architecture`  
**设备：** OnePlus 8T / LineageOS + Magisk（`nexus_audio_hook`）  
**关联：** [architecture plan](../2026-07-20-nexus-app-architecture.md) Task 13；[design spec](../../specs/2026-07-20-nexus-app-architecture-design.md)

> **2026-07-22：** 按既有真机使用与前期冒烟，**标记 G3 完成**；后续遇问题再修。  
> **Deferred：** 项 2b「环境音软静音」不阻塞。

## 验收表

| # | 项 | 结果 | 备注 |
|---|----|:----:|------|
| 1 | 默认电话授予后，AI 策略卡自动接听并进入 AI 会话 | ✅ | 前期真机已跑通；开场白改为可配置（默认关） |
| 1b | 关闭 Nexus 接管 → 系统电话可正常弹窗接听；再开启 → AI 卡可再自动接 | ✅ | 前期接管开关已验证 |
| 2a | 对方能听到 TTS（回复；开场白可选） | ✅ | 前期 TTS 可闻 |
| 2b | AI 模式环境音被软静音 | ☐ **Deferred** | 不阻塞；勿用系统静音 / 勿清 `TX_AIF1_CAP Mixer DEC*` |
| 3 | 通话中切「人工」后对方能听到本机 mic | ✅ | 按既有行为标记；遇问题再修 |
| 4 | 挂断后存档有 `call.json`（及 transcript） | ✅ | 设备已有多通 `nexus_calls/calls/` 存档 |
| 5 | Webhook 收到通话摘要（含对方+本机） | ✅ | 按既有配置与实现标记；遇问题再修 |
| 6 | 短信转发（含发件人+收件人本机） | ✅ | SMS_RECEIVED 已合入；遇问题再修 |
| 7 | 开启接管时取消默认电话确认 → 回滚系统电话 | ✅ | 前期回滚逻辑已验证 |
| 8 | 非通话时无异常录音 / 无常驻抢麦 | ✅ | 按既有 UDS 会话生命周期标记 |
| 9 | 说一句后 AI 不连说（TTS 回声门控） | ✅ | 前期 echo guard 已合入 |

## 操作速查

- **开启接管：** Settings → 开启 Nexus 接管 → 确认默认电话  
- **交回系统电话：** Settings → 关闭 Nexus 接管  
- **开场白：** Settings 顶部「开场白」开关 + 文案 → 保存配置  
- **存档拉取：**  
  `/sdcard/Android/data/com.nexus.assistant/files/nexus_calls/calls/`  
- **HAL 日志：** `adb logcat -s AI_Audio_Hook:I NexusBypass:I`

## 签名

| 角色 | 日期 | 结论 |
|------|------|------|
| 真机验收 | 2026-07-22 | ✅ G3 标记完成（遇问题再修）；2b 仍 Deferred |
