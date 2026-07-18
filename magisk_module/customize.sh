#!/system/bin/sh
SKIPUNZIP=0

ui_print "- 正在安装 AI 音频拦截系统 (C++ 替身版)..."

# Magisk 会自动把包里的 system/lib64/libai_hook.so 解压到对应位置
# 我们只需要处理 /system/bin 下的可执行文件

mkdir -p $MODPATH/system/bin

# 1. 提取手机里真正的原生 audioserver，放进我们的模块并改名
ui_print "- 提取系统原生 audioserver..."
cp /system/bin/audioserver $MODPATH/system/bin/audioserver_real

# 2. 模块包里已带有我们的 C++ 替身 system/bin/audioserver
#    这里再确认一次权限即可（文件由 Magisk 从 zip 解压）
ui_print "- 部署 ELF 二进制替身..."
if [ ! -f "$MODPATH/system/bin/audioserver" ]; then
    ui_print "! 缺少 audioserver 替身，安装失败"
    abort "! missing audioserver wrapper"
fi

# 3. 设置极其严格的系统级权限 (root:shell 权限和专属上下文)
ui_print "- 修复安全上下文 (SELinux)..."

# 语法：set_perm <目标> <所有者> <用户组> <权限> [SELinux标签]
# 0 代表 root，2000 代表 shell
set_perm $MODPATH/system/bin/audioserver 0 2000 0755 u:object_r:audioserver_exec:s0
set_perm $MODPATH/system/bin/audioserver_real 0 2000 0755 u:object_r:audioserver_exec:s0

ui_print "- 底层注入框架部署完毕！"