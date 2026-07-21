# Nexus Assistant (`nexus_app`)

Kotlin App：默认电话接管、ASR/TTS/LLM、存档、Webhook、Settings。  
与 [`zygisk_module/`](../zygisk_module/) 经帧化 UDS（`@nexus_pcm`）双工 PCM。

## Build

SDK 路径写在 `local.properties`（不进 git）。

```bat
gradlew.bat :app:test
gradlew.bat :app:assembleDebug
```

## 文档

- 总览：[`doc/00_framework_overview.md`](../doc/00_framework_overview.md)
- 归档提案：[`docs/superpowers/archive/2026-07-20-nexus-app-architecture/`](../docs/superpowers/archive/2026-07-20-nexus-app-architecture/)
