# 深度复盘：OnePlus 8T (Android 11+) `audioserver` 底层注入实战全记录

**实战背景**：
*   **目标**：通过自定义的 C++ 动态库 (`libai_hook.so`) 注入 Android 核心音频服务 `audioserver`，利用 Dobby 框架拦截底层音频流。
*   **核心手段**：利用 `LD_PRELOAD` 环境变量机制进行前置注入。
*   **设备环境**：OnePlus 8T (高通平台，Android 11+，动态分区 System-as-root)。

---

## 阶段一：Magisk 基础安装与提权踩坑

### 1. ZIP 安装死锁 (Extracting files 卡死)
*   **现象**：使用 Magisk App 或 `magisk --install-module` 刷入模块 ZIP 包时，死死卡在“解压文件”环节，强行重启后设备可能无限转圈。
*   **根本原因**：一加的 OverlayFS 底层文件系统与 Magisk 内置的 Busybox `unzip` 工具发生了罕见的 I/O 读写死锁。
*   **解决方案**：放弃 ZIP 刷入。通过 PC 端解压，使用 `adb push` 将裸文件推送到 `/data/local/tmp/`，然后通过 `adb shell` 手动组装 `$MODPATH`。

### 2. ADB Shell 基础权限认知 (`$` 与 `#`)
*   **现象**：手动组装模块时，执行 `mkdir`, `cp`, `chmod`, `chcon` 等命令满屏报错 `Permission denied`。
*   **根本原因**：忽视了 `adb shell` 默认进入的是普通用户环境（提示符为 `$`），无权操作 `/data/` 或修改系统级属性。
*   **解决方案**：先执行 `su`，确保提示符变为 `#` (Root Shell) 后再执行高权限指令。

---

## 阶段二：绕过系统守护进程校验的多次博弈

### 1. 方案 A：Shell 脚本替身 (失败)
*   **操作**：将真实的 `audioserver` 重命名为 `audioserver_real`，原地创建一个同名的 `.sh` 脚本，内容为 `export LD_PRELOAD=...` 然后 `exec audioserver_real`。
*   **现象**：系统正常开机，但库未加载，原服务依旧运行。
*   **死因分析 (SELinux 拦截)**：Android 的“祖宗进程” `init` 负责拉起 `audioserver`。它要求该守护进程必须是纯正的 **ELF 二进制文件**。发现是脚本后，系统需要调用 `/system/bin/sh`，这触发了 SELinux 针对 `audioserver` 的极严策略，直接静默拒载。

### 2. 方案 B：Service.sh 劫持与环境变量注入 (失败)
*   **操作**：不修改系统文件，利用 Magisk 的 `service.sh`，在开机后执行 `killall -9 audioserver`，随即 `export LD_PRELOAD=...` 并拉起原服务。
*   **现象**：`magisk.log` 显示脚本已执行，进程被重启，但 `maps` 内存映射中依然没有目标 `.so`。
*   **死因分析 (Init 进程的环境洁癖)**：无论你怎么 kill 进程，只要是由系统机制或 `init` 重新拉起的核心服务，`init` 都会在启动前**强制清洗所有非标准的环境变量**，导致 `LD_PRELOAD` 惨遭过滤。

### 3. 方案 C：C++ ELF 二进制替身 (陷入 Bootloop)
*   **操作**：编写 `wrapper.cpp`，利用 C++ 的 `setenv` 强行植入环境变量，并通过 `execv` 拉起真实服务。编译为无后缀的 ELF 文件后，通过 Magisk 挂载替换原版 `audioserver`。
*   **现象**：手机无限卡 Logo (Bootloop)。
*   **排查过程与惊人发现**：
    *   检查 `/data/tombstones/`，发现**日志为空 (`total 0`)**。
    *   **死因分析 (VFS 挂载黑洞)**：没有 tombstone 说明程序根本没有发生 C++ 崩溃（如段错误）。这是因为 Android 11 的动态分区机制，当 Magisk 试图 Overlay 替换极度核心的 `/system/bin/audioserver` 时，底层挂载链断裂。`init` 进程去寻找该文件时遇到了 VFS（虚拟文件系统）层面的死锁，导致系统级挂起。

---

## 阶段三：桌面 Dry Run (空载测试) 与终极乌龙

为了避开开机挂载死锁，我们进入系统桌面，在 Root 终端内手动执行带有环境的启动命令：
`LD_PRELOAD=... /system/bin/audioserver`

### 1. 终端“假死”乌龙
*   **现象**：敲下回车后，终端没有任何输出，也没有报错，光标一直卡住无法输入。
*   **真相**：**代码完美运行了！** `audioserver` 本质是一个死循环监听音频请求的守护进程（Event loop）。它成功被拉起并霸占了当前的前台终端。因为 C++ 库中的 `__attribute__((constructor))` 极为规范地使用了 `pthread_create` 开辟新线程，并没有阻塞主线程，所以一切运转极其健康。

### 2. Windows 环境与日志捕获坑点
*   **坑点 1 (grep 不存在)**：在 PowerShell 直接使用 `adb logcat | grep` 报错，因为 Windows 没有 `grep` 命令。改为 `adb shell "logcat | grep..."` 或使用 Android 原生的 `adb logcat -e`。
*   **坑点 2 (GBK 乱码)**：成功输出日志时显示类似 `鎴愬姛娼滃叆...` 的乱码。原因是 PowerShell 默认使用 GBK (CP936) 编码，而 Android 日志是 UTF-8。实际上，这正是我们代码中打印的：“成功潜入 audioserver 进程！”。

---

## 阶段四：OverlayFS 的最终“降维打击”

*   **操作**：将验证成功的 C++ 替身打包成规范的 Magisk 模块（使用 `customize.sh` 执行 `cp` 提权并赋予 `u:object_r:audioserver_exec:s0` SELinux 标签）。
*   **现象**：手机顺利开机，但**注入彻底失效**。内存映射表和 logcat 均无踪迹。
*   **最终真相揭秘**：
    在 OnePlus 8T 这类设备的现代固件中，内核对 `/system/bin` 拥有极高的完整性校验。Magisk 虽然在自身的 `/data/adb/modules/` 沙盒中准备好了完美的 `system/bin/audioserver` 替换树，但在开机后期的合并阶段，系统底层**识别到核心文件被劫持，默默地将这部分挂载（Overlay）抛弃了**。
    由于挂载被抛弃，系统依然使用的是原装的 `audioserver` 启动，因此不会死机，但也彻底宣告了“替换文件流派”的失败。

---

## 架构重构与长远建议

### 本次实战的最终破局思路
既然 `/system/bin` 的替换会被系统抛弃，那就**坚决不碰系统目录**。
将编译好的 C++ 替身（Wrapper）和 `.so` 库直接放在模块根目录。利用 `service.sh`，在开机 10 秒后，`killall -9 audioserver`，然后通过我们的模块目录的绝对路径，**强行用替身拉起原版服务**。

### 给安卓底层逆向开发者的终极建议
如果目标是 Android 11+ 的核心守护进程（如 `audioserver`, `surfaceflinger`, `app_process`）：
1. **不要再死磕 `LD_PRELOAD` 和文件替换**。厂商的魔改（如一加的自研内核安全策略、三星的 Knox）防线太深，维护成本极高。
2. **拥抱现代内存注入框架**。建议直接改用 **Zygisk (Riru)** 模块架构，或者编写脱机 Root 程序使用 `ptrace` 进行纯内存层面的 `Shellcode` 注入加载。它们在服务启动后进行动态 Hook，彻底免去了与文件系统、`init` 守护进程以及 SELinux 上下文的肉搏战。