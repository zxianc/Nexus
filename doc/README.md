# Nexus 文档索引

**现行栈：** `nexus_phone`（`org.fossify.nexus.phone` / Kotlin `com.nexus.phone`）+ Magisk 模块 `nexus_audio_hook`（Zygisk HAL）。

## 必读

| 文档 | 内容 |
|------|------|
| [`00_framework_overview.md`](00_framework_overview.md) | **框架总览**（进程、配置、Webhook、路径） |
| [`01_replace_models.md`](01_replace_models.md) | **替换 STT/TTS 模型**（下载来源、选哪套、装进手机） |
| [`../nexus_phone/README.md`](../nexus_phone/README.md) | **App 编译 / 签名 / 安装** |
| [`../zygisk_module/doc/README.md`](../zygisk_module/doc/README.md) | Hook 编译 / 安装 / 验证 |

## 归档 / 弃用

| 文档 | 内容 |
|------|------|
| [`../docs/superpowers/archive/2026-07-20-nexus-app-architecture/`](../docs/superpowers/archive/2026-07-20-nexus-app-architecture/) | 旧 App 架构提案归档（源码目录 `nexus_app/` 已删除） |
| [`../docs/superpowers/archive/2026-07-23-tts-speaker-speed/`](../docs/superpowers/archive/2026-07-23-tts-speaker-speed/) | TTS 音色 / 语速 / 试听提案归档 |
| [`../docs/superpowers/archive/2026-07-25-call-pipeline-latency/`](../docs/superpowers/archive/2026-07-25-call-pipeline-latency/) | 通话旁路延迟优化（埋点 / TTS 队列 / VAD）归档 |
| [`../docs/superpowers/specs/2026-07-25-ai-answer-prewarm-design.md`](../docs/superpowers/specs/2026-07-25-ai-answer-prewarm-design.md) | AI 响铃预热 + 延迟接听 |
| [`../docs/wechat-lan-api-guide.md`](../docs/wechat-lan-api-guide.md) | **微信局域网 API 使用指南**（Magisk / LSPosed / Bridge / Redis） |
| [`../wechat_bridge/API.md`](../wechat_bridge/API.md) | WeChat Bridge HTTP / Redis 接口 |
| [`../docs/superpowers/specs/2026-08-09-android-wechat-lan-api-design.md`](../docs/superpowers/specs/2026-08-09-android-wechat-lan-api-design.md) | 安卓微信局域网 API（独立 Bridge + LSPosed） |
| [`../docs/superpowers/plans/2026-08-09-android-wechat-lan-api.md`](../docs/superpowers/plans/2026-08-09-android-wechat-lan-api.md) | 安卓微信局域网 API 实现计划 |
| [`../docs/superpowers/specs/2026-07-22-nexus-phone-fossify-mod-design.md`](../docs/superpowers/specs/2026-07-22-nexus-phone-fossify-mod-design.md) | Fossify 魔改设计 |

## 仓库布局

```text
Nexus/
├── README.md
├── nexus_phone/          # 现行电话 App（Fossify 魔改）
├── zygisk_module/        # Magisk audio hook
├── wechat_bridge/        # 微信局域网 Bridge（HTTP + Redis）
├── wechat_hook/          # LSPosed 微信 Hook
├── wechat_protocol/      # Bridge/Hook 共用协议库
├── doc/                  # 现行文档
└── docs/                 # 使用指南 / superpowers 规格计划
```
