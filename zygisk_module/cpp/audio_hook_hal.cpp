#include <android/log.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <pthread.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <unistd.h>
#include <atomic>
#include <cerrno>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <cstring>

#include "dobby.h"
#include "pcm_frame.h"

#ifndef NEXUS_UDS_FRAMED
#define NEXUS_UDS_FRAMED 1
#endif
/*
 * File TX inject (tx_inject.pcm) is RETIRED as the primary path.
 * App injects PCM_UL frames over UDS. Keep the loader behind this macro only
 * for emergency rollback builds (NEXUS_TX_INJECT_FILE=1); never enable by default.
 */
#ifndef NEXUS_TX_INJECT_FILE
#define NEXUS_TX_INJECT_FILE 0
#endif

#define PCM_SOCK_PATH "/data/vendor/ai_hook/pcm.sock"
/* Abstract UDS name (no leading @ in C; sun_path[0]=0). App uses Namespace.ABSTRACT. */
#define PCM_SOCK_ABSTRACT "nexus_pcm"
#define TX_INJECT_PATH "/data/vendor/ai_hook/tx_inject.pcm"
#define TX_TONE_PATH "/data/vendor/ai_hook/tx_tone"
#define APCM_MAGIC 0x4D435041u /* 'APCM' LE */
/* APCM hdr[12:14] stream kind (u16 LE) */
enum {
    APCM_KIND_MIXED = 0,
    APCM_KIND_DL = 1,
    APCM_KIND_UL = 2,
};

#define LOG_TAG "AI_Audio_Hook"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// 1.D': DL-only incall-rec for STT; dump + UDS until hangup.

using pcm_io_fn_t = int (*)(void *pcm, void *data, unsigned int count);
using voice_fn1_t = int (*)(void *adev);
using voice_fn2_t = int (*)(void *adev, int usecase);
using platform_voice_fn_t = int (*)(void *platform, unsigned int vsid);
using set_incall_sess_fn_t = int (*)(void *platform, unsigned int session_id, int rec_mode);
using stop_incall_fn_t = int (*)(void *platform);
using incall_music_fn_t = int (*)(void *platform);
using voice_sid_fn_t = unsigned int (*)(void *adev);
using stream_fn_t = int (*)(void *stream);

enum { PCM_OUT = 0x00000000, PCM_IN = 0x10000000 };
enum { PCM_FORMAT_S16_LE = 0 };
enum {
    INCALL_REC_NONE = -1,
    INCALL_REC_UPLINK = 0,
    INCALL_REC_DOWNLINK = 1,
    INCALL_REC_UPLINK_AND_DOWNLINK = 2,
};
#define VOICE_VSID_DEFAULT 0x10C01000u

struct pcm_config {
    unsigned int channels;
    unsigned int rate;
    unsigned int period_size;
    unsigned int period_count;
    unsigned int format;
    unsigned int start_threshold;
    unsigned int stop_threshold;
    unsigned int silence_threshold;
    int silence_size;
    unsigned int avail_min;
};

using pcm_open_fn_t = void *(*)(unsigned int card, unsigned int device, unsigned int flags,
                                const struct pcm_config *config);
using pcm_close_fn_t = int (*)(void *pcm);
using pcm_is_ready_fn_t = int (*)(void *pcm);
using pcm_get_error_fn_t = const char *(*)(void *pcm);
using pcm_start_fn_t = int (*)(void *pcm);
using mixer_open_fn_t = void *(*)(unsigned int card);
using mixer_close_fn_t = void (*)(void *mixer);
using mixer_get_ctl_fn_t = void *(*)(void *mixer, const char *name);
using mixer_ctl_set_fn_t = int (*)(void *ctl, unsigned int id, int value);
using mixer_ctl_get_fn_t = int (*)(void *ctl, unsigned int id);

static void *resolve_sym(const char *lib, const char *sym) {
    void *h = dlopen(lib, RTLD_NOW | RTLD_NOLOAD);
    if (!h) {
        h = dlopen(lib, RTLD_NOW);
    }
    if (!h) {
        LOGI("dlopen(%s) failed: %s", lib, dlerror());
        return nullptr;
    }
    void *addr = dlsym(h, sym);
    if (!addr) {
        LOGI("dlsym(%s!%s) failed", lib, sym);
        return nullptr;
    }
    LOGI("dlsym %s!%s @%p", lib, sym, addr);
    return addr;
}

static bool hook_addr(const char *name, void *addr, void *replace, void **orig_out) {
    if (!addr) {
        LOGI("hook skip %s: null", name);
        return false;
    }
    *orig_out = nullptr;
    int rc = DobbyHook(addr, (dobby_dummy_func_t)replace, (dobby_dummy_func_t *)orig_out);
    LOGI("DobbyHook(%s) rc=%d orig=%p", name, rc, *orig_out);
    return rc == 0 && *orig_out != nullptr;
}

static void log_count(const char *tag, std::atomic<int> &ctr, unsigned bytes, int rc) {
    int n = ctr.fetch_add(1, std::memory_order_relaxed);
    if (n < 20 || (n % 500) == 0) {
        LOGI("count %s #%d bytes=%u rc=%d", tag, n, bytes, rc);
    }
}

static std::atomic<bool> g_in_voice{false};
static std::atomic<void *> g_adev{nullptr};
static std::atomic<void *> g_platform{nullptr};
static std::atomic<unsigned> g_vsid{VOICE_VSID_DEFAULT};

static pcm_io_fn_t orig_pcm_write = nullptr;
static pcm_io_fn_t orig_pcm_read = nullptr;
static std::atomic<int> g_pcm_w{0}, g_pcm_r{0};
static std::atomic<bool> g_ai_mute_mic{false};
static std::atomic<void *> g_dl_rec_pcm{nullptr};
static std::atomic<void *> g_voice_ul_pcm{nullptr};
// Last non-DL capture open (may happen before voice_start_usecase).
static std::atomic<void *> g_last_capture_pcm{nullptr};
// UDS client fd (declared early — frame parser / accept paths use it).
static std::atomic<int> g_uds_client{-1};
static std::atomic<bool> g_uds_hdr_sent{false};

static bool is_voice_ul_mic(void *pcm) {
    if (pcm == nullptr) {
        return false;
    }
    void *dl = g_dl_rec_pcm.load();
    if (dl != nullptr && pcm == dl) {
        return false; // never mute Round-7 DL dump
    }
    void *ul = g_voice_ul_pcm.load();
    if (ul != nullptr) {
        return pcm == ul;
    }
    // UL never tracked (common if mic opens before g_in_voice): during an active
    // voice call, treat any non-DL pcm_read as uplink candidate so CTRL_MUTE works.
    if (g_in_voice.load()) {
        static std::atomic<int> fb{0};
        int n = fb.fetch_add(1, std::memory_order_relaxed);
        if (n < 8 || (n % 500) == 0) {
            LOGI("soft-mute fallback pcm=%p (UL untracked, in_voice=1)", pcm);
        }
        return true;
    }
    return false;
}

static int fake_pcm_write(void *pcm, void *data, unsigned int count) {
    int rc = orig_pcm_write(pcm, data, count);
    log_count("pcm_write", g_pcm_w, count, rc);
    return rc;
}
static int fake_pcm_read(void *pcm, void *data, unsigned int count) {
    int rc = orig_pcm_read(pcm, data, count);
    if (g_ai_mute_mic.load() && rc >= 0 && data != nullptr && is_voice_ul_mic(pcm)) {
        memset(data, 0, count);
    }
    log_count("pcm_read", g_pcm_r, count, rc);
    return rc;
}

static platform_voice_fn_t orig_plat_start_voice = nullptr;
static int fake_plat_start_voice(void *platform, unsigned int vsid) {
    g_platform.store(platform);
    g_vsid.store(vsid);
    LOGI("platform_start_voice_call plat=%p vsid=0x%x", platform, vsid);
    return orig_plat_start_voice(platform, vsid);
}

static set_incall_sess_fn_t fn_set_incall_sess = nullptr;
static stop_incall_fn_t fn_stop_incall = nullptr;
static incall_music_fn_t fn_start_incall_music = nullptr;
static incall_music_fn_t fn_stop_incall_music = nullptr;
static voice_sid_fn_t fn_voice_sid = nullptr;
static set_incall_sess_fn_t orig_set_incall_sess = nullptr;
static int fake_set_incall_sess(void *platform, unsigned int session_id, int rec_mode) {
    LOGI("platform_set_incall_recording_session_id plat=%p sess=0x%x mode=%d", platform, session_id,
         rec_mode);
    return orig_set_incall_sess(platform, session_id, rec_mode);
}

static int set_mixer_int(void *mixer, mixer_get_ctl_fn_t get_ctl, mixer_ctl_set_fn_t set_val,
                         const char *name, int value) {
    void *ctl = get_ctl(mixer, name);
    if (!ctl) {
        LOGI("mixer missing ctl: %s", name);
        return -1;
    }
    int rc = set_val(ctl, 0, value);
    LOGI("mixer set '%s'=%d rc=%d", name, value, rc);
    return rc;
}

// NOTE: Do NOT zero TX_AIF1_CAP Mixer DEC* — on kona that tears down the whole
// voice UL mix and kills Incall_Music TX as well (CTRL_MUTE then → silence).
// True "mute mic, keep TTS" still needs a calibrated codec gain that is not on
// the shared UL mix bus; tracked as TODO (静麦保 TX).
static void apply_mixer_mic_mute(bool mute) {
    (void)mute;
}

/** Undo a previous bad mute that left TX_AIF1_CAP Mixer DEC1=0 (no UL / no TTS). */
static void repair_voice_ul_mixers() {
    auto mixer_open = (mixer_open_fn_t)resolve_sym("libtinyalsa.so", "mixer_open");
    auto mixer_close = (mixer_close_fn_t)resolve_sym("libtinyalsa.so", "mixer_close");
    auto get_ctl = (mixer_get_ctl_fn_t)resolve_sym("libtinyalsa.so", "mixer_get_ctl_by_name");
    auto set_val = (mixer_ctl_set_fn_t)resolve_sym("libtinyalsa.so", "mixer_ctl_set_value");
    if (!mixer_open || !mixer_close || !get_ctl || !set_val) {
        return;
    }
    void *mixer = mixer_open(0);
    if (!mixer) {
        return;
    }
    // Handset path on this device had DEC1 enabled during AI call before bad mute.
    set_mixer_int(mixer, get_ctl, set_val, "TX_AIF1_CAP Mixer DEC1", 1);
    mixer_close(mixer);
}

static bool load_capture_config(struct pcm_config *out) {
    auto *cfg = (struct pcm_config *)resolve_sym("audio.primary.kona.so", "pcm_config_audio_capture");
    if (cfg) {
        memcpy(out, cfg, sizeof(*out));
        LOGI("pcm_config_audio_capture ch=%u rate=%u period=%u count=%u fmt=%u", out->channels,
             out->rate, out->period_size, out->period_count, out->format);
        return true;
    }
    memset(out, 0, sizeof(*out));
    out->channels = 1;
    out->rate = 48000;
    out->period_size = 960;
    out->period_count = 4;
    out->format = PCM_FORMAT_S16_LE;
    LOGI("pcm_config fallback 48k mono");
    return false;
}

static bool load_incall_music_config(struct pcm_config *out) {
    auto *cfg = (struct pcm_config *)resolve_sym("audio.primary.kona.so", "pcm_config_incall_music");
    if (cfg) {
        memcpy(out, cfg, sizeof(*out));
        LOGI("pcm_config_incall_music ch=%u rate=%u period=%u count=%u fmt=%u", out->channels,
             out->rate, out->period_size, out->period_count, out->format);
        return true;
    }
    memset(out, 0, sizeof(*out));
    out->channels = 1;
    out->rate = 48000;
    out->period_size = 480;
    out->period_count = 4;
    out->format = PCM_FORMAT_S16_LE;
    LOGI("pcm_config_incall_music fallback 48k mono");
    return false;
}

// Debug: ~880Hz tone when /data/vendor/ai_hook/tx_tone exists.
static void fill_tone_s16(int16_t *dst, int frames, int channels, unsigned rate, double *phase) {
    const double freq = 880.0;
    const double two_pi = 6.283185307179586;
    const double step = two_pi * freq / (double)rate;
    const int16_t amp = 6000;
    for (int i = 0; i < frames; ++i) {
        int16_t s = (int16_t)(std::sin(*phase) * (double)amp);
        *phase += step;
        if (*phase > two_pi) {
            *phase -= two_pi;
        }
        for (int c = 0; c < channels; ++c) {
            dst[i * channels + c] = s;
        }
    }
}

// On-demand TX: drop raw s16le PCM (match incall-music rate/ch) onto TX_INJECT_PATH.
struct TxInjectQ {
    pthread_mutex_t mu;
    unsigned char *data;
    size_t len;
    size_t off;
};

static void txq_init(TxInjectQ *q) {
    pthread_mutex_init(&q->mu, nullptr);
    q->data = nullptr;
    q->len = 0;
    q->off = 0;
}

static void txq_clear_locked(TxInjectQ *q) {
    free(q->data);
    q->data = nullptr;
    q->len = 0;
    q->off = 0;
}

static void txq_destroy(TxInjectQ *q) {
    pthread_mutex_lock(&q->mu);
    txq_clear_locked(q);
    pthread_mutex_unlock(&q->mu);
    pthread_mutex_destroy(&q->mu);
}

static void txq_try_load_file(TxInjectQ *q) {
    struct stat st {};
    if (stat(TX_INJECT_PATH, &st) != 0 || st.st_size <= 0) {
        return;
    }
    int fd = open(TX_INJECT_PATH, O_RDONLY);
    if (fd < 0) {
        return;
    }
    auto *buf = (unsigned char *)malloc((size_t)st.st_size);
    if (!buf) {
        close(fd);
        return;
    }
    ssize_t n = read(fd, buf, (size_t)st.st_size);
    close(fd);
    // consume file so we don't loop the same clip
    unlink(TX_INJECT_PATH);
    if (n <= 0) {
        free(buf);
        return;
    }
    pthread_mutex_lock(&q->mu);
    txq_clear_locked(q);
    q->data = buf;
    q->len = (size_t)n;
    q->off = 0;
    pthread_mutex_unlock(&q->mu);
    LOGI("1.E loaded tx_inject.pcm bytes=%zd", n);
}

static bool txq_pull(TxInjectQ *q, void *dst, size_t need) {
    pthread_mutex_lock(&q->mu);
    size_t avail = (q->off < q->len) ? (q->len - q->off) : 0;
    if (avail == 0) {
        pthread_mutex_unlock(&q->mu);
        return false;
    }
    size_t n = avail < need ? avail : need;
    memcpy(dst, q->data + q->off, n);
    q->off += n;
    if (n < need) {
        memset((unsigned char *)dst + n, 0, need - n);
    }
    if (q->off >= q->len) {
        txq_clear_locked(q);
        LOGI("1.E tx_inject drain done");
    }
    pthread_mutex_unlock(&q->mu);
    return true;
}

static void txq_append(TxInjectQ *q, const void *src, size_t n) {
    if (!q || !src || n == 0) {
        return;
    }
    pthread_mutex_lock(&q->mu);
    size_t remain = (q->off < q->len) ? (q->len - q->off) : 0;
    size_t new_len = remain + n;
    auto *buf = (unsigned char *)malloc(new_len);
    if (!buf) {
        pthread_mutex_unlock(&q->mu);
        return;
    }
    if (remain > 0) {
        memcpy(buf, q->data + q->off, remain);
    }
    memcpy(buf + remain, src, n);
    free(q->data);
    q->data = buf;
    q->len = new_len;
    q->off = 0;
    pthread_mutex_unlock(&q->mu);
}

struct UdsFrameParser {
    uint8_t hdr[4] {};
    size_t hdr_have = 0;
    uint8_t *payload = nullptr;
    size_t need = 0;
    size_t got = 0;
};

static void uds_parser_reset(UdsFrameParser *p) {
    free(p->payload);
    p->payload = nullptr;
    p->hdr_have = 0;
    p->need = 0;
    p->got = 0;
}

static void uds_drop_client(int fd, TxInjectQ *inj) {
    int cur = g_uds_client.load();
    if (cur == fd && g_uds_client.compare_exchange_strong(cur, -1)) {
        close(fd);
        g_uds_hdr_sent.store(false);
    }
    g_ai_mute_mic.store(false);
    apply_mixer_mic_mute(false);
    if (inj) {
        pthread_mutex_lock(&inj->mu);
        txq_clear_locked(inj);
        pthread_mutex_unlock(&inj->mu);
    }
}

static void uds_dispatch_frame(uint8_t type, const uint8_t *payload, size_t len, TxInjectQ *inj) {
    switch (type) {
    case kTypePcmUl:
        txq_append(inj, payload, len);
        break;
    case kTypeCtrlMute: {
        bool on = len > 0 && payload[0] != 0;
        g_ai_mute_mic.store(on);
        // pcm_read gate only — mixer DEC mute removed (broke Incall_Music TX).
        LOGI("CTRL_MUTE %d", (int)on);
        break;
    }
    case kTypeCtrlFlushUl:
        pthread_mutex_lock(&inj->mu);
        txq_clear_locked(inj);
        pthread_mutex_unlock(&inj->mu);
        LOGI("CTRL_FLUSH_UL");
        break;
    case kTypeCtrlSession:
        if (len > 0 && payload[0] == 0) {
            g_ai_mute_mic.store(false);
            apply_mixer_mic_mute(false);
            pthread_mutex_lock(&inj->mu);
            txq_clear_locked(inj);
            pthread_mutex_unlock(&inj->mu);
        }
        LOGI("CTRL_SESSION %d", len > 0 ? (int)payload[0] : -1);
        break;
    default:
        LOGI("UDS ignore type=0x%02x len=%zu", type, len);
        break;
    }
}

static void uds_feed_bytes(UdsFrameParser *p, const uint8_t *data, size_t n, TxInjectQ *inj) {
    size_t i = 0;
    while (i < n) {
        if (p->payload == nullptr) {
            while (i < n && p->hdr_have < 4) {
                p->hdr[p->hdr_have++] = data[i++];
            }
            if (p->hdr_have < 4) {
                return;
            }
            p->need = (size_t)p->hdr[2] | ((size_t)p->hdr[3] << 8);
            if (p->need > kMaxFramePayload) {
                LOGI("UDS frame len too large %zu", p->need);
                uds_parser_reset(p);
                return;
            }
            p->got = 0;
            if (p->need == 0) {
                uds_dispatch_frame(p->hdr[0], nullptr, 0, inj);
                p->hdr_have = 0;
                continue;
            }
            p->payload = (uint8_t *)malloc(p->need);
            if (!p->payload) {
                uds_parser_reset(p);
                return;
            }
        }
        size_t take = p->need - p->got;
        if (take > n - i) {
            take = n - i;
        }
        memcpy(p->payload + p->got, data + i, take);
        p->got += take;
        i += take;
        if (p->got == p->need) {
            uds_dispatch_frame(p->hdr[0], p->payload, p->need, inj);
            free(p->payload);
            p->payload = nullptr;
            p->hdr_have = 0;
            p->need = 0;
            p->got = 0;
        }
    }
}

static void uds_poll_client_frames(UdsFrameParser *parser, TxInjectQ *inj) {
    int fd = g_uds_client.load();
    if (fd < 0 || !inj) {
        return;
    }
    uint8_t tmp[4096];
    for (;;) {
        ssize_t r = recv(fd, tmp, sizeof(tmp), MSG_DONTWAIT);
        if (r > 0) {
            uds_feed_bytes(parser, tmp, (size_t)r, inj);
            continue;
        }
        if (r == 0) {
            LOGI("uds client closed fd=%d", fd);
            uds_drop_client(fd, inj);
            uds_parser_reset(parser);
            break;
        }
        if (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR) {
            break;
        }
        LOGI("uds recv fail errno=%d", errno);
        uds_drop_client(fd, inj);
        uds_parser_reset(parser);
        break;
    }
}

static int open_dump_fd() {
    // Prefer vendor_data_file path; HAL needs sepolicy allow (see sepolicy.rule).
    const char *paths[] = {"/data/vendor/ai_hook/ai_dl.pcm", "/data/vendor/ai_hook/ai_incall.pcm",
                           "/data/local/tmp/ai_dl.pcm"};
    for (const char *p : paths) {
        // Existing pre-created file (chmod 666) first — avoids create permission.
        int fd = open(p, O_WRONLY | O_TRUNC);
        if (fd < 0) {
            fd = open(p, O_CREAT | O_TRUNC | O_WRONLY, 0666);
        }
        if (fd >= 0) {
            LOGI("DL dump fd=%d path=%s", fd, p);
            return fd;
        }
        LOGI("DL open(%s) failed errno=%d", p, errno);
    }
    return -1;
}

// 1.D: HAL is UDS server; Go pcm_recv / App connect as clients.
static void put_u32_le(unsigned char *p, unsigned v) {
    p[0] = (unsigned char)(v);
    p[1] = (unsigned char)(v >> 8);
    p[2] = (unsigned char)(v >> 16);
    p[3] = (unsigned char)(v >> 24);
}
static void put_u16_le(unsigned char *p, unsigned v) {
    p[0] = (unsigned char)(v);
    p[1] = (unsigned char)(v >> 8);
}

static size_t uds_send_all(int fd, const void *buf, size_t len) {
    if (fd < 0) {
        return 0;
    }
    const char *p = (const char *)buf;
    size_t off = 0;
    while (off < len) {
        ssize_t w = write(fd, p + off, len - off);
        if (w > 0) {
            off += (size_t)w;
            continue;
        }
        if (w < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
            break;
        }
        LOGI("uds write fail errno=%d sent=%zu/%zu", errno, off, len);
        // drop dead client
        int cur = g_uds_client.load();
        if (cur == fd && g_uds_client.compare_exchange_strong(cur, -1)) {
            close(fd);
            g_uds_hdr_sent.store(false);
        }
        break;
    }
    return off;
}

static bool uds_send_hdr_if_needed(int fd, unsigned rate, unsigned channels, unsigned kind) {
    if (fd < 0 || g_uds_hdr_sent.load()) {
        return fd >= 0;
    }
    unsigned char hdr[16] = {};
    put_u32_le(hdr + 0, APCM_MAGIC);
    put_u32_le(hdr + 4, rate);
    put_u16_le(hdr + 8, channels);
    put_u16_le(hdr + 10, 16);
    put_u16_le(hdr + 12, kind); // APCM_KIND_*
    ssize_t w = write(fd, hdr, sizeof(hdr));
    if (w != (ssize_t)sizeof(hdr)) {
        LOGI("uds hdr write rc=%zd errno=%d", w, errno);
        return false;
    }
    g_uds_hdr_sent.store(true);
    LOGI("uds hdr sent fd=%d %uHz ch%u kind=%u", fd, rate, channels, kind);
    return true;
}

static void uds_on_accept(int cfd) {
    int flags = fcntl(cfd, F_GETFL, 0);
    if (flags >= 0) {
        fcntl(cfd, F_SETFL, flags | O_NONBLOCK);
    }
    int old = g_uds_client.exchange(cfd);
    g_uds_hdr_sent.store(false);
    g_ai_mute_mic.store(false);
    apply_mixer_mic_mute(false);
    if (old >= 0) {
        close(old);
    }
    LOGI("uds client accepted fd=%d", cfd);
}

static void *uds_accept_loop(void *arg) {
    int listen_fd = (int)(intptr_t)arg;
    while (true) {
        int cfd = accept(listen_fd, nullptr, nullptr);
        if (cfd < 0) {
            if (errno == EINTR) {
                continue;
            }
            LOGI("uds accept errno=%d", errno);
            break;
        }
        uds_on_accept(cfd);
    }
    close(listen_fd);
    return nullptr;
}

static void *uds_server_thread(void *) {
    // 1) Filesystem sock — Go/ai_call transitional clients
    unlink(PCM_SOCK_PATH);
    int fs_fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fs_fd >= 0) {
        struct sockaddr_un addr {};
        addr.sun_family = AF_UNIX;
        strncpy(addr.sun_path, PCM_SOCK_PATH, sizeof(addr.sun_path) - 1);
        if (bind(fs_fd, (struct sockaddr *)&addr, sizeof(addr)) == 0) {
            chmod(PCM_SOCK_PATH, 0666);
            if (listen(fs_fd, 2) == 0) {
                LOGI("uds listening filesystem %s", PCM_SOCK_PATH);
                pthread_t t;
                pthread_create(&t, nullptr, uds_accept_loop, (void *)(intptr_t)fs_fd);
                pthread_detach(t);
                fs_fd = -1; // owned by accept loop
            } else {
                LOGI("uds fs listen errno=%d", errno);
                close(fs_fd);
            }
        } else {
            LOGI("uds fs bind(%s) errno=%d", PCM_SOCK_PATH, errno);
            close(fs_fd);
        }
    }

    // 2) Abstract sock — App (avoids vendor_data_file sock_file write)
    int abs_fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (abs_fd < 0) {
        LOGI("uds abstract socket errno=%d", errno);
        return nullptr;
    }
    struct sockaddr_un aaddr {};
    aaddr.sun_family = AF_UNIX;
    aaddr.sun_path[0] = '\0';
    strncpy(aaddr.sun_path + 1, PCM_SOCK_ABSTRACT, sizeof(aaddr.sun_path) - 2);
    socklen_t alen =
        (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + strlen(PCM_SOCK_ABSTRACT));
    if (bind(abs_fd, (struct sockaddr *)&aaddr, alen) != 0) {
        LOGI("uds abstract bind(@%s) errno=%d", PCM_SOCK_ABSTRACT, errno);
        close(abs_fd);
        return nullptr;
    }
    if (listen(abs_fd, 2) != 0) {
        LOGI("uds abstract listen errno=%d", errno);
        close(abs_fd);
        return nullptr;
    }
    LOGI("uds listening abstract @%s", PCM_SOCK_ABSTRACT);
    uds_accept_loop((void *)(intptr_t)abs_fd);
    return nullptr;
}

static void start_uds_server() {
    pthread_t t;
    pthread_create(&t, nullptr, uds_server_thread, nullptr);
    pthread_detach(t);
}

static void *round7_incall_thread(void *) {
    usleep(400 * 1000);
    if (!g_in_voice.load()) {
        return nullptr;
    }

    void *adev = g_adev.load();
    void *platform = g_platform.load();
    unsigned vsid = g_vsid.load();
    if (fn_voice_sid && adev) {
        unsigned sid = fn_voice_sid(adev);
        LOGI("voice_get_active_session_id => 0x%x (cached vsid=0x%x)", sid, vsid);
        if (sid != 0) {
            vsid = sid;
        }
    }
    if (!platform) {
        LOGE("Round-7: no platform ptr yet");
        return nullptr;
    }

    // 1.D': downlink only for STT (remote party).
    LOGI("1.D' enable incall-rec DL-only sess=0x%x mode=%d", vsid, INCALL_REC_DOWNLINK);
    if (fn_set_incall_sess) {
        int rc = fn_set_incall_sess(platform, vsid, INCALL_REC_DOWNLINK);
        LOGI("platform_set_incall_recording_session_id rc=%d", rc);
    }

    auto mixer_open = (mixer_open_fn_t)resolve_sym("libtinyalsa.so", "mixer_open");
    auto mixer_close = (mixer_close_fn_t)resolve_sym("libtinyalsa.so", "mixer_close");
    auto get_ctl = (mixer_get_ctl_fn_t)resolve_sym("libtinyalsa.so", "mixer_get_ctl_by_name");
    auto set_val = (mixer_ctl_set_fn_t)resolve_sym("libtinyalsa.so", "mixer_ctl_set_value");
    auto pcm_open = (pcm_open_fn_t)resolve_sym("libtinyalsa.so", "pcm_open");
    auto pcm_read = (pcm_io_fn_t)resolve_sym("libtinyalsa.so", "pcm_read");
    auto pcm_close = (pcm_close_fn_t)resolve_sym("libtinyalsa.so", "pcm_close");
    auto pcm_ready = (pcm_is_ready_fn_t)resolve_sym("libtinyalsa.so", "pcm_is_ready");
    auto pcm_err = (pcm_get_error_fn_t)resolve_sym("libtinyalsa.so", "pcm_get_error");
    auto pcm_start = (pcm_start_fn_t)resolve_sym("libtinyalsa.so", "pcm_start");

    if (!mixer_open || !get_ctl || !set_val || !pcm_open || !pcm_read || !pcm_close || !pcm_ready) {
        LOGE("Round-7: missing tinyalsa syms");
        return nullptr;
    }

    void *mixer = mixer_open(0);
    if (!mixer) {
        LOGE("Round-7: mixer_open failed");
        return nullptr;
    }
    set_mixer_int(mixer, get_ctl, set_val, "MultiMedia9 Mixer VOC_REC_UL", 0);
    set_mixer_int(mixer, get_ctl, set_val, "MultiMedia9 Mixer VOC_REC_DL", 1);

    // Round-6 success path: 48k stereo
    struct pcm_config cfg {};
    load_capture_config(&cfg);
    cfg.channels = 2;
    cfg.rate = 48000;
    if (cfg.period_size == 0) {
        cfg.period_size = 480; // 10ms @ 48k
    }
    if (cfg.period_count == 0) {
        cfg.period_count = 4;
    }
    cfg.format = PCM_FORMAT_S16_LE;

    void *pcm = pcm_open(0, 23, PCM_IN, &cfg);
    if (!pcm || !pcm_ready(pcm)) {
        LOGI("Round-7 pcm not ready: %s", (pcm && pcm_err) ? pcm_err(pcm) : "null");
        if (pcm) {
            pcm_close(pcm);
        }
        mixer_close(mixer);
        return nullptr;
    }
    LOGI("1.D' pcm READY d23 %uHz ch%u (DL-only)", cfg.rate, cfg.channels);
    g_dl_rec_pcm.store(pcm);
    if (pcm_start) {
        LOGI("Round-7 pcm_start rc=%d", pcm_start(pcm));
    }

    int dump_fd = open_dump_fd();
    unsigned bytes = cfg.period_size * cfg.channels * 2;
    void *buf = malloc(bytes);
    if (!buf) {
        {
            void *expected = pcm;
            g_dl_rec_pcm.compare_exchange_strong(expected, nullptr);
        }
        if (dump_fd >= 0) {
            close(dump_fd);
        }
        pcm_close(pcm);
        mixer_close(mixer);
        return nullptr;
    }

    int hits = 0, nonzero = 0;
    int max_l = 0, max_r = 0;
    size_t total_bytes = 0;
    size_t uds_bytes = 0;
    while (g_in_voice.load()) {
        int rc = pcm_read(pcm, buf, bytes);
        if (rc < 0) {
            LOGI("Round-7 pcm_read fail i=%d rc=%d err=%s", hits, rc, pcm_err ? pcm_err(pcm) : "?");
            break;
        }
        hits++;
        if (dump_fd >= 0) {
            ssize_t w = write(dump_fd, buf, bytes);
            if (w > 0) {
                total_bytes += (size_t)w;
            }
        }
        int uds_fd = g_uds_client.load();
        if (uds_fd >= 0 && uds_send_hdr_if_needed(uds_fd, cfg.rate, cfg.channels, APCM_KIND_DL)) {
#if NEXUS_UDS_FRAMED
            size_t frame_cap = 4 + (size_t)bytes;
            auto *frame = (uint8_t *)malloc(frame_cap);
            if (frame) {
                ssize_t n = pcm_frame_encode(kTypePcmDl, 0, buf, (size_t)bytes, frame, frame_cap);
                if (n > 0) {
                    uds_bytes += uds_send_all(uds_fd, frame, (size_t)n);
                }
                free(frame);
            }
#else
            uds_bytes += uds_send_all(uds_fd, buf, bytes);
#endif
        }
        auto *p = (const int16_t *)buf;
        int frames = (int)(bytes / 4); // stereo s16
        int frame_nz = 0;
        for (int f = 0; f < frames; ++f) {
            int l = p[f * 2];
            int r = p[f * 2 + 1];
            int al = l < 0 ? -l : l;
            int ar = r < 0 ? -r : r;
            if (al > max_l) {
                max_l = al;
            }
            if (ar > max_r) {
                max_r = ar;
            }
            if (l != 0 || r != 0) {
                frame_nz = 1;
            }
        }
        if (frame_nz) {
            nonzero++;
        }
        if (hits <= 8 || (hits % 50) == 0) {
            LOGI("Round-7 pcm_read #%d bytes=%u nz=%d maxL=%d maxR=%d", hits, bytes, frame_nz, max_l,
                 max_r);
        }
    }

    LOGI("1.D' DL DONE hits=%d nz=%d maxL=%d maxR=%d dumped=%zu uds=%zu rate=%u ch=2 s16le", hits,
         nonzero, max_l, max_r, total_bytes, uds_bytes, cfg.rate);
    {
        void *expected = pcm;
        g_dl_rec_pcm.compare_exchange_strong(expected, nullptr);
    }
    if (dump_fd >= 0) {
        close(dump_fd);
    }
    free(buf);
    pcm_close(pcm);
    mixer_close(mixer);
    LOGI("Round-7 incall thread exit");
    return nullptr;
}

static void start_round7() {
    pthread_t t;
    pthread_create(&t, nullptr, round7_incall_thread, nullptr);
    pthread_detach(t);
}

// 1.E: incall-music uplink TX — pcmC0D23p + Incall_Music Audio Mixer MultiMedia9.
static void *tx_incall_music_thread(void *) {
    usleep(600 * 1000); // after voice path settles; slightly after DL thread
    if (!g_in_voice.load()) {
        return nullptr;
    }

    void *platform = g_platform.load();
    if (!platform) {
        LOGE("1.E: no platform ptr");
        return nullptr;
    }

    LOGI("1.E start incall-music uplink (test tone)");
    if (fn_start_incall_music) {
        int rc = fn_start_incall_music(platform);
        LOGI("platform_start_incall_music_usecase rc=%d", rc);
    } else {
        LOGE("1.E: platform_start_incall_music_usecase missing");
    }

    auto mixer_open = (mixer_open_fn_t)resolve_sym("libtinyalsa.so", "mixer_open");
    auto mixer_close = (mixer_close_fn_t)resolve_sym("libtinyalsa.so", "mixer_close");
    auto get_ctl = (mixer_get_ctl_fn_t)resolve_sym("libtinyalsa.so", "mixer_get_ctl_by_name");
    auto set_val = (mixer_ctl_set_fn_t)resolve_sym("libtinyalsa.so", "mixer_ctl_set_value");
    auto pcm_open = (pcm_open_fn_t)resolve_sym("libtinyalsa.so", "pcm_open");
    auto pcm_write = (pcm_io_fn_t)resolve_sym("libtinyalsa.so", "pcm_write");
    auto pcm_close = (pcm_close_fn_t)resolve_sym("libtinyalsa.so", "pcm_close");
    auto pcm_ready = (pcm_is_ready_fn_t)resolve_sym("libtinyalsa.so", "pcm_is_ready");
    auto pcm_err = (pcm_get_error_fn_t)resolve_sym("libtinyalsa.so", "pcm_get_error");
    auto pcm_start = (pcm_start_fn_t)resolve_sym("libtinyalsa.so", "pcm_start");

    if (!mixer_open || !get_ctl || !set_val || !pcm_open || !pcm_write || !pcm_close || !pcm_ready) {
        LOGE("1.E: missing tinyalsa syms");
        return nullptr;
    }

    void *mixer = mixer_open(0);
    if (!mixer) {
        LOGE("1.E: mixer_open failed");
        return nullptr;
    }
    set_mixer_int(mixer, get_ctl, set_val, "Incall_Music Audio Mixer MultiMedia9", 1);

    struct pcm_config cfg {};
    load_incall_music_config(&cfg);
    if (cfg.channels == 0) {
        cfg.channels = 1;
    }
    if (cfg.rate == 0) {
        cfg.rate = 48000;
    }
    if (cfg.period_size == 0) {
        cfg.period_size = 480;
    }
    if (cfg.period_count == 0) {
        cfg.period_count = 4;
    }
    cfg.format = PCM_FORMAT_S16_LE;

    // platform XML: USECASE_INCALL_MUSIC_UPLINK out id=23 → pcmC0D23p
    void *pcm = pcm_open(0, 23, PCM_OUT, &cfg);
    if (!pcm || !pcm_ready(pcm)) {
        LOGI("1.E pcm OUT d23 not ready: %s", (pcm && pcm_err) ? pcm_err(pcm) : "null");
        if (pcm) {
            pcm_close(pcm);
        }
        set_mixer_int(mixer, get_ctl, set_val, "Incall_Music Audio Mixer MultiMedia9", 0);
        mixer_close(mixer);
        if (fn_stop_incall_music) {
            fn_stop_incall_music(platform);
        }
        return nullptr;
    }
    LOGI("1.E pcm READY OUT d23 %uHz ch%u (incall-music)", cfg.rate, cfg.channels);
    if (pcm_start) {
        LOGI("1.E pcm_start rc=%d", pcm_start(pcm));
    }

    unsigned frame_bytes = cfg.period_size * cfg.channels * 2;
    auto *buf = (int16_t *)malloc(frame_bytes);
    if (!buf) {
        pcm_close(pcm);
        set_mixer_int(mixer, get_ctl, set_val, "Incall_Music Audio Mixer MultiMedia9", 0);
        mixer_close(mixer);
        if (fn_stop_incall_music) {
            fn_stop_incall_music(platform);
        }
        return nullptr;
    }

    TxInjectQ inj {};
    txq_init(&inj);
    UdsFrameParser parser {};
    double phase = 0;
    int hits = 0;
    size_t total = 0;
    size_t audible = 0; // non-silence periods
    LOGI("1.E TX mode: framed UDS UL; file_inject=%d tone_flag=%s", NEXUS_TX_INJECT_FILE, TX_TONE_PATH);

    while (g_in_voice.load()) {
#if NEXUS_TX_INJECT_FILE
        if ((hits % 25) == 0) {
            txq_try_load_file(&inj);
        }
#endif
        uds_poll_client_frames(&parser, &inj);
        bool tone = (access(TX_TONE_PATH, F_OK) == 0);
        bool got = false;
        if (tone) {
            fill_tone_s16(buf, (int)cfg.period_size, (int)cfg.channels, cfg.rate, &phase);
            got = true;
        } else {
            got = txq_pull(&inj, buf, frame_bytes);
            if (!got) {
                memset(buf, 0, frame_bytes);
            }
        }
        int rc = pcm_write(pcm, buf, frame_bytes);
        if (rc < 0) {
            LOGI("1.E pcm_write fail i=%d rc=%d err=%s", hits, rc, pcm_err ? pcm_err(pcm) : "?");
            break;
        }
        hits++;
        total += frame_bytes;
        if (got) {
            audible++;
        }
        if (hits <= 8 || (hits % 200) == 0) {
            LOGI("1.E pcm_write #%d bytes=%u total=%zu audible_periods=%zu tone=%d", hits, frame_bytes,
                 total, audible, (int)tone);
        }
    }

    LOGI("1.E TX DONE hits=%d written=%zu audible_periods=%zu", hits, total, audible);
    uds_parser_reset(&parser);
    g_ai_mute_mic.store(false);
    apply_mixer_mic_mute(false);
    txq_destroy(&inj);
    free(buf);
    pcm_close(pcm);
    set_mixer_int(mixer, get_ctl, set_val, "Incall_Music Audio Mixer MultiMedia9", 0);
    mixer_close(mixer);
    if (fn_stop_incall_music && platform) {
        int rc = fn_stop_incall_music(platform);
        LOGI("platform_stop_incall_music_usecase rc=%d", rc);
    }
    LOGI("1.E incall-music thread exit");
    return nullptr;
}

static void start_tx_incall_music() {
    pthread_t t;
    pthread_create(&t, nullptr, tx_incall_music_thread, nullptr);
    pthread_detach(t);
}

static void cleanup_incall_mixer() {
    auto mixer_open = (mixer_open_fn_t)resolve_sym("libtinyalsa.so", "mixer_open");
    auto mixer_close = (mixer_close_fn_t)resolve_sym("libtinyalsa.so", "mixer_close");
    auto get_ctl = (mixer_get_ctl_fn_t)resolve_sym("libtinyalsa.so", "mixer_get_ctl_by_name");
    auto set_val = (mixer_ctl_set_fn_t)resolve_sym("libtinyalsa.so", "mixer_ctl_set_value");
    if (!mixer_open || !get_ctl || !set_val) {
        return;
    }
    void *mixer = mixer_open(0);
    if (!mixer) {
        return;
    }
    set_mixer_int(mixer, get_ctl, set_val, "MultiMedia9 Mixer VOC_REC_UL", 0);
    set_mixer_int(mixer, get_ctl, set_val, "MultiMedia9 Mixer VOC_REC_DL", 0);
    set_mixer_int(mixer, get_ctl, set_val, "Incall_Music Audio Mixer MultiMedia9", 0);
    mixer_close(mixer);
    void *platform = g_platform.load();
    if (fn_stop_incall && platform) {
        int rc = fn_stop_incall(platform);
        LOGI("platform_stop_incall_recording_usecase rc=%d", rc);
    }
    if (fn_stop_incall_music && platform) {
        int rc = fn_stop_incall_music(platform);
        LOGI("platform_stop_incall_music_usecase (cleanup) rc=%d", rc);
    }
}

static voice_fn1_t orig_voice_start_call = nullptr;
static voice_fn2_t orig_voice_start_uc = nullptr;
static voice_fn2_t orig_voice_stop_uc = nullptr;

static int fake_voice_start_call(void *adev) {
    g_adev.store(adev);
    LOGI("voice_start_call adev=%p", adev);
    return orig_voice_start_call(adev);
}
static int fake_voice_start_usecase(void *adev, int usecase) {
    g_adev.store(adev);
    LOGI("voice_start_usecase adev=%p usecase=%d", adev, usecase);
    int rc = orig_voice_start_uc(adev, usecase);
    g_in_voice.store(true);
    repair_voice_ul_mixers();
    // Mic capture often opens before voice_start; promote last PCM_IN as UL.
    if (g_voice_ul_pcm.load() == nullptr) {
        void *cand = g_last_capture_pcm.load();
        void *dl = g_dl_rec_pcm.load();
        if (cand != nullptr && cand != dl) {
            void *expected = nullptr;
            if (g_voice_ul_pcm.compare_exchange_strong(expected, cand)) {
                LOGI("promote pre-voice capture as UL mic pcm=%p", cand);
            }
        }
    }
    start_round7();
    start_tx_incall_music();
    return rc;
}
static int fake_voice_stop_usecase(void *adev, int usecase) {
    LOGI("voice_stop_usecase adev=%p usecase=%d", adev, usecase);
    g_in_voice.store(false);
    cleanup_incall_mixer();
    return orig_voice_stop_uc(adev, usecase);
}

static stream_fn_t orig_start_in = nullptr;
static int fake_start_in(void *s) {
    LOGI("start_input_stream s=%p", s);
    return orig_start_in(s);
}

static pcm_open_fn_t orig_pcm_open = nullptr;
static void *fake_pcm_open(unsigned int card, unsigned int device, unsigned int flags,
                           const struct pcm_config *config) {
    void *pcm = orig_pcm_open(card, device, flags, config);
    if (pcm && (flags & PCM_IN)) {
        LOGI("pcm_open IN pcm=%p card=%u dev=%u flags=0x%x in_voice=%d", pcm, card, device, flags,
             (int)g_in_voice.load());
        if (device != 23) {
            g_last_capture_pcm.store(pcm);
            if (g_in_voice.load()) {
                // Heuristic: first non-DL (d23) capture during a call is UL mic.
                void *expected = nullptr;
                if (g_voice_ul_pcm.compare_exchange_strong(expected, pcm)) {
                    LOGI("track voice UL mic pcm=%p card=%u dev=%u", pcm, card, device);
                }
            }
        }
    }
    return pcm;
}

static pcm_close_fn_t orig_pcm_close = nullptr;
static int fake_pcm_close(void *pcm) {
    void *ul = pcm;
    g_voice_ul_pcm.compare_exchange_strong(ul, nullptr);
    void *last = pcm;
    g_last_capture_pcm.compare_exchange_strong(last, nullptr);
    return orig_pcm_close(pcm);
}

static void install_hooks() {
    const char *tiny = "libtinyalsa.so";
    const char *prim = "audio.primary.kona.so";

    hook_addr("pcm_write", resolve_sym(tiny, "pcm_write"), (void *)fake_pcm_write, (void **)&orig_pcm_write);
    hook_addr("pcm_read", resolve_sym(tiny, "pcm_read"), (void *)fake_pcm_read, (void **)&orig_pcm_read);
    hook_addr("pcm_open", resolve_sym(tiny, "pcm_open"), (void *)fake_pcm_open, (void **)&orig_pcm_open);
    hook_addr("pcm_close", resolve_sym(tiny, "pcm_close"), (void *)fake_pcm_close, (void **)&orig_pcm_close);

    hook_addr("voice_start_call", resolve_sym(prim, "voice_start_call"), (void *)fake_voice_start_call,
              (void **)&orig_voice_start_call);
    hook_addr("voice_start_usecase", resolve_sym(prim, "voice_start_usecase"),
              (void *)fake_voice_start_usecase, (void **)&orig_voice_start_uc);
    hook_addr("voice_stop_usecase", resolve_sym(prim, "voice_stop_usecase"),
              (void *)fake_voice_stop_usecase, (void **)&orig_voice_stop_uc);
    hook_addr("platform_start_voice_call", resolve_sym(prim, "platform_start_voice_call"),
              (void *)fake_plat_start_voice, (void **)&orig_plat_start_voice);
    hook_addr("platform_set_incall_recording_session_id",
              resolve_sym(prim, "platform_set_incall_recording_session_id"), (void *)fake_set_incall_sess,
              (void **)&orig_set_incall_sess);
    hook_addr("start_input_stream", resolve_sym(prim, "start_input_stream"), (void *)fake_start_in,
              (void **)&orig_start_in);

    fn_set_incall_sess =
        (set_incall_sess_fn_t)resolve_sym(prim, "platform_set_incall_recording_session_id");
    // prefer original if hooked
    if (orig_set_incall_sess) {
        fn_set_incall_sess = orig_set_incall_sess;
    }
    fn_stop_incall = (stop_incall_fn_t)resolve_sym(prim, "platform_stop_incall_recording_usecase");
    fn_start_incall_music = (incall_music_fn_t)resolve_sym(prim, "platform_start_incall_music_usecase");
    fn_stop_incall_music = (incall_music_fn_t)resolve_sym(prim, "platform_stop_incall_music_usecase");
    fn_voice_sid = (voice_sid_fn_t)resolve_sym(prim, "voice_get_active_session_id");

    start_uds_server();
    LOGI("HAL 1.D'+1.E hooks done (framed UDS DL/UL + soft-mute) sock=%s", PCM_SOCK_PATH);
}

static void *main_thread(void *) {
    usleep(2000 * 1000);
    LOGI("HAL inject OK, pid=%d abi=32", getpid());
    install_hooks();
    return nullptr;
}

__attribute__((constructor)) static void on_load() {
    pthread_t t;
    pthread_create(&t, nullptr, main_thread, nullptr);
    pthread_detach(t);
}
