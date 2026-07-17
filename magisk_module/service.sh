#!/system/bin/sh
# 等待系统启动完成
until [ "$(getprop sys.boot_completed)" = "1" ]; do
    sleep 1
done

# TODO: 在这里执行进程注入逻辑，将我们的 libai_hook.so 注入到 audioserver