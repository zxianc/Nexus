#include <android/log.h>
#include <pthread.h>
#include <unistd.h>

#define LOG_TAG "AI_Audio_Hook"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// 核心业务线程
void* main_thread(void* arg) {
    LOGI("【Native 注入成功】已潜入 audioserver 进程！当前 PID: %d", getpid());
    // 后续我们将在这里初始化 Dobby
    return nullptr;
}

// 神奇的构造函数宏，只要库被加载，立刻自动执行
__attribute__((constructor)) void on_load() {
    pthread_t thread;
    // 必须开新线程，否则会卡死原本 audioserver 的主流程
    pthread_create(&thread, nullptr, main_thread, nullptr);
}
