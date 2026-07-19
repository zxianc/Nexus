# Magisk 业务模块：`nexus_runtime` + `nexus_models`

与 **`nexus_audio_hook`**（HAL）解耦。程序与模型分两个 zip，版本可独立升级。

| 模块 | zip | 内容 |
|------|-----|------|
| `nexus_runtime` | `nexus_runtime.zip` | `ai_call`、`nexus_engine`、`libonnxruntime.so`、开机自启、`/data/adb/nexus` 配置 |
| `nexus_models` | `nexus_models.zip` | `models/sense-voice`、`models/vits-zh-ll` |

## 设备路径

```text
/data/adb/modules/nexus_runtime/bin/{ai_call,nexus_engine}
/data/adb/modules/nexus_runtime/lib/libonnxruntime.so
/data/adb/modules/nexus_models/models/sense-voice/
/data/adb/modules/nexus_models/models/vits-zh-ll/
/data/adb/nexus/env.sh                 # 可写配置（首装从默认复制）
/data/adb/nexus/secrets/deepseek.key   # API Key（勿进 git）
/data/adb/nexus/run/engine.sock
/data/vendor/ai_hook/calls/            # 通话文本存档（沿用）
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

日志：`/data/vendor/ai_hook/nexus_runtime.log`、`ai_call.log`、`nexus_engine.log`。

## `env.sh` 常用项

首装从 `config/env.default.sh` 复制到 `/data/adb/nexus/env.sh`，之后只改后者：

| 变量 | 默认 | 含义 |
|------|------|------|
| `LLM` | `1` | 开 DeepSeek 闭环 |
| `LLM_BARGE_IN` | `0` | `1`=TTS 播放中允许语音打断；`0`=只排队下一轮 |
| `LLM_REPLY_DEBOUNCE_MS` | `600` | 开答前防抖（毫秒） |
| `DEEPSEEK_MODEL` | `deepseek-v4-flash` | 模型 id |
| `DEEPSEEK_KEY_FILE` | `…/secrets/deepseek.key` | API Key 路径 |
| `TX_BEEP_PREFIX` | `0` | TTS 前诊断哔声 |

改完：`su -c 'sh /data/adb/modules/nexus_runtime/service.sh'`（或重启）。启动日志应含 `barge_in=true|false`。

## 配置 UI

`config.json` 已预留；现行开机读 **`env.sh`**。设置 APK / WebUI 后续再做。
