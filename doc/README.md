# Nexus 文档索引

**现行栈：** Kotlin App `com.nexus.assistant` + Magisk 模块 `nexus_audio_hook`（Zygisk HAL）。  
已移除：Go 守护、`nexus_runtime` / `nexus_models`、旧过程文档（历史见 git）。

## 必读

| 文档 | 内容 |
|------|------|
| [`00_framework_overview.md`](00_framework_overview.md) | **框架总览**（进程、配置、Webhook、路径） |
| [`../docs/superpowers/specs/2026-07-20-nexus-app-architecture-design.md`](../docs/superpowers/specs/2026-07-20-nexus-app-architecture-design.md) | App 架构设计 |
| [`../docs/superpowers/plans/2026-07-20-nexus-app-architecture.md`](../docs/superpowers/plans/2026-07-20-nexus-app-architecture.md) | 实现计划与里程碑 |
| [`../docs/superpowers/plans/checklists/2026-07-20-app-mvp-acceptance.md`](../docs/superpowers/plans/checklists/2026-07-20-app-mvp-acceptance.md) | G3 验收清单 |
| [`../zygisk_module/doc/README.md`](../zygisk_module/doc/README.md) | Hook 编译 / 安装 / 验证 |
| [`../nexus_app/README.md`](../nexus_app/README.md) | App 工程说明 |

## 仓库布局

```text
Nexus/
├── README.md
├── nexus_app/                 # Kotlin App
├── zygisk_module/             # nexus_audio_hook（third_party/Dobby）
├── doc/                       # 现行文档（本目录）
└── docs/superpowers/          # 架构 spec / plan / checklist
```
