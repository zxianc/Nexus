# 开发过程流水账（Dev Log）

> **写法：只追加，不改写历史。**  
> 每条建议包含：日期、做了什么、结果、下一步。详细命令可链到专题文档。

---

## 2026-07-18 — 注入闭环（Zygisk + ptrace）

- 废弃 Overlay 换 `audioserver` + `LD_PRELOAD`（SELinux / init 清环境 / 核心 bin 挂载被丢）。
- 落地 `zygisk_module`：companion + `bin/inject` remote `dlopen("/system/lib64/libai_hook.so")`。
- 真机 maps 稳定出现 `libai_hook.so`。
- 详情：[`02_zygisk_inject_progress.md`](02_zygisk_inject_progress.md)、[`Magisk_Injection_Log.md`](Magisk_Injection_Log.md)。

## 2026-07-18 — Dobby 探测通过

- `clock_gettime` 不适合做探测（易 VDSO）；改为 Hook `libc.so!openat`。
- `DobbyCodePatch` 曾 SIGSEGV → `audioserver` 缺 `execmem`。
- Magisk 规则正确写法：`allow audioserver audioserver process execmem`（不能用 `self:` 冒号语法）。
- constructor 内先延迟 ~1.5s 再 Hook，避开 dlopen 锁。
- 真机确认：`DobbyHook(openat) rc=0`，进程稳定、无 `onAudioServerDied` 刷屏。

## 2026-07-18 — 文档约定

- 新增本流水账 + [`README.md`](README.md) 索引。
- 约定：过程记 `devlog`，路线图记 `plan.md`，大专题可新建 `03_*.md`。

---

## 下一条预告（尚未开始）— 通话 PCM Hook 点

建议条目模板（开始做时复制填写）：

```markdown
## YYYY-MM-DD — 通话 PCM 候选符号

- 设备 / 系统版本：
- 尝试 Hook 的符号 / 库：
- 验证方式（来电 / 录音 / log 计数）：
- 结果：成功 / 失败 / 崩溃
- 结论与下一步：
```

候选方向（供实施时参考，非定论）：

1. 在 `audioserver` 内枚举已加载库：`libaudioflinger.so` / `libaudioclient.so` / vendor HAL。  
2. 用 Dobby resolve 若干读写相关符号，**先只打计数日志**（勿一上来改 PCM）。  
3. 真实通话场景对比：有无来电时计数是否变化。  
4. 确认上/下行各自函数后再做替换，并留意 AEC。
