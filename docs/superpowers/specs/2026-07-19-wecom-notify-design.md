# Nexus 企微通知（外部联系人 + 双卡短信）设计

**日期：** 2026-07-19  
**状态：** 待实现  
**通道 v1：** 企业微信 → 外部联系人（私人微信会话，接近私聊）  
**后续：** 群机器人 Webhook（配置预留，本版不做）

## 1. 目标

| 事件 | 推送内容 |
|------|----------|
| 通话挂断 | 时间、主叫号码、本机卡槽/号码、文字摘要、对话全文 |
| 新短信（双卡） | 时间、发件人、收件卡槽/本机号、正文 |

推送到：**已添加用户为外部联系人的企微身份**，用户在私人微信中于该联系人会话内收取。

## 2. 非目标（v1）

- 群机器人 Webhook 发送（仅预留配置字段）
- 通话实时逐句推送
- 语音 PCM / mix 附件
- 短信自动回复、企微侧交互指令
- 个人微信协议挂机

## 3. 决议摘要

| 项 | 决定 |
|----|------|
| 通道 | 企微外部联系人消息（用户企业已注册） |
| 形态 | 独立进程 `nexus_notify`，进 `nexus_runtime` |
| 通话 | 挂断落盘后推送（接现有 `finalizeCallArchive`） |
| 短信 | **双卡都转发** |
| 配置 | `/data/adb/nexus/config.json` → `notify`；密钥勿提交 git |

## 4. 架构

```text
ai_call
  └─ finalizeCallArchive → 写 call_*.txt
                         → 旁路通知 nexus_notify（推荐：同目录 .notify 旁路文件或 UDS/本地队列）

telephony SMS（双卡）
  └─ nexus_notify 轮询 content://sms/inbox（或等价 dumpsys）
                 → 去重后推送

nexus_notify
  └─ WeCom Client
        ├─ gettoken(corp_id, secret)
        └─ 发送应用消息 / 外部联系人消息 → external_userid
```

开机：`service.sh` 在 webui/callpolicy 之后拉起 `nexus_notify`；**不被** `restart_callstack.sh` 杀掉。

## 5. 企微侧前置（人工）

实现与联调前，管理员需完成：

1. 自建应用，记录 `corp_id`、`secret`、`agent_id`
2. 开通「客户联系」/外部联系人相关 API 权限（以企微当前文档为准：可发消息给外部联系人）
3. 用企微账号添加用户微信号为**外部联系人**，用户在微信侧确认
4. 通过 API 或管理后台取得该联系人的 `external_userid`，写入配置

> 若官方推荐走「客户联系」专用 secret 而非应用 secret，实现时以[企业微信开放文档](https://developer.work.weixin.qq.com/)为准，配置字段保持可扩展（`secret` / `contact_secret`）。

## 6. 配置模型

```json
{
  "notify": {
    "enabled": false,
    "channel": "wecom_external",
    "wecom": {
      "corp_id": "",
      "secret": "",
      "agent_id": 0,
      "external_userid": "",
      "webhook_url": ""
    },
    "sms": {
      "enabled": true,
      "poll_ms": 3000
    },
    "call": {
      "enabled": true,
      "max_transcript_chars": 3500
    }
  }
}
```

| 字段 | 说明 |
|------|------|
| `enabled` | 总开关；默认 `false`，配好凭证后再开 |
| `channel` | v1 固定 `wecom_external`；后续可 `wecom_webhook` |
| `webhook_url` | 预留群机器人，v1 忽略 |
| `sms.enabled` | 双卡短信转发 |
| `call.max_transcript_chars` | 全文超长截断，摘要始终带上 |

WebUI v1 可只暴露开关与「是否配置」提示；密钥可先手写 `config.json`（chmod 600）。

## 7. 消息模板

### 7.1 通话

```text
【通话】
时间：2026-07-19 19:09:00
主叫：177xxxxxxxx
本机：卡0 CMCC (+86139…)
策略：ai

摘要：
……

对话：
对方: …
助理: …
（若截断）…(全文已存手机 call_YYYYMMDD_HHMMSS.txt)
```

主叫：优先会话内 STT/registry 记录的 peer；本机：`sims[]` + slot（若可知）。

### 7.2 短信

```text
【短信】
时间：2026-07-19 19:10:00
发件人：106xxxxxxxxx
收件：卡1 CHN-UNICOM (+852…)
正文：
……
```

双卡：按短信所属 `sub_id` / slot 映射到 `sims[]` 展示。

## 8. 通话触发

在现有 `finalizeCallArchive` 写盘成功后：

1. 写旁路标记（例：`/data/vendor/ai_hook/calls/call_….txt.notify` 或把路径追加到 `notify.queue`）
2. `nexus_notify` 消费队列 → 读 txt → 组包 → 企微发送 → 标记完成

失败：记日志并有限重试（如 3 次指数退避）；不阻塞 `ai_call` 主路径。

## 9. 短信采集

- 轮询 `content://sms/inbox`（root/`content query`），间隔 `poll_ms`
- 去重键：`(_id)` 或 `(address + date + body)` 哈希；持久化已推 `last_sms_id` 于 `/data/adb/nexus/run/notify_sms_cursor`
- 仅转发**新到达**；启动时以当前最大 `_id` 为水位，避免历史轰炸
- 双卡：解析 `sub_id` / `sim_id` → slot

## 10. 错误与安全

- token 缓存至过期前刷新
- 凭证仅存本机 `config.json`，Redact 时不回显 secret
- 推送失败不影响通话/落盘
- 限频：企微 API 错误码退避，避免打爆配额

## 11. 测试

- 单元：消息格式化、截断、短信去重、slot 映射
- 真机：AI 通话挂断 → 私人微信外部联系人会话收到摘要+全文；两张卡各收一条短信均转发
- 回归：`notify.enabled=false` 时无请求；`restart_callstack` 不杀 `nexus_notify`

## 12. 实现顺序

1. `nexuscfg` 增加 `notify` + Default  
2. `daemon/nexus_notify`：WeCom token + 外部联系人发文本  
3. 接通话落盘旁路  
4. 双卡短信轮询  
5. `service.sh` 拉起；文档 / WebUI 最小开关  

## 13. 后续扩展

- `channel=wecom_webhook`：同一格式发群机器人  
- 可选：告警类（callpolicy 拒接失败、进程 down）
