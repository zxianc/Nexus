# Nexus 文档索引与更新约定

## 文档分工

| 文件 | 角色 | 怎么改 |
|------|------|--------|
| [`plan.md`](plan.md) | **总方案 / 路线图** | 只改「当前进度」和选型结论；少写长篇过程 |
| [`dev_journal.md`](dev_journal.md) | **过程流水账（主记录）** | **只追加**新条目，不删不改已有记录 |
| [`devlog.md`](devlog.md) | 早期流水（可与 journal 并存） | 增量追加 |
| [`01_magisk_native_build_and_verify.md`](01_magisk_native_build_and_verify.md) | 早期 Magisk 编译手记 | 历史归档 |
| [`Magisk_Injection_Log.md`](Magisk_Injection_Log.md) | LD_PRELOAD 失败复盘 | 历史归档 |
| [`02_zygisk_inject_progress.md`](02_zygisk_inject_progress.md) | 注入阶段操作手册 | 可小改验证命令 |
| [`03_pcm_hook_next.md`](03_pcm_hook_next.md) | 当前里程碑 | 跟当前阶段同步 |
| [`04_architecture_runtime.md`](04_architecture_runtime.md) | **现行实现：方案 / 数据流 / 线程** | 架构变更时改 |
| [`../zygisk_module/doc/README.md`](../zygisk_module/doc/README.md) | 模块怎么编/装/验 | 跟代码同步 |

## 增量更新约定

1. **过程、踩坑、当天结论** → 追加到 `dev_journal.md`。  
2. **方案结论变了** → 改 `plan.md`，并在 journal 留一条。  
3. **旧文档当史料**，不重写失败过程。  
4. **模块用法** → 更新 `zygisk_module/doc/README.md`。

## 当前进度一句话

**1.C+1.D+1.D′+1.F+VAD(A) 完成；模块 v2.1 重装后自动注入/UDS 已验。** 下一步：**1.E** TX（`ai_call` 仍手动启）。
