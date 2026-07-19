# Nexus 本机配置 WebUI 设计

**日期：** 2026-07-19  
**状态：** 已实现（代码已合入；真机打包部署后验收）  
**范围：** `nexus_runtime` 内独立本机配置服务 + 单页设置 UI

## 1. 目标与约束

### 目标

- 在手机本机用浏览器修改 Nexus 运行配置（含 API Key）
- 查看进程状态与相关日志尾
- 保存后自动重启对话相关服务使配置生效

### 约束

- **仅本机**：监听 `127.0.0.1`，不暴露局域网
- **无远程管理**、无账号体系（依赖本机物理访问）
- 配置真源：`/data/adb/nexus/config.json`（含 `api_key`，文件权限 `600`）
- 服务归属：**`nexus_runtime` Magisk 模块**，开机由 `service.sh` 拉起
- 重启 `ai_call` / `nexus_engine` 时 **不杀死** WebUI 进程

### 非目标（v1 不做）

- Magisk Module WebUI / KernelSU 管理器内嵌页
- 独立设置 APK
- 局域网/公网访问、HTTPS、登录鉴权
- 通话中热更新全部参数（以重启生效为主）
- 企微/短信相关配置

## 2. 架构

```text
Chrome (本机)
    → http://127.0.0.1:8787
         ↓
nexus_webui  (Go, root, 常驻)
    ├── 静态页 (embed 或模块 web/)
    ├── 读写 /data/adb/nexus/config.json
    ├── 读日志尾 /data/vendor/ai_hook/*.log（白名单）
    ├── 进程状态：ai_call / nexus_engine / 自身
    └── 保存成功 → 重启对话服务（不杀 webui）
```

### 进程与启动顺序

`service.sh` 建议顺序：

1. `nexus_engine`
2. `ai_call`（从 `config.json` 读参；`env.sh` 仅作可选覆盖）
3. `nexus_webui`（最后启动）

二进制路径：`/data/adb/modules/nexus_runtime/bin/nexus_webui`  
日志：`/data/vendor/ai_hook/nexus_webui.log`

### 端口

- 默认：`127.0.0.1:8787`
- 可在 `config.json` → `webui.port` 修改
- 改端口后：保存时安排 **webui 延时自重启**（或提示用户手动跑 `service.sh`）；对话服务仍按规则重启

## 3. 配置模型

### 路径

| 文件 | 用途 |
|------|------|
| `/data/adb/nexus/config.json` | 真源配置（含 API Key） |
| `/data/adb/nexus/env.sh` | 可选覆盖（调试）；正式开机以 json 为准 |
| `/data/adb/nexus/secrets/deepseek.key` | 遗留；首启可迁移进 json 后不再依赖 |

### Schema（v1）

```json
{
  "schema": 1,
  "webui": { "port": 8787 },
  "llm": {
    "enabled": true,
    "model": "deepseek-v4-flash",
    "api_key": "sk-...",
    "barge_in": false,
    "max_msgs": 24,
    "system_prompt": ""
  },
  "stt": { "backend": "engine", "lang": "auto" },
  "tts": { "beep_prefix": false, "sid": 0 },
  "paths": {
    "engine_sock": "/data/adb/nexus/run/engine.sock",
    "archive_dir": "/data/vendor/ai_hook/calls",
    "stt_model": "/data/adb/modules/nexus_models/models/sense-voice",
    "tts_model": "/data/adb/modules/nexus_models/models/vits-zh-ll"
  }
}
```

### Key 处理

- 明文存在 json 内（用户明确要求，便于本机编辑）
- 文件 `chmod 600`
- `GET /api/config` **不返回**完整 `api_key`：返回 `api_key_set`（bool）与可选 `api_key_hint`（末四位）
- `PUT`：密码框为空 = 保留原 key；非空 = 更新；显式清空策略：提供「清除 Key」控件或送 `api_key: ""` 且带 `clear_api_key: true`

### 迁移

首次 `nexus_webui` 或 `service.sh` 发现：

- json 无 key，但 `secrets/deepseek.key` 有内容 → 读入 json 并写回
- 仅有 `env.sh`、无可用 json → 从 `config.default.json` + env 合成一份 json

## 4. 保存与重启规则

| 变更 | 动作 |
|------|------|
| 任意配置保存成功 | 默认重启 `ai_call` |
| `paths.stt_model` / `tts_model` / `engine_sock` 等引擎相关 | 先停/起 `nexus_engine`，再起 `ai_call` |
| 仅 `webui.port` | 延时自重启 `nexus_webui`（或提示手动） |
| 重启对话服务 | **不** `pkill nexus_webui` |

实现上复用/抽离现有 `service.sh` 中启动 `engine`+`ai_call` 的逻辑（例如 `restart_callstack.sh` 供 webui 与 service 共用），避免两套命令漂移。

写盘：临时文件 + `rename` 原子替换。

## 5. HTTP API

基址：`http://127.0.0.1:<port>`

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/` | 静态单页 |
| `GET` | `/api/config` | 读配置（key 脱敏） |
| `PUT` | `/api/config` | 写配置并按规则重启 |
| `GET` | `/api/status` | 各进程 running / pid |
| `GET` | `/api/logs?name=&lines=` | 日志尾；`name` 白名单 |
| `POST` | `/api/restart` | 手动重启对话栈（不杀 webui） |

日志白名单：`ai_call`、`nexus_engine`、`nexus_runtime`、`nexus_webui`  
对应文件在 `/data/vendor/ai_hook/`（或 runtime 约定路径）。

错误体：`{ "ok": false, "error": "..." }`  
成功写配置：`{ "ok": true, "restarted": ["ai_call", ...], "status": { ... } }`

只接受来自本机的连接（bind `127.0.0.1` 即可满足 v1）。

## 6. 页面结构

单页，手机竖屏优先，无重型前端构建（静态 HTML/CSS/JS）：

1. **状态**：进程指示、刷新、手动重启服务  
2. **设置**：LLM / STT / TTS / 路径 / WebUI 端口；保存按钮  
3. **日志**：选择日志名、行数、展示尾部  

保存成功后刷新状态区并提示「已重启服务」。

## 7. 与 `ai_call` 集成

- `ai_call`（或 `service.sh` 启动参数生成）改为优先读 `config.json`
- 现有 flag / `env.sh` 保留为覆盖，便于 adb 调试
- `LLM_BARGE_IN` 等映射到 `llm.barge_in`

## 8. 打包与目录

```text
magisk_modules/nexus_runtime/
  bin/nexus_webui          # 打包前填入（gitignore 大二进制）
  web/                     # 可选：若未 embed，则由此托管静态资源
  service.sh               # 增加拉起 webui
  config/config.default.json  # 与 schema 对齐（可含空 api_key）
```

仓库源码建议：`daemon/nexus_webui/`（Go module，CGO=0，linux/arm64）。

## 9. 测试计划

- 单元：config 读写、脱敏、原子写、迁移逻辑  
- 本机/模拟：bind 仅 127.0.0.1；PUT 后进程被重启且 webui 仍存活  
- 真机：Chrome 打开 `http://127.0.0.1:8787`，改 barge_in / model / key，通话验证；看 status/logs  
- 回归：无 json 时从 default + 旧 key 文件迁移

## 10. 实现顺序（供后续 plan 展开）

1. `config.json` schema + 读写/迁移库  
2. `nexus_webui` 最小 API + 静态页骨架  
3. 接入 `service.sh` 与对话栈重启脚本  
4. `ai_call` 读 json  
5. 补齐设置项与状态/日志页  
6. 文档（`magisk_modules/README.md`、`ai_call` README、journal）

## 11. 决议摘要

| 项 | 决定 |
|----|------|
| 形态 | 独立 `nexus_webui`，方案 B 本机 HTTP |
| 配置路径 | `/data/adb/nexus/config.json` |
| API Key | 存 json；GET 脱敏 |
| 生效 | 保存后自动重启相关服务 |
| 范围 | 设置 A+B + 状态/日志 C |
| 默认端口 | 8787 |
