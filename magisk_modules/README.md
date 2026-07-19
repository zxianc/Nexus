# Magisk 业务模块：`nexus_runtime` + `nexus_models`

与 **`nexus_audio_hook`**（HAL）解耦。程序与模型分两个 zip，版本可独立升级。

| 模块 | zip | 内容 |
|------|-----|------|
| `nexus_runtime` | `nexus_runtime.zip` | `ai_call`、`nexus_engine`、`libonnxruntime.so`、开机自启、`/data/adb/nexus` 配置 |
| `nexus_models` | `nexus_models.zip` | `models/sense-voice`、`models/vits-zh-ll` |

## 设备路径

```text
/data/adb/modules/nexus_runtime/bin/{ai_call,nexus_engine,nexus_webui,nexus_callpolicy,nexus_notify}
/data/adb/modules/nexus_runtime/lib/libonnxruntime.so
/data/adb/modules/nexus_runtime/scripts/restart_callstack.sh
/data/adb/modules/nexus_models/models/sense-voice/
/data/adb/modules/nexus_models/models/vits-zh-ll/
/data/adb/nexus/config.json             # 配置真源（含 API Key，chmod 600）
/data/adb/nexus/env.sh                 # 可选覆盖（调试）
/data/adb/nexus/secrets/deepseek.key   # 遗留；可迁移进 config.json
/data/adb/nexus/run/engine.sock
/data/vendor/ai_hook/calls/            # 通话文本存档
```

## 本机 WebUI

开机后在手机 Chrome 打开：**http://127.0.0.1:8787**（仅本机）。

- 改 LLM / Key / 打断 / 路径等 → 保存后自动重启 `ai_call`（必要时 `nexus_engine`）
- **不**杀 `nexus_webui` / `nexus_callpolicy` / `nexus_notify`（策略与通知热读 config）
- WebUI 含「双卡策略」：`human` / `ai` / `reject`（默认双卡人工）
- 设计见 `docs/superpowers/specs/2026-07-19-nexus-webui-design.md`、`2026-07-19-callpolicy-sims-design.md`、`2026-07-19-wecom-notify-design.md`

打包前：

```bat
copy daemon\nexus_webui\nexus_webui_arm64 magisk_modules\nexus_runtime\bin\nexus_webui
copy daemon\nexus_callpolicy\nexus_callpolicy_arm64 magisk_modules\nexus_runtime\bin\nexus_callpolicy
copy daemon\nexus_notify\nexus_notify_arm64 magisk_modules\nexus_runtime\bin\nexus_notify
```

## 打包前填充资产（勿提交大文件）

### runtime

```bat
copy daemon\ai_call\ai_call_arm64 magisk_modules\nexus_runtime\bin\ai_call
REM nexus_engine + libonnxruntime.so → bin\ / lib\
cd magisk_modules\nexus_runtime
build.bat
```

### models

从真机调试目录或本机模型缓存拷入：

```bat
xcopy /E /I /Y ...\sense-voice\* magisk_modules\nexus_models\models\sense-voice\
xcopy /E /I /Y ...\vits-zh-ll\*   magisk_modules\nexus_models\models\vits-zh-ll\
cd magisk_modules\nexus_models
build.bat
```

## 安装顺序

1. `nexus_audio_hook`（HAL，需重启）
2. `nexus_models`
3. `nexus_runtime`（可再重启，或手动 `sh service.sh`）

写入 Key：

```bash
adb shell 'su -c "printf \"%s\" \"sk-...\" >/data/adb/nexus/secrets/deepseek.key; chmod 600 /data/adb/nexus/secrets/deepseek.key"'
```

日志：`/data/vendor/ai_hook/nexus_runtime.log`、`ai_call.log`、`nexus_engine.log`、`nexus_webui.log`、`nexus_callpolicy.log`、`nexus_notify.log`。

## 双卡来电策略

进程 **`nexus_callpolicy`**（开机由 `service.sh` 拉起；**不被** `restart_callstack.sh` 杀掉）。

| 策略 | 行为 |
|------|------|
| `human`（默认） | 响铃，等人工 |
| `ai` | 自动接通 → 现有 HAL / `ai_call` 闭环 |
| `reject` | 自动拒接 |

- WebUI「双卡策略」：运营商/号码从系统读取（只读）；仅改 policy
- 检测：`telephony.registry` 的 `Phone Id` + `mCallState=1`
- 接听优先 `KEYCODE_HEADSETHOOK`；拒接 `telecom endCall` / `KEYCODE_ENDCALL`；均校验通话状态
- 设计：`docs/superpowers/specs/2026-07-19-callpolicy-sims-design.md`
- **TODO（后续）：** AI 接听时静麦保 TX（对方只听 AI，环境麦不上行）— 见 `doc/03_pcm_hook_next.md`

## 企微通知（通话 + 双卡短信）

进程 **`nexus_notify`**（开机拉起；**不被** `restart_callstack` 杀掉）。

- **推荐通道：** `notify.channel=wecom_webhook`（企微**内部群**机器人；无企业可信 IP）
- 通话：`ai_call` 落盘 `call_*.txt`（含主叫/本机/策略）+ `.notify` 旁路 → Webhook
- 短信：轮询 `content://sms/inbox`；`sub_id`→卡槽用 `dumpsys isub` 映射（非简单 `sub_id-1`）
- 配置：`config.json` → `notify`（默认 `enabled: false`；Webhook URL 手写、chmod 600）
- 设计：`docs/superpowers/specs/2026-07-19-wecom-notify-design.md`
- 日志：`/data/vendor/ai_hook/nexus_notify.log`

```bat
copy daemon\nexus_notify\nexus_notify_arm64 magisk_modules\nexus_runtime\bin\nexus_notify
```

## `env.sh` / `config.json`

**真源：** `/data/adb/nexus/config.json`（WebUI 读写）。`env.sh` 仅作开机/调试覆盖。

| 变量（env 覆盖） | json 字段 | 默认 | 含义 |
|------|------|------|------|
| `LLM` | `llm.enabled` | `1` | 开 DeepSeek 闭环 |
| `LLM_BARGE_IN` | `llm.barge_in` | `0` | TTS 播放中打断 |
| `DEEPSEEK_MODEL` | `llm.model` | `deepseek-v4-flash` | 模型 |
| （写在 json） | `llm.api_key` | 空 | API Key |
| `STT_LANG` | `stt.lang` | `auto` | 识别语言 |
| `TX_BEEP_PREFIX` | `tts.beep_prefix` | `0` | TTS 前哔声 |
| （写在 json） | `sims[].policy` | `human` | 双卡来电：`human` / `ai` / `reject` |

`env.sh` 在何处生效：被 **`service.sh`**（开机/手动）与 **`scripts/restart_callstack.sh`**（WebUI 保存重启）`source`；首装由 `customize.sh` 从 `env.default.sh` 复制。已 export 的变量会盖过 json，改 WebUI 后若异常请检查 `env.sh`。

改 WebUI 或 json 后服务会按规则重启；改端口需再起 `nexus_webui`（或重跑 `service.sh`）。

日志时区：脚本 export `TZ`（跟系统时区）；Go 需嵌入 `tzdata`（已处理）。

## 配置 UI

现行：**本机 WebUI**（`nexus_webui`）。`config.json` 为真源。
