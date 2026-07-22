# Nexus Phone

基于 Fossify Phone 魔改的 Nexus AI 电话助理（默认电话壳 + PCM 旁路 + ASR/TTS/LLM）。

## 上游

见 [UPSTREAM.md](UPSTREAM.md)。

## 环境要求

- Windows + PowerShell（或等价 shell）
- Android SDK（`sdk.dir` 写入 `local.properties`，该文件勿提交）
- JDK 17+（Android Studio 自带 JBR 即可）
- 真机：OnePlus 8T / LineageOS + Magisk 模块 `nexus_audio_hook`

```powershell
# 示例 local.properties（按本机 SDK 路径修改）
sdk.dir=E:\\Android\\Sdk
```

## 包名约定

| 项 | 值 |
|----|-----|
| **applicationId（正式）** | `org.fossify.nexus.phone` |
| **applicationId（调试）** | `org.fossify.nexus.phone.debug` |
| **Kotlin / namespace** | `com.nexus.phone` |

正式包名必须以 `org.fossify.` 开头（Fossify Commons 反改版检查）。代码包仍为 `com.nexus.phone`。

## 编译

在仓库根目录：

```powershell
cd nexus_phone
```

### Debug（日常开发）

```powershell
.\gradlew.bat :app:assembleCoreDebug
```

产物：`app\build\outputs\apk\core\debug\phone-<versionCode>-core-debug.apk`

### Release（正式包）

1. 准备签名（仅本机，**不要提交** `keystore.properties` / `*.jks`）：

```powershell
# 使用 Android Studio JBR 的 keytool（路径按本机调整）
$kt = "E:\Android\studio\jbr\bin\keytool.exe"
& $kt -genkeypair -keystore nexus-release.jks -alias nexus -keyalg RSA -keysize 2048 -validity 10000 `
  -storepass <密码> -keypass <密码> -dname "CN=Nexus Phone, O=Nexus, C=CN"
```

2. 在 `nexus_phone/keystore.properties` 写入（`storeFile` 相对 **`:app` 模块**）：

```properties
storePassword=<密码>
keyPassword=<密码>
keyAlias=nexus
storeFile=../nexus-release.jks
```

也可参考 `keystore.properties_sample`。

3. 编译：

```powershell
.\gradlew.bat :app:assembleCoreRelease
```

产物：`app\build\outputs\apk\core\release\phone-<versionCode>-core-release.apk`

无签名配置时仍可编译，但 APK **未签名**，多数设备无法直接安装。

### 常用变体

| 任务 | 说明 |
|------|------|
| `:app:assembleCoreDebug` | 调试包（带 `.debug` 后缀） |
| `:app:assembleCoreRelease` | 正式包（minify + 签名） |
| `:app:testCoreDebugUnitTest` | 单元测试 |
| `:app:installCoreDebug` | 直接装到已连接设备 |

Flavor：`core`（默认用这个）/ `foss` / `gplay`。本项目日常只用 **core**。

## 安装

```powershell
adb install -r app\build\outputs\apk\core\release\phone-22-core-release.apk
# 或 debug：
adb install -r app\build\outputs\apk\core\debug\phone-22-core-debug.apk
```

然后：

1. 设为 **默认电话应用**
2. 打开 App → **设置 → Nexus / AI**
3. 开启 Nexus 策略，将对应 SIM 设为 **AI**
4. 配置 LLM API Key / Webhook；导入或拷贝 STT/TTS 模型（见下方）

## 模型与配置位置

完整步骤（从哪下载、下载哪套、怎么导入）：[`doc/01_replace_models.md`](../doc/01_replace_models.md)。

| 内容 | 路径 |
|------|------|
| 配置 | App 私有 `shared_prefs/nexus_config.xml`（文件管理器不可见） |
| 模型（默认） | `files/models/sense-voice`、`files/models/vits-zh-hf-fanchen-C`（旧 `vits-zh-ll` 仍可回退） |
| 模型（设置里选择后） | `files/imported/stt`、`files/imported/tts` |
| 通话存档 | `Android/data/<applicationId>/files/nexus_calls/calls/<id>/` |

卸载 App 会清空私有目录中的模型与配置；覆盖安装（`adb install -r`）一般保留。

## HAL

PCM 旁路依赖 Magisk 模块 `nexus_audio_hook`（仓库 `zygisk_module/`）。说明见 [`doc/00_framework_overview.md`](../doc/00_framework_overview.md) 与 [`zygisk_module/doc/README.md`](../zygisk_module/doc/README.md)。

通话中 LLM 会优先绑定 Wi‑Fi（VoLTE 默认路由常无公网）。
