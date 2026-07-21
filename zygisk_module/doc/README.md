# Zygisk 模块操作手册（Nexus Audio Hook）

配套文档：[`doc/00_framework_overview.md`](../../doc/00_framework_overview.md)、[`doc/README.md`](../../doc/README.md)。

**模块 ID：** `nexus_audio_hook`（原 `ai_audio_hook`，v2.2 起更名；装新包前请卸载旧模块）。

## 机制（现行）

1. **载荷路径（重要）：** `/vendor/lib/libai_hook.so`（armeabi-v7a）  
   - 勿放 `/system/lib`：HAL linker namespace 会拒绝  
2. **注入目标：** `android.hardware.audio.service`（`inject32`）  
3. **通话 PCM：** incall-rec（`pcm_read` card0 **device 23** @ 48kHz s16le）
   - **已验证：** UL+DL 混合（听验/人模式存档）
   - **AI 模式：** **DL-only** → UDS → STT；文本存档已做；语音 `mix` TODO  
4. **UDS：** HAL listen `pcm.sock` / abstract `@nexus_pcm`；App 帧化双工（PCM_DL / PCM_UL）  
5. **TX：** 仅 UDS `PCM_UL` 队列 → incall-music（无 `tx_inject.pcm` / 测试音文件）  
6. sepolicy：`hal_audio_default` 的 `execmem` + `vendor_data_file` + UDS（含 `untrusted_app`）

## 目录

```text
nexus_audio_hook/
├── module.prop / customize.sh / service.sh
├── post-fs-data.sh / sepolicy.rule
├── bin/inject32
├── vendor/lib/libai_hook.so      # 32-bit HAL 载荷（so 名未改）
└── （可选）system/lib64/...      # audioserver 探测
```

## 编译 / 安装

```bat
cd zygisk_module
build.bat
```

产物：`nexus_audio_hook_zygisk.zip`  
Magisk 开 Zygisk → **卸载旧 `ai_audio_hook`（若有）** → 装 zip → **重启**。

## 验证（PowerShell）

```powershell
adb shell 'su -c "grep libai_hook /proc/$(pidof android.hardware.audio.service)/maps"'
adb logcat -d -s AI_Audio_Hook:I
```

手动注入：

```powershell
adb shell 'su -c "/data/adb/modules/nexus_audio_hook/bin/inject32 android.hardware.audio.service /vendor/lib/libai_hook.so"'
```

## 开机后自检

```powershell
adb shell "su -c 'cat /data/adb/modules/nexus_audio_hook/module.prop'"
adb shell "su -c 'hp=\$(pidof android.hardware.audio.service); grep libai_hook /proc/\$hp/maps'"
adb shell "su -c 'ls -la /data/vendor/ai_hook/pcm.sock'"
```

期望：`id=nexus_audio_hook` / `version=v2.2`；maps 有 `libai_hook`；存在 `pcm.sock`。

## 常见坑

| 现象 | 处理 |
|------|------|
| 新旧模块并存 | Magisk 卸载 `ai_audio_hook` 后再装本模块 |
| `dlopen` namespace 拒绝 | 载荷必须在 `/vendor/lib` |
| Dobby maps SIGBUS | HAL 侧用 `dlsym`，勿 `DobbySymbolResolver` |
| PowerShell `$(pidof)` | 勿让 PS 展开；用 `adb shell su -c "..."` 或外层单引号 |
