#include <jni.h>
#include <android/log.h>
#include <pthread.h>
#include <unistd.h>

#define LOG_TAG "AI_Audio_Hook"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// 当我们的 .so 文件被成功注入或加载到 audioserver 进程时，这个函数会自动执行
void* main_thread(void* arg) {
    LOGI("成功潜入 audioserver 进程！当前 PID: %d", getpid());
    
    // TODO: 在这里初始化 Dobby 框架，开始 Hook AudioRecord::read 和 AudioTrack::write
    
    return nullptr;
}

// C++ 动态库的构造函数属性，确保库一被加载，立刻新建一个线程运行我们的逻辑
__attribute__((constructor))
void on_library_loaded() {
    pthread_t thread;
    pthread_create(&thread, nullptr, main_thread, nullptr);
}