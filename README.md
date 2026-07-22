# Nexus

OnePlus 8T / LineageOS + Magisk：通话 AI 助理。

| 组件 | 路径 |
|------|------|
| **电话 App（现行）** | [`nexus_phone/`](nexus_phone/) — `org.fossify.nexus.phone`（Kotlin：`com.nexus.phone`） |
| Audio Hook | [`zygisk_module/`](zygisk_module/) — Magisk `nexus_audio_hook` |
| 文档入口 | [`doc/00_framework_overview.md`](doc/00_framework_overview.md) · [`doc/01_replace_models.md`](doc/01_replace_models.md) · [`doc/README.md`](doc/README.md) |

## 快速编译正式包

```powershell
cd nexus_phone
# 先配置 local.properties（sdk.dir）与 keystore.properties（见 nexus_phone/README.md）
.\gradlew.bat :app:assembleCoreRelease
```

产物：`nexus_phone/app/build/outputs/apk/core/release/phone-*-core-release.apk`  
详细步骤（签名、Debug/Release、安装）：[`nexus_phone/README.md`](nexus_phone/README.md)。

配置与模型在 App 内；Magisk **只保留** audio hook。
