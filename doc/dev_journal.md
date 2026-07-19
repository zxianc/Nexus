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

## 2026-07-19 — 开始 1.D：UDS 骨架

- HAL：`connect(/data/vendor/ai_hook/pcm.sock)` + APCM 头 + 推送 pcm 帧（与落盘并行）。
- Go：`daemon/pcm_recv` 监听并可选 dump。
- sepolicy：补充 `sock_file` / `unix_stream_socket`（shell↔hal 测试）。

---

## 2026-07-19 — 1.D 首通：落盘 OK，UDS errno=13

- **落盘：** `dumped=2926080`（~15s），max≈25594，incall-rec 仍正常。
- **UDS：** `uds=0`；`connect(...pcm.sock) errno=13`。`pcm_recv` 域为 `u:r:magisk:s0`，缺 `hal_audio_default ↔ magisk` unix_stream 放行。
- **已补：** live magiskpolicy + `sepolicy.rule` 增加 magisk peer；待再测一通。

---

## 2026-07-19 — 1.D 通过：HAL Server + pcm_recv Client

- **问题：** HAL `connect` magisk 域 socket → errno=13；补 peer allow 仍失败。
- **翻转：** HAL `bind/listen` `/data/vendor/ai_hook/pcm.sock`；Go `pcm_recv` 作 client 重连。
- **真机：** `uds hdr sent`；`dumped=3179520` **==** `uds=3179520`；`pcm_recv` `stream start 48000/2/16` 持续 recv；`uds_dump.pcm` 同大小。
- **结论：** 阶段 **1.D 完成**（PCM 旁路已进 Go）。下一步：Go daemon/STT，或并行 **1.E incall-music**。

## 2026-07-19 — 定稿：DL→STT + 软件合成存档

- **接听模式：** 全 AI / 全人互斥，无「AI 与人同时回复」。
- **AI 音频：** HAL **只采 DL** 给 STT；TTS 经 1.E 注入；存档 = Go **`mix(DL, TTS PCM)`**。
- **人模式听验：** 继续可用已验证的 UL+DL 混合落盘。
- **排除：** 硬件并行两路 incall-rec（DL + 混合）作存档。
- **文档：** `plan.md` → v1.9；`03_pcm_hook_next.md` / `README.md` 已同步。
- **下一步实现：** 1.D′ DL-only 推流 → 1.E TX → 1.F Go STT/合成。

## 2026-07-19 — 开始 1.D′：HAL DL-only

- **改动：** INCALL_REC_DOWNLINK + 仅 VOC_REC_DL；落盘 i_dl.pcm；APCM hdr[12]=kind（1=DL）。
- **部署：** 已 reinject；UDS listening + pcm_recv connected。
- **待测：** 打一通确认对方声、本机侧尽量无；日志 1.D DL DONE / kind=DL。

## 2026-07-19 — 1.D′ 真机：DL 流 OK，待听验是否仅对方

- **链路：** mode=DL；kind=DL(1)；dumped=uds=4041600（~21s）；max≈18724。
- **落盘：** i_dl.pcm / uds_dl.pcm；本机 wav：	mp/ai_dl.wav。
- **待确认：** 用户听验是否主要为对方声（本机侧应很少）。

## 2026-07-19 — 1.D′ 听验通过：仅对方（DL）

- **用户确认：** `ai_dl.wav` 只有对方声音，无本机侧。
- **结论：** 阶段 **1.D′ 完成**；STT 输入路径就绪。
- **下一步：** 1.E incall-music TX（TTS 对面可听），或 1.F Go STT/合成存档骨架。

## 2026-07-19 — 1.F 起步：本地 STT 管线（ai_call）

- **约束：** STT/TTS 本地；LLM 仅 DeepSeek（本步未接 LLM/TTS）。
- **实现：** `daemon/ai_call`：UDS DL → 16k mono → 能量 VAD → mock|sherpa-onnx SenseVoice CLI → `stt.log`。
- **资产：** 模型与 `sherpa-onnx-offline` 放 `/data/local/tmp/nexus_stt/`（不进 git）；见 `daemon/ai_call/README.md`。
- **验收：** `go test ./...` 通过；已交叉编译 `ai_call_arm64`；真机已部署 mock 并 `connected to HAL`。拨测后看 `stt.log`；sherpa 需另推模型+CLI。

## 2026-07-19 — 1.F mock 拨测通过

- **流：** kind=DL；约 10s recv。
- **VAD/STT：** stt.log 3 句 mock（800ms / 4560ms peak=7943 / 1980ms）。
- **结论：** 切句管线真机可用；下一步推 sherpa 出真字。

## 2026-07-19 — 1.F sherpa 真机听验通过

- **资产：** `/data/local/tmp/nexus_stt/`（模型 `sense-voice/`、`sherpa-onnx-offline`、`libonnxruntime.so`）；离线 `zh.wav` →「开饭时间早上九点至下午五点」。
- **启动：** `STT_BACKEND=sherpa` + `LD_LIBRARY_PATH=/data/local/tmp/nexus_stt`；`connected to HAL`；stream `kind=DL`。
- **stt.log（节选）：** `喂。` / `哎，你好，你的呃外卖已经放在门口了。` / `外卖放在门口。` / `听见吗？` / `OKOK好的。`
- **噪声：** 偶发仅 `。`（peak 偏低或静音切句）；下一步收紧 VAD / 过滤空识别。
- **结论：** 阶段 **1.F 真机 sherpa 听验完成**；DL→VAD→SenseVoice 端到端可用。下一：**VAD 调优** 或 **1.E TX**。

## 2026-07-19 — VAD 方案 A：空识别过滤 + MinSpeechMs

- **改动：**
  1. `hasSpeechText`：STT 结果无字母/数字（含 CJK）则 `DROP`，只写 `ai_call.log`，不进 `stt.log`。
  2. `DefaultVADConfig.MinSpeechMs`：300 → 500。
- **验收：** `go test ./...` 通过；已推真机 sherpa 重启。
- **待测：** 再打一通，确认真句仍进 `stt.log`，纯 `。` 被 DROP。

## 2026-07-19 — VAD 方案 A 拨测通过

- **stt.log（仅真句）：** `喂，你好。` / `哎，你这个。` / `外卖到了。` / `给你放在门口了。`
- **ai_call.log：** 2 条 `DROP text="。"`（peak≈464/575），未进 `stt.log`。
- **结论：** 空识别过滤有效；VAD 方案 A 完成。下一 **1.E TX**。

## 2026-07-19 — 文档：现行架构 / 线程模型

- **新增：** [`04_architecture_runtime.md`](04_architecture_runtime.md)（技术方案、数据流、HAL pthread + Go goroutine、Boot 维持）。
- **索引：** `doc/README.md` / `plan.md` 已挂链。

## 2026-07-19 — 文档：补充各部分作用与数据流向

- **改：** `04_architecture_runtime.md` §2「各部分作用」、§3「数据流向」——分层职责、输入/输出表、一图端到端、格式变换、路径清单、与目标全 AI 链路对照。

## 2026-07-19 — 可移植性：重装模块 v2.1 自动注入通过

- **做了什么：** `build.bat` 打出 `ai_audio_hook_zygisk.zip`（`module.prop` → **v2.1 / versionCode=3**）；Magisk 重装 → **重启**。
- **验证：** HAL pid maps 含 `/vendor/lib/libai_hook.so`；`/data/vendor/ai_hook/pcm.sock` 存在；`AI_Audio_Hook` 有日志。
- **Magisk 噪声：** `openat zygisk/armeabi-v7a.so: No such file`——zip 仅含 `zygisk/arm64-v8a.so`，32-bit 进程加载 Zygisk companion 时告警；**不影响** `service.sh`→`inject32`→HAL 主线。
- **结论：** 装模块 + 重启后注入与 UDS **可自动恢复**；`ai_call` 仍需手动启动。下一 **1.E TX**。

## 2026-07-19 — 文档：sherpa CLI 为 NDK 手编

- **结论：** 真机 `sherpa-onnx-offline` 由 Windows + NDK r30 交叉编译（sherpa **v1.13.4**，API **28** + ORT **1.27.0**）；模型用官方 SenseVoice int8 包。
- **新增：** [`05_sherpa_android_build.md`](05_sherpa_android_build.md)；脚本归入 `daemon/ai_call/scripts/`。

## 2026-07-19 — TODO：业务侧独立 Magisk 模块（暂不做）

- **决定：** sherpa / Go / 后续 TTS 等用户态资产 **不**塞进 `ai_audio_hook`；另开 Magisk 模块管理，与 HAL+UDS 解耦。
- **记在：** [`03_pcm_hook_next.md`](03_pcm_hook_next.md)、[`plan.md`](plan.md)。现行仍用 `/data/local/tmp/nexus_stt` 调试。

## 2026-07-19 — 定稿：HAL 只采集，用不用交给业务层

- **HAL：** 接通即采 DL（UDS+落盘）；不做按卡开关采集；暂不考虑省电关旁路。
- **业务：** 是否 AI/STT、双卡策略（接/拒/人工）均在 Go/策略服务。
- **文档：** `03_pcm_hook_next.md` / `plan.md` / `04_architecture_runtime.md` 已记。

## 2026-07-19 — 定稿：短信转发另模块 + Go 编排（暂不做）

- **不**做进 `ai_audio_hook`；短信独立模块/组件，Go 统一配置（含按卡策略），与 HAL/STT 解耦。
- **记在：** `03_pcm_hook_next.md` / `plan.md`。

## 2026-07-19 — 1.E 起步：incall-music TX 测试音（待对面听验）

- **侦察：** `audio_platform_info.xml`：`USECASE_INCALL_MUSIC_UPLINK` out **id=23**（`pcmC0D23p`）；mixer `Incall_Music Audio Mixer MultiMedia9`；与 DL 录音同号不同向（c/p）。
- **实现：** `tx_incall_music_thread`：`platform_start_incall_music_usecase` → mixer=1 → `pcm_open(0,23,PCM_OUT)` → 持续写 **880Hz** 正弦；挂断清理。
- **部署：** 已热更 `libai_hook.so` 并 reinject；日志见 `HAL 1.D'+1.E hooks done`。
- **待你：** 打一通，问对面是否听到连续蜂鸣；本机查 `1.E pcm READY` / `1.E pcm_write`。

## 2026-07-19 — 1.E 切片 A 听验通过：对面可闻 TX

- **用户确认：** 对面能听到一直「嘟」的测试音（880Hz）。
- **结论：** incall-music uplink（d23p + MultiMedia9）路径打通；**1.E 注入点成立**。
- **下一步：** 停掉常开测试音（改为按需/文件/UDS 灌 PCM）；接 TTS 或 Go→HAL 送语音。

## 2026-07-19 — 1.E：默认静音 + 文件按需注入

- **改动：** TX 线程默认写 **silence** 保活；不再常开蜂鸣。
- **按需：**
  - 把 raw PCM 推到 `/data/vendor/ai_hook/tx_inject.pcm`（须匹配 incall-music 格式，现多为 48k stereo s16le）→ 播完 unlink。
  - 调试蜂鸣：`touch /data/vendor/ai_hook/tx_tone`（存在即出 880Hz；删掉恢复静音）。
- **已部署** 新 so；样例 `tx_beep_48k_stereo.pcm` 在 `/data/vendor/ai_hook/`（复制为 `tx_inject.pcm` 可测）。
- **待验：** 通话中 `cp tx_beep_*.pcm tx_inject.pcm`，对面应听约 2s「嘟」后恢复安静。

## 2026-07-19 — 1.E 按需注入听验通过

- **格式：** incall-music 为 **48kHz mono**（`ch1`）；用 `tx_beep_48k_mono.pcm`。
- **真机：** 通话中写入 `tx_inject.pcm` → 日志 `loaded` / `drain done`；**用户确认对面听到约 2s 嘟**，随后安静。
- **结论：** 静音保活 + 文件按需 TX ✅。下一：Go/UDS 送 TTS PCM（或先接本地 TTS 引擎）。

## 2026-07-19 — 本地 TTS：sherpa VITS + ai_call -say

- **构建：** `build_sherpa_tts_api28.ps1` 产出 `sherpa-onnx-offline-tts`（piper-phonemize zip 需放 build 目录本地缓存，避免 FetchContent 断网）。
- **模型：** `sherpa-onnx-vits-zh-ll` → `/data/local/tmp/nexus_stt/vits-zh-ll/`。
- **Go：** `ai_call -say "…"` → VITS → 16k→48k mono → `tx_inject.pcm`；真机冒烟 OK（186KB PCM）。
- **待你：** 通话中再跑 `-say`，听对面中文。

## 2026-07-19 — TTS 通话听验：首次通过；二次无声=未再写入

- **用户确认：** 第一通对面听到女声「你好能听到嘛」。
- **日志：** 13:03:29 `loaded tx_inject.pcm bytes=186174` → `drain done`；`audible_periods=388`。
- **第二通无声：** 13:03:44 TX 起但无 `loaded`，`audible_periods=0`——文件已在首通播完 unlink，未再跑 `-say`。
- **结论：** TTS→TX 端到端 ✅；**每次播放都要重新 `-say`**（或再写 `tx_inject.pcm`）。
- **音色：** 由 VITS 模型 / `-tts-sid` 决定；换模型即可换声（已记入 plan / 03）。
- **下一：** STT→LLM→TTS 闭环（DeepSeek）；可选先做 STT echo TTS。

## 2026-07-19 — STT echo TTS（无 LLM）

- **改动：** `ai_call -echo-tts` / `ECHO_TTS=1`：STT 真句后串行 `speakTX` 同文注入。
- **待验：** 通话中开 sherpa+echo，对面应听到复述。

## 2026-07-19 — 常驻引擎方案 B（nexus_engine）

- **实现：** `daemon/nexus_engine`（STT+TTS 同进程常驻）+ `ai_call -backend engine`（UDS 行 JSON）。
- **构建：** `build_api28.ps1`；TLS 需 `-Wl,-z,max-page-size=16384`。
- **设备：** 冷加载 STT 1.6s + TTS 0.96s；RSS≈510MB；常驻后 TTS `ms≈0.9–1.1`（优于每句 exec CLI）。
- **现行：** echo 已用 `engine`；CLI `sherpa` 仍可回退。

## 2026-07-19 — Echo 听验：静音会挡 TX；常驻引擎已加速

- **现象：** 安卓通话「静音」时对面听不见复述/嘟；取消静音后可听（含蜂鸣前缀）。
- **原因：** 静音关的是**上行**（对面听你），听筒下行仍有声；incall-music 与麦同属上行，一并被挡。HAL 仍 `pcm_write`，mute 在更下游。
- **延迟：** 常驻 `nexus_engine` 后 STT `rt≈0.07–0.1s`（原 CLI≈1.9s）；体感明显快。
- **生命周期：** `nexus_engine` **跨通话常驻**（模型只加载一次）；非每通电话新起 STT/TTS 进程。`ai_call` 复用 `engine.sock`。

## 2026-07-19 — STT 语言改为 auto（改善英文识别）

- **原因：** 默认 `sense-voice-language=zh`，英文识别差。
- **改动：** 默认 / 启动脚本改为 `auto`；已重启 `nexus_engine`。
- **注意：** TTS 仍是中文 VITS `zh-ll`，英文词常被当 OOV 跳过——听英文复述要换英文/多语 TTS。

## 2026-07-19 — DeepSeek 流式 LLM（方案 A）

- **实现：** `llm` 包（SSE `/chat/completions`）+ 标点切句 + 逐句 TTS→TX；句间 `waitTXPlayed`（HAL 加载会清空队列）。
- **开关：** `-llm` / `LLM=1`；Key：`DEEPSEEK_API_KEY` 或 `/data/local/tmp/nexus_stt/deepseek.key`。
- **脚本：** `tmp/start_llm.sh`。
- **待验：** 通话勿静音；对面应听到 AI 回复（首句可有嘟）。

## 2026-07-19 — DeepSeek 闭环听验通过

- **现象：** STT→LLM→TTS 对面可听；例：「你的外卖到了。」→「好的，放在门口吧。」`llm ok rt≈3–4s`。
- **排障：** Android 上 Go 需自定义 DNS + 加载 `/system/etc/security/cacerts`（否则 lookup/[::1]:53、x509 unknown authority）。
- **嘟声：** 诊断蜂鸣默认关闭（`TX_BEEP_PREFIX=1` 可开）。

## 2026-07-19 — 按通话 LLM 上下文记忆

- **行为：** 一通电话内累积 user/assistant；请求带完整历史；挂断/新 stream 时 `Reset`。
- **上限：** 默认最近 24 条非 system（`-llm-max-msgs` / `LLM_MAX_MSGS`）。
- **竞态：** generation 令牌，避免上一通未完成的回复写进下一通。
- **为何 24 条：** 通内尽量记，但截断保护 token/延迟/费用；短通话通常用不满；跨通话不保留。

## 2026-07-19 — 通话文本存档落盘；企微/短信/语音 mix 延期

- **做了：** 挂断后等 in-flight → 对话全文 + DeepSeek 摘要 → `/data/vendor/ai_hook/calls/call_*.txt`。
- **TODO：** 语音 `mix(DL,TTS)` 延期；**企微推送与短信转发同一后续里程碑**（先落盘，后推送）。

## 2026-07-19 — 定稿：三模块命名 + Hook 更名

- **`nexus_audio_hook`：** 原 `ai_audio_hook` 仅改 Magisk id/包名（v2.2）；C++/so 逻辑不动；装前卸载旧模块。
- **`nexus_runtime` + `nexus_models`：** 程序与模型双包解耦（待实现）。
- **配置 UI：** 待选 APK / WebUI；可写目录拟 `/data/adb/nexus/`。

## 2026-07-19 — nexus_runtime + nexus_models 模块骨架

- **路径：** `magisk_modules/nexus_runtime`、`magisk_modules/nexus_models`（见该目录 README）。
- **runtime：** `service.sh` 开机起 engine+ai_call；配置 `/data/adb/nexus/env.sh`；bin/lib **打包前手工填入**（gitignore）。
- **models：** `models/sense-voice` + `vits-zh-ll` 打包前填入；与 runtime 分 zip。
- **未做：** 设置 UI；自动从设备拉资产进 zip 的一键脚本可后续加。

## 2026-07-19 — 通话打断 / 边听边答

- **问题：** 首版「每句 OK 都 cancel」会在 LLM 思考阶段被连说「喂」掐死，听不到 `say ok`。
- **现行：** `replyScheduler` — 启动防抖（`LLM_REPLY_DEBOUNCE_MS`，默认 600ms）；思考中新句排队下一轮；**仅 TTS 播放中**可真正打断（`interruptTX` + cancel）。
- **开关：** `LLM_BARGE_IN` / `-llm-barge-in`，**默认关**。开：播报中插话打断；关：播报中也只排队。设备改 `/data/adb/nexus/env.sh` 后重启 `service.sh`。
- **相关：** `daemon/ai_call/main.go`、`txinject.go`；说明见 `daemon/ai_call/README.md`、`magisk_modules/README.md`。

<!-- 新条目模板（复制到文末填写）：

## YYYY-MM-DD — 标题

- **目标：**
- **做了什么：**
- **现象 / 日志：**
- **结论：**
- **相关代码 / 提交：**

-->
