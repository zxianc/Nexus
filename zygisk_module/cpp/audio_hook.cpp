#include <android/log.h>
#include <pthread.h>
#include <unistd.h>

#define LOG_TAG "AI_Audio_Hook"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

static void *main_thread(void *) {
    LOGI("Zygisk inject OK, inside audioserver pid=%d", getpid());
    // TODO: Dobby hook telephony PCM path
    return nullptr;
}

__attribute__((constructor)) static void on_load() {
    pthread_t t;
    pthread_create(&t, nullptr, main_thread, nullptr);
    pthread_detach(t);
}
