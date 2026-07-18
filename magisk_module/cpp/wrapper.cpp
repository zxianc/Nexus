#include <cstdlib>
#include <unistd.h>

int main(int argc, char *argv[]) {
    // 强行把我们的库塞进环境变量
    setenv("LD_PRELOAD", "/system/lib64/libai_hook.so", 1);

    // 偷天换日：用原来的参数，去执行被我们改名后的原生服务
    execv("/system/bin/audioserver_real", argv);

    return 0; // 如果 execv 成功，永远不会执行到这里
}
