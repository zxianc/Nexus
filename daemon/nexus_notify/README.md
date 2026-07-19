# nexus_notify

企微通知守护进程：通话挂断摘要 + 双卡短信转发。

**推荐通道：** 企微**内部群**群机器人 Webhook（`channel=wecom_webhook`）。不依赖企业可信 IP；消息在企微 App 内群可见。含个人微信的外部群**不能**加群机器人。

## 配置

`/data/adb/nexus/config.json` → `notify`（密钥 / Webhook URL 勿提交 git）：

```json
{
  "notify": {
    "enabled": true,
    "channel": "wecom_webhook",
    "wecom": {
      "webhook_url": "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=...",
      "corp_id": "",
      "secret": "",
      "agent_id": 0,
      "external_userid": "",
      "sender": "",
      "touser": ""
    },
    "sms": { "enabled": true, "poll_ms": 3000 },
    "call": { "enabled": true, "max_transcript_chars": 3500 }
  }
}
```

| 字段 | 说明 |
|------|------|
| `webhook_url` + `channel=wecom_webhook` | 群机器人（推荐） |
| `external_userid` + `sender` | 客户联系模板 → 外部联系人（需应用 API + 常要可信 IP） |
| `touser` + `agent_id` | 应用消息发给企微成员（需可信 IP） |

## 通话触发

1. `ai_call` 挂断后写 `/data/vendor/ai_hook/calls/call_*.txt`（含时间、主叫、本机、策略、摘要、对话）
2. 旁路空文件 `call_*.txt.notify`
3. 本进程扫到标记 → 读 txt → POST Webhook → 删标记

主叫/卡槽来自通话中 `telephony.registry`；本机展示来自 `config.json` → `sims[]`。

## 短信

- 轮询 `content://sms/inbox`；水位 `/data/adb/nexus/run/notify_sms_cursor`
- **收件卡槽**用 `dumpsys isub` 的 `Logical SIM slot N: subId=M` 映射（勿假设 `sub_id-1=slot`；本机实测 slot0↔subId2）

## Android 注意（CGO=0）

与 `ai_call` 相同：自定义 DNS（`223.5.5.5` / `8.8.8.8`）+ 加载 `/system/etc/security/cacerts`（及 apex conscrypt），否则 HTTPS 会 DNS/证书失败。

## 构建 / 部署

```bat
build.bat
copy nexus_notify_arm64 ..\..\magisk_modules\nexus_runtime\bin\nexus_notify
```

开机由 `service.sh` 拉起；**不被** `restart_callstack.sh` 杀掉。日志：`/data/vendor/ai_hook/nexus_notify.log`。

设计：`docs/superpowers/specs/2026-07-19-wecom-notify-design.md`
