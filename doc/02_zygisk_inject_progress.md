# 阶段一进展总结：Zygisk + ptrace 注入 audioserver

**文档版本:** v1.1  
**日期:** 2026-07-18  
**设备:** OnePlus 8T（高通骁龙 865 / LineageOS 23.2），Magisk + Zygisk  
**模块目录:** `zygisk_module/`  
**模块 ID:** `nexus_audio_hook`（原 `ai_audio_hook`）
**当前状态:** **注入 + Dobby 探测 Hook 均已在真机验证通过**

---

## 1. 进展总览

| 里程碑 | 状态 | 说明 |
|--------|------|------|
| Windows NDK 交叉编译 / 打包 | 完成 | `build.bat` + Python 正斜杠 ZIP |
| Magisk 模块落地 | 完成 | `/data/adb/modules/nexus_audio_hook/`（旧 id 请卸载） |
| Overlay `/system/lib64/libai_hook.so` | 完成 | 重启后可见 |
| ptrace remote dlopen | 完成 | maps 含 `libai_hook.so` |
| constructor 异步线程 | 完成 | `Zygisk inject OK` |
| **Dobby resolve + inline hook** | **完成** | `DobbyHook(openat) rc=0`；无崩溃 |
| SELinux `execmem` | 完成 | `sepolicy.rule` + `post-fs-data.sh` |
| 通话 PCM Hook | **未开始** | 下一迭代 |
| UDS ↔ Go / STT / DeepSeek | 未开始 | 阶段二～四 |

**一句话：** 阶段一「进进程 + 框架能 Hook」已闭环；下一步换真实通话 PCM 符号。

---

## 2. 方案为什么改成 Zygisk（失败复盘摘要）

旧路线（Overlay 换 `audioserver` + `LD_PRELOAD`）已证伪，详见 `Magisk_Injection_Log.md`。

现行投递：

```text
Zygisk(system_server specialize)
  → root companion / service.sh
    → ptrace + remote dlopen("/system/lib64/libai_hook.so")
      → constructor 延迟后 DobbyHook(...)
```

---

## 3. 模块结构（现行）

```text
ai_audio_hook/
├── module.prop
├── customize.sh
├── service.sh                 # 开机兜底 inject
├── post-fs-data.sh            # magiskpolicy --live execmem
├── sepolicy.rule              # allow audioserver audioserver process execmem
├── bin/inject
├── zygisk/arm64-v8a.so
└── system/lib64/libai_hook.so # 含 Dobby + openat 探测
```

---

## 4. 关键踩坑（Dobby 阶段）

| 问题 | 现象 | 修复 |
|------|------|------|
| 载荷用 `/data/local/tmp` | `dlopen` 返回 0 | 只用 `/system/lib64/libai_hook.so` |
| Hook `clock_gettime` | resolve 成功但无 Hook 日志 | 常为 VDSO；改探测 `openat` |
| `DobbyCodePatch` SIGSEGV | audioserver 反复 `onAudioServerDied` | 缺 `execmem` |
| `sepolicy` 写法错误 | `Syntax error ... self:process` | Magisk 语法：`allow audioserver audioserver process execmem`（无冒号、不用 self） |
| constructor 内立刻 Hook | 与 dlopen/linker 竞态 | `main_thread` 先 `usleep(1.5s)` |
| PowerShell `$(pidof)` | 本机展开导致 maps 命令失败 | 外层用单引号，见下文 |
| `adb logcat -s TAG` | 「卡住」 | 正常阻塞监听；查历史用 `-d` |

---

## 5. 操作步骤

### 5.1 编译

```bat
cd E:\workspace\Nexus\zygisk_module
build.bat
```

产物：`ai_audio_hook_zygisk.zip`

### 5.2 安装

1. Magisk 开启 Zygisk  
2. 安装 zip → 重启  
3. 解压卡死时：手动把 `out/*` 拷到 `/data/adb/modules/ai_audio_hook/`，`chmod 755 bin/inject`

### 5.3 PowerShell 安全命令（推荐）

```powershell
# maps（注意外层单引号，避免 $(pidof) 被本机解析）
adb shell 'su -c "grep libai_hook /proc/$(pidof audioserver)/maps"'

# 日志 dump（不会一直挂起）
adb logcat -d -s AI_Audio_Hook:I AI_Inject:I

# 手动补注入
adb shell "su -c 'chmod 755 /data/adb/modules/ai_audio_hook/bin/inject; /data/adb/modules/ai_audio_hook/bin/inject audioserver /system/lib64/libai_hook.so'"
```

---

## 6. 验证标准

| 级别 | 期望 |
|------|------|
| L1 模块 | `sepolicy.rule` 存在；so 约 700KB+（含 Dobby） |
| L2 Overlay | `/system/lib64/libai_hook.so` 存在 |
| L3 注入 | maps 有 `libai_hook.so`（含 `r-xp`） |
| L4 Dobby | `DobbyHook(openat) rc=0` / `Dobby probe installed on openat`；**无** tombstone / `onAudioServerDied` |

开机后 logcat 可能为空（ring buffer 冲掉）；**以 maps + 进程稳定为准**。需要看 Hook 日志时可 `stop/start audioserver` 后再 `inject`。

真机成功样例（2026-07-18）：

```text
Zygisk inject OK, inside audioserver pid=...
resolved libc.so!openat @...
DobbyHook(openat) rc=0 orig=...
Dobby probe installed on openat
```

---

## 7. 下一步

1. 去掉 / 收敛 `openat` 探测，改为通话相关符号（AudioFlinger / HAL / voice path，需动态确认）。  
2. 先只读计数，再做 PCM 替换。  
3. UDS 对接 Go 守护进程。

---

*v1.1：记录 Dobby + execmem 真机通过。*
