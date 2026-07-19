# Zygisk 模块操作手册（AI Audio Hook）

配套文档：[`doc/plan.md`](../../doc/plan.md)、[`doc/03_pcm_hook_next.md`](../../doc/03_pcm_hook_next.md)、[`doc/dev_journal.md`](../../doc/dev_journal.md)。

## 机制（现行）

1. **载荷路径（重要）：** `/vendor/lib/libai_hook.so`（armeabi-v7a）  
   - 勿放 `/system/lib`：HAL linker namespace 会拒绝  
2. **注入目标：** `android.hardware.audio.service`（`inject32`）  
3. **通话 PCM：** incall-rec（`pcm_read` card0 **device 23** @ 48kHz s16le）
   - **已验证：** UL+DL 混合（听验/人模式存档）
   - **AI 模式目标：** **DL-only** → UDS → STT；存档由 Go `mix(DL,TTS)`（见 `doc/plan.md` v1.9）
4. **UDS：** HAL listen `/data/vendor/ai_hook/pcm.sock`；Go `pcm_recv` connect  
5. **落盘：** `/data/vendor/ai_hook/ai_incall.pcm`（需 `vendor_data_file` 写权限）  
6. sepolicy：`hal_audio_default` 的 `execmem` + `vendor_data_file` + UDS（含 magisk peer）

## 目录

```text
ai_audio_hook/
├── module.prop / customize.sh / service.sh
├── post-fs-data.sh / sepolicy.rule
├── bin/inject32
├── vendor/lib/libai_hook.so      # 32-bit HAL 载荷
└── （可选）system/lib64/...      # audioserver 探测
```

## 编译 / 安装

```bat
cd zygisk_module
build.bat
```

Magisk 开 Zygisk → 装 zip → **重启**（sepolicy 持久化需要）。  
热更测试：bind-mount + `inject32`（见下）。

## 验证（PowerShell）

```powershell
adb shell 'su -c "grep libai_hook /proc/$(pidof android.hardware.audio.service)/maps"'
adb logcat -d -s AI_Audio_Hook:I
# 通话后拉 PCM（需先 su cp 到 tmp）
adb shell 'su -c "cp -f /data/vendor/ai_hook/ai_incall.pcm /data/local/tmp/; chmod 644 /data/local/tmp/ai_incall.pcm"'
adb pull /data/local/tmp/ai_incall.pcm
```

期望通话日志含：`Round-8 dump fd=`、`DONE ... dumped=` > 0。

手动注入：

```powershell
adb shell 'su -c "/data/adb/modules/ai_audio_hook/bin/inject32 android.hardware.audio.service /vendor/lib/libai_hook.so"'
```

## 已验证（2026-07-19）

- HAL 注入 + Dobby（dlsym）稳定 ✅  
- CS `voice_start_usecase` + incall-rec（混合 / **DL-only**）✅  
- UDS + `ai_call` sherpa STT ✅  
- **模块 v2.1 重装 + 重启 → `service.sh` 自动 inject** ✅（maps + `pcm.sock`）

## 开机后自检（PowerShell）

```powershell
adb shell "su -c 'cat /data/adb/modules/ai_audio_hook/module.prop'"
adb shell "su -c 'hp=\$(pidof android.hardware.audio.service); grep libai_hook /proc/\$hp/maps'"
adb shell "su -c 'ls -la /data/vendor/ai_hook/pcm.sock'"
```

期望：`version=v2.1`；maps 有 `libai_hook`；存在 `pcm.sock`。

## 常见坑

| 现象 | 处理 |
|------|------|
| `dlopen` namespace 拒绝 | 载荷必须在 `/vendor/lib` |
| Dobby maps SIGBUS | HAL 侧用 `dlsym`，勿 `DobbySymbolResolver` |
| dump `errno=13` | sepolicy 写 `vendor_data_file`；预建文件 chmod 666 |
| PowerShell `$(pidof)` | 勿让 PS 展开；用 `adb shell su -c "..."` 或外层单引号 |
| Magisk：`zygisk/armeabi-v7a.so` missing | 仅缺 32-bit Zygisk companion；**主线 inject32 不受影响** |
| 以为能「对面接听 AI」 | VOC_REC 只录音；TX 用 incall-music |
