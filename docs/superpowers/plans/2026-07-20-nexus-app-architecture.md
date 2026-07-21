# Nexus App 架构重构 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将业务迁入 Kotlin App（最小默认电话 UI + FGS + sherpa + LLM + 存档/通知），Zygisk 仅保留帧化双工 PCM 旁路与软静音；并行迁移，通过验收前不删 `nexus_runtime`。

**Architecture:** HAL `pcm.sock` 先发 16B APCM 头，之后双向使用 `type|flags|len|payload` 帧；App 仅在 AI 通话 ACTIVE 时连接 UDS。`InCallService` + `ROLE_DIALER` 正规接听；软静音门控通话 UL mic。存档写 App `getExternalFilesDir("nexus_calls")`。

**Tech Stack:** C++17（`zygisk_module/cpp/audio_hook_hal.cpp`）、Kotlin + Android SDK（minSdk 以机型 LineageOS 为准，建议 29+；target 34）、JUnit4（JVM 协议单测）、sherpa-onnx AAR、OkHttp/HttpURLConnection（DeepSeek）、Magisk `magiskpolicy --live`。

**Spec:** [`docs/superpowers/specs/2026-07-20-nexus-app-architecture-design.md`](../specs/2026-07-20-nexus-app-architecture-design.md)

## Global Constraints

- UDS 路径：`/data/vendor/ai_hook/pcm.sock`（与现网一致）
- APCM：`magic=0x4D435041` LE，含 `kind`（现网 offset 12）；**禁止**把 offset 12 当 Reserved
- 帧：`u8 type | u8 flags | u16 LE length | payload`；`length` 硬顶 **65535** 内再限 **64 KiB**；**禁止**裸 PCM 上 `0xCC` 嗅探
- 帧 type：`0x01 PCM_DL`、`0x02 PCM_UL`、`0x10 CTRL_MUTE`、`0x11 CTRL_FLUSH_UL`、`0x12 CTRL_SESSION`（ACK `0x1F` 可后做）
- 采样率以 APCM 头为准（常见 48k stereo）；App 内再转 16k mono 给 ASR
- 软静音：**禁止**对所有 `pcm_read` memset；须 `is_voice_ul_mic(pcm)` 门控
- 默认电话方案 A：MVP 拨号/来电/通话中；拒绝授权则 AI 接听不可用
- 存档：App 自管，**不写** `/data/vendor/ai_hook/calls`；一通一目录，预留 `audio/`
- **禁止**在 §4.5 对等验收前删除 `daemon/*` 或停掉 `nexus_runtime` 开机脚本
- 过渡期 App 与 `ai_call` **勿同时**连同一 `pcm.sock`
- UDS 可达失败备选顺序：sepolicy → abstract UDS → root companion（最后手段）
- 机型基线：OnePlus 8T / LineageOS + Magisk（与 `doc/00_framework_overview.md` 一致）

## Scope / phase gates

本计划覆盖 spec M0–M5，体量大但强依赖串行。执行时按门禁推进，未过门禁不进入下一阶段：

| Gate | 完成条件 |
|------|----------|
| **G0** | JVM 帧协议单测绿；HAL 能发/收帧（可用 root/`ai_call` 过渡客户端验证） |
| **G1** | Debug App `connect` → 读 APCM → 发一帧静音 `PCM_UL` 成功（真机） |
| **G2** | AI 通话：软静音 + TTS 对方可闻；切人工 mic 恢复；非通话录音无误伤 |
| **G3** | App MVP：策略接听、STT→LLM→TTS、文字存档、企微/短信通知 |
| **G4** | 停 `nexus_runtime` 守护并删 Go 前，G3 验收签字 |

若只需先打通音频旁路，完成 Task 1–6 即可暂停。

---

## File map

| Path | Responsibility |
|------|----------------|
| `zygisk_module/cpp/pcm_frame.h` | 帧 encode/decode、APCM 常量（HAL 共用） |
| `zygisk_module/cpp/audio_hook_hal.cpp` | 帧化 DL/UL、CTRL、软静音门控；`tx_inject` 编译开关回滚 |
| `zygisk_module/post-fs-data.sh` | 增加 `untrusted_app`→sock 的 `magiskpolicy` |
| `tools/pcm_frame_host_test/` 或 `zygisk_module/cpp/pcm_frame_test.cpp` | 可选宿主机 C++ 帧测试（若环境有 g++）；主单测放 App JVM |
| `nexus_app/` | Android 工程根 |
| `nexus_app/app/src/main/java/com/nexus/assistant/protocol/` | APCM + 帧编解码（与 C++ 对齐） |
| `nexus_app/app/src/test/java/.../protocol/` | JVM 单测 |
| `nexus_app/.../uds/PcmSocketClient.kt` | LocalSocket 双工桥 |
| `nexus_app/.../service/NexusBypassService.kt` | FGS + 会话期连接 |
| `nexus_app/.../telecom/*` | InCallService、Dialer/Incoming/InCall Activity |
| `nexus_app/.../audio/*` | resample、VAD、桥到 STT/TTS |
| `nexus_app/.../ai/*` | sherpa 封装、DeepSeek、通话状态机 |
| `nexus_app/.../config/NexusConfig.kt` | DataStore/JSON 配置真源 |
| `nexus_app/.../archive/CallArchive.kt` | 一通一目录文字存档 |
| `nexus_app/.../notify/*` | 企微 Webhook、短信 Observer |
| `nexus_app/.../ui/settings/*` | 策略/API/模型/默认电话引导；**Nexus 接管开关** |
| `nexus_app/.../telecom/DialerTakeover.kt` | 系统电话 ↔ Nexus 可逆切换（root：enable/disable 系统 Dialer ICS） |
| `doc/00_framework_overview.md` | G4 后更新架构说明（勿在 M0 改） |

---

### Task 1: 帧协议 JVM 单测 + Kotlin 编解码

**Files:**
- Create: `nexus_app/settings.gradle.kts`
- Create: `nexus_app/build.gradle.kts`
- Create: `nexus_app/gradle.properties`
- Create: `nexus_app/app/build.gradle.kts`
- Create: `nexus_app/app/src/main/java/com/nexus/assistant/protocol/PcmProtocol.kt`
- Create: `nexus_app/app/src/test/java/com/nexus/assistant/protocol/PcmProtocolTest.kt`

**Interfaces:**
- Produces:
  - `object PcmProtocol` with constants matching spec
  - `data class ApcmHeader(val rate: Int, val channels: Int, val bits: Int, val kind: Int)`
  - `fun parseApcmHeader(buf: ByteArray): ApcmHeader` — requires `buf.size >= 16`
  - `sealed class PcmFrame` with `PcmDl`, `PcmUl`, `CtrlMute(on: Boolean)`, `CtrlFlushUl`, `CtrlSession(start: Boolean)`, `Unknown(type: Int, payload: ByteArray)`
  - `fun encodeFrame(type: Int, payload: ByteArray, flags: Int = 0): ByteArray`
  - `class FrameReader` — `fun feed(data: ByteArray): List<PcmFrame>` 流式组帧；超长断流抛 `ProtocolException`

- [ ] **Step 1: 脚手架最小 Android 库可跑 unitTest**

```kotlin
// nexus_app/settings.gradle.kts
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "NexusAssistant"
include(":app")
```

```kotlin
// nexus_app/app/build.gradle.kts（节选）
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.nexus.assistant"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.nexus.assistant"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0-mvp"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    testImplementation("junit:junit:4.13.2")
}
```

根 `build.gradle.kts` 使用与 AGP 8.x 匹配的 plugins 版本（以本机 Android Studio 建议为准，例如 AGP 8.5.x + Kotlin 1.9.x）。

- [ ] **Step 2: 写失败单测**

```kotlin
package com.nexus.assistant.protocol

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PcmProtocolTest {
    @Test
    fun parseApcmHeader_matchesHalLayout() {
        val buf = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(0x4D435041)
        buf.putInt(48000)
        buf.putShort(2)
        buf.putShort(16)
        buf.putShort(1) // DL
        buf.putShort(0)
        val h = PcmProtocol.parseApcmHeader(buf.array())
        assertEquals(48000, h.rate)
        assertEquals(2, h.channels)
        assertEquals(16, h.bits)
        assertEquals(1, h.kind)
    }

    @Test
    fun encodeDecode_ctrlMute_roundTrip() {
        val raw = PcmProtocol.encodeFrame(PcmProtocol.TYPE_CTRL_MUTE, byteArrayOf(1))
        val frames = FrameReader().feed(raw)
        assertEquals(1, frames.size)
        val m = frames[0] as PcmFrame.CtrlMute
        assertTrue(m.on)
    }

    @Test
    fun feed_splitsAcrossChunks() {
        val payload = ByteArray(100) { it.toByte() }
        val full = PcmProtocol.encodeFrame(PcmProtocol.TYPE_PCM_DL, payload)
        val r = FrameReader()
        assertTrue(r.feed(full.copyOfRange(0, 3)).isEmpty())
        val rest = r.feed(full.copyOfRange(3, full.size))
        assertEquals(1, rest.size)
        assertArrayEquals(payload, (rest[0] as PcmFrame.PcmDl).pcm)
    }

    @Test
    fun lengthOverCap_throws() {
        val hdr = byteArrayOf(0x01, 0, 0xFF.toByte(), 0xFF.toByte()) // len=65535 ok header
        // Construct length = 65536 via manual bytes if encode rejects — expect ProtocolException on assemble
        val bad = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        // Use reader internal: feed type=1 flags=0 len=70000 if API allows; otherwise test encodeFrame rejects > 65535
        try {
            PcmProtocol.encodeFrame(1, ByteArray(70_000))
            fail("expected ProtocolException")
        } catch (e: ProtocolException) {
            // ok
        }
    }
}
```

- [ ] **Step 3: 运行确认失败**

```bash
cd nexus_app
./gradlew :app:testDebugUnitTest --tests com.nexus.assistant.protocol.PcmProtocolTest
```

Expected: FAIL（类不存在或编译失败）

- [ ] **Step 4: 实现 `PcmProtocol.kt` / `FrameReader`**

```kotlin
package com.nexus.assistant.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

class ProtocolException(msg: String) : Exception(msg)

data class ApcmHeader(val rate: Int, val channels: Int, val bits: Int, val kind: Int)

sealed class PcmFrame {
    data class PcmDl(val pcm: ByteArray) : PcmFrame()
    data class PcmUl(val pcm: ByteArray) : PcmFrame()
    data class CtrlMute(val on: Boolean) : PcmFrame()
    data object CtrlFlushUl : PcmFrame()
    data class CtrlSession(val start: Boolean) : PcmFrame()
    data class Unknown(val type: Int, val payload: ByteArray) : PcmFrame()
}

object PcmProtocol {
    const val APCM_MAGIC = 0x4D435041
    const val TYPE_PCM_DL = 0x01
    const val TYPE_PCM_UL = 0x02
    const val TYPE_CTRL_MUTE = 0x10
    const val TYPE_CTRL_FLUSH_UL = 0x11
    const val TYPE_CTRL_SESSION = 0x12
    const val MAX_PAYLOAD = 64 * 1024

    fun parseApcmHeader(buf: ByteArray): ApcmHeader {
        require(buf.size >= 16)
        val bb = ByteBuffer.wrap(buf, 0, 16).order(ByteOrder.LITTLE_ENDIAN)
        val magic = bb.int
        if (magic != APCM_MAGIC) throw ProtocolException("bad APCM magic 0x${magic.toString(16)}")
        val rate = bb.int
        val ch = bb.short.toInt() and 0xffff
        val bits = bb.short.toInt() and 0xffff
        val kind = bb.short.toInt() and 0xffff
        return ApcmHeader(rate, ch, bits, kind)
    }

    fun encodeFrame(type: Int, payload: ByteArray, flags: Int = 0): ByteArray {
        if (payload.size > MAX_PAYLOAD) throw ProtocolException("payload too large ${payload.size}")
        val out = ByteArray(4 + payload.size)
        out[0] = type.toByte()
        out[1] = flags.toByte()
        out[2] = (payload.size and 0xff).toByte()
        out[3] = ((payload.size shr 8) and 0xff).toByte()
        System.arraycopy(payload, 0, out, 4, payload.size)
        return out
    }
}

class FrameReader {
    private val buf = ArrayList<Byte>(4096)

    fun feed(data: ByteArray): List<PcmFrame> {
        for (b in data) buf.add(b)
        val out = ArrayList<PcmFrame>()
        while (true) {
            if (buf.size < 4) break
            val type = buf[0].toInt() and 0xff
            val len = (buf[2].toInt() and 0xff) or ((buf[3].toInt() and 0xff) shl 8)
            if (len > PcmProtocol.MAX_PAYLOAD) throw ProtocolException("frame length $len")
            if (buf.size < 4 + len) break
            val payload = ByteArray(len) { i -> buf[4 + i] }
            repeat(4 + len) { buf.removeAt(0) }
            out.add(decode(type, payload))
        }
        return out
    }

    private fun decode(type: Int, payload: ByteArray): PcmFrame = when (type) {
        PcmProtocol.TYPE_PCM_DL -> PcmFrame.PcmDl(payload)
        PcmProtocol.TYPE_PCM_UL -> PcmFrame.PcmUl(payload)
        PcmProtocol.TYPE_CTRL_MUTE -> PcmFrame.CtrlMute(payload.isNotEmpty() && payload[0] != 0.toByte())
        PcmProtocol.TYPE_CTRL_FLUSH_UL -> PcmFrame.CtrlFlushUl
        PcmProtocol.TYPE_CTRL_SESSION -> PcmFrame.CtrlSession(payload.isNotEmpty() && payload[0] != 0.toByte())
        else -> PcmFrame.Unknown(type, payload)
    }
}
```

- [ ] **Step 5: 跑通单测并提交**

```bash
cd nexus_app
./gradlew :app:testDebugUnitTest --tests com.nexus.assistant.protocol.PcmProtocolTest
```

Expected: PASS

```bash
git add nexus_app
git commit -m "feat(app): add PCM frame protocol with JVM unit tests"
```

---

### Task 2: C++ `pcm_frame.h` + HAL 帧化 DL 发送

**Files:**
- Create: `zygisk_module/cpp/pcm_frame.h`
- Modify: `zygisk_module/cpp/audio_hook_hal.cpp`（UDS write 路径；现 `uds_write_all` / DL 推流处）
- Test: 复用 Task 1 向量；设备侧可用 `adb shell` + 临时 hexdump 客户端（见 Step 5）

**Interfaces:**
- Consumes: Task 1 常量数值（必须逐字节一致）
- Produces:
  - `pcm_frame_encode(type, flags, payload, len, out, out_cap) -> ssize_t`
  - DL 路径：不再 `write(raw_pcm)`，改为 `TYPE_PCM_DL` 帧
  - APCM 头布局保持现有 `uds_send_hdr_if_needed`（含 kind）

- [ ] **Step 1: 添加 `pcm_frame.h`**

```cpp
#pragma once
#include <cstdint>
#include <cstring>

static constexpr uint32_t kApcmMagic = 0x4D435041u;
static constexpr uint8_t kTypePcmDl = 0x01;
static constexpr uint8_t kTypePcmUl = 0x02;
static constexpr uint8_t kTypeCtrlMute = 0x10;
static constexpr uint8_t kTypeCtrlFlushUl = 0x11;
static constexpr uint8_t kTypeCtrlSession = 0x12;
static constexpr size_t kMaxFramePayload = 64 * 1024;

// Returns total bytes written to out, or -1 on error.
inline ssize_t pcm_frame_encode(uint8_t type, uint8_t flags, const void *payload, size_t len,
                                uint8_t *out, size_t out_cap) {
    if (len > kMaxFramePayload || out_cap < 4 + len) return -1;
    out[0] = type;
    out[1] = flags;
    out[2] = (uint8_t)(len & 0xff);
    out[3] = (uint8_t)((len >> 8) & 0xff);
    if (len && payload) memcpy(out + 4, payload, len);
    return (ssize_t)(4 + len);
}
```

- [ ] **Step 2: 修改 DL `write` 为帧发送**

在现有向 `g_uds_client` 写 PCM 的位置（搜索 `uds_write` / `write(fd` 推 DL）：

```cpp
// 伪代码落点：拿到 raw pcm pointer + count 之后
uint8_t frame[4 + 8192]; // 若 count 更大则 heap 分配
ssize_t n = pcm_frame_encode(kTypePcmDl, 0, data, count, frame, sizeof(frame));
if (n > 0) {
    uds_write_all(fd, frame, (size_t)n); // 沿用现有写失败踢客户端逻辑
}
```

注意：过渡期会 **破坏** 旧 `ai_call` 裸流解析。Task 2 完成后到 G1 前，验证用临时客户端或暂停 `ai_call`（见 Global Constraints）。可加编译宏：

```cpp
#ifndef NEXUS_UDS_FRAMED
#define NEXUS_UDS_FRAMED 1
#endif
```

`NEXUS_UDS_FRAMED=0` 时保持旧裸写，便于回滚。

- [ ] **Step 3: 重新编译并刷入 `nexus_audio_hook` 模块**

按 `zygisk_module` / `magisk_modules` 现行打包流程编译 `libai_hook.so` 并安装模块，重启或 reinject。

- [ ] **Step 4: 设备冒烟（root）——确认帧头**

在 AI 通话或强制打开 incall-rec 时，用 root 进程读 socket（可临时改一小段 Go 读 4 字节 type 或写 Python）。Expected：首包仍为 16B APCM；随后首字节为 `0x01`。

- [ ] **Step 5: Commit**

```bash
git add zygisk_module/cpp/pcm_frame.h zygisk_module/cpp/audio_hook_hal.cpp
git commit -m "feat(hal): send downlink PCM as framed UDS messages"
```

---

### Task 3: HAL 帧化 UL 接收 + CTRL + 保留 tx_inject 回滚

**Files:**
- Modify: `zygisk_module/cpp/audio_hook_hal.cpp`（`round7_incall_thread` / `txq_*`）
- Modify: `zygisk_module/cpp/pcm_frame.h`（如需 `pcm_frame_try_parse` 状态机）

**Interfaces:**
- Consumes: `pcm_frame_encode` 常量；现有 `TxInjectQ`
- Produces:
  - 从 `client_fd` 组帧读取；`PCM_UL` → `txq` 追加（实现 `txq_append` 若只有 replace-from-file）
  - `CTRL_MUTE` → `g_ai_mute_mic`（Task 4 完善门控）
  - `CTRL_FLUSH_UL` → `txq_clear`
  - `CTRL_SESSION` → 日志；`0` 时 mute=false + flush
  - 断连：mute=false、flush
  - `#if !NEXUS_UDS_FRAMED` 或 `#if NEXUS_TX_INJECT_FILE` 保留文件轮询

- [ ] **Step 1: 实现 fd 上的帧解析状态机**

```cpp
struct FrameParser {
    uint8_t hdr[4];
    size_t hdr_have = 0;
    uint8_t *payload = nullptr;
    size_t need = 0;
    size_t got = 0;
};

// 在注入线程循环：MSG_DONTWAIT recv → feed parser → dispatch
```

对 `PCM_UL`：把 payload 追加进 `TxInjectQ`（新增 `txq_append`：mutex 下 realloc 拼接，避免只支持整文件替换导致 TTS 分句丢失）。

- [ ] **Step 2: 无 UL 数据时保持现有 0 填充 keepalive**

不改变 `pcm_write` 节奏；仅数据来源从文件改为队列。

- [ ] **Step 3: 编译宏保留文件注入**

```cpp
#if NEXUS_TX_INJECT_FILE
    txq_try_load_file(&g_txq);
#endif
```

默认 `NEXUS_TX_INJECT_FILE=0`（帧路径为主）。

- [ ] **Step 4: 用 Task 1 的 encode 字节序列做对照**

在设备上用 root 小工具或临时 App（Task 5）发送：

```text
encodeFrame(CTRL_MUTE, [1])
encodeFrame(PCM_UL, 20ms silence at inject rate)
encodeFrame(CTRL_FLUSH_UL, [])
```

Expected：logcat `AI_Audio_Hook` 出现 mute/flush；对方侧可闻静音或测试音。

- [ ] **Step 5: Commit**

```bash
git add zygisk_module/cpp/audio_hook_hal.cpp zygisk_module/cpp/pcm_frame.h
git commit -m "feat(hal): framed uplink inject and control commands"
```

---

### Task 4: 软静音门控

**Files:**
- Modify: `zygisk_module/cpp/audio_hook_hal.cpp`（`fake_pcm_read`、voice/incall 句柄跟踪）

**Interfaces:**
- Consumes: `g_ai_mute_mic`（Task 3 CTRL）
- Produces: `bool is_voice_ul_mic(void *pcm)`；无法识别则 **不** mute 并限频打日志

- [ ] **Step 1: 跟踪通话相关 pcm 句柄**

在现有打开 incall-rec / voice 相关 `pcm_open` 钩子处，记录：

* DL rec 句柄（旁路用，**不要**当 UL mic mute）
* 若能区分 UL mic 句柄则记录；若首版无法区分，采用保守策略：**仅对「非 DL dump 句柄且 flags 含 PCM_IN、处于 g_in_voice」的 pcm」mute**，并在日志中打印指针，真机对照确认。

```cpp
static std::atomic<bool> g_ai_mute_mic{false};
static std::atomic<void *> g_voice_ul_pcm{nullptr};

static bool is_voice_ul_mic(void *pcm) {
    void *ul = g_voice_ul_pcm.load();
    return ul != nullptr && pcm == ul;
}
```

- [ ] **Step 2: 改 `fake_pcm_read`**

```cpp
static int fake_pcm_read(void *pcm, void *data, unsigned int count) {
    int rc = orig_pcm_read(pcm, data, count);
    if (g_ai_mute_mic.load() && rc >= 0 && data && is_voice_ul_mic(pcm)) {
        memset(data, 0, count);
    }
    log_count("pcm_read", g_pcm_r, count, rc);
    return rc;
}
```

- [ ] **Step 3: 断连与 SESSION_END 清 mute**

在 UDS client close 路径：`g_ai_mute_mic=false`。

- [ ] **Step 4: 真机验收（G2 部分）**

1. AI 模式接听 + MUTE_MUTE=1：对方听 TTS、环境音尽量无  
2. CTRL_MUTE=0：对方听到真 mic  
3. 微信语音消息/系统录音：与改造前一致  

- [ ] **Step 5: Commit**

```bash
git add zygisk_module/cpp/audio_hook_hal.cpp
git commit -m "feat(hal): gated soft-mute for voice uplink mic"
```

---

### Task 5: sepolicy 放行 App + Debug UDS 冒烟

**Files:**
- Modify: `zygisk_module/post-fs-data.sh`
- Create: `nexus_app/app/src/main/AndroidManifest.xml`
- Create: `nexus_app/app/src/main/java/com/nexus/assistant/uds/PcmSocketClient.kt`
- Create: `nexus_app/app/src/main/java/com/nexus/assistant/ui/SmokeActivity.kt`
- Create: `nexus_app/app/src/main/java/com/nexus/assistant/service/NexusBypassService.kt`（先做 connect API，FGS 通知可最小）

**Interfaces:**
- Consumes: `PcmProtocol`, `FrameReader`
- Produces:
  - `class PcmSocketClient(private val path: String = "/data/vendor/ai_hook/pcm.sock")`
  - `suspend fun connectAndAwaitHeader(timeoutMs: Long): ApcmHeader`
  - `fun sendFrame(type: Int, payload: ByteArray)`
  - `fun close()`
  - Smoke UI：按钮「Connect + send silent UL」

- [ ] **Step 1: 扩展 sepolicy（在现有 magisk/shell allow 旁追加）**

```sh
# zygisk_module/post-fs-data.sh — 追加（域名以 avc 拒绝日志为准，先试 untrusted_app）
magiskpolicy --live "allow untrusted_app vendor_data_file sock_file { write connect getattr }" 2>/dev/null
magiskpolicy --live "allow untrusted_app hal_audio_default unix_stream_socket { connect write read getopt getattr }" 2>/dev/null
magiskpolicy --live "allow untrusted_app_29 vendor_data_file sock_file { write connect getattr }" 2>/dev/null
magiskpolicy --live "allow untrusted_app_29 hal_audio_default unix_stream_socket { connect write read getopt getattr }" 2>/dev/null
magiskpolicy --live "allow untrusted_app_30 vendor_data_file sock_file { write connect getattr }" 2>/dev/null
magiskpolicy --live "allow untrusted_app_30 hal_audio_default unix_stream_socket { connect write read getopt getattr }" 2>/dev/null
```

刷模块/`post-fs-data` 后再测。若仍 AVC deny，把 `logcat`/`dmesg` 里 **完整 scontext/tcontext** 记入 `doc/dev_journal.md`，按 §8.1 试 abstract namespace（Task 5b，仅失败时做）。

- [ ] **Step 2: 实现 `PcmSocketClient`**

```kotlin
class PcmSocketClient(
    private val path: String = "/data/vendor/ai_hook/pcm.sock"
) {
    private var socket: LocalSocket? = null
    private val reader = FrameReader()

    fun connect() {
        val s = LocalSocket()
        s.connect(LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM))
        socket = s
    }

    fun readApcmHeader(timeoutMs: Long): ApcmHeader {
        val input = socket!!.inputStream
        val hdr = ByteArray(16)
        // 可用 SoTimeout；循环 ReadFully
        var off = 0
        val deadline = System.currentTimeMillis() + timeoutMs
        while (off < 16) {
            if (System.currentTimeMillis() > deadline) throw ProtocolException("APCM timeout")
            val n = input.read(hdr, off, 16 - off)
            if (n < 0) throw ProtocolException("EOF before APCM")
            off += n
        }
        return PcmProtocol.parseApcmHeader(hdr)
    }

    fun sendMute(on: Boolean) {
        val payload = byteArrayOf(if (on) 1 else 0)
        val frame = PcmProtocol.encodeFrame(PcmProtocol.TYPE_CTRL_MUTE, payload)
        socket!!.outputStream.write(frame)
        socket!!.outputStream.flush()
    }

    fun sendPcmUl(pcm: ByteArray) {
        socket!!.outputStream.write(PcmProtocol.encodeFrame(PcmProtocol.TYPE_PCM_UL, pcm))
    }

    fun close() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
    }
}
```

- [ ] **Step 3: SmokeActivity**

最小界面：显示 last error / header 字段；按钮触发：`connect` → 等待 header（需处于通话使 HAL 推流，或文档说明「先打一通 AI 测试电话」）→ `sendMute(true)` → 发送一帧全 0 `PCM_UL`（长度按 period，如 1920*2*2 @48k stereo 20ms）→ `sendMute(false)` → close。

- [ ] **Step 4: G1 真机验收**

Expected：无 `EACCES`/SELinux；读到 `rate/ch/kind`；logcat HAL 侧看到 client + UL/mute。失败则停在此 Task，不要开始业务迁移。

- [ ] **Step 5: Commit**

```bash
git add zygisk_module/post-fs-data.sh nexus_app
git commit -m "feat: allow app UDS to pcm.sock and add connect smoke UI"
```

---

### Task 6: 配置真源 + Settings 骨架

**Files:**
- Create: `nexus_app/.../config/NexusConfig.kt`
- Create: `nexus_app/.../config/ConfigRepository.kt`
- Create: `nexus_app/.../ui/settings/SettingsActivity.kt`
- Create: `nexus_app/app/src/test/java/.../config/NexusConfigTest.kt`

**Interfaces:**
- Produces:
  - `enum class SimPolicy { HUMAN, AI, REJECT }`
  - `data class SimConfig(val slot: Int, val label: String, val policy: SimPolicy, ...)`
  - `data class NexusConfig(val sims: List<SimConfig>, val llm: LlmConfig, val notify: NotifyConfig, val modelDir: String?, val archiveSafUri: String?)`
  - `class ConfigRepository(context: Context)` — `fun load()/save()`，SharedPreferences `nexus_config`（一次性迁移旧 `filesDir/config.json` 后删除）
  - Settings：编辑双卡策略、DeepSeek key、Webhook、模型目录提示、**「设为默认电话」**按钮（`RoleManager.ROLE_DIALER`）

- [ ] **Step 1: 单测默认 config 序列化**

```kotlin
@Test
fun default_roundTrip() {
    val json = NexusConfig.default().toJson()
    val back = NexusConfig.fromJson(json)
    assertEquals(SimPolicy.HUMAN, back.sims[0].policy)
}
```

- [ ] **Step 2: 实现与跑通**

```bash
./gradlew :app:testDebugUnitTest --tests com.nexus.assistant.config.NexusConfigTest
```

- [ ] **Step 3: SettingsActivity MVP 控件绑定 + Commit**

```bash
git commit -m "feat(app): config repository and settings skeleton"
```

---

### Task 7: 最小默认电话 UI + InCallService

**Files:**
- Create: `nexus_app/.../telecom/NexusInCallService.kt`
- Create: `nexus_app/.../telecom/DialerActivity.kt`
- Create: `nexus_app/.../telecom/IncomingCallActivity.kt`
- Create: `nexus_app/.../telecom/InCallActivity.kt`
- Modify: `AndroidManifest.xml`（`InCallService`、`DIAL`/`CALL` intent、权限、`ROLE_DIALER` 相关）

**Interfaces:**
- Consumes: `ConfigRepository.policyForSlot(slot)`
- Produces: 来电 `ai` → `call.answer(VideoProfile.STATE_AUDIO_ONLY)`；ACTIVE → 启动 `NexusBypassService` 会话；挂断/人工 → 结束会话

- [ ] **Step 1: Manifest 声明（关键节选）**

```xml
<uses-permission android:name="android.permission.READ_PHONE_STATE" />
<uses-permission android:name="android.permission.CALL_PHONE" />
<uses-permission android:name="android.permission.ANSWER_PHONE_CALLS" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_PHONE_CALL" />
<uses-permission android:name="android.permission.MANAGE_OWN_CALLS" />

<service
    android:name=".telecom.NexusInCallService"
    android:permission="android.permission.BIND_INCALL_SERVICE"
    android:exported="true">
    <meta-data
        android:name="android.telecom.IN_CALL_SERVICE_UI"
        android:value="true" />
    <intent-filter>
        <action android:name="android.telecom.InCallService" />
    </intent-filter>
</service>

<activity android:name=".telecom.DialerActivity" android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.DIAL" />
        <action android:name="android.intent.action.VIEW" />
        <category android:name="android.intent.category.DEFAULT" />
        <category android:name="android.intent.category.BROWSABLE" />
        <data android:scheme="tel" />
    </intent-filter>
    <intent-filter>
        <action android:name="android.intent.action.DIAL" />
        <category android:name="android.intent.category.DEFAULT" />
    </intent-filter>
</activity>
```

- [ ] **Step 2: InCallService 状态机（与 spec §4.2 一致）**

```kotlin
override fun onCallAdded(call: Call) {
    call.registerCallback(object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            when (state) {
                Call.STATE_RINGING -> {
                    if (policyFor(call) == SimPolicy.AI) {
                        call.answer(VideoProfile.STATE_AUDIO_ONLY)
                    } else if (policyFor(call) == SimPolicy.REJECT) {
                        call.reject(false, null)
                    } else {
                        showIncomingUi(call)
                    }
                }
                Call.STATE_ACTIVE -> {
                    startActivity(InCallActivity.intent(this@NexusInCallService, call))
                    if (policyFor(call) == SimPolicy.AI || aiModeEnabled) {
                        NexusBypassService.startSession(this@NexusInCallService, call)
                    }
                }
                Call.STATE_DISCONNECTED -> {
                    NexusBypassService.endSession(this@NexusInCallService)
                }
            }
        }
    })
}
```

- [ ] **Step 3: 三页 MVP UI**

* Dialer：EditText + Call（`TelecomManager.placeCall`）  
* Incoming：Answer / Reject  
* InCall：Hangup + Toggle AI/Human（切换时发 MUTE/FLUSH）

- [ ] **Step 4: 真机** — 授予默认电话 → 来电自动/手动接听 → 挂断。Expected：无系统电话 UI 抢主界面（或可接受共存，但接听由本 App 完成）。
- [ ] **Step 4b: Nexus 接管开关（见 spec §4.2.1）**
  - Settings：`开启 Nexus 接管` / `关闭 → 交回系统电话`（开启时一并弹出默认电话确认，无单独按钮）
  - ON：用户确认 `ROLE_DIALER` **之后**再切组件：启用 Nexus ICS + `pm disable` 系统 Dialer ICS + 默认电话=Nexus
  - 取消确认 / 未选 Nexus：**回滚 OFF**（不得半残）
  - OFF：`pm enable` 系统 Dialer ICS + `pm disable` Nexus ICS + 交回 `com.android.dialer` + cleanup/bounce phone；**禁止** `am start` 系统拨号盘；日常接听正常
  - 配置：`dialer_takeover`；`AiAnswerReceiver` / `NexusInCallService` 在 OFF 时 no-op

- [ ] **Step 5: Commit**

```bash
git commit -m "feat(app): minimal dialer and InCallService"
```

---

### Task 8: FGS 会话生命周期接线（UDS + CTRL）

**Files:**
- Modify: `NexusBypassService.kt`
- Modify: `PcmSocketClient.kt`（读循环）

**Interfaces:**
- Produces:
  - `startSession`：`startForeground`（`phoneCall` type）→ connect → 等 APCM → `CTRL_SESSION=1` + `CTRL_MUTE=1` → 读 `PCM_DL` 回调
  - `endSession`：`CTRL_MUTE=0` + `FLUSH` + `SESSION=0` → close → `stopForeground`（若无其它工作）
  - 仅会话期 2s 重连

- [ ] **Step 1: 实现会话状态 `enum class SessionState { Idle, Connecting, Streaming }`**
- [ ] **Step 2: 读线程把 `PcmDl` 送入 `AudioPipeline` 的队列（Task 9 可先 log 字节数）**
- [ ] **Step 3: 真机 AI 通话确认 mute 帧与断连复位**
- [ ] **Step 4: Commit** `feat(app): FGS UDS session lifecycle`

---

### Task 9: 音频管线（resample + VAD）

**Files:**
- Create: `nexus_app/.../audio/AudioResampler.kt`
- Create: `nexus_app/.../audio/EnergyVad.kt`
- Create: `nexus_app/.../audio/AudioPipeline.kt`
- Create: `nexus_app/app/src/test/java/.../audio/AudioResamplerTest.kt`

**Interfaces:**
- Consumes: `ApcmHeader`、`ByteArray` PCM_DL
- Produces: `fun stereoS16ToMono16k(pcm: ByteArray, channels: Int, rate: Int): ShortArray`（可移植 `daemon/ai_call` 逻辑）
- `EnergyVad`：对标现网能量 VAD，输出切句 `ShortArray` 给 ASR

- [ ] **Step 1: 单测** — 已知 48k stereo 正弦 → 16k mono 长度公式断言  
- [ ] **Step 2: 实现并 `./gradlew :app:testDebugUnitTest`**  
- [ ] **Step 3: Commit** `feat(app): resample and energy VAD pipeline`

---

### Task 10: sherpa-onnx ASR/TTS

**Files:**
- Modify: `app/build.gradle.kts`（AAR 依赖；版本以 Maven 可解析的最新 1.10+ 为准，在 commit message 写死实际版本）
- Create: `nexus_app/.../ai/SherpaAsr.kt`
- Create: `nexus_app/.../ai/SherpaTts.kt`

**Interfaces:**
- SenseVoice：**Offline** API（按 AAR 文档；不要假设 OnlineSenseVoice）
- TTS：`OfflineTts` VITS；`synthesize(text): FloatArray/ByteArray` → resample 到注入格式 → `PCM_UL` 帧发送
- 模型路径：仅 App `files/models/...`（或 config `model_dir`）；**不再**读 Magisk `nexus_models`

- [ ] **Step 1: 依赖可解析 spike** — `./gradlew :app:assembleDebug`  
- [ ] **Step 2: 离线文件测 ASR**（assets 或 adb push 一小段 wav）  
- [ ] **Step 3: 通话中 TTS 对方可闻（G2 完成）**  
- [ ] **Step 4: Commit** `feat(app): integrate sherpa-onnx ASR and TTS`

---

### Task 11: DeepSeek LLM 通话状态机

**Files:**
- Create: `nexus_app/.../ai/DeepSeekClient.kt`
- Create: `nexus_app/.../ai/CallSessionController.kt`
- Test: `DeepSeekClient` 可用 mock web server 或纯解析单测

**Interfaces:**
- 对标 `daemon` 里 session：系统 prompt（可从 config 迁移默认中文 prompt）、`max_msgs`、流式分句送 TTS
- `onUserUtterance(text)` → API → `onAssistantSentence(text)` → TTS

- [ ] **Step 1: 实现 HTTP chat completions（HTTPS，key 来自 Config）**  
- [ ] **Step 2: 串进 `AudioPipeline`：VAD 句 → ASR → LLM → TTS → UL**  
- [ ] **Step 3: 真机一通完整 AI 对话**  
- [ ] **Step 4: Commit** `feat(app): DeepSeek call session controller`

---

### Task 12: 通话存档 + 企微通知 + 短信

**Files:**
- Create: `nexus_app/.../archive/CallArchiveWriter.kt`
- Create: `nexus_app/.../notify/WeComNotifier.kt`
- Create: `nexus_app/.../notify/SmsWatcher.kt`
- Test: `CallArchiveWriterTest`（临时目录）

**Interfaces:**
- 根目录：`context.getExternalFilesDir("nexus_calls")`
- 一通：`calls/yyyy-MM-dd_HHmmss_slotX/{meta.json,transcript.txt,summary.txt,audio/}`
- `notify_queue/` 投放待推送任务；成功删除
- SMS：`READ_SMS` + Inbox Observer + 水位 SharedPreferences

- [x] **Step 1: 存档单测 round-trip meta+transcript**  
- [x] **Step 2: 挂断路径调用 writer + WeCom（config.enabled）**  
- [x] **Step 3: 短信转发冒烟**（ContentObserver + 水位；需 READ_SMS + notify 配置）  
- [x] **Step 4: Commit**（与接管开关 / ASR-LLM-TTS / 回声门控一并提交）

---

### Deferred TODO：AI 接听静麦保 TX（kona 真机未解）

**状态：** 暂缓；G3 项「环境音被软静音」当前**不作为阻塞**（对方仍可能听到环境音）。

**已踩坑（勿再试）：**
- `AudioManager.isMicrophoneMute` → 环境音没了，Incall_Music TTS 也没了
- `TX_AIF1_CAP Mixer DEC*` 置 0 → 拆掉共享语音上行，AI/环境音皆无

**待标定：** 只降手麦/DEC 增益、不动共享 UL 总线与 `Incall_Music Audio Mixer MultiMedia9` 的 mixer/codec 控件（见 spec §5）。

---

### Task 13: G3 验收清单（文档化，不删 runtime）

**Files:**
- Create: `docs/superpowers/plans/checklists/2026-07-20-app-mvp-acceptance.md`

- [x] **Step 1: 清单落盘** — `docs/superpowers/plans/checklists/2026-07-20-app-mvp-acceptance.md`（含 Deferred 静麦说明）
- [ ] **Step 2: 按表在真机勾选**（详见 checklist；项 2b 静麦不阻塞）
- [ ] **Step 3: 阻塞项清零后再进入 Task 14**  
- [ ] **Step 4: Commit** checklist 勾选结果（若有更新）

---

### Task 14: M4 — 停用 Go / models 模块（仅保留 hook）

**决策（2026-07-21）：** 不等 G3 签字，设备侧已弃用 `nexus_models`/`nexus_runtime`；配置与模型归 App。

**Files:**
- Modify: `doc/00_framework_overview.md` — App 闭环 + 仅 `nexus_audio_hook`
- App：删除 Magisk 同步 UI（`ModelSync`/`LlmKeySync`）；`ModelPaths` 只解析 App `files/models`
- 一次性迁移：`nexus_app/scripts/migrate_magisk_config_once.py`（PC 侧合并后 push）
- `daemon/*` 源码可暂留，**不装机、不打包进 Magisk zip**

- [x] **Step 1: 设备 disable `nexus_models`；杀遗留 Go 守护（本机无 `nexus_runtime`）**  
- [x] **Step 2: LLM/企微/模型迁入 App `files/`；Settings 去掉 Magisk 同步按钮**  
- [ ] **Step 3: Commit** `chore(app): drop Magisk sync; keep audio hook only`

---

### Task 15: M5 — 清理 tx_inject 主路径与文档

**Files:**
- Modify: `audio_hook_hal.cpp` — 删除或永久禁用 `txq_try_load_file` 主路径（保留宏一版）
- Modify: `magisk_modules/README.md`、`doc/04_architecture_runtime.md`（或加新 overview）
- Update: design spec 状态 → `Accepted` / Implemented-in-progress

- [ ] **Step 1: 确认无任何客户端再写 `tx_inject.pcm`**  
- [ ] **Step 2: 文档与模块 README 对齐**  
- [ ] **Step 3: Commit** `chore: retire tx_inject.pcm primary path and update docs`

---

## Spec coverage (self-review)

| Spec 章节 | Task |
|-----------|------|
| §3 帧协议 / APCM | 1, 2, 3 |
| §3.5 UDS 可达 / sepolicy | 5 |
| §3.4 连接生命周期 | 8 |
| §4.1 FGS | 8 |
| §4.2 默认电话 MVP | 7 |
| §4.3 音频管线 | 9 |
| §4.4 sherpa | 10 |
| §4.5 配置对等 | 6, 11, 12 |
| §4.6 存档布局 | 12 |
| §5 软静音门控 | 4 |
| §6 M0–M5 迁移 | 门禁 + 13–15 |
| §8.1 已拍板项 | 全局约束 / 7 / 12 / 5 |

**刻意延后（spec 允许）：** 语音 `audio/` 实写入、CTRL_ACK、通话 UI 抛光、abstract/companion 备选（仅 G1 失败时另开任务）。

---

## Execution handoff

Plan complete and saved to [`docs/superpowers/plans/2026-07-20-nexus-app-architecture.md`](docs/superpowers/plans/2026-07-20-nexus-app-architecture.md).

**Two execution options:**

1. **Subagent-Driven (recommended)** — 每任务新开 subagent，任务间复查  
2. **Inline Execution** — 本会话按 `executing-plans` 连续做，门禁处停下来给你验收  

Which approach?
