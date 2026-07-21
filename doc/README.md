# Nexus 文档索引

**现行栈：** Kotlin App `com.nexus.assistant` + Magisk 模块 `nexus_audio_hook`（Zygisk HAL）。

## 必读

| 文档 | 内容 |
|------|------|
| [`00_framework_overview.md`](00_framework_overview.md) | **框架总览**（进程、配置、Webhook、路径） |
| [`../zygisk_module/doc/README.md`](../zygisk_module/doc/README.md) | Hook 编译 / 安装 / 验证 |
| [`../nexus_app/README.md`](../nexus_app/README.md) | App 工程说明 |

## 归档提案

| 文档 | 内容 |
|------|------|
| [`../docs/superpowers/archive/2026-07-20-nexus-app-architecture/`](../docs/superpowers/archive/2026-07-20-nexus-app-architecture/) | App 架构 design / plan / G3 清单（已归档） |

## 仓库布局

```text
Nexus/
├── README.md
├── nexus_app/
├── zygisk_module/
├── doc/                              # 现行文档
└── docs/superpowers/archive/         # 已归档提案
```
