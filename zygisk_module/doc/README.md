# Zygisk 模块操作手册（AI Audio Hook）

配套文档：[`doc/02_zygisk_inject_progress.md`](../../doc/02_zygisk_inject_progress.md)、[`doc/plan.md`](../../doc/plan.md)。

## 机制

1. Overlay：`/system/lib64/libai_hook.so`（含 Dobby）  
2. Zygisk companion / `service.sh` → ptrace `dlopen`  
3. `sepolicy.rule`：`allow audioserver audioserver process execmem`  

**禁止** `dlopen(/data/local/tmp/...)`。

## 目录

```text
ai_audio_hook/
├── module.prop / customize.sh / service.sh
├── post-fs-data.sh / sepolicy.rule
├── bin/inject
├── zygisk/arm64-v8a.so
└── system/lib64/libai_hook.so
```

## 编译 / 安装

```bat
cd zygisk_module
build.bat
```

Magisk 开 Zygisk → 装 `ai_audio_hook_zygisk.zip` → 重启。

## 验证（PowerShell）

```powershell
# maps（外层单引号，避免 $(pidof) 被本机展开）
adb shell 'su -c "grep libai_hook /proc/$(pidof audioserver)/maps"'

# 日志用 -d，不会一直挂起
adb logcat -d -s AI_Audio_Hook:I
```

期望：

- maps 有 `libai_hook.so`
- 若能抓到日志：`DobbyHook(openat) rc=0` / `Dobby probe installed on openat`
- 无 `onAudioServerDied` 刷屏

手动补注入：

```powershell
adb shell "su -c 'chmod 755 /data/adb/modules/ai_audio_hook/bin/inject; /data/adb/modules/ai_audio_hook/bin/inject audioserver /system/lib64/libai_hook.so'"
```

## 已验证（2026-07-18）

- 注入进 `audioserver` ✅  
- Dobby Hook `openat` ✅（需 execmem sepolicy）  
- 下一步：通话 PCM 真实符号  

## 常见坑

| 现象 | 处理 |
|------|------|
| `logcat -s`「卡住」 | 正常；查历史加 `-d` |
| PowerShell maps 报 `pidof` 不是 cmdlet | 外层改用单引号 |
| `DobbyCodePatch` 崩溃 | 检查 `sepolicy.rule` 是否为无冒号写法 |
