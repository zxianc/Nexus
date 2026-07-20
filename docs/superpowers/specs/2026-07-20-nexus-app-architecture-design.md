# Nexus 全新架构设计方案：Zygisk 旁路 + 纯 Android Native App 闭环

**日期：** 2026-07-20  
**修订：** 2026-07-21（按架构评审修订）  
**状态：** Draft（产品项已拍板；实现计划见 `docs/superpowers/plans/2026-07-20-nexus-app-architecture.md`）  
**特性分支：** `nexus-app-architecture`

---

## 1. 目标与重构背景

### 1.1 现状与痛点

现有 Nexus 框架（v2.2）采用 **「3 个 Magisk 模块 + 5 个后台 Go 进程」** 的重型架构，核心痛点：

* **后台保活艰难：** Android 对普通后台 ELF（`ai_call`、`nexus_notify` 等）限制严苛，易被 LMK 强杀。
* **配置控制面复杂：** 依赖本机浏览器打开 `127.0.0.1:8787` WebUI。
* **通话控制脆弱：** Go 侧靠 `telephony.registry` + 模拟按键接听，兼容性差。
* **静音冲突（致命）：** 系统 `AudioManager` 静麦会在硬件侧截断整路上行，AI 注入的 `Incall_Music` 一并消失。现行总览仍将「AI 接听静麦保 TX」标为 TODO。
* **I/O 磨损：** 上行注入依赖 `/data/vendor/ai_hook/tx_inject.pcm` 的 stat/unlink 轮询。

### 1.2 破局方案：全功能 App 化

保持底层 **极简 Zygisk 音频 Hook**，将上层业务（ASR、TTS、LLM、接听策略、通知、配置）迁入 **Kotlin Android App**：

* **Zygisk 只负责旁路：** 抓取通话下行 PCM；接收 App 帧化上行 PCM 注入；按控制帧在 HAL 层对 **通话上行 mic** 做软静音。
* **App 负责业务：** 通话接管、短信/通知、Foreground Service、本地 `sherpa-onnx`、DeepSeek、Settings UI。

**非目标（本阶段不做）：** 跨机型 HAL 抽象、非高通路径、云端 ASR/TTS 主路径。

---

## 2. 演进架构对比

### 2.1 原 Go 守护进程架构

```text
+─────────────────────────────────────────────────────────────────────────+
│                          Magisk (root env)                              │
│                                                                         │
│  +─────────────────────────+       +──────────────────────────────────+ │
│  │    nexus_audio_hook     │       │          nexus_runtime           │ │
│  │  (Zygisk / HAL Hook)    │       │   (Go Daemons + WebUI Server)    │ │
│  +────────────┬────────────+       +─────────────────▲────────────────+ │
│               │                              │                 │       │
│   pcm.sock    │ (下行 PCM，单向)               │ 模拟按键接听      │ HTTP  │
│               ▼                              │                 ▼       │
│         +─────┴──────────+            +──────┴─────+     +─────┴─────+ │
│         │    ai_call     │ ◄────────► │callpolicy  │     │   WebUI   │ │
│         +─────┬──────────+            +────────────+     │ (8787)    │ │
│               ▼                                                        │
│         +─────┴──────────+                                             │
│         │  nexus_engine  │                                             │
│         +────────────────+                                             │
│  上行：ai_call → tx_inject.pcm → HAL 轮询注入                            │
+─────────────────────────────────────────────────────────────────────────+
```

### 2.2 新 Android App 闭环架构

```text
+─────────────────────────────────────────────────────────────────────────+
│              Zygisk Audio Bypass (nexus_audio_hook SO)                  │
│   - DL PCM → 帧化写入 UDS                                               │
│   - 从 UDS 读上行 PCM 帧 → Incall_Music                                  │
│   - CTRL 帧：软静音 / flush / 会话同步（见 §3）                            │
│   - 软静音仅作用于已识别的通话 UL pcm 句柄                                 │
+─────────────────────────────────▲───────────────────────────────────────+
                                  │
                    帧化双工 UDS (pcm.sock)
                    App 可达性见 §3.5（待验证）
                                  │
+─────────────────────────────────▼───────────────────────────────────────+
│                      Nexus Call Assistant App                           │
│  Foreground Service：通话期持有桥接；闲时可断开 UDS、可卸模型               │
│       │ DL PCM              ▲ UL PCM / CTRL                             │
│       ▼                     │                                           │
│  Resample→mono→STT    TTS→resample→帧发送                                │
│  (sherpa-onnx)         (sherpa-onnx)                                    │
│       │                     ▲                                           │
│       └──────── LLM ────────┘                                           │
│  InCallService │ Settings / 配置真源 │ SMS + 企微通知 │ 通话文字存档       │
+─────────────────────────────────────────────────────────────────────────+
```

---

## 3. 双工 UDS 帧协议

全局 UDS 服务端路径（与现网一致）：`/data/vendor/ai_hook/pcm.sock`。  
**禁止**在裸 PCM 流上用魔法字节嗅探控制命令（避免 `0xCC` 与合法 PCM 碰撞）。

### 3.1 APCM 握手头（与现网对齐）

客户端 `connect` 成功后，**不保证立刻有头**。现网行为：HAL 在下行 `pcm_read` 开始推流时发送一次 16 字节 APCM 头；重连后 `g_uds_hdr_sent` 复位，下一通/下一次推流再发。

布局与 `audio_hook_hal.cpp` / `daemon/ai_call/uds.go` 一致：

```text
0               4               8              10              12              14
+---------------+---------------+---------------+---------------+---------------+
| APCM_MAGIC    | Sample Rate   | Channels      | Bits (16)     | Kind (u16)    |
| 0x4D435041 LE | u32 LE        | u16 LE        | u16 LE        | 见下表         |
+---------------+---------------+---------------+---------------+---------------+
| pad u16 = 0   |
+---------------+
```

| Kind | 值 | 含义 |
| :--- | :--- | :--- |
| `APCM_KIND_MIXED` | 0 | 混合（保留） |
| `APCM_KIND_DL` | 1 | 下行（对方→本机，现网主路径） |
| `APCM_KIND_UL` | 2 | 上行（保留） |

**重要：** 握手中的 rate/channels 以 **HAL 实际旁路格式**为准（现网常见 **48kHz / stereo / S16LE**），**不是**写死 16k mono。App 必须按头字段做重采样与 mono 下混后再喂 ASR；TTS 输出再按头字段（或单独约定的注入格式）上采样后帧发送。

### 3.2 帧封装（双向统一）

APCM 头之后的所有载荷（含 App→HAL 的上行与控制、以及可选的 HAL→App 控制）使用统一帧：

```text
offset 0: type   u8
offset 1: flags  u8   (保留，默认 0)
offset 2: length u16 LE  = payload 字节数
offset 4: payload[length]
```

| type | 方向 | payload | 说明 |
| :--- | :--- | :--- | :--- |
| `0x01` PCM_DL | HAL→App | raw S16LE PCM | 与 APCM 头声明的 rate/ch 一致；建议 period 对齐现网 write 块大小 |
| `0x02` PCM_UL | App→HAL | raw S16LE PCM | 注入 Incall_Music；格式须与播放侧配置一致（实现阶段与 HAL 对齐并写测试向量） |
| `0x10` CTRL_MUTE | App→HAL | 1 byte：`0` 关 / `1` 开 | 软静音开关 |
| `0x11` CTRL_FLUSH_UL | App→HAL | 空 | 清空 `TxInjectQ`，后续静音 keepalive |
| `0x12` CTRL_SESSION | App→HAL | 1 byte：`1` AI 会话开始 / `0` 结束 | 供 HAL 日志/状态；断连视为结束并 mute=false |
| `0x1F` CTRL_ACK | HAL→App | 回显 type + status | 可选；MVP 可不实现，但协议位保留 |

**解析规则：**

* 收端按 4 字节头组帧；`length` 超上限（建议硬顶 64 KiB）则断连。
* 未知 `type`：忽略 payload，保持连接（便于向前兼容）。
* 单连接同时：HAL 写线程只发 `PCM_DL`（+ 可选 ACK）；App 写线程只发 `PCM_UL` + CTRL_*。同一 fd 上 **读/写并发允许**，但各方各自单生产者。

### 3.3 Zygisk 双工行为

* **DL：** `pcm_read`（incall-rec 下行）→ 封装 `PCM_DL` → `write(client_fd, …)`（现网非阻塞语义保留：失败则踢客户端）。
* **UL：** 注入线程从 fd 组帧读取；`PCM_UL` 入 `TxInjectQ`；无数据时 0 填充 keepalive（与现 `round7_incall_thread` 一致）。
* **废弃** `tx_inject.pcm` 的 stat/unlink 路径（可保留编译开关一个版本作回滚）。
* **多客户端：** 保持现网策略——新 `accept` CAS 替换并关闭旧 fd；App 与过渡期 Go 客户端勿并行抢连。

### 3.4 连接生命周期（纠正原方案矛盾）

| 阶段 | App | Zygisk |
| :--- | :--- | :--- |
| 开机 / 闲时 | FGS 可常驻，**默认不连** UDS（或短连探测后断开） | `uds_server_thread` listen |
| `Call.STATE_ACTIVE` 且策略为 AI | `connect` → 等待 APCM 头 → 读 `PCM_DL` / 写 `PCM_UL`+MUTE | accept；推流时发头 |
| 切回人工 | `CTRL_MUTE=0`，可停 TTS、`CTRL_FLUSH_UL` | mute 关，队列可 flush |
| `DISCONNECTED` / 崩溃断连 | 关 socket；卸模型；写存档/通知 | 关 client；`g_ai_mute_mic=false`；清队列 |

### 3.5 App 沙箱可达性（阻塞项，必须先冒烟）

现网客户端为 root/adb 域进程；`chmod 0666` 只解决 DAC。`untrusted_app` 连接 `/data/vendor/ai_hook/pcm.sock` **很可能被 SELinux 拒绝**。

**推荐默认（实现前验证，失败则换备选）：**

1. Magisk `post-fs-data` / sepolicy 补丁：允许 App 域 `connect` 到该 sock（或放宽 `ai_hook` 目录类型给目标包名）。
2. 冒烟用例（OnePlus 8T / 现行 LineageOS+Magisk）：安装 debug App → `LocalSocket` connect → 读到合法 APCM → 回显一帧 `PCM_UL` 静音。

**备选（验证失败时启用，见 §8）：** abstract namespace UDS、或极薄 root companion 经 Binder 转发 PCM（尽量避免）。

---

## 4. Android App 核心设计

### 4.1 Foreground Service

* 用途：通话期保活 + UDS 桥 + 推理生命周期。
* 通知类型：优先 `FOREGROUND_SERVICE_TYPE_PHONE_CALL`（以目标 SDK 合规为准）。
* **不**在服务启动时无条件死循环占线 UDS；连接时机见 §3.4。
* 断线重试：仅在「应处于 AI 通话会话」时 2s 退避重连。

### 4.2 InCallService 与默认拨号器（已拍板：方案 A）

**决策：** 成为默认电话应用，用 `InCallService` 正规接听/挂断；**先做最小通话 UI，后续再完善该模块视觉与交互。**  
不采用「不占默认拨号 + 模拟按键/钩 Telephony」作为主路径。

#### MVP 通话 UI 范围（最小实现）

| 界面 | MVP 必须 | 可后续完善 |
| :--- | :--- | :--- |
| 拨号页 | 处理 `DIAL`/`tel:`：号码框 + 呼叫（满足 `ROLE_DIALER`） | 通讯录、通话记录、智能拨号 |
| 来电页 | 号码/卡槽、接听、拒接；策略 `ai` 时可自动 `answer()` | 精美全屏动效、头像/归属地 |
| 通话中页 | 挂断、**AI / 人工**切换 | 免提/通讯录/会议/转接等 |
| 设置 | 双卡 `human` / `ai` / `reject`；引导授予默认电话 | 向导抛光、多语言文案 |

用户拒绝授予默认电话时：**AI 自动接听不可用**，Settings 内明确提示并提供「去设置」入口（不做侧门接听兜底）。

AI 接通后：

1. 通知 FGS：`SESSION_START` → connect UDS；
2. 发送 `CTRL_MUTE=1`（**不要**调 `AudioManager.setMicrophoneMute` 作为主路径）；
3. 挂断或切人工：`CTRL_MUTE=0` + `CTRL_FLUSH_UL` + `SESSION_END`。

### 4.3 音频管线（App 内）

```text
PCM_DL (HAL rate/ch) → stereo→mono → resample→16k → VAD → ASR
ASR 文本 → LLM（DeepSeek，通话上下文）→ 回复文本
回复 → TTS → resample→注入格式 → PCM_UL 帧
```

### 4.4 本地推理（sherpa-onnx）

* 依赖：官方 AAR（版本在实现计划锁定；以文档为准，**不写死错误 Online/Offline API**）。
* SenseVoice：按 sherpa-onnx 对应该模型的 **Offline / 非流式** API 接入（若后续换流式模型再改 Online）。
* VITS TTS：`OfflineTts` 路径；支持分句流式送入 `PCM_UL`。
* 模型目录：SAF 自选目录（持久 URI 权限）和/或 `/data/adb/nexus/models`（需可读策略）；通话结束可卸模型降内存。

### 4.5 配置真源与 v2.2 功能对等

废除 WebUI 后，配置真源改为 App 私有存储（可导出 JSON 备份）。须对等覆盖：

| 能力 | v2.2 | App 目标 |
| :--- | :--- | :--- |
| 双卡策略热读 | `config.json` + callpolicy | Settings + 内存热更新 |
| LLM/STT/TTS 参数 | config + 可能重启进程 | Settings；改模型路径则重载引擎 |
| 企微 Webhook | nexus_notify | App 内推送模块 |
| 通话文字存档 | `call_*.txt` + `.notify` | **App 自管存储**（见 §4.6）；不兼容旧 vendor 路径 |
| 短信转发 + cursor 水位 | notify + inbox | `READ_SMS` + ContentObserver + 水位持久化 |
| 语音存档（含 mix） | 原 TODO / 未做 | MVP 可只留文字；目录布局预留音频，见 §4.6 |

### 4.6 通话存档与语音数据（已拍板：App 自管，不兼容旧路径）

**决策：** 彻底重构，**不再写入** `/data/vendor/ai_hook/calls`。存档以方便 App 读写、后续扩语音为准。

**默认根目录：** 应用专属外部目录（如 `context.getExternalFilesDir("nexus_calls")`）——通常无需额外存储运行时权限，卸载可随 App 清理；调试可用 `adb` 从包路径拉取。

**可选：** Settings 用 SAF 自选「存档根目录」（大容量语音、方便拷到电脑时）。选中后持久化 URI 权限。

**建议目录布局（文字 MVP + 语音预留）：**

```text
{archive_root}/
  calls/
    2026-07-21_143022_slot0/     # 一通一目录，便于后续加音频
      meta.json                  # 卡槽、号码、策略、起止时间
      transcript.txt             # 对话文字（对等原 call_*.txt）
      summary.txt                # 可选摘要
      audio/                     # MVP 可空；后续再写
        dl.pcm | dl.wav          # 对方下行（可选）
        ul_tts.pcm | ul_tts.wav  # AI 注入（可选）
        mix.wav                  # 对轨/混音成片（后续）
  notify_queue/                  # 待推企微等（替代 .notify 文件约定）
```

**原则：**

* MVP：先保证 `meta.json` + `transcript` + 通知队列；`audio/` 目录创建即可，不阻塞上线。
* 语音落地时：优先写 App 管得到的路径；注意通话期磁盘吞吐，可先落 PCM 再异步转码。
* 配置/模型路径与存档根分离：模型仍可用 SAF 或 `/data/adb/nexus/models`（只读权重）；存档走本节。

---

## 5. Zygisk 软静音（Soft Mute）

在 `fake_pcm_read` 路径增加 `g_ai_mute_mic`，但 **禁止对所有 pcm_read 一刀切 memset**。

### 5.1 门控规则

仅当同时满足时清零：

1. `g_ai_mute_mic == true`；
2. 当前 `pcm*` 是已识别的 **通话上行 / 会进入对方耳麦的 mic 路径**（实现阶段结合现有 incall / voice 句柄跟踪；对无法识别的句柄默认不 mute，并打日志）。

伪代码：

```cpp
static std::atomic<bool> g_ai_mute_mic{false};

static int fake_pcm_read(void *pcm, void *data, unsigned int count) {
    int rc = orig_pcm_read(pcm, data, count);
    if (g_ai_mute_mic.load() && rc >= 0 && data && is_voice_ul_mic(pcm)) {
        memset(data, 0, count);
    }
    return rc;
}
```

`CTRL_MUTE` / 客户端断连时更新原子变量；断连强制 `false`。

### 5.2 预期收益

* 不碰系统全局 mute，Incall_Music TX 可继续；
* AI 期对方只听 TTS；切人工立即恢复真 mic，无需重建通话音频会话。

### 5.3 验收

* AI 接听：对方听清 TTS、无环境音；
* 切人工：对方听到真 mic；
* 普通非通话录音 / 语音消息：行为与改造前一致（无误伤）。

---

## 6. 迁移路径（并行，禁止大爆炸）

```text
M0  协议与冒烟：帧协议 + App 连 UDS 成功（§3.5）
M1  HAL：双工 UL 帧注入 + 软静音门控；保留 tx_inject 回滚开关
M2  过渡：Go ai_call 仍可读 DL（若需临时兼容裸流，仅过渡分支）；或 App 并行读流做 STT 对照
M3  App MVP：InCallService + 策略 + LLM + sherpa + 通知/存档
M4  功能对等验收通过后，再停 nexus_runtime 开机脚本并删除 Go 守护
M5  清理：移除 tx_inject 主路径、WebUI、旧 config 热读进程
```

**明确禁止：** 在 App 未通过 §4.5 对等验收前删除 `daemon/*` 或停掉 `nexus_runtime`。

模块收敛目标（终态）：

* 保留：`nexus_audio_hook`（Zygisk）、`nexus_models`（或改由 App/SAF 管理模型）；
* 移除：`nexus_runtime` Go 守护与 8787 WebUI。

---

## 7. 风险登记与待验证项

| ID | 风险 | 状态 | 缓解 |
| :--- | :--- | :--- | :--- |
| R1 | App SELinux 连不上 pcm.sock | **未验证** | §3.5 冒烟；失败按 §8.1 备选顺序 |
| R2 | 默认拨号器 UX 简陋被吐槽 | **已决策走最小 UI** | §4.2 MVP；后续迭代抛光，不阻塞主路径 |
| R3 | 软静音误伤非通话 mic | **未实现** | 句柄门控 + §5.3 |
| R4 | 帧协议与 period 不齐导致卡顿/欠载 | **未实现** | 测试向量 + 队列水位 |
| R5 | sherpa AAR API/包体积/性能 | **未锁定版本** | M3 前选型 spike |
| R6 | FGS 类型与后台限制（Android 14+） | **待查目标 API** | 选对 fgsType；通话外卸模型 |
| R7 | 过渡期 App 与 ai_call 抢同一 sock | 已知 | 互斥；M2 单客户端 |

原「已验证、无不可行点」表述撤销。本方案为可执行设计草稿，以上项关闭前不宣称完工。

---

## 8. 已拍板 / 待拍板

### 8.1 已拍板

* **默认拨号器：方案 A** — 最小默认电话应用 + `InCallService`；UI 先 MVP、后抛光。详见 §4.2。  
  明确不做：不占默认拨号的模拟按键/无障碍接听作为主路径。
* **存档位置：App 自管** — 默认 `getExternalFilesDir("nexus_calls")`，可选 SAF；不兼容 `/data/vendor/ai_hook/calls`。目录按「一通一目录」预留 `audio/`。详见 §4.6。
* **UDS 可达性失败时的备选顺序：** sepolicy 扩展 → abstract namespace UDS → root companion + Binder（最后手段）。主路径仍先做 §3.5 冒烟。

### 8.2 待拍板

* 当前无。若 §3.5 冒烟失败需在三种备选间做机型特例，再开讨论。

---

## 9. 自检（修订后）

* 无「已验证完毕」类结案表述；风险见 §7。
* APCM 头与现网 `kind` 字段一致；采样率以握手为准。
* 控制面为长度前缀帧，无裸流魔法字节。
* 迁移为 M0–M5 并行路径，禁止先删 Go。
* 软静音带句柄门控与验收。
* 产品拍板：通话 UI A、存档 App 自管、UDS 备选顺序，见 §8.1。
