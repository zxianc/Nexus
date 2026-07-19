# Nexus 企微通知（Webhook + 双卡短信）设计

**日期：** 2026-07-19  
**状态：** 已实现（v1）  
**通道 v1（现行）：** 企业微信 → **内部群**群机器人 Webhook（无企业可信 IP）  
**备用：** 外部联系人 / 应用消息（需可信 IP 或固定出口；家用动态 IP 不推荐）

## 1. 目标

| 事件 | 推送内容 |
|------|----------|
| 通话挂断 | 时间、主叫号码、本机卡槽/号码、策略、文字摘要、对话全文 |
| 新短信（双卡） | 时间、发件人、收件卡槽/本机号、正文 |

推送到：企微**内部群**机器人消息（成员在企微 App 收取）。含个人微信的外部群不能加群机器人。

## 2. 非目标（v1）

- 通话实时逐句推送
- 语音 PCM / mix 附件
- 短信自动回复、企微侧交互指令
- 个人微信协议 / 外部群机器人（平台不支持）

## 3. 决议摘要

| 项 | 决定 |
|----|------|
| 通道 | **优先 `wecom_webhook`**；`wecom_external` / `touser` 保留 |
| 形态 | **独立进程 `nexus_notify`**（不并入 `nexus_callpolicy`） |
| 通话 | 挂断落盘后 `.notify` 旁路推送 |
| 短信 | **双卡都转发**；`sub_id`→slot 用 `dumpsys isub` 映射 |
| 配置 | `/data/adb/nexus/config.json` → `notify`；密钥勿提交 git |

## 4. 架构

```text
ai_call
  └─ finalizeCallArchive → 写 call_*.txt（时间/主叫/本机/策略/摘要/对话）
                         → 写 call_*.txt.notify

telephony SMS（双卡）
  └─ nexus_notify 轮询 content://sms/inbox
                 → sub_id → slot（isub 映射）→ 去重后推送

nexus_notify
  └─ WeCom Client（Android：自定义 DNS + 系统 CA）
        ├─ channel=wecom_webhook → POST webhook_url
        └─ channel=wecom_external → gettoken + 应用/外部联系人 API
```

开机：`service.sh` 拉起；**不被** `restart_callstack.sh` 杀掉。

## 5. 企微侧前置（Webhook）

1. 建**仅企微成员**的内部群（不要拉个人微信）
2. 群设置 → 添加群机器人 → 复制 Webhook URL
3. 写入 `notify.wecom.webhook_url`，`channel=wecom_webhook`，`enabled=true`

（备用外部联系人路径仍需自建应用、客户联系权限、`external_userid` + `sender`，且常需企业可信 IP。）

## 6. 配置模型

```json
{
  "notify": {
    "enabled": false,
    "channel": "wecom_webhook",
    "wecom": {
      "corp_id": "",
      "secret": "",
      "agent_id": 0,
      "external_userid": "",
      "sender": "",
      "touser": "",
      "webhook_url": ""
    },
    "sms": { "enabled": true, "poll_ms": 3000 },
    "call": { "enabled": true, "max_transcript_chars": 3500 }
  }
}
```

| 字段 | 说明 |
|------|------|
| `enabled` | 总开关；默认 `false` |
| `channel` | 推荐 `wecom_webhook`；可选 `wecom_external` |
| `webhook_url` | 群机器人完整 URL |
| `sms` / `call` | 分开关与截断 |

## 7. 消息模板

### 7.1 通话

```text
【通话】
时间：…
主叫：…
本机：CMCC (+86139…)
策略：ai

摘要：
…

对话：
对方: …
助理: …
```

主叫：通话中 `telephony.registry` 的 `mCallIncomingNumber`；本机：`sims[]` + slot。

### 7.2 短信

```text
【短信】
时间：…
发件人：…
收件：CMCC (+86139…)
正文：
…
```

## 8. 通话触发

`finalizeCallArchive` 写盘成功后写 `*.txt.notify`；`nexus_notify` 消费 → Webhook → 删标记。失败重试，不阻塞通话。

## 9. 短信采集

- 轮询 `content://sms/inbox`，水位 `/data/adb/nexus/run/notify_sms_cursor`
- 启动以当前最大 `_id` 为水位，避免历史轰炸
- **映射：** `dumpsys isub` 中 `Logical SIM slot N: subId=M`（例：slot0↔subId2）。**禁止**假设 `sub_id - 1 == slot`

## 10. 错误与安全

- Webhook / token 凭证仅存本机 `config.json`，Redact 不回显
- CGO=0：固定公共 DNS + 加载 Android cacerts
- 推送失败不影响通话/落盘

## 11. 测试

- 单元：格式化、截断、isub/siminfo 映射
- 真机：挂断 → 内部群收到摘要；两卡短信收件卡正确
- 回归：`enabled=false` 无请求；`restart_callstack` 不杀 `nexus_notify`

## 12. 后续扩展

- WebUI 最小开关与「Webhook 已配置」提示
- 可选：应用消息通道的云主机 IP 中转
