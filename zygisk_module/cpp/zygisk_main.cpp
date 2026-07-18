#include <android/log.h>
#include <unistd.h>
#include <cstdlib>

#include "zygisk.hpp"
#include "inject.h"

#define LOG_TAG "AI_Zygisk"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using zygisk::Api;
using zygisk::AppSpecializeArgs;
using zygisk::ServerSpecializeArgs;

// audioserver cannot open /data/local/tmp (SELinux). Magisk overlays this path.
static constexpr const char *kPayloadPath = "/system/lib64/libai_hook.so";

class AIAudioZygisk : public zygisk::ModuleBase {
public:
    void onLoad(Api *a, JNIEnv *e) override {
        api = a;
        env = e;
    }

    void preAppSpecialize(AppSpecializeArgs *) override {
        api->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
    }

    void preServerSpecialize(ServerSpecializeArgs *) override {
        LOGI("preServerSpecialize: ask companion to inject audioserver");
        int fd = api->connectCompanion();
        if (fd < 0) {
            LOGE("connectCompanion failed");
        } else {
            int dirfd = api->getModuleDir();
            write(fd, &dirfd, sizeof(dirfd));
            if (dirfd >= 0) {
                api->exemptFd(dirfd);
                close(dirfd);
            }
            int ack = 0;
            read(fd, &ack, sizeof(ack));
            close(fd);
            LOGI("companion ack=%d", ack);
        }
        api->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
    }

private:
    Api *api = nullptr;
    JNIEnv *env = nullptr;
};

static void do_inject_async() {
    // Wait for Magisk overlay + audioserver
    for (int i = 0; i < 90; i++) {
        if (access(kPayloadPath, R_OK) == 0) break;
        sleep(1);
    }
    for (int i = 0; i < 90; i++) {
        pid_t pid = find_pid_by_name("audioserver");
        if (pid > 0) {
            LOGI("audioserver pid=%d, payload=%s", pid, kPayloadPath);
            bool ok = inject_library(pid, kPayloadPath);
            LOGI("inject result=%d", ok ? 1 : 0);
            return;
        }
        sleep(1);
    }
    LOGE("audioserver not found");
}

static void companion_handler(int client) {
    int dirfd = -1;
    read(client, &dirfd, sizeof(dirfd));
    if (dirfd >= 0) close(dirfd);

    pid_t child = fork();
    if (child == 0) {
        setsid();
        do_inject_async();
        _exit(0);
    }

    int ack = 1;
    write(client, &ack, sizeof(ack));
    LOGI("companion forked injector child=%d", child);
}

REGISTER_ZYGISK_MODULE(AIAudioZygisk)
REGISTER_ZYGISK_COMPANION(companion_handler)
