#include <cstdio>
#include <unistd.h>
#include <android/log.h>
#include "inject.h"

#define LOG_TAG "AI_Inject"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

int main(int argc, char **argv) {
    if (argc < 3) {
        fprintf(stderr, "usage: %s <process_name> <absolute_so_path>\n", argv[0]);
        return 1;
    }
    const char *proc = argv[1];
    const char *so = argv[2];
    for (int i = 0; i < 90; i++) {
        pid_t pid = find_pid_by_name(proc);
        if (pid > 0) {
            LOGI("found %s pid=%d, injecting %s", proc, pid, so);
            return inject_library(pid, so) ? 0 : 2;
        }
        sleep(1);
    }
    LOGE("process %s not found", proc);
    return 3;
}
