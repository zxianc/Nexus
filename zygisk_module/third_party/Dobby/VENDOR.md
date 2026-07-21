# Dobby（Nexus 内嵌说明）

本目录是 [jmpews/Dobby](https://github.com/jmpews/Dobby) 的**源码快照**，作为 Nexus 仓库的一部分直接版本管理，**不是** git submodule。

上游仓库的 `.git` 已移除，避免嵌套仓库导致 `git add` 只提交 gitlink、clone 后拿不到源码。

## 当前锁定版本

| 项 | 值 |
|----|----|
| 上游 | https://github.com/jmpews/Dobby.git |
| Commit | `0932d69c320e786672361ab53825ba8f4245e9d3` |
| 短哈希 | `0932d69` |
| 提交说明 | Update backend of xnu kernel, and macho_ctx_kit |
| 日期 | 2023-04-14 |

## 为何不用 master

上游 `master` 近期无法稳定交叉编译到 Android ARM64，常见报错包括：

- `fatal error: 'core/arch/Cpu.h' file not found`
- 汇编 `@PAGE` / `@PAGEOFF` 语法与 Android NDK 不兼容
- 其它头文件 / 符号不完整问题

社区验证可用的提交为 **`0932d69`**（参见 [jmpews/Dobby#237](https://github.com/jmpews/Dobby/issues/237)、[#295](https://github.com/jmpews/Dobby/issues/295)）。本目录即该提交的状态，并已与 `magisk_module/build.bat` 的 NDK/CMake 流程联调通过。

## 在本项目中的用法

由 `magisk_module/cpp/CMakeLists.txt` 通过 `add_subdirectory(Dobby)` 引入，静态链接为 `dobby_static`，产物合并进 `libai_hook.so`。

## 以后若要更新上游

1. 另开目录 clone 上游，`git checkout <新 commit>` 并确认 Android arm64-v8a 能编过。
2. 用新源码替换本目录内容（不要带入 `.git`）。
3. 更新本文件中的 Commit / 日期，并说明更换原因与验证结果。

请勿在本目录重新执行 `git init`，以免再次变成嵌套仓库。
