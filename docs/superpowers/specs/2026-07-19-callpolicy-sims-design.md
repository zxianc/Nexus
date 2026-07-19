# Nexus 双卡来电策略（callpolicy）设计

**日期：** 2026-07-19  
**状态：** 已实现（v1；OnePlus 8T / Lineage 已标定拒接与接听路径）  
**范围：** `nexus_runtime` 内 `nexus_callpolicy` + WebUI 按卡配置；默认双卡「人工」

## 1. 目标

- 识别来电所属 **物理卡槽（主）** 与 **本机号码（展示/校验）**
- 按 WebUI 可配策略处理：`human` / `ai` / `reject`
- **默认两卡均为 `human`**（响铃，等人工）
- 策略不进 HAL；不写死槽位行为

## 2. 非目标（v1）

- 按主叫号码白名单/黑名单细分（后续）
- 正式 InCallService APK / 默认拨号应用替换
- 无障碍模拟点击
- 改 audio HAL

## 3. 架构

```text
来电 RINGING
    → nexus_callpolicy (root, nexus_runtime 开机拉起)
         ├── 解析 slot (+ 可选本机号)
         ├── 查 config.json sims[]
         ├── human  → no-op
         ├── reject → Adapter.Reject()
         └── ai     → Adapter.Answer() → 现有 HAL / ai_call 链路
```

| 项 | 路径/约定 |
|----|-----------|
| 二进制 | `/data/adb/modules/nexus_runtime/bin/nexus_callpolicy` |
| 启动 | `service.sh`；**不被** `restart_callstack.sh` pkill |
| 配置 | `/data/adb/nexus/config.json` |
| 日志 | `/data/vendor/ai_hook/nexus_callpolicy.log` |
| WebUI | 同 `nexus_webui` 页内「双卡策略」 |

开机顺序：`nexus_engine` → `ai_call` → `nexus_webui` → `nexus_callpolicy`。

## 4. 配置模型

```json
{
  "sims": [
    { "slot": 0, "label": "CMCC", "carrier": "CMCC", "number": "+86139…", "policy": "human" },
    { "slot": 1, "label": "CHN-UNICOM", "carrier": "CHN-UNICOM", "number": "+852…", "policy": "human" }
  ]
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `slot` | int | 0 或 1，主匹配键（= `Phone Id`） |
| `label` / `carrier` / `number` | string | **系统只读**（`content://telephony/siminfo`），WebUI 不可改 |
| `policy` | string | `human` \| `ai` \| `reject`（唯一可配项） |

缺省 / 非法 policy → 当作 `human`。  
`nexuscfg` 扩展 `Sims []Sim`；`Default()` 填入两槽 `human`。

保存策略后：callpolicy **热读** json（轮询 mtime 或每次来电前 Load），不必重启 callpolicy。

## 5. WebUI

- 区块「双卡策略」：slot0/1 各一行；**运营商 / 号码只读**（`content://telephony/siminfo`）；仅 policy 可改
- 「刷新卡信息」：`POST /api/sims/refresh` → 写入 label/carrier/number，保留 policy
- 文案：选 `ai` 时提示将自动接听

API：`GET/PUT /api/config`（PUT 只改 policy）；`POST /api/sims/refresh`。

## 6. 识别与动作（适配层）

### 检测

v1 在 OnePlus 8T / Lineage 上标定：

1. **主路径：** 轮询 `dumpsys telephony.registry`，`Phone Id=N` + `mCallState=1`（RINGING）+ `mCallIncomingNumber`
2. **勿**把 `dumpsys telecom` 历史里的 `Enter RINGING` / 段标题 `Ringing calls:` 当来电（会误报）
3. 卡展示信息：`content query --uri content://telephony/siminfo`（WebUI 刷新）

### 动作

```text
Answer(ctx, slot) error   // 校验 mCallState→OFFHOOK(2)
Reject(ctx, slot) error   // 校验 mCallState≠RINGING(1)
```

| 动作 | 真机优先命令 |
|------|----------------|
| Answer | `KEYCODE_HEADSETHOOK` → `telecom 36`（acceptRingingCall）→ `KEYCODE_CALL` |
| Reject | `telecom 35`（endCall）→ `KEYCODE_ENDCALL` |

启动时 `appops set com.android.shell ANSWER_PHONE_CALLS allow`（否则 accept 静默失败）。**禁止**把 `service call` Parcel 退出码当成成功。

**幂等：** 同一通 RINGING 只执行一次策略（按 call id 或 peer+slot+时间窗去重）。

## 7. 与 ai_call 边界

- `ai`：仅负责接通；STT/LLM/TTS 仍由现有进程在 ACTIVE 后自然工作
- `human` / `reject`：callpolicy 不通知 ai_call；无 DL 流或流无会话即可
- `restart_callstack.sh` **不得** `pkill nexus_callpolicy`

## 8. 生命周期与错误

- 探测失败：打日志，当 `human`（安全默认）
- Answer/Reject 失败：打日志，不重试轰炸（同通话最多 1～2 次）
- 配置损坏：回退 Default sims

## 9. 测试

- 单元：policy 解析、缺省 human、非法值回退、去重
- 真机：slot0/1 各测 human / reject / ai；WebUI 改策略立即生效
- 回归：改 sims 不杀 webui；callstack 重启后 callpolicy 仍在

## 10. 实现顺序

1. `nexuscfg` 增加 `sims` + Default  
2. WebUI 双卡表单  
3. `nexus_callpolicy` 骨架（读配置、日志、Watch stub）  
4. Lineage 适配：探测 RINGING + slot；Answer/Reject  
5. `service.sh` 拉起；文档  

## 11. 决议摘要

| 项 | 决定 |
|----|------|
| 识别 | 卡槽为主 + 号码展示/校验 |
| 策略 | WebUI 可配；默认双卡 `human` |
| 形态 | `nexus_callpolicy` 在 `nexus_runtime` |
| 动作 | root 适配层；不进 HAL |
