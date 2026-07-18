// Remote dlopen injector ? arm64 and arm32 (separate binaries).
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cerrno>
#include <cinttypes>
#include <dirent.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/ptrace.h>
#include <sys/syscall.h>
#include <sys/uio.h>
#include <sys/wait.h>
#include <unistd.h>
#include <csignal>
#include <android/log.h>
#include <linux/elf.h>

#include "inject.h"

#define LOG_TAG "AI_Inject"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#ifndef NT_PRSTATUS
#define NT_PRSTATUS 1
#endif

#if defined(__aarch64__)
using regs_t = user_pt_regs;
#elif defined(__arm__)
// Compat layout used by Android arm32 NT_PRSTATUS.
struct regs_t {
    long uregs[18];
};
#define ARM_r0  uregs[0]
#define ARM_r1  uregs[1]
#define ARM_r2  uregs[2]
#define ARM_r3  uregs[3]
#define ARM_sp  uregs[13]
#define ARM_lr  uregs[14]
#define ARM_pc  uregs[15]
#define ARM_cpsr uregs[16]
#else
#error "unsupported arch"
#endif

static bool ptrace_getregs(pid_t pid, regs_t *regs) {
    iovec iov{regs, sizeof(*regs)};
    return ptrace(PTRACE_GETREGSET, pid, NT_PRSTATUS, &iov) == 0;
}

static bool ptrace_setregs(pid_t pid, regs_t *regs) {
    iovec iov{regs, sizeof(*regs)};
    return ptrace(PTRACE_SETREGSET, pid, NT_PRSTATUS, &iov) == 0;
}

static bool wait_stop(pid_t pid, int *out_status = nullptr) {
    int status = 0;
    alarm(5);
    pid_t w = waitpid(pid, &status, __WALL);
    alarm(0);
    if (w < 0) return false;
    if (out_status) *out_status = status;
    return WIFSTOPPED(status);
}

pid_t find_pid_by_name(const char *name) {
    DIR *dir = opendir("/proc");
    if (!dir) return -1;
    pid_t found = -1;
    while (auto *ent = readdir(dir)) {
        if (ent->d_name[0] < '1' || ent->d_name[0] > '9') continue;
        char path[64];
        snprintf(path, sizeof(path), "/proc/%s/cmdline", ent->d_name);
        int fd = open(path, O_RDONLY);
        if (fd < 0) continue;
        char cmd[256] = {};
        read(fd, cmd, sizeof(cmd) - 1);
        close(fd);
        const char *base = strrchr(cmd, '/');
        base = base ? base + 1 : cmd;
        if (strcmp(cmd, name) == 0 || strcmp(base, name) == 0) {
            found = static_cast<pid_t>(atoi(ent->d_name));
            break;
        }
    }
    closedir(dir);
    return found;
}

static bool already_injected(pid_t pid, const char *needle) {
    char path[64];
    snprintf(path, sizeof(path), "/proc/%d/maps", pid);
    FILE *fp = fopen(path, "r");
    if (!fp) return false;
    char line[512];
    bool hit = false;
    while (fgets(line, sizeof(line), fp)) {
        if (strstr(line, needle)) {
            hit = true;
            break;
        }
    }
    fclose(fp);
    return hit;
}

static uintptr_t module_base(pid_t pid, const char *lib_substr) {
    char path[64];
    snprintf(path, sizeof(path), "/proc/%d/maps", pid);
    FILE *fp = fopen(path, "r");
    if (!fp) return 0;
    char line[512];
    uintptr_t best = 0;
    while (fgets(line, sizeof(line), fp)) {
        if (!strstr(line, lib_substr)) continue;
        uintptr_t start = static_cast<uintptr_t>(strtoull(line, nullptr, 16));
        if (start == 0) continue;
        if (best == 0 || start < best) best = start;
    }
    fclose(fp);
    return best;
}

static uintptr_t local_to_remote(pid_t pid, void *local_fn) {
    if (!local_fn) return 0;
    Dl_info info{};
    if (dladdr(local_fn, &info) == 0 || !info.dli_fbase || !info.dli_fname) {
        LOGE("dladdr failed for %p", local_fn);
        return 0;
    }
    const char *fname = strrchr(info.dli_fname, '/');
    fname = fname ? fname + 1 : info.dli_fname;

    uintptr_t local_base = reinterpret_cast<uintptr_t>(info.dli_fbase);
    uintptr_t remote_base = module_base(pid, fname);
    if (!remote_base) {
        remote_base = module_base(pid, info.dli_fname);
    }
    if (!remote_base) {
        LOGE("remote base not found for %s", info.dli_fname);
        return 0;
    }
    uintptr_t remote = reinterpret_cast<uintptr_t>(local_fn) - local_base + remote_base;
    LOGI("rebase %s %s local=%p base=%" PRIxPTR " -> remote=%" PRIxPTR " (rbase=%" PRIxPTR ")",
         fname, info.dli_sname ? info.dli_sname : "?", local_fn, local_base, remote, remote_base);
    return remote;
}

#if defined(__aarch64__)
static uintptr_t find_brk_gadget(pid_t pid) {
    char path[64];
    snprintf(path, sizeof(path), "/proc/%d/maps", pid);
    FILE *fp = fopen(path, "r");
    if (!fp) return 0;
    char line[512];
    uintptr_t gadget = 0;
    while (fgets(line, sizeof(line), fp)) {
        if (!strstr(line, "r-xp") || !strstr(line, "libc.so")) continue;
        uintptr_t start = 0, end = 0;
        if (sscanf(line, "%" SCNxPTR "-%" SCNxPTR, &start, &end) != 2) continue;
        size_t span = static_cast<size_t>(end - start);
        if (span > 0x20000) span = 0x20000;
        for (size_t off = 0; off + 4 <= span; off += 4) {
            errno = 0;
            long word = ptrace(PTRACE_PEEKTEXT, pid, start + off, nullptr);
            if (errno) break;
            if (static_cast<uint32_t>(word) == 0xD4200000u) {
                gadget = start + off;
                break;
            }
        }
        if (gadget) break;
    }
    fclose(fp);
    return gadget;
}
#else
static uintptr_t find_brk_gadget(pid_t) {
    // ARM32: use LR=0 return trap (same as arm64 fallback).
    return 0;
}
#endif

static bool remote_write(pid_t pid, uintptr_t addr, const void *data, size_t len) {
    iovec local{const_cast<void *>(data), len};
    iovec remote{reinterpret_cast<void *>(addr), len};
    ssize_t n = syscall(__NR_process_vm_writev, pid, &local, 1, &remote, 1, 0);
    if (n == static_cast<ssize_t>(len)) return true;
    LOGI("process_vm_writev fallback (%zd): %s", n, strerror(errno));

    const auto *p = reinterpret_cast<const uint8_t *>(data);
    for (size_t i = 0; i < len; i += sizeof(long)) {
        long word = 0;
        size_t chunk = (len - i) < sizeof(long) ? (len - i) : sizeof(long);
        memcpy(&word, p + i, chunk);
        if (ptrace(PTRACE_POKEDATA, pid, addr + i, word) < 0) {
            LOGE("POKEDATA @%" PRIxPTR " failed: %s", addr + i, strerror(errno));
            return false;
        }
    }
    return true;
}

static long remote_call(pid_t pid, uintptr_t func, uintptr_t ret_addr,
                        uintptr_t a0 = 0, uintptr_t a1 = 0, uintptr_t a2 = 0,
                        uintptr_t a3 = 0, uintptr_t a4 = 0, uintptr_t a5 = 0) {
    regs_t backup{}, regs{};
    if (!ptrace_getregs(pid, &backup)) {
        LOGE("GETREGSET failed: %s", strerror(errno));
        return -1;
    }
    regs = backup;
#if defined(__aarch64__)
    regs.regs[0] = a0;
    regs.regs[1] = a1;
    regs.regs[2] = a2;
    regs.regs[3] = a3;
    regs.regs[4] = a4;
    regs.regs[5] = a5;
    regs.regs[30] = ret_addr;
    regs.pc = func;
    regs.sp &= ~0xFULL;
#else
    // ARM EABI: r0-r3 + stack for 5th/6th (mmap needs fd/offset on stack).
    regs.ARM_r0 = static_cast<long>(a0);
    regs.ARM_r1 = static_cast<long>(a1);
    regs.ARM_r2 = static_cast<long>(a2);
    regs.ARM_r3 = static_cast<long>(a3);
    regs.ARM_lr = static_cast<long>(ret_addr);
    regs.ARM_pc = static_cast<long>(func);
    if (func & 1) {
        regs.ARM_cpsr |= 0x20; // Thumb
        regs.ARM_pc &= ~1L;
    } else {
        regs.ARM_cpsr &= ~0x20L;
    }
    regs.ARM_sp &= ~7L;
    {
        uint32_t stack_args[2] = {static_cast<uint32_t>(a4), static_cast<uint32_t>(a5)};
        regs.ARM_sp -= 8;
        if (!remote_write(pid, static_cast<uintptr_t>(regs.ARM_sp), stack_args, sizeof(stack_args))) {
            LOGE("remote_write stack args failed");
            return -1;
        }
    }
#endif

    if (!ptrace_setregs(pid, &regs)) {
        LOGE("SETREGSET failed: %s", strerror(errno));
        return -1;
    }
    if (ptrace(PTRACE_CONT, pid, nullptr, nullptr) < 0) {
        LOGE("PTRACE_CONT failed: %s", strerror(errno));
        return -1;
    }
    int status = 0;
    if (!wait_stop(pid, &status)) {
        LOGE("wait_stop failed");
        return -1;
    }
    if (!ptrace_getregs(pid, &regs)) {
        LOGE("GETREGSET after call failed");
        return -1;
    }
#if defined(__aarch64__)
    long ret = static_cast<long>(regs.regs[0]);
    LOGI("remote_call func=%" PRIxPTR " sig=%d pc=%" PRIxPTR " x0=%ld",
         func, WSTOPSIG(status), static_cast<uintptr_t>(regs.pc), ret);
#else
    long ret = regs.ARM_r0;
    LOGI("remote_call func=%" PRIxPTR " sig=%d pc=%" PRIxPTR " r0=%ld",
         func, WSTOPSIG(status), static_cast<uintptr_t>(regs.ARM_pc), ret);
#endif
    ptrace_setregs(pid, &backup);
    return ret;
}

bool inject_library(pid_t pid, const char *lib_path) {
    if (already_injected(pid, "libai_hook.so")) {
        LOGI("pid=%d already has libai_hook.so", pid);
        return true;
    }
    if (access(lib_path, R_OK) != 0) {
        LOGE("payload not readable: %s (%s)", lib_path, strerror(errno));
        return false;
    }

    if (ptrace(PTRACE_ATTACH, pid, nullptr, nullptr) < 0) {
        LOGE("PTRACE_ATTACH failed: %s", strerror(errno));
        return false;
    }
    if (!wait_stop(pid)) {
        LOGE("wait after attach failed");
        ptrace(PTRACE_DETACH, pid, nullptr, nullptr);
        return false;
    }

    void *local_mmap = dlsym(RTLD_DEFAULT, "mmap");
    void *local_dlopen = dlsym(RTLD_DEFAULT, "dlopen");
    uintptr_t mmap_addr = local_to_remote(pid, local_mmap);
    uintptr_t dlopen_addr = local_to_remote(pid, local_dlopen);
    uintptr_t ret_gadget = find_brk_gadget(pid);
    if (!ret_gadget) {
        ret_gadget = 0;
        LOGI("BRK gadget not found, using LR=0");
    } else {
        LOGI("BRK gadget @%" PRIxPTR, ret_gadget);
    }

    if (!mmap_addr || !dlopen_addr) {
        LOGE("resolve failed mmap=%" PRIxPTR " dlopen=%" PRIxPTR, mmap_addr, dlopen_addr);
        ptrace(PTRACE_DETACH, pid, nullptr, nullptr);
        return false;
    }

    size_t path_len = strlen(lib_path) + 1;
    size_t alloc_len = (path_len + 0xFFF) & ~static_cast<size_t>(0xFFF);
    auto remote_buf = static_cast<uintptr_t>(remote_call(
        pid, mmap_addr, ret_gadget,
        /*addr*/ 0, /*len*/ alloc_len,
        PROT_READ | PROT_WRITE,
        MAP_PRIVATE | MAP_ANONYMOUS,
        static_cast<uintptr_t>(-1), 0));

    if (remote_buf == 0 || remote_buf == static_cast<uintptr_t>(-1)) {
        LOGE("remote mmap failed (ret=%" PRIxPTR ")", remote_buf);
        ptrace(PTRACE_DETACH, pid, nullptr, nullptr);
        return false;
    }
    LOGI("remote buffer @%" PRIxPTR, remote_buf);

    if (!remote_write(pid, remote_buf, lib_path, path_len)) {
        LOGE("remote write path failed");
        ptrace(PTRACE_DETACH, pid, nullptr, nullptr);
        return false;
    }

    long handle = remote_call(pid, dlopen_addr, ret_gadget, remote_buf, RTLD_NOW);
    LOGI("dlopen(%s) => %ld (0x%lx)", lib_path, handle, static_cast<unsigned long>(handle));

    bool ok = already_injected(pid, "libai_hook.so");
    if (!ok && handle != 0) {
        ok = true;
    }

    ptrace(PTRACE_DETACH, pid, nullptr, nullptr);
    return ok;
}
