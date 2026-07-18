# Zygisk 模块操作手册（AI Audio Hook）

配套文档：[`doc/plan.md`](../../doc/plan.md)、[`doc/03_pcm_hook_next.md`](../../doc/03_pcm_hook_next.md)、[`doc/dev_journal.md`](../../doc/dev_journal.md)。

## 机制（现行）

1. **载荷路径（重要）：** `/vendor/lib/libai_hook.so`（armeabi-v7a）  
   - 勿放 `/system/lib`：HAL linker namespace 会拒绝  
2. **注入目标：** `android.hardware.audio.service`（`inject32`）  
3. **通话 PCM：** incall-rec  
   - `platform_set_incall_recording_session_id` + `MultiMedia9 Mixer VOC_REC_UL/DL`  
   - `pcm_read` card0 **device 23** @ 48kHz s16le（L≡R 混合上下行）  
4. **落盘：** `/data/vendor/ai_hook/ai_incall.pcm`（需 `vendor_data_file` 写权限）  
5. sepolicy：`hal_audio_default` 的 `execmem` + 读写 `vendor_data_file` / `shell_data_file`

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
- CS `voice_start_usecase(41)` ✅  
- incall-rec 双向通话 PCM 落盘并听验通过 ✅  

## 常见坑

| 现象 | 处理 |
|------|------|
| `dlopen` namespace 拒绝 | 载荷必须在 `/vendor/lib` |
| Dobby maps SIGBUS | HAL 侧用 `dlsym`，勿 `DobbySymbolResolver` |
| dump `errno=13` | sepolicy 写 `vendor_data_file`；预建文件 chmod 666 |
| PowerShell `$(pidof)` | `adb shell 'su -c "..."'` 外层单引号 |
| 以为能「对面接听 AI」 | VOC_REC 只录音；TX 用 incall-music |
