# 开发过程日志（增量追加）

**用途：** 按时间记录做过什么、踩过什么坑、当时结论。  
**写法：** **只在文末追加新条目**，不要改写旧条目（纠错可再开一条「更正」）。  
**总方案 / 当前状态：** 以 [`plan.md`](plan.md) 为准，本文件不负责「最新真相」的大段改写。

---

## 2026-07-18 — 注入链路打通（Zygisk + ptrace）

- **做了什么：** 放弃 Overlay 换 `audioserver` + `LD_PRELOAD`；改为 Zygisk companion / `service.sh` + ptrace remote `dlopen`。
- **载荷路径：** 必须用 Magisk 挂载的 `/system/lib64/libai_hook.so`（`/data/local/tmp` 会被 SELinux 拒绝）。
- **验证：** `grep libai_hook /proc/$(pidof audioserver)/maps` 有 `r-xp`。
- **详记：** [`02_zygisk_inject_progress.md`](02_zygisk_inject_progress.md)、[`Magisk_Injection_Log.md`](Magisk_Injection_Log.md)

---

## 2026-07-18 — Dobby 探测 Hook 打通

- **做了什么：** `libai_hook.so` 静态链 Dobby；探测 Hook `libc.so!openat`。
- **踩坑：**
  1. `clock_gettime` 易为 VDSO，不适合做探测。
  2. `DobbyCodePatch` SIGSEGV → `audioserver` 缺 `execmem`。
  3. Magisk sepolicy 语法要用：`allow audioserver audioserver process execmem`（不能写 `self:`）。
  4. constructor 内立刻 Hook 易与 dlopen 竞态 → 延迟约 1.5s。
  5. PowerShell 会展开 `$(pidof)` → `adb shell` 外层用单引号；`logcat` 查历史加 `-d`。
- **成功日志：** `DobbyHook(openat) rc=0` / `Dobby probe installed on openat`；无 `onAudioServerDied`。
- **结论：** 「进进程 + 框架能 Hook」完成；下一步换通话 PCM 符号。

---

## 2026-07-18 — 阶段 1.C：maps / 符号侦察 + 计数 Hook

- **目标：** 摸清通话 PCM 可能经过的库与符号；挂只读计数 Hook。
- **做了什么：**
  1. 扫 `audioserver` maps：约 52 个 audio 相关映射；**无**独立 `libaudioflinger.so`（AudioFlinger 编进 `/system/bin/audioserver`）；有 `libaudioflinger_datapath/fastpath`、`libnbaio`、`libaudiohal@6.0` 等。
  2. **vendor HAL 不在 audioserver**：`audio.primary.kona` / qti voice 相关在进程 `/vendor/bin/hw/android.hardware.audio.service`（例 pid=6081）。
  3. `libaudioflinger_datapath.so` 导出：`AudioStreamIn::read`、`AudioStreamOut::write`（mangled 见下）。
  4. `audio_hook.cpp`：`AI_HOOK_OPENAT_PROBE` 可关；默认挂上述 IN/OUT **计数 Hook**（不改 buffer）。
- **候选符号：**
  - `_ZN7android13AudioStreamIn4readEPvmPm`
  - `_ZN7android14AudioStreamOut5writeEPKvm`
- **结论：** 第一刀先在 **audioserver 内 datapath** 计数；若通话 hit 不涨，再考虑注入 HAL 进程或 NBAIO（`MonoPipeReader::read` 等）。
- **相关代码：** `zygisk_module/cpp/audio_hook.cpp`；清单 [`03_pcm_hook_next.md`](03_pcm_hook_next.md)

---

## 2026-07-18 — 阶段 1.C 侦察：maps / HAL 进程 / 符号

- **目标：** 摸清通话 PCM 可能落在哪条路径，选计数 Hook 候选。
- **做了什么：** 扫 `audioserver` maps；定位 `audio.primary.kona` 所在进程；`strings` 看 datapath / nbaio。
- **现象：**
  1. `audioserver` **无**独立 `libaudioflinger.so`（AudioFlinger 编进 `/system/bin/audioserver`）；有 `libaudioflinger_datapath.so` / `libnbaio.so` / `libaudiohal@6.0.so` 等。
  2. **vendor HAL**（`audio.primary.kona`、qti/voice/pal 类库）只在  
     `/vendor/bin/hw/android.hardware.audio.service`（当时 pid=6081），**不在** `audioserver`。
  3. datapath 导出：`AudioStreamIn::read` / `AudioStreamOut::write`（mangled 已确认）。
  4. nbaio：`MonoPipeReader::read`、`NBAIO_Sink::writeVia` 等。
- **结论：** 第一轮计数 Hook 挂在 **audioserver 内** datapath 的 In/Out；若通话时无 hit，再考虑注入 HAL 进程或挂 nbaio。
- **相关代码：** `zygisk_module/cpp/audio_hook.cpp`（随后加入 IN/OUT 计数 Hook）。

---

## 2026-07-18 — 阶段 1.C：datapath 计数 Hook 已装上

- **做了什么：** `AI_HOOK_OPENAT_PROBE` 可开关；挂  
  `libaudioflinger_datapath.so!AudioStreamIn::read` / `AudioStreamOut::write`（只计数）。
- **验证：** 重注入后日志  
  `PCM count hooks: IN=1 OUT=1 (1=ok)`；`DobbyHook(...) rc=0`；进程未死。
- **空闲：** 已有稀疏 `count OUT`（例 `bytes=0`）；尚无 `count IN`。需媒体/通话对照。
- **待你做：** 空闲 / 放媒体 / **打电话** 各抓一段  
  `adb logcat -d -s AI_Audio_Hook:I`，看 `count IN` / `count OUT` 是否上涨。

---

## 2026-07-18 — 更正：journal 侦察条目重复

上文「maps / 符号侦察」与「maps / HAL 进程 / 符号」内容重叠，以 **「阶段 1.C：maps / 符号侦察 + 计数 Hook」** + **「datapath 计数 Hook 已装上」** 为准即可。

---

## 2026-07-18 — 通话对照：datapath 未命中热路径

- **现象：** 通话有 `AudioFocus`（`USAGE_VOICE_COMMUNICATION`，约 14:53:19 / 14:53:48）。
- **Hook：** 全程仅稀疏 `count OUT ... bytes=0`，**无** `count IN`；与通话音量不匹配。
- **对照：** `dumpsys media.audio_flinger` 显示 FastMixer `writeSequence`/`framesWritten` 很大 → 音频走了 **MonoPipe / FastMixer**，未经过我们挂的 `AudioStreamOut::write` 符号（疑似虚表调用）。
- **附注：** vendor HAL 进程加载的是 **32-bit** `audio.primary.kona.so`（`/vendor/lib/`），当前 arm64 载荷不能直接塞进该进程。
- **下一步：** 改挂 `libnbaio.so` 的 `MonoPipe::write` / `MonoPipeReader::read` 再打一通对照。

---

## 2026-07-18 — 第二通对照：MonoPipe 也不是通话 PCM

- **时间线（dumpsys audio）：**  
  `15:00:07 MODE_RINGTONE` → `15:00:09 MODE_IN_CALL` → `15:00:29 MODE_NORMAL`（约 20s）。
- **Hook：** `MonoPipe::write` / `MonoPipeReader::read` 已挂上（`write=1 read=1`），通话期间 **零** `count MonoPipe` 日志。
- **AudioFlinger：** 响铃/切听筒时 FastMixer 有约 `framesWritten≈227k`，随后 **threadLoop_standby**；真正通话段 AF 处于 standby。
- **结论：** 高通通话 PCM **不经过** `audioserver` 的 Mixer/MonoPipe；在 **`android.hardware.audio.service`（32-bit）** 的 `audio.primary.kona`（底层 `libtinyalsa` `pcm_write`/`pcm_read`）。
- **下一步（1.C Round-3）：** 编 **armeabi-v7a** 载荷 + 对 HAL 进程 ptrace `dlopen`；sepolicy 给 `hal_audio_default` `execmem`。

---

## 2026-07-18 — Round-3：32-bit HAL 注入实现

- **做了什么：**
  1. `audio_hook_hal.cpp`：Hook `libtinyalsa.so!pcm_write` / `pcm_read`（只计数）。
  2. `inject.cpp` 支持 arm32（EABI 栈传 mmap 第 5/6 参）；产出 `bin/inject32`。
  3. Magisk：`system/lib/libai_hook.so`（32）+ `system/lib64/...`（64）；`sepolicy` 增加 `hal_audio_default` execmem。
  4. `service.sh` 优先注入 `android.hardware.audio.service`。
- **真机：** 未 reboot 时 `/system/lib/libai_hook.so` 尚未 magic-mount；从 `/data/adb/modules/...` dlopen 返回 0（SELinux）。**需装 zip 后重启** 再验证。
- **验证（重启后）：**
  ```text
  adb shell 'su -c "grep libai_hook /proc/\$(pidof android.hardware.audio.service)/maps"'
  adb logcat -d -s AI_Audio_Hook:I
  ```
  期望：`HAL inject OK` / `HAL pcm hooks: write=1 read=1`，再打一通电话看 `count HAL pcm_*`。

---

## 2026-07-18 — 重启后通话：HAL 未进进程；linker 命名空间拒绝 /system/lib

- **通话：** `20:35:02 RINGTONE` → `20:35:04 IN_CALL` → `20:35:24 NORMAL`。
- **HAL：** overlay `/system/lib/libai_hook.so` 存在，但 **maps 无库**；手动注入 `dlopen => 0`。
- **根因：**  
  1. 先是 SELinux（`system_file` 不可读）；  
  2. 修 context 后 linker 报：`not accessible for the namespace`，`permitted_paths=/odm:/vendor:/system/vendor`（**不能**从 `/system/lib` 加载）。
- **audioserver：** MonoPipe 仍有 hit（含通话时段），但此前 standby 结论仍在——不能当作通话 PCM 证据。
- **修复：** 32-bit 载荷改放到 **`/vendor/lib/libai_hook.so`**（模块 `vendor/lib/`）；`service.sh` / sepolicy / post-fs-data 已改。需再装 zip **重启** 后验证。

---

## 2026-07-18 — HAL 注入打通；Dobby maps 解析崩溃已绕过

- **突破：** `/vendor/lib/libai_hook.so` 可 `dlopen`（linker namespace OK；label=`vendor_file`）。
- **崩溃：** 首版在 `DobbySymbolResolver` → `GetProcessModuleMap` **SIGBUS**（tombstone）。
- **修复：** HAL 载荷改用 `dlsym(libtinyalsa)` 取 `pcm_write`/`pcm_read`，再 `DobbyHook`。
- **验证：** `HAL pcm hooks: write=1 read=1`；maps 有库；进程不反复死。
- **待你：** 打一通电话，看 `count HAL pcm_write` / `pcm_read`。

---

## 2026-07-18 — 通话对照：pcm_write 有 hit，pcm_read 无；通话中段可能绕开 tinyalsa

- **时间：** `22:25:33 RINGTONE` → `22:25:36 IN_CALL` → `22:25:55 NORMAL`（~19s）。
- **现象：**
  - `pcm_write`：响铃/接通初大量 hit（`bytes=768`），至少到 `#1000`（~22:25:38）。
  - `pcm_read`：**全程 0 hit**。
  - `#1000` 之后未见更高档位日志 → 真正通话段可能不再走 `pcm_write`（高通 voice/CSD/compress voip 旁路）。
- **结论：** tinyalsa 能抓住**部分**下行（响铃/短暂），**不是**完整通话上下行。下一步要挖 `audio.primary.kona` 的 voice/`out_write`/`in_read` 或非 tinyalsa 路径。

---

## 2026-07-18 — multi-hook 通话：voice 命中，PCM I/O 仍旁路

- **载荷：** 在 tinyalsa 之外加挂 `pcm_mmap_*`、`libtinycompress` `compress_*`、`voice_start_call` / `voice_start_usecase` / `voice_stop_usecase`、`cin_read`；全部 `DobbyHook rc=0`。
- **通话时间线：**
  - `22:59:20` 起大量 `pcm_write`（`bytes=768`，至少到 `#1000`）
  - `22:59:21.822` `voice_start_call` → `voice_start_usecase usecase=41`
  - `22:59:24` 后未见更高 `pcm_write` 档位 → 通话中段无 tinyalsa 写入
  - `22:59:40` `voice_stop_usecase usecase=41`
- **零 hit：** `pcm_read` / `pcm_mmap_*` / `compress_write|read` / `cin_read`
- **结论：** CS voice 生命周期在 `audio.primary.kona`；双工 PCM **不**经 tinyalsa / tinycompress / cin。下一步挂 `compress_voip_{open,start,close}_*` 与 `start_{out,in}put_stream` 判定是否 VoIP/普通 stream，否则按 ADSP/modem 旁路改路。

---

## 2026-07-18 — Round-4：compress_voip 未走；确认蜂窝 CS/IMS 旁路

- **新增 Hook：** `compress_voip_{open,start,close}_{in,out}`、`start_{out,in}put_stream`、`audio_extn_cin_read`（均 rc=0）。
- **通话：** `23:24:07` `start_output_stream` + `pcm_write`（响铃）→ `23:24:09` `voice_start_* usecase=41` → `23:24:26` `voice_stop`；挂断后再 `start_output_stream`（`pcm_write #1500 bytes=3840`）。
- **零 hit：** 全部 `compress_voip_*`、`pcm_read`、mmap、`compress_*`、`cin_*`。
- **结论：** 与 SIM「菜单设置」基本无关；有 SIM 的蜂窝通话走 ADSP/modem，不会 magically 进 tinyalsa。完整 PCM 需 **incall-rec**（平台 XML：`INCALL_REC_*` → PCM **dev 23**）或其它导出。
- **Round-5：** `voice_start` 后主动 `pcm_open` 探测 d23/VoiceMMode/AFE-PROXY，并 Hook incall-rec 观察点。

---

## 2026-07-18 — Round-5：裸 pcm 探测 — d2 被占；d23 需路由；无非零 PCM

- **通话：** ~40s；`23:42:41` `voice_start_* usecase=41` → probe → 其后 `voice_stop`。
- **探测：**
  - **d23 (incall-rec)：** `pcm_open` READY，但 `pcm_read` 一律 `rc=-22 cannot prepare channel`（缺 HAL/mixer 建链）。
  - **d2 (VoiceMMode1)：** `Device or resource busy` → 通话 PCM 占在此设备，HAL 已打开。
  - **d15：** 同 d23，prepare 失败。
  - **d6 (AFE-PROXY TX)：** 可读 30 帧，**全零**（未接到通话）。
- **框架侧：** 全程无 `voice_check_and_set_incall_rec_usecase` / `cin_open` → 未走正式 incall-rec usecase。
- **附注：** `voice_set_parameters` 日志乱码 → 签名不是 `const char*`（疑为 `str_parms*`），下轮修正。
- **结论：** 裸 `pcm_open(23)` 不够；要么走完整 incall-rec（`start_input` + VOICE_CALL / `platform_set_incall_recording_session_id` + mixer），要么在 **已打开的 VoiceMMode 路径**上想办法（共享/复制流，不能再 open）。

---

## 2026-07-18 — Round-6 准备：VOC_REC mixer + session_id

- **路径：** `incall-rec-uplink` → `MultiMedia9 Mixer VOC_REC_UL=1`；DL 同理；UL+DL 组合两者。
- **API：** `platform_set_incall_recording_session_id(platform, vsid, INCALL_REC_UPLINK_AND_DOWNLINK=2)`；`VOICE_VSID` 默认 `0x10C01000`。
- **实现：** Hook `platform_start_voice_call` 抓 platform/vsid；`voice_start` 后开 mixer 并 `pcm_read` d23。

---

## 2026-07-18 — Round-6 突破：incall-rec d23 读到非零 PCM

- **通话：** ~20s；`23:51:03` voice_start usecase=41 → `platform_start_voice_call vsid=0x11c05000` → `23:51:23` voice_stop。
- **建链：** `platform_set_incall_recording_session_id rc=0`；`VOC_REC_UL/DL=1 rc=0`。
- **读取：** `pcm READY d23 48000Hz ch2`；`pcm_start rc=0`；**hits=100, nonzero_frames=96**。
- **清理：** hangup 时 UL/DL 置 0；无新 tombstone。
- **结论：** 蜂窝通话 PCM 可通过 **MultiMedia9 incall-rec** 导出。下轮：落盘校验是否为人声、改进 peak 统计、持续采集至挂断。

---

## 2026-07-19 — Round-7：双向确认 + 落盘听验

- **是否双向：** 是。`mode=INCALL_REC_UPLINK_AND_DOWNLINK(2)`，且同时打开 `VOC_REC_UL` + `VOC_REC_DL`；48k stereo 落盘，日志报 `maxL`/`maxR` 看左右能量（常为 UL/DL 分轨或混合）。
- **实现：** 通话全程 `pcm_read` → `/data/local/tmp/ai_incall.pcm`（fallback 模块目录）。
- **待测：** 再打一通，双方说话，拉回听验。

---

## 2026-07-19 — Round-7 通话：有真实电平；落盘 EACCES；L=R

- **时间：** `00:05:37` → `00:05:59`（~22s）；BIDIR UL+DL 建链成功。
- **电平：** `hits=2119 nz=1693`，**maxL=maxR=31354**（说话段明显抬升，像真人声而非底噪）。
- **声道：** 全程 **maxL == maxR** → 更像 **UL+DL 混合后双声道拷贝**，不是左上行/右下行分轨。
- **落盘失败：** 三处路径均 `errno=13`（`hal_audio_default` 无写权限）→ `dumped=0`。
- **下轮：** 预建可写文件/目录（正确 SELinux label）或经 UDS 把 PCM 送出 HAL。

---

## 2026-07-19 — Round-8：修 dump 权限

- **改动：** `sepolicy` 放行 `hal_audio_default` → `vendor_data_file`/`shell_data_file` 写；live `magiskpolicy`；预建 `/data/vendor/ai_hook/ai_incall.pcm`。
- **待测：** 再打一通，确认 `Round-8 dump fd=` 与 `dumped>0`，拉回听验。

---

## 2026-07-19 — Round-8 落盘成功并听验准备

- **dump：** `fd=64` → `/data/vendor/ai_hook/ai_incall.pcm`，**dumped=3062400**（~16s @ 48k stereo s16le）。
- **电平：** hits=1595 nz=1330，max=28977。
- **声道：** **L==R 100%** → UL+DL 已混合为单轨再复制成 stereo。
- **本机：** 已转 `tmp/ai_incall.wav` 可播放听验。

---

## 2026-07-19 — 听验确认：1.C 闭环

- **用户确认：** `ai_incall.wav` 即为双向通话录音。
- **分析：** ~16s，48kHz s16le，L==R 100%，max≈28977。
- **结论：** 阶段 **1.C 完成**。录音点≠注入点；下一阶段 **1.D UDS→Go**；对面可听另开 **1.E incall-music**。

---

<!-- 新条目模板（复制到文末填写）：

## YYYY-MM-DD — 标题

- **目标：**
- **做了什么：**
- **现象 / 日志：**
- **结论：**
- **相关代码 / 提交：**

-->
