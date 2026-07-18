#include <android/log.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdarg.h>
#include <unistd.h>
#include <atomic>
#include <cstring>

#include "dobby.h"

#define LOG_TAG "AI_Audio_Hook"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// openat: framework sanity (noisy). Keep off while hunting PCM.
#ifndef AI_HOOK_OPENAT_PROBE
#define AI_HOOK_OPENAT_PROBE 0
#endif

// Round-1 datapath symbols: resolved OK but call path did not hit (likely vtable).
#ifndef AI_HOOK_DATAPATH_COUNT
#define AI_HOOK_DATAPATH_COUNT 0
#endif

// Round-2: FastMixer / mixer uses MonoPipe in libnbaio (non-virtual, concrete).
#ifndef AI_HOOK_MONOPIPE_COUNT
#define AI_HOOK_MONOPIPE_COUNT 1
#endif

static bool hook_symbol(const char *image, const char *sym, void *replace, void **orig_out) {
    void *addr = DobbySymbolResolver(image, sym);
    if (!addr) {
        LOGI("resolve failed: %s!%s", image ? image : "*", sym);
        return false;
    }
    LOGI("resolved %s!%s @%p", image ? image : "*", sym, addr);
    *orig_out = nullptr;
    int rc = DobbyHook(addr, (dobby_dummy_func_t)replace, (dobby_dummy_func_t *)orig_out);
    LOGI("DobbyHook(%s) rc=%d orig=%p", sym, rc, *orig_out);
    return rc == 0 && *orig_out != nullptr;
}

// ============================================================================
// openat probe
// ============================================================================

#if AI_HOOK_OPENAT_PROBE
using openat_fn_t = int (*)(int, const char *, int, ...);
static openat_fn_t orig_openat = nullptr;
static std::atomic<int> g_openat_hits{0};
static thread_local bool g_in_openat_hook = false;

static int fake_openat(int dirfd, const char *pathname, int flags, ...) {
    mode_t mode = 0;
    va_list ap;
    va_start(ap, flags);
    if (flags & O_CREAT) {
        mode = static_cast<mode_t>(va_arg(ap, int));
    }
    va_end(ap);

    if (!g_in_openat_hook) {
        g_in_openat_hook = true;
        int n = g_openat_hits.fetch_add(1, std::memory_order_relaxed);
        if (n < 20) {
            LOGI("Dobby probe: openat #%d path=%s", n, pathname ? pathname : "(null)");
        } else if (n == 20) {
            LOGI("Dobby probe: openat muted after 20 hits");
        }
        g_in_openat_hook = false;
    }

    if (flags & O_CREAT) {
        return orig_openat(dirfd, pathname, flags, mode);
    }
    return orig_openat(dirfd, pathname, flags);
}

static bool install_openat_probe() {
    if (hook_symbol("libc.so", "openat", (void *)fake_openat, (void **)&orig_openat) ||
        hook_symbol("libc.so", "__openat", (void *)fake_openat, (void **)&orig_openat)) {
        LOGI("Dobby probe installed on openat");
        return true;
    }
    LOGI("Dobby probe install FAILED");
    return false;
}
#endif

// ============================================================================
// Round-1: AudioStreamIn/Out (kept for A/B; call test showed miss)
// ============================================================================

#if AI_HOOK_DATAPATH_COUNT
using stream_in_read_fn_t = ssize_t (*)(void *thiz, void *buffer, size_t bytes, size_t *read);
static stream_in_read_fn_t orig_stream_in_read = nullptr;
static std::atomic<int> g_stream_in_hits{0};

static ssize_t fake_stream_in_read(void *thiz, void *buffer, size_t bytes, size_t *read) {
    ssize_t rc = orig_stream_in_read(thiz, buffer, bytes, read);
    int n = g_stream_in_hits.fetch_add(1, std::memory_order_relaxed);
    if (n < 30 || (n % 200) == 0) {
        size_t got = (read && rc >= 0) ? *read : 0;
        LOGI("count StreamIn::read #%d bytes_req=%zu got=%zu rc=%zd", n, bytes, got, rc);
    }
    return rc;
}

using stream_out_write_fn_t = ssize_t (*)(void *thiz, const void *buffer, size_t bytes);
static stream_out_write_fn_t orig_stream_out_write = nullptr;
static std::atomic<int> g_stream_out_hits{0};

static ssize_t fake_stream_out_write(void *thiz, const void *buffer, size_t bytes) {
    ssize_t rc = orig_stream_out_write(thiz, buffer, bytes);
    int n = g_stream_out_hits.fetch_add(1, std::memory_order_relaxed);
    if (n < 30 || (n % 200) == 0) {
        LOGI("count StreamOut::write #%d bytes=%zu rc=%zd", n, bytes, rc);
    }
    return rc;
}

static void install_datapath_count_hooks() {
    const char *img = "libaudioflinger_datapath.so";
    bool ok_in = hook_symbol(img, "_ZN7android13AudioStreamIn4readEPvmPm", (void *)fake_stream_in_read,
                             (void **)&orig_stream_in_read);
    bool ok_out = hook_symbol(img, "_ZN7android14AudioStreamOut5writeEPKvm", (void *)fake_stream_out_write,
                              (void **)&orig_stream_out_write);
    LOGI("datapath count hooks: IN=%d OUT=%d", ok_in ? 1 : 0, ok_out ? 1 : 0);
}
#endif

// ============================================================================
// Round-2: MonoPipe (mixer <-> FastMixer). Concrete methods in libnbaio.
// ============================================================================

#if AI_HOOK_MONOPIPE_COUNT
// android::MonoPipe::write(const void*, size_t) -> ssize_t
using monopipe_write_fn_t = ssize_t (*)(void *thiz, const void *buffer, size_t frames);
static monopipe_write_fn_t orig_monopipe_write = nullptr;
static std::atomic<int> g_mp_write_hits{0};

static ssize_t fake_monopipe_write(void *thiz, const void *buffer, size_t frames) {
    ssize_t rc = orig_monopipe_write(thiz, buffer, frames);
    int n = g_mp_write_hits.fetch_add(1, std::memory_order_relaxed);
    if (n < 40 || (n % 500) == 0) {
        LOGI("count MonoPipe::write #%d frames=%zu rc=%zd", n, frames, rc);
    }
    return rc;
}

// android::MonoPipeReader::read(void*, size_t) -> ssize_t
using monopipe_read_fn_t = ssize_t (*)(void *thiz, void *buffer, size_t frames);
static monopipe_read_fn_t orig_monopipe_read = nullptr;
static std::atomic<int> g_mp_read_hits{0};

static ssize_t fake_monopipe_read(void *thiz, void *buffer, size_t frames) {
    ssize_t rc = orig_monopipe_read(thiz, buffer, frames);
    int n = g_mp_read_hits.fetch_add(1, std::memory_order_relaxed);
    if (n < 40 || (n % 500) == 0) {
        LOGI("count MonoPipeReader::read #%d frames=%zu rc=%zd", n, frames, rc);
    }
    return rc;
}

static void install_monopipe_count_hooks() {
    const char *img = "libnbaio.so";
    const char *sym_w = "_ZN7android8MonoPipe5writeEPKvm";
    const char *sym_r = "_ZN7android14MonoPipeReader4readEPvm";

    bool ok_w = hook_symbol(img, sym_w, (void *)fake_monopipe_write, (void **)&orig_monopipe_write);
    if (!ok_w) {
        ok_w = hook_symbol(nullptr, sym_w, (void *)fake_monopipe_write, (void **)&orig_monopipe_write);
    }
    bool ok_r = hook_symbol(img, sym_r, (void *)fake_monopipe_read, (void **)&orig_monopipe_read);
    if (!ok_r) {
        ok_r = hook_symbol(nullptr, sym_r, (void *)fake_monopipe_read, (void **)&orig_monopipe_read);
    }
    LOGI("MonoPipe count hooks: write=%d read=%d (1=ok)", ok_w ? 1 : 0, ok_r ? 1 : 0);
}
#endif

static void *main_thread(void *) {
    usleep(1500 * 1000);

    LOGI("Zygisk inject OK, inside audioserver pid=%d", getpid());
    const char *ver = DobbyGetVersion();
    LOGI("Dobby version: %s", (ver && ver[0]) ? ver : "(empty)");

#if AI_HOOK_OPENAT_PROBE
    install_openat_probe();
#else
    LOGI("openat probe disabled");
#endif

#if AI_HOOK_DATAPATH_COUNT
    install_datapath_count_hooks();
#else
    LOGI("datapath count hooks disabled (missed on call)");
#endif

#if AI_HOOK_MONOPIPE_COUNT
    install_monopipe_count_hooks();
#else
    LOGI("MonoPipe count hooks disabled");
#endif
    return nullptr;
}

__attribute__((constructor)) static void on_load() {
    pthread_t t;
    pthread_create(&t, nullptr, main_thread, nullptr);
    pthread_detach(t);
}
