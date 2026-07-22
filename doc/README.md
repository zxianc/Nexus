# Nexus 文档索引

**现行栈：** `nexus_phone`（`org.fossify.nexus.phone` / Kotlin `com.nexus.phone`）+ Magisk 模块 `nexus_audio_hook`（Zygisk HAL）。

## 必读

| 文档 | 内容 |
|------|------|
| [`00_framework_overview.md`](00_framework_overview.md) | **框架总览**（进程、配置、Webhook、路径） |
| [`../nexus_phone/README.md`](../nexus_phone/README.md) | **App 编译 / 签名 / 安装** |
| [`../zygisk_module/doc/README.md`](../zygisk_module/doc/README.md) | Hook 编译 / 安装 / 验证 |

## 归档 / 弃用

| 文档 | 内容 |
|------|------|
| [`../nexus_app/README.md`](../nexus_app/README.md) | 旧 App（`com.nexus.assistant`）— 已弃用 |
| [`../docs/superpowers/archive/2026-07-20-nexus-app-architecture/`](../docs/superpowers/archive/2026-07-20-nexus-app-architecture/) | 旧 App 架构提案归档 |
| [`../docs/superpowers/specs/2026-07-22-nexus-phone-fossify-mod-design.md`](../docs/superpowers/specs/2026-07-22-nexus-phone-fossify-mod-design.md) | Fossify 魔改设计 |

## 仓库布局

```text
Nexus/
├── README.md
├── nexus_phone/          # 现行电话 App（Fossify 魔改）
├── nexus_app/            # 已弃用对照源
├── zygisk_module/        # Magisk audio hook
├── doc/                  # 现行文档
└── docs/superpowers/     # 规格 / 计划 / 归档
```
