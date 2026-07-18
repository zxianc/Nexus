# 个人 AI 通信助理 (AI-Call-Agent) 技术落地白皮书

**版本:** v1.5（注入 + Dobby 探测均已真机验证）  
**日期:** 2026-07-18  
**作者:** Developer  
**目标环境:** 备用 Android 机（实装：OnePlus 8T / 骁龙 865 / LineageOS 23.2，Magisk + Zygisk）

**相关文档：**

- 进展与操作验证：[`doc/02_zygisk_inject_progress.md`](02_zygisk_inject_progress.md)
- 旧 LD_PRELOAD 失败复盘：[`doc/Magisk_Injection_Log.md`](Magisk_Injection_Log.md)
- 模块手册：[`zygisk_module/doc/README.md`](../zygisk_module/doc/README.md)

---

## 1. 系统架构概述

本项目旨在打造完全运行在备用安卓机上的「AI 电话接听与短信摘要」中枢。

核心设计：**底层劫持音频、端侧极致处理、云端高智商推理、微信无缝触达**。

1. **AudioHook（C++ / Magisk Zygisk）**  
   `libai_hook.so` 经 ptrace remote `dlopen` 进入 `audioserver`；Dobby 做 inline hook。  
   **当前：注入与 Dobby 探测（`openat`）已真机通过；通话 PCM Hook 未开始。**

2. **AI 调度守护进程（Go / Termux）** — 未开始  
3. **微信推送层（企业微信 API）** — 未开始  

---

## 2. 核心技术栈与选型

| 类别 | 选型 | 备注 |
|------|------|------|
| Root / 模块 | Magisk 27+，**开启 Zygisk** | |
| 进程注入 | companion + ptrace `dlopen`，`service.sh` 兜底 | 载荷：`/system/lib64/libai_hook.so` |
| Inline Hook | Dobby（静态链入 `libai_hook.so`） | 需 `execmem` sepolicy |
| SELinux | `sepolicy.rule`：`allow audioserver audioserver process execmem` | Magisk 语法无冒号、不用 `self` |
| 守护进程 / STT / TTS / LLM / 企微 | 同前，待后续阶段 | |

### 2.1 已废弃

- Overlay 换 `audioserver` + `LD_PRELOAD`
- `dlopen(/data/local/tmp/...)`
- 探测 Hook `clock_gettime`（易为 VDSO，不可靠）

---

## 3. 实施路线与当前进度

### 阶段一：Native 进入 audioserver 并劫持通话 PCM — **进行中**

#### 1.A 注入投递 — ✅ 完成

详见 [`02_zygisk_inject_progress.md`](02_zygisk_inject_progress.md)。判定：maps 含 `libai_hook.so`。

#### 1.B Dobby 探测 Hook — ✅ 完成

**实现要点：**

- 静态链接 Dobby；constructor 里起线程，**延迟 ~1.5s** 再 Hook（避开 dlopen 锁）。
- 探测目标：`libc.so!openat`（勿用 `clock_gettime`）。
- 模块提供 `sepolicy.rule` + `post-fs-data.sh` 放行 `execmem`。

**成功日志：**

```text
DobbyHook(openat) rc=0
Dobby probe installed on openat
```

**验证（PowerShell）：**

```powershell
adb shell 'su -c "grep libai_hook /proc/$(pidof audioserver)/maps"'
adb logcat -d -s AI_Audio_Hook:I
```

#### 1.C 定位通话 PCM 并拦截 — ⏳ 下一步

- 不预设一定是 `AudioRecord`/`AudioTrack`；高通通话常走 AudioFlinger/HAL/voice。
- 先只读计数，再 PCM 替换；注意 AEC 层级。

#### 1.D UDS ↔ Go — 未开始

---

### 阶段二～四

Go 守护进程、DeepSeek 流式、企微推送 — 均未开始（内容同前版规划）。

---

## 4. 关键风险

| 风险 | 应对 |
|------|------|
| 缺 `execmem` | DobbyCodePatch SIGSEGV；必须带正确 sepolicy |
| ZIP 反斜杠 / 解压卡死 | Python 正斜杠打包；可手动组装模块目录 |
| PowerShell 解析 `$(pidof)` | 外层单引号包住 `adb shell '...'` |
| AEC / Hook 点错误 | 分场景实测选点 |

---

## 5. 目录索引

| 路径 | 说明 |
|------|------|
| `zygisk_module/` | **现行主线** |
| `magisk_module/` | 旧实验 + 内嵌 Dobby 源码 |
| `doc/02_zygisk_inject_progress.md` | 操作与验证详版 |

---

*v1.5：Dobby 探测与 execmem 策略已在真机确认。*
