#include <android/log.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <pthread.h>
#include <unistd.h>
#include <atomic>
#include <cerrno>
#include <cstdlib>
#include <cstring>

#include "dobby.h"

#define LOG_TAG "AI_Audio_Hook"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Round-7: UL+DL incall-rec (bidirectional), dump PCM until hangup, log L/R max-abs.

using pcm_io_fn_t = int (*)(void *pcm, void *data, unsigned int count);
using voice_fn1_t = int (*)(void *adev);
using voice_fn2_t = int (*)(void *adev, int usecase);
using platform_voice_fn_t = int (*)(void *platform, unsigned int vsid);
using set_incall_sess_fn_t = int (*)(void *platform, unsigned int session_id, int rec_mode);
using stop_incall_fn_t = int (*)(void *platform);
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

static int fake_pcm_write(void *pcm, void *data, unsigned int count) {
    int rc = orig_pcm_write(pcm, data, count);
    log_count("pcm_write", g_pcm_w, count, rc);
    return rc;
}
static int fake_pcm_read(void *pcm, void *data, unsigned int count) {
    int rc = orig_pcm_read(pcm, data, count);
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

static int open_dump_fd() {
    // Prefer vendor_data_file path; HAL needs sepolicy allow (see sepolicy.rule).
    const char *paths[] = {"/data/vendor/ai_hook/ai_incall.pcm", "/data/local/tmp/ai_incall.pcm",
                           "/data/adb/modules/ai_audio_hook/ai_incall.pcm"};
    for (const char *p : paths) {
        // Existing pre-created file (chmod 666) first — avoids create permission.
        int fd = open(p, O_WRONLY | O_TRUNC);
        if (fd < 0) {
            fd = open(p, O_CREAT | O_TRUNC | O_WRONLY, 0666);
        }
        if (fd >= 0) {
            LOGI("Round-8 dump fd=%d path=%s", fd, p);
            return fd;
        }
        LOGI("Round-8 open(%s) failed errno=%d", p, errno);
    }
    return -1;
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

    // Bidirectional: UL+DL session mode and both mixer taps.
    LOGI("Round-7 enable incall-rec BIDIR sess=0x%x mode=%d (UL+DL)", vsid,
         INCALL_REC_UPLINK_AND_DOWNLINK);
    if (fn_set_incall_sess) {
        int rc = fn_set_incall_sess(platform, vsid, INCALL_REC_UPLINK_AND_DOWNLINK);
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
    set_mixer_int(mixer, get_ctl, set_val, "MultiMedia9 Mixer VOC_REC_UL", 1);
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
    LOGI("Round-7 pcm READY d23 %uHz ch%u (UL+DL bidir)", cfg.rate, cfg.channels);
    if (pcm_start) {
        LOGI("Round-7 pcm_start rc=%d", pcm_start(pcm));
    }

    int dump_fd = open_dump_fd();
    unsigned bytes = cfg.period_size * cfg.channels * 2;
    void *buf = malloc(bytes);
    if (!buf) {
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

    LOGI("Round-8 DONE hits=%d nz=%d maxL=%d maxR=%d dumped=%zu rate=%u ch=2 s16le", hits, nonzero,
         max_l, max_r, total_bytes, cfg.rate);
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
    mixer_close(mixer);
    void *platform = g_platform.load();
    if (fn_stop_incall && platform) {
        int rc = fn_stop_incall(platform);
        LOGI("platform_stop_incall_recording_usecase rc=%d", rc);
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
    start_round7();
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

static void install_hooks() {
    const char *tiny = "libtinyalsa.so";
    const char *prim = "audio.primary.kona.so";

    hook_addr("pcm_write", resolve_sym(tiny, "pcm_write"), (void *)fake_pcm_write, (void **)&orig_pcm_write);
    hook_addr("pcm_read", resolve_sym(tiny, "pcm_read"), (void *)fake_pcm_read, (void **)&orig_pcm_read);

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
    fn_voice_sid = (voice_sid_fn_t)resolve_sym(prim, "voice_get_active_session_id");

    LOGI("HAL Round-8 hooks done (dump via vendor_data_file /data/vendor/ai_hook)");
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
