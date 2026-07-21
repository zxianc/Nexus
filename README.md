# Nexus

OnePlus 8T / LineageOS + Magisk：通话 AI 助理。

| 组件 | 路径 |
|------|------|
| App | [`nexus_app/`](nexus_app/) — `com.nexus.assistant` |
| Audio Hook | [`zygisk_module/`](zygisk_module/) — Magisk `nexus_audio_hook` |
| 文档入口 | [`doc/00_framework_overview.md`](doc/00_framework_overview.md) · [`doc/README.md`](doc/README.md) |

配置与模型在 App 内；Magisk **只保留** audio hook。
