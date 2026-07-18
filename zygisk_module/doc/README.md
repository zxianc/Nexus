# Zygisk 模块操作手册（AI Audio Hook）

与总方案配套；更完整的进展复盘见仓库 [`doc/02_zygisk_inject_progress.md`](../../doc/02_zygisk_inject_progress.md)、[`doc/plan.md`](../../doc/plan.md)。

## 目标与机制

`audioserver` 由 `init` 拉起，不是 Zygote 子进程。本模块：

1. Magisk Overlay：`/system/lib64/libai_hook.so`
2. Zygisk `preServerSpecialize` → root companion
3. companion / `service.sh`：`ptrace` + remote `dlopen("/system/lib64/libai_hook.so")`

**禁止**对 `audioserver` 使用 `/data/local/tmp/` 下的 so（SELinux 会导致 dlopen 失败）。

## 目录结构

```text
ai_audio_hook/
├── module.prop
├── customize.sh
├── service.sh
├── bin/inject
├── zygisk/arm64-v8a.so
└── system/lib64/libai_hook.so
```

## 编译

```bat
cd zygisk_module
build.bat
```

产物：`ai_audio_hook_zygisk.zip`（Python 正斜杠打包）。

按需修改 `build.bat` 中 `ANDROID_SDK` / `NDK_PATH`。

## 安装

1. Magisk → 开启 **Zygisk**
2. 安装 `ai_audio_hook_zygisk.zip` → 重启  
3. 若 Magisk 解压卡死：手动复制 `out/*` 到 `/data/adb/modules/ai_audio_hook/`，`chmod 755 bin/inject`，再重启

## 验证（重启后）

**判定标准：maps 中有库（优先于 logcat）。**

```powershell
adb shell "su -c 'grep libai_hook /proc/\$(pidof audioserver)/maps'"
adb shell "su -c 'ls -l /system/lib64/libai_hook.so'"
adb logcat -d -s AI_Audio_Hook:I AI_Inject:I AI_Zygisk:I
```

成功示例：

```text
... r-xp ... /system/lib64/libai_hook.so
```

logcat 为空可忽略（开机日志常被冲掉）。

## 手动补注入

```powershell
adb shell "su -c 'chmod 755 /data/adb/modules/ai_audio_hook/bin/inject; /data/adb/modules/ai_audio_hook/bin/inject audioserver /system/lib64/libai_hook.so'"
```

## 已验证结论（2026-07-18）

重启后 maps 稳定出现 `libai_hook.so` → **阶段一注入完成**。  
下一步：在载荷中接入 Dobby，定位通话 PCM Hook 点。
