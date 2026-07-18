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

// Probe target: openat is real libc code (unlike clock_gettime which often is VDSO).
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

static bool hook_symbol(const char *image, const char *sym, void *replace, void **orig_out) {
    void *addr = DobbySymbolResolver(image, sym);
    if (!addr) {
        LOGI("resolve failed: %s!%s", image ? image : "*", sym);
        return false;
    }
    LOGI("resolved %s!%s @%p", image ? image : "*", sym, addr);
    *orig_out = nullptr;
    int rc = DobbyHook(addr, (dobby_dummy_func_t)replace, (dobby_dummy_func_t *)orig_out);
    // Use LOGI so `adb logcat -s AI_Audio_Hook:I` always shows result.
    LOGI("DobbyHook(%s) rc=%d orig=%p", sym, rc, *orig_out);
    return rc == 0 && *orig_out != nullptr;
}

static bool install_dobby_probe() {
    const char *ver = DobbyGetVersion();
    LOGI("Dobby version: %s", (ver && ver[0]) ? ver : "(empty)");

    // Prefer openat; fall back to open.
    if (hook_symbol("libc.so", "openat", (void *)fake_openat, (void **)&orig_openat)) {
        LOGI("Dobby probe installed on openat");
        return true;
    }
    if (hook_symbol("libc.so", "__openat", (void *)fake_openat, (void **)&orig_openat)) {
        LOGI("Dobby probe installed on __openat");
        return true;
    }

    LOGI("Dobby probe install FAILED");
    return false;
}

static void *main_thread(void *) {
    // Critical: constructor runs inside dlopen(). Wait until linker unlocks
    // before Dobby allocates trampolines / patches code.
    usleep(1500 * 1000);

    LOGI("Zygisk inject OK, inside audioserver pid=%d", getpid());
    install_dobby_probe();
    return nullptr;
}

__attribute__((constructor)) static void on_load() {
    pthread_t t;
    pthread_create(&t, nullptr, main_thread, nullptr);
    pthread_detach(t);
}
