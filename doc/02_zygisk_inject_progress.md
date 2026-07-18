# 阶段一进展总结：Zygisk + ptrace 注入 audioserver

**文档版本:** v1.0  
**日期:** 2026-07-18  
**设备:** OnePlus 8T（高通骁龙 865），Magisk + Zygisk  
**模块目录:** `zygisk_module/`  
**模块 ID:** `ai_audio_hook`  
**当前状态:** **注入已在真机验证通过**（`audioserver` 的 `/proc/<pid>/maps` 含 `libai_hook.so`）

---

## 1. 进展总览

| 里程碑 | 状态 | 说明 |
|--------|------|------|
| Windows NDK 交叉编译 / 打包 | 完成 | `zygisk_module/build.bat` + Python 正斜杠 ZIP |
| Magisk 模块落地（文件 / Zygisk so） | 完成 | `/data/adb/modules/ai_audio_hook/` |
| Magisk Overlay `system/lib64/libai_hook.so` | 完成 | 重启后 `/system/lib64/libai_hook.so` 可见 |
| **ptrace remote dlopen 进入 audioserver** | **完成** | maps 含 `r-xp .../libai_hook.so` |
| constructor 线程跑起来 | 完成 | 手动注入时可见 `Zygisk inject OK`；开机后 logcat 可能被冲掉，以 maps 为准 |
| Dobby / 通话 PCM Hook | **未开始** | 下一阶段 |
| UDS ↔ Go 守护进程 | 未开始 | 阶段二 |
| STT/TTS / DeepSeek / 企微 | 未开始 | 阶段二～四 |

**一句话：** 阶段一里「把库送进 `audioserver`」已经打通；还没开始「在正确函数上 Hook 电话音频」。

---

## 2. 方案为什么改成 Zygisk（失败复盘摘要）

旧路线（`magisk_module` + Overlay 换 `/system/bin/audioserver` + `LD_PRELOAD`）在本机已证伪，详见 `doc/Magisk_Injection_Log.md`：

| 尝试 | 结果 | 原因 |
|------|------|------|
| Shell 脚本替身 | 失败 | `init` 要求 ELF；SELinux 拒脚本 |
| `service.sh` kill 后带环境重启 | 失败 | `init` 清洗非标准环境变量，`LD_PRELOAD` 丢失 |
| C++ ELF wrapper Overlay | Bootloop / 挂载被丢弃 | 核心 bin Overlay 在厂商机上极脆 |
| 桌面手动 `LD_PRELOAD=... audioserver` | **成功** | 说明 `.so` 与 constructor 本身没问题，坏在「投递方式」 |

**结论：** 目标进程仍是 `audioserver`，但投递改为：

```text
Zygisk(system_server specialize)
    → root companion / service.sh
        → ptrace + remote mmap + remote dlopen("/system/lib64/libai_hook.so")
```

注意：`audioserver` **不是** Zygote 子进程，Zygisk 的 `preAppSpecialize` / `preServerSpecialize` **不会**直接进入它；Zygisk 只负责触发有 root 权限的 companion。

---

## 3. 现行架构（已落地）

```text
zygisk_module/
├── module.prop                 # id=ai_audio_hook, Zygisk 模块
├── customize.sh                # 安装时 chmod inject
├── service.sh                  # 开机兜底：inject audioserver /system/lib64/libai_hook.so
├── bin/inject                  # CLI 注入器（与 companion 共用 inject.cpp）
├── zygisk/arm64-v8a.so         # Zygisk 模块：preServerSpecialize → companion
└── system/lib64/libai_hook.so  # 载荷：constructor 起线程打日志（后续接 Dobby）
```

**载荷路径硬约束：**  
`audioserver` 因 SELinux **无法** `dlopen(/data/local/tmp/...)`。  
必须使用 Magisk 已挂载的 **`/system/lib64/libai_hook.so`**。

**注入器关键实现点（`cpp/inject.cpp`）：**

1. 用本机 `dlsym(mmap/dlopen)` + `/proc/self` 与目标 maps **基址重定位**（避免 ELF 偏移算错导致 remote mmap 失败）。
2. remote call 返回陷阱用 `LR=0`（或 BRK gadget），避免错误 RET gadget 导致挂死。
3. `bin/inject` 必须 `chmod 755`。

---

## 4. 操作步骤（从编译到安装）

### 4.1 环境前提

- Windows + Android SDK / NDK（脚本默认：`E:\android\SDK\ndk\30.0.15729638`，CMake `4.1.2`）
- 手机已 Root，Magisk **设置 → 开启 Zygisk**
- PC 能 `adb devices` 看到设备
- 修改 `zygisk_module/build.bat` 里的 `ANDROID_SDK` / `NDK_PATH` 若路径不同

### 4.2 编译打包

```bat
cd E:\workspace\Nexus\zygisk_module
build.bat
```

成功产物：

- `zygisk_module\ai_audio_hook_zygisk.zip`
- 中间文件在 `zygisk_module\out\` 与 `zygisk_module\cpp\build\`

打包使用 `pack_zip.py`（**正斜杠**路径），避免 Windows `Compress-Archive` 反斜杠导致 Magisk 解压出畸形文件名。

### 4.3 安装

**方式 A：Magisk App**

1. 把 zip 推到手机：`adb push zygisk_module\ai_audio_hook_zygisk.zip /sdcard/Download/`
2. Magisk → 模块 → 从本地安装 → 选 zip → 重启

**方式 B：若 Magisk 解压卡死（一加已知坑）**

手动组装模块目录（需 `adb shell` 后先 `su`）：

```bash
adb push zygisk_module/out/. /data/local/tmp/ai_audio_hook_out/
adb shell
su
rm -rf /data/adb/modules/ai_audio_hook
mkdir -p /data/adb/modules/ai_audio_hook
cp -a /data/local/tmp/ai_audio_hook_out/* /data/adb/modules/ai_audio_hook/
chmod 755 /data/adb/modules/ai_audio_hook/bin/inject
chmod 755 /data/adb/modules/ai_audio_hook/service.sh
# 确认没有 disable 文件
reboot
```

### 4.4 重启后自动行为

1. Magisk 挂载 `system/lib64/libai_hook.so`
2. Zygisk 在 `system_server` specialize 时唤起 companion，后台等待并注入
3. `service.sh` 在 `sys.boot_completed=1` 后再兜底执行一次 `bin/inject`

---

## 5. 验证方法（以 maps 为准）

### 5.1 三级验证清单

| 级别 | 命令 | 期望 |
|------|------|------|
| L1 模块落地 | 见下 | `module.prop` 存在、无 `disable`、有 `zygisk/arm64-v8a.so` |
| L2 Overlay | `ls -l /system/lib64/libai_hook.so` | 文件存在 |
| **L3 注入成功（判定标准）** | `grep libai_hook /proc/$(pidof audioserver)/maps` | **至少一行，且含 `r-xp`** |

**PowerShell / cmd 推荐（注意引号，避免 `$(pidof)` 被本机解析）：**

```powershell
adb shell "su -c 'grep libai_hook /proc/\$(pidof audioserver)/maps'"
adb shell "su -c 'ls -l /system/lib64/libai_hook.so'"
adb shell "su -c 'cat /data/adb/modules/ai_audio_hook/module.prop; [ -f /data/adb/modules/ai_audio_hook/disable ] && echo DISABLED || echo ENABLED'"
```

或在 `adb shell` → `su` 后直接敲：

```bash
grep libai_hook /proc/$(pidof audioserver)/maps
ls -l /system/lib64/libai_hook.so
```

### 5.2 日志（辅助，不作为唯一标准）

```powershell
adb logcat -d -s AI_Audio_Hook:I AI_Inject:I AI_Zygisk:I
```

期望（若缓冲区未冲掉）：

- `AI_Inject: ... injecting /system/lib64/libai_hook.so`
- `AI_Audio_Hook: Zygisk inject OK, inside audioserver pid=...`

**说明：** 开机很早的 constructor 日志常被 ring buffer 冲掉；**maps 有库 = 成功**，logcat 空不代表失败。

### 5.3 真机已记录的成功样例（2026-07-18 重启后）

```text
7369e34000-7369e35000 r--p ... /system/lib64/libai_hook.so
7369e38000-7369e39000 r-xp ... /system/lib64/libai_hook.so
7369e3c000-7369e3d000 r--p ... /system/lib64/libai_hook.so

-rw-r--r-- 1 root root 9032 ... /system/lib64/libai_hook.so
```

### 5.4 失败时手动补打一枪

```powershell
adb shell "su -c 'chmod 755 /data/adb/modules/ai_audio_hook/bin/inject; /data/adb/modules/ai_audio_hook/bin/inject audioserver /system/lib64/libai_hook.so'"
adb logcat -d -s AI_Inject:I AI_Audio_Hook:I
adb shell "su -c 'grep libai_hook /proc/\$(pidof audioserver)/maps'"
```

常见失败与对策：

| 现象 | 对策 |
|------|------|
| maps 无库，`dlopen(...tmp...) => 0` | 改用 `/system/lib64/libai_hook.so`，勿用 tmp |
| `remote mmap failed` | 确认用的是带基址重定位的新 `inject` |
| inject 卡住数分钟 | 旧 RET gadget bug，换新构建；必要时 `stop audioserver; start audioserver` |
| `/system/lib64/libai_hook.so` 不存在 | 模块未启用 / Overlay 失败 / ZIP 反斜杠打包损坏 |
| Zygisk 相关无日志 | Magisk 是否开启 Zygisk；模块是否被 disable |

---

## 6. 与仓库目录的关系

| 目录 | 角色 |
|------|------|
| `zygisk_module/` | **现行主线**：编译、安装、验证都围绕这里 |
| `magisk_module/` | 旧 LD_PRELOAD / wrapper 实验，**不再作为注入主路径** |
| `doc/Magisk_Injection_Log.md` | 旧路线失败复盘 |
| `doc/01_magisk_native_build_and_verify.md` | 早期 Native 编译与 Overlay 验证手记 |
| `doc/plan.md` | 总方案（已同步为 Zygisk 路线） |

---

## 7. 下一步（阶段一剩余 / 阶段二前置）

1. **在 `libai_hook.so` 中接入 Dobby**（仓库已内嵌可用提交的 Dobby 源码在旧 `magisk_module/cpp/Dobby`，可迁入 zygisk 构建）。
2. **定位通话 PCM 路径**（高通机上不一定是 `AudioRecord::read` / `AudioTrack::write`；需结合 AOSP / 厂商 `libaudioclient` / HAL / 通话场景动态验证）。
3. **UDS IPC** 与 Go 守护进程对接，再进入 STT/TTS / DeepSeek。

---

*本文档记录截至 2026-07-18：注入链路已闭环，音频 Hook 业务尚未开始。*
